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
import org.geysermc.geyser.GeyserLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

/**
 * The server identity's key file, {@code nethernet/identity.pem}: one SEC1
 * {@code EC PRIVATE KEY} block with its public point, the file and form
 * upstream Geyser uses, so a server that moves between Geyser and EduGeyser
 * keeps its identity and the pins players hold for it.
 *
 * Earlier EduGeyser builds kept the key in {@code identity-key.pem} as two
 * blocks, PKCS#8 without the public point and then a {@code PUBLIC KEY} block.
 * Upstream's reader cannot read that file: it reads only the first block and
 * requires the point. {@link #load} moves such a key to {@code identity.pem},
 * the same key, so no player has to trust the server again.
 */
final class NetherNetIdentityFile {

    private static final String LOG_PREFIX = "[Nethernet] ";
    static final String FILE = "identity.pem";
    static final String LEGACY_FILE = "identity-key.pem";

    private NetherNetIdentityFile() {
    }

    /**
     * Loads the server identity from {@code identity.pem}, creating it when
     * no key exists yet. A key only in {@code identity-key.pem} is written to
     * {@code identity.pem} first; once {@code identity.pem} holds the same
     * key, the old file is deleted. An old file with another key, or one that
     * cannot be read, is left alone with a warning that it is ignored.
     *
     * @param nethernetDir the folder of both files
     * @param domain       the name the identity carries
     * @throws IOException              if a file cannot be read or created
     * @throws GeneralSecurityException if the key to use cannot be read
     */
    static ServerIdentity load(Path nethernetDir, String domain, GeyserLogger logger)
            throws IOException, GeneralSecurityException {
        Path file = nethernetDir.resolve(FILE);
        Path legacy = nethernetDir.resolve(LEGACY_FILE);
        if (!Files.exists(legacy)) {
            return ServerIdentity.loadOrCreate(file, domain);
        }

        ServerIdentity old;
        boolean moved = false;
        if (Files.exists(file)) {
            try {
                old = ServerIdentity.load(legacy, domain);
            } catch (IOException | GeneralSecurityException e) {
                ServerIdentity identity = ServerIdentity.loadOrCreate(file, domain);
                logger.warning(LOG_PREFIX + "Ignoring " + legacy + ", which cannot be read (" + e.getMessage()
                        + "). NetherNet uses the key in " + FILE + " (SHA-256 " + identity.fingerprint() + ")");
                return identity;
            }
        } else {
            // No other key to fall back on: a key that cannot be read fails
            // the start, as a new one would make every player trust the
            // server again
            old = ServerIdentity.load(legacy, domain);
            // False when another process created the file meanwhile, which is
            // then judged like a file that was there before
            moved = old.writeKeyFile(file);
        }

        // Read back through the same reader every later start uses
        ServerIdentity identity = ServerIdentity.loadOrCreate(file, domain);
        if (!identity.fingerprint().equals(old.fingerprint())) {
            logger.warning(LOG_PREFIX + "Ignoring " + legacy + ": it holds another key (SHA-256 " + old.fingerprint()
                    + ") than " + FILE + " (SHA-256 " + identity.fingerprint() + "), which NetherNet uses."
                    + " Players who trusted the other key are asked to trust the server again. Delete "
                    + LEGACY_FILE + " to silence this, or delete " + FILE + " and restart to go back to the other key");
            return identity;
        }
        try {
            Files.delete(legacy);
        } catch (IOException e) {
            logger.warning(LOG_PREFIX + "Could not delete " + legacy + " (" + e.getMessage() + "); it is no longer used,"
                    + " as " + FILE + " holds the same key");
        }
        logger.info(LOG_PREFIX + (moved
                ? "Moved the server identity key from " + LEGACY_FILE + " to " + FILE + ", the file upstream Geyser uses"
                : "Deleted " + LEGACY_FILE + ", as " + FILE + " holds the same key")
                + " (SHA-256 " + identity.fingerprint() + ")");
        return identity;
    }
}
