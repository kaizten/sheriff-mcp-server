package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A release's jar is taken only when the releases' key signed it.
 */
class ReleaseSignatureTest {

    private static final String OPENSSL_KEY = """
            -----BEGIN PUBLIC KEY-----
            MCowBQYDK2VwAyEA3J+mw5/CioWjOUzpv8VREzBTcjLknGspvuZyyYcCLtE=
            -----END PUBLIC KEY-----
            """;
    private static final String OPENSSL_SIGNATURE =
            "Hiwkd6nihe+XFffW7XHmyq4lo5uLnSnXWugt91i3GenEEP0dE+CMQheGNg5glPx/gu2n0tsVTP1X73JdXtbuAw==";
    private static final byte[] CONTENT = "signed by openssl".getBytes(StandardCharsets.UTF_8);

    static KeyPair keys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static byte[] sign(KeyPair keys, byte[] content) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(content);
        return Base64.getEncoder().encode(signature.sign());
    }

    @Test
    @DisplayName("a signature made by openssl, as the release workflow makes it, is read, a newline after it or not")
    void aSignatureOfOpensslIsRead() {
        ReleaseSignature check = new ReleaseSignature(ReleaseSignature.publicKey(OPENSSL_KEY));
        assertTrue(check.signed(CONTENT, OPENSSL_SIGNATURE.getBytes(StandardCharsets.US_ASCII)));
        assertTrue(check.signed(CONTENT, (OPENSSL_SIGNATURE + "\n").getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("other content, another key's signature and one that cannot be read are all refused")
    void anythingElseIsRefused() throws Exception {
        ReleaseSignature check = new ReleaseSignature(ReleaseSignature.publicKey(OPENSSL_KEY));
        assertFalse(check.signed("signed by openssI".getBytes(StandardCharsets.UTF_8),
                OPENSSL_SIGNATURE.getBytes(StandardCharsets.US_ASCII)));
        assertFalse(check.signed(CONTENT, sign(keys(), CONTENT)));
        assertFalse(check.signed(CONTENT, "not a signature".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(check.signed(CONTENT, "*".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(check.signed(CONTENT, new byte[0]));
    }

    @Test
    @DisplayName("the jar carries this project's key, which does not take another key's signature")
    void theJarCarriesTheProjectsKey() throws Exception {
        ReleaseSignature project = ReleaseSignature.ofThisProject();
        assertFalse(project.signed(CONTENT, OPENSSL_SIGNATURE.getBytes(StandardCharsets.US_ASCII)));
        assertFalse(project.signed(CONTENT, sign(keys(), CONTENT)));
    }

    @Test
    @DisplayName("a jar with no key, or one that cannot be read, is a broken build, said at once")
    void aMissingKeyIsRefused() {
        IllegalStateException none = assertThrows(IllegalStateException.class, () -> ReleaseSignature.from(null));
        assertEquals("this jar carries no release key", none.getMessage());
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("unreadable");
            }
        };
        IllegalStateException unread = assertThrows(IllegalStateException.class,
                () -> ReleaseSignature.from(broken));
        assertEquals("unreadable", unread.getCause().getMessage());
        ReleaseSignature read = ReleaseSignature.from(new ByteArrayInputStream(
                OPENSSL_KEY.getBytes(StandardCharsets.US_ASCII)));
        assertTrue(read.signed(CONTENT, OPENSSL_SIGNATURE.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("a key that is not one is a broken build, said at once")
    void aKeyThatIsNotOneIsRefused() {
        assertThrows(IllegalStateException.class, () -> ReleaseSignature.publicKey("-----BEGIN PUBLIC KEY-----\n"
                + "bm90IGEga2V5\n-----END PUBLIC KEY-----\n"));
        assertThrows(IllegalStateException.class, () -> ReleaseSignature.publicKey("***"));
    }
}
