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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.om.OverlayInfo;
import android.content.om.OverlayableInfo;
import android.os.Build.VERSION_CODES;
import android.os.FabricatedOverlayInfo;
import android.os.FabricatedOverlayInternal;
import android.os.OverlayablePolicy;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Slog;

import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

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

    static final int IDMAP_NOT_EXIST = 0;
    static final int IDMAP_IS_VERIFIED = 1;
    static final int IDMAP_IS_MODIFIED = 1 << 1;

    @IntDef(flag = true, prefix = { "IDMAP_" }, value = {
            IDMAP_NOT_EXIST,
            IDMAP_IS_VERIFIED,
            IDMAP_IS_MODIFIED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface IdmapStatus {}

    private final IdmapDaemon mIdmapDaemon;
    private final PackageManagerHelper mPackageManager;

    /**
     * Package name of the reference package defined in 'overlay-config-signature' tag of
     * SystemConfig or empty String if tag not defined. This package is vetted on scan by
     * PackageManagerService that it's a system package and is used to check if overlay matches
     * its signature in order to fulfill the config_signature policy.
     */
    private final String mConfigSignaturePackage;

    IdmapManager(final IdmapDaemon idmapDaemon, final PackageManagerHelper packageManager) {
        mPackageManager = packageManager;
        mIdmapDaemon = idmapDaemon;
        mConfigSignaturePackage = packageManager.getConfigSignaturePackage();
    }

    /**
     * Creates the idmap for the target/overlay combination and returns whether the idmap file was
     * modified.
     * @return the status of the specific idmap file. It's one of the following.<ul>
     *     <li>{@link #IDMAP_NOT_EXIST} means the idmap file is not existed.</li>
     *     <li>{@link #IDMAP_IS_VERIFIED} means the idmap file is verified by Idmap2d.</li>
     *     <li>{@link #IDMAP_IS_MODIFIED | IDMAP_IS_VERIFIED } means the idmap file is modified and
     *     verified by Idmap2d.</li>
     * </ul>.
     */
    @IdmapStatus int createIdmap(@NonNull final AndroidPackage targetPackage,
            @NonNull PackageState overlayPackageState, @NonNull final AndroidPackage overlayPackage,
            String overlayBasePath, String overlayName, @UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, "create idmap for " + targetPackage.getPackageName() + " and "
                    + overlayPackage.getPackageName());
        }
        final String targetPath = targetPackage.getSplits().get(0).getPath();
        try {
            int policies = calculateFulfilledPolicies(targetPackage, overlayPackageState,
                    overlayPackage, userId);
            boolean enforce = enforceOverlayable(overlayPackageState, overlayPackage);
            if (mIdmapDaemon.verifyIdmap(targetPath, overlayBasePath, overlayName, policies,
                    enforce, userId)) {
                return IDMAP_IS_VERIFIED;
            }
            final boolean idmapCreated = mIdmapDaemon.createIdmap(targetPath, overlayBasePath,
                    overlayName, policies, enforce, userId) != null;
            return (idmapCreated) ? IDMAP_IS_MODIFIED | IDMAP_IS_VERIFIED : IDMAP_NOT_EXIST;
        } catch (Exception e) {
            Slog.w(TAG, "failed to generate idmap for " + targetPath + " and "
                    + overlayBasePath, e);
            return IDMAP_NOT_EXIST;
        }
    }

    boolean removeIdmap(@NonNull final OverlayInfo oi, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "remove idmap for " + oi.baseCodePath);
        }
        try {
            return mIdmapDaemon.removeIdmap(oi.baseCodePath, userId);
        } catch (Exception e) {
            Slog.w(TAG, "failed to remove idmap for " + oi.baseCodePath, e);
            return false;
        }
    }

    boolean idmapExists(@NonNull final OverlayInfo oi) {
        return mIdmapDaemon.idmapExists(oi.baseCodePath, oi.userId);
    }

    /**
     * @return the list of all fabricated overlays
     */
    List<FabricatedOverlayInfo> getFabricatedOverlayInfos() {
        return mIdmapDaemon.getFabricatedOverlayInfos();
    }

    /**
     * Creates a fabricated overlay and persists it to disk.
     * @return the path to the fabricated overlay
     */
    FabricatedOverlayInfo createFabricatedOverlay(@NonNull FabricatedOverlayInternal overlay) {
        return mIdmapDaemon.createFabricatedOverlay(overlay);
    }

    /**
     * Deletes the fabricated overlay file on disk.
     * @return whether the path was deleted
     */
    boolean deleteFabricatedOverlay(@NonNull String path) {
        return mIdmapDaemon.deleteFabricatedOverlay(path);
    }

    /**
     * Gets the idmap data associated with an overlay, in dump format.
     * Only indented for debugging.
     */
    String dumpIdmap(@NonNull String overlayPath) {
        return mIdmapDaemon.dumpIdmap(overlayPath);
    }

    /**
     * Checks if overlayable and policies should be enforced on the specified overlay for backwards
     * compatibility with pre-Q overlays.
     */
    private boolean enforceOverlayable(@NonNull PackageState overlayPackageState,
            @NonNull final AndroidPackage overlayPackage) {
        if (overlayPackage.getTargetSdkVersion() >= VERSION_CODES.Q) {
            // Always enforce policies for overlays targeting Q+.
            return true;
        }

        if (overlayPackageState.isVendor()) {
            // If the overlay is on a pre-Q vendor partition, do not enforce overlayable
            // restrictions on this overlay because the pre-Q platform has no understanding of
            // overlayable.
            return VENDOR_IS_Q_OR_LATER;
        }

        // Do not enforce overlayable restrictions on pre-Q overlays that are signed with the
        // platform signature or that are preinstalled.
        return !(overlayPackageState.isSystem() || overlayPackage.isSignedWithPlatformKey());
    }

    /**
     * Retrieves a bitmask for idmap2 that represents the policies the overlay fulfills.
     */
    private int calculateFulfilledPolicies(@NonNull final AndroidPackage targetPackage,
            @NonNull PackageState overlayPackageState, @NonNull final AndroidPackage overlayPackage,
            @UserIdInt int userId)  {
        int fulfilledPolicies = OverlayablePolicy.PUBLIC;

        // Overlay matches target signature
        if (mPackageManager.signaturesMatching(targetPackage.getPackageName(),
                overlayPackage.getPackageName(), userId)) {
            fulfilledPolicies |= OverlayablePolicy.SIGNATURE;
        }

        // Overlay matches actor signature
        if (matchesActorSignature(targetPackage, overlayPackage, userId)) {
            fulfilledPolicies |= OverlayablePolicy.ACTOR_SIGNATURE;
        }

        // If SystemConfig defines 'overlay-config-signature' package, given that
        // this package is vetted by OverlayManagerService that it's a
        // preinstalled package, check if overlay matches its signature.
        if (!TextUtils.isEmpty(mConfigSignaturePackage)
                && mPackageManager.signaturesMatching(mConfigSignaturePackage,
                                                           overlayPackage.getPackageName(),
                                                           userId)) {
            fulfilledPolicies |= OverlayablePolicy.CONFIG_SIGNATURE;
        }

        // Vendor partition (/vendor)
        if (overlayPackageState.isVendor()) {
            return fulfilledPolicies | OverlayablePolicy.VENDOR_PARTITION;
        }

        // Product partition (/product)
        if (overlayPackageState.isProduct()) {
            return fulfilledPolicies | OverlayablePolicy.PRODUCT_PARTITION;
        }

        // Odm partition (/odm)
        if (overlayPackageState.isOdm()) {
            return fulfilledPolicies | OverlayablePolicy.ODM_PARTITION;
        }

        // Oem partition (/oem)
        if (overlayPackageState.isOem()) {
            return fulfilledPolicies | OverlayablePolicy.OEM_PARTITION;
        }

        // System_ext partition (/system_ext) is considered as system
        // Check this last since every partition except for data is scanned as system in the PMS.
        if (overlayPackageState.isSystem() || overlayPackageState.isSystemExt()) {
            return fulfilledPolicies | OverlayablePolicy.SYSTEM_PARTITION;
        }

        return fulfilledPolicies;
    }

    private boolean matchesActorSignature(@NonNull AndroidPackage targetPackage,
            @NonNull AndroidPackage overlayPackage, int userId) {
        String targetOverlayableName = overlayPackage.getOverlayTargetOverlayableName();
        if (targetOverlayableName != null && !mPackageManager.getNamedActors().isEmpty()) {
            try {
                OverlayableInfo overlayableInfo = mPackageManager.getOverlayableForTarget(
                        targetPackage.getPackageName(), targetOverlayableName, userId);
                if (overlayableInfo != null && overlayableInfo.actor != null) {
                    String actorPackageName = OverlayActorEnforcer.getPackageNameForActor(
                            overlayableInfo.actor, mPackageManager.getNamedActors()).first;
                    if (mPackageManager.signaturesMatching(actorPackageName,
                            overlayPackage.getPackageName(), userId)) {
                        return true;
                    }
                }
            } catch (IOException ignored) {
            }
        }

        return false;
    }
}
