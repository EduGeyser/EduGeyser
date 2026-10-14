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

package org.geysermc.geyser.dump;

import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.text.AsteriskSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.interfaces.InterfaceDefaultOptions;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DumpCensorTest {
    private static final String CONFIG = """
        haproxy-protocol-whitelisted-ips: ["198.51.100.1", "192.168.1.5", "0.0.0.0", "127.0.0.1"]
        """;

    @AfterEach
    void resetSensitivity() {
        AsteriskSerializer.showSensitive = false;
    }

    /** The same node the dump is built from, so the test cannot drift away from it. */
    private static CommentedConfigurationNode dumped() throws Exception {
        GeyserConfig.AdvancedBedrockConfig config = YamlConfigurationLoader.builder()
            .source(() -> new BufferedReader(new StringReader(CONFIG)))
            .defaultOptions(InterfaceDefaultOptions::addTo)
            .build().load().get(GeyserConfig.AdvancedBedrockConfig.class);

        CommentedConfigurationNode node = CommentedConfigurationNode.root(AsteriskSerializer.dumpOptions());
        node.set(config);
        return node;
    }

    @Test
    void censorsPublicWhitelistedProxyAddresses() throws Exception {
        // Local and wildcard entries stay readable, as with the bind address
        assertEquals(List.of("***", "192.168.1.5", "0.0.0.0", "127.0.0.1"),
            dumped().node("haproxy-protocol-whitelisted-ips").getList(String.class));
    }

    @Test
    void showsThemInAFullDump() throws Exception {
        AsteriskSerializer.showSensitive = true;
        assertEquals(List.of("198.51.100.1", "192.168.1.5", "0.0.0.0", "127.0.0.1"),
            dumped().node("haproxy-protocol-whitelisted-ips").getList(String.class));
    }
}
