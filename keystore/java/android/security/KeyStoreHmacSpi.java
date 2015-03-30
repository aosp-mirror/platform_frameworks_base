package android.security;

import android.os.IBinder;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;
import android.security.keymaster.OperationResult;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.MacSpi;

/**
 * {@link MacSpi} which provides HMAC implementations backed by Android KeyStore.
 *
 * @hide
 */
public abstract class KeyStoreHmacSpi extends MacSpi {

    public static class HmacSHA256 extends KeyStoreHmacSpi {
        public HmacSHA256() {
            super(KeyStoreKeyConstraints.Digest.SHA256, 256 / 8);
        }
    }

    private final KeyStore mKeyStore = KeyStore.getInstance();
    private final @KeyStoreKeyConstraints.DigestEnum int mDigest;
    private final int mMacSizeBytes;

    private String mKeyAliasInKeyStore;

    // The fields below are reset by the engineReset operation.
    private KeyStoreCryptoOperationChunkedStreamer mChunkedStreamer;
    private IBinder mOperationToken;

    protected KeyStoreHmacSpi(@KeyStoreKeyConstraints.DigestEnum int digest, int macSizeBytes) {
        mDigest = digest;
        mMacSizeBytes = macSizeBytes;
    }

    @Override
    protected int engineGetMacLength() {
        return mMacSizeBytes;
    }

    @Override
    protected void engineInit(Key key, AlgorithmParameterSpec params) throws InvalidKeyException,
            InvalidAlgorithmParameterException {
        if (key == null) {
            throw new InvalidKeyException("key == null");
        } else if (!(key instanceof KeyStoreSecretKey)) {
            throw new InvalidKeyException(
                    "Only Android KeyStore secret keys supported. Key: " + key);
        }

        if (params != null) {
            throw new InvalidAlgorithmParameterException(
                    "Unsupported algorithm parameters: " + params);
        }

        mKeyAliasInKeyStore = ((KeyStoreSecretKey) key).getAlias();
        engineReset();
    }

    @Override
    protected void engineReset() {
        IBinder operationToken = mOperationToken;
        if (operationToken != null) {
            mOperationToken = null;
            mKeyStore.abort(operationToken);
        }
        mChunkedStreamer = null;

        KeymasterArguments keymasterArgs = new KeymasterArguments();
        keymasterArgs.addInt(KeymasterDefs.KM_TAG_DIGEST, mDigest);

        OperationResult opResult = mKeyStore.begin(mKeyAliasInKeyStore,
                KeymasterDefs.KM_PURPOSE_SIGN,
                true,
                keymasterArgs,
                null,
                new KeymasterArguments());
        if (opResult == null) {
            throw new KeyStoreConnectException();
        } else if (opResult.resultCode != KeyStore.NO_ERROR) {
            throw new CryptoOperationException("Failed to start keystore operation",
                    KeymasterUtils.getExceptionForKeymasterError(opResult.resultCode));
        }
        mOperationToken = opResult.token;
        if (mOperationToken == null) {
            throw new CryptoOperationException("Keystore returned null operation token");
        }
        mChunkedStreamer = new KeyStoreCryptoOperationChunkedStreamer(
                new KeyStoreStreamingConsumer(mKeyStore, mOperationToken));
    }

    @Override
    protected void engineUpdate(byte input) {
        engineUpdate(new byte[] {input}, 0, 1);
    }

    @Override
    protected void engineUpdate(byte[] input, int offset, int len) {
        if (mChunkedStreamer == null) {
            throw new IllegalStateException("Not initialized");
        }

        byte[] output;
        try {
            output = mChunkedStreamer.update(input, offset, len);
        } catch (KeymasterException e) {
            throw new CryptoOperationException("Keystore operation failed", e);
        }
        if ((output != null) && (output.length != 0)) {
            throw new CryptoOperationException("Update operation unexpectedly produced output");
        }
    }

    @Override
    protected byte[] engineDoFinal() {
        if (mChunkedStreamer == null) {
            throw new IllegalStateException("Not initialized");
        }

        byte[] result;
        try {
            result = mChunkedStreamer.doFinal(null, 0, 0);
        } catch (KeymasterException e) {
            throw new CryptoOperationException("Keystore operation failed", e);
        }

        engineReset();
        return result;
    }

    @Override
    public void finalize() throws Throwable {
        try {
            IBinder operationToken = mOperationToken;
            if (operationToken != null) {
                mOperationToken = null;
                mKeyStore.abort(operationToken);
            }
        } finally {
            super.finalize();
        }
    }

    /**
     * KeyStore-backed consumer of {@code MacSpi}'s chunked stream.
     */
    private static class KeyStoreStreamingConsumer
            implements KeyStoreCryptoOperationChunkedStreamer.KeyStoreOperation {
        private final KeyStore mKeyStore;
        private final IBinder mOperationToken;

        private KeyStoreStreamingConsumer(KeyStore keyStore, IBinder operationToken) {
            mKeyStore = keyStore;
            mOperationToken = operationToken;
        }

        @Override
        public OperationResult update(byte[] input) {
            return mKeyStore.update(mOperationToken, null, input);
        }

        @Override
        public OperationResult finish(byte[] input) {
            return mKeyStore.finish(mOperationToken, null, input);
        }
    }
}
