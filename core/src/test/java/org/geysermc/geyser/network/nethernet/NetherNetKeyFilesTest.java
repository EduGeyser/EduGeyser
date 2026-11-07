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

import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.geysermc.geyser.GeyserLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class NetherNetKeyFilesTest {
    private final GeyserLogger logger = mock(GeyserLogger.class);

    @Test
    void writesOneSec1BlockWithThePublicPoint() throws Exception {
        KeyPair key = NetherNetKeyFiles.generateP256();

        String pem = NetherNetKeyFiles.writeSec1(key);

        assertSingleSec1Block(pem);
        assertSameKey(key, NetherNetKeyFiles.parse(pem, "test").pair());
    }

    @Test
    void rewritesAnOlderTwoBlockFileAsTheSameKey(@TempDir Path dir) throws Exception {
        KeyPair key = NetherNetKeyFiles.generateP256();
        Path file = dir.resolve("account-key.pem");
        Files.writeString(file, twoBlocks(key));

        KeyPair loaded = NetherNetKeyFiles.load(file, logger);

        assertSameKey(key, loaded);
        assertSingleSec1Block(Files.readString(file));
        assertSameKey(key, NetherNetKeyFiles.parse(Files.readString(file), "test").pair());
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no temporary file is left behind");
        }
        verify(logger).info(contains("Rewrote account-key.pem as one PEM block"));
    }

    @Test
    void leavesAFileInTheOneFormAlone(@TempDir Path dir) throws Exception {
        KeyPair key = NetherNetKeyFiles.generateP256();
        Path file = dir.resolve("domain-key.pem");
        String pem = NetherNetKeyFiles.writeSec1(key);
        Files.writeString(file, pem);

        assertSameKey(key, NetherNetKeyFiles.load(file, logger));
        assertEquals(pem, Files.readString(file));
        verify(logger, never()).info(anyString());
    }

    @Test
    void refusesAPublicKeyThatIsNotThePrivateKeys(@TempDir Path dir) throws Exception {
        KeyPair key = NetherNetKeyFiles.generateP256();
        KeyPair other = NetherNetKeyFiles.generateP256();
        Path file = dir.resolve("account-key.pem");
        String mismatched = pemBlock(key.getPrivate().getEncoded(), "PRIVATE KEY")
                + pemBlock(other.getPublic().getEncoded(), "PUBLIC KEY");
        Files.writeString(file, mismatched);

        GeneralSecurityException refused = assertThrows(GeneralSecurityException.class,
                () -> NetherNetKeyFiles.load(file, logger));
        assertTrue(refused.getMessage().contains("do not match"), refused.getMessage());
        assertEquals(mismatched, Files.readString(file), "a refused file is never rewritten");
    }

    @Test
    void everyWriteGetsATemporaryFileOfItsOwn(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("account-key.pem");

        // Two writers of the same file, as two conversions at once
        Path first = NetherNetKeyFiles.writeTemporary(file, "first");
        Path second = NetherNetKeyFiles.writeTemporary(file, "second");

        assertNotEquals(first, second);
        assertEquals("first", Files.readString(first), "a writer never replaces another's file");
        assertEquals("second", Files.readString(second));
    }

    private static void assertSingleSec1Block(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            PEMKeyPair pair = assertInstanceOf(PEMKeyPair.class, parser.readObject(), "an EC PRIVATE KEY block");
            assertNotNull(ECPrivateKey.getInstance(pair.getPrivateKeyInfo().parsePrivateKey()).getPublicKey(),
                    "the block carries the public point");
            assertNull(parser.readObject(), "one block only");
        }
    }

    private static void assertSameKey(KeyPair expected, KeyPair actual) {
        assertArrayEquals(expected.getPublic().getEncoded(), actual.getPublic().getEncoded());
        assertEquals(((java.security.interfaces.ECPrivateKey) expected.getPrivate()).getS(),
                ((java.security.interfaces.ECPrivateKey) actual.getPrivate()).getS());
    }

    /** The form earlier builds wrote: the JDK's PKCS#8, which has no public point, then the SPKI. */
    private static String twoBlocks(KeyPair key) {
        return pemBlock(key.getPrivate().getEncoded(), "PRIVATE KEY") + pemBlock(key.getPublic().getEncoded(), "PUBLIC KEY");
    }

    private static String pemBlock(byte[] der, String label) {
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
