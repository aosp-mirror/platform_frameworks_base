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

import static android.content.om.OverlayInfo.STATE_DISABLED;
import static android.content.om.OverlayInfo.STATE_ENABLED;
import static android.content.om.OverlayInfo.STATE_MISSING_TARGET;
import static android.content.om.OverlayInfo.STATE_NO_IDMAP;

import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.om.OverlayInfo;
import android.content.pm.PackageInfo;
import android.text.TextUtils;
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
    private final Set<String> mDefaultOverlays;
    private final OverlayChangeListener mListener;

    OverlayManagerServiceImpl(@NonNull final PackageManagerHelper packageManager,
            @NonNull final IdmapManager idmapManager,
            @NonNull final OverlayManagerSettings settings,
            @NonNull final Set<String> defaultOverlays,
            @NonNull final OverlayChangeListener listener) {
        mPackageManager = packageManager;
        mIdmapManager = idmapManager;
        mSettings = settings;
        mDefaultOverlays = defaultOverlays;
        mListener = listener;
    }

    private static boolean isPackageStaticOverlay(final PackageInfo packageInfo) {
        return packageInfo.overlayTarget != null
                && (packageInfo.overlayFlags & PackageInfo.FLAG_OVERLAY_STATIC) != 0;
    }

    /**
     * Call this to synchronize the Settings for a user with what PackageManager knows about a user.
     * Returns a list of target packages that must refresh their overlays. This list is the union
     * of two sets: the set of targets with currently active overlays, and the
     * set of targets that had, but no longer have, active overlays.
     */
    ArrayList<String> updateOverlaysForUser(final int newUserId) {
        if (DEBUG) {
            Slog.d(TAG, "updateOverlaysForUser newUserId=" + newUserId);
        }

        final Set<String> packagesToUpdateAssets = new ArraySet<>();
        final ArrayMap<String, List<OverlayInfo>> tmp = mSettings.getOverlaysForUser(newUserId);
        final int tmpSize = tmp.size();
        final ArrayMap<String, OverlayInfo> storedOverlayInfos = new ArrayMap<>(tmpSize);
        for (int i = 0; i < tmpSize; i++) {
            final List<OverlayInfo> chunk = tmp.valueAt(i);
            final int chunkSize = chunk.size();
            for (int j = 0; j < chunkSize; j++) {
                final OverlayInfo oi = chunk.get(j);
                storedOverlayInfos.put(oi.packageName, oi);
            }
        }

        List<PackageInfo> overlayPackages = mPackageManager.getOverlayPackages(newUserId);
        final int overlayPackagesSize = overlayPackages.size();
        for (int i = 0; i < overlayPackagesSize; i++) {
            final PackageInfo overlayPackage = overlayPackages.get(i);
            final OverlayInfo oi = storedOverlayInfos.get(overlayPackage.packageName);
            if (oi == null || !oi.targetPackageName.equals(overlayPackage.overlayTarget)) {
                // Update the overlay if it didn't exist or had the wrong target package.
                mSettings.init(overlayPackage.packageName, newUserId,
                        overlayPackage.overlayTarget,
                        overlayPackage.applicationInfo.getBaseCodePath(),
                        isPackageStaticOverlay(overlayPackage), overlayPackage.overlayPriority);

                if (oi == null) {
                    // This overlay does not exist in our settings.
                    if (isPackageStaticOverlay(overlayPackage) ||
                            mDefaultOverlays.contains(overlayPackage.packageName)) {
                        // Enable this overlay by default.
                        if (DEBUG) {
                            Slog.d(TAG, "Enabling overlay " + overlayPackage.packageName
                                    + " for user " + newUserId + " by default");
                        }
                        mSettings.setEnabled(overlayPackage.packageName, newUserId, true);
                    }
                } else {
                    // The targetPackageName we have stored doesn't match the overlay's target.
                    // Queue the old target for an update as well.
                    packagesToUpdateAssets.add(oi.targetPackageName);
                }
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
        final int storedOverlayInfosSize = storedOverlayInfos.size();
        for (int i = 0; i < storedOverlayInfosSize; i++) {
            final OverlayInfo oi = storedOverlayInfos.valueAt(i);
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
        return new ArrayList<>(packagesToUpdateAssets);
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
        mListener.onOverlaysChanged(packageName, userId);
    }

    void onTargetPackageChanged(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageChanged packageName=" + packageName + " userId=" + userId);
        }

        final PackageInfo targetPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (updateAllOverlaysForTarget(packageName, userId, targetPackage)) {
            mListener.onOverlaysChanged(packageName, userId);
        }
    }

    void onTargetPackageUpgrading(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageUpgrading packageName=" + packageName + " userId=" + userId);
        }

        if (updateAllOverlaysForTarget(packageName, userId, null)) {
            mListener.onOverlaysChanged(packageName, userId);
        }
    }

    void onTargetPackageUpgraded(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageUpgraded packageName=" + packageName + " userId=" + userId);
        }

        final PackageInfo targetPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (updateAllOverlaysForTarget(packageName, userId, targetPackage)) {
            mListener.onOverlaysChanged(packageName, userId);
        }
    }

    void onTargetPackageRemoved(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageRemoved packageName=" + packageName + " userId=" + userId);
        }

        updateAllOverlaysForTarget(packageName, userId, null);
    }

    /**
     * Returns true if the settings were modified for this target.
     */
    private boolean updateAllOverlaysForTarget(@NonNull final String packageName, final int userId,
            @Nullable final PackageInfo targetPackage) {
        boolean modified = false;
        final List<OverlayInfo> ois = mSettings.getOverlaysForTarget(packageName, userId);
        final int N = ois.size();
        for (int i = 0; i < N; i++) {
            final OverlayInfo oi = ois.get(i);
            final PackageInfo overlayPackage = mPackageManager.getPackageInfo(oi.packageName, userId);
            if (overlayPackage == null) {
                modified |= mSettings.remove(oi.packageName, oi.userId);
                removeIdmapIfPossible(oi);
            } else {
                try {
                    modified |= updateState(targetPackage, overlayPackage, userId);
                } catch (OverlayManagerSettings.BadKeyException e) {
                    Slog.e(TAG, "failed to update settings", e);
                    modified |= mSettings.remove(oi.packageName, userId);
                }
            }
        }
        return modified;
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
                overlayPackage.applicationInfo.getBaseCodePath(),
                isPackageStaticOverlay(overlayPackage), overlayPackage.overlayPriority);
        try {
            if (updateState(targetPackage, overlayPackage, userId)) {
                mListener.onOverlaysChanged(overlayPackage.overlayTarget, userId);
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e(TAG, "failed to update settings", e);
            mSettings.remove(packageName, userId);
        }
    }

    void onOverlayPackageChanged(@NonNull final String packageName, final int userId) {
        Slog.wtf(TAG, "onOverlayPackageChanged called, but only pre-installed overlays supported");
    }

    void onOverlayPackageUpgrading(@NonNull final String packageName, final int userId) {
        Slog.wtf(TAG, "onOverlayPackageUpgrading called, but only pre-installed overlays supported");
    }

    void onOverlayPackageUpgraded(@NonNull final String packageName, final int userId) {
        Slog.wtf(TAG, "onOverlayPackageUpgraded called, but only pre-installed overlays supported");
    }

    void onOverlayPackageRemoved(@NonNull final String packageName, final int userId) {
        Slog.wtf(TAG, "onOverlayPackageRemoved called, but only pre-installed overlays supported");
    }

    OverlayInfo getOverlayInfo(@NonNull final String packageName, final int userId) {
        try {
            return mSettings.getOverlayInfo(packageName, userId);
        } catch (OverlayManagerSettings.BadKeyException e) {
            return null;
        }
    }

    List<OverlayInfo> getOverlayInfosForTarget(@NonNull final String targetPackageName,
            final int userId) {
        return mSettings.getOverlaysForTarget(targetPackageName, userId);
    }

    Map<String, List<OverlayInfo>> getOverlaysForUser(final int userId) {
        return mSettings.getOverlaysForUser(userId);
    }

    boolean setEnabled(@NonNull final String packageName, final boolean enable,
            final int userId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("setEnabled packageName=%s enable=%s userId=%d",
                        packageName, enable, userId));
        }

        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            return false;
        }

        // Ignore static overlays.
        if (isPackageStaticOverlay(overlayPackage)) {
            return false;
        }

        try {
            final OverlayInfo oi = mSettings.getOverlayInfo(packageName, userId);
            final PackageInfo targetPackage =
                    mPackageManager.getPackageInfo(oi.targetPackageName, userId);
            boolean modified = mSettings.setEnabled(packageName, userId, enable);
            modified |= updateState(targetPackage, overlayPackage, userId);

            if (modified) {
                mListener.onOverlaysChanged(oi.targetPackageName, userId);
            }
            return true;
        } catch (OverlayManagerSettings.BadKeyException e) {
            return false;
        }
    }

    boolean setEnabledExclusive(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("setEnabledExclusive packageName=%s userId=%d", packageName, userId));
        }

        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            return false;
        }

        try {
            final OverlayInfo oi = mSettings.getOverlayInfo(packageName, userId);
            final PackageInfo targetPackage =
                    mPackageManager.getPackageInfo(oi.targetPackageName, userId);

            List<OverlayInfo> allOverlays = getOverlayInfosForTarget(oi.targetPackageName, userId);

            boolean modified = false;

            // Disable all other overlays.
            allOverlays.remove(oi);
            for (int i = 0; i < allOverlays.size(); i++) {
                final String disabledOverlayPackageName = allOverlays.get(i).packageName;
                final PackageInfo disabledOverlayPackageInfo = mPackageManager.getPackageInfo(
                        disabledOverlayPackageName, userId);
                if (disabledOverlayPackageInfo == null) {
                    modified |= mSettings.remove(disabledOverlayPackageName, userId);
                    continue;
                }

                if (isPackageStaticOverlay(disabledOverlayPackageInfo)) {
                    // Don't touch static overlays.
                    continue;
                }

                // Disable the overlay.
                modified |= mSettings.setEnabled(disabledOverlayPackageName, userId, false);
                modified |= updateState(targetPackage, disabledOverlayPackageInfo, userId);
            }

            // Enable the selected overlay.
            modified |= mSettings.setEnabled(packageName, userId, true);
            modified |= updateState(targetPackage, overlayPackage, userId);

            if (modified) {
                mListener.onOverlaysChanged(oi.targetPackageName, userId);
            }
            return true;
        } catch (OverlayManagerSettings.BadKeyException e) {
            return false;
        }
    }

    private boolean isPackageUpdatableOverlay(@NonNull final String packageName, final int userId) {
        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null || isPackageStaticOverlay(overlayPackage)) {
            return false;
        }
        return true;
    }

    boolean setPriority(@NonNull final String packageName,
            @NonNull final String newParentPackageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "setPriority packageName=" + packageName + " newParentPackageName="
                    + newParentPackageName + " userId=" + userId);
        }

        if (!isPackageUpdatableOverlay(packageName, userId)) {
            return false;
        }

        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            return false;
        }

        if (mSettings.setPriority(packageName, newParentPackageName, userId)) {
            mListener.onOverlaysChanged(overlayPackage.overlayTarget, userId);
        }
        return true;
    }

    boolean setHighestPriority(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "setHighestPriority packageName=" + packageName + " userId=" + userId);
        }

        if (!isPackageUpdatableOverlay(packageName, userId)) {
            return false;
        }

        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            return false;
        }

        if (mSettings.setHighestPriority(packageName, userId)) {
            mListener.onOverlaysChanged(overlayPackage.overlayTarget, userId);
        }
        return true;
    }

    boolean setLowestPriority(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "setLowestPriority packageName=" + packageName + " userId=" + userId);
        }

        if (!isPackageUpdatableOverlay(packageName, userId)) {
            return false;
        }

        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            return false;
        }

        if (mSettings.setLowestPriority(packageName, userId)) {
            mListener.onOverlaysChanged(overlayPackage.overlayTarget, userId);
        }
        return true;
    }

    void onDump(@NonNull final PrintWriter pw) {
        mSettings.dump(pw);
        pw.println("Default overlays: " + TextUtils.join(";", mDefaultOverlays));
    }

    List<String> getEnabledOverlayPackageNames(@NonNull final String targetPackageName,
            final int userId) {
        final List<OverlayInfo> overlays = mSettings.getOverlaysForTarget(targetPackageName, userId);
        final List<String> paths = new ArrayList<>(overlays.size());
        final int N = overlays.size();
        for (int i = 0; i < N; i++) {
            final OverlayInfo oi = overlays.get(i);
            if (oi.isEnabled()) {
                paths.add(oi.packageName);
            }
        }
        return paths;
    }

    /**
     * Returns true if the settings/state was modified, false otherwise.
     */
    private boolean updateState(@Nullable final PackageInfo targetPackage,
            @NonNull final PackageInfo overlayPackage, final int userId)
            throws OverlayManagerSettings.BadKeyException {
        // Static RROs targeting to "android", ie framework-res.apk, are handled by native layers.
        if (targetPackage != null &&
                !("android".equals(targetPackage.packageName)
                        && isPackageStaticOverlay(overlayPackage))) {
            mIdmapManager.createIdmap(targetPackage, overlayPackage, userId);
        }

        boolean modified = mSettings.setBaseCodePath(overlayPackage.packageName, userId,
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
            modified |= mSettings.setState(overlayPackage.packageName, userId, newState);
        }
        return modified;
    }

    private int calculateNewState(@Nullable final PackageInfo targetPackage,
            @NonNull final PackageInfo overlayPackage, final int userId)
        throws OverlayManagerSettings.BadKeyException {
        if (targetPackage == null) {
            return STATE_MISSING_TARGET;
        }

        if (!mIdmapManager.idmapExists(overlayPackage, userId)) {
            return STATE_NO_IDMAP;
        }

        final boolean enabled = mSettings.getEnabled(overlayPackage.packageName, userId);
        return enabled ? STATE_ENABLED : STATE_DISABLED;
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
        final int[] userIds = mSettings.getUsers();
        for (int userId : userIds) {
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

    interface OverlayChangeListener {
        void onOverlaysChanged(@NonNull String targetPackage, int userId);
    }

    interface PackageManagerHelper {
        PackageInfo getPackageInfo(@NonNull String packageName, int userId);
        boolean signaturesMatching(@NonNull String packageName1, @NonNull String packageName2,
                                   int userId);
        List<PackageInfo> getOverlayPackages(int userId);
    }
}
