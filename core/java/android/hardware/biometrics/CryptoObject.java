/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.hardware.biometrics;

import android.annotation.NonNull;
import android.security.identity.IdentityCredential;
import android.security.identity.PresentationSession;
import android.security.keystore2.AndroidKeyStoreProvider;

import java.security.Signature;

import javax.crypto.Cipher;
import javax.crypto.Mac;

/**
 * A wrapper class for the crypto objects supported by BiometricPrompt and FingerprintManager.
 * Currently the framework supports {@link Signature}, {@link Cipher}, {@link Mac},
 * {@link IdentityCredential}, and {@link PresentationSession} objects.
 * @hide
 */
public class CryptoObject {
    private final Object mCrypto;

    public CryptoObject(@NonNull Signature signature) {
        mCrypto = signature;
    }

    public CryptoObject(@NonNull Cipher cipher) {
        mCrypto = cipher;
    }

    public CryptoObject(@NonNull Mac mac) {
        mCrypto = mac;
    }

    /**
     * Create from a {@link IdentityCredential} object.
     *
     * @param credential a {@link IdentityCredential} object.
     * @deprecated Use {@link PresentationSession} instead of {@link IdentityCredential}.
     */
    @Deprecated
    public CryptoObject(@NonNull IdentityCredential credential) {
        mCrypto = credential;
    }

    public CryptoObject(@NonNull PresentationSession session) {
        mCrypto = session;
    }

    /**
     * Get {@link Signature} object.
     * @return {@link Signature} object or null if this doesn't contain one.
     */
    public Signature getSignature() {
        return mCrypto instanceof Signature ? (Signature) mCrypto : null;
    }

    /**
     * Get {@link Cipher} object.
     * @return {@link Cipher} object or null if this doesn't contain one.
     */
    public Cipher getCipher() {
        return mCrypto instanceof Cipher ? (Cipher) mCrypto : null;
    }

    /**
     * Get {@link Mac} object.
     * @return {@link Mac} object or null if this doesn't contain one.
     */
    public Mac getMac() {
        return mCrypto instanceof Mac ? (Mac) mCrypto : null;
    }

    /**
     * Get {@link IdentityCredential} object.
     * @return {@link IdentityCredential} object or null if this doesn't contain one.
     * @deprecated Use {@link PresentationSession} instead of {@link IdentityCredential}.
     */
    @Deprecated
    public IdentityCredential getIdentityCredential() {
        return mCrypto instanceof IdentityCredential ? (IdentityCredential) mCrypto : null;
    }

    /**
     * Get {@link PresentationSession} object.
     * @return {@link PresentationSession} object or null if this doesn't contain one.
     */
    public PresentationSession getPresentationSession() {
        return mCrypto instanceof PresentationSession ? (PresentationSession) mCrypto : null;
    }

    /**
     * @hide
     * @return the opId associated with this object or 0 if none
     */
    public final long getOpId() {
        if (mCrypto == null) {
            return 0;
        } else if (mCrypto instanceof IdentityCredential) {
            return ((IdentityCredential) mCrypto).getCredstoreOperationHandle();
        } else if (mCrypto instanceof PresentationSession) {
            return ((PresentationSession) mCrypto).getCredstoreOperationHandle();
        }
        return AndroidKeyStoreProvider.getKeyStoreOperationHandle(mCrypto);
    }
}
