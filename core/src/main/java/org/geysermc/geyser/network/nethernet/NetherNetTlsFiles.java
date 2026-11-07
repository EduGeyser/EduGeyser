/*
 * Copyright (c) 2026 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.network.nethernet;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.geysermc.geyser.configuration.GeyserConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * TLS material an operator supplies for HTTP signaling, and where it is set:
 * the {@code bedrock.signaling.builtin.https} keys of the config (a PEM chain
 * and key, or a PKCS12 file, with an optional password), or {@code cert.pem}
 * and {@code key.pem} in the {@code nethernet} folder. Every read goes to the
 * files again, so a reload picks up whatever they hold now.
 */
sealed interface NetherNetTlsFiles {

    /** Where the files are set, worded for the log: "the HTTPS certificate from {@link #description}". */
    enum Origin {
        CONFIG("the files set in bedrock.signaling.builtin.https",
                "Fix the files set there, which are reloaded when they change, or empty \"certificate\" there and"
                        + " restart to stop using them"),
        NETHERNET_FOLDER("nethernet/cert.pem and key.pem",
                "Fix or replace them, which are reloaded when they change, or remove both and restart to use"
                        + " an automatic certificate");

        private final String description;
        private final String remedy;

        Origin(String description, String remedy) {
            this.description = description;
            this.remedy = remedy;
        }

        String description() {
            return description;
        }

        String remedy() {
            return remedy;
        }
    }

    /** A private key and its certificate chain, leaf first. */
    record Material(PrivateKey key, List<X509Certificate> chain) {
    }

    Origin origin();

    /** Every file the material is read from; a change to any of them reloads it. */
    List<Path> files();

    /**
     * Reads the material from the files.
     *
     * @throws IOException              if a file cannot be read, with a message that names it and says why
     * @throws GeneralSecurityException if the files hold no usable key or certificate
     */
    Material read() throws IOException, GeneralSecurityException;

    /**
     * The material the config sets, with paths relative to the config folder.
     *
     * @throws IllegalArgumentException if a path is not a valid path
     */
    static NetherNetTlsFiles configured(GeyserConfig.SignalingConfig.BuiltinConfig.HttpsConfig https, Path configFolder) {
        Path certificate = configFolder.resolve(https.certificate().trim());
        NetherNetSecret password = new NetherNetSecret(https.password(), configFolder);
        if (https.privateKey().isBlank()) {
            return new Pkcs12(certificate, password);
        }
        return new Pem(certificate, configFolder.resolve(https.privateKey().trim()), password, Origin.CONFIG);
    }

    /** {@code cert.pem} and {@code key.pem} in the nethernet folder, which take no password. */
    static NetherNetTlsFiles nethernetFolder(Path nethernetDir) {
        return new Pem(nethernetDir.resolve("cert.pem"), nethernetDir.resolve("key.pem"), NetherNetSecret.none(),
                Origin.NETHERNET_FOLDER);
    }

    /**
     * A PEM certificate chain and the PEM private key of its leaf: PKCS#8,
     * SEC1 or PKCS#1, encrypted or not.
     */
    record Pem(Path certificate, Path privateKey, NetherNetSecret password, Origin origin) implements NetherNetTlsFiles {
        /**
         * Decrypting OpenSSL's key formats takes algorithms only BouncyCastle
         * has (its OpenSSL key derivation, AES with PKCS7 padding), so it is
         * asked directly rather than relying on its global registration.
         */
        private static final Provider BOUNCY_CASTLE = new BouncyCastleProvider();

        @Override
        public List<Path> files() {
            return withPasswordFile(password, certificate, privateKey);
        }

        @Override
        public Material read() throws IOException, GeneralSecurityException {
            List<X509Certificate> chain;
            try (InputStream in = new ByteArrayInputStream(readFile(certificate, "certificate"))) {
                chain = CertificateFactory.getInstance("X.509").generateCertificates(in).stream()
                        .map(X509Certificate.class::cast).toList();
            } catch (GeneralSecurityException e) {
                throw new GeneralSecurityException("the certificate " + certificate + " cannot be read ("
                        + e.getMessage() + ")", e);
            }
            if (chain.isEmpty()) {
                throw new GeneralSecurityException("the certificate " + certificate + " holds no certificate");
            }
            return new Material(readKey(), chain);
        }

