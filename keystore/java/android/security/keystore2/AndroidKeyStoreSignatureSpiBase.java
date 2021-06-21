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
import android.hardware.security.keymint.KeyParameter;
import android.security.KeyStoreException;
import android.security.KeyStoreOperation;
import android.security.keymaster.KeymasterDefs;
import android.security.keystore.ArrayUtils;
import android.security.keystore.KeyStoreCryptoOperation;

import libcore.util.EmptyArray;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.SignatureSpi;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for {@link SignatureSpi} implementations of Android KeyStore backed ciphers.
 *
 * @hide
 */
abstract class AndroidKeyStoreSignatureSpiBase extends SignatureSpi
        implements KeyStoreCryptoOperation {
    private static final String TAG = "AndroidKeyStoreSignatureSpiBase";

    // Fields below are populated by SignatureSpi.engineInitSign/engineInitVerify and KeyStore.begin
    // and should be preserved after SignatureSpi.engineSign/engineVerify finishes.
    private boolean mSigning;
    private AndroidKeyStoreKey mKey;

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
    private KeyStoreCryptoOperationStreamer mMessageStreamer;

    /**
     * Encountered exception which could not be immediately thrown because it was encountered inside
     * a method that does not throw checked exception. This exception will be thrown from
     * {@code engineSign} or {@code engineVerify}. Once such an exception is encountered,
     * {@code engineUpdate} starts ignoring input data.
     */
    private Exception mCachedException;

    /**
     * This signature object is used for public key operations, i.e, signatrue verification.
     * The Android Keystore backend does not perform public key operations and defers to the
     * Highest priority provider.
     */
    private Signature mSignature;

    AndroidKeyStoreSignatureSpiBase() {
        mOperation = null;
        mOperationChallenge = 0;
        mSigning = false;
        mKey = null;
        appRandom = null;
        mMessageStreamer = null;
        mCachedException = null;
        mSignature = null;
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

        try {
            mSignature = Signature.getInstance(getAlgorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new InvalidKeyException(e);
        }

        mSignature.initVerify(publicKey);
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
        mOperationChallenge = 0;
        mSigning = false;
        mKey = null;
        appRandom = null;
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
        abortOperation();
        mOperationChallenge = 0;
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

        List<KeyParameter> parameters = new ArrayList<>();
        addAlgorithmSpecificParametersToBegin(parameters);

        int purpose = mSigning ? KeymasterDefs.KM_PURPOSE_SIGN : KeymasterDefs.KM_PURPOSE_VERIFY;

        parameters.add(KeyStore2ParameterUtils.makeEnum(KeymasterDefs.KM_TAG_PURPOSE, purpose));

        try {
            mOperation = mKey.getSecurityLevel().createOperation(
                    mKey.getKeyIdDescriptor(),
                    parameters);
        } catch (KeyStoreException keyStoreException) {
            throw KeyStoreCryptoOperationUtils.getInvalidKeyException(
                    mKey, keyStoreException);
        }

        // Now we check if we got an operation challenge. This indicates that user authorization
        // is required. And if we got a challenge we check if the authorization can possibly
        // succeed.
        mOperationChallenge = KeyStoreCryptoOperationUtils.getOrMakeOperationChallenge(
                mOperation, mKey);

        mMessageStreamer = createMainDataStreamer(mOperation);
    }

    /**
     * Creates a streamer which sends the message to be signed/verified into the provided KeyStore
     *
     * <p>This implementation returns a working streamer.
     */
    @NonNull
    protected KeyStoreCryptoOperationStreamer createMainDataStreamer(
            @NonNull KeyStoreOperation operation) {
        return new KeyStoreCryptoOperationChunkedStreamer(
                new KeyStoreCryptoOperationChunkedStreamer.MainDataStream(
                        operation));
    }

    @Override
    public final long getOperationHandle() {
        return mOperationChallenge;
    }

    @Override
    protected final void engineUpdate(byte[] b, int off, int len) throws SignatureException {
        if (mSignature != null) {
            mSignature.update(b, off, len);
            return;
        }

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
                    null); // no signature provided -- it'll be generated by this invocation
        } catch (InvalidKeyException | KeyStoreException e) {
            throw new SignatureException(e);
        }

        resetWhilePreservingInitState();
        return signature;
    }

    @Override
    protected final boolean engineVerify(byte[] signature) throws SignatureException {
        if (mSignature != null) {
            return mSignature.verify(signature);
        }
        throw new IllegalStateException("Not initialised.");
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

    /**
     * Implementations need to report the algorithm they implement so that we can delegate to the
     * highest priority provider.
     * @return Algorithm string.
     */
    protected abstract String getAlgorithm();

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
     * @param parameters keystore/keymaster arguments to be populated with algorithm-specific
     *        parameters.
     */
    protected abstract void addAlgorithmSpecificParametersToBegin(
            @NonNull List<KeyParameter> parameters);
}
