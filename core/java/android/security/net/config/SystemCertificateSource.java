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
 * {@link CertificateSource} based on the system trusted CA store.
 * @hide
 */
public class SystemCertificateSource implements CertificateSource {
    private static Set<X509Certificate> sSystemCerts = null;
    private static final Object sLock = new Object();

    public SystemCertificateSource() {
    }

    @Override
    public Set<X509Certificate> getCertificates() {
        // TODO: loading all of these is wasteful, we should instead use a keystore style API.
        synchronized (sLock) {
            if (sSystemCerts != null) {
                return sSystemCerts;
            }
            CertificateFactory certFactory;
            try {
                certFactory = CertificateFactory.getInstance("X.509");
            } catch (CertificateException e) {
                throw new RuntimeException("Failed to obtain X.509 CertificateFactory", e);
            }

            final String ANDROID_ROOT = System.getenv("ANDROID_ROOT");
            final File systemCaDir = new File(ANDROID_ROOT + "/etc/security/cacerts");
            final File configDir = Environment.getUserConfigDirectory(UserHandle.myUserId());
            final File userRemovedCaDir = new File(configDir, "cacerts-removed");
            // Sanity check
            if (!systemCaDir.isDirectory()) {
                throw new AssertionError(systemCaDir + " is not a directory");
            }

            Set<X509Certificate> systemCerts = new ArraySet<X509Certificate>();
            for (String caFile : systemCaDir.list()) {
                // Skip any CAs in the user's deleted directory.
                if (new File(userRemovedCaDir, caFile).exists()) {
                    continue;
                }
                InputStream is = null;
                try {
                    is = new BufferedInputStream(
                            new FileInputStream(new File(systemCaDir, caFile)));
                    systemCerts.add((X509Certificate) certFactory.generateCertificate(is));
                } catch (CertificateException | IOException e) {
                    // Don't rethrow to be consistent with conscrypt's cert loading code.
                    continue;
                } finally {
                    IoUtils.closeQuietly(is);
                }
            }
            sSystemCerts = systemCerts;
            return sSystemCerts;
        }
    }

    public void onCertificateStorageChange() {
        synchronized (sLock) {
            sSystemCerts = null;
        }
    }
}
