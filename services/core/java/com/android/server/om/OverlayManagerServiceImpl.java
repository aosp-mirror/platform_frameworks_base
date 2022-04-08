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
import static android.content.om.OverlayInfo.STATE_OVERLAY_IS_BEING_REPLACED;
import static android.content.om.OverlayInfo.STATE_TARGET_IS_BEING_REPLACED;
import static android.os.UserHandle.USER_SYSTEM;

import static com.android.server.om.IdmapManager.IDMAP_IS_MODIFIED;
import static com.android.server.om.IdmapManager.IDMAP_IS_VERIFIED;
import static com.android.server.om.IdmapManager.IDMAP_NOT_EXIST;
import static com.android.server.om.OverlayManagerService.DEBUG;
import static com.android.server.om.OverlayManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.om.CriticalOverlayInfo;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.pm.overlay.OverlayPaths;
import android.content.pm.parsing.FrameworkParsingPackageUtils;
import android.os.FabricatedOverlayInfo;
import android.os.FabricatedOverlayInternal;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.content.om.OverlayConfig;
import com.android.internal.util.CollectionUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

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
    /**
     * @deprecated Not used. See {@link android.content.om.OverlayInfo#STATE_TARGET_UPGRADING}.
     */
    @Deprecated
    private static final int FLAG_TARGET_IS_BEING_REPLACED = 1 << 0;

    // Flags to use in conjunction with updateState.
    private static final int FLAG_OVERLAY_IS_BEING_REPLACED = 1 << 1;

    private final PackageManagerHelper mPackageManager;
    private final IdmapManager mIdmapManager;
    private final OverlayManagerSettings mSettings;
    private final OverlayConfig mOverlayConfig;
    private final String[] mDefaultOverlays;

    /**
     * Helper method to merge the overlay manager's (as read from overlays.xml)
     * and package manager's (as parsed from AndroidManifest.xml files) views
     * on overlays.
     *
     * Both managers are usually in agreement, but especially after an OTA things
     * may differ. The package manager is always providing the truth; the overlay
     * manager has to adapt. Depending on what has changed about an overlay, we
     * should either scrap the overlay manager's previous settings or merge the old
     * settings with the new.
     */
    private boolean mustReinitializeOverlay(@NonNull final AndroidPackage theTruth,
            @Nullable final OverlayInfo oldSettings) {
        if (oldSettings == null) {
            return true;
        }
        if (!Objects.equals(theTruth.getOverlayTarget(), oldSettings.targetPackageName)) {
            return true;
        }
        if (!Objects.equals(theTruth.getOverlayTargetOverlayableName(),
                oldSettings.targetOverlayableName)) {
            return true;
        }
        if (oldSettings.isFabricated) {
            return true;
        }
        boolean isMutable = isPackageConfiguredMutable(theTruth);
        if (isMutable != oldSettings.isMutable) {
            return true;
        }
        // If an immutable overlay changes its configured enabled state, reinitialize the overlay.
        if (!isMutable && isPackageConfiguredEnabled(theTruth) != oldSettings.isEnabled()) {
            return true;
        }
        return false;
    }

    private boolean mustReinitializeOverlay(@NonNull final FabricatedOverlayInfo theTruth,
            @Nullable final OverlayInfo oldSettings) {
        if (oldSettings == null) {
            return true;
        }
        if (!Objects.equals(theTruth.targetPackageName, oldSettings.targetPackageName)) {
            return true;
        }
        if (!Objects.equals(theTruth.targetOverlayable, oldSettings.targetOverlayableName)) {
            return true;
        }
        return false;
    }

    OverlayManagerServiceImpl(@NonNull final PackageManagerHelper packageManager,
            @NonNull final IdmapManager idmapManager,
            @NonNull final OverlayManagerSettings settings,
            @NonNull final OverlayConfig overlayConfig,
            @NonNull final String[] defaultOverlays) {
        mPackageManager = packageManager;
        mIdmapManager = idmapManager;
        mSettings = settings;
        mOverlayConfig = overlayConfig;
        mDefaultOverlays = defaultOverlays;
    }

    /**
     * Call this to synchronize the Settings for a user with what PackageManager knows about a user.
     * Returns a list of target packages that must refresh their overlays. This list is the union
     * of two sets: the set of targets with currently active overlays, and the
     * set of targets that had, but no longer have, active overlays.
     */
    @NonNull
    ArraySet<PackageAndUser> updateOverlaysForUser(final int newUserId) {
        if (DEBUG) {
            Slog.d(TAG, "updateOverlaysForUser newUserId=" + newUserId);
        }

        // Remove the settings of all overlays that are no longer installed for this user.
        final ArraySet<PackageAndUser> updatedTargets = new ArraySet<>();
        final ArrayMap<String, AndroidPackage> userPackages = mPackageManager.initializeForUser(
                newUserId);
        CollectionUtils.addAll(updatedTargets, removeOverlaysForUser(
                (info) -> !userPackages.containsKey(info.packageName), newUserId));

        // Update the state of all installed packages containing overlays, and initialize new
        // overlays that are not currently in the settings.
        for (int i = 0, n = userPackages.size(); i < n; i++) {
            final AndroidPackage pkg = userPackages.valueAt(i);
            try {
                CollectionUtils.addAll(updatedTargets,
                        updatePackageOverlays(pkg, newUserId, 0 /* flags */));

                // When a new user is switched to for the first time, package manager must be
                // informed of the overlay paths for all packages installed in the user.
                updatedTargets.add(new PackageAndUser(pkg.getPackageName(), newUserId));
            } catch (OperationFailedException e) {
                Slog.e(TAG, "failed to initialize overlays of '" + pkg.getPackageName()
                        + "' for user " + newUserId + "", e);
            }
        }

        // Update the state of all fabricated overlays, and initialize fabricated overlays in the
        // new user.
        for (final FabricatedOverlayInfo info : getFabricatedOverlayInfos()) {
            try {
                CollectionUtils.addAll(updatedTargets, registerFabricatedOverlay(
                        info, newUserId));
            } catch (OperationFailedException e) {
                Slog.e(TAG, "failed to initialize fabricated overlay of '" + info.path
                        + "' for user " + newUserId + "", e);
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
                // OverlayConfig is the new preferred way to enable overlays by default. This legacy
                // default enabled method was created before overlays could have a name specified.
                // Only allow enabling overlays without a name using this mechanism.
                final OverlayIdentifier overlay = new OverlayIdentifier(defaultOverlay);

                final OverlayInfo oi = mSettings.getOverlayInfo(overlay, newUserId);
                if (!enabledCategories.contains(oi.category)) {
                    Slog.w(TAG, "Enabling default overlay '" + defaultOverlay + "' for target '"
                            + oi.targetPackageName + "' in category '" + oi.category + "' for user "
                            + newUserId);
                    mSettings.setEnabled(overlay, newUserId, true);
                    if (updateState(oi, newUserId, 0)) {
                        CollectionUtils.add(updatedTargets,
                                new PackageAndUser(oi.targetPackageName, oi.userId));
                    }
                }
            } catch (OverlayManagerSettings.BadKeyException e) {
                Slog.e(TAG, "Failed to set default overlay '" + defaultOverlay + "' for user "
                        + newUserId, e);
            }
        }

        cleanStaleResourceCache();
        return updatedTargets;
    }

    void onUserRemoved(final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onUserRemoved userId=" + userId);
        }
        mSettings.removeUser(userId);
    }

    @NonNull
    Set<PackageAndUser> onPackageAdded(@NonNull final String pkgName,
            final int userId) throws OperationFailedException {
        final Set<PackageAndUser> updatedTargets = new ArraySet<>();
        // Always update the overlays of newly added packages.
        updatedTargets.add(new PackageAndUser(pkgName, userId));
        updatedTargets.addAll(reconcileSettingsForPackage(pkgName, userId, 0 /* flags */));
        return updatedTargets;
    }

    @NonNull
    Set<PackageAndUser> onPackageChanged(@NonNull final String pkgName,
            final int userId) throws OperationFailedException {
        return reconcileSettingsForPackage(pkgName, userId, 0 /* flags */);
    }

    @NonNull
    Set<PackageAndUser> onPackageReplacing(@NonNull final String pkgName, final int userId)
            throws OperationFailedException {
        return reconcileSettingsForPackage(pkgName, userId, FLAG_OVERLAY_IS_BEING_REPLACED);
    }

    @NonNull
    Set<PackageAndUser> onPackageReplaced(@NonNull final String pkgName, final int userId)
            throws OperationFailedException {
        return reconcileSettingsForPackage(pkgName, userId, 0 /* flags */);
    }

    @NonNull
    Set<PackageAndUser> onPackageRemoved(@NonNull final String pkgName, final int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onPackageRemoved pkgName=" + pkgName + " userId=" + userId);
        }
        // Update the state of all overlays that target this package.
        final Set<PackageAndUser> targets = updateOverlaysForTarget(pkgName, userId, 0 /* flags */);

        // Remove all the overlays this package declares.
        return CollectionUtils.addAll(targets,
                removeOverlaysForUser(oi -> pkgName.equals(oi.packageName), userId));
    }

    @NonNull
    private Set<PackageAndUser> removeOverlaysForUser(
            @NonNull final Predicate<OverlayInfo> condition, final int userId) {
        final List<OverlayInfo> overlays = mSettings.removeIf(
                io -> userId == io.userId && condition.test(io));
        Set<PackageAndUser> targets = Collections.emptySet();
        for (int i = 0, n = overlays.size(); i < n; i++) {
            final OverlayInfo info = overlays.get(i);
            targets = CollectionUtils.add(targets,
                    new PackageAndUser(info.targetPackageName, userId));

            // Remove the idmap if the overlay is no longer installed for any user.
            removeIdmapIfPossible(info);
        }
        return targets;
    }

    @NonNull
    private Set<PackageAndUser> updateOverlaysForTarget(@NonNull final String targetPackage,
            final int userId, final int flags) {
        boolean modified = false;
        final List<OverlayInfo> overlays = mSettings.getOverlaysForTarget(targetPackage, userId);
        for (int i = 0, n = overlays.size(); i < n; i++) {
            final OverlayInfo oi = overlays.get(i);
            try {
                modified |= updateState(oi, userId, flags);
            } catch (OverlayManagerSettings.BadKeyException e) {
                Slog.e(TAG, "failed to update settings", e);
                modified |= mSettings.remove(oi.getOverlayIdentifier(), userId);
            }
        }
        if (!modified) {
            return Collections.emptySet();
        }
        return Set.of(new PackageAndUser(targetPackage, userId));
    }

    @NonNull
    private Set<PackageAndUser> updatePackageOverlays(@NonNull AndroidPackage pkg,
            final int userId, final int flags) throws OperationFailedException {
        if (pkg.getOverlayTarget() == null) {
            // This package does not have overlays declared in its manifest.
            return Collections.emptySet();
        }

        Set<PackageAndUser> updatedTargets = Collections.emptySet();
        final OverlayIdentifier overlay = new OverlayIdentifier(pkg.getPackageName());
        final int priority = getPackageConfiguredPriority(pkg);
        try {
            OverlayInfo currentInfo = mSettings.getNullableOverlayInfo(overlay, userId);
            if (mustReinitializeOverlay(pkg, currentInfo)) {
                if (currentInfo != null) {
                    // If the targetPackageName has changed, the package that *used* to
                    // be the target must also update its assets.
                    updatedTargets = CollectionUtils.add(updatedTargets,
                            new PackageAndUser(currentInfo.targetPackageName, userId));
                }

                currentInfo = mSettings.init(overlay, userId, pkg.getOverlayTarget(),
                        pkg.getOverlayTargetOverlayableName(), pkg.getBaseApkPath(),
                        isPackageConfiguredMutable(pkg),
                        isPackageConfiguredEnabled(pkg),
                        getPackageConfiguredPriority(pkg), pkg.getOverlayCategory(),
                        false);
            } else if (priority != currentInfo.priority) {
                // Changing the priority of an overlay does not cause its settings to be
                // reinitialized. Reorder the overlay and update its target package.
                mSettings.setPriority(overlay, userId, priority);
                updatedTargets = CollectionUtils.add(updatedTargets,
                        new PackageAndUser(currentInfo.targetPackageName, userId));
            }

            // Update the enabled state of the overlay.
            if (updateState(currentInfo, userId, flags)) {
                updatedTargets = CollectionUtils.add(updatedTargets,
                        new PackageAndUser(currentInfo.targetPackageName, userId));
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            throw new OperationFailedException("failed to update settings", e);
        }
        return updatedTargets;
    }

    @NonNull
    private Set<PackageAndUser> reconcileSettingsForPackage(@NonNull final String pkgName,
            final int userId, final int flags) throws OperationFailedException {
        if (DEBUG) {
            Slog.d(TAG, "reconcileSettingsForPackage pkgName=" + pkgName + " userId=" + userId);
        }

        // Update the state of overlays that target this package.
        Set<PackageAndUser> updatedTargets = Collections.emptySet();
        updatedTargets = CollectionUtils.addAll(updatedTargets,
                updateOverlaysForTarget(pkgName, userId, flags));

        // Realign the overlay settings with PackageManager's view of the package.
        final AndroidPackage pkg = mPackageManager.getPackageForUser(pkgName, userId);
        if (pkg == null) {
            return onPackageRemoved(pkgName, userId);
        }

        // Update the state of the overlays this package declares in its manifest.
        updatedTargets = CollectionUtils.addAll(updatedTargets,
                updatePackageOverlays(pkg, userId, flags));
        return updatedTargets;
    }

    OverlayInfo getOverlayInfo(@NonNull final OverlayIdentifier packageName, final int userId) {
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

    @NonNull
    Set<PackageAndUser> setEnabled(@NonNull final OverlayIdentifier overlay,
            final boolean enable, final int userId) throws OperationFailedException {
        if (DEBUG) {
            Slog.d(TAG, String.format("setEnabled overlay=%s enable=%s userId=%d",
                    overlay, enable, userId));
        }

        try {
            final OverlayInfo oi = mSettings.getOverlayInfo(overlay, userId);
            if (!oi.isMutable) {
                // Ignore immutable overlays.
                throw new OperationFailedException(
                        "cannot enable immutable overlay packages in runtime");
            }

            boolean modified = mSettings.setEnabled(overlay, userId, enable);
            modified |= updateState(oi, userId, 0);

            if (modified) {
                return Set.of(new PackageAndUser(oi.targetPackageName, userId));
            }
            return Set.of();
        } catch (OverlayManagerSettings.BadKeyException e) {
            throw new OperationFailedException("failed to update settings", e);
        }
    }

    Optional<PackageAndUser> setEnabledExclusive(@NonNull final OverlayIdentifier overlay,
            boolean withinCategory, final int userId) throws OperationFailedException {
        if (DEBUG) {
            Slog.d(TAG, String.format("setEnabledExclusive overlay=%s"
                    + " withinCategory=%s userId=%d", overlay, withinCategory, userId));
        }

        try {
            final OverlayInfo enabledInfo = mSettings.getOverlayInfo(overlay, userId);
            if (!enabledInfo.isMutable) {
                throw new OperationFailedException(
                        "cannot enable immutable overlay packages in runtime");
            }

            // Remove the overlay to have enabled from the list of overlays to disable.
            List<OverlayInfo> allOverlays = getOverlayInfosForTarget(enabledInfo.targetPackageName,
                    userId);
            allOverlays.remove(enabledInfo);

            boolean modified = false;
            for (int i = 0; i < allOverlays.size(); i++) {
                final OverlayInfo disabledInfo = allOverlays.get(i);
                final OverlayIdentifier disabledOverlay = disabledInfo.getOverlayIdentifier();
                if (!disabledInfo.isMutable) {
                    // Don't touch immutable overlays.
                    continue;
                }
                if (withinCategory && !Objects.equals(disabledInfo.category,
                        enabledInfo.category)) {
                    // Don't touch overlays from other categories.
                    continue;
                }

                // Disable the overlay.
                modified |= mSettings.setEnabled(disabledOverlay, userId, false);
                modified |= updateState(disabledInfo, userId, 0);
            }

            // Enable the selected overlay.
            modified |= mSettings.setEnabled(overlay, userId, true);
            modified |= updateState(enabledInfo, userId, 0);

            if (modified) {
                return Optional.of(new PackageAndUser(enabledInfo.targetPackageName, userId));
            }
            return Optional.empty();
        } catch (OverlayManagerSettings.BadKeyException e) {
            throw new OperationFailedException("failed to update settings", e);
        }
    }

    @NonNull
    Set<PackageAndUser> registerFabricatedOverlay(
            @NonNull final FabricatedOverlayInternal overlay)
            throws OperationFailedException {
        if (FrameworkParsingPackageUtils.validateName(overlay.overlayName,
                false /* requireSeparator */, true /* requireFilename */) != null) {
            throw new OperationFailedException(
                    "overlay name can only consist of alphanumeric characters, '_', and '.'");
        }

        final FabricatedOverlayInfo info = mIdmapManager.createFabricatedOverlay(overlay);
        if (info == null) {
            throw new OperationFailedException("failed to create fabricated overlay");
        }

        final Set<PackageAndUser> updatedTargets = new ArraySet<>();
        for (int userId : mSettings.getUsers()) {
            updatedTargets.addAll(registerFabricatedOverlay(info, userId));
        }
        return updatedTargets;
    }

    @NonNull
    private Set<PackageAndUser> registerFabricatedOverlay(
            @NonNull final FabricatedOverlayInfo info, int userId)
            throws OperationFailedException {
        final OverlayIdentifier overlayIdentifier = new OverlayIdentifier(
                info.packageName, info.overlayName);
        final Set<PackageAndUser> updatedTargets = new ArraySet<>();
        OverlayInfo oi = mSettings.getNullableOverlayInfo(overlayIdentifier, userId);
        if (oi != null) {
            if (!oi.isFabricated) {
                throw new OperationFailedException("non-fabricated overlay with name '" +
                        oi.overlayName + "' already present in '" + oi.packageName + "'");
            }
        }
        try {
            if (mustReinitializeOverlay(info, oi)) {
                if (oi != null) {
                    // If the fabricated overlay changes its target package, update the previous
                    // target package so it no longer is overlaid.
                    updatedTargets.add(new PackageAndUser(oi.targetPackageName, userId));
                }
                oi = mSettings.init(overlayIdentifier, userId, info.targetPackageName,
                        info.targetOverlayable, info.path, true, false,
                        OverlayConfig.DEFAULT_PRIORITY, null, true);
            } else {
                // The only non-critical part of the info that will change is path to the fabricated
                // overlay.
                mSettings.setBaseCodePath(overlayIdentifier, userId, info.path);
            }
            if (updateState(oi, userId, 0)) {
                updatedTargets.add(new PackageAndUser(oi.targetPackageName, userId));
            }
        } catch (OverlayManagerSettings.BadKeyException e) {
            throw new OperationFailedException("failed to update settings", e);
        }

        return updatedTargets;
    }

    @NonNull
    Set<PackageAndUser> unregisterFabricatedOverlay(@NonNull final OverlayIdentifier overlay) {
        final Set<PackageAndUser> updatedTargets = new ArraySet<>();
        for (int userId : mSettings.getUsers()) {
            updatedTargets.addAll(unregisterFabricatedOverlay(overlay, userId));
        }
        return updatedTargets;
    }

    @NonNull
    private Set<PackageAndUser> unregisterFabricatedOverlay(
            @NonNull final OverlayIdentifier overlay, int userId) {
        final OverlayInfo oi = mSettings.getNullableOverlayInfo(overlay, userId);
        if (oi != null) {
            mSettings.remove(overlay, userId);
            if (oi.isEnabled()) {
                // Removing a fabricated overlay only changes the overlay path of a package if it is
                // currently enabled.
                return Set.of(new PackageAndUser(oi.targetPackageName, userId));
            }
        }
        return Set.of();
    }


    private void cleanStaleResourceCache() {
        // Clean up fabricated overlays that are no longer registered in any user.
        final Set<String> fabricatedPaths = mSettings.getAllBaseCodePaths();
        for (final FabricatedOverlayInfo info : mIdmapManager.getFabricatedOverlayInfos()) {
            if (!fabricatedPaths.contains(info.path)) {
                mIdmapManager.deleteFabricatedOverlay(info.path);
            }
        }
    }

    /**
     * Retrieves information about the fabricated overlays still in use.
     * @return
     */
    @NonNull
    private List<FabricatedOverlayInfo> getFabricatedOverlayInfos() {
        final Set<String> fabricatedPaths = mSettings.getAllBaseCodePaths();
        // Filter out stale fabricated overlays.
        final ArrayList<FabricatedOverlayInfo> infos = new ArrayList<>(
                mIdmapManager.getFabricatedOverlayInfos());
        infos.removeIf(info -> !fabricatedPaths.contains(info.path));
        return infos;
    }

    private boolean isPackageConfiguredMutable(@NonNull final AndroidPackage overlay) {
        // TODO(162841629): Support overlay name in OverlayConfig
        return mOverlayConfig.isMutable(overlay.getPackageName());
    }

    private int getPackageConfiguredPriority(@NonNull final AndroidPackage overlay) {
        // TODO(162841629): Support overlay name in OverlayConfig
        return mOverlayConfig.getPriority(overlay.getPackageName());
    }

    private boolean isPackageConfiguredEnabled(@NonNull final AndroidPackage overlay) {
        // TODO(162841629): Support overlay name in OverlayConfig
        return mOverlayConfig.isEnabled(overlay.getPackageName());
    }

    Optional<PackageAndUser> setPriority(@NonNull final OverlayIdentifier overlay,
            @NonNull final OverlayIdentifier newParentOverlay, final int userId)
            throws OperationFailedException {
        try {
            if (DEBUG) {
                Slog.d(TAG, "setPriority overlay=" + overlay + " newParentOverlay="
                        + newParentOverlay + " userId=" + userId);
            }

            final OverlayInfo overlayInfo = mSettings.getOverlayInfo(overlay, userId);
            if (!overlayInfo.isMutable) {
                // Ignore immutable overlays.
                throw new OperationFailedException(
                        "cannot change priority of an immutable overlay package at runtime");
            }

            if (mSettings.setPriority(overlay, newParentOverlay, userId)) {
                return Optional.of(new PackageAndUser(overlayInfo.targetPackageName, userId));
            }
            return Optional.empty();
        } catch (OverlayManagerSettings.BadKeyException e) {
            throw new OperationFailedException("failed to update settings", e);
        }
    }

    Set<PackageAndUser> setHighestPriority(@NonNull final OverlayIdentifier overlay,
            final int userId) throws OperationFailedException {
        try{
            if (DEBUG) {
                Slog.d(TAG, "setHighestPriority overlay=" + overlay + " userId=" + userId);
            }

            final OverlayInfo overlayInfo = mSettings.getOverlayInfo(overlay, userId);
            if (!overlayInfo.isMutable) {
                // Ignore immutable overlays.
                throw new OperationFailedException(
                        "cannot change priority of an immutable overlay package at runtime");
            }

            if (mSettings.setHighestPriority(overlay, userId)) {
                return Set.of(new PackageAndUser(overlayInfo.targetPackageName, userId));
            }
            return Set.of();
        } catch (OverlayManagerSettings.BadKeyException e) {
            throw new OperationFailedException("failed to update settings", e);
        }
    }

    Optional<PackageAndUser> setLowestPriority(@NonNull final OverlayIdentifier overlay,
            final int userId) throws OperationFailedException {
        try{
            if (DEBUG) {
                Slog.d(TAG, "setLowestPriority packageName=" + overlay + " userId=" + userId);
            }

            final OverlayInfo overlayInfo = mSettings.getOverlayInfo(overlay, userId);
            if (!overlayInfo.isMutable) {
                // Ignore immutable overlays.
                throw new OperationFailedException(
                        "cannot change priority of an immutable overlay package at runtime");
            }

            if (mSettings.setLowestPriority(overlay, userId)) {
                return Optional.of(new PackageAndUser(overlayInfo.targetPackageName, userId));
            }
            return Optional.empty();
        } catch (OverlayManagerSettings.BadKeyException e) {
            throw new OperationFailedException("failed to update settings", e);
        }
    }

    void dump(@NonNull final PrintWriter pw, @NonNull DumpState dumpState) {
        Pair<OverlayIdentifier, String> overlayIdmap = null;
        if (dumpState.getPackageName() != null) {
            OverlayIdentifier id = new OverlayIdentifier(dumpState.getPackageName(),
                    dumpState.getOverlayName());
            OverlayInfo oi = mSettings.getNullableOverlayInfo(id, USER_SYSTEM);
            if (oi != null) {
                overlayIdmap = new Pair<>(id, oi.baseCodePath);
            }
        }

        // settings
        mSettings.dump(pw, dumpState);

        // idmap data
        if (dumpState.getField() == null) {
            Set<Pair<OverlayIdentifier, String>> allIdmaps = (overlayIdmap != null)
                    ? Set.of(overlayIdmap) : mSettings.getAllIdentifiersAndBaseCodePaths();
            for (Pair<OverlayIdentifier, String> pair : allIdmaps) {
                pw.println("IDMAP OF " + pair.first);
                String dump = mIdmapManager.dumpIdmap(pair.second);
                if (dump != null) {
                    pw.println(dump);
                } else {
                    OverlayInfo oi = mSettings.getNullableOverlayInfo(pair.first, USER_SYSTEM);
                    pw.println((oi != null && !mIdmapManager.idmapExists(oi))
                            ? "<missing idmap>" : "<internal error>");
                }
            }
        }

        // default overlays
        if (overlayIdmap == null) {
            pw.println("Default overlays: " + TextUtils.join(";", mDefaultOverlays));
        }

        // overlay configurations
        if (dumpState.getPackageName() == null) {
            mOverlayConfig.dump(pw);
        }
    }

    @NonNull String[] getDefaultOverlayPackages() {
        return mDefaultOverlays;
    }

    void removeIdmapForOverlay(OverlayIdentifier overlay, int userId)
            throws OperationFailedException {
        try {
            final OverlayInfo oi = mSettings.getOverlayInfo(overlay, userId);
            removeIdmapIfPossible(oi);
        } catch (OverlayManagerSettings.BadKeyException e) {
            throw new OperationFailedException("failed to update settings", e);
        }
    }

    OverlayPaths getEnabledOverlayPaths(@NonNull final String targetPackageName,
            final int userId, boolean includeImmutableOverlays) {
        final List<OverlayInfo> overlays = mSettings.getOverlaysForTarget(targetPackageName,
                userId);
        final OverlayPaths.Builder paths = new OverlayPaths.Builder();
        final int n = overlays.size();
        for (int i = 0; i < n; i++) {
            final OverlayInfo oi = overlays.get(i);
            if (!oi.isEnabled()) {
                continue;
            }
            if (!includeImmutableOverlays && !oi.isMutable) {
                continue;
            }
            if (oi.isFabricated()) {
                paths.addNonApkPath(oi.baseCodePath);
            } else {
                paths.addApkPath(oi.baseCodePath);
            }
        }
        return paths.build();
    }

    /**
     * Returns true if the settings/state was modified, false otherwise.
     */
    private boolean updateState(@NonNull final CriticalOverlayInfo info,
            final int userId, final int flags) throws OverlayManagerSettings.BadKeyException {
        final OverlayIdentifier overlay = info.getOverlayIdentifier();
        final AndroidPackage targetPackage = mPackageManager.getPackageForUser(
                info.getTargetPackageName(), userId);
        final AndroidPackage overlayPackage = mPackageManager.getPackageForUser(
                info.getPackageName(), userId);

        boolean modified = false;
        if (overlayPackage == null) {
            removeIdmapIfPossible(mSettings.getOverlayInfo(overlay, userId));
            return mSettings.remove(overlay, userId);
        }

        modified |= mSettings.setCategory(overlay, userId, overlayPackage.getOverlayCategory());
        if (!info.isFabricated()) {
            modified |= mSettings.setBaseCodePath(overlay, userId, overlayPackage.getBaseApkPath());
        }

        // Immutable RROs targeting to "android", ie framework-res.apk, are handled by native
        // layers.
        final OverlayInfo updatedOverlayInfo = mSettings.getOverlayInfo(overlay, userId);
        @IdmapManager.IdmapStatus int idmapStatus = IDMAP_NOT_EXIST;
        if (targetPackage != null && !("android".equals(info.getTargetPackageName())
                && !isPackageConfiguredMutable(overlayPackage))) {
            idmapStatus = mIdmapManager.createIdmap(targetPackage,
                    overlayPackage, updatedOverlayInfo.baseCodePath, overlay.getOverlayName(),
                    userId);
            modified |= (idmapStatus & IDMAP_IS_MODIFIED) != 0;
        }

        final @OverlayInfo.State int currentState = mSettings.getState(overlay, userId);
        final @OverlayInfo.State int newState = calculateNewState(updatedOverlayInfo, targetPackage,
                userId, flags, idmapStatus);
        if (currentState != newState) {
            if (DEBUG) {
                Slog.d(TAG, String.format("%s:%d: %s -> %s",
                        overlay, userId,
                        OverlayInfo.stateToString(currentState),
                        OverlayInfo.stateToString(newState)));
            }
            modified |= mSettings.setState(overlay, userId, newState);
        }

        return modified;
    }

    private @OverlayInfo.State int calculateNewState(@NonNull final OverlayInfo info,
            @Nullable final AndroidPackage targetPackage, final int userId, final int flags,
            @IdmapManager.IdmapStatus final int idmapStatus)
            throws OverlayManagerSettings.BadKeyException {
        if ((flags & FLAG_TARGET_IS_BEING_REPLACED) != 0) {
            return STATE_TARGET_IS_BEING_REPLACED;
        }

        if ((flags & FLAG_OVERLAY_IS_BEING_REPLACED) != 0) {
            return STATE_OVERLAY_IS_BEING_REPLACED;
        }

        if (targetPackage == null) {
            return STATE_MISSING_TARGET;
        }

        if ((idmapStatus & IDMAP_IS_VERIFIED) == 0) {
            if (!mIdmapManager.idmapExists(info)) {
                return STATE_NO_IDMAP;
            }
        }

        final boolean enabled = mSettings.getEnabled(info.getOverlayIdentifier(), userId);
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
                final OverlayInfo tmp = mSettings.getOverlayInfo(oi.getOverlayIdentifier(), userId);
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

    static final class OperationFailedException extends Exception {
        OperationFailedException(@NonNull final String message) {
            super(message);
        }

        OperationFailedException(@NonNull final String message, @NonNull Throwable cause) {
            super(message, cause);
        }
    }
}
