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

import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

class ThemeOverlayManager {
    private static final String TAG = "ThemeOverlayManager";
    private static final boolean DEBUG = false;

    @VisibleForTesting
    static final String ANDROID_PACKAGE = "android";
    @VisibleForTesting
    static final String SETTINGS_PACKAGE = "com.android.settings";
    @VisibleForTesting
    static final String SYSUI_PACKAGE = "com.android.systemui";

    @VisibleForTesting
    static final String OVERLAY_CATEGORY_COLOR = "android.theme.customization.accent_color";
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
            OVERLAY_CATEGORY_ICON_LAUNCHER,
            OVERLAY_CATEGORY_SHAPE,
            OVERLAY_CATEGORY_FONT,
            OVERLAY_CATEGORY_COLOR,
            OVERLAY_CATEGORY_ICON_ANDROID,
            OVERLAY_CATEGORY_ICON_SYSUI,
            OVERLAY_CATEGORY_ICON_SETTINGS,
            OVERLAY_CATEGORY_ICON_THEME_PICKER);

    /* Categories that need to applied to the current user as well as the system user. */
    @VisibleForTesting
    static final Set<String> SYSTEM_USER_CATEGORIES = Sets.newHashSet(
            OVERLAY_CATEGORY_COLOR,
            OVERLAY_CATEGORY_FONT,
            OVERLAY_CATEGORY_SHAPE,
            OVERLAY_CATEGORY_ICON_ANDROID,
            OVERLAY_CATEGORY_ICON_SYSUI);

    /* Allowed overlay categories for each target package. */
    private final Map<String, Set<String>> mTargetPackageToCategories = new ArrayMap<>();
    /* Target package for each overlay category. */
    private final Map<String, String> mCategoryToTargetPackage = new ArrayMap<>();
    private final OverlayManager mOverlayManager;
    private final Executor mExecutor;
    private final String mLauncherPackage;
    private final String mThemePickerPackage;

    ThemeOverlayManager(OverlayManager overlayManager, Executor executor,
            String launcherPackage, String themePickerPackage) {
        mOverlayManager = overlayManager;
        mExecutor = executor;
        mLauncherPackage = launcherPackage;
        mThemePickerPackage = themePickerPackage;
        mTargetPackageToCategories.put(ANDROID_PACKAGE, Sets.newHashSet(
                OVERLAY_CATEGORY_COLOR, OVERLAY_CATEGORY_FONT,
                OVERLAY_CATEGORY_SHAPE, OVERLAY_CATEGORY_ICON_ANDROID));
        mTargetPackageToCategories.put(SYSUI_PACKAGE,
                Sets.newHashSet(OVERLAY_CATEGORY_ICON_SYSUI));
        mTargetPackageToCategories.put(SETTINGS_PACKAGE,
                Sets.newHashSet(OVERLAY_CATEGORY_ICON_SETTINGS));
        mTargetPackageToCategories.put(mLauncherPackage,
                Sets.newHashSet(OVERLAY_CATEGORY_ICON_LAUNCHER));
        mTargetPackageToCategories.put(mThemePickerPackage,
                Sets.newHashSet(OVERLAY_CATEGORY_ICON_THEME_PICKER));
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_COLOR, ANDROID_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_FONT, ANDROID_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_SHAPE, ANDROID_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_ICON_ANDROID, ANDROID_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_ICON_SYSUI, SYSUI_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_ICON_SETTINGS, SETTINGS_PACKAGE);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_ICON_LAUNCHER, mLauncherPackage);
        mCategoryToTargetPackage.put(OVERLAY_CATEGORY_ICON_THEME_PICKER, mThemePickerPackage);
    }

    /**
     * Apply the set of overlay packages to the set of {@code UserHandle}s provided. Overlays that
     * affect sysui will also be applied to the system user.
     */
    void applyCurrentUserOverlays(
            Map<String, String> categoryToPackage, Set<UserHandle> userHandles) {
        // Disable all overlays that have not been specified in the user setting.
        final Set<String> overlayCategoriesToDisable = new HashSet<>(THEME_CATEGORIES);
        overlayCategoriesToDisable.removeAll(categoryToPackage.keySet());
        final Set<String> targetPackagesToQuery = overlayCategoriesToDisable.stream()
                .map(category -> mCategoryToTargetPackage.get(category))
                .collect(Collectors.toSet());
        final List<OverlayInfo> overlays = new ArrayList<>();
        targetPackagesToQuery.forEach(targetPackage -> overlays.addAll(mOverlayManager
                .getOverlayInfosForTarget(targetPackage, UserHandle.SYSTEM)));
        final Map<String, String> overlaysToDisable = overlays.stream()
                .filter(o ->
                        mTargetPackageToCategories.get(o.targetPackageName).contains(o.category))
                .filter(o -> overlayCategoriesToDisable.contains(o.category))
                .filter(o -> o.isEnabled())
                .collect(Collectors.toMap((o) -> o.category, (o) -> o.packageName));

        // Toggle overlays in the order of THEME_CATEGORIES.
        for (String category : THEME_CATEGORIES) {
            if (categoryToPackage.containsKey(category)) {
                setEnabled(categoryToPackage.get(category), category, userHandles, true);
            } else if (overlaysToDisable.containsKey(category)) {
                setEnabled(overlaysToDisable.get(category), category, userHandles, false);
            }
        }
    }

    private void setEnabled(
            String packageName, String category, Set<UserHandle> handles, boolean enabled) {
        for (UserHandle userHandle : handles) {
            setEnabledAsync(packageName, userHandle, enabled);
        }
        if (!handles.contains(UserHandle.SYSTEM) && SYSTEM_USER_CATEGORIES.contains(category)) {
            setEnabledAsync(packageName, UserHandle.SYSTEM, enabled);
        }
    }

    private void setEnabledAsync(String pkg, UserHandle userHandle, boolean enabled) {
        mExecutor.execute(() -> {
            if (DEBUG) Log.d(TAG, String.format("setEnabled: %s %s %b", pkg, userHandle, enabled));
            try {
                if (enabled) {
                    mOverlayManager.setEnabledExclusiveInCategory(pkg, userHandle);
                } else {
                    mOverlayManager.setEnabled(pkg, false, userHandle);
                }
            } catch (SecurityException | IllegalStateException e) {
                Log.e(TAG,
                        String.format("setEnabled failed: %s %s %b", pkg, userHandle, enabled), e);
            }
        });
    }
}
