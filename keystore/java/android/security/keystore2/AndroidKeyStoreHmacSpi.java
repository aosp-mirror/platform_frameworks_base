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

package android.security.keystore2;

import android.hardware.security.keymint.KeyParameter;
import android.security.KeyStoreException;
import android.security.KeyStoreOperation;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.KeyStoreCryptoOperation;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.ProviderException;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.MacSpi;

/**
 * {@link MacSpi} which provides HMAC implementations backed by Android KeyStore.
 *
 * @hide
 */
public abstract class AndroidKeyStoreHmacSpi extends MacSpi implements KeyStoreCryptoOperation {

    private static final String TAG = "AndroidKeyStoreHmacSpi";

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

    private final int mKeymasterDigest;
    private final int mMacSizeBits;

    // Fields below are populated by engineInit and should be preserved after engineDoFinal.
    private AndroidKeyStoreSecretKey mKey;

    // Fields below are reset when engineDoFinal succeeds.
    private KeyStoreCryptoOperationChunkedStreamer mChunkedStreamer;
    private KeyStoreOperation mOperation;
    private long mOperationChallenge;

    protected AndroidKeyStoreHmacSpi(int keymasterDigest) {
        mKeymasterDigest = keymasterDigest;
        mMacSizeBits = KeymasterUtils.getDigestOutputSizeBits(keymasterDigest);
        mOperation = null;
        mOperationChallenge = 0;
        mKey = null;
        mChunkedStreamer = null;
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

    private void abortOperation() {
        KeyStoreCryptoOperationUtils.abortOperation(mOperation);
        mOperation = null;
    }

    private void resetAll() {
        abortOperation();
        mOperationChallenge = 0;
        mKey = null;
        mChunkedStreamer = null;
    }

    private void resetWhilePreservingInitState() {
        abortOperation();
        mOperationChallenge = 0;
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

        List<KeyParameter> parameters = new ArrayList<>();
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_PURPOSE, KeymasterDefs.KM_PURPOSE_SIGN
        ));
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_HMAC
        ));
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigest
        ));
        parameters.add(KeyStore2ParameterUtils.makeInt(
                KeymasterDefs.KM_TAG_MAC_LENGTH, mMacSizeBits
        ));

        try {
            mOperation = mKey.getSecurityLevel().createOperation(
                    mKey.getKeyIdDescriptor(),
                    parameters
            );
        } catch (KeyStoreException keyStoreException) {
            // If necessary, throw an exception due to KeyStore operation having failed.
            InvalidKeyException e = KeyStoreCryptoOperationUtils.getInvalidKeyException(
                    mKey, keyStoreException);
            if (e != null) {
                throw e;
            }
        }

        // Now we check if we got an operation challenge. This indicates that user authorization
        // is required. And if we got a challenge we check if the authorization can possibly
        // succeed.
        mOperationChallenge = KeyStoreCryptoOperationUtils.getOrMakeOperationChallenge(
                mOperation, mKey);

        mChunkedStreamer = new KeyStoreCryptoOperationChunkedStreamer(
                new KeyStoreCryptoOperationChunkedStreamer.MainDataStream(
                        mOperation));
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
                    null); // no signature provided -- this invocation will generate one
        } catch (KeyStoreException e) {
            throw new ProviderException("Keystore operation failed", e);
        }

        resetWhilePreservingInitState();
        return result;
    }

    @Override
    public void finalize() throws Throwable {
        try {
            abortOperation();
        } finally {
            super.finalize();
        }
    }

    @Override
    public long getOperationHandle() {
        return mOperationChallenge;
    }
}
