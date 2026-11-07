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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetherNetSecretTest {

    @Test
    void takesAValueWithoutThePrefixAsTheSecret(@TempDir Path dir) throws Exception {
        NetherNetSecret secret = new NetherNetSecret("/tR+u5/aB=", dir);

        assertEquals("/tR+u5/aB=", secret.resolve(), "a password may start with a slash");
        assertNull(secret.file());
    }

    @Test
    void readsAFileRelativeToTheConfigFolder(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("secrets"));
        Files.writeString(dir.resolve("secrets/tls"), "hunter2\n");
        NetherNetSecret secret = new NetherNetSecret("file:secrets/tls", dir);

        assertEquals("hunter2", secret.resolve(), "the line terminator echo or an editor adds is not the secret");
        assertEquals(dir.resolve("secrets/tls"), secret.file());
    }

    @Test
    void readsAnAbsoluteFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("tls-password");
        Files.writeString(file, "hunter2\r\n");

        assertEquals("hunter2", new NetherNetSecret("file:" + file, Path.of("elsewhere")).resolve());
    }

    @Test
    void namesAMissingFileWithoutTheSecret(@TempDir Path dir) {
        IOException refused = assertThrows(IOException.class,
                () -> new NetherNetSecret("file:missing", dir).resolve());

        assertTrue(refused.getMessage().contains(dir.resolve("missing").toString()), refused.getMessage());
    }

    @Test
    void refusesAPathThatLostItsPrefix(@TempDir Path dir) throws Exception {
        // Upstream Geyser reads such a value as a path; using it as the password would fail
        // with a misleading wrong password error
        Files.writeString(dir.resolve("tls-password"), "hunter2");

        IOException refused = assertThrows(IOException.class,
                () -> new NetherNetSecret("tls-password", dir).resolve());
        assertTrue(refused.getMessage().contains("file:<path>"), refused.getMessage());
        assertFalse(refused.getMessage().contains("tls-password"), "the value may be the secret");

        assertThrows(IOException.class, () -> new NetherNetSecret("C:\\secrets\\tls", dir).resolve());
    }

    @Test
    void neverShowsTheValue(@TempDir Path dir) {
        assertFalse(new NetherNetSecret("hunter2", dir).toString().contains("hunter2"));
    }
}
