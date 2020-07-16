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

import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.content.om.OverlayInfo;
import android.content.om.OverlayableInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build.VERSION_CODES;
import android.os.OverlayablePolicy;
import android.os.SystemProperties;
import android.util.Slog;

import java.io.IOException;

/**
 * Handle the creation and deletion of idmap files.
 *
 * The actual work is performed by idmap2d.
 * @see IdmapDaemon
 */
final class IdmapManager {
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

    private final IdmapDaemon mIdmapDaemon;
    private final OverlayableInfoCallback mOverlayableCallback;

    IdmapManager(final IdmapDaemon idmapDaemon, final OverlayableInfoCallback verifyCallback) {
        mOverlayableCallback = verifyCallback;
        mIdmapDaemon = idmapDaemon;
    }

    /**
     * Creates the idmap for the target/overlay combination and returns whether the idmap file was
     * modified.
     */
    boolean createIdmap(@NonNull final PackageInfo targetPackage,
            @NonNull final PackageInfo overlayPackage, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "create idmap for " + targetPackage.packageName + " and "
                    + overlayPackage.packageName);
        }
        final String targetPath = targetPackage.applicationInfo.getBaseCodePath();
        final String overlayPath = overlayPackage.applicationInfo.getBaseCodePath();
        try {
            int policies = calculateFulfilledPolicies(targetPackage, overlayPackage, userId);
            boolean enforce = enforceOverlayable(overlayPackage);
            if (mIdmapDaemon.verifyIdmap(targetPath, overlayPath, policies, enforce, userId)) {
                return false;
            }
            return mIdmapDaemon.createIdmap(targetPath, overlayPath, policies,
                    enforce, userId) != null;
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
            return mIdmapDaemon.removeIdmap(oi.baseCodePath, userId);
        } catch (Exception e) {
            Slog.w(TAG, "failed to remove idmap for " + oi.baseCodePath + ": " + e.getMessage());
            return false;
        }
    }

    boolean idmapExists(@NonNull final OverlayInfo oi) {
        return mIdmapDaemon.idmapExists(oi.baseCodePath, oi.userId);
    }

    boolean idmapExists(@NonNull final PackageInfo overlayPackage, final int userId) {
        return mIdmapDaemon.idmapExists(overlayPackage.applicationInfo.getBaseCodePath(), userId);
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
        int fulfilledPolicies = OverlayablePolicy.PUBLIC;

        // Overlay matches target signature
        if (mOverlayableCallback.signaturesMatching(targetPackage.packageName,
                overlayPackage.packageName, userId)) {
            fulfilledPolicies |= OverlayablePolicy.SIGNATURE;
        }

        // Overlay matches actor signature
        if (matchesActorSignature(targetPackage, overlayPackage, userId)) {
            fulfilledPolicies |= OverlayablePolicy.ACTOR_SIGNATURE;
        }

        // Vendor partition (/vendor)
        if (ai.isVendor()) {
            return fulfilledPolicies | OverlayablePolicy.VENDOR_PARTITION;
        }

        // Product partition (/product)
        if (ai.isProduct()) {
            return fulfilledPolicies | OverlayablePolicy.PRODUCT_PARTITION;
        }

        // Odm partition (/odm)
        if (ai.isOdm()) {
            return fulfilledPolicies | OverlayablePolicy.ODM_PARTITION;
        }

        // Oem partition (/oem)
        if (ai.isOem()) {
            return fulfilledPolicies | OverlayablePolicy.OEM_PARTITION;
        }

        // System_ext partition (/system_ext) is considered as system
        // Check this last since every partition except for data is scanned as system in the PMS.
        if (ai.isSystemApp() || ai.isSystemExt()) {
            return fulfilledPolicies | OverlayablePolicy.SYSTEM_PARTITION;
        }

        return fulfilledPolicies;
    }

    private boolean matchesActorSignature(@NonNull PackageInfo targetPackage,
            @NonNull PackageInfo overlayPackage, int userId) {
        String targetOverlayableName = overlayPackage.targetOverlayableName;
        if (targetOverlayableName != null) {
            try {
                OverlayableInfo overlayableInfo = mOverlayableCallback.getOverlayableForTarget(
                        targetPackage.packageName, targetOverlayableName, userId);
                if (overlayableInfo != null && overlayableInfo.actor != null) {
                    String actorPackageName = OverlayActorEnforcer.getPackageNameForActor(
                            overlayableInfo.actor, mOverlayableCallback.getNamedActors()).first;
                    if (mOverlayableCallback.signaturesMatching(actorPackageName,
                            overlayPackage.packageName, userId)) {
                        return true;
                    }
                }
            } catch (IOException ignored) {
            }
        }

        return false;
    }
}
