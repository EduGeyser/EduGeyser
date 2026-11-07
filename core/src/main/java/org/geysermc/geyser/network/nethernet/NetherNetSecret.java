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

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * A secret set in the config, such as a certificate password: the value
 * itself, or a file that holds it. A config file is read by anyone who can
 * read its folder and ends up in support requests; a file keeps the secret
 * out of it.
 *
 * The rule is upstream CloudburstMC/Network's {@code SecretValue}, so a
 * config means the same in Geyser and EduGeyser: a value names a file when
 * it starts with {@code file:}, {@code /}, {@code ./} or {@code ../}, or is
 * an absolute path on this platform (such as {@code C:\secret} on Windows).
 * A relative path resolves against the config folder. Any other value is the
 * secret itself.
 *
 * @param value     the configured value, possibly empty
 * @param directory the folder a relative path is resolved against
 */
record NetherNetSecret(String value, Path directory) {
    /** Above this a file is something other than a secret, and reading it is a waste. */
    private static final long MAX_BYTES = 16384;
    private static final String FILE = "file:";
    private static final String NOT_SHOWN = "the password looks like a file path, but no file is there. It is not"
            + " shown, since it may be the password itself; to name a file, write file:<path>";

    /** An unset secret. */
    static NetherNetSecret none() {
        return new NetherNetSecret("", Path.of(""));
    }

    /**
     * The file the secret is read from, or null when the value is the secret
     * itself or names no valid path.
     */
    @Nullable Path file() {
        String trimmed = value.trim();
        if (!isFileReference(trimmed)) {
            return null;
        }
        try {
            return path(trimmed);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * The secret: the content of the file the value names, without one
     * trailing line terminator (a file written by {@code echo} or an editor
     * has one), else the value as it is.
     *
     * @throws IOException if the value names a file that is not a regular
     *                     file of at most 16 KiB or cannot be read. The message
     *                     names the path only for a {@code file:} reference or
     *                     when something exists there, since a value that only
     *                     looks like a path may be the secret itself.
     */
    String resolve() throws IOException {
        String trimmed = value.trim();
        if (!isFileReference(trimmed)) {
            return value;
        }
        Path source;
        try {
            source = path(trimmed);
        } catch (InvalidPathException e) {
            throw new IOException("the password file path is not a valid path");
        }
        try {
            if (!Files.isRegularFile(source)) {
                throw new IOException("not a regular file");
            }
            if (Files.size(source) > MAX_BYTES) {
                throw new IOException("larger than " + MAX_BYTES + " bytes");
            }
            return stripLineTerminator(Files.readString(source));
        } catch (IOException | RuntimeException e) {
            if (!trimmed.startsWith(FILE) && !exists(source)) {
                // A literal secret that only looks like a path must not reach the log, nor its cause's message
                throw new IOException(NOT_SHOWN);
            }
            throw new IOException("the password file " + source + " cannot be read (" + e.getMessage() + ")", e);
        }
    }

    /**
     * How a message may name the file the secret is read from, by the rule
     * {@link #resolve} follows: with its path for a {@code file:} reference
     * or when something is there, else without it.
     */
    String describeFile() {
        Path file = file();
        return file != null && (value.trim().startsWith(FILE) || exists(file))
                ? "the password file " + file : "the password file";
    }

    /** Why the secret cannot be read while its file is missing, by the same rule. */
    String missingFile() {
        Path file = file();
        return file != null && value.trim().startsWith(FILE) ? "the password file " + file + " does not exist" : NOT_SHOWN;
    }

    private static boolean exists(Path path) {
        try {
            return Files.exists(path);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Never shows the value, which may be the secret itself. */
    @Override
    public String toString() {
        return "NetherNetSecret[" + (value.isBlank() ? "unset" : "***") + "]";
    }

    private static boolean isFileReference(String trimmed) {
        return trimmed.startsWith(FILE) || trimmed.startsWith("/") || trimmed.startsWith("./")
                || trimmed.startsWith("../") || isAbsolutePath(trimmed);
    }

    private static boolean isAbsolutePath(String trimmed) {
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            return Path.of(trimmed).isAbsolute();
        } catch (InvalidPathException notAPath) {
            return false;
        }
    }

    private Path path(String trimmed) {
        Path path = Path.of(trimmed.startsWith(FILE) ? trimmed.substring(FILE.length()) : trimmed);
        return (path.isAbsolute() ? path : directory.resolve(path)).normalize();
    }

    private static String stripLineTerminator(String text) {
        if (text.endsWith("\r\n")) {
            return text.substring(0, text.length() - 2);
        }
        if (text.endsWith("\n") || text.endsWith("\r")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }
}
