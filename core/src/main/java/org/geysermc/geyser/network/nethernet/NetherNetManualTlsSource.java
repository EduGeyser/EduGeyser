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

import io.github.sendablemetatype.netty.channel.nethernet.signaling.http.TlsContextSelector;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.geysermc.geyser.GeyserLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Serves the operator supplied TLS material for HTTP signaling, from the
 * config's {@code bedrock.signaling.builtin.https} keys or from cert.pem and
 * key.pem in the nethernet folder (see {@link NetherNetTlsFiles}). Consulted
 * per connection: the files are read again whenever their modification times
 * or sizes change, so certificates can be swapped while the server is live,
 * with no rebind and no restart.
 *
 * Every certificate passes {@link NetherNetCertificateManager#clientRejectionReason}
 * before being served, and expiry is re-checked per connection, because a
 * certificate a stock client rejects is strictly worse than none: the 26.40
 * client treats an invalid certificate as terminal for signaling and goes to
 * RakNet, which this server does not serve, while a server that refuses TLS
 * outright sends it to plain HTTP and its TOFU flow. Returning null here is
 * that refusal, and plain HTTP stays on whatever this source serves. For the
 * same reason a connection is only served a certificate that names what the
 * client asked for: the domain it sent as SNI, or an IP address when it sent
 * none, which clients do when they dial an IP.
 *
 * Material that cannot be read at all also leaves TLS withdrawn rather than
 * handing over to automatic certificate management: an operator who supplied
 * a certificate did not ask for Let's Encrypt enrollment, and the files heal
 * live once fixed. Removing the nethernet folder's files hands over on the
 * next restart, as automatic management only starts when no material is
 * supplied at bind.
 */
final class NetherNetManualTlsSource implements TlsContextSelector {

    /** subjectAltName entry types, RFC 5280 section 4.2.1.6. */
    private static final int SAN_DNS_NAME = 2;
    private static final int SAN_IP_ADDRESS = 7;

    private static final String LOG_PREFIX = "[Nethernet] ";

    private final NetherNetTlsFiles files;
    private final GeyserLogger logger;
    private final LongSupplier nanoTime;

    /**
     * Change detection state: modification times and sizes of every file,
     * re-checked at most once per second so probe floods cannot turn the
     * per connection consultation into a syscall storm. Sizes participate so
     * same tick swaps on coarse mtime filesystems are still noticed when the
     * content length moved.
     */
    private static final long CHECK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private record FileState(Path file, FileTime modified, long size) {
    }

    private boolean checkedOnce;
    private long lastCheckNanos;
    private List<FileState> fileStates;
    private SslContext context;
    private Instant expiry;
    /** The DNS names the served certificate names, lowercase. */
    private List<String> dnsNames = List.of();
    /** Whether the served certificate names any IP address. */
    private boolean namesIpAddress;
    private String problem;
    private boolean expiryWarned;
    private boolean missingWarned;

    NetherNetManualTlsSource(NetherNetTlsFiles files, GeyserLogger logger) {
        this(files, logger, System::nanoTime);
    }

    /** With the clock the file check interval is measured on, so tests need not wait for it. */
    NetherNetManualTlsSource(NetherNetTlsFiles files, GeyserLogger logger, LongSupplier nanoTime) {
        this.files = files;
        this.logger = logger;
        this.nanoTime = nanoTime;
    }

    @Override
    public synchronized SslContext select(String serverName) {
        SslContext current = current();
        if (current == null || !names(dnsNames, namesIpAddress, serverName)) {
            return null;
        }
        return current;
    }

    /**
     * Whether a certificate with these names suits a client that asked for
     * the server name, or for an IP address when it is null. Which IP the
     * client dialed is not known here (NAT), so any IP address entry counts.
     *
     * @param dnsNames the certificate's DNS names, lowercase, wildcards included
     * @param namesIpAddress whether it names any IP address
     * @param serverName the SNI name, lowercase, or null
     */
    static boolean names(List<String> dnsNames, boolean namesIpAddress, String serverName) {
        if (serverName == null) {
            return namesIpAddress;
        }
        for (String name : dnsNames) {
            if (name.equals(serverName)) {
                return true;
            }
            // A wildcard stands for exactly one leftmost label (RFC 6125 section 6.4.3)
            if (name.startsWith("*.")) {
                int dot = serverName.indexOf('.');
                if (dot > 0 && serverName.substring(dot).equals(name.substring(1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The context to serve whatever the client asked for, or null while TLS is withheld. */
    private SslContext current() {
        long now = nanoTime.getAsLong();
        if (!checkedOnce || now - lastCheckNanos >= CHECK_INTERVAL_NANOS) {
            checkedOnce = true;
            lastCheckNanos = now;
            checkFiles();
        }
        if (context == null) {
            return null;
        }
        if (expiry != null && !Instant.now().isBefore(expiry)) {
            if (!expiryWarned) {
                expiryWarned = true;
                logger.warning(LOG_PREFIX + "The HTTPS certificate from " + files.origin().description()
                        + " expired " + expiry + "; withdrawing TLS so updated clients fall back to plain HTTP with"
                        + " their trust prompt. " + files.origin().remedy());
            }
            return null;
        }
        return context;
    }

    /**
     * Why TLS is currently withheld, or null while a servable certificate is
     * loaded. Meant for the bind time mode log.
     */
    synchronized String currentProblem() {
        current();
        if (context == null) {
            return problem == null ? "the certificate files cannot be read" : problem;
        }
        if (expiry != null && !Instant.now().isBefore(expiry)) {
            return "certificate expired " + expiry;
        }
        return null;
    }

    private void checkFiles() {
        try {
            List<FileState> states = new ArrayList<>();
            for (Path file : files.files()) {
                if (!Files.exists(file)) {
                    withdrawForMissing(file);
                    return;
                }
                states.add(new FileState(file, Files.getLastModifiedTime(file), Files.size(file)));
            }
            missingWarned = false;
            if (!states.equals(fileStates)) {
                fileStates = states;
                reload();
            }
        } catch (Exception e) {
            logger.debug(LOG_PREFIX + "HTTPS certificate check failed: " + e.getMessage());
        }
    }

    private void withdrawForMissing(Path file) {
        problem = file + " does not exist";
        if (!missingWarned) {
            missingWarned = true;
            logger.warning(LOG_PREFIX + "The HTTPS certificate from " + files.origin().description()
                    + " cannot be used: " + problem + ". TLS is withdrawn, and HTTP signaling serves plain HTTP. "
                    + files.origin().remedy());
        }
        context = null;
        fileStates = null;
    }

    /**
     * Reads, validates, and builds the current files. Any failure leaves TLS
     * withdrawn rather than serving material a client would reject; the file
     * states stay recorded either way so a broken set is not re-read on every
     * connection.
     */
    private void reload() {
        boolean replacing = context != null || problem != null;
        context = null;
        expiry = null;
        expiryWarned = false;
        try {
            NetherNetTlsFiles.Material material = files.read();
            List<X509Certificate> chain = material.chain();
            String rejection = NetherNetCertificateManager.clientRejectionReason(chain);
            if (rejection != null) {
                problem = "a stock client rejects the certificate (" + rejection + ")";
                logger.warning(LOG_PREFIX + "The HTTPS certificate from " + files.origin().description()
                        + " is not served: " + problem + ". TLS is withdrawn; updated clients fall back to plain"
                        + " HTTP with their trust prompt");
                return;
            }
            // Netty builds a context from a mismatched pair without complaint,
            // producing handshakes no client can verify
            String mismatch = NetherNetCertificateManager.keyMismatchReason(material.key(), chain.getFirst().getPublicKey());
            if (mismatch != null) {
                problem = mismatch;
                logger.warning(LOG_PREFIX + "The HTTPS certificate from " + files.origin().description()
                        + " is not served: " + problem + ". TLS is withdrawn until the pair matches; a half finished"
                        + " swap heals when the second file lands");
                return;
            }
            readNames(chain.getFirst());
            context = SslContextBuilder.forServer(material.key(), chain.toArray(new X509Certificate[0])).build();
            expiry = chain.getFirst().getNotAfter().toInstant();
            problem = null;
            if (replacing) {
                logger.info(LOG_PREFIX + "HTTPS certificate from " + files.origin().description()
                        + " reloaded (valid until " + expiry + ")");
            }
        } catch (Exception e) {
            problem = e.getMessage() != null ? e.getMessage() : e.toString();
            logger.warning(LOG_PREFIX + "The HTTPS certificate from " + files.origin().description()
                    + " cannot be used: " + problem + ". TLS is withdrawn, and HTTP signaling serves plain HTTP. "
                    + files.origin().remedy());
        }
    }

    /**
     * Records what the leaf names in its subjectAltName entries, which every
     * publicly trusted certificate carries; the common name is not read.
     */
    private void readNames(X509Certificate leaf) throws CertificateParsingException {
        List<String> dns = new ArrayList<>();
        boolean ip = false;
        Collection<List<?>> entries = leaf.getSubjectAlternativeNames();
        if (entries != null) {
            for (List<?> entry : entries) {
                if (entry.get(0) instanceof Integer type && entry.get(1) instanceof String value) {
                    if (type == SAN_DNS_NAME) {
                        dns.add(value.toLowerCase(Locale.ROOT));
                    } else if (type == SAN_IP_ADDRESS) {
                        ip = true;
                    }
                }
            }
        }
        dnsNames = List.copyOf(dns);
        namesIpAddress = ip;
    }
}
