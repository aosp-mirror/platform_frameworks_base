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
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.ArrayUtils;
import android.security.keystore.KeyProperties;

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
import javax.crypto.spec.IvParameterSpec;

/**
 * Base class for Android Keystore unauthenticated AES {@link CipherSpi} implementations.
 *
 * @hide
 */
abstract class AndroidKeyStoreUnauthenticatedAESCipherSpi extends AndroidKeyStoreCipherSpiBase {

    abstract static class ECB extends AndroidKeyStoreUnauthenticatedAESCipherSpi {
        protected ECB(int keymasterPadding) {
            super(KeymasterDefs.KM_MODE_ECB, keymasterPadding, false);
        }

        public static class NoPadding extends ECB {
            public NoPadding() {
                super(KeymasterDefs.KM_PAD_NONE);
            }

            @Override
            protected final String getTransform() {
                return "AES/ECB/NoPadding";
            }
        }

        public static class PKCS7Padding extends ECB {
            public PKCS7Padding() {
                super(KeymasterDefs.KM_PAD_PKCS7);
            }

            @Override
            protected final String getTransform() {
                return "AES/ECB/PKCS7Padding";
            }
        }
    }

    abstract static class CBC extends AndroidKeyStoreUnauthenticatedAESCipherSpi {
        protected CBC(int keymasterPadding) {
            super(KeymasterDefs.KM_MODE_CBC, keymasterPadding, true);
        }

        public static class NoPadding extends CBC {
            public NoPadding() {
                super(KeymasterDefs.KM_PAD_NONE);
            }

            @Override
            protected final String getTransform() {
                return "AES/CBC/NoPadding";
            }
        }

        public static class PKCS7Padding extends CBC {
            public PKCS7Padding() {
                super(KeymasterDefs.KM_PAD_PKCS7);
            }

            @Override
            protected final String getTransform() {
                return "AES/CBC/PKCS7Padding";
            }
        }
    }

    abstract static class CTR extends AndroidKeyStoreUnauthenticatedAESCipherSpi {
        protected CTR(int keymasterPadding) {
            super(KeymasterDefs.KM_MODE_CTR, keymasterPadding, true);
        }

        public static class NoPadding extends CTR {
            public NoPadding() {
                super(KeymasterDefs.KM_PAD_NONE);
            }

            @Override
            protected final String getTransform() {
                return "AES/CTR/NoPadding";
            }
        }
    }

    private static final int BLOCK_SIZE_BYTES = 16;

    private final int mKeymasterBlockMode;
    private final int mKeymasterPadding;
    /** Whether this transformation requires an IV. */
    private final boolean mIvRequired;

    private byte[] mIv;

    /** Whether the current {@code #mIv} has been used by the underlying crypto operation. */
    private boolean mIvHasBeenUsed;

    AndroidKeyStoreUnauthenticatedAESCipherSpi(
            int keymasterBlockMode,
            int keymasterPadding,
            boolean ivRequired) {
        mKeymasterBlockMode = keymasterBlockMode;
        mKeymasterPadding = keymasterPadding;
        mIvRequired = ivRequired;
    }

    @Override
    protected final void resetAll() {
        mIv = null;
        mIvHasBeenUsed = false;
        super.resetAll();
    }

