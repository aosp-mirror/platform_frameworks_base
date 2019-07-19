/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.om;

import static android.content.Context.IDMAP_SERVICE;
import static android.text.format.DateUtils.SECOND_IN_MILLIS;

import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build.VERSION_CODES;
import android.os.IBinder;
import android.os.IIdmap2;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.server.om.OverlayManagerServiceImpl.PackageManagerHelper;
import com.android.server.pm.Installer;

import java.io.File;

/**
 * Handle the creation and deletion of idmap files.
 *
 * The actual work is performed by the idmap binary, launched through Installer
 * and installd (or idmap2).
 *
 * Note: this class is subclassed in the OMS unit tests, and hence not marked as final.
 */
class IdmapManager {
    private static final boolean FEATURE_FLAG_IDMAP2 = true;

    private final Installer mInstaller;
    private final PackageManagerHelper mPackageManager;
    private IIdmap2 mIdmap2Service;

    private static final boolean VENDOR_IS_Q_OR_LATER;
    static {
        final String value = SystemProperties.get("ro.vndk.version", "29");
        boolean isQOrLater;
        try {
            isQOrLater = Integer.parseInt(value) >= 29;
        } catch (NumberFormatException e) {
            // The version is not a number, therefore it is a development codename.
            isQOrLater = true;
        }

        VENDOR_IS_Q_OR_LATER = isQOrLater;
    }

    IdmapManager(final Installer installer, final PackageManagerHelper packageManager) {
        mInstaller = installer;
        mPackageManager = packageManager;
        if (FEATURE_FLAG_IDMAP2) {
            connectToIdmap2d();
        }
    }

