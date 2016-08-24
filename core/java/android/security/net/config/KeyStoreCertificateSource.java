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

package android.security.net.config;

import android.util.ArraySet;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;

import com.android.org.conscrypt.TrustedCertificateIndex;

/**
 * {@link CertificateSource} which provides certificates from trusted certificate entries of a
 * {@link KeyStore}.
 */
class KeyStoreCertificateSource implements CertificateSource {
    private final Object mLock = new Object();
    private final KeyStore mKeyStore;
    private TrustedCertificateIndex mIndex;
    private Set<X509Certificate> mCertificates;

    public KeyStoreCertificateSource(KeyStore ks) {
        mKeyStore = ks;
    }

    @Override
    public Set<X509Certificate> getCertificates() {
        ensureInitialized();
        return mCertificates;
    }

    private void ensureInitialized() {
        synchronized (mLock) {
            if (mCertificates != null) {
                return;
            }

            try {
                TrustedCertificateIndex localIndex = new TrustedCertificateIndex();
                Set<X509Certificate> certificates = new ArraySet<>(mKeyStore.size());
                for (Enumeration<String> en = mKeyStore.aliases(); en.hasMoreElements();) {
                    String alias = en.nextElement();
                    X509Certificate cert = (X509Certificate) mKeyStore.getCertificate(alias);
                    if (cert != null) {
                        certificates.add(cert);
                        localIndex.index(cert);
                    }
                }
                mIndex = localIndex;
                mCertificates = certificates;
            } catch (KeyStoreException e) {
                throw new RuntimeException("Failed to load certificates from KeyStore", e);
            }
        }
    }

    @Override
    public X509Certificate findBySubjectAndPublicKey(X509Certificate cert) {
        ensureInitialized();
        java.security.cert.TrustAnchor anchor = mIndex.findBySubjectAndPublicKey(cert);
        if (anchor == null) {
            return null;
        }
        return anchor.getTrustedCert();
    }

    @Override
    public X509Certificate findByIssuerAndSignature(X509Certificate cert) {
        ensureInitialized();
        java.security.cert.TrustAnchor anchor = mIndex.findByIssuerAndSignature(cert);
        if (anchor == null) {
            return null;
        }
        return anchor.getTrustedCert();
    }

    @Override
    public Set<X509Certificate> findAllByIssuerAndSignature(X509Certificate cert) {
        ensureInitialized();
        Set<java.security.cert.TrustAnchor> anchors = mIndex.findAllByIssuerAndSignature(cert);
        if (anchors.isEmpty()) {
            return Collections.<X509Certificate>emptySet();
        }
        Set<X509Certificate> certs = new ArraySet<X509Certificate>(anchors.size());
        for (java.security.cert.TrustAnchor anchor : anchors) {
            certs.add(anchor.getTrustedCert());
        }
        return certs;
    }

    @Override
    public void handleTrustStorageUpdate() {
        // Nothing to do.
    }
}