    @Override
    protected final void resetWhilePreservingInitState() {
        super.resetWhilePreservingInitState();
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
    protected final void initAlgorithmSpecificParameters() throws InvalidKeyException {
        if (!mIvRequired) {
            return;
        }

        // IV is used
        if (!isEncrypting()) {
            throw new InvalidKeyException("IV required when decrypting"
                    + ". Use IvParameterSpec or AlgorithmParameters to provide it.");
        }
    }

    @Override
    protected final void initAlgorithmSpecificParameters(AlgorithmParameterSpec params)
            throws InvalidAlgorithmParameterException {
        if (!mIvRequired) {
            if (params != null) {
                throw new InvalidAlgorithmParameterException("Unsupported parameters: " + params);
            }
            return;
        }

        // IV is used
        if (params == null) {
            if (!isEncrypting()) {
                // IV must be provided by the caller
                throw new InvalidAlgorithmParameterException(
                        "IvParameterSpec must be provided when decrypting");
            }
            return;
        }
        if (!(params instanceof IvParameterSpec)) {
            throw new InvalidAlgorithmParameterException("Only IvParameterSpec supported");
        }
        mIv = ((IvParameterSpec) params).getIV();
        if (mIv == null) {
            throw new InvalidAlgorithmParameterException("Null IV in IvParameterSpec");
        }
    }

    @Override
    protected final void initAlgorithmSpecificParameters(AlgorithmParameters params)
            throws InvalidAlgorithmParameterException {
        if (!mIvRequired) {
            if (params != null) {
                throw new InvalidAlgorithmParameterException("Unsupported parameters: " + params);
            }
            return;
        }

        // IV is used
        if (params == null) {
            if (!isEncrypting()) {
                // IV must be provided by the caller
                throw new InvalidAlgorithmParameterException("IV required when decrypting"
                        + ". Use IvParameterSpec or AlgorithmParameters to provide it.");
            }
            return;
        }

        if (!"AES".equalsIgnoreCase(params.getAlgorithm())) {
            throw new InvalidAlgorithmParameterException(
                    "Unsupported AlgorithmParameters algorithm: " + params.getAlgorithm()
                    + ". Supported: AES");
        }

        IvParameterSpec ivSpec;
        try {
            ivSpec = params.getParameterSpec(IvParameterSpec.class);
        } catch (InvalidParameterSpecException e) {
            if (!isEncrypting()) {
                // IV must be provided by the caller
                throw new InvalidAlgorithmParameterException("IV required when decrypting"
                        + ", but not found in parameters: " + params, e);
            }
            mIv = null;
            return;
        }
        mIv = ivSpec.getIV();
        if (mIv == null) {
            throw new InvalidAlgorithmParameterException("Null IV in AlgorithmParameters");
        }
    }

    @Override
    protected final int getAdditionalEntropyAmountForBegin() {
        if ((mIvRequired) && (mIv == null) && (isEncrypting())) {
            // IV will need to be generated
            return BLOCK_SIZE_BYTES;
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
        if ((isEncrypting()) && (mIvRequired) && (mIvHasBeenUsed)) {
            // IV is being reused for encryption: this violates security best practices.
            throw new IllegalStateException(
                    "IV has already been used. Reusing IV in encryption mode violates security best"
                    + " practices.");
        }

        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_ALGORITHM, KeymasterDefs.KM_ALGORITHM_AES
        ));
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_BLOCK_MODE, mKeymasterBlockMode
        ));
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                KeymasterDefs.KM_TAG_PADDING, mKeymasterPadding
        ));
        if ((mIvRequired) && (mIv != null)) {
            parameters.add(KeyStore2ParameterUtils.makeBytes(
                    KeymasterDefs.KM_TAG_NONCE, mIv
            ));
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

        if (mIvRequired) {
            if (mIv == null) {
                mIv = returnedIv;
            } else if ((returnedIv != null) && (!Arrays.equals(returnedIv, mIv))) {
                throw new ProviderException("IV in use differs from provided IV");
            }
        } else {
            if (returnedIv != null) {
                throw new ProviderException(
                        "IV in use despite IV not being used by this transformation");
            }
        }
    }

    @Override
    protected final int engineGetBlockSize() {
        return BLOCK_SIZE_BYTES;
    }

    @Override
    protected final int engineGetOutputSize(int inputLen) {
        return inputLen + 3 * BLOCK_SIZE_BYTES;
    }

    @Override
    protected final byte[] engineGetIV() {
        return ArrayUtils.cloneIfNotEmpty(mIv);
    }

    @Nullable
    @Override
    protected final AlgorithmParameters engineGetParameters() {
        if (!mIvRequired) {
            return null;
        }
        if ((mIv != null) && (mIv.length > 0)) {
            try {
                AlgorithmParameters params = AlgorithmParameters.getInstance("AES");
                params.init(new IvParameterSpec(mIv));
                return params;
            } catch (NoSuchAlgorithmException e) {
                throw new ProviderException(
                        "Failed to obtain AES AlgorithmParameters", e);
            } catch (InvalidParameterSpecException e) {
                throw new ProviderException(
                        "Failed to initialize AES AlgorithmParameters with an IV",
                        e);
            }
        }
        return null;
    }
}