    boolean createIdmap(@NonNull final PackageInfo targetPackage,
            @NonNull final PackageInfo overlayPackage, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "create idmap for " + targetPackage.packageName + " and "
                    + overlayPackage.packageName);
        }
        final int sharedGid = UserHandle.getSharedAppGid(targetPackage.applicationInfo.uid);
        final String targetPath = targetPackage.applicationInfo.getBaseCodePath();
        final String overlayPath = overlayPackage.applicationInfo.getBaseCodePath();
        try {
            if (FEATURE_FLAG_IDMAP2) {
                int policies = calculateFulfilledPolicies(targetPackage, overlayPackage, userId);
                boolean enforce = enforceOverlayable(overlayPackage);
                if (mIdmap2Service.verifyIdmap(overlayPath, policies, enforce, userId)) {
                    return true;
                }
                return mIdmap2Service.createIdmap(targetPath, overlayPath, policies, enforce,
                    userId) != null;
            } else {
                mInstaller.idmap(targetPath, overlayPath, sharedGid);
                return true;
            }
        } catch (Exception e) {
            Slog.w(TAG, "failed to generate idmap for " + targetPath + " and "
                    + overlayPath + ": " + e.getMessage());
            return false;
        }
    }

    boolean removeIdmap(@NonNull final OverlayInfo oi, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "remove idmap for " + oi.baseCodePath);
        }
        try {
            if (FEATURE_FLAG_IDMAP2) {
                return mIdmap2Service.removeIdmap(oi.baseCodePath, userId);
            } else {
                mInstaller.removeIdmap(oi.baseCodePath);
                return true;
            }
        } catch (Exception e) {
            Slog.w(TAG, "failed to remove idmap for " + oi.baseCodePath + ": " + e.getMessage());
            return false;
        }
    }

    boolean idmapExists(@NonNull final OverlayInfo oi) {
        return new File(getIdmapPath(oi.baseCodePath, oi.userId)).isFile();
    }

    boolean idmapExists(@NonNull final PackageInfo overlayPackage, final int userId) {
        return new File(getIdmapPath(overlayPackage.applicationInfo.getBaseCodePath(), userId))
            .isFile();
    }

    private @NonNull String getIdmapPath(@NonNull final String overlayPackagePath,
            final int userId) {
        if (FEATURE_FLAG_IDMAP2) {
            try {
                return mIdmap2Service.getIdmapPath(overlayPackagePath, userId);
            } catch (Exception e) {
                Slog.w(TAG, "failed to get idmap path for " + overlayPackagePath + ": "
                        + e.getMessage());
                return "";
            }
        } else {
            final StringBuilder sb = new StringBuilder("/data/resource-cache/");
            sb.append(overlayPackagePath.substring(1).replace('/', '@'));
            sb.append("@idmap");
            return sb.toString();
        }
    }

    private void connectToIdmap2d() {
        IBinder binder = ServiceManager.getService(IDMAP_SERVICE);
        if (binder != null) {
            try {
                binder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        Slog.w(TAG, "service '" + IDMAP_SERVICE + "' died; reconnecting...");
                        connectToIdmap2d();
                    }

                }, 0);
            } catch (RemoteException e) {
                binder = null;
            }
        }
        if (binder != null) {
            mIdmap2Service = IIdmap2.Stub.asInterface(binder);
            if (DEBUG) {
                Slog.d(TAG, "service '" + IDMAP_SERVICE + "' connected");
            }
        } else {
            Slog.w(TAG, "service '" + IDMAP_SERVICE + "' not found; trying again...");
            BackgroundThread.getHandler().postDelayed(() -> {
                connectToIdmap2d();
            }, SECOND_IN_MILLIS);
        }
    }

    /**
     * Checks if overlayable and policies should be enforced on the specified overlay for backwards
     * compatibility with pre-Q overlays.
     */
    private boolean enforceOverlayable(@NonNull final PackageInfo overlayPackage) {
        final ApplicationInfo ai = overlayPackage.applicationInfo;
        if (ai.targetSdkVersion >= VERSION_CODES.Q) {
            // Always enforce policies for overlays targeting Q+.
            return true;
        }

        if (ai.isVendor()) {
            // If the overlay is on a pre-Q vendor partition, do not enforce overlayable
            // restrictions on this overlay because the pre-Q platform has no understanding of
            // overlayable.
            return VENDOR_IS_Q_OR_LATER;
        }

        // Do not enforce overlayable restrictions on pre-Q overlays that are signed with the
        // platform signature or that are preinstalled.
        return !(ai.isSystemApp() || ai.isSignedWithPlatformKey());
    }

    /**
     * Retrieves a bitmask for idmap2 that represents the policies the overlay fulfills.
     */
    private int calculateFulfilledPolicies(@NonNull final PackageInfo targetPackage,
            @NonNull final PackageInfo overlayPackage, int userId)  {
        final ApplicationInfo ai = overlayPackage.applicationInfo;
        int fulfilledPolicies = IIdmap2.POLICY_PUBLIC;

        // Overlay matches target signature
        if (mPackageManager.signaturesMatching(targetPackage.packageName,
                overlayPackage.packageName, userId)) {
            fulfilledPolicies |= IIdmap2.POLICY_SIGNATURE;
        }

        // Vendor partition (/vendor)
        if (ai.isVendor()) {
            return fulfilledPolicies | IIdmap2.POLICY_VENDOR_PARTITION;
        }

        // Product partition (/product)
        if (ai.isProduct()) {
            return fulfilledPolicies | IIdmap2.POLICY_PRODUCT_PARTITION;
        }

        // Odm partition (/odm)
        if (ai.isOdm()) {
            return fulfilledPolicies | IIdmap2.POLICY_ODM_PARTITION;
        }

        // Oem partition (/oem)
        if (ai.isOem()) {
            return fulfilledPolicies | IIdmap2.POLICY_OEM_PARTITION;
        }

        // Check partitions for which there exists no policy so overlays on these partitions will
        // not fulfill the system policy.
        if (ai.isProductServices()) {
            return fulfilledPolicies;
        }

        // Check this last since every partition except for data is scanned as system in the PMS.
        if (ai.isSystemApp()) {
            return fulfilledPolicies | IIdmap2.POLICY_SYSTEM_PARTITION;
        }

        return fulfilledPolicies;
    }
}
