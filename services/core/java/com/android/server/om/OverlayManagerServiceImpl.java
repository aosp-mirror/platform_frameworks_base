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
import static android.content.om.OverlayInfo.STATE_OVERLAY_UPGRADING;
import static android.content.om.OverlayInfo.STATE_TARGET_UPGRADING;

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
import java.util.Objects;
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
    // Flags to use in conjunction with updateState.
    private static final int FLAG_TARGET_IS_UPGRADING = 1<<0;
    private static final int FLAG_OVERLAY_IS_UPGRADING = 1<<1;

    private final PackageManagerHelper mPackageManager;
    private final IdmapManager mIdmapManager;
    private final OverlayManagerSettings mSettings;
    private final String[] mDefaultOverlays;
    private final OverlayChangeListener mListener;

    OverlayManagerServiceImpl(@NonNull final PackageManagerHelper packageManager,
            @NonNull final IdmapManager idmapManager,
            @NonNull final OverlayManagerSettings settings,
            @NonNull final String[] defaultOverlays,
            @NonNull final OverlayChangeListener listener) {
        mPackageManager = packageManager;
        mIdmapManager = idmapManager;
        mSettings = settings;
        mDefaultOverlays = defaultOverlays;
        mListener = listener;
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
                // Reset the overlay if it didn't exist or had the wrong target package.
                mSettings.init(overlayPackage.packageName, newUserId,
                        overlayPackage.overlayTarget,
                        overlayPackage.applicationInfo.getBaseCodePath(),
                        overlayPackage.isStaticOverlayPackage(),
                        overlayPackage.overlayPriority,
                        overlayPackage.overlayCategory);

                if (oi != null) {
                    // The targetPackageName we have stored doesn't match the overlay's target.
                    // Queue the old target for an update as well.
                    packagesToUpdateAssets.add(oi.targetPackageName);
                }
            } else {
                // Update all other components of an overlay that don't require a hard reset.
                if (!Objects.equals(oi.category, overlayPackage.overlayCategory)) {
                    // When changing categories, it is ok just to update our internal state.
                    mSettings.setCategory(overlayPackage.packageName, newUserId,
                            overlayPackage.overlayCategory);
                }
            }

            try {
                updateState(overlayPackage.overlayTarget, overlayPackage.packageName, newUserId, 0);
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

        // Collect all of the categories in which we have at least one overlay enabled.
        final ArraySet<String> enabledCategories = new ArraySet<>();
        final ArrayMap<String, List<OverlayInfo>> userOverlays =
                mSettings.getOverlaysForUser(newUserId);
        final int userOverlayTargetCount = userOverlays.size();
        for (int i = 0; i < userOverlayTargetCount; i++) {
            final List<OverlayInfo> overlayList = userOverlays.valueAt(i);
            final int overlayCount = overlayList != null ? overlayList.size() : 0;
            for (int j = 0; j < overlayCount; j++) {
                final OverlayInfo oi = overlayList.get(j);
                if (oi.isEnabled()) {
                    enabledCategories.add(oi.category);
                }
            }
        }

        // Enable the default overlay if its category does not have a single overlay enabled.
        for (final String defaultOverlay : mDefaultOverlays) {
            try {
                final OverlayInfo oi = mSettings.getOverlayInfo(defaultOverlay, newUserId);
                if (!enabledCategories.contains(oi.category)) {
                    Slog.w(TAG, "Enabling default overlay '" + defaultOverlay + "' for target '"
                            + oi.targetPackageName + "' in category '" + oi.category + "' for user "
                            + newUserId);
                    mSettings.setEnabled(oi.packageName, newUserId, true);
                    if (updateState(oi.targetPackageName, oi.packageName, newUserId, 0)) {
                        packagesToUpdateAssets.add(oi.targetPackageName);
                    }
                }
            } catch (OverlayManagerSettings.BadKeyException e) {
                Slog.e(TAG, "Failed to set default overlay '" + defaultOverlay + "' for user "
                        + newUserId, e);
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

        updateAllOverlaysForTarget(packageName, userId, 0);
    }

    void onTargetPackageChanged(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageChanged packageName=" + packageName + " userId=" + userId);
        }

        updateAllOverlaysForTarget(packageName, userId, 0);
    }

    void onTargetPackageUpgrading(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageUpgrading packageName=" + packageName + " userId="
                    + userId);
        }

        updateAllOverlaysForTarget(packageName, userId, FLAG_TARGET_IS_UPGRADING);
    }

    void onTargetPackageUpgraded(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageUpgraded packageName=" + packageName + " userId=" + userId);
        }

        updateAllOverlaysForTarget(packageName, userId, 0);
    }

    void onTargetPackageRemoved(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onTargetPackageRemoved packageName=" + packageName + " userId=" + userId);
        }

        updateAllOverlaysForTarget(packageName, userId, 0);
    }

    /**
     * Calls OverlayChangeListener#onChanged if the settings for the overlay target were modified,
     * and calls OverlayChangeListener#onTargetChanged to signal a change in the target package that
     * requires updating target overlays.
     */
    private void updateAllOverlaysForTarget(@NonNull final String targetPackageName,
            final int userId, final int flags) {
        boolean overlayModified = false;
        final List<OverlayInfo> ois = mSettings.getOverlaysForTarget(targetPackageName, userId);
        final int N = ois.size();
        for (int i = 0; i < N; i++) {
            final OverlayInfo oi = ois.get(i);
            final PackageInfo overlayPackage = mPackageManager.getPackageInfo(oi.packageName,
                    userId);
            if (overlayPackage == null) {
                overlayModified |= mSettings.remove(oi.packageName, oi.userId);
                removeIdmapIfPossible(oi);
            } else {
                try {
                    overlayModified |= updateState(targetPackageName, oi.packageName, userId, flags);
                } catch (OverlayManagerSettings.BadKeyException e) {
                    Slog.e(TAG, "failed to update settings", e);
                    overlayModified |= mSettings.remove(oi.packageName, userId);
                }
            }
        }

        mListener.onChanged(targetPackageName, userId, /* targetChanged */ true, overlayModified);
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

        mSettings.init(packageName, userId, overlayPackage.overlayTarget,
                overlayPackage.applicationInfo.getBaseCodePath(),
                overlayPackage.isStaticOverlayPackage(), overlayPackage.overlayPriority,
                overlayPackage.overlayCategory);
        try {
            if (updateState(overlayPackage.overlayTarget, packageName, userId, 0)) {
                mListener.onChanged(overlayPackage.overlayTarget, userId,
                        /* targetChanged */ false,  /* overlayChanged */ true);
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e(TAG, "failed to update settings", e);
            mSettings.remove(packageName, userId);
        }
    }

    void onOverlayPackageChanged(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageChanged packageName=" + packageName + " userId=" + userId);
        }

        try {
            final OverlayInfo oi = mSettings.getOverlayInfo(packageName, userId);
            if (updateState(oi.targetPackageName, packageName, userId, 0)) {
                mListener.onChanged(oi.targetPackageName, userId,
                        /* targetChanged */ false,  /* overlayChanged */ true);
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e(TAG, "failed to update settings", e);
        }
    }

    void onOverlayPackageUpgrading(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageUpgrading packageName=" + packageName + " userId="
                    + userId);
        }

        try {
            final OverlayInfo oi = mSettings.getOverlayInfo(packageName, userId);
            if (updateState(oi.targetPackageName, packageName, userId, FLAG_OVERLAY_IS_UPGRADING)) {
                removeIdmapIfPossible(oi);
                mListener.onChanged(oi.targetPackageName, userId,
                        /* targetChanged */ false,  /* overlayChanged */ true);
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e(TAG, "failed to update settings", e);
        }
    }

    void onOverlayPackageUpgraded(@NonNull final String packageName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onOverlayPackageUpgraded packageName=" + packageName + " userId="
                    + userId);
        }

        final PackageInfo pkg = mPackageManager.getPackageInfo(packageName, userId);
        if (pkg == null) {
            Slog.w(TAG, "overlay package " + packageName + " was upgraded, but couldn't be found");
            onOverlayPackageRemoved(packageName, userId);
            return;
        }

        try {
            final OverlayInfo oldOi = mSettings.getOverlayInfo(packageName, userId);
            if (!oldOi.targetPackageName.equals(pkg.overlayTarget)) {
                mSettings.init(packageName, userId, pkg.overlayTarget,
                        pkg.applicationInfo.getBaseCodePath(), pkg.isStaticOverlayPackage(),
                        pkg.overlayPriority, pkg.overlayCategory);
            } else {
                if (!Objects.equals(oldOi.category, pkg.overlayCategory)) {
                    // Update the category in-place.
                    mSettings.setCategory(packageName, userId, pkg.overlayCategory);
                }
            }

            if (updateState(pkg.overlayTarget, packageName, userId, 0)) {
                mListener.onChanged(pkg.overlayTarget, userId,
                        /* targetChanged */ false,  /* overlayChanged */ true);
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e(TAG, "failed to update settings", e);
        }
    }

    void onOverlayPackageRemoved(@NonNull final String packageName, final int userId) {
        try {
            final OverlayInfo overlayInfo = mSettings.getOverlayInfo(packageName, userId);
            if (mSettings.remove(packageName, userId)) {
                removeIdmapIfPossible(overlayInfo);
                if (overlayInfo.isEnabled()) {
                    // Only trigger updates if the overlay was enabled.
                    mListener.onChanged(overlayInfo.targetPackageName, userId,
                            /* targetChanged */ false,  /* overlayChanged */ true);
                }
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            Slog.e(TAG, "failed to remove overlay", e);
        }
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
        if (overlayPackage.isStaticOverlayPackage()) {
            return false;
        }

        try {
            final OverlayInfo oi = mSettings.getOverlayInfo(packageName, userId);
            boolean modified = mSettings.setEnabled(packageName, userId, enable);
            modified |= updateState(oi.targetPackageName, oi.packageName, userId, 0);

            if (modified) {
                mListener.onChanged(oi.targetPackageName, userId,
                        /* targetChanged */ false,  /* overlayChanged */ true);
            }
            return true;
        } catch (OverlayManagerSettings.BadKeyException e) {
            return false;
        }
    }

    boolean setEnabledExclusive(@NonNull final String packageName, boolean withinCategory,
            final int userId) {
        if (DEBUG) {
            Slog.d(TAG, String.format("setEnabledExclusive packageName=%s"
                    + " withinCategory=%s userId=%d", packageName, withinCategory, userId));
        }

        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null) {
            return false;
        }

        try {
            final OverlayInfo oi = mSettings.getOverlayInfo(packageName, userId);
            final String targetPackageName = oi.targetPackageName;

            List<OverlayInfo> allOverlays = getOverlayInfosForTarget(targetPackageName, userId);

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

                if (disabledOverlayPackageInfo.isStaticOverlayPackage()) {
                    // Don't touch static overlays.
                    continue;
                }
                if (withinCategory && !Objects.equals(disabledOverlayPackageInfo.overlayCategory,
                        oi.category)) {
                    // Don't touch overlays from other categories.
                    continue;
                }

                // Disable the overlay.
                modified |= mSettings.setEnabled(disabledOverlayPackageName, userId, false);
                modified |= updateState(targetPackageName, disabledOverlayPackageName, userId, 0);
            }

            // Enable the selected overlay.
            modified |= mSettings.setEnabled(packageName, userId, true);
            modified |= updateState(targetPackageName, packageName, userId, 0);

            if (modified) {
                mListener.onChanged(targetPackageName, userId,
                        /* targetChanged */ false,  /* overlayChanged */ true);
            }
            return true;
        } catch (OverlayManagerSettings.BadKeyException e) {
            return false;
        }
    }

    private boolean isPackageUpdatableOverlay(@NonNull final String packageName, final int userId) {
        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(packageName, userId);
        if (overlayPackage == null || overlayPackage.isStaticOverlayPackage()) {
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
            mListener.onChanged(overlayPackage.overlayTarget, userId,
                    /* targetChanged */ false,  /* overlayChanged */ true);
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
            mListener.onChanged(overlayPackage.overlayTarget, userId,
                    /* targetChanged */ false,  /* overlayChanged */ true);
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
            mListener.onChanged(overlayPackage.overlayTarget, userId,
                    /* targetChanged */ false,  /* overlayChanged */ true);
        }
        return true;
    }

    void onDump(@NonNull final PrintWriter pw) {
        mSettings.dump(pw);
        pw.println("Default overlays: " + TextUtils.join(";", mDefaultOverlays));
    }

    List<String> getEnabledOverlayPackageNames(@NonNull final String targetPackageName,
            final int userId) {
        final List<OverlayInfo> overlays = mSettings.getOverlaysForTarget(targetPackageName,
                userId);
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
    private boolean updateState(@NonNull final String targetPackageName,
            @NonNull final String overlayPackageName, final int userId, final int flags)
            throws OverlayManagerSettings.BadKeyException {

        final PackageInfo targetPackage = mPackageManager.getPackageInfo(targetPackageName, userId);
        final PackageInfo overlayPackage = mPackageManager.getPackageInfo(overlayPackageName,
                userId);

        // Static RROs targeting to "android", ie framework-res.apk, are handled by native layers.
        if (targetPackage != null && overlayPackage != null &&
                !("android".equals(targetPackageName)
                        && overlayPackage.isStaticOverlayPackage())) {
            mIdmapManager.createIdmap(targetPackage, overlayPackage, userId);
        }

        boolean modified = false;
        if (overlayPackage != null) {
            modified |= mSettings.setBaseCodePath(overlayPackageName, userId,
                    overlayPackage.applicationInfo.getBaseCodePath());
        }

        final @OverlayInfo.State int currentState = mSettings.getState(overlayPackageName, userId);
        final @OverlayInfo.State int newState = calculateNewState(targetPackage, overlayPackage,
                userId, flags);
        if (currentState != newState) {
            if (DEBUG) {
                Slog.d(TAG, String.format("%s:%d: %s -> %s",
                            overlayPackageName, userId,
                            OverlayInfo.stateToString(currentState),
                            OverlayInfo.stateToString(newState)));
            }
            modified |= mSettings.setState(overlayPackageName, userId, newState);
        }
        return modified;
    }

    private @OverlayInfo.State int calculateNewState(@Nullable final PackageInfo targetPackage,
            @Nullable final PackageInfo overlayPackage, final int userId, final int flags)
        throws OverlayManagerSettings.BadKeyException {

        if ((flags & FLAG_TARGET_IS_UPGRADING) != 0) {
            return STATE_TARGET_UPGRADING;
        }

        if ((flags & FLAG_OVERLAY_IS_UPGRADING) != 0) {
            return STATE_OVERLAY_UPGRADING;
        }

        // assert expectation on overlay package: can only be null if the flags are used
        if (DEBUG && overlayPackage == null) {
            throw new IllegalArgumentException("null overlay package not compatible with no flags");
        }

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
        void onChanged(@NonNull String targetPackage, int userId,
                boolean targetChanged, boolean overlayChanged);
    }

    interface PackageManagerHelper {
        PackageInfo getPackageInfo(@NonNull String packageName, int userId);
        boolean signaturesMatching(@NonNull String packageName1, @NonNull String packageName2,
                                   int userId);
        List<PackageInfo> getOverlayPackages(int userId);
    }
}
