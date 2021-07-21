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

import static com.android.systemui.theme.ThemeOverlayApplier.ANDROID_PACKAGE;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_ACCENT_COLOR;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_FONT;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_ICON_ANDROID;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_ICON_LAUNCHER;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_ICON_SETTINGS;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_ICON_SYSUI;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_ICON_THEME_PICKER;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_SHAPE;
import static com.android.systemui.theme.ThemeOverlayApplier.OVERLAY_CATEGORY_SYSTEM_PALETTE;
import static com.android.systemui.theme.ThemeOverlayApplier.SETTINGS_PACKAGE;
import static com.android.systemui.theme.ThemeOverlayApplier.SYSTEM_USER_CATEGORIES;
import static com.android.systemui.theme.ThemeOverlayApplier.SYSUI_PACKAGE;
import static com.android.systemui.theme.ThemeOverlayApplier.THEME_CATEGORIES;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;

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
public class ThemeOverlayApplierTest extends SysuiTestCase {
    private static final String TEST_DISABLED_PREFIX = "com.example.";
    private static final String TEST_ENABLED_PREFIX = "com.example.enabled.";

    private static final Map<String, OverlayIdentifier> ALL_CATEGORIES_MAP = Maps.newArrayMap();

    static {
        for (String category : THEME_CATEGORIES) {
            ALL_CATEGORIES_MAP.put(category,
                    new OverlayIdentifier(TEST_DISABLED_PREFIX + category));
        }
    }

    private static final String THEMEPICKER_PACKAGE = "com.android.wallpaper";
    private static final String LAUNCHER_PACKAGE = "com.android.launcher3";
    private static final UserHandle TEST_USER = UserHandle.of(5);
    private static final UserHandle TEST_USER_MANAGED_PROFILE = UserHandle.of(6);
    private static final Set<UserHandle> TEST_USER_HANDLES =
            Sets.newHashSet(TEST_USER_MANAGED_PROFILE);

    @Mock
    OverlayManager mOverlayManager;
    @Mock
    DumpManager mDumpManager;
    @Mock
    OverlayManagerTransaction.Builder mTransactionBuilder;

