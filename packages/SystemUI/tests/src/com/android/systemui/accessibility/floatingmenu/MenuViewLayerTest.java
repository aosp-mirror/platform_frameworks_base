/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_COMPONENT_NAME;
import static com.android.systemui.accessibility.floatingmenu.MenuViewLayer.LayerIndex;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

/** Tests for {@link MenuViewLayer}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class MenuViewLayerTest extends SysuiTestCase {
    private static final String SELECT_TO_SPEAK_PACKAGE_NAME = "com.google.android.marvin.talkback";
    private static final String SELECT_TO_SPEAK_SERVICE_NAME =
            "com.google.android.accessibility.selecttospeak.SelectToSpeakService";
    private static final ComponentName TEST_SELECT_TO_SPEAK_COMPONENT_NAME = new ComponentName(
            SELECT_TO_SPEAK_PACKAGE_NAME, SELECT_TO_SPEAK_SERVICE_NAME);

    private MenuViewLayer mMenuViewLayer;
    private String mLastAccessibilityButtonTargets;
    private String mLastEnabledAccessibilityServices;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private IAccessibilityFloatingMenu mFloatingMenu;

    @Mock
    private AccessibilityManager mStubAccessibilityManager;

    @Before
    public void setUp() throws Exception {
        final WindowManager stubWindowManager = mContext.getSystemService(WindowManager.class);
        mMenuViewLayer = new MenuViewLayer(mContext, stubWindowManager, mStubAccessibilityManager,
                mFloatingMenu);

        mLastAccessibilityButtonTargets =
                Settings.Secure.getStringForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, UserHandle.USER_CURRENT);
        mLastEnabledAccessibilityServices =
                Settings.Secure.getStringForUser(mContext.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, UserHandle.USER_CURRENT);
    }

    @After
    public void tearDown() throws Exception {
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, mLastAccessibilityButtonTargets,
                UserHandle.USER_CURRENT);
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, mLastEnabledAccessibilityServices,
                UserHandle.USER_CURRENT);
    }

    @Test
    public void onAttachedToWindow_menuIsVisible() {
        mMenuViewLayer.onAttachedToWindow();
        final View menuView = mMenuViewLayer.getChildAt(LayerIndex.MENU_VIEW);

        assertThat(menuView.getVisibility()).isEqualTo(VISIBLE);
    }

    @Test
    public void onAttachedToWindow_menuIsGone() {
        mMenuViewLayer.onDetachedFromWindow();
        final View menuView = mMenuViewLayer.getChildAt(LayerIndex.MENU_VIEW);

        assertThat(menuView.getVisibility()).isEqualTo(GONE);
    }

    @Test
    public void tiggerDismissMenuAction_hideFloatingMenu() {
        mMenuViewLayer.mDismissMenuAction.run();

        verify(mFloatingMenu).hide();
    }

    @Test
    public void tiggerDismissMenuAction_matchA11yButtonTargetsResult() {
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                MAGNIFICATION_COMPONENT_NAME.flattenToString(), UserHandle.USER_CURRENT);

        mMenuViewLayer.mDismissMenuAction.run();
        final String value =
                Settings.Secure.getStringForUser(mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, UserHandle.USER_CURRENT);

        assertThat(value).isEqualTo("");
    }

    @Test
    public void tiggerDismissMenuAction_matchEnabledA11yServicesResult() {
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                TEST_SELECT_TO_SPEAK_COMPONENT_NAME.flattenToString());
        final ResolveInfo resolveInfo = new ResolveInfo();
        final ServiceInfo serviceInfo = new ServiceInfo();
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        resolveInfo.serviceInfo = serviceInfo;
        serviceInfo.applicationInfo = applicationInfo;
        applicationInfo.targetSdkVersion = Build.VERSION_CODES.R;
        final AccessibilityServiceInfo accessibilityServiceInfo = new AccessibilityServiceInfo();
        accessibilityServiceInfo.setResolveInfo(resolveInfo);
        accessibilityServiceInfo.flags = AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON;
        final List<AccessibilityServiceInfo> serviceInfoList = new ArrayList<>();
        accessibilityServiceInfo.setComponentName(TEST_SELECT_TO_SPEAK_COMPONENT_NAME);
        serviceInfoList.add(accessibilityServiceInfo);
        when(mStubAccessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)).thenReturn(serviceInfoList);

        mMenuViewLayer.mDismissMenuAction.run();
        final String value = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        assertThat(value).isEqualTo("");
    }
}
