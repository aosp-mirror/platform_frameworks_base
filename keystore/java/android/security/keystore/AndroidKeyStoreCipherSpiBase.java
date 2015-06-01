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

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.security.KeyStore;
import android.security.KeyStoreException;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;
import android.security.keymaster.OperationResult;

import java.nio.ByteBuffer;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;

/**
 * Base class for {@link CipherSpi} implementations of Android KeyStore backed ciphers.
 *
 * @hide
 */
abstract class AndroidKeyStoreCipherSpiBase extends CipherSpi implements KeyStoreCryptoOperation {
    private final KeyStore mKeyStore;

    // Fields below are populated by Cipher.init and KeyStore.begin and should be preserved after
    // doFinal finishes.
    private boolean mEncrypting;
    private AndroidKeyStoreKey mKey;
    private SecureRandom mRng;

    /**
     * Token referencing this operation inside keystore service. It is initialized by
     * {@code engineInit} and is invalidated when {@code engineDoFinal} succeeds and on some error
     * conditions in between.
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

    AndroidKeyStoreCipherSpiBase() {
        mKeyStore = KeyStore.getInstance();
    }

    @Override
    protected final void engineInit(int opmode, Key key, SecureRandom random)
            throws InvalidKeyException {
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
    protected final void engineInit(int opmode, Key key, AlgorithmParameters params,
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

    @Override
    protected final void engineInit(int opmode, Key key, AlgorithmParameterSpec params,
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
        if ((opmode != Cipher.ENCRYPT_MODE) && (opmode != Cipher.DECRYPT_MODE)) {
            throw new UnsupportedOperationException(
                    "Only ENCRYPT and DECRYPT modes supported. Mode: " + opmode);
        }
        mEncrypting = opmode == Cipher.ENCRYPT_MODE;
        initKey(opmode, key);
        if (mKey == null) {
            throw new ProviderException("initKey did not initialize the key");
        }
        mRng = random;
    }

    /**
     * Resets this cipher to its pristine pre-init state. This must be equivalent to obtaining a new
     * cipher instance.
     *
     * <p>Subclasses storing additional state should override this method, reset the additional
     * state, and then chain to superclass.
     */
    @CallSuper
    protected void resetAll() {
        IBinder operationToken = mOperationToken;
        if (operationToken != null) {
            mKeyStore.abort(operationToken);
        }
        mEncrypting = false;
        mKey = null;
        mRng = null;
        mOperationToken = null;
        mOperationHandle = 0;
        mMainDataStreamer = null;
        mCachedException = null;
    }

    /**
     * Resets this cipher while preserving the initialized state. This must be equivalent to
     * rolling back the cipher's state to just after the most recent {@code engineInit} completed
     * successfully.
     *
     * <p>Subclasses storing additional post-init state should override this method, reset the
     * additional state, and then chain to superclass.
     */
    @CallSuper
    protected void resetWhilePreservingInitState() {
        IBinder operationToken = mOperationToken;
        if (operationToken != null) {
            mKeyStore.abort(operationToken);
        }
        mOperationToken = null;
        mOperationHandle = 0;
        mMainDataStreamer = null;
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

        KeymasterArguments keymasterInputArgs = new KeymasterArguments();
        addAlgorithmSpecificParametersToBegin(keymasterInputArgs);
        byte[] additionalEntropy = KeyStoreCryptoOperationUtils.getRandomBytesToMixIntoKeystoreRng(
                mRng, getAdditionalEntropyAmountForBegin());

        KeymasterArguments keymasterOutputArgs = new KeymasterArguments();
        OperationResult opResult = mKeyStore.begin(
                mKey.getAlias(),
                mEncrypting ? KeymasterDefs.KM_PURPOSE_ENCRYPT : KeymasterDefs.KM_PURPOSE_DECRYPT,
                true, // permit aborting this operation if keystore runs out of resources
                keymasterInputArgs,
                additionalEntropy,
                keymasterOutputArgs);
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
        mMainDataStreamer = new KeyStoreCryptoOperationChunkedStreamer(
                new KeyStoreCryptoOperationChunkedStreamer.MainDataStream(
                        mKeyStore, opResult.token));
    }

    @Override
    protected final byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
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
    protected final int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output,
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
    protected final int engineUpdate(ByteBuffer input, ByteBuffer output)
            throws ShortBufferException {
        return super.engineUpdate(input, output);
    }

    @Override
    protected final void engineUpdateAAD(byte[] input, int inputOffset, int inputLen) {
        super.engineUpdateAAD(input, inputOffset, inputLen);
    }

    @Override
    protected final void engineUpdateAAD(ByteBuffer src) {
        super.engineUpdateAAD(src);
    }

