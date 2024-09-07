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

package com.android.server.accessibility;

import static android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_AUDIBLE;
import static android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityTrace;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Handler;
import android.os.test.FakePermissionEnforcer;
import android.view.accessibility.AccessibilityEvent;

import com.android.server.wm.WindowManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

public class ProxyAccessibilityServiceConnectionTest {
    private static final int DISPLAY_ID = 1000;
    private static final int DEVICE_ID = 2000;
    private static final int CONNECTION_ID = 1000;
    private static final ComponentName COMPONENT_NAME = new ComponentName(
            "com.android.server.accessibility", ".ProxyAccessibilityServiceConnectionTest");
    public static final int NON_INTERACTIVE_UI_TIMEOUT_100MS = 100;
    public static final int NON_INTERACTIVE_UI_TIMEOUT_200MS = 200;
    public static final int INTERACTIVE_UI_TIMEOUT_100MS = 100;
    public static final int INTERACTIVE_UI_TIMEOUT_200MS = 200;
    public static final int NOTIFICATION_TIMEOUT_100MS = 100;
    public static final int NOTIFICATION_TIMEOUT_200MS = 200;
    public static final String PACKAGE_1 = "package 1";
    public static final String PACKAGE_2 = "package 2";
    public static final String PACKAGE_3 = "package 3";

    @Mock
    Context mMockContext;
    @Mock
    Object mMockLock;
    @Mock
    AccessibilitySecurityPolicy mMockSecurityPolicy;
    @Mock
    AccessibilityWindowManager mMockA11yWindowManager;
    @Mock AbstractAccessibilityServiceConnection.SystemSupport mMockSystemSupport;
    @Mock
    AccessibilityTrace mMockA11yTrace;
    @Mock
    WindowManagerInternal mMockWindowManagerInternal;
    FakePermissionEnforcer mFakePermissionEnforcer  = new FakePermissionEnforcer();
    ProxyAccessibilityServiceConnection mProxyConnection;
    AccessibilityServiceInfo mAccessibilityServiceInfo;
    private int mFocusStrokeWidthDefaultValue;
    private int mFocusColorDefaultValue;

    @Before
    public void setup() {
        final Resources resources = getInstrumentation().getContext().getResources();
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getResources()).thenReturn(resources);
        when(mMockSecurityPolicy.checkAccessibilityAccess(any())).thenReturn(true);
        when(mMockContext.getSystemService(Context.PERMISSION_ENFORCER_SERVICE))
                .thenReturn(mFakePermissionEnforcer);

        mAccessibilityServiceInfo = new AccessibilityServiceInfo();
        mProxyConnection = new ProxyAccessibilityServiceConnection(mMockContext, COMPONENT_NAME,
                mAccessibilityServiceInfo, CONNECTION_ID , new Handler(
                        getInstrumentation().getContext().getMainLooper()),
                mMockLock, mMockSecurityPolicy, mMockSystemSupport, mMockA11yTrace,
                mMockWindowManagerInternal, mMockA11yWindowManager, DISPLAY_ID, DEVICE_ID);

