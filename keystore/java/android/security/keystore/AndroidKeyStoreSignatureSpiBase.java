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
import android.os.IBinder;
import android.security.KeyStore;
import android.security.KeyStoreException;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterDefs;
import android.security.keymaster.OperationResult;

import libcore.util.EmptyArray;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.security.SignatureSpi;

/**
 * Base class for {@link SignatureSpi} implementations of Android KeyStore backed ciphers.
 *
 * @hide
 */
abstract class AndroidKeyStoreSignatureSpiBase extends SignatureSpi
        implements KeyStoreCryptoOperation {
    private final KeyStore mKeyStore;

    // Fields below are populated by SignatureSpi.engineInitSign/engineInitVerify and KeyStore.begin
    // and should be preserved after SignatureSpi.engineSign/engineVerify finishes.
    private boolean mSigning;
    private AndroidKeyStoreKey mKey;

    /**
     * Token referencing this operation inside keystore service. It is initialized by
     * {@code engineInitSign}/{@code engineInitVerify} and is invalidated when
     * {@code engineSign}/{@code engineVerify} succeeds and on some error conditions in between.
     */
    private IBinder mOperationToken;
    private long mOperationHandle;
    private KeyStoreCryptoOperationStreamer mMessageStreamer;

    /**
     * Encountered exception which could not be immediately thrown because it was encountered inside
     * a method that does not throw checked exception. This exception will be thrown from
     * {@code engineSign} or {@code engineVerify}. Once such an exception is encountered,
     * {@code engineUpdate} starts ignoring input data.
     */
    private Exception mCachedException;

    AndroidKeyStoreSignatureSpiBase() {
        mKeyStore = KeyStore.getInstance();
    }

    @Override
    protected final void engineInitSign(PrivateKey key) throws InvalidKeyException {
        engineInitSign(key, null);
    }

    @Override
    protected final void engineInitSign(PrivateKey privateKey, SecureRandom random)
            throws InvalidKeyException {
        resetAll();

        boolean success = false;
        try {
            if (privateKey == null) {
                throw new InvalidKeyException("Unsupported key: null");
            }
            AndroidKeyStoreKey keystoreKey;
            if (privateKey instanceof AndroidKeyStorePrivateKey) {
                keystoreKey = (AndroidKeyStoreKey) privateKey;
            } else {
                throw new InvalidKeyException("Unsupported private key type: " + privateKey);
            }
            mSigning = true;
            initKey(keystoreKey);
            appRandom = random;
            ensureKeystoreOperationInitialized();
            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    @Override
    protected final void engineInitVerify(PublicKey publicKey) throws InvalidKeyException {
        resetAll();

        boolean success = false;
        try {
            if (publicKey == null) {
                throw new InvalidKeyException("Unsupported key: null");
            }
            AndroidKeyStoreKey keystoreKey;
            if (publicKey instanceof AndroidKeyStorePublicKey) {
                keystoreKey = (AndroidKeyStorePublicKey) publicKey;
            } else {
                throw new InvalidKeyException("Unsupported public key type: " + publicKey);
            }
            mSigning = false;
            initKey(keystoreKey);
            appRandom = null;
            ensureKeystoreOperationInitialized();
            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    /**
     * Configures this signature instance to use the provided key.
     *
     * @throws InvalidKeyException if the {@code key} is not suitable.
     */
    @CallSuper
    protected void initKey(AndroidKeyStoreKey key) throws InvalidKeyException {
        mKey = key;
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
            mOperationToken = null;
            mKeyStore.abort(operationToken);
        }
        mSigning = false;
        mKey = null;
        appRandom = null;
        mOperationToken = null;
        mOperationHandle = 0;
        mMessageStreamer = null;
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
            mOperationToken = null;
            mKeyStore.abort(operationToken);
        }
        mOperationHandle = 0;
        mMessageStreamer = null;
        mCachedException = null;
    }

    private void ensureKeystoreOperationInitialized() throws InvalidKeyException {
        if (mMessageStreamer != null) {
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

        OperationResult opResult = mKeyStore.begin(
                mKey.getAlias(),
                mSigning ? KeymasterDefs.KM_PURPOSE_SIGN : KeymasterDefs.KM_PURPOSE_VERIFY,
                true, // permit aborting this operation if keystore runs out of resources
                keymasterInputArgs,
                null // no additional entropy for begin -- only finish might need some
                );
        if (opResult == null) {
            throw new KeyStoreConnectException();
        }

        // Store operation token and handle regardless of the error code returned by KeyStore to
        // ensure that the operation gets aborted immediately if the code below throws an exception.
        mOperationToken = opResult.token;
        mOperationHandle = opResult.operationHandle;

        // If necessary, throw an exception due to KeyStore operation having failed.
        InvalidKeyException e = KeyStoreCryptoOperationUtils.getInvalidKeyExceptionForInit(
                mKeyStore, mKey, opResult.resultCode);
        if (e != null) {
            throw e;
        }

        if (mOperationToken == null) {
            throw new ProviderException("Keystore returned null operation token");
        }
        if (mOperationHandle == 0) {
            throw new ProviderException("Keystore returned invalid operation handle");
        }

        mMessageStreamer = createMainDataStreamer(mKeyStore, opResult.token);
    }

    /**
     * Creates a streamer which sends the message to be signed/verified into the provided KeyStore
     *
     * <p>This implementation returns a working streamer.
     */
    @NonNull
    protected KeyStoreCryptoOperationStreamer createMainDataStreamer(
            KeyStore keyStore, IBinder operationToken) {
        return new KeyStoreCryptoOperationChunkedStreamer(
                new KeyStoreCryptoOperationChunkedStreamer.MainDataStream(
                        keyStore, operationToken));
    }

    @Override
    public final long getOperationHandle() {
        return mOperationHandle;
    }

    @Override
    protected final void engineUpdate(byte[] b, int off, int len) throws SignatureException {
        if (mCachedException != null) {
            throw new SignatureException(mCachedException);
        }

        try {
            ensureKeystoreOperationInitialized();
        } catch (InvalidKeyException e) {
            throw new SignatureException(e);
        }

        if (len == 0) {
            return;
        }

        byte[] output;
        try {
            output = mMessageStreamer.update(b, off, len);
        } catch (KeyStoreException e) {
            throw new SignatureException(e);
        }

        if (output.length != 0) {
            throw new ProviderException(
                    "Update operation unexpectedly produced output: " + output.length + " bytes");
        }
    }

    @Override
    protected final void engineUpdate(byte b) throws SignatureException {
        engineUpdate(new byte[] {b}, 0, 1);
    }

    @Override
    protected final void engineUpdate(ByteBuffer input) {
        byte[] b;
        int off;
        int len = input.remaining();
        if (input.hasArray()) {
            b = input.array();
            off = input.arrayOffset() + input.position();
            input.position(input.limit());
        } else {
            b = new byte[len];
            off = 0;
            input.get(b);
        }

        try {
            engineUpdate(b, off, len);
        } catch (SignatureException e) {
            mCachedException = e;
        }
    }

    @Override
    protected final int engineSign(byte[] out, int outOffset, int outLen)
            throws SignatureException {
        return super.engineSign(out, outOffset, outLen);
    }

    @Override
    protected final byte[] engineSign() throws SignatureException {
        if (mCachedException != null) {
            throw new SignatureException(mCachedException);
        }

        byte[] signature;
        try {
            ensureKeystoreOperationInitialized();

            byte[] additionalEntropy =
                    KeyStoreCryptoOperationUtils.getRandomBytesToMixIntoKeystoreRng(
                            appRandom, getAdditionalEntropyAmountForSign());
            signature = mMessageStreamer.doFinal(
                    EmptyArray.BYTE, 0, 0,
                    null, // no signature provided -- it'll be generated by this invocation
                    additionalEntropy);
        } catch (InvalidKeyException | KeyStoreException e) {
            throw new SignatureException(e);
        }

        resetWhilePreservingInitState();
        return signature;
    }

    @Override
    protected final boolean engineVerify(byte[] signature) throws SignatureException {
        if (mCachedException != null) {
            throw new SignatureException(mCachedException);
        }

        try {
            ensureKeystoreOperationInitialized();
        } catch (InvalidKeyException e) {
            throw new SignatureException(e);
        }

        boolean verified;
        try {
            byte[] output = mMessageStreamer.doFinal(
                    EmptyArray.BYTE, 0, 0,
                    signature,
                    null // no additional entropy needed -- verification is deterministic
                    );
            if (output.length != 0) {
                throw new ProviderException(
                        "Signature verification unexpected produced output: " + output.length
                        + " bytes");
            }
            verified = true;
        } catch (KeyStoreException e) {
            switch (e.getErrorCode()) {
                case KeymasterDefs.KM_ERROR_VERIFICATION_FAILED:
                    verified = false;
                    break;
                default:
                    throw new SignatureException(e);
            }
        }

        resetWhilePreservingInitState();
        return verified;
    }

    @Override
    protected final boolean engineVerify(byte[] sigBytes, int offset, int len)
            throws SignatureException {
        return engineVerify(ArrayUtils.subarray(sigBytes, offset, len));
    }

    @Deprecated
    @Override
    protected final Object engineGetParameter(String param) throws InvalidParameterException {
        throw new InvalidParameterException();
    }

    @Deprecated
    @Override
    protected final void engineSetParameter(String param, Object value)
            throws InvalidParameterException {
        throw new InvalidParameterException();
    }

    protected final KeyStore getKeyStore() {
        return mKeyStore;
    }

    /**
     * Returns {@code true} if this signature is initialized for signing, {@code false} if this
     * signature is initialized for verification.
     */
    protected final boolean isSigning() {
        return mSigning;
    }

    // The methods below need to be implemented by subclasses.

    /**
     * Returns the amount of additional entropy (in bytes) to be provided to the KeyStore's
     * {@code finish} operation when generating a signature.
     *
     * <p>This value should match (or exceed) the amount of Shannon entropy of the produced
     * signature assuming the key and the message are known. For example, for ECDSA signature this
     * should be the size of {@code R}, whereas for the RSA signature with PKCS#1 padding this
     * should be {@code 0}.
     */
    protected abstract int getAdditionalEntropyAmountForSign();

    /**
     * Invoked to add algorithm-specific parameters for the KeyStore's {@code begin} operation.
     *
     * @param keymasterArgs keystore/keymaster arguments to be populated with algorithm-specific
     *        parameters.
     */
    protected abstract void addAlgorithmSpecificParametersToBegin(
            @NonNull KeymasterArguments keymasterArgs);
}
