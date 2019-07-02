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

import static com.android.systemui.theme.ThemeOverlayManager.ANDROID_PACKAGE;
import static com.android.systemui.theme.ThemeOverlayManager.OVERLAY_CATEGORY_COLOR;
import static com.android.systemui.theme.ThemeOverlayManager.OVERLAY_CATEGORY_FONT;
import static com.android.systemui.theme.ThemeOverlayManager.OVERLAY_CATEGORY_ICON_ANDROID;
import static com.android.systemui.theme.ThemeOverlayManager.OVERLAY_CATEGORY_ICON_LAUNCHER;
import static com.android.systemui.theme.ThemeOverlayManager.OVERLAY_CATEGORY_ICON_SETTINGS;
import static com.android.systemui.theme.ThemeOverlayManager.OVERLAY_CATEGORY_ICON_SYSUI;
import static com.android.systemui.theme.ThemeOverlayManager.OVERLAY_CATEGORY_ICON_THEME_PICKER;
import static com.android.systemui.theme.ThemeOverlayManager.OVERLAY_CATEGORY_SHAPE;
import static com.android.systemui.theme.ThemeOverlayManager.SETTINGS_PACKAGE;
import static com.android.systemui.theme.ThemeOverlayManager.SYSTEM_USER_CATEGORIES;
import static com.android.systemui.theme.ThemeOverlayManager.SYSUI_PACKAGE;
import static com.android.systemui.theme.ThemeOverlayManager.THEME_CATEGORIES;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import com.google.android.collect.Maps;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ThemeOverlayManagerTest extends SysuiTestCase {
    private static final String TEST_DISABLED_PREFIX = "com.example.";
    private static final String TEST_ENABLED_PREFIX = "com.example.enabled.";

    private static final Map<String, String> ALL_CATEGORIES_MAP = Maps.newArrayMap();

    static {
        for (String category : THEME_CATEGORIES) {
            ALL_CATEGORIES_MAP.put(category, TEST_DISABLED_PREFIX + category);
        }
    }

    private static final String THEMEPICKER_PACKAGE = "com.android.wallpaper";
    private static final String LAUNCHER_PACKAGE = "com.android.launcher3";
    private static final UserHandle TEST_USER = UserHandle.of(5);
    private static final Set<UserHandle> TEST_USER_HANDLES = Sets.newHashSet(TEST_USER);

    @Mock
    OverlayManager mOverlayManager;

    private ThemeOverlayManager mManager;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mManager = new ThemeOverlayManager(mOverlayManager, MoreExecutors.directExecutor(),
                LAUNCHER_PACKAGE, THEMEPICKER_PACKAGE);
        when(mOverlayManager.getOverlayInfosForTarget(ANDROID_PACKAGE, UserHandle.SYSTEM))
                .thenReturn(Lists.newArrayList(
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_COLOR,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_COLOR, false),
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_FONT,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_FONT, false),
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_SHAPE,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_SHAPE, false),
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_ICON_ANDROID,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_ICON_ANDROID, false),
                        createOverlayInfo(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_COLOR,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_COLOR, true),
                        createOverlayInfo(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_FONT,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_FONT, true),
                        createOverlayInfo(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_SHAPE,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_SHAPE, true),
                        createOverlayInfo(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_ICON_ANDROID,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_ICON_ANDROID, true)));
        when(mOverlayManager.getOverlayInfosForTarget(SYSUI_PACKAGE, UserHandle.SYSTEM))
                .thenReturn(Lists.newArrayList(
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_ICON_SYSUI,
                                SYSUI_PACKAGE, OVERLAY_CATEGORY_ICON_SYSUI, false),
                        createOverlayInfo(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_ICON_SYSUI,
                                SYSUI_PACKAGE, OVERLAY_CATEGORY_ICON_SYSUI, true)));
        when(mOverlayManager.getOverlayInfosForTarget(SETTINGS_PACKAGE, UserHandle.SYSTEM))
                .thenReturn(Lists.newArrayList(
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_ICON_SETTINGS,
                                SETTINGS_PACKAGE, OVERLAY_CATEGORY_ICON_SETTINGS, false),
                        createOverlayInfo(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_ICON_SETTINGS,
                                SETTINGS_PACKAGE, OVERLAY_CATEGORY_ICON_SETTINGS, true)));
        when(mOverlayManager.getOverlayInfosForTarget(LAUNCHER_PACKAGE, UserHandle.SYSTEM))
                .thenReturn(Lists.newArrayList(
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_ICON_LAUNCHER,
                                LAUNCHER_PACKAGE, OVERLAY_CATEGORY_ICON_LAUNCHER, false),
                        createOverlayInfo(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_ICON_LAUNCHER,
                                LAUNCHER_PACKAGE, OVERLAY_CATEGORY_ICON_LAUNCHER, true)));
        when(mOverlayManager.getOverlayInfosForTarget(THEMEPICKER_PACKAGE, UserHandle.SYSTEM))
                .thenReturn(Lists.newArrayList(
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_ICON_THEME_PICKER,
                                THEMEPICKER_PACKAGE, OVERLAY_CATEGORY_ICON_THEME_PICKER, false),
                        createOverlayInfo(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_ICON_THEME_PICKER,
                                THEMEPICKER_PACKAGE, OVERLAY_CATEGORY_ICON_THEME_PICKER, true)));
    }

    @Test
    public void allCategoriesSpecified_allEnabledExclusively() {
        mManager.applyCurrentUserOverlays(ALL_CATEGORIES_MAP, TEST_USER_HANDLES);

        for (String overlayPackage : ALL_CATEGORIES_MAP.values()) {
            verify(mOverlayManager).setEnabledExclusiveInCategory(overlayPackage, TEST_USER);
        }
    }

    @Test
    public void allCategoriesSpecified_sysuiCategoriesAlsoAppliedToSysuiUser() {
        mManager.applyCurrentUserOverlays(ALL_CATEGORIES_MAP, TEST_USER_HANDLES);

        for (Map.Entry<String, String> entry : ALL_CATEGORIES_MAP.entrySet()) {
            if (SYSTEM_USER_CATEGORIES.contains(entry.getKey())) {
                verify(mOverlayManager).setEnabledExclusiveInCategory(
                        entry.getValue(), UserHandle.SYSTEM);
            } else {
                verify(mOverlayManager, never()).setEnabledExclusiveInCategory(
                        entry.getValue(), UserHandle.SYSTEM);
            }
        }
    }

    @Test
    public void allCategoriesSpecified_enabledForAllUserHandles() {
        Set<UserHandle> userHandles = Sets.newHashSet(TEST_USER_HANDLES);
        UserHandle newUserHandle = UserHandle.of(10);
        userHandles.add(newUserHandle);
        mManager.applyCurrentUserOverlays(ALL_CATEGORIES_MAP, userHandles);

        for (String overlayPackage : ALL_CATEGORIES_MAP.values()) {
            verify(mOverlayManager).setEnabledExclusiveInCategory(overlayPackage, TEST_USER);
            verify(mOverlayManager).setEnabledExclusiveInCategory(overlayPackage, newUserHandle);
        }
    }

    @Test
    public void allCategoriesSpecified_overlayManagerNotQueried() {
        mManager.applyCurrentUserOverlays(ALL_CATEGORIES_MAP, TEST_USER_HANDLES);

        verify(mOverlayManager, never())
                .getOverlayInfosForTarget(anyString(), any(UserHandle.class));
    }

    @Test
    public void someCategoriesSpecified_specifiedEnabled_unspecifiedDisabled() {
        Map<String, String> categoryToPackage = new HashMap<>(ALL_CATEGORIES_MAP);
        categoryToPackage.remove(OVERLAY_CATEGORY_ICON_SETTINGS);
        categoryToPackage.remove(OVERLAY_CATEGORY_ICON_ANDROID);

        mManager.applyCurrentUserOverlays(categoryToPackage, TEST_USER_HANDLES);

        for (String overlayPackage : categoryToPackage.values()) {
            verify(mOverlayManager).setEnabledExclusiveInCategory(overlayPackage, TEST_USER);
        }
        verify(mOverlayManager).setEnabled(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_ICON_SETTINGS,
                false, TEST_USER);
        verify(mOverlayManager).setEnabled(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_ICON_ANDROID,
                false, TEST_USER);
    }

    @Test
    public void zeroCategoriesSpecified_allDisabled() {
        mManager.applyCurrentUserOverlays(Maps.newArrayMap(), TEST_USER_HANDLES);

        for (String category : THEME_CATEGORIES) {
            verify(mOverlayManager).setEnabled(TEST_ENABLED_PREFIX + category, false, TEST_USER);
        }
    }

    @Test
    public void nonThemeCategorySpecified_ignored() {
        Map<String, String> categoryToPackage = new HashMap<>(ALL_CATEGORIES_MAP);
        categoryToPackage.put("blah.category", "com.example.blah.category");

        mManager.applyCurrentUserOverlays(categoryToPackage, TEST_USER_HANDLES);

        verify(mOverlayManager, never()).setEnabled("com.example.blah.category", false, TEST_USER);
        verify(mOverlayManager, never()).setEnabledExclusiveInCategory("com.example.blah.category",
                TEST_USER);
    }

    @Test
    public void overlayManagerOnlyQueriedForUnspecifiedPackages() {
        Map<String, String> categoryToPackage = new HashMap<>(ALL_CATEGORIES_MAP);
        categoryToPackage.remove(OVERLAY_CATEGORY_ICON_SETTINGS);

        mManager.applyCurrentUserOverlays(categoryToPackage, TEST_USER_HANDLES);

        verify(mOverlayManager).getOverlayInfosForTarget(SETTINGS_PACKAGE, UserHandle.SYSTEM);
        verify(mOverlayManager, never()).getOverlayInfosForTarget(ANDROID_PACKAGE,
                UserHandle.SYSTEM);
        verify(mOverlayManager, never()).getOverlayInfosForTarget(SYSUI_PACKAGE, UserHandle.SYSTEM);
        verify(mOverlayManager, never()).getOverlayInfosForTarget(LAUNCHER_PACKAGE,
                UserHandle.SYSTEM);
        verify(mOverlayManager, never()).getOverlayInfosForTarget(THEMEPICKER_PACKAGE,
                UserHandle.SYSTEM);
    }

    private static OverlayInfo createOverlayInfo(String packageName, String targetPackageName,
            String category, boolean enabled) {
        return new OverlayInfo(packageName, targetPackageName, null, category, "",
                enabled ? OverlayInfo.STATE_ENABLED : OverlayInfo.STATE_DISABLED, 0, 0, false);
    }
}
