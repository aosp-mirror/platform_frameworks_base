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

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.security.keymint.KeyParameter;
import android.security.KeyStoreException;
import android.security.KeyStoreOperation;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.KeyStoreCryptoOperation;
import android.system.keystore2.Authorization;

import libcore.util.EmptyArray;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Base class for {@link CipherSpi} implementations of Android KeyStore backed ciphers.
 *
 * @hide
 */
abstract class AndroidKeyStoreCipherSpiBase extends CipherSpi implements KeyStoreCryptoOperation {
    private static final String TAG = "AndroidKeyStoreCipherSpiBase";
    public static final String DEFAULT_MGF1_DIGEST = "SHA-1";

    // Fields below are populated by Cipher.init and KeyStore.begin and should be preserved after
    // doFinal finishes.
    private boolean mEncrypting;
    private int mKeymasterPurposeOverride = -1;
    private AndroidKeyStoreKey mKey;
    private SecureRandom mRng;

    /**
     * Object representing this operation inside keystore service. It is initialized
     * by {@code engineInit} and is invalidated when {@code engineDoFinal} succeeds and on some
     * error conditions in between.
     */
    private KeyStoreOperation mOperation;
    /**
     * The operation challenge is required when an operation needs user authorization.
     * The challenge is subjected to an authenticator, e.g., Gatekeeper or a biometric
     * authenticator, and included in the authentication token minted by this authenticator.
     * It may be null, if the operation does not require authorization.
     */
    private long mOperationChallenge;
    private KeyStoreCryptoOperationStreamer mMainDataStreamer;
    private KeyStoreCryptoOperationStreamer mAdditionalAuthenticationDataStreamer;
    private boolean mAdditionalAuthenticationDataStreamerClosed;

    /**
     * Encountered exception which could not be immediately thrown because it was encountered inside
     * a method that does not throw checked exception. This exception will be thrown from
     * {@code engineDoFinal}. Once such an exception is encountered, {@code engineUpdate} and
     * {@code engineDoFinal} start ignoring input data.
     */
    private Exception mCachedException;

    private Cipher mCipher;

    AndroidKeyStoreCipherSpiBase() {
        mOperation = null;
        mEncrypting = false;
        mKeymasterPurposeOverride = -1;
        mKey = null;
        mRng = null;
        mOperationChallenge = 0;
        mMainDataStreamer = null;
        mAdditionalAuthenticationDataStreamer = null;
        mAdditionalAuthenticationDataStreamerClosed = false;
        mCachedException = null;
        mCipher = null;
    }

    private Authorization[] getKeyCharacteristics(Key key) {
        if (!(key instanceof AndroidKeyStoreKey)) {
            return new Authorization[] {};
        }

        return ((AndroidKeyStoreKey) key).getAuthorizations();
    }

