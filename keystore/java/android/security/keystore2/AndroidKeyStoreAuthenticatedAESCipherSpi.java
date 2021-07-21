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
import android.annotation.Nullable;
import android.hardware.security.keymint.KeyParameter;
import android.security.KeyStoreException;
import android.security.KeyStoreOperation;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.ArrayUtils;
import android.security.keystore.KeyProperties;
import android.security.keystore2.KeyStoreCryptoOperationChunkedStreamer.Stream;

import libcore.util.EmptyArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.util.Arrays;
import java.util.List;

import javax.crypto.CipherSpi;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Base class for Android Keystore authenticated AES {@link CipherSpi} implementations.
 *
 * @hide
 */
abstract class AndroidKeyStoreAuthenticatedAESCipherSpi extends AndroidKeyStoreCipherSpiBase {

    abstract static class GCM extends AndroidKeyStoreAuthenticatedAESCipherSpi {
        static final int MIN_SUPPORTED_TAG_LENGTH_BITS = 96;
        private static final int MAX_SUPPORTED_TAG_LENGTH_BITS = 128;
        private static final int DEFAULT_TAG_LENGTH_BITS = 128;
        private static final int IV_LENGTH_BYTES = 12;

        private int mTagLengthBits = DEFAULT_TAG_LENGTH_BITS;

        GCM(int keymasterPadding) {
            super(KeymasterDefs.KM_MODE_GCM, keymasterPadding);
        }

        @Override
        protected final String getTransform() {
            return "AES/GCM/NoPadding";
        }

        @Override
        protected final void resetAll() {
            mTagLengthBits = DEFAULT_TAG_LENGTH_BITS;
            super.resetAll();
        }

        @Override
        protected final void resetWhilePreservingInitState() {
            super.resetWhilePreservingInitState();
        }

        @Override
        protected final void initAlgorithmSpecificParameters() throws InvalidKeyException {
            if (!isEncrypting()) {
                throw new InvalidKeyException("IV required when decrypting"
                        + ". Use IvParameterSpec or AlgorithmParameters to provide it.");
            }
        }

        @Override
        protected final void initAlgorithmSpecificParameters(AlgorithmParameterSpec params)
                throws InvalidAlgorithmParameterException {
            // IV is used
            if (params == null) {
                if (!isEncrypting()) {
                    // IV must be provided by the caller
                    throw new InvalidAlgorithmParameterException(
                            "GCMParameterSpec must be provided when decrypting");
                }
                return;
            }
            if (!(params instanceof GCMParameterSpec)) {
                throw new InvalidAlgorithmParameterException("Only GCMParameterSpec supported");
            }
            GCMParameterSpec spec = (GCMParameterSpec) params;
            byte[] iv = spec.getIV();
            if (iv == null) {
                throw new InvalidAlgorithmParameterException("Null IV in GCMParameterSpec");
            } else if (iv.length != IV_LENGTH_BYTES) {
                throw new InvalidAlgorithmParameterException("Unsupported IV length: "
                        + iv.length + " bytes. Only " + IV_LENGTH_BYTES
                        + " bytes long IV supported");
            }
            int tagLengthBits = spec.getTLen();
            if ((tagLengthBits < MIN_SUPPORTED_TAG_LENGTH_BITS)
                    || (tagLengthBits > MAX_SUPPORTED_TAG_LENGTH_BITS)
                    || ((tagLengthBits % 8) != 0)) {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported tag length: " + tagLengthBits + " bits"
                        + ". Supported lengths: 96, 104, 112, 120, 128");
            }
            setIv(iv);
            mTagLengthBits = tagLengthBits;
        }

        @Override
        protected final void initAlgorithmSpecificParameters(AlgorithmParameters params)
                throws InvalidAlgorithmParameterException {
            if (params == null) {
                if (!isEncrypting()) {
                    // IV must be provided by the caller
                    throw new InvalidAlgorithmParameterException("IV required when decrypting"
                            + ". Use GCMParameterSpec or GCM AlgorithmParameters to provide it.");
                }
                return;
            }

            if (!"GCM".equalsIgnoreCase(params.getAlgorithm())) {
                throw new InvalidAlgorithmParameterException(
                        "Unsupported AlgorithmParameters algorithm: " + params.getAlgorithm()
                        + ". Supported: GCM");
            }

            GCMParameterSpec spec;
            try {
                spec = params.getParameterSpec(GCMParameterSpec.class);
            } catch (InvalidParameterSpecException e) {
                if (!isEncrypting()) {
                    // IV must be provided by the caller
                    throw new InvalidAlgorithmParameterException("IV and tag length required when"
                            + " decrypting, but not found in parameters: " + params, e);
                }
                setIv(null);
                return;
            }
            initAlgorithmSpecificParameters(spec);
        }

