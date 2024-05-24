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
package com.android.systemui.theme;

import static com.android.systemui.shared.Flags.enableHomeDelay;

import android.annotation.AnyThread;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;

import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Responsible for orchestrating overlays, based on user preferences and other inputs from
 * {@link ThemeOverlayController}.
 */
@SysUISingleton
public class ThemeOverlayApplier implements Dumpable {
    private static final String TAG = "ThemeOverlayApplier";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @VisibleForTesting
    static final String ANDROID_PACKAGE = "android";
    @VisibleForTesting
    static final String SETTINGS_PACKAGE = "com.android.settings";
    @VisibleForTesting
    static final String SYSUI_PACKAGE = "com.android.systemui";

    static final String OVERLAY_CATEGORY_DYNAMIC_COLOR =
            "android.theme.customization.dynamic_color";
    static final String OVERLAY_CATEGORY_ACCENT_COLOR =
            "android.theme.customization.accent_color";
    static final String OVERLAY_CATEGORY_SYSTEM_PALETTE =
            "android.theme.customization.system_palette";
    static final String OVERLAY_CATEGORY_THEME_STYLE =
            "android.theme.customization.theme_style";

    static final String OVERLAY_COLOR_SOURCE = "android.theme.customization.color_source";

    static final String OVERLAY_COLOR_INDEX = "android.theme.customization.color_index";

    static final String OVERLAY_COLOR_BOTH = "android.theme.customization.color_both";

    static final String COLOR_SOURCE_PRESET = "preset";

    static final String COLOR_SOURCE_HOME = "home_wallpaper";

    static final String COLOR_SOURCE_LOCK = "lock_wallpaper";

    static final String TIMESTAMP_FIELD = "_applied_timestamp";

    @VisibleForTesting
    static final String OVERLAY_CATEGORY_FONT = "android.theme.customization.font";
    @VisibleForTesting
    static final String OVERLAY_CATEGORY_SHAPE =
            "android.theme.customization.adaptive_icon_shape";
    @VisibleForTesting
    static final String OVERLAY_CATEGORY_ICON_ANDROID =
            "android.theme.customization.icon_pack.android";
    @VisibleForTesting
    static final String OVERLAY_CATEGORY_ICON_SYSUI =
            "android.theme.customization.icon_pack.systemui";
    @VisibleForTesting
    static final String OVERLAY_CATEGORY_ICON_SETTINGS =
            "android.theme.customization.icon_pack.settings";
    @VisibleForTesting
    static final String OVERLAY_CATEGORY_ICON_LAUNCHER =
            "android.theme.customization.icon_pack.launcher";
    @VisibleForTesting
    static final String OVERLAY_CATEGORY_ICON_THEME_PICKER =
            "android.theme.customization.icon_pack.themepicker";

    /*
     * All theme customization categories used by the system, in order that they should be applied,
     * starts with launcher and grouped by target package.
     */
    static final List<String> THEME_CATEGORIES = Lists.newArrayList(
            OVERLAY_CATEGORY_SYSTEM_PALETTE,
            OVERLAY_CATEGORY_ICON_LAUNCHER,
            OVERLAY_CATEGORY_SHAPE,
            OVERLAY_CATEGORY_FONT,
            OVERLAY_CATEGORY_ACCENT_COLOR,
            OVERLAY_CATEGORY_DYNAMIC_COLOR,
            OVERLAY_CATEGORY_ICON_ANDROID,
            OVERLAY_CATEGORY_ICON_SYSUI,
            OVERLAY_CATEGORY_ICON_SETTINGS,
            OVERLAY_CATEGORY_ICON_THEME_PICKER);

    /* Categories that need to be applied to the current user as well as the system user. */
    @VisibleForTesting
    static final Set<String> SYSTEM_USER_CATEGORIES = Sets.newHashSet(
            OVERLAY_CATEGORY_SYSTEM_PALETTE,
            OVERLAY_CATEGORY_ACCENT_COLOR,
            OVERLAY_CATEGORY_DYNAMIC_COLOR,
            OVERLAY_CATEGORY_FONT,
            OVERLAY_CATEGORY_SHAPE,
            OVERLAY_CATEGORY_ICON_ANDROID,
            OVERLAY_CATEGORY_ICON_SYSUI);

