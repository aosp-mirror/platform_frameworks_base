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

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

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
     * Returns true if APK Verity is supported on the device. When supported, an APK can be
     * installed with a fs-verity signature (if verified with trusted App Source Certificate) for
     * continuous on-access verification.
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
     * Returns whether the given certificate can be used to prove app's install source. Always
     * return false if the feature is not supported.
     *
     * <p>A store can use this API to decide if a signature file needs to be downloaded. Also, if a
     * store has shipped different certificates before (e.g. with stronger and weaker key), it can
     * also use this API to download the best signature on the running device.
     *
     * @return whether the certificate is trusted in the system
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.INSTALL_PACKAGES,
            android.Manifest.permission.REQUEST_INSTALL_PACKAGES
    })
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