        @Nullable
        @Override
        protected final AlgorithmParameters engineGetParameters() {
            byte[] iv = getIv();
            if ((iv != null) && (iv.length > 0)) {
                try {
                    AlgorithmParameters params = AlgorithmParameters.getInstance("GCM");
                    params.init(new GCMParameterSpec(mTagLengthBits, iv));
                    return params;
                } catch (NoSuchAlgorithmException e) {
                    throw new ProviderException(
                            "Failed to obtain GCM AlgorithmParameters", e);
                } catch (InvalidParameterSpecException e) {
                    throw new ProviderException(
                            "Failed to initialize GCM AlgorithmParameters", e);
                }
            }
            return null;
        }

        @NonNull
        @Override
        protected KeyStoreCryptoOperationStreamer createMainDataStreamer(
                KeyStoreOperation operation) {
            KeyStoreCryptoOperationStreamer streamer = new KeyStoreCryptoOperationChunkedStreamer(
                    new KeyStoreCryptoOperationChunkedStreamer.MainDataStream(
                            operation), 0);
            if (isEncrypting()) {
                return streamer;
            } else {
                // When decrypting, to avoid leaking unauthenticated plaintext, do not return any
                // plaintext before ciphertext is authenticated by KeyStore.finish.
                return new BufferAllOutputUntilDoFinalStreamer(streamer);
            }
        }

        @NonNull
        @Override
        protected final KeyStoreCryptoOperationStreamer createAdditionalAuthenticationDataStreamer(
                KeyStoreOperation operation) {
            return new KeyStoreCryptoOperationChunkedStreamer(
                    new AdditionalAuthenticationDataStream(operation), 0);
        }

        @Override
        protected final int getAdditionalEntropyAmountForBegin() {
            if ((getIv() == null) && (isEncrypting())) {
                // IV will need to be generated
                return IV_LENGTH_BYTES;
            }

            return 0;
        }

        @Override
        protected final int getAdditionalEntropyAmountForFinish() {
            return 0;
        }

        @Override
        protected final void addAlgorithmSpecificParametersToBegin(
                @NonNull List<KeyParameter> parameters) {
            super.addAlgorithmSpecificParametersToBegin(parameters);
            parameters.add(KeyStore2ParameterUtils.makeInt(
                    KeymasterDefs.KM_TAG_MAC_LENGTH,
                    mTagLengthBits
            ));
        }

        protected final int getTagLengthBits() {
            return mTagLengthBits;
        }

        public static final class NoPadding extends GCM {
            public NoPadding() {
                super(KeymasterDefs.KM_PAD_NONE);
            }

            @Override
            protected final int engineGetOutputSize(int inputLen) {
                int tagLengthBytes = (getTagLengthBits() + 7) / 8;
                long result;
                if (isEncrypting()) {
                    result = getConsumedInputSizeBytes() - getProducedOutputSizeBytes() + inputLen
                            + tagLengthBytes;
                } else {
                    result = getConsumedInputSizeBytes() - getProducedOutputSizeBytes() + inputLen
                            - tagLengthBytes;
                }
                if (result < 0) {
                    return 0;
                } else if (result > Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
                return (int) result;
            }
        }
    }

    private static final int BLOCK_SIZE_BYTES = 16;

    private final int mKeymasterBlockMode;
    private final int mKeymasterPadding;

    private byte[] mIv;

    /** Whether the current {@code #mIv} has been used by the underlying crypto operation. */
    private boolean mIvHasBeenUsed;

    AndroidKeyStoreAuthenticatedAESCipherSpi(
            int keymasterBlockMode,
            int keymasterPadding) {
        mKeymasterBlockMode = keymasterBlockMode;
        mKeymasterPadding = keymasterPadding;
    }

    @Override
    protected void resetAll() {
        mIv = null;
        mIvHasBeenUsed = false;
        super.resetAll();
    }

    @Override
    protected final void initKey(int opmode, Key key) throws InvalidKeyException {
        if (!(key instanceof AndroidKeyStoreSecretKey)) {
            throw new InvalidKeyException(
                    "Unsupported key: " + ((key != null) ? key.getClass().getName() : "null"));
        }
        if (!KeyProperties.KEY_ALGORITHM_AES.equalsIgnoreCase(key.getAlgorithm())) {
            throw new InvalidKeyException(
                    "Unsupported key algorithm: " + key.getAlgorithm() + ". Only " +
                    KeyProperties.KEY_ALGORITHM_AES + " supported");
        }
        setKey((AndroidKeyStoreSecretKey) key);
    }

