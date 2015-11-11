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
import java.util.Enumeration;
import java.util.Set;

/**
 * {@link CertificateSource} which provides certificates from trusted certificate entries of a
 * {@link KeyStore}.
 */
class KeyStoreCertificateSource implements CertificateSource {
    private final Object mLock = new Object();
    private final KeyStore mKeyStore;
    private Set<X509Certificate> mCertificates;

    public KeyStoreCertificateSource(KeyStore ks) {
        mKeyStore = ks;
    }

    @Override
    public Set<X509Certificate> getCertificates() {
        synchronized (mLock) {
            if (mCertificates != null) {
                return mCertificates;
            }
            try {
                Set<X509Certificate> certificates = new ArraySet<>(mKeyStore.size());
                for (Enumeration<String> en = mKeyStore.aliases(); en.hasMoreElements();) {
                    String alias = en.nextElement();
                    if (!mKeyStore.isCertificateEntry(alias)) {
                        continue;
                    }
                    X509Certificate cert = (X509Certificate) mKeyStore.getCertificate(alias);
                    if (cert != null) {
                        certificates.add(cert);
                    }
                }
                mCertificates = certificates;
                return mCertificates;
            } catch (KeyStoreException e) {
                throw new RuntimeException("Failed to load certificates from KeyStore", e);
            }
        }
    }
}
