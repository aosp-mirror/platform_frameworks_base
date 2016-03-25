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

/** @hide */
public final class CertificatesEntryRef {
    private final CertificateSource mSource;
    private final boolean mOverridesPins;

    public CertificatesEntryRef(CertificateSource source, boolean overridesPins) {
        mSource = source;
        mOverridesPins = overridesPins;
    }

    boolean overridesPins() {
        return mOverridesPins;
    }

    public Set<TrustAnchor> getTrustAnchors() {
        // TODO: cache this [but handle mutable sources]
        Set<TrustAnchor> anchors = new ArraySet<TrustAnchor>();
        for (X509Certificate cert : mSource.getCertificates()) {
            anchors.add(new TrustAnchor(cert, mOverridesPins));
        }
        return anchors;
    }

    public TrustAnchor findBySubjectAndPublicKey(X509Certificate cert) {
        X509Certificate foundCert = mSource.findBySubjectAndPublicKey(cert);
        if (foundCert == null) {
            return null;
        }

        return new TrustAnchor(foundCert, mOverridesPins);
    }

    public TrustAnchor findByIssuerAndSignature(X509Certificate cert) {
        X509Certificate foundCert = mSource.findByIssuerAndSignature(cert);
        if (foundCert == null) {
            return null;
        }

        return new TrustAnchor(foundCert, mOverridesPins);
    }

    public Set<X509Certificate> findAllCertificatesByIssuerAndSignature(X509Certificate cert) {
        return mSource.findAllByIssuerAndSignature(cert);
    }

    public void handleTrustStorageUpdate() {
        mSource.handleTrustStorageUpdate();
    }
}