    @Override
    protected final void engineInit(int opmode, Key key, SecureRandom random)
            throws InvalidKeyException {
        resetAll();

        // Public key operations get diverted to the default provider.
        if (!(key instanceof AndroidKeyStorePrivateKey)
                && (key instanceof PrivateKey || key instanceof PublicKey)) {
            try {
                mCipher = Cipher.getInstance(getTransform());
                String transform = getTransform();

                if ("RSA/ECB/OAEPWithSHA-224AndMGF1Padding".equals(transform)) {
                    OAEPParameterSpec spec =
                            new OAEPParameterSpec("SHA-224", "MGF1",
                                    new MGF1ParameterSpec(DEFAULT_MGF1_DIGEST),
                                    PSource.PSpecified.DEFAULT);
                    mCipher.init(opmode, key, spec, random);
                } else if ("RSA/ECB/OAEPWithSHA-256AndMGF1Padding".equals(transform)) {
                    OAEPParameterSpec spec =
                            new OAEPParameterSpec("SHA-256", "MGF1",
                                    new MGF1ParameterSpec(DEFAULT_MGF1_DIGEST),
                                    PSource.PSpecified.DEFAULT);
                    mCipher.init(opmode, key, spec, random);

                } else if ("RSA/ECB/OAEPWithSHA-384AndMGF1Padding".equals(transform)) {
                    OAEPParameterSpec spec =
                            new OAEPParameterSpec("SHA-384", "MGF1",
                                    new MGF1ParameterSpec(DEFAULT_MGF1_DIGEST),
                                    PSource.PSpecified.DEFAULT);
                    mCipher.init(opmode, key, spec, random);

                } else if ("RSA/ECB/OAEPWithSHA-512AndMGF1Padding".equals(transform)) {
                    OAEPParameterSpec spec =
                            new OAEPParameterSpec("SHA-512", "MGF1",
                                    new MGF1ParameterSpec(DEFAULT_MGF1_DIGEST),
                                    PSource.PSpecified.DEFAULT);
                    mCipher.init(opmode, key, spec, random);
                } else {
                    mCipher.init(opmode, key, random);
                }
                return;
            } catch (NoSuchAlgorithmException
                    | NoSuchPaddingException
                    | InvalidAlgorithmParameterException e) {
                throw new InvalidKeyException(e);
            }
        }

        boolean success = false;
        try {
            init(opmode, key, random);
            initAlgorithmSpecificParameters();
            try {
                ensureKeystoreOperationInitialized(getKeyCharacteristics(key));
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

        // Public key operations get diverted to the default provider.
        if (!(key instanceof AndroidKeyStorePrivateKey)
                && (key instanceof PrivateKey || key instanceof PublicKey)) {
            try {
                mCipher = Cipher.getInstance(getTransform());
                mCipher.init(opmode, key, params, random);
                return;
            } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
                throw new InvalidKeyException(e);
            }
        }

        boolean success = false;
        try {
            init(opmode, key, random);
            initAlgorithmSpecificParameters(params);
            ensureKeystoreOperationInitialized(getKeyCharacteristics(key));
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

        // Public key operations get diverted to the default provider.
        if (!(key instanceof AndroidKeyStorePrivateKey)
                && (key instanceof PrivateKey || key instanceof PublicKey)) {
            try {
                mCipher = Cipher.getInstance(getTransform());
                mCipher.init(opmode, key, params, random);
                return;
            } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
                throw new InvalidKeyException(e);
            }
        }

        boolean success = false;
        try {
            init(opmode, key, random);
            initAlgorithmSpecificParameters(params);
            ensureKeystoreOperationInitialized(getKeyCharacteristics(key));
            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    private void init(int opmode, Key key, SecureRandom random) throws InvalidKeyException {
        switch (opmode) {
            case Cipher.ENCRYPT_MODE:
            case Cipher.WRAP_MODE:
                mEncrypting = true;
                break;
            case Cipher.DECRYPT_MODE:
            case Cipher.UNWRAP_MODE:
                mEncrypting = false;
                break;
            default:
                throw new InvalidParameterException("Unsupported opmode: " + opmode);
        }
        initKey(opmode, key);
        if (mKey == null) {
            throw new ProviderException("initKey did not initialize the key");
        }
        mRng = random;
    }

    private void abortOperation() {
        KeyStoreCryptoOperationUtils.abortOperation(mOperation);
        mOperation = null;
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
        abortOperation();
        mEncrypting = false;
        mKeymasterPurposeOverride = -1;
        mKey = null;
        mRng = null;
        mOperationChallenge = 0;
        mMainDataStreamer = null;
        mAdditionalAuthenticationDataStreamer = null;
        mAdditionalAuthenticationDataStreamerClosed = false;
        mCachedException = null;
        mCipher = null;
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
        abortOperation();
        mOperationChallenge = 0;
        mMainDataStreamer = null;
        mAdditionalAuthenticationDataStreamer = null;
        mAdditionalAuthenticationDataStreamerClosed = false;
        mCachedException = null;
    }

    private void ensureKeystoreOperationInitialized(Authorization[] keyCharacteristics)
            throws InvalidKeyException,
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

        List<KeyParameter> parameters = new ArrayList<>();
        addAlgorithmSpecificParametersToBegin(parameters, keyCharacteristics);

        int purpose;
        if (mKeymasterPurposeOverride != -1) {
            purpose = mKeymasterPurposeOverride;
        } else {
            purpose = mEncrypting
                    ? KeymasterDefs.KM_PURPOSE_ENCRYPT : KeymasterDefs.KM_PURPOSE_DECRYPT;
        }

        parameters.add(KeyStore2ParameterUtils.makeEnum(KeymasterDefs.KM_TAG_PURPOSE, purpose));

        try {
            mOperation = mKey.getSecurityLevel().createOperation(
                    mKey.getKeyIdDescriptor(),
                    parameters
            );
        } catch (KeyStoreException keyStoreException) {
            GeneralSecurityException e = KeyStoreCryptoOperationUtils.getExceptionForCipherInit(
                    mKey, keyStoreException);
            if (e != null) {
                if (e instanceof InvalidKeyException) {
                    throw (InvalidKeyException) e;
                } else if (e instanceof InvalidAlgorithmParameterException) {
                    throw (InvalidAlgorithmParameterException) e;
                } else {
                    throw new ProviderException("Unexpected exception type", e);
                }
            }
        }

        // Now we check if we got an operation challenge. This indicates that user authorization
        // is required. And if we got a challenge we check if the authorization can possibly
        // succeed.
        mOperationChallenge = KeyStoreCryptoOperationUtils.getOrMakeOperationChallenge(
                mOperation, mKey);

        loadAlgorithmSpecificParametersFromBeginResult(mOperation.getParameters());
        mMainDataStreamer = createMainDataStreamer(mOperation);
        mAdditionalAuthenticationDataStreamer =
                createAdditionalAuthenticationDataStreamer(mOperation);
        mAdditionalAuthenticationDataStreamerClosed = false;
    }

    /**
     * Creates a streamer which sends plaintext/ciphertext into the provided KeyStore and receives
     * the corresponding ciphertext/plaintext from the KeyStore.
     *
     * <p>This implementation returns a working streamer.
     */
    @NonNull
    protected KeyStoreCryptoOperationStreamer createMainDataStreamer(
            KeyStoreOperation operation) {
        return new KeyStoreCryptoOperationChunkedStreamer(
                new KeyStoreCryptoOperationChunkedStreamer.MainDataStream(
                        operation), 0);
    }

    /**
     * Creates a streamer which sends Additional Authentication Data (AAD) into the KeyStore.
     *
     * <p>This implementation returns {@code null}.
     *
     * @return stream or {@code null} if AAD is not supported by this cipher.
     */
    @Nullable
    protected KeyStoreCryptoOperationStreamer createAdditionalAuthenticationDataStreamer(
            @SuppressWarnings("unused") KeyStoreOperation operation) {
        return null;
    }

    @Override
    protected final byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
        if (mCipher != null) {
            return mCipher.update(input, inputOffset, inputLen);
        }

        if (mCachedException != null) {
            return null;
        }
        try {
            ensureKeystoreOperationInitialized(getKeyCharacteristics(mKey));
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            mCachedException = e;
            return null;
        }

        if (inputLen == 0) {
            return null;
        }

        byte[] output;
        try {
            flushAAD();
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

    private void flushAAD() throws KeyStoreException {
        if ((mAdditionalAuthenticationDataStreamer != null)
                && (!mAdditionalAuthenticationDataStreamerClosed)) {
            byte[] output;
            try {
                output = mAdditionalAuthenticationDataStreamer.doFinal(
                        EmptyArray.BYTE, 0, 0,
                        null); // no signature
            } finally {
                mAdditionalAuthenticationDataStreamerClosed = true;
            }
            if ((output != null) && (output.length > 0)) {
                throw new ProviderException(
                        "AAD update unexpectedly returned data: " + output.length + " bytes");
            }
        }
    }

    @Override
    protected final int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output,
            int outputOffset) throws ShortBufferException {
        if (mCipher != null) {
            return mCipher.update(input, inputOffset, inputLen, output);
        }
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
        if (mCipher != null) {
            return mCipher.update(input, output);
        }

        if (input == null) {
            throw new NullPointerException("input == null");
        }
        if (output == null) {
            throw new NullPointerException("output == null");
        }

        int inputSize = input.remaining();
        byte[] outputArray;
        if (input.hasArray()) {
            outputArray =
                    engineUpdate(
                            input.array(), input.arrayOffset() + input.position(), inputSize);
            input.position(input.position() + inputSize);
        } else {
            byte[] inputArray = new byte[inputSize];
            input.get(inputArray);
            outputArray = engineUpdate(inputArray, 0, inputSize);
        }

        int outputSize = (outputArray != null) ? outputArray.length : 0;
        if (outputSize > 0) {
            int outputBufferAvailable = output.remaining();
            try {
                output.put(outputArray);
            } catch (BufferOverflowException e) {
                throw new ShortBufferException(
                        "Output buffer too small. Produced: " + outputSize + ", available: "
                                + outputBufferAvailable);
            }
        }
        return outputSize;
    }

    @Override
    protected final void engineUpdateAAD(byte[] input, int inputOffset, int inputLen) {
        if (mCipher != null) {
            mCipher.updateAAD(input, inputOffset, inputLen);
            return;
        }

        if (mCachedException != null) {
            return;
        }

        try {
            ensureKeystoreOperationInitialized(getKeyCharacteristics(mKey));
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            mCachedException = e;
            return;
        }

        if (mAdditionalAuthenticationDataStreamerClosed) {
            throw new IllegalStateException(
                    "AAD can only be provided before Cipher.update is invoked");
        }

        if (mAdditionalAuthenticationDataStreamer == null) {
            throw new IllegalStateException("This cipher does not support AAD");
        }

        byte[] output;
        try {
            output = mAdditionalAuthenticationDataStreamer.update(input, inputOffset, inputLen);
        } catch (KeyStoreException e) {
            mCachedException = e;
            return;
        }

        if ((output != null) && (output.length > 0)) {
            throw new ProviderException("AAD update unexpectedly produced output: "
                    + output.length + " bytes");
        }
    }

    @Override
    protected final void engineUpdateAAD(ByteBuffer src) {
        if (mCipher != null) {
            mCipher.updateAAD(src);
            return;
        }

        if (src == null) {
            throw new IllegalArgumentException("src == null");
        }
        if (!src.hasRemaining()) {
            return;
        }

        byte[] input;
        int inputOffset;
        int inputLen;
        if (src.hasArray()) {
            input = src.array();
            inputOffset = src.arrayOffset() + src.position();
            inputLen = src.remaining();
            src.position(src.limit());
        } else {
            input = new byte[src.remaining()];
            inputOffset = 0;
            inputLen = input.length;
            src.get(input);
        }
        engineUpdateAAD(input, inputOffset, inputLen);
    }

    @Override
    protected final byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen)
            throws IllegalBlockSizeException, BadPaddingException {
        if (mCipher != null) {
            if (input == null && inputLen == 0) {
                return mCipher.doFinal();
            } else {
                return mCipher.doFinal(input, inputOffset, inputLen);
            }
        }

        if (mCachedException != null) {
            throw (IllegalBlockSizeException)
                    new IllegalBlockSizeException().initCause(mCachedException);
        }

        try {
            ensureKeystoreOperationInitialized(getKeyCharacteristics(mKey));
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            throw (IllegalBlockSizeException) new IllegalBlockSizeException().initCause(e);
        }

        byte[] output;
        try {
            flushAAD();
            output = mMainDataStreamer.doFinal(
                    input, inputOffset, inputLen,
                    null); // no signature involved
        } catch (KeyStoreException e) {
            switch (e.getErrorCode()) {
                case KeymasterDefs.KM_ERROR_INVALID_ARGUMENT:
                    throw (BadPaddingException) new BadPaddingException().initCause(e);
                case KeymasterDefs.KM_ERROR_VERIFICATION_FAILED:
                    throw (AEADBadTagException) new AEADBadTagException().initCause(e);
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
        if (mCipher != null) {
            return mCipher.doFinal(input, inputOffset, inputLen, output);
        }

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
        if (mCipher != null) {
            return mCipher.doFinal(input, output);
        }

        if (input == null) {
            throw new NullPointerException("input == null");
        }
        if (output == null) {
            throw new NullPointerException("output == null");
        }

        int inputSize = input.remaining();
        byte[] outputArray;
        if (input.hasArray()) {
            outputArray =
                    engineDoFinal(
                            input.array(), input.arrayOffset() + input.position(), inputSize);
            input.position(input.position() + inputSize);
        } else {
            byte[] inputArray = new byte[inputSize];
            input.get(inputArray);
            outputArray = engineDoFinal(inputArray, 0, inputSize);
        }

        int outputSize = (outputArray != null) ? outputArray.length : 0;
        if (outputSize > 0) {
            int outputBufferAvailable = output.remaining();
            try {
                output.put(outputArray);
            } catch (BufferOverflowException e) {
                throw new ShortBufferException(
                        "Output buffer too small. Produced: " + outputSize + ", available: "
                                + outputBufferAvailable);
            }
        }
        return outputSize;
    }

    @Override
    protected final byte[] engineWrap(Key key)
            throws IllegalBlockSizeException, InvalidKeyException {
        if (mCipher != null) {
            return mCipher.wrap(key);
        }

        if (mKey == null) {
            throw new IllegalStateException("Not initilized");
        }

        if (!isEncrypting()) {
            throw new IllegalStateException(
                    "Cipher must be initialized in Cipher.WRAP_MODE to wrap keys");
        }

        if (key == null) {
            throw new NullPointerException("key == null");
        }
        byte[] encoded = null;
        if (key instanceof SecretKey) {
            if ("RAW".equalsIgnoreCase(key.getFormat())) {
                encoded = key.getEncoded();
            }
            if (encoded == null) {
                try {
                    SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(key.getAlgorithm());
                    SecretKeySpec spec =
                            (SecretKeySpec) keyFactory.getKeySpec(
                                    (SecretKey) key, SecretKeySpec.class);
                    encoded = spec.getEncoded();
                } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                    throw new InvalidKeyException(
                            "Failed to wrap key because it does not export its key material",
                            e);
                }
            }
        } else if (key instanceof PrivateKey) {
            if ("PKCS8".equalsIgnoreCase(key.getFormat())) {
                encoded = key.getEncoded();
            }
            if (encoded == null) {
                try {
                    KeyFactory keyFactory = KeyFactory.getInstance(key.getAlgorithm());
                    PKCS8EncodedKeySpec spec =
                            keyFactory.getKeySpec(key, PKCS8EncodedKeySpec.class);
                    encoded = spec.getEncoded();
                } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                    throw new InvalidKeyException(
                            "Failed to wrap key because it does not export its key material",
                            e);
                }
            }
        } else if (key instanceof PublicKey) {
            if ("X.509".equalsIgnoreCase(key.getFormat())) {
                encoded = key.getEncoded();
            }
            if (encoded == null) {
                try {
                    KeyFactory keyFactory = KeyFactory.getInstance(key.getAlgorithm());
                    X509EncodedKeySpec spec =
                            keyFactory.getKeySpec(key, X509EncodedKeySpec.class);
                    encoded = spec.getEncoded();
                } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                    throw new InvalidKeyException(
                            "Failed to wrap key because it does not export its key material",
                            e);
                }
            }
        } else {
            throw new InvalidKeyException("Unsupported key type: " + key.getClass().getName());
        }

        if (encoded == null) {
            throw new InvalidKeyException(
                    "Failed to wrap key because it does not export its key material");
        }

        try {
            return engineDoFinal(encoded, 0, encoded.length);
        } catch (BadPaddingException e) {
            throw (IllegalBlockSizeException) new IllegalBlockSizeException().initCause(e);
        }
    }

