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
import android.util.Log;

import libcore.io.IoUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

/**
 * {@link CertificateSource} based on a directory where certificates are stored as individual files
 * named after a hash of their SubjectName for more efficient lookups.
 * @hide
 */
abstract class DirectoryCertificateSource implements CertificateSource {
    private static final String LOG_TAG = "DirectoryCertificateSrc";
    private final File mDir;
    private final Object mLock = new Object();
    private final CertificateFactory mCertFactory;

    private Set<X509Certificate> mCertificates;

    protected DirectoryCertificateSource(File caDir) {
        mDir = caDir;
        try {
            mCertFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new RuntimeException("Failed to obtain X.509 CertificateFactory", e);
        }
    }

    protected abstract boolean isCertMarkedAsRemoved(String caFile);

    @Override
    public Set<X509Certificate> getCertificates() {
        // TODO: loading all of these is wasteful, we should instead use a keystore style API.
        synchronized (mLock) {
            if (mCertificates != null) {
                return mCertificates;
            }

            Set<X509Certificate> certs = new ArraySet<X509Certificate>();
            if (mDir.isDirectory()) {
                for (String caFile : mDir.list()) {
                    if (isCertMarkedAsRemoved(caFile)) {
                        continue;
                    }
                    X509Certificate cert = readCertificate(caFile);
                    if (cert != null) {
                        certs.add(cert);
                    }
                }
            }
            mCertificates = certs;
            return mCertificates;
        }
    }

    @Override
    public X509Certificate findBySubjectAndPublicKey(final X509Certificate cert) {
        return findCert(cert.getSubjectX500Principal(), new CertSelector() {
            @Override
            public boolean match(X509Certificate ca) {
                return ca.getPublicKey().equals(cert.getPublicKey());
            }
        });
    }

    @Override
    public X509Certificate findByIssuerAndSignature(final X509Certificate cert) {
        return findCert(cert.getIssuerX500Principal(), new CertSelector() {
            @Override
            public boolean match(X509Certificate ca) {
                try {
                    cert.verify(ca.getPublicKey());
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });
    }

    @Override
    public Set<X509Certificate> findAllByIssuerAndSignature(final X509Certificate cert) {
        return findCerts(cert.getIssuerX500Principal(), new CertSelector() {
            @Override
            public boolean match(X509Certificate ca) {
                try {
                    cert.verify(ca.getPublicKey());
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        });
    }

    @Override
    public void handleTrustStorageUpdate() {
        synchronized (mLock) {
            mCertificates = null;
        }
    }

    private static interface CertSelector {
        boolean match(X509Certificate cert);
    }

    private Set<X509Certificate> findCerts(X500Principal subj, CertSelector selector) {
        String hash = getHash(subj);
        Set<X509Certificate> certs = null;
        for (int index = 0; index >= 0; index++) {
            String fileName = hash + "." + index;
            if (!new File(mDir, fileName).exists()) {
                break;
            }
            if (isCertMarkedAsRemoved(fileName)) {
                continue;
            }
            X509Certificate cert = readCertificate(fileName);
            if (cert == null) {
                continue;
            }
            if (!subj.equals(cert.getSubjectX500Principal())) {
                continue;
            }
            if (selector.match(cert)) {
                if (certs == null) {
                    certs = new ArraySet<X509Certificate>();
                }
                certs.add(cert);
            }
        }
        return certs != null ? certs : Collections.<X509Certificate>emptySet();
    }

    private X509Certificate findCert(X500Principal subj, CertSelector selector) {
        String hash = getHash(subj);
        for (int index = 0; index >= 0; index++) {
            String fileName = hash + "." + index;
            if (!new File(mDir, fileName).exists()) {
                break;
            }
            if (isCertMarkedAsRemoved(fileName)) {
                continue;
            }
            X509Certificate cert = readCertificate(fileName);
            if (cert == null) {
                continue;
            }
            if (!subj.equals(cert.getSubjectX500Principal())) {
                continue;
            }
            if (selector.match(cert)) {
                return cert;
            }
        }
        return null;
    }

    private String getHash(X500Principal name) {
        int hash = hashName(name);
        return intToHexString(hash, 8);
    }

    private static final char[] DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

    private static String intToHexString(int i, int minWidth) {
        int bufLen = 8;  // Max number of hex digits in an int
        char[] buf = new char[bufLen];
        int cursor = bufLen;

        do {
            buf[--cursor] = DIGITS[i & 0xf];
        } while ((i >>>= 4) != 0 || (bufLen - cursor < minWidth));

        return new String(buf, cursor, bufLen - cursor);
    }

    // This code matches the code in X509_NAME_hash_old() in Conscrypt, which in turn matches
    // the names of certificate files.  It must be kept in sync.
    private static int hashName(X500Principal principal) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(principal.getEncoded());
            int offset = 0;
            return (((digest[offset++] & 0xff) << 0) | ((digest[offset++] & 0xff) << 8)
                    | ((digest[offset++] & 0xff) << 16) | ((digest[offset] & 0xff) << 24));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private X509Certificate readCertificate(String file) {
        InputStream is = null;
        try {
            is = new BufferedInputStream(new FileInputStream(new File(mDir, file)));
            return (X509Certificate) mCertFactory.generateCertificate(is);
        } catch (CertificateException | IOException e) {
            Log.e(LOG_TAG, "Failed to read certificate from " + file, e);
            return null;
        } finally {
            IoUtils.closeQuietly(is);
        }
    }
}
