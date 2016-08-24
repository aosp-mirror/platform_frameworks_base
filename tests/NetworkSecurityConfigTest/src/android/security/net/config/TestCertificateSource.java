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
import java.security.cert.X509Certificate;
import java.util.Set;

import com.android.org.conscrypt.TrustedCertificateIndex;

/** @hide */
public class TestCertificateSource implements CertificateSource {

    private final Set<X509Certificate> mCertificates;
    private final TrustedCertificateIndex mIndex = new TrustedCertificateIndex();
    public TestCertificateSource(Set<X509Certificate> certificates) {
        mCertificates = certificates;
        for (X509Certificate cert : certificates) {
            mIndex.index(cert);
        }
    }

    @Override
    public Set<X509Certificate> getCertificates() {
            return mCertificates;
    }

    @Override
    public X509Certificate findBySubjectAndPublicKey(X509Certificate cert) {
        java.security.cert.TrustAnchor anchor = mIndex.findBySubjectAndPublicKey(cert);
        if (anchor == null) {
            return null;
        }
        return anchor.getTrustedCert();
    }

    @Override
    public X509Certificate findByIssuerAndSignature(X509Certificate cert) {
        java.security.cert.TrustAnchor anchor = mIndex.findByIssuerAndSignature(cert);
        if (anchor == null) {
            return null;
        }
        return anchor.getTrustedCert();
    }

    @Override
    public Set<X509Certificate> findAllByIssuerAndSignature(X509Certificate cert) {
        Set<X509Certificate> certs = new ArraySet<X509Certificate>();
        for (java.security.cert.TrustAnchor anchor : mIndex.findAllByIssuerAndSignature(cert)) {
            certs.add(anchor.getTrustedCert());
        }
        return certs;
    }

    @Override
    public void handleTrustStorageUpdate() {
        // Nothing to do.
    }
}
