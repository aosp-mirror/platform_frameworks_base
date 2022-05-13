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

import android.annotation.NonNull;
import android.hardware.security.keymint.KeyParameter;
import android.security.KeyStoreException;
import android.security.KeyStoreOperation;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.KeyProperties;
import android.system.keystore2.Authorization;

import libcore.util.EmptyArray;

import java.io.ByteArrayOutputStream;
import java.security.InvalidKeyException;
import java.security.SignatureSpi;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Base class for {@link SignatureSpi} providing Android KeyStore backed ECDSA signatures.
 *
 * @hide
 */
abstract class AndroidKeyStoreECDSASignatureSpi extends AndroidKeyStoreSignatureSpiBase {
    private static final Set<String> ACCEPTED_SIGNING_SCHEMES = Set.of(
            KeyProperties.KEY_ALGORITHM_EC.toLowerCase(),
            NamedParameterSpec.ED25519.getName().toLowerCase(),
            "eddsa");

    public final static class NONE extends AndroidKeyStoreECDSASignatureSpi {
        public NONE() {
            super(KeymasterDefs.KM_DIGEST_NONE);
        }

        @Override
        protected String getAlgorithm() {
            return "NONEwithECDSA";
        }

        @Override
        protected KeyStoreCryptoOperationStreamer createMainDataStreamer(
                KeyStoreOperation operation) {
            return new TruncateToFieldSizeMessageStreamer(
                    super.createMainDataStreamer(operation),
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
            public byte[] doFinal(byte[] input, int inputOffset, int inputLength, byte[] signature)
                    throws KeyStoreException {
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
                        signature);
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

    public static final class Ed25519 extends AndroidKeyStoreECDSASignatureSpi {
        public Ed25519() {
            // Ed25519 uses an internal digest system.
            super(KeymasterDefs.KM_DIGEST_NONE);
        }

        @Override
        protected String getAlgorithm() {
            return NamedParameterSpec.ED25519.getName();
        }
    }

    public final static class SHA1 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA1() {
            super(KeymasterDefs.KM_DIGEST_SHA1);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA1withECDSA";
        }
    }

    public final static class SHA224 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA224() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_224);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA224withECDSA";
        }
    }

    public final static class SHA256 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA256() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_256);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA256withECDSA";
        }
    }

    public final static class SHA384 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA384() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_384);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA384withECDSA";
        }
    }

    public final static class SHA512 extends AndroidKeyStoreECDSASignatureSpi {
        public SHA512() {
            super(KeymasterDefs.KM_DIGEST_SHA_2_512);
        }
        @Override
        protected String getAlgorithm() {
            return "SHA512withECDSA";
        }
    }

    private final int mKeymasterDigest;

    private int mGroupSizeBits = -1;

    AndroidKeyStoreECDSASignatureSpi(int keymasterDigest) {
        mKeymasterDigest = keymasterDigest;
    }

    @Override
    protected final void initKey(AndroidKeyStoreKey key) throws InvalidKeyException {
        if (!ACCEPTED_SIGNING_SCHEMES.contains(key.getAlgorithm().toLowerCase())) {
            throw new InvalidKeyException("Unsupported key algorithm: " + key.getAlgorithm()
                    + ". Only" + Arrays.toString(ACCEPTED_SIGNING_SCHEMES.stream().toArray())
                    + " supported");
        }

        long keySizeBits = -1;
        for (Authorization a : key.getAuthorizations()) {
            if (a.keyParameter.tag == KeymasterDefs.KM_TAG_KEY_SIZE) {
                keySizeBits = KeyStore2ParameterUtils.getUnsignedInt(a);
            }
        }

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
            @NonNull List<KeyParameter> parameters) {
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_EC
        ));
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_DIGEST, mKeymasterDigest
        ));
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