    @Override
    protected final byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen)
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
    protected final int engineDoFinal(byte[] input, int inputOffset, int inputLen, byte[] output,
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
    protected final int engineDoFinal(ByteBuffer input, ByteBuffer output)
            throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
        return super.engineDoFinal(input, output);
    }

    @Override
    protected final byte[] engineWrap(Key key)
            throws IllegalBlockSizeException, InvalidKeyException {
        return super.engineWrap(key);
    }

    @Override
    protected final Key engineUnwrap(byte[] wrappedKey, String wrappedKeyAlgorithm,
            int wrappedKeyType) throws InvalidKeyException, NoSuchAlgorithmException {
        return super.engineUnwrap(wrappedKey, wrappedKeyAlgorithm, wrappedKeyType);
    }

    @Override
    protected final void engineSetMode(String mode) throws NoSuchAlgorithmException {
        // This should never be invoked because all algorithms registered with the AndroidKeyStore
        // provide explicitly specify block mode.
        throw new UnsupportedOperationException();
    }

    @Override
    protected final void engineSetPadding(String arg0) throws NoSuchPaddingException {
        // This should never be invoked because all algorithms registered with the AndroidKeyStore
        // provide explicitly specify padding mode.
        throw new UnsupportedOperationException();
    }

    @Override
    protected final int engineGetKeySize(Key key) throws InvalidKeyException {
        throw new UnsupportedOperationException();
    }

    @CallSuper
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
    public final long getOperationHandle() {
        return mOperationHandle;
    }

    protected final void setKey(@NonNull AndroidKeyStoreKey key) {
        mKey = key;
    }

    /**
     * Returns {@code true} if this cipher is initialized for encryption, {@code false} if this
     * cipher is initialized for decryption.
     */
    protected final boolean isEncrypting() {
        return mEncrypting;
    }

    @NonNull
    protected final KeyStore getKeyStore() {
        return mKeyStore;
    }

    // The methods below need to be implemented by subclasses.

    /**
     * Initializes this cipher with the provided key.
     *
     * @throws InvalidKeyException if the {@code key} is not suitable for this cipher in the
     *         specified {@code opmode}.
     *
     * @see #setKey(AndroidKeyStoreKey)
     */
    protected abstract void initKey(int opmode, @Nullable Key key) throws InvalidKeyException;

    /**
     * Returns algorithm-specific parameters used by this cipher or {@code null} if no
     * algorithm-specific parameters are used.
     */
    @Nullable
    @Override
    protected abstract AlgorithmParameters engineGetParameters();

    /**
     * Invoked by {@code engineInit} to initialize algorithm-specific parameters when no additional
     * initialization parameters were provided.
     *
     * @throws InvalidKeyException if this cipher cannot be configured based purely on the provided
     *         key and needs additional parameters to be provided to {@code Cipher.init}.
     */
    protected abstract void initAlgorithmSpecificParameters() throws InvalidKeyException;

    /**
     * Invoked by {@code engineInit} to initialize algorithm-specific parameters when additional
     * parameters were provided.
     *
     * @param params additional algorithm parameters or {@code null} if not specified.
     *
     * @throws InvalidAlgorithmParameterException if there is insufficient information to configure
     *         this cipher or if the provided parameters are not suitable for this cipher.
     */
    protected abstract void initAlgorithmSpecificParameters(
            @Nullable AlgorithmParameterSpec params) throws InvalidAlgorithmParameterException;

    /**
     * Invoked by {@code engineInit} to initialize algorithm-specific parameters when additional
     * parameters were provided.
     *
     * @param params additional algorithm parameters or {@code null} if not specified.
     *
     * @throws InvalidAlgorithmParameterException if there is insufficient information to configure
     *         this cipher or if the provided parameters are not suitable for this cipher.
     */
    protected abstract void initAlgorithmSpecificParameters(@Nullable AlgorithmParameters params)
            throws InvalidAlgorithmParameterException;

    /**
     * Returns the amount of additional entropy (in bytes) to be provided to the KeyStore's
     * {@code begin} operation.
     *
     * <p>For decryption, this should be {@code 0} because decryption should not be consuming any
     * entropy. For encryption, this value should match (or exceed) the amount of Shannon entropy of
     * the ciphertext produced by this cipher assuming the key, the plaintext, and all explicitly
     * provided parameters to {@code Cipher.init} are known. For example, for AES CBC encryption
     * with an explicitly provided IV this should be {@code 0}, whereas for the case where IV is
     * generated by the KeyStore's {@code begin} operation this should be {@code 16}. For RSA with
     * OAEP this should be the size of the OAEP hash output. For RSA with PKCS#1 padding this should
     * be the size of the padding string or could be raised (for simplicity) to the size of the
     * modulus.
     */
    protected abstract int getAdditionalEntropyAmountForBegin();

    /**
     * Invoked to add algorithm-specific parameters for the KeyStore's {@code begin} operation.
     *
     * @param keymasterArgs keystore/keymaster arguments to be populated with algorithm-specific
     *        parameters.
     */
    protected abstract void addAlgorithmSpecificParametersToBegin(
            @NonNull KeymasterArguments keymasterArgs);

    /**
     * Invoked to obtain algorithm-specific parameters from the result of the KeyStore's
     * {@code begin} operation.
     *
     * <p>Some parameters, such as IV, are not required to be provided to {@code Cipher.init}. Such
     * parameters, if not provided, must be generated by KeyStore and returned to the user of
     * {@code Cipher} and potentially reused after {@code doFinal}.
     *
     * @param keymasterArgs keystore/keymaster arguments returned by KeyStore {@code begin}
     *        operation.
     */
    protected abstract void loadAlgorithmSpecificParametersFromBeginResult(
            @NonNull KeymasterArguments keymasterArgs);
}
