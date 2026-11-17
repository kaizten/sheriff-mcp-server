package com.kaizten.sheriff.infrastructure.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.stream.Collectors;

/**
 * Whether a file of a release was signed by who publishes the releases.
 *
 * <p>A release's checksum comes from the release itself, so it tells a
 * download cut short from a whole one and nothing else: whoever could put
 * another jar in a release would put its checksum beside it. A signature is
 * made with a key only the release workflow holds, and checked with the
 * public half this jar carries, so a jar nobody signed with it is refused.
 */
public final class ReleaseSignature {

    private static final String KEY_RESOURCE = "/release-key.pem";
    private static final String ALGORITHM = "Ed25519";
    private static final String PEM_BOUNDARY = "-----";
    private static final String NO_KEY = "this jar carries no release key";
    private final PublicKey key;

    /**
     * A check against one key.
     *
     * @param key the public half of the key releases are signed with
     */
    public ReleaseSignature(PublicKey key) {
        this.key = key;
    }

    /**
     * The check against the key this project's releases are signed with,
     * which the jar carries.
     *
     * @return that check
     * @throws IllegalStateException when the jar has no key, or one that
     *     cannot be read, which is a broken build
     */
    public static ReleaseSignature ofThisProject() {
        return from(ReleaseSignature.class.getResourceAsStream(KEY_RESOURCE));
    }

    /**
     * The check against a key read from a stream, which is closed.
     *
     * @param resource the key as OpenSSL writes it, {@code null} when there
     *     is none
     * @return that check
     * @throws IllegalStateException when there is no key, or it cannot be read
     */
    static ReleaseSignature from(InputStream resource) {
        if (resource == null) {
            throw new IllegalStateException(NO_KEY);
        }
        try (InputStream stream = resource) {
            return new ReleaseSignature(publicKey(new String(stream.readAllBytes(), StandardCharsets.US_ASCII)));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Reads a public key as OpenSSL writes it.
     *
     * @param pem the key, between its {@code BEGIN} and {@code END} lines
     * @return the key
     * @throws IllegalStateException when it is not an Ed25519 public key
     */
    static PublicKey publicKey(String pem) {
        String body = pem.lines().filter(line -> !line.startsWith(PEM_BOUNDARY)).collect(Collectors.joining());
        try {
            byte[] encoded = Base64.getMimeDecoder().decode(body);
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Whether a signature is the key's over some content.
     *
     * @param content what was signed
     * @param signature the signature as the release publishes it, in Base64
     * @return {@code true} only for a signature made with the key over
     *     exactly that content; one that cannot be read is not one
     */
    public boolean signed(byte[] content, byte[] signature) {
        try {
            Signature check = Signature.getInstance(ALGORITHM);
            check.initVerify(key);
            check.update(content);
            return check.verify(Base64.getMimeDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }
}
