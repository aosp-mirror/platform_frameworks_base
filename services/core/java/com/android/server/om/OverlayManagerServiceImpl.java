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

import static android.content.om.OverlayInfo.STATE_APPROVED_DISABLED;
import static android.content.om.OverlayInfo.STATE_APPROVED_ENABLED;
import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_COMPONENT_DISABLED;
import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_DANGEROUS_OVERLAY;
import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_MISSING_TARGET;
import static android.content.om.OverlayInfo.STATE_NOT_APPROVED_NO_IDMAP;
import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.om.OverlayInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Internal implementation of OverlayManagerService.
 *
 * Methods in this class should only be called by the OverlayManagerService.
 * This class is not thread-safe; the caller is expected to ensure the
 * necessary thread synchronization.
 *
 * @see OverlayManagerService
 */
final class OverlayManagerServiceImpl {
    private final PackageManagerHelper mPackageManager;
    private final IdmapManager mIdmapManager;
    private final OverlayManagerSettings mSettings;

    OverlayManagerServiceImpl(@NonNull final PackageManagerHelper packageManager,
            @NonNull final IdmapManager idmapManager,
            @NonNull final OverlayManagerSettings settings) {
        mPackageManager = packageManager;
        mIdmapManager = idmapManager;
        mSettings = settings;
    }

    /*
     * Call this when switching to a new Android user. Will return a list of
     * target packages that must refresh their overlays. This list is the union
     * of two sets: the set of targets with currently active overlays, and the
     * set of targets that had, but no longer have, active overlays.
     */
    List<String> onSwitchUser(final int newUserId) {
        if (DEBUG) {
            Slog.d(TAG, "onSwitchUser newUserId=" + newUserId);
        }

        final Set<String> packagesToUpdateAssets = new ArraySet<>();
        final Map<String, List<OverlayInfo>> tmp = mSettings.getOverlaysForUser(newUserId);
        final Map<String, OverlayInfo> storedOverlayInfos = new ArrayMap<>(tmp.size());
        for (final List<OverlayInfo> chunk: tmp.values()) {
            for (final OverlayInfo oi: chunk) {
                storedOverlayInfos.put(oi.packageName, oi);
            }
        }

        for (PackageInfo overlayPackage: mPackageManager.getOverlayPackages(newUserId)) {
            final OverlayInfo oi = storedOverlayInfos.get(overlayPackage.packageName);
            if (oi == null || !oi.targetPackageName.equals(overlayPackage.overlayTarget)) {
                if (oi != null) {
                    packagesToUpdateAssets.add(oi.targetPackageName);
                }
                mSettings.init(overlayPackage.packageName, newUserId,
                        overlayPackage.overlayTarget,
                        overlayPackage.applicationInfo.getBaseCodePath());
            }

            try {
                final PackageInfo targetPackage =
                    mPackageManager.getPackageInfo(overlayPackage.overlayTarget, newUserId);
                updateState(targetPackage, overlayPackage, newUserId);
            } catch (OverlayManagerSettings.BadKeyException e) {
                Slog.e(TAG, "failed to update settings", e);
                mSettings.remove(overlayPackage.packageName, newUserId);
            }

            packagesToUpdateAssets.add(overlayPackage.overlayTarget);
            storedOverlayInfos.remove(overlayPackage.packageName);
        }

        // any OverlayInfo left in storedOverlayInfos is no longer
        // installed and should be removed
        for (final OverlayInfo oi: storedOverlayInfos.values()) {
            mSettings.remove(oi.packageName, oi.userId);
            removeIdmapIfPossible(oi);
            packagesToUpdateAssets.add(oi.targetPackageName);
        }

        // remove target packages that are not installed
        final Iterator<String> iter = packagesToUpdateAssets.iterator();
        while (iter.hasNext()) {
            String targetPackageName = iter.next();
            if (mPackageManager.getPackageInfo(targetPackageName, newUserId) == null) {
                iter.remove();
            }
        }

        return new ArrayList<String>(packagesToUpdateAssets);
    }