        /**
         * The first private key in the file. Other blocks, such as EC
         * parameters or certificates kept in the same file, are skipped, as
         * netty's own reader skips them.
         */
        private PrivateKey readKey() throws IOException, GeneralSecurityException {
            String armour = new String(readFile(privateKey, "private key"), StandardCharsets.UTF_8);
            try (PEMParser parser = new PEMParser(new StringReader(armour))) {
                for (Object block; (block = parser.readObject()) != null; ) {
                    PrivateKeyInfo info = switch (block) {
                        case PEMKeyPair pair -> pair.getPrivateKeyInfo();
                        case PrivateKeyInfo plain -> plain;
                        case PKCS8EncryptedPrivateKeyInfo encrypted ->
                                encrypted.decryptPrivateKeyInfo(new JceOpenSSLPKCS8DecryptorProviderBuilder()
                                        .setProvider(BOUNCY_CASTLE).build(passwordChars()));
                        case PEMEncryptedKeyPair encrypted -> encrypted.decryptKeyPair(new JcePEMDecryptorProviderBuilder()
                                .setProvider(BOUNCY_CASTLE).build(passwordChars())).getPrivateKeyInfo();
                        default -> null;
                    };
                    if (info != null) {
                        return new JcaPEMKeyConverter().getPrivateKey(info);
                    }
                }
            } catch (IOException | RuntimeException | PKCSException | OperatorCreationException e) {
                // Decryption with a wrong password lands here too
                throw new GeneralSecurityException("the private key " + privateKey + " cannot be read"
                        + (password.value().isBlank() ? "" : " with the configured password") + " (" + e.getMessage() + ")", e);
            }
            throw new GeneralSecurityException("the file " + privateKey + " holds no private key");
        }

        /** The password of an encrypted key; resolved only then, so a key without one never needs it. */
        private char[] passwordChars() throws GeneralSecurityException {
            String secret;
            try {
                secret = password.resolve();
            } catch (IOException e) {
                throw new GeneralSecurityException(e.getMessage(), e);
            }
            if (secret.isEmpty()) {
                throw new GeneralSecurityException(origin == Origin.NETHERNET_FOLDER
                        ? "the private key " + privateKey + " is encrypted, which nethernet/key.pem cannot be; set"
                                + " the files and their password under bedrock.signaling.builtin.https instead"
                        : "the private key " + privateKey + " is encrypted; set its password in \"password\"");
            }
            return secret.toCharArray();
        }
    }

    /** A PKCS12 file holding a private key and its certificate chain. */
    record Pkcs12(Path keystore, NetherNetSecret password) implements NetherNetTlsFiles {
        @Override
        public Origin origin() {
            return Origin.CONFIG;
        }

        @Override
        public List<Path> files() {
            return withPasswordFile(password, keystore);
        }

        @Override
        public Material read() throws IOException, GeneralSecurityException {
            char[] secret = password.resolve().toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = new ByteArrayInputStream(readFile(keystore, "PKCS12 file"))) {
                store.load(in, secret);
            } catch (IOException | GeneralSecurityException e) {
                // A wrong password is reported as an IOException
                throw new GeneralSecurityException("the PKCS12 file " + keystore + " cannot be opened ("
                        + e.getMessage() + ")", e);
            }
            try {
                for (String alias : Collections.list(store.aliases())) {
                    if (store.isKeyEntry(alias) && store.getKey(alias, secret) instanceof PrivateKey key) {
                        Certificate[] chain = store.getCertificateChain(alias);
                        if (chain != null && chain.length > 0) {
                            List<X509Certificate> certificates = new ArrayList<>();
                            for (Certificate certificate : chain) {
                                certificates.add((X509Certificate) certificate);
                            }
                            return new Material(key, List.copyOf(certificates));
                        }
                    }
                }
            } catch (GeneralSecurityException | ClassCastException e) {
                throw new GeneralSecurityException("the key in the PKCS12 file " + keystore + " cannot be read ("
                        + e.getMessage() + ")", e);
            }
            throw new GeneralSecurityException("the PKCS12 file " + keystore + " holds no private key with a certificate");
        }
    }

    private static List<Path> withPasswordFile(NetherNetSecret password, Path... files) {
        Path passwordFile = password.file();
        return passwordFile == null ? List.of(files) : Stream.concat(Stream.of(files), Stream.of(passwordFile)).toList();
    }

    /** Reads a file whole, with an error that names the file and says why it cannot be read. */
    private static byte[] readFile(Path file, String what) throws IOException {
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw new IOException("the " + what + " " + file + " does not exist", e);
        } catch (AccessDeniedException e) {
            throw new IOException("the " + what + " " + file + " cannot be read with the permissions this server runs with", e);
        } catch (IOException e) {
            throw new IOException("the " + what + " " + file + " cannot be read (" + e.getMessage() + ")", e);
        }
    }
}
