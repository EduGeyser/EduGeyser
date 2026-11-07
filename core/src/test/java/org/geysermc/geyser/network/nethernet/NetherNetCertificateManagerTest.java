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

import org.geysermc.geyser.GeyserLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class NetherNetCertificateManagerTest {

    @Test
    void renewsAtTwoThirdsOfTheShortlivedLifetime() {
        // The Let's Encrypt shortlived profile: 160 hours of validity.
        Instant notBefore = Instant.parse("2026-07-03T00:00:00Z");
        Instant notAfter = notBefore.plus(Duration.ofHours(160));

        assertFalse(NetherNetCertificateManager.needsRenewal(notBefore, notAfter, notBefore),
                "fresh certificate is kept");
        assertFalse(NetherNetCertificateManager.needsRenewal(notBefore, notAfter,
                notBefore.plus(Duration.ofHours(100))), "kept before the two thirds point");
        assertTrue(NetherNetCertificateManager.needsRenewal(notBefore, notAfter,
                notBefore.plus(Duration.ofHours(107))), "renewed past the two thirds point (~106.7h)");
        assertTrue(NetherNetCertificateManager.needsRenewal(notBefore, notAfter,
                notAfter.plus(Duration.ofHours(1))), "renewed when already expired");
    }

    @Test
    void writesAnIssuedKeyAndCertificateAsOnePair(@TempDir Path dir) throws Exception {
        Path cert = dir.resolve("cert.pem");
        Path key = dir.resolve("domain-key.pem");
        Files.writeString(cert, "old certificate");
        Files.writeString(key, "old key");
        KeyPair fresh = NetherNetKeyFiles.generateP256();

        NetherNetCertificateManager.writePair(cert, key, fresh, "new certificate");

        assertEquals("new certificate", Files.readString(cert));
        assertArrayEquals(fresh.getPublic().getEncoded(),
                NetherNetKeyFiles.parse(Files.readString(key), "test").pair().getPublic().getEncoded());
        try (var files = Files.list(dir)) {
            assertEquals(2, files.count(), "nothing pending is left behind");
        }
    }

    /**
     * Where a crash can stop {@link NetherNetCertificateManager#writePair}: with
     * only the key written beside its final name, with both written, or with
     * the key already moved into place.
     */
    @ParameterizedTest
    @ValueSource(strings = {"key-written", "both-written", "key-moved"})
    void theNextStartLeavesAWholePair(String crashedAt, @TempDir Path dir) throws Exception {
        Path cert = dir.resolve("cert.pem");
        Path key = dir.resolve("domain-key.pem");
        Files.writeString(cert, "old certificate");
        Files.writeString(key, "old key");
        switch (crashedAt) {
            case "key-written" -> Files.writeString(dir.resolve("domain-key.pem.next"), "new key");
            case "both-written" -> {
                Files.writeString(dir.resolve("domain-key.pem.next"), "new key");
                Files.writeString(dir.resolve("cert.pem.next"), "new certificate");
            }
            case "key-moved" -> {
                Files.writeString(key, "new key");
                Files.writeString(dir.resolve("cert.pem.next"), "new certificate");
            }
            default -> throw new AssertionError(crashedAt);
        }

        NetherNetCertificateManager.recoverPair(cert, key);

        // Only a written certificate commits the pair
        String expected = crashedAt.equals("key-written") ? "old" : "new";
        assertEquals(expected + " certificate", Files.readString(cert));
        assertEquals(expected + " key", Files.readString(key));
        try (var files = Files.list(dir)) {
            assertEquals(2, files.count(), "nothing pending is left behind");
        }
    }

    @Test
    void aSecondManagerWaitsForTheDirectory(@TempDir Path dir) throws Exception {
        Path cert = dir.resolve("cert.pem");
        Path key = dir.resolve("domain-key.pem");
        GeyserLogger logger = mock(GeyserLogger.class);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Future<?> recovery;
            try (var held = NetherNetCertificateManager.DirectoryLock.acquire(dir, logger)) {
                // The holder is between the two writes of a pair
                Files.writeString(dir.resolve("domain-key.pem.next"), "new key");
                recovery = other.submit(() -> {
                    try (var lock = NetherNetCertificateManager.DirectoryLock.acquire(dir, logger)) {
                        NetherNetCertificateManager.recoverPair(cert, key);
                    }
                    return null;
                });
                assertThrows(TimeoutException.class, () -> recovery.get(300, TimeUnit.MILLISECONDS));
                assertTrue(Files.exists(dir.resolve("domain-key.pem.next")), "the waiting recovery discarded nothing");

                Files.writeString(dir.resolve("cert.pem.next"), "new certificate");
            }
            recovery.get(10, TimeUnit.SECONDS);
            assertEquals("new key", Files.readString(key), "the pair finished by the holder was completed");
            assertEquals("new certificate", Files.readString(cert));
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    void deletesWhatACrashedWriteLeftBehind(@TempDir Path dir) throws Exception {
        Path leftover = NetherNetKeyFiles.writeTemporary(dir.resolve("domain-key.pem"), "half a key");
        Files.writeString(dir.resolve("cert.pem"), "certificate");

        NetherNetCertificateManager.deleteTemporaries(dir);

        assertFalse(Files.exists(leftover));
        assertTrue(Files.exists(dir.resolve("cert.pem")));
    }
}
