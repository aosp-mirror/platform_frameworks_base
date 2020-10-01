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
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.security.IFileIntegrityService;
import android.util.Slog;

import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            return Build.VERSION.FIRST_SDK_INT >= Build.VERSION_CODES.R
                    || SystemProperties.getInt("ro.apk_verity.mode", 0) == 2;
        }

        @Override
        public boolean isAppSourceCertificateTrusted(@Nullable byte[] certificateBytes,
                @NonNull String packageName) {
            checkCallerPermission(packageName);

            try {
                if (!isApkVeritySupported()) {
                    return false;
                }
                if (certificateBytes == null) {
                    Slog.w(TAG, "Received a null certificate");
                    return false;
                }
                return mTrustedCertificates.contains(toCertificate(certificateBytes));
            } catch (CertificateException e) {
                Slog.e(TAG, "Failed to convert the certificate: " + e);
                return false;
            }
        }

        private void checkCallerPermission(String packageName) {
            final int callingUid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(callingUid);
            final PackageManagerInternal packageManager =
                    LocalServices.getService(PackageManagerInternal.class);
            final int packageUid = packageManager.getPackageUid(
                    packageName, 0 /*flag*/, callingUserId);
            if (callingUid != packageUid) {
                throw new SecurityException(
                        "Calling uid " + callingUid + " does not own package " + packageName);
            }

            if (getContext().checkCallingPermission(android.Manifest.permission.INSTALL_PACKAGES)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }

            final AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
            final int mode = appOpsManager.checkOpNoThrow(
                    AppOpsManager.OP_REQUEST_INSTALL_PACKAGES, callingUid, packageName);
            if (mode != AppOpsManager.MODE_ALLOWED) {
                throw new SecurityException(
                        "Caller should have INSTALL_PACKAGES or REQUEST_INSTALL_PACKAGES");
            }
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
        // NB: Directories need to be synced with system/security/fsverity_init/fsverity_init.cpp.
        final String relativeDir = "etc/security/fsverity";
        loadCertificatesFromDirectory(Environment.getRootDirectory().toPath()
                .resolve(relativeDir));
        loadCertificatesFromDirectory(Environment.getProductDirectory().toPath()
                .resolve(relativeDir));
    }

    private void loadCertificatesFromDirectory(Path path) {
        try {
            File[] files = path.toFile().listFiles();
            if (files == null) {
                return;
            }

            for (File cert : files) {
                byte[] certificateBytes = Files.readAllBytes(cert.toPath());
                if (certificateBytes == null) {
                    Slog.w(TAG, "The certificate file is empty, ignoring " + cert);
                    continue;
                }
                collectCertificate(certificateBytes);
            }
        } catch (IOException e) {
            Slog.wtf(TAG, "Failed to load fs-verity certificate from " + path, e);
        }
    }

    /**
     * Tries to convert {@code bytes} into an X.509 certificate and store in memory.
     * Errors need to be surpressed in order fo the next certificates to still be collected.
     */
    private void collectCertificate(@NonNull byte[] bytes) {
        try {
            mTrustedCertificates.add(toCertificate(bytes));
        } catch (CertificateException e) {
            Slog.e(TAG, "Invalid certificate, ignored: " + e);
        }
    }

    /**
     * Converts byte array into one X.509 certificate. If multiple certificate is defined, ignore
     * the rest. The rational is to make it harder to smuggle.
     */
    @NonNull
    private static X509Certificate toCertificate(@NonNull byte[] bytes)
            throws CertificateException {
        Certificate certificate = sCertFactory.generateCertificate(new ByteArrayInputStream(bytes));
        if (!(certificate instanceof X509Certificate)) {
            throw new CertificateException("Expected to contain an X.509 certificate");
        }
        return (X509Certificate) certificate;
    }
}