    private ThemeOverlayApplier mManager;
    private boolean mGetOverlayInfoEnabled = true;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mManager = new ThemeOverlayApplier(mOverlayManager, MoreExecutors.directExecutor(),
                LAUNCHER_PACKAGE, THEMEPICKER_PACKAGE, mDumpManager) {
            @Override
            protected OverlayManagerTransaction.Builder getTransactionBuilder() {
                return mTransactionBuilder;
            }
        };
        when(mOverlayManager.getOverlayInfosForTarget(ANDROID_PACKAGE, UserHandle.SYSTEM))
                .thenReturn(Lists.newArrayList(
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_ACCENT_COLOR,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_ACCENT_COLOR, false),
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_SYSTEM_PALETTE,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_SYSTEM_PALETTE, false),
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_FONT,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_FONT, false),
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_SHAPE,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_SHAPE, false),
                        createOverlayInfo(TEST_DISABLED_PREFIX + OVERLAY_CATEGORY_ICON_ANDROID,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_ICON_ANDROID, false),
                        createOverlayInfo(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_ACCENT_COLOR,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_ACCENT_COLOR, true),
                        createOverlayInfo(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_SYSTEM_PALETTE,
                                ANDROID_PACKAGE, OVERLAY_CATEGORY_SYSTEM_PALETTE, true),
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

        OverlayInfo launcherTargetInfo = new OverlayInfo("packageName", LAUNCHER_PACKAGE,
                null, null, "/", 0, 0, 0, false);
        when(mOverlayManager.getOverlayInfo(any(OverlayIdentifier.class), any()))
                .thenAnswer(answer -> {
                    if (mGetOverlayInfoEnabled) {
                        return launcherTargetInfo;
                    }
                    return null;
                });
        clearInvocations(mOverlayManager);
        verify(mDumpManager).registerDumpable(any(), any());
    }

    @Test
    public void allCategoriesSpecified_allEnabledExclusively() {
        mManager.applyCurrentUserOverlays(ALL_CATEGORIES_MAP, null, TEST_USER.getIdentifier(),
                TEST_USER_HANDLES);
        verify(mOverlayManager).commit(any());

        for (OverlayIdentifier overlayPackage : ALL_CATEGORIES_MAP.values()) {
            verify(mTransactionBuilder).setEnabled(eq(overlayPackage), eq(true),
                    eq(TEST_USER.getIdentifier()));
        }
    }

    @Test
    public void allCategoriesSpecified_sysuiCategoriesAlsoAppliedToSysuiUser() {
        mManager.applyCurrentUserOverlays(ALL_CATEGORIES_MAP, null, TEST_USER.getIdentifier(),
                TEST_USER_HANDLES);

        for (Map.Entry<String, OverlayIdentifier> entry : ALL_CATEGORIES_MAP.entrySet()) {
            if (SYSTEM_USER_CATEGORIES.contains(entry.getKey())) {
                verify(mTransactionBuilder).setEnabled(eq(entry.getValue()), eq(true),
                        eq(UserHandle.SYSTEM.getIdentifier()));
            } else {
                verify(mTransactionBuilder, never()).setEnabled(
                        eq(entry.getValue()), eq(true), eq(UserHandle.SYSTEM.getIdentifier()));
            }
        }
    }

    @Test
    public void allCategoriesSpecified_enabledForAllUserHandles() {
        Set<UserHandle> userHandles = Sets.newHashSet(TEST_USER_HANDLES);
        mManager.applyCurrentUserOverlays(ALL_CATEGORIES_MAP, null, TEST_USER.getIdentifier(),
                userHandles);

        for (OverlayIdentifier overlayPackage : ALL_CATEGORIES_MAP.values()) {
            verify(mTransactionBuilder).setEnabled(eq(overlayPackage), eq(true),
                    eq(TEST_USER.getIdentifier()));
            // Not enabled for work profile because the target package is LAUNCHER_PACKAGE
            verify(mTransactionBuilder, never()).setEnabled(eq(overlayPackage), eq(true),
                    eq(TEST_USER_MANAGED_PROFILE.getIdentifier()));
        }
    }

    @Test
    public void enablesOverlays_onlyIfItExistsForUser() {
        mGetOverlayInfoEnabled = false;

        Set<UserHandle> userHandles = Sets.newHashSet(TEST_USER_HANDLES);
        mManager.applyCurrentUserOverlays(ALL_CATEGORIES_MAP, null, TEST_USER.getIdentifier(),
                userHandles);

        for (OverlayIdentifier overlayPackage : ALL_CATEGORIES_MAP.values()) {
            verify(mTransactionBuilder, never()).setEnabled(eq(overlayPackage), eq(true),
                    eq(TEST_USER.getIdentifier()));
        }
    }

    @Test
    public void applyCurrentUserOverlays_createsPendingOverlays() {
        FabricatedOverlay[] pendingCreation = new FabricatedOverlay[]{
                mock(FabricatedOverlay.class)
        };
        mManager.applyCurrentUserOverlays(ALL_CATEGORIES_MAP, pendingCreation,
                TEST_USER.getIdentifier(), TEST_USER_HANDLES);

        for (FabricatedOverlay overlay : pendingCreation) {
            verify(mTransactionBuilder).registerFabricatedOverlay(eq(overlay));
        }
    }

    @Test
    public void someCategoriesSpecified_specifiedEnabled_unspecifiedDisabled() {
        Map<String, OverlayIdentifier> categoryToPackage = new HashMap<>(ALL_CATEGORIES_MAP);
        categoryToPackage.remove(OVERLAY_CATEGORY_ICON_SETTINGS);
        categoryToPackage.remove(OVERLAY_CATEGORY_ICON_ANDROID);

        mManager.applyCurrentUserOverlays(categoryToPackage, null, TEST_USER.getIdentifier(),
                TEST_USER_HANDLES);

        for (OverlayIdentifier overlayPackage : categoryToPackage.values()) {
            verify(mTransactionBuilder).setEnabled(eq(overlayPackage), eq(true),
                    eq(TEST_USER.getIdentifier()));
        }
        verify(mTransactionBuilder).setEnabled(
                eq(new OverlayIdentifier(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_ICON_SETTINGS)),
                eq(false), eq(TEST_USER.getIdentifier()));
        verify(mTransactionBuilder).setEnabled(
                eq(new OverlayIdentifier(TEST_ENABLED_PREFIX + OVERLAY_CATEGORY_ICON_ANDROID)),
                eq(false), eq(TEST_USER.getIdentifier()));
    }

    @Test
    public void zeroCategoriesSpecified_allDisabled() {
        mManager.applyCurrentUserOverlays(Maps.newArrayMap(), null, TEST_USER.getIdentifier(),
                TEST_USER_HANDLES);

        for (String category : THEME_CATEGORIES) {
            verify(mTransactionBuilder).setEnabled(
                    eq(new OverlayIdentifier(TEST_ENABLED_PREFIX + category)), eq(false),
                    eq(TEST_USER.getIdentifier()));
        }
    }

    @Test
    public void nonThemeCategorySpecified_ignored() {
        Map<String, OverlayIdentifier> categoryToPackage = new HashMap<>(ALL_CATEGORIES_MAP);
        categoryToPackage.put("blah.category", new OverlayIdentifier("com.example.blah.category"));

        mManager.applyCurrentUserOverlays(categoryToPackage, null, TEST_USER.getIdentifier(),
                TEST_USER_HANDLES);

        verify(mTransactionBuilder, never()).setEnabled(
                eq(new OverlayIdentifier("com.example.blah.category")), eq(false),
                eq(TEST_USER.getIdentifier()));
        verify(mTransactionBuilder, never()).setEnabled(
                eq(new OverlayIdentifier("com.example.blah.category")), eq(true),
                eq(TEST_USER.getIdentifier()));
    }

    private static OverlayInfo createOverlayInfo(String packageName, String targetPackageName,
            String category, boolean enabled) {
        return new OverlayInfo(packageName, null, targetPackageName, null, category, "",
                enabled ? OverlayInfo.STATE_ENABLED : OverlayInfo.STATE_DISABLED, 0, 0, false,
                false);
    }
}
