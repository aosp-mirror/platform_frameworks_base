package android.security;

import javax.crypto.SecretKey;

/**
 * {@link SecretKey} backed by keystore.
 *
 * @hide
 */
public class KeyStoreSecretKey implements SecretKey {
    private final String mAlias;
    private final String mAlgorithm;

    public KeyStoreSecretKey(String alias, String algorithm) {
        mAlias = alias;
        mAlgorithm = algorithm;
    }

    String getAlias() {
        return mAlias;
    }

    @Override
    public String getAlgorithm() {
        return mAlgorithm;
    }

    @Override
    public String getFormat() {
        // This key does not export its key material
        return null;
    }

    @Override
    public byte[] getEncoded() {
        // This key does not export its key material
        return null;
    }
}