    /* Allowed overlay categories for each target package. */
    private final Map<String, Set<String>> mTargetPackageToCategories = new ArrayMap<>();
    /* Target package for each overlay category. */
    private final Map<String, String> mCategoryToTargetPackage = new ArrayMap<>();
    private final OverlayManager mOverlayManager;
    private final Executor mBgExecutor;
    private final Executor mMainExecutor;
    private final String mLauncherPackage;
    private final String mThemePickerPackage;

    @Inject
    public ThemeOverlayApplier(OverlayManager overlayManager,
            @Background Executor bgExecutor,
            @Named(ThemeModule.LAUNCHER_PACKAGE) String launcherPackage,
            @Named(ThemeModule.THEME_PICKER_PACKAGE) String themePickerPackage,
            DumpManager dumpManager,
            @Main Executor mainExecutor) {
        mOverlayManager = overlayManager;
        mBgExecutor = bgExecutor;
        mMainExecutor = mainExecutor;
        mLauncherPackage = launcherPackage;
        mThemePickerPackage = themePickerPackage;
        mTargetPackageToCategories.put(ANDROID_PACKAGE, Sets.newHashSet(
                OVERLAY_CATEGORY_SYSTEM_PALETTE, OVERLAY_CATEGORY_ACCENT_COLOR,
                OVERLAY_CATEGORY_DYNAMIC_COLOR,
                OVERLAY_CATEGORY_FONT, OVERLAY_CATEGORY_SHAPE,
                OVERLAY_CATEGORY_ICON_ANDROID));
        mTargetPackageToCategories.put(SYSUI_PACKAGE,
                Sets.newHashSet(OVERLAY_CATEGORY_ICON_SYSUI));
        mTargetPackageToCategories.put(SETTINGS_PACKAGE,
                Sets.newHashSet(OVERLAY_CATEGORY_ICON_SETTINGS));
        mTargetPackageToCategories.put(mLauncherPackage,
                Sets.newHashSet(OVERLAY_CATEGORY_ICON_LAUNCHER));
        mTargetPackageToCategories.put(mThemePickerPackage,
                Sets.newHashSet(OVERLAY_CATEGORY_ICON_THEME_PICKER));
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_ACCENT_COLOR, ANDROID_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_DYNAMIC_COLOR, ANDROID_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_FONT, ANDROID_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_SHAPE, ANDROID_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_ICON_ANDROID, ANDROID_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_ICON_SYSUI, SYSUI_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_ICON_SETTINGS, SETTINGS_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_ICON_LAUNCHER, mLauncherPackage);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_ICON_THEME_PICKER, mThemePickerPackage);

        dumpManager.registerDumpable(TAG, this);
    }

    /**
     * Apply the set of overlay packages to the set of {@code UserHandle}s provided. Overlays that
     * affect sysui will also be applied to the system user.
     *
     * @param categoryToPackage Overlay packages to be applied
     * @param pendingCreation Overlays yet to be created
     * @param currentUser Current User ID
     * @param managedProfiles Profiles get overlays
     * @param onComplete Callback for when resources are ready. Runs in the main thread.
     */
    public void applyCurrentUserOverlays(
            Map<String, OverlayIdentifier> categoryToPackage,
            FabricatedOverlay[] pendingCreation,
            int currentUser,
            Set<UserHandle> managedProfiles,
            Runnable onComplete
    ) {

        mBgExecutor.execute(() -> {

            // Disable all overlays that have not been specified in the user setting.
            final Set<String> overlayCategoriesToDisable = new HashSet<>(THEME_CATEGORIES);
            final Set<String> targetPackagesToQuery = overlayCategoriesToDisable.stream()
                    .map(category -> mCategoryToTargetPackage.get(category))
                    .collect(Collectors.toSet());
            final List<OverlayInfo> overlays = new ArrayList<>();
            targetPackagesToQuery.forEach(targetPackage -> overlays.addAll(mOverlayManager
                    .getOverlayInfosForTarget(targetPackage, UserHandle.SYSTEM)));
            final List<Pair<String, String>> overlaysToDisable = overlays.stream()
                    .filter(o ->
                            mTargetPackageToCategories.get(o.targetPackageName).contains(
                                    o.category))
                    .filter(o -> overlayCategoriesToDisable.contains(o.category))
                    .filter(o -> !categoryToPackage.containsValue(
                            new OverlayIdentifier(o.packageName)))
                    .filter(o -> o.isEnabled())
                    .map(o -> new Pair<>(o.category, o.packageName))
                    .collect(Collectors.toList());

            OverlayManagerTransaction.Builder transaction = getTransactionBuilder();
            HashSet<OverlayIdentifier> identifiersPending = new HashSet<>();
            if (pendingCreation != null) {
                for (FabricatedOverlay overlay : pendingCreation) {
                    identifiersPending.add(overlay.getIdentifier());
                    transaction.registerFabricatedOverlay(overlay);
                }
            }

            for (Pair<String, String> packageToDisable : overlaysToDisable) {
                OverlayIdentifier overlayInfo = new OverlayIdentifier(packageToDisable.second);
                setEnabled(transaction, overlayInfo, packageToDisable.first, currentUser,
                        managedProfiles, false, identifiersPending.contains(overlayInfo));
            }

            for (String category : THEME_CATEGORIES) {
                if (categoryToPackage.containsKey(category)) {
                    OverlayIdentifier overlayInfo = categoryToPackage.get(category);
                    setEnabled(transaction, overlayInfo, category, currentUser, managedProfiles,
                            true, identifiersPending.contains(overlayInfo));
                }
            }

            try {
                mOverlayManager.commit(transaction.build());
                if (enableHomeDelay() && onComplete != null) {
                    Log.d(TAG, "Executing onComplete runnable");
                    mMainExecutor.execute(onComplete);
                }
            } catch (SecurityException | IllegalStateException e) {
                Log.e(TAG, "setEnabled failed", e);
            }
        });
    }

    @VisibleForTesting
    protected OverlayManagerTransaction.Builder getTransactionBuilder() {
        return new OverlayManagerTransaction.Builder();
    }

    @AnyThread
    private void setEnabled(OverlayManagerTransaction.Builder transaction,
            OverlayIdentifier identifier, String category, int currentUser,
            Set<UserHandle> managedProfiles, boolean enabled, boolean pendingCreation) {
        if (DEBUG) {
            Log.d(TAG, "setEnabled: " + identifier.getPackageName() + " category: "
                    + category + ": " + enabled);
        }

        OverlayInfo overlayInfo = mOverlayManager.getOverlayInfo(identifier,
                UserHandle.of(currentUser));
        if (overlayInfo == null && !pendingCreation) {
            Log.i(TAG, "Won't enable " + identifier + ", it doesn't exist for user"
                    + currentUser);
            return;
        }

        transaction.setEnabled(identifier, enabled, currentUser);
        if (currentUser != UserHandle.SYSTEM.getIdentifier()
                && SYSTEM_USER_CATEGORIES.contains(category)) {
            transaction.setEnabled(identifier, enabled, UserHandle.SYSTEM.getIdentifier());
        }

        // Do not apply Launcher or Theme picker overlays to managed users. Apps are not
        // installed in there.
        overlayInfo = mOverlayManager.getOverlayInfo(identifier, UserHandle.SYSTEM);
        if (overlayInfo == null || overlayInfo.targetPackageName.equals(mLauncherPackage)
                || overlayInfo.targetPackageName.equals(mThemePickerPackage)) {
            return;
        }

        for (UserHandle userHandle : managedProfiles) {
            transaction.setEnabled(identifier, enabled, userHandle.getIdentifier());
        }
    }

    /**
     * @inherit
     */
    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mTargetPackageToCategories=" + mTargetPackageToCategories);
        pw.println("mCategoryToTargetPackage=" + mCategoryToTargetPackage);
    }
}
