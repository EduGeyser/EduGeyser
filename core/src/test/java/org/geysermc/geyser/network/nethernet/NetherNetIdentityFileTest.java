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

import io.github.sendablemetatype.netty.util.nethernet.ServerIdentity;
import org.bouncycastle.asn1.ASN1BitString;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.geysermc.geyser.GeyserLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class NetherNetIdentityFileTest {
    private final GeyserLogger logger = mock(GeyserLogger.class);

    @Test
    void movesTheOldKeyToUpstreamsFile(@TempDir Path dir) throws Exception {
        KeyPair key = p384();
        Path legacy = writeLegacy(dir, key);
        String fingerprint = ServerIdentity.load(legacy, "self").fingerprint();

        ServerIdentity identity = NetherNetIdentityFile.load(dir, "self", logger);

        assertFalse(Files.exists(legacy));
        assertEquals(fingerprint, identity.fingerprint(), "the same key keeps the pins players hold");
        assertArrayEquals(key.getPublic().getEncoded(), readLikeUpstream(dir.resolve("identity.pem")).getEncoded());
        verify(logger).info(contains("Moved the server identity key from identity-key.pem to identity.pem"));
        verify(logger).info(contains(identity.fingerprint()));
    }

    @Test
    void usesTheNewFileAndDeletesAnOldOneWithTheSameKey(@TempDir Path dir) throws Exception {
        Path legacy = writeLegacy(dir, p384());
        Path file = dir.resolve("identity.pem");
        ServerIdentity old = ServerIdentity.load(legacy, "self");
        assertTrue(old.writeKeyFile(file));
        String written = Files.readString(file);

        ServerIdentity identity = NetherNetIdentityFile.load(dir, "self", logger);

        assertFalse(Files.exists(legacy));
        assertEquals(old.fingerprint(), identity.fingerprint());
        assertEquals(written, Files.readString(file));
        verify(logger).info(contains("Deleted identity-key.pem, as identity.pem holds the same key"));
    }

    @Test
    void keepsAnOldFileWithAnotherKeyAndWarns(@TempDir Path dir) throws Exception {
        ServerIdentity current = ServerIdentity.loadOrCreate(dir.resolve("identity.pem"), "self");
        Path legacy = writeLegacy(dir, p384());
        String legacyText = Files.readString(legacy);

        ServerIdentity identity = NetherNetIdentityFile.load(dir, "self", logger);

        assertEquals(current.fingerprint(), identity.fingerprint(), "identity.pem wins");
        assertEquals(legacyText, Files.readString(legacy), "the other key is left for the operator to decide");
        verify(logger).warning(contains("Ignoring " + legacy + ": it holds another key"));
    }

    @Test
    void keepsAnOldFileThatCannotBeReadAndWarns(@TempDir Path dir) throws Exception {
        ServerIdentity created = ServerIdentity.loadOrCreate(dir.resolve("identity.pem"), "self");
        Path legacy = dir.resolve("identity-key.pem");
        Files.writeString(legacy, "not a key");

        ServerIdentity identity = NetherNetIdentityFile.load(dir, "self", logger);

        assertEquals(created.fingerprint(), identity.fingerprint());
        assertTrue(Files.exists(legacy));
        verify(logger).warning(contains("Ignoring " + legacy + ", which cannot be read"));
    }

    @Test
    void createsUpstreamsFileWhenThereIsNoKey(@TempDir Path dir) throws Exception {
        ServerIdentity identity = NetherNetIdentityFile.load(dir, "self", logger);

        PublicKey written = readLikeUpstream(dir.resolve("identity.pem"));
        assertEquals(identity.fingerprint(), ServerIdentity.load(dir.resolve("identity.pem"), "self").fingerprint());
        assertArrayEquals(identity.publicKey().getEncoded(), written.getEncoded());
        assertFalse(Files.exists(dir.resolve("identity-key.pem")));
        verify(logger, never()).warning(anyString());
    }

    @Test
    void failsWhenTheOnlyKeyCannotBeRead(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("identity-key.pem");
        Files.writeString(legacy, "not a key");

        // A new key would make every player trust the server again, so the start fails instead
        assertThrows(GeneralSecurityException.class, () -> NetherNetIdentityFile.load(dir, "self", logger));
        assertTrue(Files.exists(legacy));
        assertFalse(Files.exists(dir.resolve("identity.pem")));
    }

    /**
     * Reads a key file the way upstream CloudburstMC/Network's PemKeys.read does
     * (nethernet branch at 14cede4): the first PEM block only, which must be an
     * EC private key that carries its public point, on P-384.
     */
    private static PublicKey readLikeUpstream(Path file) throws Exception {
        Object parsed;
        try (PEMParser parser = new PEMParser(new StringReader(Files.readString(file)))) {
            parsed = parser.readObject();
        }
        PrivateKeyInfo info = parsed instanceof PEMKeyPair pair ? pair.getPrivateKeyInfo() : (PrivateKeyInfo) parsed;
        ASN1BitString point = ECPrivateKey.getInstance(info.parsePrivateKey()).getPublicKey();
        assertNotNull(point, "upstream refuses a key without its public point");
        KeyPair pair = new JcaPEMKeyConverter().getKeyPair(new PEMKeyPair(
                new SubjectPublicKeyInfo(info.getPrivateKeyAlgorithm(), point.getBytes()), info));
        ECPublicKey publicKey = assertInstanceOf(ECPublicKey.class, pair.getPublic());
        assertEquals(384, publicKey.getParams().getCurve().getField().getFieldSize());
        return publicKey;
    }

    /** The two-block file earlier EduGeyser builds wrote: the JDK's PKCS#8, then the SPKI. */
    private static Path writeLegacy(Path dir, KeyPair key) throws Exception {
        Path file = dir.resolve("identity-key.pem");
        Files.writeString(file, pemBlock(key.getPrivate().getEncoded(), "PRIVATE KEY")
                + pemBlock(key.getPublic().getEncoded(), "PUBLIC KEY"));
        return file;
    }

    private static KeyPair p384() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        return generator.generateKeyPair();
    }

    private static String pemBlock(byte[] der, String label) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