    void onUserRemoved(final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onUserRemoved userId=" + userId);
        }
        mSettings.removeUser(userId);
    }

    void onTargetPackageAdded(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageAdded packageName=" + packageName + " userId=" + userId);
        }

        final PackageInfo targetPackage = mPackageManager.getPackageInfo(packageName, userId);
        updateAllOverlaysForTarget(packageName, userId, targetPackage);
    }

    void onTargetPackageChanged(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageChanged packageName=" + packageName + " userId=" + userId);
        }

        final PackageInfo targetPackage = mPackageManager.getPackageInfo(packageName, userId);
        updateAllOverlaysForTarget(packageName, userId, targetPackage);
    }

    void onTargetPackageUpgrading(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageUpgrading packageName=" + packageName + " userId=" + userId);
        }

        updateAllOverlaysForTarget(packageName, userId, null);
    }

    void onTargetPackageUpgraded(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageUpgraded packageName=" + packageName + " userId=" + userId);
        }

        final PackageInfo targetPackage = mPackageManager.getPackageInfo(packageName, userId);
        updateAllOverlaysForTarget(packageName, userId, targetPackage);
    }

    void onTargetPackageRemoved(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageRemoved packageName=" + packageName + " userId=" + userId);
        }

        updateAllOverlaysForTarget(packageName, userId, null);
    }

    private void updateAllOverlaysForTarget(@NonNull final String packageName, final int userId,
            @Nullable final PackageInfo targetPackage) {
        final List<OverlayInfo> ois = mSettings.getOverlaysForTarget(packageName, userId);
        for (final OverlayInfo oi : ois) {
            final PackageInfo overlayPackage = mPackageManager.getPackageInfo(oi.packageName, userId);
            if (overlayPackage == null) {
                mSettings.remove(oi.packageName, oi.userId);
                removeIdmapIfPossible(oi);
            } else {
                try {
                    updateState(targetPackage, overlayPackage, userId);
                } catch (OverlayManagerSettings.BadKeyException e) {
                    Slog.e(TAG, "failed to update settings", e);
                    mSettings.remove(oi.packageName, userId);
                }
            }
        }
    }

    void onOverlayPackageAdded(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageAdded packageName=" + packageName + " userId=" + userId);
        }

        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            Slog.w(TAG, "overlay package " + packageName + " was added, but couldn't be found");
            onOverlayPackageRemoved(packageName, userId);
            return;
        }

        final PackageInfo targetPackage =
            mPackageManager.getPackageInfo(overlayPackage.overlayTarget, userId);

        mSettings.init(packageName, userId, overlayPackage.overlayTarget,
                overlayPackage.applicationInfo.getBaseCodePath());
        try {
            updateState(targetPackage, overlayPackage, userId);
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e(TAG, "failed to update settings", e);
            mSettings.remove(packageName, userId);
        }
    }

    void onOverlayPackageChanged(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageChanged packageName=" + packageName + " userId=" + userId);
        }

        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            Slog.w(TAG, "overlay package " + packageName + " was changed, but couldn't be found");
            onOverlayPackageRemoved(packageName, userId);
            return;
        }

        final PackageInfo targetPackage =
            mPackageManager.getPackageInfo(overlayPackage.overlayTarget, userId);

        try {
            updateState(targetPackage, overlayPackage, userId);
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e(TAG, "failed to update settings", e);
            mSettings.remove(packageName, userId);
        }
    }

    void onOverlayPackageUpgrading(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageUpgrading packageName=" + packageName + " userId=" + userId);
        }

        try {
            final OverlayInfo oi = mSettings.getOverlayInfo(packageName, userId);
            mSettings.setUpgrading(packageName, userId, true);
            removeIdmapIfPossible(oi);
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e(TAG, "failed to update settings", e);
            mSettings.remove(packageName, userId);
        }
    }

    void onOverlayPackageUpgraded(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageUpgraded packageName=" + packageName + " userId=" + userId);
        }

        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            Slog.w(TAG, "overlay package " + packageName + " was upgraded, but couldn't be found");
            onOverlayPackageRemoved(packageName, userId);
            return;
        }

        try {
            final String storedTargetPackageName = mSettings.getTargetPackageName(packageName, userId);
            if (!overlayPackage.overlayTarget.equals(storedTargetPackageName)) {
                // Sneaky little hobbitses, changing the overlay's target package
                // from one version to the next! We can't use the old version's
                // state.
                mSettings.remove(packageName, userId);
                onOverlayPackageAdded(packageName, userId);
                return;
            }

            mSettings.setUpgrading(packageName, userId, false);
            final PackageInfo targetPackage =
                mPackageManager.getPackageInfo(overlayPackage.overlayTarget, userId);
            updateState(targetPackage, overlayPackage, userId);
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e(TAG, "failed to update settings", e);
            mSettings.remove(packageName, userId);
        }
    }

    void onOverlayPackageRemoved(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageRemoved packageName=" + packageName + " userId=" + userId);
        }

        try {
            final OverlayInfo oi = mSettings.getOverlayInfo(packageName, userId);
            mSettings.remove(packageName, userId);
            removeIdmapIfPossible(oi);
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e(TAG, "failed to remove overlay package", e);
        }
    }

    OverlayInfo onGetOverlayInfo(@NonNull final String packageName, final int userId) {
        try {
            return mSettings.getOverlayInfo(packageName, userId);
        } catch (OverlayManagerSettings.BadKeyException e) {
            return null;
        }
    }

    List<OverlayInfo> onGetOverlayInfosForTarget(@NonNull final String targetPackageName,
            final int userId) {
        return mSettings.getOverlaysForTarget(targetPackageName, userId);
    }

    Map<String, List<OverlayInfo>> onGetOverlaysForUser(final int userId) {
        return mSettings.getOverlaysForUser(userId);
    }

    boolean onSetEnabled(@NonNull final String packageName, final boolean enable,
            final int userId, final boolean shouldWait) {
        if (DEBUG) {
            Slog.d(TAG, String.format("onSetEnabled packageName=%s enable=%s userId=%d",
                        packageName, enable, userId));
        }

        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            return false;
        }

        try {
            final OverlayInfo oi = mSettings.getOverlayInfo(packageName, userId);
            final PackageInfo targetPackage =
                mPackageManager.getPackageInfo(oi.targetPackageName, userId);
            mSettings.setEnabled(packageName, userId, enable);
            updateState(targetPackage, overlayPackage, userId, shouldWait);
            return true;
        } catch (OverlayManagerSettings.BadKeyException e) {
            return false;
        }
    }

    boolean onSetPriority(@NonNull final String packageName,
            @NonNull final String newParentPackageName, final int userId) {
        return mSettings.setPriority(packageName, newParentPackageName, userId);
    }

    boolean onSetHighestPriority(@NonNull final String packageName, final int userId) {
        return mSettings.setHighestPriority(packageName, userId);
    }

    boolean onSetLowestPriority(@NonNull final String packageName, final int userId) {
        return mSettings.setLowestPriority(packageName, userId);
    }

    void onDump(@NonNull final PrintWriter pw) {
        mSettings.dump(pw);
    }

    List<String> onGetEnabledOverlayPaths(@NonNull final String targetPackageName,
            final int userId) {
        final List<OverlayInfo> overlays = mSettings.getOverlaysForTarget(targetPackageName, userId);
        final List<String> paths = new ArrayList<>(overlays.size());
        for (final OverlayInfo oi : overlays) {
            if (oi.isEnabled()) {
                paths.add(oi.baseCodePath);
            }
        }
        return paths;
    }

    private void updateState(@Nullable final PackageInfo targetPackage,
            @NonNull final PackageInfo overlayPackage, final int userId)
        throws OverlayManagerSettings.BadKeyException {
        updateState(targetPackage, overlayPackage, userId, false);
    }

    private void updateState(@Nullable final PackageInfo targetPackage,
            @NonNull final PackageInfo overlayPackage, final int userId,
            final boolean shouldWait) throws OverlayManagerSettings.BadKeyException {
        if (targetPackage != null) {
            mIdmapManager.createIdmap(targetPackage, overlayPackage, userId);
        }

        mSettings.setBaseCodePath(overlayPackage.packageName, userId,
                overlayPackage.applicationInfo.getBaseCodePath());

        final int currentState = mSettings.getState(overlayPackage.packageName, userId);
        final int newState = calculateNewState(targetPackage, overlayPackage, userId);
        if (currentState != newState) {
            if (DEBUG) {
                Slog.d(TAG, String.format("%s:%d: %s -> %s",
                            overlayPackage.packageName, userId,
                            OverlayInfo.stateToString(currentState),
                            OverlayInfo.stateToString(newState)));
            }
            mSettings.setState(overlayPackage.packageName, userId, newState, shouldWait);
        }
    }

    private int calculateNewState(@Nullable final PackageInfo targetPackage,
            @NonNull final PackageInfo overlayPackage, final int userId)
        throws OverlayManagerSettings.BadKeyException {

        // STATE 0 CHECK: Check if the overlay package is disabled by PackageManager
        if (!overlayPackage.applicationInfo.enabled) {
            return STATE_NOT_APPROVED_COMPONENT_DISABLED;
        }

        // OVERLAY STATE CHECK: Check the current overlay's activation
        boolean stateCheck = mSettings.getEnabled(overlayPackage.packageName, userId);

        // STATE 1 CHECK: Check if the overlay's target package is missing from the device
        if (targetPackage == null) {
            return STATE_NOT_APPROVED_MISSING_TARGET;
        }

        // STATE 2 CHECK: Check if the overlay has an existing idmap file created. Perhaps
        // there were no matching resources between the two packages? (Overlay & Target)
        if (!mIdmapManager.idmapExists(overlayPackage, userId)) {
            return STATE_NOT_APPROVED_NO_IDMAP;
        }

        // STATE 6 CHECK: System Overlays, also known as RRO overlay files, work the same
        // as OMS, but with enable/disable limitations. A system overlay resides in the
        // directory "/vendor/overlay" depending on your device.
        //
        // Team Substratum: Disable this as this is a security vulnerability and a
        // memory-limited partition.
        if ((overlayPackage.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return STATE_NOT_APPROVED_COMPONENT_DISABLED;
        }

        // STATE 3 CHECK: If the overlay only modifies resources explicitly granted by the
        // target, we approve it.
        //
        // Team Substratum: Always approve dangerous packages but disabled state
        if (!mIdmapManager.isDangerous(overlayPackage, userId)) {
            return STATE_APPROVED_DISABLED;
        }

        return stateCheck ? STATE_APPROVED_ENABLED : STATE_APPROVED_DISABLED;
    }

    private void removeIdmapIfPossible(@NonNull final OverlayInfo oi) {
        // For a given package, all Android users share the same idmap file.
        // This works because Android currently does not support users to
        // install different versions of the same package. It also means we
        // cannot remove an idmap file if any user still needs it.
        //
        // When/if the Android framework allows different versions of the same
        // package to be installed for different users, idmap file handling
        // should be revised:
        //
        // - an idmap file should be unique for each {user, package} pair
        //
        // - the path to the idmap file should be passed to the native Asset
        //   Manager layers, just like the path to the apk is passed today
        //
        // As part of that change, calls to this method should be replaced by
        // direct calls to IdmapManager.removeIdmap, without looping over all
        // users.

        if (!mIdmapManager.idmapExists(oi)) {
            return;
        }
        final List<Integer> userIds = mSettings.getUsers();
        for (final int userId : userIds) {
            try {
                final OverlayInfo tmp = mSettings.getOverlayInfo(oi.packageName, userId);
                if (tmp != null && tmp.isEnabled()) {
                    // someone is still using the idmap file -> we cannot remove it
                    return;
                }
            } catch (OverlayManagerSettings.BadKeyException e) {
                // intentionally left empty
            }
        }
        mIdmapManager.removeIdmap(oi, oi.userId);
    }

    interface PackageManagerHelper {
        PackageInfo getPackageInfo(@NonNull String packageName, int userId);
        boolean signaturesMatching(@NonNull String packageName1, @NonNull String packageName2,
                                   int userId);
        List<PackageInfo> getOverlayPackages(int userId);
    }
}
