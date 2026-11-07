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
import java.util.regex.Pattern;

/**
 * A secret set in the config, such as a certificate password: the value
 * itself, or {@code file:} followed by the path of a file that holds it,
 * relative to the config folder unless absolute. A config file is read by
 * anyone who can read its folder and ends up in support requests; a file
 * reference keeps the secret out of it.
 *
 * Only the prefix makes a value a file reference, as NetworkM's NXS
 * {@code SecretValue} rules (upstream Geyser also takes a value that starts
 * with {@code /} or {@code ./} as a path). A value without the prefix that
 * names an existing file, or starts with a Windows drive, is refused with an
 * error that names the {@code file:} form, so a reference that lost its prefix
 * is never used as the secret.
 *
 * @param value     the configured value, possibly empty
 * @param directory the folder a relative path is resolved against
 */
record NetherNetSecret(String value, Path directory) {
    /** Above this a file is something other than a secret, and reading it is a waste. */
    private static final long MAX_BYTES = 16384;
    private static final String FILE = "file:";
    private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:[\\\\/].*");

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
        if (!trimmed.startsWith(FILE)) {
            return null;
        }
        try {
            return path(trimmed.substring(FILE.length()));
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * The secret: the file's content for a reference, without its trailing
     * line terminator (a file written by {@code echo} or an editor has one),
     * else the value as it is.
     *
     * @throws IOException if a referenced file cannot be read, or the value
     *                     looks like a path without the prefix. The message
     *                     never contains the value.
     */
    String resolve() throws IOException {
        String trimmed = value.trim();
        if (!trimmed.startsWith(FILE)) {
            if (looksLikePath(trimmed)) {
                throw new IOException("the password looks like a file path; write file:<path> to read it from a file");
            }
            return value;
        }
        Path source = file();
        if (source == null) {
            throw new IOException("the text after file: in the password is not a valid path");
        }
        try {
            if (!Files.isRegularFile(source)) {
                throw new IOException("not a readable file");
            }
            if (Files.size(source) > MAX_BYTES) {
                throw new IOException("larger than " + MAX_BYTES + " bytes");
            }
            return stripLineTerminator(Files.readString(source));
        } catch (IOException | RuntimeException e) {
            // The path is safe to name: it is the reference, not the secret
            throw new IOException("the password file " + source + " cannot be read (" + e.getMessage() + ")", e);
        }
    }

    /** Never shows the value, which may be the secret itself. */
    @Override
    public String toString() {
        return "NetherNetSecret[" + (value.isBlank() ? "unset" : "***") + "]";
    }

    private boolean looksLikePath(String trimmed) {
        if (trimmed.isEmpty()) {
            return false;
        }
        if (DRIVE.matcher(trimmed).matches()) {
            return true;
        }
        try {
            return Files.exists(path(trimmed));
        } catch (InvalidPathException | SecurityException notAPath) {
            return false;
        }
    }

    private Path path(String raw) {
        Path path = Path.of(raw);
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
