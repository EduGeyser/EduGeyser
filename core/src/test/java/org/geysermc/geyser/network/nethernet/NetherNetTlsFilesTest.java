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

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NetherNetTlsFilesTest {
    private static final char[] PASSWORD = "hunter2".toCharArray();
    /** Encrypting as OpenSSL does takes BouncyCastle's own algorithms. */
    private static final Provider BOUNCY_CASTLE = new BouncyCastleProvider();

    @ParameterizedTest
    @ValueSource(strings = {"pkcs8", "encrypted-pkcs8", "encrypted-sec1"})
    void readsAPemChainAndItsKey(String form, @TempDir Path dir) throws Exception {
        KeyPair key = NetherNetKeyFiles.generateP256();
        X509Certificate certificate = selfSigned(key);
        Files.writeString(dir.resolve("chain.pem"), pem(certificate));
        Files.writeString(dir.resolve("key.pem"), switch (form) {
            case "pkcs8" -> pem(new JcaPKCS8Generator(key.getPrivate(), null));
            case "encrypted-pkcs8" -> pem(new JcaPKCS8Generator(key.getPrivate(),
                    new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC).setProvider(BOUNCY_CASTLE)
                            .setPassword(PASSWORD).build()));
            case "encrypted-sec1" -> encryptedSec1(key);
            default -> throw new AssertionError(form);
        });

        NetherNetTlsFiles.Material material = configured(dir, "chain.pem", "key.pem",
                form.startsWith("encrypted") ? "hunter2" : "").read();

        assertEquals(List.of(certificate), material.chain());
        assertSameKey(key, material);
    }

    @Test
    void readsAPkcs12FileWithItsPasswordFromAFile(@TempDir Path dir) throws Exception {
        KeyPair key = NetherNetKeyFiles.generateP256();
        X509Certificate certificate = selfSigned(key);
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("tls", key.getPrivate(), PASSWORD, new Certificate[]{certificate});
        try (OutputStream out = Files.newOutputStream(dir.resolve("tls.p12"))) {
            store.store(out, PASSWORD);
        }
        Files.writeString(dir.resolve("tls-password"), "hunter2\n");

        NetherNetTlsFiles files = configured(dir, "tls.p12", "", "file:tls-password");

        NetherNetTlsFiles.Pkcs12 pkcs12 = assertInstanceOf(NetherNetTlsFiles.Pkcs12.class, files);
        assertEquals(List.of(dir.resolve("tls.p12"), dir.resolve("tls-password")), pkcs12.files(),
                "a new password is read together with a new file");
        NetherNetTlsFiles.Material material = files.read();
        assertEquals(List.of(certificate), material.chain());
        assertSameKey(key, material);

        Files.writeString(dir.resolve("tls-password"), "wrong");
        GeneralSecurityException refused = assertThrows(GeneralSecurityException.class, files::read);
        assertTrue(refused.getMessage().contains("cannot be opened"), refused.getMessage());
    }

    @Test
    void saysAnEncryptedKeyNeedsItsPassword(@TempDir Path dir) throws Exception {
        KeyPair key = NetherNetKeyFiles.generateP256();
        Files.writeString(dir.resolve("chain.pem"), pem(selfSigned(key)));
        Files.writeString(dir.resolve("key.pem"), encryptedSec1(key));

        GeneralSecurityException missing = assertThrows(GeneralSecurityException.class,
                () -> configured(dir, "chain.pem", "key.pem", "").read());
        assertTrue(missing.getMessage().contains("is encrypted; set its password"), missing.getMessage());

        GeneralSecurityException wrong = assertThrows(GeneralSecurityException.class,
                () -> configured(dir, "chain.pem", "key.pem", "wrong").read());
        assertTrue(wrong.getMessage().contains("cannot be read with the configured password"), wrong.getMessage());

        // The nethernet folder's files take no password, so the advice differs
        Files.createDirectory(dir.resolve("nethernet"));
        Files.copy(dir.resolve("chain.pem"), dir.resolve("nethernet/cert.pem"));
        Files.copy(dir.resolve("key.pem"), dir.resolve("nethernet/key.pem"));
        GeneralSecurityException folder = assertThrows(GeneralSecurityException.class,
                () -> NetherNetTlsFiles.nethernetFolder(dir.resolve("nethernet")).read());
        assertTrue(folder.getMessage().contains("under bedrock.signaling.builtin.https instead"), folder.getMessage());
    }

    @Test
    void namesAMissingFile(@TempDir Path dir) {
        Exception missing = assertThrows(Exception.class, () -> configured(dir, "chain.pem", "key.pem", "").read());

        assertEquals("the certificate " + dir.resolve("chain.pem") + " does not exist", missing.getMessage());
    }

    /** The scalar, as encodings differ in whether they repeat the public point. */
    private static void assertSameKey(KeyPair expected, NetherNetTlsFiles.Material material) {
        assertEquals(((ECPrivateKey) expected.getPrivate()).getS(),
                assertInstanceOf(ECPrivateKey.class, material.key()).getS());
    }

    static NetherNetTlsFiles configured(Path configFolder, String certificate, String privateKey, String password) {
        GeyserConfig.SignalingConfig.BuiltinConfig.HttpsConfig https =
                mock(GeyserConfig.SignalingConfig.BuiltinConfig.HttpsConfig.class);
        when(https.certificate()).thenReturn(certificate);
        when(https.privateKey()).thenReturn(privateKey);
        when(https.password()).thenReturn(password);
        return NetherNetTlsFiles.configured(https, configFolder);
    }

    /** Self signed for an IP address: valid in form, and rejected by every stock client. */
    static X509Certificate selfSigned(KeyPair key) throws Exception {
        X500Name name = new X500Name("CN=test");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE,
                Date.from(now.minus(Duration.ofHours(1))), Date.from(now.plus(Duration.ofDays(1))), name, key.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.iPAddress, "192.0.2.1")));
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(key.getPrivate())));
    }

    static String pem(Object object) throws Exception {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(object);
        }
        return out.toString();
    }

    /**
     * The traditional OpenSSL form, as {@code openssl ec -aes128} writes it: an
     * EC PRIVATE KEY block with Proc-Type and DEK-Info headers, the curve
     * named inside. Built from a SEC1 block, as the JDK's own encoding names
     * the curve only outside, in the PKCS#8 wrapper.
     */
    private static String encryptedSec1(KeyPair key) throws Exception {
        PEMKeyPair sec1;
        try (PEMParser parser = new PEMParser(new StringReader(NetherNetKeyFiles.writeSec1(key)))) {
            sec1 = (PEMKeyPair) parser.readObject();
        }
        return pem(new JcaMiscPEMGenerator(sec1.getPrivateKeyInfo(),
                new JcePEMEncryptorBuilder("AES-128-CBC").setProvider(BOUNCY_CASTLE).build(PASSWORD)));
    }
}
