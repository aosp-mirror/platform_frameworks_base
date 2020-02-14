/*
 * Copyright 2019 The Android Open Source Project
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
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

class CredstoreIdentityCredential extends IdentityCredential {

    private static final String TAG = "CredstoreIdentityCredential";
    private String mCredentialName;
    private @IdentityCredentialStore.Ciphersuite int mCipherSuite;
    private Context mContext;
    private ICredential mBinder;

    CredstoreIdentityCredential(Context context, String credentialName,
            @IdentityCredentialStore.Ciphersuite int cipherSuite,
            ICredential binder) {
        mContext = context;
        mCredentialName = credentialName;
        mCipherSuite = cipherSuite;
        mBinder = binder;
    }

    private KeyPair mEphemeralKeyPair = null;
    private SecretKey mSecretKey = null;
    private SecretKey mReaderSecretKey = null;
    private int mEphemeralCounter;
    private int mReadersExpectedEphemeralCounter;

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
            byte[] pkcs12 = mBinder.createEphemeralKeyPair();
            String alias = "ephemeralKey";
            char[] password = {};

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ByteArrayInputStream bais = new ByteArrayInputStream(pkcs12);
            ks.load(bais, password);
            PrivateKey privKey = (PrivateKey) ks.getKey(alias, password);

            Certificate cert = ks.getCertificate(alias);
            PublicKey pubKey = cert.getPublicKey();

            mEphemeralKeyPair = new KeyPair(pubKey, privKey);
        } catch (android.os.RemoteException e) {
            throw new RuntimeException("Unexpected RemoteException ", e);
        } catch (android.os.ServiceSpecificException e) {
            throw new RuntimeException("Unexpected ServiceSpecificException with code "
                    + e.errorCode, e);
        } catch (KeyStoreException
                | CertificateException
                | UnrecoverableKeyException
                | NoSuchAlgorithmException
                | IOException e) {
            throw new RuntimeException("Unexpected exception ", e);
        }
    }

    @Override
    public @NonNull KeyPair createEphemeralKeyPair() {
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

        ensureEphemeralKeyPair();

        try {
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(mEphemeralKeyPair.getPrivate());
            ka.doPhase(readerEphemeralPublicKey, true);
            byte[] sharedSecret = ka.generateSecret();

            byte[] salt = new byte[1];
            byte[] info = new byte[0];

            salt[0] = 0x01;
            byte[] derivedKey = Util.computeHkdf("HmacSha256", sharedSecret, salt, info, 32);
            mSecretKey = new SecretKeySpec(derivedKey, "AES");

            salt[0] = 0x00;
            derivedKey = Util.computeHkdf("HmacSha256", sharedSecret, salt, info, 32);
            mReaderSecretKey = new SecretKeySpec(derivedKey, "AES");

            mEphemeralCounter = 1;
            mReadersExpectedEphemeralCounter = 1;

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error performing key agreement", e);
        }
    }

    @Override
    public @NonNull byte[] encryptMessageToReader(@NonNull byte[] messagePlaintext) {
        byte[] messageCiphertextAndAuthTag = null;
        try {
            ByteBuffer iv = ByteBuffer.allocate(12);
            iv.putInt(0, 0x00000000);
            iv.putInt(4, 0x00000001);
            iv.putInt(8, mEphemeralCounter);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec encryptionParameterSpec = new GCMParameterSpec(128, iv.array());
            cipher.init(Cipher.ENCRYPT_MODE, mSecretKey, encryptionParameterSpec);
            messageCiphertextAndAuthTag = cipher.doFinal(messagePlaintext);
        } catch (BadPaddingException
                | IllegalBlockSizeException
                | NoSuchPaddingException
                | InvalidKeyException
                | NoSuchAlgorithmException
                | InvalidAlgorithmParameterException e) {
            throw new RuntimeException("Error encrypting message", e);
        }
        mEphemeralCounter += 1;
        return messageCiphertextAndAuthTag;
    }

    @Override
    public @NonNull byte[] decryptMessageFromReader(@NonNull byte[] messageCiphertext)
            throws MessageDecryptionException {
        ByteBuffer iv = ByteBuffer.allocate(12);
        iv.putInt(0, 0x00000000);
        iv.putInt(4, 0x00000000);
        iv.putInt(8, mReadersExpectedEphemeralCounter);
        byte[] plainText = null;
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, mReaderSecretKey,
                    new GCMParameterSpec(128, iv.array()));
            plainText = cipher.doFinal(messageCiphertext);
        } catch (BadPaddingException
                | IllegalBlockSizeException
                | InvalidAlgorithmParameterException
                | InvalidKeyException
                | NoSuchAlgorithmException
                | NoSuchPaddingException e) {
            throw new MessageDecryptionException("Error decrypting message", e);
        }
        mReadersExpectedEphemeralCounter += 1;
        return plainText;
    }

    @Override
    public @NonNull Collection<X509Certificate> getCredentialKeyCertificateChain() {
        try {
            byte[] certsBlob = mBinder.getCredentialKeyCertificateChain();
            ByteArrayInputStream bais = new ByteArrayInputStream(certsBlob);

            Collection<? extends Certificate> certs = null;
            try {
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                certs = factory.generateCertificates(bais);
            } catch (CertificateException e) {
                throw new RuntimeException("Error decoding certificates", e);
            }

            LinkedList<X509Certificate> x509Certs = new LinkedList<>();
            for (Certificate cert : certs) {
                x509Certs.add((X509Certificate) cert);
            }
            return x509Certs;
        } catch (android.os.RemoteException e) {
            throw new RuntimeException("Unexpected RemoteException ", e);
        } catch (android.os.ServiceSpecificException e) {
            throw new RuntimeException("Unexpected ServiceSpecificException with code "
                    + e.errorCode, e);
        }
    }

    private boolean mAllowUsingExhaustedKeys = true;

    @Override
    public void setAllowUsingExhaustedKeys(boolean allowUsingExhaustedKeys) {
        mAllowUsingExhaustedKeys = allowUsingExhaustedKeys;
    }

    private boolean mOperationHandleSet = false;
    private long mOperationHandle = 0;

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
                mOperationHandle = mBinder.selectAuthKey(mAllowUsingExhaustedKeys);
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

    @NonNull
    @Override
    public ResultData getEntries(
            @Nullable byte[] requestMessage,
            @NonNull Map<String, Collection<String>> entriesToRequest,
            @Nullable byte[] sessionTranscript,
            @Nullable byte[] readerSignature)
            throws SessionTranscriptMismatchException, NoAuthenticationKeyAvailableException,
            InvalidReaderSignatureException, EphemeralPublicKeyNotFoundException,
            InvalidRequestMessageException {

        RequestNamespaceParcel[] rnsParcels = new RequestNamespaceParcel[entriesToRequest.size()];
        int n = 0;
        for (String namespaceName : entriesToRequest.keySet()) {
            Collection<String> entryNames = entriesToRequest.get(namespaceName);
            rnsParcels[n] = new RequestNamespaceParcel();
            rnsParcels[n].namespaceName = namespaceName;
            rnsParcels[n].entries = new RequestEntryParcel[entryNames.size()];
            int m = 0;
            for (String entryName : entryNames) {
                rnsParcels[n].entries[m] = new RequestEntryParcel();
                rnsParcels[n].entries[m].name = entryName;
                m++;
            }
            n++;
        }

        GetEntriesResultParcel resultParcel = null;
        try {
            resultParcel = mBinder.getEntries(
                requestMessage != null ? requestMessage : new byte[0],
                rnsParcels,
                sessionTranscript != null ? sessionTranscript : new byte[0],
                readerSignature != null ? readerSignature : new byte[0],
                mAllowUsingExhaustedKeys);
        } catch (android.os.RemoteException e) {
            throw new RuntimeException("Unexpected RemoteException ", e);
        } catch (android.os.ServiceSpecificException e) {
            if (e.errorCode == ICredentialStore.ERROR_EPHEMERAL_PUBLIC_KEY_NOT_FOUND) {
                throw new EphemeralPublicKeyNotFoundException(e.getMessage(), e);
            } else if (e.errorCode == ICredentialStore.ERROR_INVALID_READER_SIGNATURE) {
                throw new InvalidReaderSignatureException(e.getMessage(), e);
            } else if (e.errorCode == ICredentialStore.ERROR_NO_AUTHENTICATION_KEY_AVAILABLE) {
                throw new NoAuthenticationKeyAvailableException(e.getMessage(), e);
            } else if (e.errorCode == ICredentialStore.ERROR_INVALID_ITEMS_REQUEST_MESSAGE) {
                throw new InvalidRequestMessageException(e.getMessage(), e);
            } else if (e.errorCode == ICredentialStore.ERROR_SESSION_TRANSCRIPT_MISMATCH) {
                throw new SessionTranscriptMismatchException(e.getMessage(), e);
            } else {
                throw new RuntimeException("Unexpected ServiceSpecificException with code "
                        + e.errorCode, e);
            }
        }

        byte[] mac = resultParcel.mac;
        if (mac != null && mac.length == 0) {
            mac = null;
        }
        CredstoreResultData.Builder resultDataBuilder = new CredstoreResultData.Builder(
                resultParcel.staticAuthenticationData, resultParcel.deviceNameSpaces, mac);

        for (ResultNamespaceParcel resultNamespaceParcel : resultParcel.resultNamespaces) {
            for (ResultEntryParcel resultEntryParcel : resultNamespaceParcel.entries) {
                if (resultEntryParcel.status == ICredential.STATUS_OK) {
                    resultDataBuilder.addEntry(resultNamespaceParcel.namespaceName,
                            resultEntryParcel.name, resultEntryParcel.value);
                } else {
                    resultDataBuilder.addErrorStatus(resultNamespaceParcel.namespaceName,
                            resultEntryParcel.name,
                            resultEntryParcel.status);
                }
            }
        }
        return resultDataBuilder.build();
    }

    @Override
    public void setAvailableAuthenticationKeys(int keyCount, int maxUsesPerKey) {
        try {
            mBinder.setAvailableAuthenticationKeys(keyCount, maxUsesPerKey);
        } catch (android.os.RemoteException e) {
            throw new RuntimeException("Unexpected RemoteException ", e);
        } catch (android.os.ServiceSpecificException e) {
            throw new RuntimeException("Unexpected ServiceSpecificException with code "
                    + e.errorCode, e);
        }
    }

    @Override
    public @NonNull Collection<X509Certificate> getAuthKeysNeedingCertification() {
        try {
            AuthKeyParcel[] authKeyParcels = mBinder.getAuthKeysNeedingCertification();
            LinkedList<X509Certificate> x509Certs = new LinkedList<>();
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (AuthKeyParcel authKeyParcel : authKeyParcels) {
                Collection<? extends Certificate> certs = null;
                ByteArrayInputStream bais = new ByteArrayInputStream(authKeyParcel.x509cert);
                certs = factory.generateCertificates(bais);
                if (certs.size() != 1) {
                    throw new RuntimeException("Returned blob yields more than one X509 cert");
                }
                X509Certificate authKeyCert = (X509Certificate) certs.iterator().next();
                x509Certs.add(authKeyCert);
            }
            return x509Certs;
        } catch (CertificateException e) {
            throw new RuntimeException("Error decoding authenticationKey", e);
        } catch (android.os.RemoteException e) {
            throw new RuntimeException("Unexpected RemoteException ", e);
        } catch (android.os.ServiceSpecificException e) {
            throw new RuntimeException("Unexpected ServiceSpecificException with code "
                    + e.errorCode, e);
        }
    }

    @Override
    public void storeStaticAuthenticationData(X509Certificate authenticationKey,
            byte[] staticAuthData)
            throws UnknownAuthenticationKeyException {
        try {
            AuthKeyParcel authKeyParcel = new AuthKeyParcel();
            authKeyParcel.x509cert = authenticationKey.getEncoded();
            mBinder.storeStaticAuthenticationData(authKeyParcel, staticAuthData);
        } catch (CertificateEncodingException e) {
            throw new RuntimeException("Error encoding authenticationKey", e);
        } catch (android.os.RemoteException e) {
            throw new RuntimeException("Unexpected RemoteException ", e);
        } catch (android.os.ServiceSpecificException e) {
            if (e.errorCode == ICredentialStore.ERROR_AUTHENTICATION_KEY_NOT_FOUND) {
                throw new UnknownAuthenticationKeyException(e.getMessage(), e);
            } else {
                throw new RuntimeException("Unexpected ServiceSpecificException with code "
                        + e.errorCode, e);
            }
        }
    }

    @Override
    public @NonNull int[] getAuthenticationDataUsageCount() {
        try {
            int[] usageCount = mBinder.getAuthenticationDataUsageCount();
            return usageCount;
        } catch (android.os.RemoteException e) {
            throw new RuntimeException("Unexpected RemoteException ", e);
        } catch (android.os.ServiceSpecificException e) {
            throw new RuntimeException("Unexpected ServiceSpecificException with code "
                    + e.errorCode, e);
        }
    }
}
