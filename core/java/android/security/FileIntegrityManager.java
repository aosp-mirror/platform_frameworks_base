/*
 * Copyright 2019 The Android Open Source Project
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

package android.security;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.IInstalld.IFsveritySetupAuthToken;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.ErrnoException;

import com.android.internal.security.VerityUtils;

import java.io.File;
import java.io.IOException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;

/**
 * This class provides access to file integrity related operations.
 */
@SystemService(Context.FILE_INTEGRITY_SERVICE)
public final class FileIntegrityManager {
    @NonNull private final IFileIntegrityService mService;
    @NonNull private final Context mContext;

    /** @hide */
    public FileIntegrityManager(@NonNull Context context, @NonNull IFileIntegrityService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns whether fs-verity is supported on the device. fs-verity provides on-access
     * verification, although the app APIs are only made available to apps in a later SDK version.
     * Only when this method returns true, the other fs-verity APIs in the same class can succeed.
     *
     * <p>The app may not need this method and just call the other APIs normally and handle any
     * failure. If some app feature really depends on fs-verity (e.g. protecting integrity of a
     * large file download), an early check of support status may avoid any cost if it is to fail
     * late.
     *
     * <p>Note: for historical reasons this is named {@code isApkVeritySupported()} instead of
     * {@code isFsVeritySupported()}. It has also been available since API level 30, predating the
     * other fs-verity APIs.
     */
    public boolean isApkVeritySupported() {
        try {
            // Go through the service just to avoid exposing the vendor controlled system property
            // to all apps.
            return mService.isApkVeritySupported();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables fs-verity to the owned file under the calling app's private directory. It always uses
     * the common configuration, i.e. SHA-256 digest algorithm, 4K block size, and without salt.
     *
     * <p>For enabling fs-verity to succeed, the device must support fs-verity, the file must be
     * writable by the app and not already have fs-verity enabled, and the file must not currently
     * be open for writing by any process. To check whether the device supports fs-verity, use
     * {@link #isApkVeritySupported()}.
     *
     * <p>It takes O(file size) time to build the underlying data structure for continuous
     * verification. The operation is atomic, i.e. it's either enabled or not, even in case of
     * power failure during or after the call.
     *
     * <p>Note for the API users: When the file's authenticity is crucial, the app typical needs to
     * perform a signature check by itself before using the file. The signature is often delivered
     * as a separate file and stored next to the targeting file in the filesystem. The public key of
     * the signer (normally the same app developer) can be put in the APK, and the app can use the
     * public key to verify the signature to the file's actual fs-verity digest (from {@link
     * #getFsVerityDigest(File)}) before using the file. The exact format is not prescribed by the
     * framework. App developers may choose to use common practices like JCA for the signing and
     * verification, or their own preferred approach.
     *
     * @param file The file to enable fs-verity. It must represent an absolute path.
     * @throws IllegalArgumentException If the provided file is not an absolute path.
     * @throws IOException If the operation failed.
     *
     * @see <a href="https://www.kernel.org/doc/html/next/filesystems/fsverity.html">Kernel doc</a>
     * @hide
     */
    @FlaggedApi(Flags.FLAG_FSVERITY_API)
    @SuppressLint("StreamFiles")
    @SystemApi
    public void setupFsVerity(@NonNull File file) throws IOException {
        if (!file.isAbsolute()) {
            // fs-verity is to be enabled by installd, which enforces the validation to the
            // (untrusted) file path passed from here. To make this less error prone, installd
            // accepts only absolute path. When a relative path is provided, we fail with an
            // explicit exception to help developers understand the requirement to use an absolute
            // path.
            throw new IllegalArgumentException("Expect an absolute path");
        }
        IFsveritySetupAuthToken authToken;
        // fs-verity setup requires no writable fd to the file. Make sure it's closed before
        // continue.
        try (var authFd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)) {
            authToken = mService.createAuthToken(authFd);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        try {
            int errno = mService.setupFsverity(authToken, file.getPath(),
                    mContext.getPackageName());
            if (errno != 0) {
                new ErrnoException("setupFsVerity", errno).rethrowAsIOException();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the fs-verity digest for the owned file under the calling app's private directory, or
     * null when the file does not have fs-verity enabled (including when fs-verity is not supported
     * on older devices).
     *
     * @param file The file to measure the fs-verity digest.
     * @return The fs-verity digest in byte[], null if none.
     * @see <a href="https://www.kernel.org/doc/html/next/filesystems/fsverity.html">Kernel doc</a>
     * @hide
     */
    @FlaggedApi(Flags.FLAG_FSVERITY_API)
    @SuppressLint("StreamFiles")
    @SystemApi
    public @Nullable byte[] getFsVerityDigest(@NonNull File file) throws IOException {
        return VerityUtils.getFsverityDigest(file.getPath());
    }

    /**
     * Returns whether the given certificate can be used to prove app's install source. Always
     * return false if the feature is not supported.
     *
     * <p>A store can use this API to decide if a signature file needs to be downloaded. Also, if a
     * store has shipped different certificates before (e.g. with stronger and weaker key), it can
     * also use this API to download the best signature on the running device.
     *
     * @return whether the certificate is trusted in the system
     * @deprecated The feature is no longer supported, and this API now always returns false.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.INSTALL_PACKAGES,
            android.Manifest.permission.REQUEST_INSTALL_PACKAGES
    })
    @Deprecated
    public boolean isAppSourceCertificateTrusted(@NonNull X509Certificate certificate)
            throws CertificateEncodingException {
        try {
            return mService.isAppSourceCertificateTrusted(
                    certificate.getEncoded(), mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
