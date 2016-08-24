/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security.keystore;

import android.os.IBinder;
import android.security.KeyStore;
import android.security.KeyStoreException;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;
import android.security.keymaster.OperationResult;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.ProviderException;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.MacSpi;

/**
 * {@link MacSpi} which provides HMAC implementations backed by Android KeyStore.
 *
 * @hide
 */
public abstract class AndroidKeyStoreHmacSpi extends MacSpi implements KeyStoreCryptoOperation {

    public static class HmacSHA1 extends AndroidKeyStoreHmacSpi {
        public HmacSHA1() {
            super(KeymasterDefs.KM_DIGEST_SHA1);
        }
    }

    public static class HmacSHA224 extends AndroidKeyStoreHmacSpi {
        public HmacSHA224() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_224);
        }
    }

    public static class HmacSHA256 extends AndroidKeyStoreHmacSpi {
        public HmacSHA256() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_256);
        }
    }

    public static class HmacSHA384 extends AndroidKeyStoreHmacSpi {
        public HmacSHA384() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_384);
        }
    }

    public static class HmacSHA512 extends AndroidKeyStoreHmacSpi {
        public HmacSHA512() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_512);
        }
    }

    private final KeyStore mKeyStore = KeyStore.getInstance();
    private final int mKeymasterDigest;
    private final int mMacSizeBits;

    // Fields below are populated by engineInit and should be preserved after engineDoFinal.
    private AndroidKeyStoreSecretKey mKey;

    // Fields below are reset when engineDoFinal succeeds.
    private KeyStoreCryptoOperationChunkedStreamer mChunkedStreamer;
    private IBinder mOperationToken;
    private long mOperationHandle;

    protected AndroidKeyStoreHmacSpi(int keymasterDigest) {
        mKeymasterDigest = keymasterDigest;
        mMacSizeBits = KeymasterUtils.getDigestOutputSizeBits(keymasterDigest);
    }

    @Override
    protected int engineGetMacLength() {
        return (mMacSizeBits + 7) / 8;
    }

    @Override
    protected void engineInit(Key key, AlgorithmParameterSpec params) throws InvalidKeyException,
            InvalidAlgorithmParameterException {
        resetAll();

        boolean success = false;
        try {
            init(key, params);
            ensureKeystoreOperationInitialized();
            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    private void init(Key key, AlgorithmParameterSpec params) throws InvalidKeyException,
        InvalidAlgorithmParameterException {
        if (key == null) {
            throw new InvalidKeyException("key == null");
        } else if (!(key instanceof AndroidKeyStoreSecretKey)) {
            throw new InvalidKeyException(
                    "Only Android KeyStore secret keys supported. Key: " + key);
        }
        mKey = (AndroidKeyStoreSecretKey) key;

        if (params != null) {
            throw new InvalidAlgorithmParameterException(
                    "Unsupported algorithm parameters: " + params);
        }

    }

    private void resetAll() {
        mKey = null;
        IBinder operationToken = mOperationToken;
        if (operationToken != null) {
            mKeyStore.abort(operationToken);
        }
        mOperationToken = null;
        mOperationHandle = 0;
        mChunkedStreamer = null;
    }

    private void resetWhilePreservingInitState() {
        IBinder operationToken = mOperationToken;
        if (operationToken != null) {
            mKeyStore.abort(operationToken);
        }
        mOperationToken = null;
        mOperationHandle = 0;
        mChunkedStreamer = null;
    }

    @Override
    protected void engineReset() {
        resetWhilePreservingInitState();
    }

    private void ensureKeystoreOperationInitialized() throws InvalidKeyException {
        if (mChunkedStreamer != null) {
            return;
        }
        if (mKey == null) {
            throw new IllegalStateException("Not initialized");
        }

        KeymasterArguments keymasterArgs = new KeymasterArguments();
        keymasterArgs.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_HMAC);
        keymasterArgs.addEnum(KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigest);
        keymasterArgs.addUnsignedInt(KeymasterDefs.KM_TAG_MAC_LENGTH, mMacSizeBits);

        OperationResult opResult = mKeyStore.begin(
                mKey.getAlias(),
                KeymasterDefs.KM_PURPOSE_SIGN,
                true,
                keymasterArgs,
                null, // no additional entropy needed for HMAC because it's deterministic
                mKey.getUid());

        if (opResult == null) {
            throw new KeyStoreConnectException();
        }

        // Store operation token and handle regardless of the error code returned by KeyStore to
        // ensure that the operation gets aborted immediately if the code below throws an exception.
        mOperationToken = opResult.token;
        mOperationHandle = opResult.operationHandle;

        // If necessary, throw an exception due to KeyStore operation having failed.
        InvalidKeyException e = KeyStoreCryptoOperationUtils.getInvalidKeyExceptionForInit(
                mKeyStore, mKey, opResult.resultCode);
        if (e != null) {
            throw e;
        }

        if (mOperationToken == null) {
            throw new ProviderException("Keystore returned null operation token");
        }
        if (mOperationHandle == 0) {
            throw new ProviderException("Keystore returned invalid operation handle");
        }

        mChunkedStreamer = new KeyStoreCryptoOperationChunkedStreamer(
                new KeyStoreCryptoOperationChunkedStreamer.MainDataStream(
                        mKeyStore, mOperationToken));
    }

    @Override
    protected void engineUpdate(byte input) {
        engineUpdate(new byte[] {input}, 0, 1);
    }

    @Override
    protected void engineUpdate(byte[] input, int offset, int len) {
        try {
            ensureKeystoreOperationInitialized();
        } catch (InvalidKeyException e) {
            throw new ProviderException("Failed to reinitialize MAC", e);
        }

        byte[] output;
        try {
            output = mChunkedStreamer.update(input, offset, len);
        } catch (KeyStoreException e) {
            throw new ProviderException("Keystore operation failed", e);
        }
        if ((output != null) && (output.length != 0)) {
            throw new ProviderException("Update operation unexpectedly produced output");
        }
    }

    @Override
    protected byte[] engineDoFinal() {
        try {
            ensureKeystoreOperationInitialized();
        } catch (InvalidKeyException e) {
            throw new ProviderException("Failed to reinitialize MAC", e);
        }

        byte[] result;
        try {
            result = mChunkedStreamer.doFinal(
                    null, 0, 0,
                    null, // no signature provided -- this invocation will generate one
                    null // no additional entropy needed -- HMAC is deterministic
                    );
        } catch (KeyStoreException e) {
            throw new ProviderException("Keystore operation failed", e);
        }

        resetWhilePreservingInitState();
        return result;
    }

    @Override
    public void finalize() throws Throwable {
        try {
            IBinder operationToken = mOperationToken;
            if (operationToken != null) {
                mKeyStore.abort(operationToken);
            }
        } finally {
            super.finalize();
        }
    }

    @Override
    public long getOperationHandle() {
        return mOperationHandle;
    }
}
