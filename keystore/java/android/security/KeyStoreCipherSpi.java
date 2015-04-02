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

import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;

/**
 * Base class for {@link CipherSpi} providing Android KeyStore backed ciphers.
 *
 * @hide
 */
public abstract class KeyStoreCipherSpi extends CipherSpi implements KeyStoreCryptoOperation {

    public abstract static class AES extends KeyStoreCipherSpi {
        protected AES(@KeyStoreKeyConstraints.BlockModeEnum int blockMode,
                @KeyStoreKeyConstraints.PaddingEnum int padding, boolean ivUsed) {
            super(KeyStoreKeyConstraints.Algorithm.AES,
                    blockMode,
                    padding,
                    16,
                    ivUsed);
        }

        public abstract static class ECB extends AES {
            protected ECB(@KeyStoreKeyConstraints.PaddingEnum int padding) {
                super(KeyStoreKeyConstraints.BlockMode.ECB, padding, false);
            }

            public static class NoPadding extends ECB {
                public NoPadding() {
                    super(KeyStoreKeyConstraints.Padding.NONE);
                }
            }

            public static class PKCS7Padding extends ECB {
                public PKCS7Padding() {
                    super(KeyStoreKeyConstraints.Padding.PKCS7);
                }
            }
        }

        public abstract static class CBC extends AES {
            protected CBC(@KeyStoreKeyConstraints.BlockModeEnum int padding) {
                super(KeyStoreKeyConstraints.BlockMode.CBC, padding, true);
            }

            public static class NoPadding extends CBC {
                public NoPadding() {
                    super(KeyStoreKeyConstraints.Padding.NONE);
                }
            }

            public static class PKCS7Padding extends CBC {
                public PKCS7Padding() {
                    super(KeyStoreKeyConstraints.Padding.PKCS7);
                }
            }
        }

        public abstract static class CTR extends AES {
            protected CTR(@KeyStoreKeyConstraints.BlockModeEnum int padding) {
                super(KeyStoreKeyConstraints.BlockMode.CTR, padding, true);
            }

            public static class NoPadding extends CTR {
                public NoPadding() {
                    super(KeyStoreKeyConstraints.Padding.NONE);
                }
            }
        }
    }

    private final KeyStore mKeyStore;
    private final @KeyStoreKeyConstraints.AlgorithmEnum int mAlgorithm;
    private final @KeyStoreKeyConstraints.BlockModeEnum int mBlockMode;
    private final @KeyStoreKeyConstraints.PaddingEnum int mPadding;
    private final int mBlockSizeBytes;
    private final boolean mIvUsed;

    // Fields below are populated by Cipher.init and KeyStore.begin and should be preserved after
    // doFinal finishes.
    protected boolean mEncrypting;
    private KeyStoreSecretKey mKey;
    private SecureRandom mRng;
    private boolean mFirstOperationInitiated;
    byte[] mIv;

    // Fields below must be reset
    private byte[] mAdditionalEntropyForBegin;
    /**
     * Token referencing this operation inside keystore service. It is initialized by
     * {@code engineInit} and is invalidated when {@code engineDoFinal} succeeds and one some
     * error conditions in between.
     */
    private IBinder mOperationToken;
    private Long mOperationHandle;
    private KeyStoreCryptoOperationChunkedStreamer mMainDataStreamer;

    protected KeyStoreCipherSpi(
            @KeyStoreKeyConstraints.AlgorithmEnum int algorithm,
            @KeyStoreKeyConstraints.BlockModeEnum int blockMode,
            @KeyStoreKeyConstraints.PaddingEnum int padding,
            int blockSizeBytes,
            boolean ivUsed) {
        mKeyStore = KeyStore.getInstance();
        mAlgorithm = algorithm;
        mBlockMode = blockMode;
        mPadding = padding;
        mBlockSizeBytes = blockSizeBytes;
        mIvUsed = ivUsed;
    }

    @Override
    protected void engineInit(int opmode, Key key, SecureRandom random) throws InvalidKeyException {
        init(opmode, key, random);
        initAlgorithmSpecificParameters();
        ensureKeystoreOperationInitialized();
    }

