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

import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.geysermc.geyser.GeyserLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NetherNetManualTlsSourceTest {
    private final GeyserLogger logger = mock(GeyserLogger.class);
    private long now;

    @Test
    void anIpCertificateServesOnlyClientsThatDialedAnIp() {
        assertTrue(NetherNetManualTlsSource.names(List.of(), true, null));
        assertFalse(NetherNetManualTlsSource.names(List.of(), true, "play.example.com"));
    }

    @Test
    void aDomainCertificateServesOnlyTheNamesItLists() {
        List<String> names = List.of("play.example.com");

        assertTrue(NetherNetManualTlsSource.names(names, false, "play.example.com"));
        assertFalse(NetherNetManualTlsSource.names(names, false, "other.example.com"));
        assertFalse(NetherNetManualTlsSource.names(names, false, null));
    }

    @Test
    void aWildcardStandsForExactlyOneLabel() {
        List<String> names = List.of("*.example.com");

        assertTrue(NetherNetManualTlsSource.names(names, false, "play.example.com"));
        assertFalse(NetherNetManualTlsSource.names(names, false, "example.com"));
        assertFalse(NetherNetManualTlsSource.names(names, false, "a.play.example.com"));
    }

    @Test
    void withdrawsTlsForACertificateNoStockClientTrusts(@TempDir Path dir) throws Exception {
        writePair(dir);
        NetherNetManualTlsSource source = new NetherNetManualTlsSource(
                NetherNetTlsFilesTest.configured(dir, "chain.pem", "key.pem", ""), logger, () -> now);

        // Plain HTTP stays on: refusing TLS is what sends clients there
        assertNull(source.select(null));
        assertTrue(source.currentProblem().startsWith("a stock client rejects the certificate"), source.currentProblem());
        verify(logger).warning(contains("The HTTPS certificate from the files set in bedrock.signaling.builtin.https is not served"));
    }

    @Test
    void readsTheFilesAgainOnceTheyAppear(@TempDir Path dir) throws Exception {
        NetherNetManualTlsSource source = new NetherNetManualTlsSource(
                NetherNetTlsFilesTest.configured(dir, "chain.pem", "key.pem", ""), logger, () -> now);

        assertEquals(dir.resolve("chain.pem") + " does not exist", source.currentProblem());
        verify(logger).warning(contains("cannot be used: " + dir.resolve("chain.pem") + " does not exist"));

        writePair(dir);
        assertEquals(dir.resolve("chain.pem") + " does not exist", source.currentProblem(),
                "the files are checked at most once a second");
        now += TimeUnit.SECONDS.toNanos(1);
        assertTrue(source.currentProblem().startsWith("a stock client rejects the certificate"), source.currentProblem());
    }

    private static void writePair(Path dir) throws Exception {
        KeyPair key = NetherNetKeyFiles.generateP256();
        Files.writeString(dir.resolve("chain.pem"), NetherNetTlsFilesTest.pem(NetherNetTlsFilesTest.selfSigned(key)));
        Files.writeString(dir.resolve("key.pem"), NetherNetTlsFilesTest.pem(new JcaPKCS8Generator(key.getPrivate(), null)));
    }
}
