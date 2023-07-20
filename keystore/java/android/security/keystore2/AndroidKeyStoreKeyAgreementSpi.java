/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.hardware.security.keymint.Algorithm;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.KeyPurpose;
import android.hardware.security.keymint.Tag;
import android.security.KeyStoreException;
import android.security.KeyStoreOperation;
import android.security.keystore.KeyStoreCryptoOperation;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.KeyAgreementSpi;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link KeyAgreementSpi} which provides an ECDH implementation backed by Android KeyStore.
 *
 * @hide
 */
public class AndroidKeyStoreKeyAgreementSpi extends KeyAgreementSpi
        implements KeyStoreCryptoOperation {

    private static final String TAG = "AndroidKeyStoreKeyAgreementSpi";

    /**
     * ECDH implementation.
     *
     * @hide
     */
    public static class ECDH extends AndroidKeyStoreKeyAgreementSpi {
        public ECDH() {
            super(Algorithm.EC);
        }
    }

    /**
     * X25519 key agreement support.
     *
     * @hide
     */
    public static class XDH extends AndroidKeyStoreKeyAgreementSpi {
        public XDH() {
            super(Algorithm.EC);
        }
    }

    private final int mKeymintAlgorithm;

    // Fields below are populated by engineInit and should be preserved after engineDoFinal.
    private AndroidKeyStorePrivateKey mKey;
    private PublicKey mOtherPartyKey;

    // Fields below are reset when engineDoFinal succeeds.
    private KeyStoreOperation mOperation;
    private long mOperationHandle;

    protected AndroidKeyStoreKeyAgreementSpi(int keymintAlgorithm) {
        resetAll();

        mKeymintAlgorithm = keymintAlgorithm;
    }

    @Override
    protected void engineInit(Key key, SecureRandom random) throws InvalidKeyException {
        resetAll();

        if (key == null) {
            throw new InvalidKeyException("key == null");
        } else if (!(key instanceof AndroidKeyStorePrivateKey)) {
            throw new InvalidKeyException(
                    "Only Android KeyStore private keys supported. Key: " + key);
        }
        // Checking the correct KEY_PURPOSE and algorithm is done by the Keymint implementation in
        // ensureKeystoreOperationInitialized() below.
        mKey = (AndroidKeyStorePrivateKey) key;

        boolean success = false;
        try {
            ensureKeystoreOperationInitialized();
            success = true;
        } finally {
            if (!success) {
                resetAll();
            }
        }
    }

    @Override
    protected void engineInit(Key key, AlgorithmParameterSpec params, SecureRandom random)
            throws InvalidKeyException, InvalidAlgorithmParameterException {
        if (params != null) {
            throw new InvalidAlgorithmParameterException(
                    "Unsupported algorithm parameters: " + params);
        }
        engineInit(key, random);
    }

    @Override
    protected Key engineDoPhase(Key key, boolean lastPhase)
            throws InvalidKeyException, IllegalStateException {
        ensureKeystoreOperationInitialized();

        if (key == null) {
            throw new InvalidKeyException("key == null");
        } else if (!(key instanceof PublicKey)) {
            throw new InvalidKeyException("Only public keys supported. Key: " + key);
        } else if (!lastPhase) {
            throw new IllegalStateException(
                    "Only one other party supported. lastPhase must be set to true.");
        } else if (mOtherPartyKey != null) {
            throw new IllegalStateException(
                    "Only one other party supported. doPhase() must only be called exactly once.");
        }
        // The other party key will be passed as part of the doFinal() call, to prevent an
        // additional IPC.
        mOtherPartyKey = (PublicKey) key;

        return null; // No intermediate key
    }

    @Override
    protected byte[] engineGenerateSecret() throws IllegalStateException {
        try {
            ensureKeystoreOperationInitialized();
        } catch (InvalidKeyException e) {
            throw new IllegalStateException("Not initialized", e);
        }

        if (mOtherPartyKey == null) {
            throw new IllegalStateException("Other party key not provided. Call doPhase() first.");
        }
        byte[] otherPartyKeyEncoded = mOtherPartyKey.getEncoded();

        try {
            return mOperation.finish(otherPartyKeyEncoded, null);
        } catch (KeyStoreException e) {
            throw new ProviderException("Keystore operation failed", e);
        } finally {
            resetWhilePreservingInitState();
        }
    }

    @Override
    protected SecretKey engineGenerateSecret(String algorithm)
            throws IllegalStateException, NoSuchAlgorithmException, InvalidKeyException {
        byte[] generatedSecret = engineGenerateSecret();

        return new SecretKeySpec(generatedSecret, algorithm);
    }

    @Override
    protected int engineGenerateSecret(byte[] sharedSecret, int offset)
            throws IllegalStateException, ShortBufferException {
        byte[] generatedSecret = engineGenerateSecret();

        if (generatedSecret.length > sharedSecret.length - offset) {
            throw new ShortBufferException("Needed: " + generatedSecret.length);
        }
        System.arraycopy(generatedSecret, 0, sharedSecret, offset, generatedSecret.length);
        return generatedSecret.length;
    }

    @Override
    public long getOperationHandle() {
        return mOperationHandle;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            resetAll();
        } finally {
            super.finalize();
        }
    }

    private void resetWhilePreservingInitState() {
        KeyStoreCryptoOperationUtils.abortOperation(mOperation);
        mOperationHandle = 0;
        mOperation = null;
        mOtherPartyKey = null;
    }

    private void resetAll() {
        resetWhilePreservingInitState();
        mKey = null;
    }

    private void ensureKeystoreOperationInitialized()
            throws InvalidKeyException, IllegalStateException {
        if (mKey == null) {
            throw new IllegalStateException("Not initialized");
        }
        if (mOperation != null) {
            return;
        }

        // We don't need to explicitly pass in any other parameters here, as they're part of the
        // private key that is available to Keymint.
        List<KeyParameter> parameters = new ArrayList<>();
        parameters.add(KeyStore2ParameterUtils.makeEnum(
                Tag.PURPOSE, KeyPurpose.AGREE_KEY
        ));

        try {
            mOperation =
                    mKey.getSecurityLevel().createOperation(mKey.getKeyIdDescriptor(), parameters);
        } catch (KeyStoreException keyStoreException) {
            // If necessary, throw an exception due to KeyStore operation having failed.
            InvalidKeyException e =
                    KeyStoreCryptoOperationUtils.getInvalidKeyException(mKey, keyStoreException);
            if (e != null) {
                throw e;
            }
        }

        // Set the operation handle. This will be a random number, or the operation challenge if
        // user authentication is required. If we got a challenge we check if the authorization can
        // possibly succeed.
        mOperationHandle =
                KeyStoreCryptoOperationUtils.getOrMakeOperationChallenge(mOperation, mKey);
    }
}
