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

import android.content.Context;
import android.util.ArraySet;
import libcore.io.IoUtils;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Set;

/**
 * {@link CertificateSource} based on certificates contained in an application resource file.
 * @hide
 */
public class ResourceCertificateSource implements CertificateSource {
    private Set<X509Certificate> mCertificates;
    private final int  mResourceId;
    private Context mContext;
    private final Object mLock = new Object();

    public ResourceCertificateSource(int resourceId, Context context) {
        mResourceId = resourceId;
        mContext = context.getApplicationContext();
    }

    @Override
    public Set<X509Certificate> getCertificates() {
        synchronized (mLock) {
            if (mCertificates != null) {
                return mCertificates;
            }
            Set<X509Certificate> certificates = new ArraySet<X509Certificate>();
            Collection<? extends Certificate> certs;
            InputStream in = null;
            try {
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                in = mContext.getResources().openRawResource(mResourceId);
                certs = factory.generateCertificates(in);
            } catch (CertificateException e) {
                throw new RuntimeException("Failed to load trust anchors from id " + mResourceId,
                        e);
            } finally {
                IoUtils.closeQuietly(in);
            }
            for (Certificate cert : certs) {
                    certificates.add((X509Certificate) cert);
            }
            mCertificates = certificates;
            mContext = null;
            return mCertificates;
        }
    }
}
