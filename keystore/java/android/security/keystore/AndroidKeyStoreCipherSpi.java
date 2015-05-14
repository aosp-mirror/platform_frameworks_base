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
import android.security.keystore.KeyProperties;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
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
public abstract class AndroidKeyStoreCipherSpi extends CipherSpi
        implements KeyStoreCryptoOperation {

    public abstract static class AES extends AndroidKeyStoreCipherSpi {
        protected AES(int keymasterBlockMode, int keymasterPadding, boolean ivUsed) {
            super(KeymasterDefs.KM_ALGORITHM_AES,
                    keymasterBlockMode,
                    keymasterPadding,
                    16,
                    ivUsed);
        }

        public abstract static class ECB extends AES {
            protected ECB(int keymasterPadding) {
                super(KeymasterDefs.KM_MODE_ECB, keymasterPadding, false);
            }

            public static class NoPadding extends ECB {
                public NoPadding() {
                    super(KeymasterDefs.KM_PAD_NONE);
                }
            }

            public static class PKCS7Padding extends ECB {
                public PKCS7Padding() {
                    super(KeymasterDefs.KM_PAD_PKCS7);
                }
            }
        }

        public abstract static class CBC extends AES {
            protected CBC(int keymasterPadding) {
                super(KeymasterDefs.KM_MODE_CBC, keymasterPadding, true);
            }

            public static class NoPadding extends CBC {
                public NoPadding() {
                    super(KeymasterDefs.KM_PAD_NONE);
                }
            }

            public static class PKCS7Padding extends CBC {
                public PKCS7Padding() {
                    super(KeymasterDefs.KM_PAD_PKCS7);
                }
            }
        }

        public abstract static class CTR extends AES {
            protected CTR(int keymasterPadding) {
                super(KeymasterDefs.KM_MODE_CTR, keymasterPadding, true);
            }

            public static class NoPadding extends CTR {
                public NoPadding() {
                    super(KeymasterDefs.KM_PAD_NONE);
                }
            }
        }
    }

    private final KeyStore mKeyStore;
    private final int mKeymasterAlgorithm;
    private final int mKeymasterBlockMode;
    private final int mKeymasterPadding;
    private final int mBlockSizeBytes;

    /** Whether this transformation requires an IV. */
    private final boolean mIvRequired;

    // Fields below are populated by Cipher.init and KeyStore.begin and should be preserved after
    // doFinal finishes.
    protected boolean mEncrypting;
    private AndroidKeyStoreSecretKey mKey;
    private SecureRandom mRng;
    private boolean mFirstOperationInitiated;
    private byte[] mIv;
    /** Whether the current {@code #mIv} has been used by the underlying crypto operation. */
    private boolean mIvHasBeenUsed;

    // Fields below must be reset after doFinal
    private byte[] mAdditionalEntropyForBegin;

    /**
     * Token referencing this operation inside keystore service. It is initialized by
     * {@code engineInit} and is invalidated when {@code engineDoFinal} succeeds and one some
     * error conditions in between.
     */
    private IBinder mOperationToken;
    private long mOperationHandle;
    private KeyStoreCryptoOperationChunkedStreamer mMainDataStreamer;

    /**
     * Encountered exception which could not be immediately thrown because it was encountered inside
     * a method that does not throw checked exception. This exception will be thrown from
     * {@code engineDoFinal}. Once such an exception is encountered, {@code engineUpdate} and
     * {@code engineDoFinal} start ignoring input data.
     */
    private Exception mCachedException;

    protected AndroidKeyStoreCipherSpi(
            int keymasterAlgorithm,
            int keymasterBlockMode,
            int keymasterPadding,
            int blockSizeBytes,
            boolean ivUsed) {
        mKeyStore = KeyStore.getInstance();
        mKeymasterAlgorithm = keymasterAlgorithm;
        mKeymasterBlockMode = keymasterBlockMode;
        mKeymasterPadding = keymasterPadding;
        mBlockSizeBytes = blockSizeBytes;
        mIvRequired = ivUsed;
    }

    @Override
    protected void engineInit(int opmode, Key key, SecureRandom random) throws InvalidKeyException {
        resetAll();

        boolean success = false;
        try {
            init(opmode, key, random);
            initAlgorithmSpecificParameters();
            try {
                ensureKeystoreOperationInitialized();
            } catch (InvalidAlgorithmParameterException e) {
                throw new InvalidKeyException(e);
            }
            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    @Override
    protected void engineInit(int opmode, Key key, AlgorithmParameters params, SecureRandom random)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
        resetAll();

        boolean success = false;
        try {
            init(opmode, key, random);
            initAlgorithmSpecificParameters(params);
            ensureKeystoreOperationInitialized();
            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    @Override
    protected void engineInit(int opmode, Key key, AlgorithmParameterSpec params,
            SecureRandom random) throws InvalidKeyException, InvalidAlgorithmParameterException {
        resetAll();

        boolean success = false;
        try {
            init(opmode, key, random);
            initAlgorithmSpecificParameters(params);
            ensureKeystoreOperationInitialized();
            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    private void init(int opmode, Key key, SecureRandom random) throws InvalidKeyException {
        if (!(key instanceof AndroidKeyStoreSecretKey)) {
            throw new InvalidKeyException(
                    "Unsupported key: " + ((key != null) ? key.getClass().getName() : "null"));
        }
        mKey = (AndroidKeyStoreSecretKey) key;
        mRng = random;
        mIv = null;
        mFirstOperationInitiated = false;

        if ((opmode != Cipher.ENCRYPT_MODE) && (opmode != Cipher.DECRYPT_MODE)) {
            throw new UnsupportedOperationException(
                    "Only ENCRYPT and DECRYPT modes supported. Mode: " + opmode);
        }
        mEncrypting = opmode == Cipher.ENCRYPT_MODE;
    }

    private void resetAll() {
        IBinder operationToken = mOperationToken;
        if (operationToken != null) {
            mOperationToken = null;
            mKeyStore.abort(operationToken);
        }
        mEncrypting = false;
        mKey = null;
        mRng = null;
        mFirstOperationInitiated = false;
        mIv = null;
        mIvHasBeenUsed = false;
        mAdditionalEntropyForBegin = null;
        mOperationToken = null;
        mOperationHandle = 0;
        mMainDataStreamer = null;
        mCachedException = null;
    }

    private void resetWhilePreservingInitState() {
        IBinder operationToken = mOperationToken;
        if (operationToken != null) {
            mOperationToken = null;
            mKeyStore.abort(operationToken);
        }
        mOperationHandle = 0;
        mMainDataStreamer = null;
        mAdditionalEntropyForBegin = null;
        mCachedException = null;
    }

    private void ensureKeystoreOperationInitialized() throws InvalidKeyException,
            InvalidAlgorithmParameterException {
        if (mMainDataStreamer != null) {
            return;
        }
        if (mCachedException != null) {
            return;
        }
        if (mKey == null) {
            throw new IllegalStateException("Not initialized");
        }
        if ((mEncrypting) && (mIvRequired) && (mIvHasBeenUsed)) {
            // IV is being reused for encryption: this violates security best practices.
            throw new IllegalStateException(
                    "IV has already been used. Reusing IV in encryption mode violates security best"
                    + " practices.");
        }

        KeymasterArguments keymasterInputArgs = new KeymasterArguments();
        keymasterInputArgs.addInt(KeymasterDefs.KM_TAG_ALGORITHM, mKeymasterAlgorithm);
        keymasterInputArgs.addInt(KeymasterDefs.KM_TAG_BLOCK_MODE, mKeymasterBlockMode);
        keymasterInputArgs.addInt(KeymasterDefs.KM_TAG_PADDING, mKeymasterPadding);
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
        }

        // Store operation token and handle regardless of the error code returned by KeyStore to
        // ensure that the operation gets aborted immediately if the code below throws an exception.
        mOperationToken = opResult.token;
        mOperationHandle = opResult.operationHandle;

        // If necessary, throw an exception due to KeyStore operation having failed.
        GeneralSecurityException e = KeyStoreCryptoOperationUtils.getExceptionForCipherInit(
                mKeyStore, mKey, opResult.resultCode);
        if (e != null) {
            if (e instanceof InvalidKeyException) {
                throw (InvalidKeyException) e;
            } else if (e instanceof InvalidAlgorithmParameterException) {
                throw (InvalidAlgorithmParameterException) e;
            } else {
                throw new ProviderException("Unexpected exception type", e);
            }
        }

        if (mOperationToken == null) {
            throw new ProviderException("Keystore returned null operation token");
        }
        if (mOperationHandle == 0) {
            throw new ProviderException("Keystore returned invalid operation handle");
        }

        loadAlgorithmSpecificParametersFromBeginResult(keymasterOutputArgs);
        mFirstOperationInitiated = true;
        mIvHasBeenUsed = true;
        mMainDataStreamer = new KeyStoreCryptoOperationChunkedStreamer(
                new KeyStoreCryptoOperationChunkedStreamer.MainDataStream(
                        mKeyStore, opResult.token));
    }

    @Override
    protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
        if (mCachedException != null) {
            return null;
        }
        try {
            ensureKeystoreOperationInitialized();
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            mCachedException = e;
            return null;
        }

        if (inputLen == 0) {
            return null;
        }

        byte[] output;
        try {
            output = mMainDataStreamer.update(input, inputOffset, inputLen);
        } catch (KeyStoreException e) {
            mCachedException = e;
            return null;
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
        if (mCachedException != null) {
            throw (IllegalBlockSizeException)
                    new IllegalBlockSizeException().initCause(mCachedException);
        }

        try {
            ensureKeystoreOperationInitialized();
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw (IllegalBlockSizeException) new IllegalBlockSizeException().initCause(e);
        }

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
                    throw (IllegalBlockSizeException) new IllegalBlockSizeException().initCause(e);
            }
        }

        resetWhilePreservingInitState();
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
    public long getOperationHandle() {
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
        if (!mIvRequired) {
            return null;
        }
        if ((mIv != null) && (mIv.length > 0)) {
            try {
                AlgorithmParameters params =
                        AlgorithmParameters.getInstance(KeyProperties.KEY_ALGORITHM_AES);
                params.init(new IvParameterSpec(mIv));
                return params;
            } catch (NoSuchAlgorithmException e) {
                throw new ProviderException("Failed to obtain AES AlgorithmParameters", e);
            } catch (InvalidParameterSpecException e) {
                throw new ProviderException(
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
        if (!mIvRequired) {
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
        if (!mIvRequired) {
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
        if (!mIvRequired) {
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
            if (mIvRequired) {
                // IV is needed
                if ((mIv == null) && (mEncrypting)) {
                    // IV was not provided by the caller and thus will be generated by keymaster.
                    // Mix in some additional entropy from the provided SecureRandom.
                    mAdditionalEntropyForBegin =
                            KeyStoreCryptoOperationUtils.getRandomBytesToMixIntoKeystoreRng(
                                    mRng, mBlockSizeBytes);
                }
            }
        }

        if ((mIvRequired) && (mIv != null)) {
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
}