    @Override
    protected void addAlgorithmSpecificParametersToBegin(
            @NonNull List<KeyParameter> parameters) {
        if ((isEncrypting()) && (mIvHasBeenUsed)) {
            // IV is being reused for encryption: this violates security best practices.
            throw new IllegalStateException(
                    "IV has already been used. Reusing IV in encryption mode violates security best"
                    + " practices.");
        }
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_ALGORITHM,
                KeymasterDefs.KM_ALGORITHM_AES
        ));
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_BLOCK_MODE,
                mKeymasterBlockMode
        ));
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_PADDING,
                mKeymasterPadding
        ));

        if (mIv != null) {
            parameters.add(KeyStore2ParameterUtils.makeBytes(KeymasterDefs.KM_TAG_NONCE, mIv));
        }
    }

    @Override
    protected final void loadAlgorithmSpecificParametersFromBeginResult(
            KeyParameter[] parameters) {
        mIvHasBeenUsed = true;

        // NOTE: Keymaster doesn't always return an IV, even if it's used.
        byte[] returnedIv = null;
        if (parameters != null) {
            for (KeyParameter p : parameters) {
                if (p.tag == KeymasterDefs.KM_TAG_NONCE) {
                    returnedIv = p.value.getBlob();
                    break;
                }
            }
        }

        if (mIv == null) {
            mIv = returnedIv;
        } else if ((returnedIv != null) && (!Arrays.equals(returnedIv, mIv))) {
            throw new ProviderException("IV in use differs from provided IV");
        }
    }

    @Override
    protected final int engineGetBlockSize() {
        return BLOCK_SIZE_BYTES;
    }

    @Override
    protected final byte[] engineGetIV() {
        return ArrayUtils.cloneIfNotEmpty(mIv);
    }

    protected void setIv(byte[] iv) {
        mIv = iv;
    }

    protected byte[] getIv() {
        return mIv;
    }

    /**
     * {@link KeyStoreCryptoOperationStreamer} which buffers all output until {@code doFinal} from
     * which it returns all output in one go, provided {@code doFinal} succeeds.
     */
    private static class BufferAllOutputUntilDoFinalStreamer
        implements KeyStoreCryptoOperationStreamer {

        private final KeyStoreCryptoOperationStreamer mDelegate;
        private ByteArrayOutputStream mBufferedOutput = new ByteArrayOutputStream();
        private long mProducedOutputSizeBytes;

        private BufferAllOutputUntilDoFinalStreamer(KeyStoreCryptoOperationStreamer delegate) {
            mDelegate = delegate;
        }

        @Override
        public byte[] update(byte[] input, int inputOffset, int inputLength)
                throws KeyStoreException {
            byte[] output = mDelegate.update(input, inputOffset, inputLength);
            if (output != null) {
                try {
                    mBufferedOutput.write(output);
                } catch (IOException e) {
                    throw new ProviderException("Failed to buffer output", e);
                }
            }
            return EmptyArray.BYTE;
        }

        @Override
        public byte[] doFinal(byte[] input, int inputOffset, int inputLength,
                byte[] signature) throws KeyStoreException {
            byte[] output = mDelegate.doFinal(input, inputOffset, inputLength, signature);
            if (output != null) {
                try {
                    mBufferedOutput.write(output);
                } catch (IOException e) {
                    throw new ProviderException("Failed to buffer output", e);
                }
            }
            byte[] result = mBufferedOutput.toByteArray();
            mBufferedOutput.reset();
            mProducedOutputSizeBytes += result.length;
            return result;
        }

        @Override
        public long getConsumedInputSizeBytes() {
            return mDelegate.getConsumedInputSizeBytes();
        }

        @Override
        public long getProducedOutputSizeBytes() {
            return mProducedOutputSizeBytes;
        }
    }

    /**
     * Additional Authentication Data (AAD) stream via a KeyStore streaming operation. This stream
     * sends AAD into the KeyStore.
     */
    private static class AdditionalAuthenticationDataStream implements Stream {

        private final KeyStoreOperation mOperation;

        private AdditionalAuthenticationDataStream(KeyStoreOperation operation) {
            mOperation = operation;
        }

        @Override
        public byte[] update(byte[] input) throws KeyStoreException {
            mOperation.updateAad(input);
            return null;
        }

        @Override
        public byte[] finish(byte[] input, byte[] signature) {
            return null;
        }
    }
}