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
public abstract class KeyStoreHmacSpi extends MacSpi implements KeyStoreCryptoOperation {

    public static class HmacSHA1 extends KeyStoreHmacSpi {
        public HmacSHA1() {
            super(KeymasterDefs.KM_DIGEST_SHA1);
        }
    }

    public static class HmacSHA224 extends KeyStoreHmacSpi {
        public HmacSHA224() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_224);
        }
    }

    public static class HmacSHA256 extends KeyStoreHmacSpi {
        public HmacSHA256() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_256);
        }
    }

    public static class HmacSHA384 extends KeyStoreHmacSpi {
        public HmacSHA384() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_384);
        }
    }

    public static class HmacSHA512 extends KeyStoreHmacSpi {
        public HmacSHA512() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_512);
        }
    }

    private final KeyStore mKeyStore = KeyStore.getInstance();
    private final int mKeymasterDigest;
    private final int mMacSizeBytes;

    // Fields below are populated by engineInit and should be preserved after engineDoFinal.
    private KeyStoreSecretKey mKey;

    // Fields below are reset when engineDoFinal succeeds.
    private KeyStoreCryptoOperationChunkedStreamer mChunkedStreamer;
    private IBinder mOperationToken;
    private Long mOperationHandle;

    protected KeyStoreHmacSpi(int keymasterDigest) {
        mKeymasterDigest = keymasterDigest;
        mMacSizeBytes = KeymasterUtils.getDigestOutputSizeBytes(keymasterDigest);
    }

    @Override
    protected int engineGetMacLength() {
        return mMacSizeBytes;
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
        } else if (!(key instanceof KeyStoreSecretKey)) {
            throw new InvalidKeyException(
                    "Only Android KeyStore secret keys supported. Key: " + key);
        }
        mKey = (KeyStoreSecretKey) key;

        if (params != null) {
            throw new InvalidAlgorithmParameterException(
                    "Unsupported algorithm parameters: " + params);
        }

    }

    private void resetAll() {
        mKey = null;
        IBinder operationToken = mOperationToken;
        if (operationToken != null) {
            mOperationToken = null;
            mKeyStore.abort(operationToken);
        }
        mOperationHandle = null;
        mChunkedStreamer = null;
    }

    private void resetWhilePreservingInitState() {
        IBinder operationToken = mOperationToken;
        if (operationToken != null) {
            mOperationToken = null;
            mKeyStore.abort(operationToken);
        }
        mOperationHandle = null;
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
        keymasterArgs.addInt(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_HMAC);
        keymasterArgs.addInt(KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigest);

        OperationResult opResult = mKeyStore.begin(
                mKey.getAlias(),
                KeymasterDefs.KM_PURPOSE_SIGN,
                true,
                keymasterArgs,
                null,
                new KeymasterArguments());
        if (opResult == null) {
            throw new KeyStoreConnectException();
        } else if (opResult.resultCode != KeyStore.NO_ERROR) {
            throw KeyStore.getInvalidKeyException(opResult.resultCode);
        }
        if (opResult.token == null) {
            throw new IllegalStateException("Keystore returned null operation token");
        }
        mOperationToken = opResult.token;
        mOperationHandle = opResult.operationHandle;
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
            throw new IllegalStateException("Failed to reinitialize MAC", e);
        }

        byte[] output;
        try {
            output = mChunkedStreamer.update(input, offset, len);
        } catch (KeyStoreException e) {
            throw new IllegalStateException("Keystore operation failed", e);
        }
        if ((output != null) && (output.length != 0)) {
            throw new IllegalStateException("Update operation unexpectedly produced output");
        }
    }

    @Override
    protected byte[] engineDoFinal() {
        try {
            ensureKeystoreOperationInitialized();
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("Failed to reinitialize MAC", e);
        }

        byte[] result;
        try {
            result = mChunkedStreamer.doFinal(null, 0, 0);
        } catch (KeyStoreException e) {
            throw new IllegalStateException("Keystore operation failed", e);
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
    public Long getOperationHandle() {
        return mOperationHandle;
    }
}
