/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.AlarmManager.RTC_WAKEUP;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.internal.messages.nano.SystemMessageProto.SystemMessage.NOTE_A11Y_VIEW_AND_CONTROL_ACCESS;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.TestableContext;
import android.util.ArraySet;

import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for the {@link PolicyWarningUIController}.
 */
public class PolicyWarningUIControllerTest {
    private static final int TEST_USER_ID = UserHandle.USER_SYSTEM;
    private static final ComponentName TEST_COMPONENT_NAME = new ComponentName(
            "com.android.server.accessibility", "PolicyWarningUIControllerTest");

    private static final ComponentName TEST_COMPONENT_NAME2 = new ComponentName(
            "com.android.server.accessibility", "nonAccessibilityToolService");
    private final List<AccessibilityServiceInfo> mEnabledServiceList = new ArrayList<>();

    @Rule
    public final A11yTestableContext mContext = new A11yTestableContext(
            getInstrumentation().getTargetContext());
    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private NotificationManager mNotificationManager;
    @Mock
    private StatusBarManager mStatusBarManager;
    @Mock
    private ServiceInfo mMockServiceInfo;
    @Mock
    private Context mSpyContext;

    private PolicyWarningUIController mPolicyWarningUIController;
    private FakeNotificationController mFakeNotificationController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(AlarmManager.class, mAlarmManager);
        mContext.addMockSystemService(NotificationManager.class, mNotificationManager);
        mContext.addMockSystemService(StatusBarManager.class, mStatusBarManager);
        mFakeNotificationController = new FakeNotificationController(mContext);
        mPolicyWarningUIController = new PolicyWarningUIController(
                getInstrumentation().getTargetContext().getMainThreadHandler(), mContext,
                mFakeNotificationController);
        mEnabledServiceList.clear();
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.NOTIFIED_NON_ACCESSIBILITY_CATEGORY_SERVICES,
                "", TEST_USER_ID);
        mPolicyWarningUIController.enableSendingNonA11yToolNotification(true);
        mPolicyWarningUIController.onSwitchUser(TEST_USER_ID, new HashSet<>());
        getInstrumentation().waitForIdleSync();
    }

    @Test
    public void receiveActionSendNotification_isNonA11yCategoryService_sendNotification() {
        addEnabledServiceInfo(TEST_COMPONENT_NAME, false);

        mFakeNotificationController.onReceive(mContext,
                PolicyWarningUIController.createIntent(mContext, TEST_USER_ID,
                        PolicyWarningUIController.ACTION_SEND_NOTIFICATION,
                        TEST_COMPONENT_NAME));

        verify(mNotificationManager).notify(eq(TEST_COMPONENT_NAME.flattenToShortString()),
                eq(NOTE_A11Y_VIEW_AND_CONTROL_ACCESS), any(Notification.class));
    }

    @Test
    public void receiveActionSendNotification_sendNotificationDisabled_doNothing() {
        mPolicyWarningUIController.enableSendingNonA11yToolNotification(false);
        addEnabledServiceInfo(TEST_COMPONENT_NAME, false);

        mFakeNotificationController.onReceive(mContext,
                PolicyWarningUIController.createIntent(mContext, TEST_USER_ID,
                        PolicyWarningUIController.ACTION_SEND_NOTIFICATION,
                        TEST_COMPONENT_NAME));

        verify(mNotificationManager, never()).notify(eq(TEST_COMPONENT_NAME.flattenToShortString()),
                eq(NOTE_A11Y_VIEW_AND_CONTROL_ACCESS), any(Notification.class));
    }

    @Test
    public void receiveActionSendNotificationWithNotifiedService_doNothing() {
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.NOTIFIED_NON_ACCESSIBILITY_CATEGORY_SERVICES,
                TEST_COMPONENT_NAME.flattenToShortString(), TEST_USER_ID);
        mEnabledServiceList.clear();
        mPolicyWarningUIController.onSwitchUser(TEST_USER_ID, new HashSet<>());
        getInstrumentation().waitForIdleSync();
        addEnabledServiceInfo(TEST_COMPONENT_NAME, false);

        mFakeNotificationController.onReceive(mContext,
                PolicyWarningUIController.createIntent(mContext, TEST_USER_ID,
                        PolicyWarningUIController.ACTION_SEND_NOTIFICATION,
                        TEST_COMPONENT_NAME));

        verify(mNotificationManager, never()).notify(eq(TEST_COMPONENT_NAME.flattenToShortString()),
                eq(NOTE_A11Y_VIEW_AND_CONTROL_ACCESS), any(Notification.class));
    }

    @Test
    public void receiveActionA11ySettings_launchA11ySettingsAndDismissNotification() {
        mFakeNotificationController.onReceive(mContext,
                PolicyWarningUIController.createIntent(mContext, TEST_USER_ID,
                        PolicyWarningUIController.ACTION_A11Y_SETTINGS,
                        TEST_COMPONENT_NAME));

        verifyLaunchA11ySettings();
        verify(mNotificationManager).cancel(TEST_COMPONENT_NAME.flattenToShortString(),
                NOTE_A11Y_VIEW_AND_CONTROL_ACCESS);
        assertNotifiedSettingsEqual(TEST_USER_ID, TEST_COMPONENT_NAME.flattenToShortString());
    }

    @Test
    public void receiveActionDismissNotification_addToNotifiedSettings() {
        mFakeNotificationController.onReceive(mContext,
                PolicyWarningUIController.createIntent(mContext, TEST_USER_ID,
                        PolicyWarningUIController.ACTION_DISMISS_NOTIFICATION,
                        TEST_COMPONENT_NAME));

        assertNotifiedSettingsEqual(TEST_USER_ID, TEST_COMPONENT_NAME.flattenToShortString());
    }

    @Test
    public void onEnabledServicesChangedLocked_serviceDisabled_removedFromNotifiedSettings() {
        final Set<ComponentName> enabledServices = new HashSet<>();
        enabledServices.add(TEST_COMPONENT_NAME);
        mPolicyWarningUIController.onEnabledServicesChanged(TEST_USER_ID, enabledServices);
        getInstrumentation().waitForIdleSync();
        receiveActionDismissNotification_addToNotifiedSettings();

        mPolicyWarningUIController.onEnabledServicesChanged(TEST_USER_ID, new HashSet<>());
        getInstrumentation().waitForIdleSync();

        assertNotifiedSettingsEqual(TEST_USER_ID, "");
    }

    @Test
    public void onNonA11yCategoryServiceBound_setAlarm() {
        mPolicyWarningUIController.onNonA11yCategoryServiceBound(TEST_USER_ID, TEST_COMPONENT_NAME);
        getInstrumentation().waitForIdleSync();

        verify(mAlarmManager).set(eq(RTC_WAKEUP), anyLong(),
                eq(PolicyWarningUIController.createPendingIntent(mContext, TEST_USER_ID,
                        PolicyWarningUIController.ACTION_SEND_NOTIFICATION, TEST_COMPONENT_NAME)));
    }

    @Test
    public void onNonA11yCategoryServiceUnbound_cancelAlarm() {
        mPolicyWarningUIController.onNonA11yCategoryServiceUnbound(TEST_USER_ID,
                TEST_COMPONENT_NAME);
        getInstrumentation().waitForIdleSync();

        verify(mAlarmManager).cancel(
                eq(PolicyWarningUIController.createPendingIntent(mContext, TEST_USER_ID,
                        PolicyWarningUIController.ACTION_SEND_NOTIFICATION, TEST_COMPONENT_NAME)));
    }

    @Test
    public void onSwitchUserLocked_hasAlarmAndSentNotification_cancelNotification() {
        addEnabledServiceInfo(TEST_COMPONENT_NAME2, false);
        final Set<ComponentName> enabledNonA11yServices = new ArraySet<>();
        enabledNonA11yServices.add(TEST_COMPONENT_NAME);
        enabledNonA11yServices.add(TEST_COMPONENT_NAME2);
        mPolicyWarningUIController.onEnabledServicesChanged(TEST_USER_ID,
                enabledNonA11yServices);
        mPolicyWarningUIController.onNonA11yCategoryServiceBound(TEST_USER_ID, TEST_COMPONENT_NAME);
        mFakeNotificationController.onReceive(mContext,
                PolicyWarningUIController.createIntent(mContext, TEST_USER_ID,
                        PolicyWarningUIController.ACTION_SEND_NOTIFICATION,
                        TEST_COMPONENT_NAME2));
        getInstrumentation().waitForIdleSync();

        mPolicyWarningUIController.onSwitchUser(TEST_USER_ID,
                ImmutableSet.copyOf(new ArraySet<>()));
        getInstrumentation().waitForIdleSync();

        verify(mNotificationManager).cancel(TEST_COMPONENT_NAME2.flattenToShortString(),
                NOTE_A11Y_VIEW_AND_CONTROL_ACCESS);
    }

    private void assertNotifiedSettingsEqual(int userId, String settingString) {
        final String notifiedServicesSetting = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.NOTIFIED_NON_ACCESSIBILITY_CATEGORY_SERVICES,
                userId);
        assertEquals(settingString, notifiedServicesSetting);
    }

    private void verifyLaunchA11ySettings() {
        final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        final ArgumentCaptor<UserHandle> userHandleCaptor = ArgumentCaptor.forClass(
                UserHandle.class);
        verify(mSpyContext).startActivityAsUser(intentCaptor.capture(),
                any(), userHandleCaptor.capture());
        assertThat(intentCaptor.getValue().getAction()).isEqualTo(
                Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
        assertThat(userHandleCaptor.getValue().getIdentifier()).isEqualTo(TEST_USER_ID);
        verify(mStatusBarManager).collapsePanels();
    }

    private void addEnabledServiceInfo(ComponentName componentName, boolean isAccessibilityTool) {
        final AccessibilityServiceInfo a11yServiceInfo = Mockito.mock(
                AccessibilityServiceInfo.class);
        when(a11yServiceInfo.getComponentName()).thenReturn(componentName);
        when(a11yServiceInfo.isAccessibilityTool()).thenReturn(isAccessibilityTool);
        final ResolveInfo resolveInfo = Mockito.mock(ResolveInfo.class);
        when(a11yServiceInfo.getResolveInfo()).thenReturn(resolveInfo);
        resolveInfo.serviceInfo = mMockServiceInfo;
        mEnabledServiceList.add(a11yServiceInfo);
    }

    private class A11yTestableContext extends TestableContext {
        A11yTestableContext(Context base) {
            super(base);
        }

        @Override
        public void startActivityAsUser(Intent intent, Bundle options, UserHandle user) {
            mSpyContext.startActivityAsUser(intent, options, user);
        }
    }

    private class FakeNotificationController extends
            PolicyWarningUIController.NotificationController {
        FakeNotificationController(Context context) {
            super(context);
        }

        @Override
        protected List<AccessibilityServiceInfo> getEnabledServiceInfos() {
            return mEnabledServiceList;
        }
    }
}
