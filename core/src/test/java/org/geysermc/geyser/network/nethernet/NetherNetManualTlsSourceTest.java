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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherNetManualTlsSourceTest {

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
}