    @Override
    protected final Key engineUnwrap(byte[] wrappedKey, String wrappedKeyAlgorithm,
            int wrappedKeyType) throws InvalidKeyException, NoSuchAlgorithmException {
        if (mCipher != null) {
            return mCipher.unwrap(wrappedKey, wrappedKeyAlgorithm, wrappedKeyType);
        }

        if (mKey == null) {
            throw new IllegalStateException("Not initilized");
        }

        if (isEncrypting()) {
            throw new IllegalStateException(
                    "Cipher must be initialized in Cipher.WRAP_MODE to wrap keys");
        }

        if (wrappedKey == null) {
            throw new NullPointerException("wrappedKey == null");
        }

        byte[] encoded;
        try {
            encoded = engineDoFinal(wrappedKey, 0, wrappedKey.length);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            throw new InvalidKeyException("Failed to unwrap key", e);
        }

        switch (wrappedKeyType) {
            case Cipher.SECRET_KEY:
            {
                return new SecretKeySpec(encoded, wrappedKeyAlgorithm);
                // break;
            }
            case Cipher.PRIVATE_KEY:
            {
                KeyFactory keyFactory = KeyFactory.getInstance(wrappedKeyAlgorithm);
                try {
                    return keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
                } catch (InvalidKeySpecException e) {
                    throw new InvalidKeyException(
                            "Failed to create private key from its PKCS#8 encoded form", e);
                }
                // break;
            }
            case Cipher.PUBLIC_KEY:
            {
                KeyFactory keyFactory = KeyFactory.getInstance(wrappedKeyAlgorithm);
                try {
                    return keyFactory.generatePublic(new X509EncodedKeySpec(encoded));
                } catch (InvalidKeySpecException e) {
                    throw new InvalidKeyException(
                            "Failed to create public key from its X.509 encoded form", e);
                }
                // break;
            }
            default:
                throw new InvalidParameterException(
                        "Unsupported wrappedKeyType: " + wrappedKeyType);
        }
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
            abortOperation();
        } finally {
            super.finalize();
        }
    }

    @Override
    public final long getOperationHandle() {
        return mOperationChallenge;
    }

    protected final void setKey(@NonNull AndroidKeyStoreKey key) {
        mKey = key;
    }

    /**
     * Overrides the default purpose/type of the crypto operation.
     */
    protected final void setKeymasterPurposeOverride(int keymasterPurpose) {
        mKeymasterPurposeOverride = keymasterPurpose;
    }

    protected final int getKeymasterPurposeOverride() {
        return mKeymasterPurposeOverride;
    }

    /**
     * Returns {@code true} if this cipher is initialized for encryption, {@code false} if this
     * cipher is initialized for decryption.
     */
    protected final boolean isEncrypting() {
        return mEncrypting;
    }

    protected final long getConsumedInputSizeBytes() {
        if (mMainDataStreamer == null) {
            throw new IllegalStateException("Not initialized");
        }
        return mMainDataStreamer.getConsumedInputSizeBytes();
    }

    protected final long getProducedOutputSizeBytes() {
        if (mMainDataStreamer == null) {
            throw new IllegalStateException("Not initialized");
        }
        return mMainDataStreamer.getProducedOutputSizeBytes();
    }

    static String opmodeToString(int opmode) {
        switch (opmode) {
            case Cipher.ENCRYPT_MODE:
                return "ENCRYPT_MODE";
            case Cipher.DECRYPT_MODE:
                return "DECRYPT_MODE";
            case Cipher.WRAP_MODE:
                return "WRAP_MODE";
            case Cipher.UNWRAP_MODE:
                return "UNWRAP_MODE";
            default:
                return String.valueOf(opmode);
        }
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
     * {@code begin} operation. This amount of entropy is typically what's consumed to generate
     * random parameters, such as IV.
     *
     * <p>For decryption, the return value should be {@code 0} because decryption should not be
     * consuming any entropy. For encryption, the value combined with
     * {@link #getAdditionalEntropyAmountForFinish()} should match (or exceed) the amount of Shannon
     * entropy of the ciphertext produced by this cipher assuming the key, the plaintext, and all
     * explicitly provided parameters to {@code Cipher.init} are known. For example, for AES CBC
     * encryption with an explicitly provided IV the return value should be {@code 0}, whereas for
     * the case where IV is generated by the KeyStore's {@code begin} operation it should be
     * {@code 16}.
     */
    protected abstract int getAdditionalEntropyAmountForBegin();

    /**
     * Returns the amount of additional entropy (in bytes) to be provided to the KeyStore's
     * {@code finish} operation. This amount of entropy is typically what's consumed by encryption
     * padding scheme.
     *
     * <p>For decryption, the return value should be {@code 0} because decryption should not be
     * consuming any entropy. For encryption, the value combined with
     * {@link #getAdditionalEntropyAmountForBegin()} should match (or exceed) the amount of Shannon
     * entropy of the ciphertext produced by this cipher assuming the key, the plaintext, and all
     * explicitly provided parameters to {@code Cipher.init} are known. For example, for RSA with
     * OAEP the return value should be the size of the OAEP hash output. For RSA with PKCS#1 padding
     * the return value should be the size of the padding string or could be raised (for simplicity)
     * to the size of the modulus.
     */
    protected abstract int getAdditionalEntropyAmountForFinish();

    /**
     * Invoked to add algorithm-specific parameters for the KeyStore's {@code begin} operation.
     *
     * @param parameters keystore/keymaster arguments to be populated with algorithm-specific
     *        parameters.
     */
    protected abstract void addAlgorithmSpecificParametersToBegin(
            @NonNull List<KeyParameter> parameters);

    /**
     * Invoked to add algorithm-specific parameters for the KeyStore's {@code begin} operation,
     * including the key characteristics. This is useful in case the parameters to {@code begin}
     * depend on how the key was generated.
     * The default implementation provided here simply ignores these key characteristics because
     * they are not be needed for most engines.
     *
     * @param parameters keystore/keymaster arguments to be populated with algorithm-specific
     *                   parameters.
     * @param keyCharacteristics The key's characteristics.
     */
    protected void addAlgorithmSpecificParametersToBegin(
            @NonNull List<KeyParameter> parameters, Authorization[] keyCharacteristics) {
        addAlgorithmSpecificParametersToBegin(parameters);
    }

    /**
     * Invoked to obtain algorithm-specific parameters from the result of the KeyStore's
     * {@code begin} operation.
     *
     * <p>Some parameters, such as IV, are not required to be provided to {@code Cipher.init}. Such
     * parameters, if not provided, must be generated by KeyStore and returned to the user of
     * {@code Cipher} and potentially reused after {@code doFinal}.
     *
     * @param parameters keystore/keymaster arguments returned by KeyStore {@code createOperation}.
     */
    protected abstract void loadAlgorithmSpecificParametersFromBeginResult(
            KeyParameter[] parameters);

    protected abstract String getTransform();
}
