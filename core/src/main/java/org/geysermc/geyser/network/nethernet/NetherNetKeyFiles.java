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

import org.bouncycastle.asn1.ASN1BitString;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.math.ec.FixedPointCombMultiplier;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.geysermc.geyser.GeyserLogger;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The key files of the automatic certificate ({@code acme/account-key.pem}
 * and {@code acme/domain-key.pem}). Every key file EduGeyser writes has one
 * form: a single SEC1 {@code EC PRIVATE KEY} block that carries its public
 * point. That is the form of the server identity in {@code identity.pem}, the
 * form upstream Geyser and {@code openssl ecparam -genkey -noout} write, and
 * one any reader can take without a second block or deriving the point.
 *
 * Earlier builds wrote these files as two blocks, the JDK's PKCS#8 (which
 * drops the point) and then a {@code PUBLIC KEY} block. {@link #load} reads
 * both forms and rewrites an old one in place: the same key, read back
 * before it replaces the file.
 */
final class NetherNetKeyFiles {

    private static final String LOG_PREFIX = "[Nethernet] ";
    private static final String SEC1_LABEL = "EC PRIVATE KEY";
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private NetherNetKeyFiles() {
    }

    /**
     * A key read from a file.
     *
     * @param pair      the key pair, its public half derived from the private scalar
     * @param singleBlock whether the file is already one SEC1 block with the point
     */
    record Parsed(KeyPair pair, boolean singleBlock) {
    }

    /** A new P-256 key, the curve of both automatic certificate keys. */
    static KeyPair generateP256() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /**
     * Reads a key file, rewriting it as one SEC1 block when it is in an
     * older form. The rewrite must read back as the same key before it
     * replaces the file. A rewrite that fails keeps the file as it is: the key
     * in it is still good, and the next load tries again.
     *
     * @throws IOException              if the file cannot be read
     * @throws GeneralSecurityException if it holds no usable EC key
     */
    static KeyPair load(Path file, GeyserLogger logger) throws IOException, GeneralSecurityException {
        Parsed parsed = parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        if (!parsed.singleBlock()) {
            try {
                rewrite(file, parsed.pair());
                logger.info(LOG_PREFIX + "Rewrote " + file.getFileName() + " as one PEM block with its public key;"
                        + " the key is unchanged");
            } catch (IOException | GeneralSecurityException e) {
                logger.warning(LOG_PREFIX + "Could not rewrite " + file.getFileName() + " as one PEM block ("
                        + e.getMessage() + "); its key is used as it is, and the next load tries again");
            }
        }
        return parsed.pair();
    }

    private static void rewrite(Path file, KeyPair pair) throws IOException, GeneralSecurityException {
        // The read back checks the very file the move installs: the name is
        // this write's own, so no other writer can have replaced it
        Path temporary = writeTemporary(file, writeSec1(pair));
        try {
            Parsed written = parse(Files.readString(temporary, StandardCharsets.UTF_8), temporary.toString());
            if (!written.singleBlock() || !sameKey(written.pair(), pair)) {
                throw new GeneralSecurityException("the rewritten file did not hold the same key");
            }
            moveIntoPlace(temporary, file);
        } finally {
            deleteQuietly(temporary);
        }
    }

    /**
     * Reads an unencrypted EC private key on a named curve: one SEC1 or
     * PKCS#8 block, optionally with a {@code PUBLIC KEY} block, as
     * {@code openssl ecparam -genkey} writes it or as earlier builds did. The
     * public point is derived from the private scalar; a point the file
     * states, embedded or in its own block, must equal it.
     *
     * @param source names the file in error messages
     */
    static Parsed parse(String armour, String source) throws GeneralSecurityException {
        PrivateKeyInfo privateKey = null;
        boolean sec1 = false;
        SubjectPublicKeyInfo statedPublic = null;
        int blocks = 0;
        try (PEMParser parser = new PEMParser(new StringReader(armour))) {
            for (Object block; (block = parser.readObject()) != null; ) {
                blocks++;
                if (block instanceof ASN1ObjectIdentifier) {
                    continue; // EC PARAMETERS; the key's own parameters decide the curve
                }
                if (privateKey == null && block instanceof PEMKeyPair pair) {
                    privateKey = pair.getPrivateKeyInfo();
                    sec1 = true;
                } else if (privateKey == null && block instanceof PrivateKeyInfo info) {
                    privateKey = info;
                } else if (statedPublic == null && block instanceof SubjectPublicKeyInfo info) {
                    statedPublic = info;
                } else {
                    throw new GeneralSecurityException("Expected one unencrypted EC private key and at most one public"
                            + " key in " + source + ", found " + block.getClass().getSimpleName());
                }
            }
        } catch (IOException | RuntimeException malformed) {
            throw new GeneralSecurityException("Cannot read the PEM in " + source, malformed);
        }
        if (privateKey == null) {
            throw new GeneralSecurityException("No EC private key in " + source);
        }

        try {
            AlgorithmIdentifier algorithm = privateKey.getPrivateKeyAlgorithm();
            ECPrivateKey key = ECPrivateKey.getInstance(privateKey.parsePrivateKey());
            if (!X9ObjectIdentifiers.id_ecPublicKey.equals(algorithm.getAlgorithm())
                    || !(algorithm.getParameters() instanceof ASN1ObjectIdentifier curveId)) {
                throw new GeneralSecurityException("The key in " + source + " is not an EC key on a named curve");
            }
            ASN1Encodable innerParameters = key.getParametersObject();
            X9ECParameters curve = ECNamedCurveTable.getByOID(curveId);
            if (curve == null || (innerParameters != null && !curveId.equals(innerParameters))) {
                throw new GeneralSecurityException("The key in " + source + " names an unknown or contradictory curve");
            }
            BigInteger scalar = key.getKey();
            if (scalar.signum() <= 0 || scalar.compareTo(curve.getN()) >= 0) {
                throw new GeneralSecurityException("The private key in " + source + " is out of range");
            }
            byte[] point = new FixedPointCombMultiplier().multiply(curve.getG(), scalar).normalize().getEncoded(false);
            ASN1BitString embedded = key.getPublicKey();
            if (embedded != null && !MessageDigest.isEqual(embedded.getOctets(), point)) {
                throw mismatch(source);
            }
            if (statedPublic != null && (!algorithm.equals(statedPublic.getAlgorithm())
                    || !MessageDigest.isEqual(statedPublic.getPublicKeyData().getOctets(), point))) {
                throw mismatch(source);
            }

            AlgorithmIdentifier canonical = new AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, curveId);
            int orderBits = curve.getN().bitLength();
            KeyFactory factory = KeyFactory.getInstance("EC");
            KeyPair pair = new KeyPair(
                    factory.generatePublic(new X509EncodedKeySpec(new SubjectPublicKeyInfo(canonical, point).getEncoded())),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(
                            new PrivateKeyInfo(canonical, new ECPrivateKey(orderBits, scalar, curveId)).getEncoded())));
            return new Parsed(pair, blocks == 1 && sec1 && embedded != null);
        } catch (IOException | RuntimeException malformed) {
            throw new GeneralSecurityException("Cannot read the EC private key in " + source, malformed);
        }
    }

    /** The key as one SEC1 block with its public point. */
    static String writeSec1(KeyPair pair) throws IOException {
        SubjectPublicKeyInfo publicKey = SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded());
        PrivateKeyInfo info = PrivateKeyInfo.getInstance(pair.getPrivate().getEncoded());
        ASN1ObjectIdentifier curveId = ASN1ObjectIdentifier.getInstance(info.getPrivateKeyAlgorithm().getParameters());
        ECPrivateKey scalar = ECPrivateKey.getInstance(info.parsePrivateKey());
        int orderBits = ECNamedCurveTable.getByOID(curveId).getN().bitLength();

        StringWriter out = new StringWriter();
        try (PemWriter writer = new PemWriter(out)) {
            writer.writeObject(new PemObject(SEC1_LABEL, new ECPrivateKey(orderBits, scalar.getKey(),
                    publicKey.getPublicKeyData(), curveId).getEncoded()));
        }
        return out.toString();
    }

    /**
     * Replaces a file in one step, readable only by its owner. A crash or full disk
     * mid write leaves the previous file whole: a torn certificate wedges
     * adoption, and a torn key cannot be recovered.
     */
    static void writeAtomically(Path file, String text) throws IOException {
        Path temporary = writeTemporary(file, text);
        try {
            moveIntoPlace(temporary, file);
        } finally {
            deleteQuietly(temporary);
        }
    }

    /**
     * Removes a temporary file after a failed write or move. A successful
     * move already removed it, and a failure here must not hide the cause of
     * the failure that left it.
     */
    private static void deleteQuietly(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
        }
    }

    /**
     * Writes the text, synced, to a new file beside the target, named for
     * this write alone: writers that shared one name deleted and replaced
     * each other's file, so one could install another's half written file.
     * The name starts with a dot and ends in {@code .tmp}, so
     * {@link #isTemporary} recognizes what a crash left behind.
     * The file is owner-only from the moment it exists (POSIX permissions,
     * or an owner-only ACL on Windows), as NetworkM's identity key writer
     * makes its files: the move keeps its permissions, so a key replaced
     * this way never takes on the directory's wider access.
     */
    static Path writeTemporary(Path file, String text) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = directory.resolve("." + file.getFileName() + "-"
                + Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36) + ".tmp");
        FileStore store = Files.getFileStore(directory);
        if (store.supportsFileAttributeView(PosixFileAttributeView.class)) {
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } else if (store.supportsFileAttributeView(AclFileAttributeView.class)) {
            // Set at creation, not after: a handle another account opened in
            // between would keep its access. An ACL given at creation also
            // inherits nothing from the directory.
            Files.createFile(temporary, ownerOnlyAcl(directory));
        } else {
            Files.createFile(temporary);
        }
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        return temporary;
    }

    /** Whether the file is one {@link #writeTemporary} creates, for any target. */
    static boolean isTemporary(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith(".") && name.endsWith(".tmp") && name.indexOf('-') > 0;
    }

    private static FileAttribute<List<AclEntry>> ownerOnlyAcl(Path directory) throws IOException {
        UserPrincipal owner = directory.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        List<AclEntry> entries = List.of(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build());
        return new FileAttribute<>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return entries;
            }
        };
    }

    /**
     * Renames a written file over its target, then syncs the directory, so
     * the rename is on disk before anything that follows it. The pair write
     * of the automatic certificate depends on that order: after a power loss
     * a later rename must not survive without an earlier one.
     */
    static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        syncDirectory(target.toAbsolutePath().getParent());
    }

    /**
     * Syncs a directory where the platform allows it, which POSIX systems
     * do. Windows cannot open a directory for that, and Java's moves there
     * do not ask for write through; NTFS journals renames in order, so a
     * power loss there loses the latest renames rather than an earlier one
     * (from its documented design, not measured here). Best effort: a file
     * system that cannot sync a directory still has the rename itself.
     */
    private static void syncDirectory(Path directory) {
        try {
            if (!Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
                return;
            }
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean sameKey(KeyPair a, KeyPair b) {
        return MessageDigest.isEqual(a.getPublic().getEncoded(), b.getPublic().getEncoded())
                && MessageDigest.isEqual(a.getPrivate().getEncoded(), b.getPrivate().getEncoded());
    }

    private static GeneralSecurityException mismatch(String source) {
        return new GeneralSecurityException("The private and public keys in " + source + " do not match");
    }
}
