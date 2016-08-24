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

import android.annotation.NonNull;
import android.os.IBinder;
import android.security.KeyStore;
import android.security.KeyStoreException;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;

import libcore.util.EmptyArray;

import java.io.ByteArrayOutputStream;
import java.security.InvalidKeyException;
import java.security.SignatureSpi;

/**
 * Base class for {@link SignatureSpi} providing Android KeyStore backed ECDSA signatures.
 *
 * @hide
 */
abstract class AndroidKeyStoreECDSASignatureSpi extends AndroidKeyStoreSignatureSpiBase {

    public final static class NONE extends AndroidKeyStoreECDSASignatureSpi {
        public NONE() {
            super(KeymasterDefs.KM_DIGEST_NONE);
        }

        @Override
        protected KeyStoreCryptoOperationStreamer createMainDataStreamer(KeyStore keyStore,
                IBinder operationToken) {
            return new TruncateToFieldSizeMessageStreamer(
                    super.createMainDataStreamer(keyStore, operationToken),
                    getGroupSizeBits());
        }

        /**
         * Streamer which buffers all input, then truncates it to field size, and then sends it into
         * KeyStore via the provided delegate streamer.
         */
        private static class TruncateToFieldSizeMessageStreamer
                implements KeyStoreCryptoOperationStreamer {

            private final KeyStoreCryptoOperationStreamer mDelegate;
            private final int mGroupSizeBits;
            private final ByteArrayOutputStream mInputBuffer = new ByteArrayOutputStream();
            private long mConsumedInputSizeBytes;

            private TruncateToFieldSizeMessageStreamer(
                    KeyStoreCryptoOperationStreamer delegate,
                    int groupSizeBits) {
                mDelegate = delegate;
                mGroupSizeBits = groupSizeBits;
            }

            @Override
            public byte[] update(byte[] input, int inputOffset, int inputLength)
                    throws KeyStoreException {
                if (inputLength > 0) {
                    mInputBuffer.write(input, inputOffset, inputLength);
                    mConsumedInputSizeBytes += inputLength;
                }
                return EmptyArray.BYTE;
            }

            @Override
            public byte[] doFinal(byte[] input, int inputOffset, int inputLength, byte[] signature,
                    byte[] additionalEntropy) throws KeyStoreException {
                if (inputLength > 0) {
                    mConsumedInputSizeBytes += inputLength;
                    mInputBuffer.write(input, inputOffset, inputLength);
                }

                byte[] bufferedInput = mInputBuffer.toByteArray();
                mInputBuffer.reset();
                // Truncate input at field size (bytes)
                return mDelegate.doFinal(bufferedInput,
                        0,
                        Math.min(bufferedInput.length, ((mGroupSizeBits + 7) / 8)),
                        signature, additionalEntropy);
            }

            @Override
            public long getConsumedInputSizeBytes() {
                return mConsumedInputSizeBytes;
            }

            @Override
            public long getProducedOutputSizeBytes() {
                return mDelegate.getProducedOutputSizeBytes();
            }
        }
    }

    public final static class SHA1 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA1() {
            super(KeymasterDefs.KM_DIGEST_SHA1);
        }
    }

    public final static class SHA224 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA224() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_224);
        }
    }

    public final static class SHA256 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA256() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_256);
        }
    }

    public final static class SHA384 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA384() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_384);
        }
    }

    public final static class SHA512 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA512() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_512);
        }
    }

    private final int mKeymasterDigest;

    private int mGroupSizeBits = -1;

    AndroidKeyStoreECDSASignatureSpi(int keymasterDigest) {
        mKeymasterDigest = keymasterDigest;
    }

    @Override
    protected final void initKey(AndroidKeyStoreKey key) throws InvalidKeyException {
        if (!KeyProperties.KEY_ALGORITHM_EC.equalsIgnoreCase(key.getAlgorithm())) {
            throw new InvalidKeyException("Unsupported key algorithm: " + key.getAlgorithm()
                    + ". Only" + KeyProperties.KEY_ALGORITHM_EC + " supported");
        }

        KeyCharacteristics keyCharacteristics = new KeyCharacteristics();
        int errorCode = getKeyStore().getKeyCharacteristics(
                key.getAlias(), null, null, key.getUid(), keyCharacteristics);
        if (errorCode != KeyStore.NO_ERROR) {
            throw getKeyStore().getInvalidKeyException(key.getAlias(), key.getUid(), errorCode);
        }
        long keySizeBits = keyCharacteristics.getUnsignedInt(KeymasterDefs.KM_TAG_KEY_SIZE, -1);
        if (keySizeBits == -1) {
            throw new InvalidKeyException("Size of key not known");
        } else if (keySizeBits > Integer.MAX_VALUE) {
            throw new InvalidKeyException("Key too large: " + keySizeBits + " bits");
        }
        mGroupSizeBits = (int) keySizeBits;

        super.initKey(key);
    }

    @Override
    protected final void resetAll() {
        mGroupSizeBits = -1;
        super.resetAll();
    }

    @Override
    protected final void resetWhilePreservingInitState() {
        super.resetWhilePreservingInitState();
    }

    @Override
    protected final void addAlgorithmSpecificParametersToBegin(
            @NonNull KeymasterArguments keymasterArgs) {
        keymasterArgs.addEnum(KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_EC);
        keymasterArgs.addEnum(KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigest);
    }

    @Override
    protected final int getAdditionalEntropyAmountForSign() {
        return (mGroupSizeBits + 7) / 8;
    }

    protected final int getGroupSizeBits() {
        if (mGroupSizeBits == -1) {
            throw new IllegalStateException("Not initialized");
        }
        return mGroupSizeBits;
    }
}
