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
    private Long mOperationHandle;

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
        if (mKeyAliasInKeyStore == null) {
            throw new InvalidKeyException("Key's KeyStore alias not known");
        }
        engineReset();
        ensureKeystoreOperationInitialized();
    }

    @Override
    protected void engineReset() {
        IBinder operationToken = mOperationToken;
        if (operationToken != null) {
            mOperationToken = null;
            mKeyStore.abort(operationToken);
        }
        mOperationHandle = null;
        mChunkedStreamer = null;
    }

    private void ensureKeystoreOperationInitialized() {
        if (mChunkedStreamer != null) {
            return;
        }
        if (mKeyAliasInKeyStore == null) {
            throw new IllegalStateException("Not initialized");
        }

        KeymasterArguments keymasterArgs = new KeymasterArguments();
        keymasterArgs.addInt(KeymasterDefs.KM_TAG_ALGORITHM, KeyStoreKeyConstraints.Algorithm.HMAC);
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
            throw KeyStore.getCryptoOperationException(opResult.resultCode);
        }
        if (opResult.token == null) {
            throw new CryptoOperationException("Keystore returned null operation token");
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
        ensureKeystoreOperationInitialized();

        byte[] output;
        try {
            output = mChunkedStreamer.update(input, offset, len);
        } catch (KeyStoreException e) {
            throw KeyStore.getCryptoOperationException(e);
        }
        if ((output != null) && (output.length != 0)) {
            throw new CryptoOperationException("Update operation unexpectedly produced output");
        }
    }

    @Override
    protected byte[] engineDoFinal() {
        ensureKeystoreOperationInitialized();

        byte[] result;
        try {
            result = mChunkedStreamer.doFinal(null, 0, 0);
        } catch (KeyStoreException e) {
            throw KeyStore.getCryptoOperationException(e);
        }

        engineReset();
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
