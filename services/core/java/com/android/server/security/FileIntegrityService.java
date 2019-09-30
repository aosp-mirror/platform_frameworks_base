/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.security;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemProperties;
import android.security.Credentials;
import android.security.IFileIntegrityService;
import android.security.KeyStore;
import android.util.Slog;

import com.android.server.SystemService;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;

/**
 * A {@link SystemService} that provides file integrity related operations.
 * @hide
 */
public class FileIntegrityService extends SystemService {
    private static final String TAG = "FileIntegrityService";

    private static CertificateFactory sCertFactory;

    private Collection<X509Certificate> mTrustedCertificates = new ArrayList<X509Certificate>();

    private final IBinder mService = new IFileIntegrityService.Stub() {
        @Override
        public boolean isApkVeritySupported() {
            return SystemProperties.getInt("ro.apk_verity.mode", 0) == 2;
        }

        @Override
        public boolean isAppSourceCertificateTrusted(byte[] certificateBytes) {
            enforceAnyCallingPermissions(
                    android.Manifest.permission.REQUEST_INSTALL_PACKAGES,
                    android.Manifest.permission.INSTALL_PACKAGES);
            try {
                if (!isApkVeritySupported()) {
                    return false;
                }

                return mTrustedCertificates.contains(toCertificate(certificateBytes));
            } catch (CertificateException e) {
                Slog.e(TAG, "Failed to convert the certificate: " + e);
                return false;
            }
        }

        private void enforceAnyCallingPermissions(String ...permissions) {
            for (String permission : permissions) {
                if (getContext().checkCallingPermission(permission)
                        == PackageManager.PERMISSION_GRANTED) {
                    return;
                }
            }
            throw new SecurityException("Insufficient permission");
        }
    };

    public FileIntegrityService(final Context context) {
        super(context);
        try {
            sCertFactory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            Slog.wtf(TAG, "Cannot get an instance of X.509 certificate factory");
        }
    }

    @Override
    public void onStart() {
        loadAllCertificates();
        publishBinderService(Context.FILE_INTEGRITY_SERVICE, mService);
    }

    private void loadAllCertificates() {
        // A better alternative to load certificates would be to read from .fs-verity kernel
        // keyring, which fsverity_init loads to during earlier boot time from the same sources
        // below. But since the read operation from keyring is not provided in kernel, we need to
        // duplicate the same loading logic here.

        // Load certificates trusted by the device manufacturer.
        loadCertificatesFromDirectory("/product/etc/security/fsverity");

        // Load certificates trusted by the device owner.
        loadCertificatesFromKeystore(KeyStore.getInstance());
    }

    private void loadCertificatesFromDirectory(String path) {
        try {
            File[] files = new File(path).listFiles();
            if (files == null) {
                return;
            }

            for (File cert : files) {
                collectCertificate(Files.readAllBytes(cert.toPath()));
            }
        } catch (IOException e) {
            Slog.wtf(TAG, "Failed to load fs-verity certificate from " + path, e);
        }
    }

    private void loadCertificatesFromKeystore(KeyStore keystore) {
        for (final String alias : keystore.list(Credentials.APP_SOURCE_CERTIFICATE,
                    Process.FSVERITY_CERT_UID)) {
            byte[] certificateBytes = keystore.get(Credentials.APP_SOURCE_CERTIFICATE + alias,
                    Process.FSVERITY_CERT_UID, false /* suppressKeyNotFoundWarning */);
            if (certificateBytes == null) {
                Slog.w(TAG, "The retrieved fs-verity certificate is null, ignored " + alias);
                continue;
            }
            collectCertificate(certificateBytes);
        }
    }

    /**
     * Tries to convert {@code bytes} into an X.509 certificate and store in memory.
     * Errors need to be surpressed in order fo the next certificates to still be collected.
     */
    private void collectCertificate(@Nullable byte[] bytes) {
        try {
            mTrustedCertificates.add(toCertificate(bytes));
        } catch (CertificateException | AssertionError e) {
            Slog.e(TAG, "Invalid certificate, ignored: " + e);
        }
    }

    /**
     * Converts byte array into one X.509 certificate. If multiple certificate is defined, ignore
     * the rest. The rational is to make it harder to smuggle.
     */
    @NonNull
    private static X509Certificate toCertificate(@Nullable byte[] bytes)
            throws CertificateException {
        Certificate certificate = sCertFactory.generateCertificate(new ByteArrayInputStream(bytes));
        if (!(certificate instanceof X509Certificate)) {
            throw new CertificateException("Expected to contain an X.509 certificate");
        }
        return (X509Certificate) certificate;
    }
}
