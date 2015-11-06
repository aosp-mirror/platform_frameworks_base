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

import android.os.Environment;
import android.os.UserHandle;
import android.util.ArraySet;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Set;
import libcore.io.IoUtils;

/**
 * {@link CertificateSource} based on the user-installed trusted CA store.
 * @hide
 */
public class UserCertificateSource implements CertificateSource {
    private static final UserCertificateSource INSTANCE = new UserCertificateSource();
    private Set<X509Certificate> mUserCerts = null;
    private final Object mLock = new Object();

    private UserCertificateSource() {
    }

    public static UserCertificateSource getInstance() {
        return INSTANCE;
    }

    @Override
    public Set<X509Certificate> getCertificates() {
        // TODO: loading all of these is wasteful, we should instead use a keystore style API.
        synchronized (mLock) {
            if (mUserCerts != null) {
                return mUserCerts;
            }
            CertificateFactory certFactory;
            try {
                certFactory = CertificateFactory.getInstance("X.509");
            } catch (CertificateException e) {
                throw new RuntimeException("Failed to obtain X.509 CertificateFactory", e);
            }
            final File configDir = Environment.getUserConfigDirectory(UserHandle.myUserId());
            final File userCaDir = new File(configDir, "cacerts-added");
            Set<X509Certificate> userCerts = new ArraySet<X509Certificate>();
            // If the user hasn't added any certificates the directory may not exist.
            if (userCaDir.isDirectory()) {
                for (String caFile : userCaDir.list()) {
                    InputStream is = null;
                    try {
                        is = new BufferedInputStream(
                                new FileInputStream(new File(userCaDir, caFile)));
                        userCerts.add((X509Certificate) certFactory.generateCertificate(is));
                    } catch (CertificateException | IOException e) {
                        // Don't rethrow to be consistent with conscrypt's cert loading code.
                        continue;
                    } finally {
                        IoUtils.closeQuietly(is);
                    }
                }
            }
            mUserCerts = userCerts;
            return mUserCerts;
        }
    }

    public void onCertificateStorageChange() {
        synchronized (mLock) {
            mUserCerts = null;
        }
    }
}
