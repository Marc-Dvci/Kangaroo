package com.kangaroo.crypto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PEMDecoder;
import java.security.PEMEncoder;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The device's cryptographic identity, and the signature on every clinical record.
 *
 * <p>A clinical record that can be silently altered after the fact is not a clinical record. Each
 * installation generates an Ed25519 keypair on first run, publishes the public half for enrolment
 * with a supervisor, and signs every encounter it produces. A supervisor receiving a batch of
 * encounters can verify that each one came from the device it claims to and has not been edited
 * since — including by whoever is holding the device.
 *
 * <p><b>JEP 524 is what makes this two lines instead of a dependency.</b> PEM is how keys are
 * exchanged in practice — it is what you paste into an enrolment form and what a supervisor's
 * tooling expects — and until {@link PEMEncoder} and {@link PEMDecoder} arrived in the platform,
 * writing or reading one from Java meant either hand-rolling the base64-and-headers encoding
 * (and the ASN.1 underneath it) or pulling in a full cryptography library for the sake of a text
 * format. For a clinical application, not carrying that dependency is a real reduction in
 * attack surface, not a matter of taste.
 *
 * <p>Ed25519 rather than RSA because a Raspberry Pi signing every encounter should not spend
 * milliseconds doing it, and the keys are short enough that the public half fits in a QR code
 * for offline enrolment.
 */
public final class DeviceIdentity {

    private static final String ALGORITHM = "Ed25519";
    private static final String PRIVATE_KEY_FILE = "device-key.pem";
    private static final String PUBLIC_KEY_FILE = "device-public.pem";

    private final KeyPair keyPair;
    private final String fingerprint;

    private DeviceIdentity(KeyPair keyPair) {
        this.keyPair = keyPair;
        this.fingerprint = computeFingerprint(keyPair.getPublic());
    }

    /**
     * Load the device's identity, generating one on first run.
     *
     * <p>The private key never leaves this directory and is written with owner-only permissions
     * where the filesystem supports them.
     */
    public static DeviceIdentity loadOrCreate(Path directory) throws IOException, GeneralSecurityException {
        Path privatePath = directory.resolve(PRIVATE_KEY_FILE);

        if (Files.isRegularFile(privatePath)) {
            PEMDecoder decoder = PEMDecoder.of();
            String pem = Files.readString(privatePath, StandardCharsets.UTF_8);
            // A PEM private key round-trips straight back to a KeyPair: the PKCS#8 encoding carries
            // the public half alongside the private one for Ed25519.
            KeyPair pair = decoder.decode(pem, KeyPair.class);
            return new DeviceIdentity(pair);
        }

        KeyPair generated = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        DeviceIdentity identity = new DeviceIdentity(generated);

        Files.createDirectories(directory);
        Files.writeString(privatePath, PEMEncoder.of().encodeToString(generated),
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(PUBLIC_KEY_FILE), identity.publicKeyPem(),
                StandardCharsets.UTF_8);
        restrictPermissions(privatePath);

        return identity;
    }

    /** An in-memory identity, for tests and for the scripted demo. Never persisted. */
    public static DeviceIdentity ephemeral() throws GeneralSecurityException {
        return new DeviceIdentity(KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair());
    }

    /**
     * The public key, PEM-encoded, for enrolment with a supervisor.
     *
     * <p>This is the whole enrolment payload: it fits in a QR code, so a device can be enrolled by
     * a supervisor holding a phone, with no network on either side.
     */
    public String publicKeyPem() {
        return PEMEncoder.of().encodeToString(keyPair.getPublic());
    }

    /** A short, human-comparable fingerprint, for reading aloud during enrolment. */
    public String fingerprint() {
        return fingerprint;
    }

    /** Sign a record. The signature covers the exact bytes, so any edit invalidates it. */
    public String sign(byte[] content) throws GeneralSecurityException {
        Signature signature = Signature.getInstance(ALGORITHM);
        signature.initSign(keyPair.getPrivate());
        signature.update(content);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    public String sign(String content) throws GeneralSecurityException {
        return sign(content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Verify a signature against a PEM-encoded public key.
     *
     * <p>Static, and taking the key as PEM, because verification happens on a different machine
     * from signing — the supervisor's console has the enrolled public keys and nothing else.
     */
    public static boolean verify(String publicKeyPem, byte[] content, String signatureBase64) {
        try {
            PublicKey key = PEMDecoder.of().decode(publicKeyPem, PublicKey.class);
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(key);
            signature.update(content);
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // A malformed key or signature is a failed verification, not an exception to handle.
            return false;
        }
    }

    public static boolean verify(String publicKeyPem, String content, String signatureBase64) {
        return verify(publicKeyPem, content.getBytes(StandardCharsets.UTF_8), signatureBase64);
    }

    PublicKey publicKey() {
        return keyPair.getPublic();
    }

    private static String computeFingerprint(PublicKey key) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getEncoded());
            String hex = HexFormat.of().formatHex(digest, 0, 8).toUpperCase(java.util.Locale.ROOT);
            // Grouped in fours so it can be read aloud without losing your place.
            return hex.replaceAll("(.{4})(?!$)", "$1-");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            var view = Files.getFileAttributeView(path,
                    java.nio.file.attribute.PosixFileAttributeView.class);
            if (view != null) {
                view.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Windows has no POSIX permissions; the file inherits the user profile's ACL, which is
            // already owner-only.
        }
    }
}