        mFocusStrokeWidthDefaultValue = mProxyConnection.getFocusStrokeWidthLocked();
        mFocusColorDefaultValue = mProxyConnection.getFocusColorLocked();
    }

    @Test
    public void testSetInstalledAndEnabledServices_updateInfos_notifiesSystemOfProxyChange() {
        final List<AccessibilityServiceInfo> infos = new ArrayList<>();
        final AccessibilityServiceInfo info1 = new AccessibilityServiceInfo();
        infos.add(info1);

        mProxyConnection.setInstalledAndEnabledServices(infos);

        verify(mMockSystemSupport).onProxyChanged(DEVICE_ID);
    }

    @Test
    public void testSetFocusAppearance_updateAppearance_notifiesSystemOfProxyChange() {
        final int updatedWidth = mFocusStrokeWidthDefaultValue + 10;
        final int updatedColor = mFocusColorDefaultValue
                == Color.BLUE ? Color.RED : Color.BLUE;

        mProxyConnection.setFocusAppearance(updatedWidth, updatedColor);

        verify(mMockSystemSupport).onProxyChanged(DEVICE_ID);
    }

    @Test
    public void testSetInstalledAndEnabledServices_returnList() {
        final List<AccessibilityServiceInfo> infos = new ArrayList<>();
        final AccessibilityServiceInfo info1 = new AccessibilityServiceInfo();
        final AccessibilityServiceInfo info2 = new AccessibilityServiceInfo();
        infos.add(info1);
        infos.add(info2);

        mProxyConnection.setInstalledAndEnabledServices(infos);
        final List<AccessibilityServiceInfo> infoList =
                mProxyConnection.getInstalledAndEnabledServices();

        assertThat(infoList.size()).isEqualTo(2);
        assertThat(infoList.get(0)).isEqualTo(info1);
        assertThat(infoList.get(1)).isEqualTo(info2);
    }

    @Test
    public void testSetInstalledAndEnabledServices_defaultNamesPopulated() {
        final List<AccessibilityServiceInfo> infos = new ArrayList<>();
        final AccessibilityServiceInfo info1 = new AccessibilityServiceInfo();
        infos.add(info1);

        mProxyConnection.setInstalledAndEnabledServices(infos);
        final List<AccessibilityServiceInfo> infoList =
                mProxyConnection.getInstalledAndEnabledServices();

        assertThat(infoList.get(0).getComponentName()).isNotNull();
        assertThat(infoList.get(0).getResolveInfo()).isNotNull();
    }

    @Test
    public void testSetInstalledAndEnabledServices_connectionInfoIsUnion() {
        final List<AccessibilityServiceInfo> infos = new ArrayList<>();
        final AccessibilityServiceInfo info1 = new AccessibilityServiceInfo();
        info1.setAccessibilityTool(true);
        info1.packageNames = new String[]{PACKAGE_1, PACKAGE_2};
        info1.setInteractiveUiTimeoutMillis(INTERACTIVE_UI_TIMEOUT_200MS);
        info1.setNonInteractiveUiTimeoutMillis(NON_INTERACTIVE_UI_TIMEOUT_100MS);
        info1.notificationTimeout = NOTIFICATION_TIMEOUT_100MS;
        info1.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED;
        info1.feedbackType = FEEDBACK_AUDIBLE;
        info1.flags = FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        infos.add(info1);

        final AccessibilityServiceInfo info2 = new AccessibilityServiceInfo();
        info2.packageNames = new String[]{PACKAGE_2, PACKAGE_3};
        info2.setInteractiveUiTimeoutMillis(INTERACTIVE_UI_TIMEOUT_100MS);
        info2.setNonInteractiveUiTimeoutMillis(NON_INTERACTIVE_UI_TIMEOUT_200MS);
        info2.notificationTimeout = NOTIFICATION_TIMEOUT_200MS;
        info2.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info2.feedbackType = FEEDBACK_GENERIC;
        info2.flags = FLAG_REQUEST_TOUCH_EXPLORATION_MODE | FLAG_REPORT_VIEW_IDS;
        infos.add(info2);

        mProxyConnection.setInstalledAndEnabledServices(infos);

        assertThat(mAccessibilityServiceInfo.isAccessibilityTool()).isTrue();
        // Package 1, 2, 3
        assertThat(mAccessibilityServiceInfo.packageNames).asList().containsExactly(
                PACKAGE_1, PACKAGE_2, PACKAGE_3);
        assertThat(mAccessibilityServiceInfo.getInteractiveUiTimeoutMillis()).isEqualTo(
                INTERACTIVE_UI_TIMEOUT_200MS);
        assertThat(mAccessibilityServiceInfo.getNonInteractiveUiTimeoutMillis()).isEqualTo(
                NON_INTERACTIVE_UI_TIMEOUT_200MS);
        assertThat(mAccessibilityServiceInfo.notificationTimeout).isEqualTo(
                NOTIFICATION_TIMEOUT_200MS);
        assertThat(mAccessibilityServiceInfo.eventTypes).isEqualTo(
                AccessibilityEvent.TYPES_ALL_MASK);
        assertThat(mAccessibilityServiceInfo.feedbackType).isEqualTo(FEEDBACK_AUDIBLE
                | FEEDBACK_GENERIC);
        assertThat(mAccessibilityServiceInfo.flags).isEqualTo(FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | FLAG_REQUEST_TOUCH_EXPLORATION_MODE | FLAG_REPORT_VIEW_IDS);
    }

    @Test
    public void testSetInstalledAndEnabledServices_emptyPackageNames_packageNamesIsNull() {
        final List<AccessibilityServiceInfo> infos = new ArrayList<>();
        final AccessibilityServiceInfo info1 = new AccessibilityServiceInfo();
        info1.packageNames = new String[]{PACKAGE_1, PACKAGE_2};
        infos.add(info1);
        final AccessibilityServiceInfo info2 = new AccessibilityServiceInfo();
        infos.add(info2);

        mProxyConnection.setInstalledAndEnabledServices(infos);

        final String[] packageNames = mAccessibilityServiceInfo.packageNames;
        assertThat(packageNames).isNull();
    }

    @Test
    public void testSetServiceInfo_setIllegalOperationExceptionThrown() {
        UnsupportedOperationException thrown =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> mProxyConnection.setServiceInfo(new AccessibilityServiceInfo()));

        assertThat(thrown).hasMessageThat().contains("setServiceInfo is not supported");
    }

    @Test
    public void testDisableSelf_setIllegalOperationExceptionThrown() {
        UnsupportedOperationException thrown =
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> mProxyConnection.disableSelf());

        assertThat(thrown).hasMessageThat().contains("disableSelf is not supported");
    }

    @Test
    public void getInstalledAndEnabledServices_noServices_returnEmpty() {
        assertThat(mProxyConnection.getInstalledAndEnabledServices()).isEmpty();
    }
}
