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
    void takesAnyOtherValueAsTheSecret(@TempDir Path dir) throws Exception {
        for (String literal : new String[]{"hunter2", "tR+u5/aB=", "secrets/tls", "file"}) {
            NetherNetSecret secret = new NetherNetSecret(literal, dir);

            assertEquals(literal, secret.resolve());
            assertNull(secret.file());
        }
    }

    @Test
    void readsAFileReferenceRelativeToTheConfigFolder(@TempDir Path dir) throws Exception {
        Path file = write(dir, "secrets/tls", "hunter2\n");
        NetherNetSecret secret = new NetherNetSecret("file:secrets/tls", dir);

        assertEquals("hunter2", secret.resolve(), "the line terminator echo or an editor adds is not the secret");
        assertEquals(file, secret.file());
    }

    @Test
    void readsABarePathThatStartsAtTheRoot(@TempDir Path dir) throws Exception {
        Path file = write(dir, "tls-password", "hunter2\r\n");
        // An absolute path on POSIX; on Windows the root of the config folder's drive
        String rooted = "/" + file.getRoot().relativize(file).toString().replace('\\', '/');

        assertEquals("hunter2", new NetherNetSecret(rooted, dir).resolve());
        assertEquals(file, new NetherNetSecret(rooted, dir).file());
    }

    @Test
    void readsBarePathsRelativeToTheConfigFolder(@TempDir Path dir) throws Exception {
        Path config = Files.createDirectory(dir.resolve("config"));
        write(config, "secrets/tls", "inside");
        write(dir, "outside", "outside");

        assertEquals("inside", new NetherNetSecret("./secrets/tls", config).resolve());
        assertEquals("outside", new NetherNetSecret("../outside", config).resolve());
    }

    @Test
    void readsAWindowsDrivePathWhereThePlatformHasDrives(@TempDir Path dir) throws Exception {
        String drivePath = "C:\\secrets\\tls";
        if (!Path.of(drivePath).isAbsolute()) {
            // Not absolute elsewhere, so it is the secret itself, as upstream reads it
            assertEquals(drivePath, new NetherNetSecret(drivePath, dir).resolve());
            return;
        }
        Path file = write(dir, "tls-password", "hunter2");
        assertTrue(file.toString().matches("[A-Za-z]:\\\\.*"), file::toString);

        assertEquals("hunter2", new NetherNetSecret(file.toString(), Path.of("elsewhere")).resolve());
    }

    @Test
    void dropsOnlyOneLineTerminator(@TempDir Path dir) throws Exception {
        write(dir, "tls-password", "hunter2\n\n");

        assertEquals("hunter2\n", new NetherNetSecret("file:tls-password", dir).resolve());
    }

    @Test
    void neverShowsABarePathWithNothingThere(@TempDir Path dir) {
        // A literal password that happens to start with a slash, as a base64 value can
        String looksLikeAPath = "/k8Hq2wZ+secret";
        IOException error = assertThrows(IOException.class, () -> new NetherNetSecret(looksLikeAPath, dir).resolve());
        assertFalse(error.getMessage().contains("k8Hq2wZ"), error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void namesTheUnreadablePathButNeverTheValue(@TempDir Path dir) throws Exception {
        IOException missing = assertThrows(IOException.class, () -> new NetherNetSecret("file:missing", dir).resolve());
        assertTrue(missing.getMessage().contains(dir.resolve("missing").toString()), missing.getMessage());
        assertFalse(missing.getMessage().contains("file:"), missing.getMessage());

        Files.createDirectory(dir.resolve("folder"));
        IOException folder = assertThrows(IOException.class, () -> new NetherNetSecret("./folder", dir).resolve());
        assertTrue(folder.getMessage().contains("not a regular file"), folder.getMessage());

        write(dir, "large", "x".repeat(16385));
        IOException large = assertThrows(IOException.class, () -> new NetherNetSecret("./large", dir).resolve());
        assertTrue(large.getMessage().contains("larger than 16384 bytes"), large.getMessage());
    }

    @Test
    void namesItsFileOnlyByTheSameRule(@TempDir Path dir) throws Exception {
        NetherNetSecret bare = new NetherNetSecret("./secret-password", dir);
        assertEquals("the password file", bare.describeFile());
        assertFalse(bare.missingFile().contains("secret-password"), bare.missingFile());

        write(dir, "secret-password", "hunter2");
        assertEquals("the password file " + dir.resolve("secret-password"), bare.describeFile(), "something is there");
        assertEquals("the password file " + dir.resolve("missing") + " does not exist",
                new NetherNetSecret("file:missing", dir).missingFile());
    }

    @Test
    void neverShowsTheValue(@TempDir Path dir) {
        assertFalse(new NetherNetSecret("hunter2", dir).toString().contains("hunter2"));
    }

    private static Path write(Path dir, String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }
}
