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
import android.content.Context;
import android.security.GateKeeper;

import java.io.ByteArrayInputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;

class CredstoreWritableIdentityCredential extends WritableIdentityCredential {

    private static final String TAG = "CredstoreWritableIdentityCredential";

    private String mDocType;
    private String mCredentialName;
    private Context mContext;
    private IWritableCredential mBinder;

    CredstoreWritableIdentityCredential(Context context,
            @NonNull String credentialName,
            @NonNull String docType,
            IWritableCredential binder) {
        mContext = context;
        mDocType = docType;
        mCredentialName = credentialName;
        mBinder = binder;
    }

    @NonNull @Override
    public Collection<X509Certificate> getCredentialKeyCertificateChain(@NonNull byte[] challenge) {
        try {
            byte[] certsBlob = mBinder.getCredentialKeyCertificateChain(challenge);
            ByteArrayInputStream bais = new ByteArrayInputStream(certsBlob);

            Collection<? extends Certificate> certs = null;
            try {
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                certs = factory.generateCertificates(bais);
            } catch (CertificateException e) {
                throw new RuntimeException("Error decoding certificates", e);
            }

            ArrayList<X509Certificate> x509Certs = new ArrayList<>();
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

    @NonNull @Override
    public byte[] personalize(@NonNull PersonalizationData personalizationData) {
        return personalize(mBinder, personalizationData);
    }

    // Used by both personalize() and CredstoreIdentityCredential.update().
    //
    @NonNull
    static byte[] personalize(IWritableCredential binder,
            @NonNull PersonalizationData personalizationData) {
        Collection<AccessControlProfile> accessControlProfiles =
                personalizationData.getAccessControlProfiles();

        AccessControlProfileParcel[] acpParcels =
                new AccessControlProfileParcel[accessControlProfiles.size()];
        boolean usingUserAuthentication = false;
        int n = 0;
        for (AccessControlProfile profile : accessControlProfiles) {
            acpParcels[n] = new AccessControlProfileParcel();
            acpParcels[n].id = profile.getAccessControlProfileId().getId();
            X509Certificate cert = profile.getReaderCertificate();
            if (cert != null) {
                try {
                    acpParcels[n].readerCertificate = cert.getEncoded();
                } catch (CertificateException e) {
                    throw new RuntimeException("Error encoding reader certificate", e);
                }
            } else {
                acpParcels[n].readerCertificate = new byte[0];
            }
            acpParcels[n].userAuthenticationRequired = profile.isUserAuthenticationRequired();
            acpParcels[n].userAuthenticationTimeoutMillis = profile.getUserAuthenticationTimeout();
            if (profile.isUserAuthenticationRequired()) {
                usingUserAuthentication = true;
            }
            n++;
        }

        Collection<String> namespaces = personalizationData.getNamespaces();

        EntryNamespaceParcel[] ensParcels  = new EntryNamespaceParcel[namespaces.size()];
        n = 0;
        for (String namespaceName : namespaces) {
            PersonalizationData.NamespaceData nsd =
                    personalizationData.getNamespaceData(namespaceName);

            ensParcels[n] = new EntryNamespaceParcel();
            ensParcels[n].namespaceName = namespaceName;

            Collection<String> entryNames = nsd.getEntryNames();
            EntryParcel[] eParcels = new EntryParcel[entryNames.size()];
            int m = 0;
            for (String entryName : entryNames) {
                eParcels[m] = new EntryParcel();
                eParcels[m].name = entryName;
                eParcels[m].value = nsd.getEntryValue(entryName);
                Collection<AccessControlProfileId> acpIds =
                        nsd.getAccessControlProfileIds(entryName);
                eParcels[m].accessControlProfileIds = new int[acpIds.size()];
                int o = 0;
                for (AccessControlProfileId acpId : acpIds) {
                    eParcels[m].accessControlProfileIds[o++] = acpId.getId();
                }
                m++;
            }
            ensParcels[n].entries = eParcels;
            n++;
        }

        // Note: The value 0 is used to convey that no user-authentication is needed for this
        // credential. This is to allow creating credentials w/o user authentication on devices
        // where Secure lock screen is not enabled.
        long secureUserId = 0;
        if (usingUserAuthentication) {
            secureUserId = getRootSid();
        }
        try {
            byte[] personalizationReceipt = binder.personalize(acpParcels, ensParcels,
                    secureUserId);
            return personalizationReceipt;
        } catch (android.os.RemoteException e) {
            throw new RuntimeException("Unexpected RemoteException ", e);
        } catch (android.os.ServiceSpecificException e) {
            throw new RuntimeException("Unexpected ServiceSpecificException with code "
                    + e.errorCode, e);
        }
    }

    private static long getRootSid() {
        long rootSid = GateKeeper.getSecureUserId();
        if (rootSid == 0) {
            throw new IllegalStateException("Secure lock screen must be enabled"
                    + " to create credentials requiring user authentication");
        }
        return rootSid;
    }

}
