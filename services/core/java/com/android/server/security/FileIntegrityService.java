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

import android.annotation.EnforcePermission;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PermissionEnforcer;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.storage.StorageManagerInternal;
import android.security.IFileIntegrityService;

import com.android.internal.security.VerityUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.IOException;
import java.util.Objects;

/**
 * A {@link SystemService} that provides file integrity related operations.
 * @hide
 */
public class FileIntegrityService extends SystemService {
    private static final String TAG = "FileIntegrityService";

    /** Gets the instance of the service */
    public static FileIntegrityService getService() {
        return LocalServices.getService(FileIntegrityService.class);
    }

    private final class BinderService extends IFileIntegrityService.Stub {
        BinderService(Context context) {
            super(PermissionEnforcer.fromContext(context));
        }

        @Override
        public boolean isApkVeritySupported() {
            return VerityUtils.isFsVeritySupported();
        }

        private void checkCallerPackageName(String packageName) {
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
        }

        private void checkCallerPermission(String packageName) {
            checkCallerPackageName(packageName);
            if (getContext().checkCallingPermission(android.Manifest.permission.INSTALL_PACKAGES)
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }

            final AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
            final int mode = appOpsManager.checkOpNoThrow(
                    AppOpsManager.OP_REQUEST_INSTALL_PACKAGES, Binder.getCallingUid(), packageName);
            if (mode != AppOpsManager.MODE_ALLOWED) {
                throw new SecurityException(
                        "Caller should have INSTALL_PACKAGES or REQUEST_INSTALL_PACKAGES");
            }
        }

        @Override
        public android.os.IInstalld.IFsveritySetupAuthToken createAuthToken(
                ParcelFileDescriptor authFd) throws RemoteException {
            Objects.requireNonNull(authFd);
            try {
                var authToken = getStorageManagerInternal().createFsveritySetupAuthToken(authFd,
                        Binder.getCallingUid());
                // fs-verity setup requires no writable fd to the file. Release the dup now that
                // it's passed.
                authFd.close();
                return authToken;
            } catch (IOException e) {
                throw new RemoteException(e);
            }
        }

        @Override
        @EnforcePermission(android.Manifest.permission.SETUP_FSVERITY)
        public int setupFsverity(android.os.IInstalld.IFsveritySetupAuthToken authToken,
                String filePath, String packageName) throws RemoteException {
            setupFsverity_enforcePermission();
            Objects.requireNonNull(authToken);
            Objects.requireNonNull(filePath);
            Objects.requireNonNull(packageName);
            checkCallerPackageName(packageName);

            try {
                return getStorageManagerInternal().enableFsverity(authToken, filePath, packageName);
            } catch (IOException e) {
                throw new RemoteException(e);
            }
        }
    }
    private final IBinder mService;

    public FileIntegrityService(final Context context) {
        super(context);
        mService = new BinderService(context);
        LocalServices.addService(FileIntegrityService.class, this);
    }

    /**
     * Returns StorageManagerInternal as a proxy to fs-verity related calls. This is to plumb
     * the call through the canonical Installer instance in StorageManagerService, since the
     * Installer instance isn't directly accessible.
     */
    private StorageManagerInternal getStorageManagerInternal() {
        return LocalServices.getService(StorageManagerInternal.class);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.FILE_INTEGRITY_SERVICE, mService);
    }
}