    @Override
    protected void engineInit(int opmode, Key key, AlgorithmParameters params, SecureRandom random)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
        init(opmode, key, random);
        initAlgorithmSpecificParameters(params);
        ensureKeystoreOperationInitialized();
    }

    @Override
    protected void engineInit(int opmode, Key key, AlgorithmParameterSpec params,
            SecureRandom random) throws InvalidKeyException, InvalidAlgorithmParameterException {
        init(opmode, key, random);
        initAlgorithmSpecificParameters(params);
        ensureKeystoreOperationInitialized();
    }

    private void init(int opmode, Key key, SecureRandom random) throws InvalidKeyException {
        reset();
        if (!(key instanceof KeyStoreSecretKey)) {
            throw new InvalidKeyException(
                    "Unsupported key: " + ((key != null) ? key.getClass().getName() : "null"));
        }
        mKey = (KeyStoreSecretKey) key;
        mRng = random;
        mIv = null;
        mFirstOperationInitiated = false;

        if ((opmode != Cipher.ENCRYPT_MODE) && (opmode != Cipher.DECRYPT_MODE)) {
            throw new UnsupportedOperationException(
                    "Only ENCRYPT and DECRYPT modes supported. Mode: " + opmode);
        }
        mEncrypting = opmode == Cipher.ENCRYPT_MODE;
    }

    private void reset() {
        IBinder operationToken = mOperationToken;
        if (operationToken != null) {
            mOperationToken = null;
            mKeyStore.abort(operationToken);
        }
        mOperationHandle = null;
        mMainDataStreamer = null;
        mAdditionalEntropyForBegin = null;
    }

    private void ensureKeystoreOperationInitialized() {
        if (mMainDataStreamer != null) {
            return;
        }
        if (mKey == null) {
            throw new IllegalStateException("Not initialized");
        }

        KeymasterArguments keymasterInputArgs = new KeymasterArguments();
        keymasterInputArgs.addInt(KeymasterDefs.KM_TAG_ALGORITHM, mAlgorithm);
        keymasterInputArgs.addInt(KeymasterDefs.KM_TAG_BLOCK_MODE, mBlockMode);
        keymasterInputArgs.addInt(KeymasterDefs.KM_TAG_PADDING, mPadding);
        addAlgorithmSpecificParametersToBegin(keymasterInputArgs);

        KeymasterArguments keymasterOutputArgs = new KeymasterArguments();
        OperationResult opResult = mKeyStore.begin(
                mKey.getAlias(),
                mEncrypting ? KeymasterDefs.KM_PURPOSE_ENCRYPT : KeymasterDefs.KM_PURPOSE_DECRYPT,
                true, // permit aborting this operation if keystore runs out of resources
                keymasterInputArgs,
                mAdditionalEntropyForBegin,
                keymasterOutputArgs);
        mAdditionalEntropyForBegin = null;
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
        loadAlgorithmSpecificParametersFromBeginResult(keymasterOutputArgs);
        mFirstOperationInitiated = true;
        mMainDataStreamer = new KeyStoreCryptoOperationChunkedStreamer(
                new KeyStoreCryptoOperationChunkedStreamer.MainDataStream(
                        mKeyStore, opResult.token));
    }

    @Override
    protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
        ensureKeystoreOperationInitialized();

        if (inputLen == 0) {
            return null;
        }

        byte[] output;
        try {
            output = mMainDataStreamer.update(input, inputOffset, inputLen);
        } catch (KeyStoreException e) {
            throw KeyStore.getCryptoOperationException(e);
        }

        if (output.length == 0) {
            return null;
        }

        return output;
    }

    @Override
    protected int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output,
            int outputOffset) throws ShortBufferException {
        byte[] outputCopy = engineUpdate(input, inputOffset, inputLen);
        if (outputCopy == null) {
            return 0;
        }
        int outputAvailable = output.length - outputOffset;
        if (outputCopy.length > outputAvailable) {
            throw new ShortBufferException("Output buffer too short. Produced: "
                    + outputCopy.length + ", available: " + outputAvailable);
        }
        System.arraycopy(outputCopy, 0, output, outputOffset, outputCopy.length);
        return outputCopy.length;
    }

    @Override
    protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen)
            throws IllegalBlockSizeException, BadPaddingException {
        ensureKeystoreOperationInitialized();

        byte[] output;
        try {
            output = mMainDataStreamer.doFinal(input, inputOffset, inputLen);
        } catch (KeyStoreException e) {
            switch (e.getErrorCode()) {
                case KeymasterDefs.KM_ERROR_INVALID_INPUT_LENGTH:
                    throw new IllegalBlockSizeException();
                case KeymasterDefs.KM_ERROR_INVALID_ARGUMENT:
                    throw new BadPaddingException();
                case KeymasterDefs.KM_ERROR_VERIFICATION_FAILED:
                    throw new AEADBadTagException();
                default:
                    throw KeyStore.getCryptoOperationException(e);
            }
        }

        reset();
        return output;
    }

    @Override
    protected int engineDoFinal(byte[] input, int inputOffset, int inputLen, byte[] output,
            int outputOffset) throws ShortBufferException, IllegalBlockSizeException,
            BadPaddingException {
        byte[] outputCopy = engineDoFinal(input, inputOffset, inputLen);
        if (outputCopy == null) {
            return 0;
        }
        int outputAvailable = output.length - outputOffset;
        if (outputCopy.length > outputAvailable) {
            throw new ShortBufferException("Output buffer too short. Produced: "
                    + outputCopy.length + ", available: " + outputAvailable);
        }
        System.arraycopy(outputCopy, 0, output, outputOffset, outputCopy.length);
        return outputCopy.length;
    }

    @Override
    protected int engineGetBlockSize() {
        return mBlockSizeBytes;
    }

    @Override
    protected byte[] engineGetIV() {
        return (mIv != null) ? mIv.clone() : null;
    }

    @Override
    protected int engineGetOutputSize(int inputLen) {
        return inputLen + 3 * engineGetBlockSize();
    }

    @Override
    protected void engineSetMode(String mode) throws NoSuchAlgorithmException {
        // This should never be invoked because all algorithms registered with the AndroidKeyStore
        // provide explicitly specify block mode.
        throw new UnsupportedOperationException();
    }

    @Override
    protected void engineSetPadding(String arg0) throws NoSuchPaddingException {
        // This should never be invoked because all algorithms registered with the AndroidKeyStore
        // provide explicitly specify padding mode.
        throw new UnsupportedOperationException();
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

    // The methods below may need to be overridden by subclasses that use algorithm-specific
    // parameters.

    /**
     * Returns algorithm-specific parameters used by this {@code CipherSpi} instance or {@code null}
     * if no algorithm-specific parameters are used.
     *
     * <p>This implementation only handles the IV parameter.
     */
    @Override
    protected AlgorithmParameters engineGetParameters() {
        if (!mIvUsed) {
            return null;
        }
        if ((mIv != null) && (mIv.length > 0)) {
            try {
                AlgorithmParameters params = AlgorithmParameters.getInstance("AES");
                params.init(new IvParameterSpec(mIv));
                return params;
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("Failed to obtain AES AlgorithmParameters", e);
            } catch (InvalidParameterSpecException e) {
                throw new RuntimeException(
                        "Failed to initialize AES AlgorithmParameters with an IV", e);
            }
        }
        return null;
    }

    /**
     * Invoked by {@code engineInit} to initialize algorithm-specific parameters. These parameters
     * may need to be stored to be reused after {@code doFinal}.
     *
     * <p>The default implementation only handles the IV parameters.
     *
     * @param params algorithm parameters.
     *
     * @throws InvalidAlgorithmParameterException if some/all of the parameters cannot be
     *         automatically configured and thus {@code Cipher.init} needs to be invoked with
     *         explicitly provided parameters.
     */
    protected void initAlgorithmSpecificParameters(AlgorithmParameterSpec params)
            throws InvalidAlgorithmParameterException {
        if (!mIvUsed) {
            if (params != null) {
                throw new InvalidAlgorithmParameterException("Unsupported parameters: " + params);
            }
            return;
        }

        // IV is used
        if (params == null) {
            if (!mEncrypting) {
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

    /**
     * Invoked by {@code engineInit} to initialize algorithm-specific parameters. These parameters
     * may need to be stored to be reused after {@code doFinal}.
     *
     * <p>The default implementation only handles the IV parameters.
     *
     * @param params algorithm parameters.
     *
     * @throws InvalidAlgorithmParameterException if some/all of the parameters cannot be
     *         automatically configured and thus {@code Cipher.init} needs to be invoked with
     *         explicitly provided parameters.
     */
    protected void initAlgorithmSpecificParameters(AlgorithmParameters params)
            throws InvalidAlgorithmParameterException {
        if (!mIvUsed) {
            if (params != null) {
                throw new InvalidAlgorithmParameterException("Unsupported parameters: " + params);
            }
            return;
        }

        // IV is used
        if (params == null) {
            if (!mEncrypting) {
                // IV must be provided by the caller
                throw new InvalidAlgorithmParameterException("IV required when decrypting"
                        + ". Use IvParameterSpec or AlgorithmParameters to provide it.");
            }
            return;
        }

        IvParameterSpec ivSpec;
        try {
            ivSpec = params.getParameterSpec(IvParameterSpec.class);
        } catch (InvalidParameterSpecException e) {
            if (!mEncrypting) {
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

    /**
     * Invoked by {@code engineInit} to initialize algorithm-specific parameters. These parameters
     * may need to be stored to be reused after {@code doFinal}.
     *
     * <p>The default implementation only handles the IV parameter.
     *
     * @throws InvalidKeyException if some/all of the parameters cannot be automatically configured
     *         and thus {@code Cipher.init} needs to be invoked with explicitly provided parameters.
     */
    protected void initAlgorithmSpecificParameters() throws InvalidKeyException {
        if (!mIvUsed) {
            return;
        }

        // IV is used
        if (!mEncrypting) {
            throw new InvalidKeyException("IV required when decrypting"
                    + ". Use IvParameterSpec or AlgorithmParameters to provide it.");
        }
    }

    /**
     * Invoked to add algorithm-specific parameters for the KeyStore's {@code begin} operation.
     *
     * <p>The default implementation takes care of the IV.
     *
     * @param keymasterArgs keystore/keymaster arguments to be populated with algorithm-specific
     *        parameters.
     */
    protected void addAlgorithmSpecificParametersToBegin(KeymasterArguments keymasterArgs) {
        if (!mFirstOperationInitiated) {
            // First begin operation -- see if we need to provide additional entropy for IV
            // generation.
            if (mIvUsed) {
                // IV is needed
                if ((mIv == null) && (mEncrypting)) {
                    // TODO: Switch to keymaster-generated IV code below once keymaster supports
                    // that.
                    // IV is needed but was not provided by the caller -- generate an IV.
                    mIv = new byte[mBlockSizeBytes];
                    SecureRandom rng = (mRng != null) ? mRng : new SecureRandom();
                    rng.nextBytes(mIv);
//                    // IV was not provided by the caller and thus will be generated by keymaster.
//                    // Mix in some additional entropy from the provided SecureRandom.
//                    if (mRng != null) {
//                        mAdditionalEntropyForBegin = new byte[mBlockSizeBytes];
//                        mRng.nextBytes(mAdditionalEntropyForBegin);
//                    }
                }
            }
        }

        if ((mIvUsed) && (mIv != null)) {
            keymasterArgs.addBlob(KeymasterDefs.KM_TAG_NONCE, mIv);
        }
    }

    /**
     * Invoked by {@code engineInit} to obtain algorithm-specific parameters from the result of the
     * Keymaster's {@code begin} operation. Some of these parameters may need to be reused after
     * {@code doFinal} by {@link #addAlgorithmSpecificParametersToBegin(KeymasterArguments)}.
     *
     * <p>The default implementation only takes care of the IV.
     *
     * @param keymasterArgs keystore/keymaster arguments returned by KeyStore {@code begin}
     *        operation.
     */
    protected void loadAlgorithmSpecificParametersFromBeginResult(
            KeymasterArguments keymasterArgs) {
        // NOTE: Keymaster doesn't always return an IV, even if it's used.
        byte[] returnedIv = keymasterArgs.getBlob(KeymasterDefs.KM_TAG_NONCE, null);
        if ((returnedIv != null) && (returnedIv.length == 0)) {
            returnedIv = null;
        }

        if (mIvUsed) {
            if (mIv == null) {
                mIv = returnedIv;
            } else if ((returnedIv != null) && (!Arrays.equals(returnedIv, mIv))) {
                throw new CryptoOperationException("IV in use differs from provided IV");
            }
        } else {
            if (returnedIv != null) {
                throw new CryptoOperationException(
                        "IV in use despite IV not being used by this transformation");
            }
        }
    }
}
