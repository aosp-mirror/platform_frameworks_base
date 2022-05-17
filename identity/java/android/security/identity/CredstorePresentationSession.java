/*
 * Copyright 2021 The Android Open Source Project
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

package android.security.identity;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.LinkedHashMap;
import java.util.Map;

class CredstorePresentationSession extends PresentationSession {
    private static final String TAG = "CredstorePresentationSession";

    private @IdentityCredentialStore.Ciphersuite int mCipherSuite;
    private Context mContext;
    private CredstoreIdentityCredentialStore mStore;
    private ISession mBinder;
    private Map<String, CredstoreIdentityCredential> mCredentialCache = new LinkedHashMap<>();
    private KeyPair mEphemeralKeyPair = null;
    private byte[] mSessionTranscript = null;
    private boolean mOperationHandleSet = false;
    private long mOperationHandle = 0;

    CredstorePresentationSession(Context context,
            @IdentityCredentialStore.Ciphersuite int cipherSuite,
            CredstoreIdentityCredentialStore store,
            ISession binder) {
        mContext = context;
        mCipherSuite = cipherSuite;
        mStore = store;
        mBinder = binder;
    }

    private void ensureEphemeralKeyPair() {
        if (mEphemeralKeyPair != null) {
            return;
        }
        try {
            // This PKCS#12 blob is generated in credstore, using BoringSSL.
            //
            // The main reason for this convoluted approach and not just sending the decomposed
            // key-pair is that this would require directly using (device-side) BouncyCastle which
            // is tricky due to various API hiding efforts. So instead we have credstore generate
            // this PKCS#12 blob. The blob is encrypted with no password (sadly, also, BoringSSL
            // doesn't support not using encryption when building a PKCS#12 blob).
            //
            byte[] pkcs12 = mBinder.getEphemeralKeyPair();
            String alias = "ephemeralKey";
            char[] password = {};

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ByteArrayInputStream bais = new ByteArrayInputStream(pkcs12);
            ks.load(bais, password);
            PrivateKey privKey = (PrivateKey) ks.getKey(alias, password);

            Certificate cert = ks.getCertificate(alias);
            PublicKey pubKey = cert.getPublicKey();

            mEphemeralKeyPair = new KeyPair(pubKey, privKey);
        } catch (android.os.ServiceSpecificException e) {
            throw new RuntimeException("Unexpected ServiceSpecificException with code "
                    + e.errorCode, e);
        } catch (android.os.RemoteException
                | KeyStoreException
                | CertificateException
                | UnrecoverableKeyException
                | NoSuchAlgorithmException
                | IOException e) {
            throw new RuntimeException("Unexpected exception ", e);
        }
    }

    @Override
    public @NonNull KeyPair getEphemeralKeyPair() {
        ensureEphemeralKeyPair();
        return mEphemeralKeyPair;
    }

    @Override
    public void setReaderEphemeralPublicKey(@NonNull PublicKey readerEphemeralPublicKey)
            throws InvalidKeyException {
        try {
            byte[] uncompressedForm =
                    Util.publicKeyEncodeUncompressedForm(readerEphemeralPublicKey);
            mBinder.setReaderEphemeralPublicKey(uncompressedForm);
        } catch (android.os.RemoteException e) {
            throw new RuntimeException("Unexpected RemoteException ", e);
        } catch (android.os.ServiceSpecificException e) {
            throw new RuntimeException("Unexpected ServiceSpecificException with code "
                    + e.errorCode, e);
        }
    }

    @Override
    public void setSessionTranscript(@NonNull byte[] sessionTranscript) {
        try {
            mBinder.setSessionTranscript(sessionTranscript);
            mSessionTranscript = sessionTranscript;
        } catch (android.os.RemoteException e) {
            throw new RuntimeException("Unexpected RemoteException ", e);
        } catch (android.os.ServiceSpecificException e) {
            throw new RuntimeException("Unexpected ServiceSpecificException with code "
                    + e.errorCode, e);
        }
    }

    @Override
    public @Nullable CredentialDataResult getCredentialData(@NonNull String credentialName,
                                                            @NonNull CredentialDataRequest request)
            throws NoAuthenticationKeyAvailableException, InvalidReaderSignatureException,
            InvalidRequestMessageException, EphemeralPublicKeyNotFoundException {
        try {
            // Cache the IdentityCredential to satisfy the property that AuthKey usage counts are
            // incremented on only the _first_ getCredentialData() call.
            //
            CredstoreIdentityCredential credential = mCredentialCache.get(credentialName);
            if (credential == null) {
                ICredential credstoreCredential =
                    mBinder.getCredentialForPresentation(credentialName);
                credential = new CredstoreIdentityCredential(mContext, credentialName,
                                                             mCipherSuite, credstoreCredential,
                                                             this);
                mCredentialCache.put(credentialName, credential);

                credential.setAllowUsingExhaustedKeys(request.isAllowUsingExhaustedKeys());
                credential.setAllowUsingExpiredKeys(request.isAllowUsingExpiredKeys());
                credential.setIncrementKeyUsageCount(request.isIncrementUseCount());
            }

            ResultData deviceSignedResult = credential.getEntries(
                    request.getRequestMessage(),
                    request.getDeviceSignedEntriesToRequest(),
                    mSessionTranscript,
                    request.getReaderSignature());

            // By design this second getEntries() call consumes the same auth-key.

            ResultData issuerSignedResult = credential.getEntries(
                    request.getRequestMessage(),
                    request.getIssuerSignedEntriesToRequest(),
                    mSessionTranscript,
                    request.getReaderSignature());

            return new CredstoreCredentialDataResult(deviceSignedResult, issuerSignedResult);

        } catch (SessionTranscriptMismatchException e) {
            throw new RuntimeException("Unexpected ", e);
        } catch (android.os.RemoteException e) {
            throw new RuntimeException("Unexpected RemoteException ", e);
        } catch (android.os.ServiceSpecificException e) {
            if (e.errorCode == ICredentialStore.ERROR_NO_SUCH_CREDENTIAL) {
                return null;
            } else {
                throw new RuntimeException("Unexpected ServiceSpecificException with code "
                        + e.errorCode, e);
            }
        }
    }

    /**
     * Called by android.hardware.biometrics.CryptoObject#getOpId() to get an
     * operation handle.
     *
     * @hide
     */
    @Override
    public long getCredstoreOperationHandle() {
        if (!mOperationHandleSet) {
            try {
                mOperationHandle = mBinder.getAuthChallenge();
                mOperationHandleSet = true;
            } catch (android.os.RemoteException e) {
                throw new RuntimeException("Unexpected RemoteException ", e);
            } catch (android.os.ServiceSpecificException e) {
                if (e.errorCode == ICredentialStore.ERROR_NO_AUTHENTICATION_KEY_AVAILABLE) {
                    // The NoAuthenticationKeyAvailableException will be thrown when
                    // the caller proceeds to call getEntries().
                }
                throw new RuntimeException("Unexpected ServiceSpecificException with code "
                        + e.errorCode, e);
            }
        }
        return mOperationHandle;
    }

}
