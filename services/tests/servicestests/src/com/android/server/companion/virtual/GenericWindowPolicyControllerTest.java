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

package com.android.server.companion.virtual;

import static android.content.pm.ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.WindowConfiguration;
import android.companion.virtual.IVirtualDeviceIntentInterceptor;
import android.companion.virtual.VirtualDeviceManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArraySet;
import android.view.Display;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.app.BlockedAppStreamingActivity;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class GenericWindowPolicyControllerTest {

    private static final int TIMEOUT_MILLIS = 500;
    private static final int DISPLAY_ID = Display.DEFAULT_DISPLAY + 1;
    private static final int TEST_UID = 1234567;
    private static final String DISPLAY_CATEGORY = "com.display.category";
    private static final String NONBLOCKED_APP_PACKAGE_NAME = "com.someapp";
    private static final String BLOCKED_PACKAGE_NAME = "com.blockedapp";
    private static final int FLAG_CANNOT_DISPLAY_ON_REMOTE_DEVICES = 0x00000;
    private static final String TEST_SITE = "http://test";
    private static final ComponentName BLOCKED_APP_STREAMING_COMPONENT =
            new ComponentName("android", BlockedAppStreamingActivity.class.getName());
    private static final ComponentName BLOCKED_COMPONENT = new ComponentName(BLOCKED_PACKAGE_NAME,
            BLOCKED_PACKAGE_NAME);
    private static final ComponentName NONBLOCKED_COMPONENT = new ComponentName(
            NONBLOCKED_APP_PACKAGE_NAME, NONBLOCKED_APP_PACKAGE_NAME);

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock
    private GenericWindowPolicyController.PipBlockedCallback mPipBlockedCallback;
    @Mock
    private VirtualDeviceManager.ActivityListener mActivityListener;
    @Mock
    private GenericWindowPolicyController.IntentListenerCallback mIntentListenerCallback;
    @Mock
    private GenericWindowPolicyController.ActivityBlockedCallback mActivityBlockedCallback;
    @Mock
    private GenericWindowPolicyController.RunningAppsChangedListener mRunningAppsChangedListener;
    @Mock
    private GenericWindowPolicyController.SecureWindowCallback mSecureWindowCallback;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void showTasksInHostDeviceRecents() {
        GenericWindowPolicyController gwpc = createGwpc();

        gwpc.setShowInHostDeviceRecents(true);
        assertThat(gwpc.canShowTasksInHostDeviceRecents()).isTrue();

        gwpc.setShowInHostDeviceRecents(false);
        assertThat(gwpc.canShowTasksInHostDeviceRecents()).isFalse();
    }

    @Test
    public void containsUid() {
        GenericWindowPolicyController gwpc = createGwpc();

        assertThat(gwpc.containsUid(TEST_UID)).isFalse();

        gwpc.onRunningAppsChanged(new ArraySet<>(Arrays.asList(TEST_UID)));
        assertThat(gwpc.containsUid(TEST_UID)).isTrue();

        gwpc.onRunningAppsChanged(new ArraySet<>());
        assertThat(gwpc.containsUid(TEST_UID)).isFalse();
    }

    @Test
    public void isEnteringPipAllowed_falseByDefault() {
        GenericWindowPolicyController gwpc = createGwpc();

        assertThat(gwpc.isEnteringPipAllowed(TEST_UID)).isFalse();
        verify(mPipBlockedCallback, timeout(TIMEOUT_MILLIS)).onEnteringPipBlocked(TEST_UID);
    }

    @Test
    public void isEnteringPipAllowed_dpcSupportsPinned_allowed() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setSupportedWindowingModes(new HashSet<>(
                Arrays.asList(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                        WindowConfiguration.WINDOWING_MODE_PINNED)));
        assertThat(gwpc.isEnteringPipAllowed(TEST_UID)).isTrue();
        verify(mPipBlockedCallback, after(TIMEOUT_MILLIS).never()).onEnteringPipBlocked(TEST_UID);
    }

    @Test
    public void userNotAllowlisted_launchIsBlocked() {
        GenericWindowPolicyController gwpc = createGwpcWithNoAllowedUsers();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityIsBlocked(gwpc, activityInfo);
    }

    @Test
    public void openNonBlockedAppOnVirtualDisplay_isNotBlocked() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityCanBeLaunched(gwpc, activityInfo);
    }

    @Test
    public void activityDoesNotSupportDisplayOnRemoteDevices_isBlocked() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ false,
                /* targetDisplayCategory */ null);
        assertActivityIsBlocked(gwpc, activityInfo);
    }

    @Test
    public void openBlockedComponentOnVirtualDisplay_isBlocked() {
        GenericWindowPolicyController gwpc = createGwpcWithBlockedComponent(BLOCKED_COMPONENT);
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                BLOCKED_PACKAGE_NAME,
                BLOCKED_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityIsBlocked(gwpc, activityInfo);
    }

    @Test
    public void addActivityPolicyExemption_openBlockedOnVirtualDisplay_isBlocked() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);
        gwpc.setActivityLaunchDefaultAllowed(true);
        gwpc.addActivityPolicyExemption(BLOCKED_COMPONENT);

        ActivityInfo activityInfo = getActivityInfo(
                BLOCKED_PACKAGE_NAME,
                BLOCKED_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityIsBlocked(gwpc, activityInfo);
    }

    @Test
    public void openNotAllowedComponentOnBlocklistVirtualDisplay_isBlocked() {
        GenericWindowPolicyController gwpc = createGwpcWithAllowedComponent(NONBLOCKED_COMPONENT);
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                BLOCKED_PACKAGE_NAME,
                BLOCKED_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityIsBlocked(gwpc, activityInfo);
    }

    @Test
    public void addActivityPolicyExemption_openNotAllowedOnVirtualDisplay_isBlocked() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);
        gwpc.setActivityLaunchDefaultAllowed(false);
        gwpc.addActivityPolicyExemption(NONBLOCKED_COMPONENT);

        ActivityInfo activityInfo = getActivityInfo(
                BLOCKED_PACKAGE_NAME,
                BLOCKED_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityIsBlocked(gwpc, activityInfo);
    }

    @Test
    public void openAllowedComponentOnBlocklistVirtualDisplay_startsActivity() {
        GenericWindowPolicyController gwpc = createGwpcWithAllowedComponent(NONBLOCKED_COMPONENT);
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityCanBeLaunched(gwpc, activityInfo);
    }

    @Test
    public void addActivityPolicyExemption_openAllowedOnVirtualDisplay_startsActivity() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);
        gwpc.setActivityLaunchDefaultAllowed(false);
        gwpc.addActivityPolicyExemption(NONBLOCKED_COMPONENT);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityCanBeLaunched(gwpc, activityInfo);
    }

    @Test
    public void openNonBlockedAppOnMirrorVirtualDisplay_isBlocked() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ true);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertNoActivityLaunched(gwpc, DISPLAY_ID, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_mismatchingUserHandle_isBlocked() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null,
                /* uid */ UserHandle.PER_USER_RANGE + 1);
        assertActivityIsBlocked(gwpc, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_blockedAppStreamingComponent_isNeverBlocked() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                BLOCKED_APP_STREAMING_COMPONENT.getPackageName(),
                BLOCKED_APP_STREAMING_COMPONENT.getClassName(),
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityCanBeLaunched(gwpc, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_blockedAppStreamingComponentExplicitlyBlocked_isNeverBlocked() {
        GenericWindowPolicyController gwpc = createGwpcWithBlockedComponent(
                BLOCKED_APP_STREAMING_COMPONENT);
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                BLOCKED_APP_STREAMING_COMPONENT.getPackageName(),
                BLOCKED_APP_STREAMING_COMPONENT.getClassName(),
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);

        assertActivityCanBeLaunched(gwpc, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_blockedAppStreamingComponentExemptFromStreaming_isNeverBlocked() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);
        gwpc.setActivityLaunchDefaultAllowed(true);
        gwpc.addActivityPolicyExemption(BLOCKED_APP_STREAMING_COMPONENT);

        ActivityInfo activityInfo = getActivityInfo(
                BLOCKED_APP_STREAMING_COMPONENT.getPackageName(),
                BLOCKED_APP_STREAMING_COMPONENT.getClassName(),
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);

        assertActivityCanBeLaunched(gwpc, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_blockedAppStreamingComponentNotAllowlisted_isNeverBlocked() {
        GenericWindowPolicyController gwpc = createGwpcWithAllowedComponent(NONBLOCKED_COMPONENT);
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                BLOCKED_APP_STREAMING_COMPONENT.getPackageName(),
                BLOCKED_APP_STREAMING_COMPONENT.getClassName(),
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);

        assertActivityCanBeLaunched(gwpc, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_blockedAppStreamingComponentNotExemptFromBlocklist_isNeverBlocked() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);
        gwpc.setActivityLaunchDefaultAllowed(false);
        gwpc.addActivityPolicyExemption(NONBLOCKED_COMPONENT);

        ActivityInfo activityInfo = getActivityInfo(
                BLOCKED_APP_STREAMING_COMPONENT.getPackageName(),
                BLOCKED_APP_STREAMING_COMPONENT.getClassName(),
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);

        assertActivityCanBeLaunched(gwpc, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_customDisplayCategoryMatches_isNotBlocked() {
        GenericWindowPolicyController gwpc = createGwpcWithDisplayCategory(DISPLAY_CATEGORY);
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ DISPLAY_CATEGORY);

        assertActivityCanBeLaunched(gwpc, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_customDisplayCategoryDoesNotMatch_isBlocked() {
        GenericWindowPolicyController gwpc = createGwpcWithDisplayCategory(DISPLAY_CATEGORY);
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ "some.random.category");
        assertActivityIsBlocked(gwpc, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_crossTaskLaunch_fromDefaultDisplay_isNotBlocked() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityCanBeLaunched(gwpc, Display.DEFAULT_DISPLAY, true,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_crossTaskLaunchFromVirtualDisplay_notExplicitlyBlocked_isNotBlocked() {
        GenericWindowPolicyController gwpc = createGwpcWithCrossTaskNavigationBlockedFor(
                BLOCKED_COMPONENT);
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);

        assertActivityCanBeLaunched(gwpc, DISPLAY_ID, true,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_crossTaskLaunchFromVirtualDisplay_explicitlyBlocked_isBlocked() {
        GenericWindowPolicyController gwpc = createGwpcWithCrossTaskNavigationBlockedFor(
                BLOCKED_COMPONENT);
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                BLOCKED_PACKAGE_NAME,
                BLOCKED_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityIsBlocked(gwpc, DISPLAY_ID, true,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_crossTaskLaunchFromVirtualDisplay_notAllowed_isBlocked() {
        GenericWindowPolicyController gwpc = createGwpcWithCrossTaskNavigationAllowed(
                NONBLOCKED_COMPONENT);
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                BLOCKED_PACKAGE_NAME,
                BLOCKED_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityIsBlocked(gwpc, DISPLAY_ID, true,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_crossTaskLaunchFromVirtualDisplay_allowed_isNotBlocked() {
        GenericWindowPolicyController gwpc = createGwpcWithCrossTaskNavigationAllowed(
                NONBLOCKED_COMPONENT);
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityCanBeLaunched(gwpc, DISPLAY_ID, true,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, activityInfo);
    }

    @Test
    public void canActivityBeLaunched_unsupportedWindowingMode_isBlocked() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityIsBlocked(gwpc, DISPLAY_ID, true, WindowConfiguration.WINDOWING_MODE_PINNED,
                activityInfo);
    }

    @Test
    public void canActivityBeLaunched_permissionComponent_isBlocked() {
        GenericWindowPolicyController gwpc = createGwpcWithPermissionComponent(BLOCKED_COMPONENT);
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                BLOCKED_PACKAGE_NAME,
                BLOCKED_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertActivityIsBlocked(gwpc, activityInfo);
    }

    @Test
    public void registerRunningAppsChangedListener_onRunningAppsChanged_listenersNotified() {
        ArraySet<Integer> uids = new ArraySet<>(Arrays.asList(TEST_UID));
        GenericWindowPolicyController gwpc = createGwpc();

        gwpc.registerRunningAppsChangedListener(mRunningAppsChangedListener);
        gwpc.onRunningAppsChanged(uids);

        assertThat(gwpc.getRunningAppsChangedListenersSizeForTesting()).isEqualTo(1);
        verify(mRunningAppsChangedListener, timeout(TIMEOUT_MILLIS)).onRunningAppsChanged(uids);
    }

    @Test
    public void onRunningAppsChanged_empty_onDisplayEmpty() {
        ArraySet<Integer> uids = new ArraySet<>();
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        gwpc.onRunningAppsChanged(uids);

        assertThat(gwpc.getRunningAppsChangedListenersSizeForTesting()).isEqualTo(0);
        verify(mActivityListener, timeout(TIMEOUT_MILLIS)).onDisplayEmpty(DISPLAY_ID);
    }

    @Test
    public void noRunningAppsChangedListener_onRunningAppsChanged_doesNotThrowException() {
        ArraySet<Integer> uids = new ArraySet<>(Arrays.asList(TEST_UID));
        GenericWindowPolicyController gwpc = createGwpc();

        gwpc.onRunningAppsChanged(uids);

        assertThat(gwpc.getRunningAppsChangedListenersSizeForTesting()).isEqualTo(0);
        verify(mRunningAppsChangedListener, after(TIMEOUT_MILLIS).never())
                .onRunningAppsChanged(uids);
    }

    @Test
    public void registerUnregisterRunningAppsChangedListener_onRunningAppsChanged_doesNotThrowException() {
        ArraySet<Integer> uids = new ArraySet<>(Arrays.asList(TEST_UID));
        GenericWindowPolicyController gwpc = createGwpc();

        gwpc.registerRunningAppsChangedListener(mRunningAppsChangedListener);
        gwpc.unregisterRunningAppsChangedListener(mRunningAppsChangedListener);
        gwpc.onRunningAppsChanged(uids);

        assertThat(gwpc.getRunningAppsChangedListenersSizeForTesting()).isEqualTo(0);
        verify(mRunningAppsChangedListener, after(TIMEOUT_MILLIS).never())
                .onRunningAppsChanged(uids);
    }

    @Test
    public void canActivityBeLaunched_intentInterceptedWhenRegistered_activityNoLaunch()
            throws RemoteException {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(TEST_SITE));

        IVirtualDeviceIntentInterceptor.Stub interceptor =
                mock(IVirtualDeviceIntentInterceptor.Stub.class);
        doNothing().when(interceptor).onIntentIntercepted(any());
        doReturn(interceptor).when(interceptor).asBinder();
        doReturn(interceptor).when(interceptor).queryLocalInterface(anyString());

        GenericWindowPolicyController gwpc = createGwpc();
        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);

        // register interceptor and intercept intent
        when(mIntentListenerCallback.shouldInterceptIntent(any(Intent.class))).thenReturn(true);
        assertThat(gwpc.canActivityBeLaunched(activityInfo, intent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID, /*isNewTask=*/false))
                .isFalse();

        // unregister interceptor and launch activity
        when(mIntentListenerCallback.shouldInterceptIntent(any(Intent.class))).thenReturn(false);
        assertThat(gwpc.canActivityBeLaunched(activityInfo, intent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID, /*isNewTask=*/false))
                .isTrue();
    }

    @Test
    public void canActivityBeLaunched_noMatchIntentFilter_activityLaunches() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("testing"));

        GenericWindowPolicyController gwpc = createGwpc();
        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);

        // register interceptor with different filter
        when(mIntentListenerCallback.shouldInterceptIntent(any(Intent.class))).thenReturn(false);
        assertThat(gwpc.canActivityBeLaunched(activityInfo, intent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID, /*isNewTask=*/false))
                .isTrue();
        verify(mIntentListenerCallback, timeout(TIMEOUT_MILLIS))
                .shouldInterceptIntent(any(Intent.class));
    }

    @Test
    public void onTopActivitychanged_null_noCallback() {
        GenericWindowPolicyController gwpc = createGwpc();

        gwpc.onTopActivityChanged(null, 0, 0);
        verify(mActivityListener, after(TIMEOUT_MILLIS).never())
                .onTopActivityChanged(anyInt(), any(ComponentName.class), anyInt());
    }

    @Test
    public void onTopActivitychanged_activityListenerCallbackObserved() {
        int userId = 1000;
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        gwpc.onTopActivityChanged(BLOCKED_COMPONENT, 0, userId);
        verify(mActivityListener, timeout(TIMEOUT_MILLIS))
                .onTopActivityChanged(eq(DISPLAY_ID), eq(BLOCKED_COMPONENT), eq(userId));
    }

    @Test
    public void keepActivityOnWindowFlagsChanged_noChange() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);

        assertThat(gwpc.keepActivityOnWindowFlagsChanged(activityInfo, 0, 0)).isTrue();

        verify(mSecureWindowCallback, after(TIMEOUT_MILLIS).never())
                .onSecureWindowShown(DISPLAY_ID, activityInfo.applicationInfo.uid);
        verify(mActivityBlockedCallback, never()).onActivityBlocked(DISPLAY_ID, activityInfo);
    }

    @Test
    public void keepActivityOnWindowFlagsChanged_flagSecure_isAllowedAfterTM() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);

        assertThat(gwpc.keepActivityOnWindowFlagsChanged(activityInfo, FLAG_SECURE, 0)).isTrue();

        verify(mSecureWindowCallback, timeout(TIMEOUT_MILLIS)).onSecureWindowShown(DISPLAY_ID,
                activityInfo.applicationInfo.uid);
        verify(mActivityBlockedCallback, after(TIMEOUT_MILLIS).never())
                .onActivityBlocked(DISPLAY_ID, activityInfo);
    }

    @Test
    public void keepActivityOnWindowFlagsChanged_systemFlagHideNonSystemOverlayWindows_isAllowedAfterTM() {
        GenericWindowPolicyController gwpc = createGwpc();
        gwpc.setDisplayId(DISPLAY_ID, /* isMirrorDisplay= */ false);

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);

        assertThat(gwpc.keepActivityOnWindowFlagsChanged(activityInfo, 0,
                SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS)).isTrue();

        verify(mSecureWindowCallback, after(TIMEOUT_MILLIS).never())
                .onSecureWindowShown(DISPLAY_ID, activityInfo.applicationInfo.uid);
        verify(mActivityBlockedCallback, never()).onActivityBlocked(DISPLAY_ID, activityInfo);
    }

    @Test
    public void getCustomHomeComponent_noneSet() {
        GenericWindowPolicyController gwpc = createGwpc();

        assertThat(gwpc.getCustomHomeComponent()).isNull();
    }

    @Test
    public void getCustomHomeComponent_returnsHomeComponent() {
        GenericWindowPolicyController gwpc = createGwpcWithCustomHomeComponent(
                NONBLOCKED_COMPONENT);

        assertThat(gwpc.getCustomHomeComponent()).isEqualTo(NONBLOCKED_COMPONENT);
    }

    private GenericWindowPolicyController createGwpc() {
        return new GenericWindowPolicyController(
                0,
                0,
                /* allowedUsers= */ new ArraySet<>(getCurrentUserId()),
                /* activityLaunchAllowedByDefault= */ true,
                /* activityPolicyExemptions= */ new ArraySet<>(),
                /* crossTaskNavigationAllowedByDefault= */ true,
                /* crossTaskNavigationExemptions= */ new ArraySet<>(),
                /* permissionDialogComponent= */ null,
                /* activityListener= */ mActivityListener,
                /* pipBlockedCallback= */ mPipBlockedCallback,
                /* activityBlockedCallback= */ mActivityBlockedCallback,
                /* secureWindowCallback= */ mSecureWindowCallback,
                /* intentListenerCallback= */ mIntentListenerCallback,
                /* displayCategories= */ new ArraySet<>(),
                /* showTasksInHostDeviceRecents= */ true,
                /* customHomeComponent= */ null);
    }

    private GenericWindowPolicyController createGwpcWithNoAllowedUsers() {
        return new GenericWindowPolicyController(
                0,
                0,
                /* allowedUsers= */ new ArraySet<>(),
                /* activityLaunchAllowedByDefault= */ true,
                /* activityPolicyExemptions= */ new ArraySet<>(),
                /* crossTaskNavigationAllowedByDefault= */ true,
                /* crossTaskNavigationExemptions= */ new ArraySet<>(),
                /* permissionDialogComponent= */ null,
                /* activityListener= */ mActivityListener,
                /* pipBlockedCallback= */ mPipBlockedCallback,
                /* activityBlockedCallback= */ mActivityBlockedCallback,
                /* secureWindowCallback= */ mSecureWindowCallback,
                /* intentListenerCallback= */ mIntentListenerCallback,
                /* displayCategories= */ new ArraySet<>(),
                /* showTasksInHostDeviceRecents= */ true,
                /* customHomeComponent= */ null);
    }

    private GenericWindowPolicyController createGwpcWithCustomHomeComponent(
            ComponentName homeComponent) {
        return new GenericWindowPolicyController(
                0,
                0,
                /* allowedUsers= */ new ArraySet<>(getCurrentUserId()),
                /* activityLaunchAllowedByDefault= */ true,
                /* activityPolicyExemptions= */ new ArraySet<>(),
                /* crossTaskNavigationAllowedByDefault= */ true,
                /* crossTaskNavigationExemptions= */ new ArraySet<>(),
                /* permissionDialogComponent= */ null,
                /* activityListener= */ mActivityListener,
                /* pipBlockedCallback= */ mPipBlockedCallback,
                /* activityBlockedCallback= */ mActivityBlockedCallback,
                /* secureWindowCallback= */ null,
                /* intentListenerCallback= */ mIntentListenerCallback,
                /* displayCategories= */ new ArraySet<>(),
                /* showTasksInHostDeviceRecents= */ true,
                /* customHomeComponent= */ homeComponent);
    }

    private GenericWindowPolicyController createGwpcWithBlockedComponent(
            ComponentName blockedComponent) {
        return new GenericWindowPolicyController(
                0,
                0,
                /* allowedUsers= */ new ArraySet<>(getCurrentUserId()),
                /* activityLaunchAllowedByDefault= */ true,
                /* activityPolicyExemptions= */ Collections.singleton(blockedComponent),
                /* crossTaskNavigationAllowedByDefault= */ true,
                /* crossTaskNavigationExemptions= */ new ArraySet<>(),
                /* permissionDialogComponent= */ null,
                /* activityListener= */ mActivityListener,
                /* pipBlockedCallback= */ mPipBlockedCallback,
                /* activityBlockedCallback= */ mActivityBlockedCallback,
                /* secureWindowCallback= */ null,
                /* intentListenerCallback= */ mIntentListenerCallback,
                /* displayCategories= */ new ArraySet<>(),
                /* showTasksInHostDeviceRecents= */ true,
                /* customHomeComponent= */ null);
    }

    private GenericWindowPolicyController createGwpcWithAllowedComponent(
            ComponentName allowedComponent) {
        return new GenericWindowPolicyController(
                0,
                0,
                /* allowedUsers= */ new ArraySet<>(getCurrentUserId()),
                /* activityLaunchAllowedByDefault= */ false,
                /* activityPolicyExemptions= */ Collections.singleton(allowedComponent),
                /* crossTaskNavigationAllowedByDefault= */ true,
                /* crossTaskNavigationExemptions= */ new ArraySet<>(),
                /* permissionDialogComponent= */ null,
                /* activityListener= */ mActivityListener,
                /* pipBlockedCallback= */ mPipBlockedCallback,
                /* activityBlockedCallback= */ mActivityBlockedCallback,
                /* secureWindowCallback= */ null,
                /* intentListenerCallback= */ mIntentListenerCallback,
                /* displayCategories= */ new ArraySet<>(),
                /* showTasksInHostDeviceRecents= */ true,
                /* customHomeComponent= */ null);
    }

    private GenericWindowPolicyController createGwpcWithDisplayCategory(
            String displayCategory) {
        return new GenericWindowPolicyController(
                0,
                0,
                /* allowedUsers= */ new ArraySet<>(getCurrentUserId()),
                /* activityLaunchAllowedByDefault= */ true,
                /* activityPolicyExemptions= */ new ArraySet<>(),
                /* crossTaskNavigationAllowedByDefault= */ true,
                /* crossTaskNavigationExemptions= */ new ArraySet<>(),
                /* permissionDialogComponent= */ null,
                /* activityListener= */ mActivityListener,
                /* pipBlockedCallback= */ mPipBlockedCallback,
                /* activityBlockedCallback= */ mActivityBlockedCallback,
                /* secureWindowCallback= */ null,
                /* intentListenerCallback= */ mIntentListenerCallback,
                /* displayCategories= */ Collections.singleton(displayCategory),
                /* showTasksInHostDeviceRecents= */ true,
                /* customHomeComponent= */ null);
    }

    private GenericWindowPolicyController createGwpcWithCrossTaskNavigationBlockedFor(
            ComponentName blockedComponent) {
        return new GenericWindowPolicyController(
                0,
                0,
                /* allowedUsers= */ new ArraySet<>(getCurrentUserId()),
                /* activityLaunchAllowedByDefault= */ true,
                /* activityPolicyExemptions= */ new ArraySet<>(),
                /* crossTaskNavigationAllowedByDefault= */ true,
                /* crossTaskNavigationExemptions= */ Collections.singleton(blockedComponent),
                /* permissionDialogComponent= */ null,
                /* activityListener= */ mActivityListener,
                /* pipBlockedCallback= */ mPipBlockedCallback,
                /* activityBlockedCallback= */ mActivityBlockedCallback,
                /* secureWindowCallback= */ null,
                /* intentListenerCallback= */ mIntentListenerCallback,
                /* displayCategories= */ new ArraySet<>(),
                /* showTasksInHostDeviceRecents= */ true,
                /* customHomeComponent= */ null);
    }

    private GenericWindowPolicyController createGwpcWithCrossTaskNavigationAllowed(
            ComponentName allowedComponent) {
        return new GenericWindowPolicyController(
                0,
                0,
                /* allowedUsers= */ new ArraySet<>(getCurrentUserId()),
                /* activityLaunchAllowedByDefault= */ true,
                /* activityPolicyExemptions= */ new ArraySet<>(),
                /* crossTaskNavigationAllowedByDefault= */ false,
                /* crossTaskNavigationExemptions= */ Collections.singleton(allowedComponent),
                /* permissionDialogComponent= */ null,
                /* activityListener= */ mActivityListener,
                /* pipBlockedCallback= */ mPipBlockedCallback,
                /* activityBlockedCallback= */ mActivityBlockedCallback,
                /* secureWindowCallback= */ null,
                /* intentListenerCallback= */ mIntentListenerCallback,
                /* displayCategories= */ new ArraySet<>(),
                /* showTasksInHostDeviceRecents= */ true,
                /* customHomeComponent= */ null);
    }

    private GenericWindowPolicyController createGwpcWithPermissionComponent(
            ComponentName permissionComponent) {
        //TODO instert the component
        return new GenericWindowPolicyController(
                0,
                0,
                /* allowedUsers= */ new ArraySet<>(getCurrentUserId()),
                /* activityLaunchAllowedByDefault= */ true,
                /* activityPolicyExemptions= */ new ArraySet<>(),
                /* crossTaskNavigationAllowedByDefault= */ false,
                /* crossTaskNavigationExemptions= */ new ArraySet<>(),
                /* permissionDialogComponent= */ permissionComponent,
                /* activityListener= */ mActivityListener,
                /* pipBlockedCallback= */ mPipBlockedCallback,
                /* activityBlockedCallback= */ mActivityBlockedCallback,
                /* secureWindowCallback= */ null,
                /* intentListenerCallback= */ mIntentListenerCallback,
                /* displayCategories= */ new ArraySet<>(),
                /* showTasksInHostDeviceRecents= */ true,
                /* customHomeComponent= */ null);
    }

    private Set<UserHandle> getCurrentUserId() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return new ArraySet<>(Arrays.asList(context.getUser()));
    }

    private ActivityInfo getActivityInfo(
            String packageName, String name, boolean displayOnRemoteDevices,
            String requiredDisplayCategory) {
        return getActivityInfo(packageName, name, displayOnRemoteDevices, requiredDisplayCategory,
                0);
    }

    private ActivityInfo getActivityInfo(
            String packageName, String name, boolean displayOnRemoteDevices,
            String requiredDisplayCategory, int uid) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = uid;

        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = packageName;
        activityInfo.name = name;
        activityInfo.flags = displayOnRemoteDevices
                ? FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES : FLAG_CANNOT_DISPLAY_ON_REMOTE_DEVICES;
        activityInfo.applicationInfo = applicationInfo;
        activityInfo.requiredDisplayCategory = requiredDisplayCategory;
        return activityInfo;
    }

    private void assertActivityCanBeLaunched(GenericWindowPolicyController gwpc,
            ActivityInfo activityInfo) {
        assertActivityCanBeLaunched(gwpc, DISPLAY_ID, false,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, activityInfo);
    }

    private void assertActivityCanBeLaunched(GenericWindowPolicyController gwpc, int fromDisplay,
            boolean isNewTask, int windowingMode, ActivityInfo activityInfo) {
        assertThat(gwpc.canActivityBeLaunched(activityInfo, null, windowingMode, fromDisplay,
                isNewTask)).isTrue();

        verify(mActivityBlockedCallback, after(TIMEOUT_MILLIS).never())
                .onActivityBlocked(fromDisplay, activityInfo);
        verify(mIntentListenerCallback, never()).shouldInterceptIntent(any(Intent.class));
    }

    private void assertActivityIsBlocked(GenericWindowPolicyController gwpc,
            ActivityInfo activityInfo) {
        assertActivityIsBlocked(gwpc, DISPLAY_ID, false,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, activityInfo);
    }

    private void assertActivityIsBlocked(GenericWindowPolicyController gwpc, int fromDisplay,
            boolean isNewTask, int windowingMode, ActivityInfo activityInfo) {
        assertThat(gwpc.canActivityBeLaunched(activityInfo, null, windowingMode, fromDisplay,
                isNewTask)).isFalse();

        verify(mActivityBlockedCallback, timeout(TIMEOUT_MILLIS))
                .onActivityBlocked(fromDisplay, activityInfo);
        verify(mIntentListenerCallback, after(TIMEOUT_MILLIS).never())
                .shouldInterceptIntent(any(Intent.class));
    }

    private void assertNoActivityLaunched(GenericWindowPolicyController gwpc, int fromDisplay,
            ActivityInfo activityInfo) {
        assertThat(gwpc.canActivityBeLaunched(activityInfo, null,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID, true))
                .isFalse();

        verify(mActivityBlockedCallback, after(TIMEOUT_MILLIS).never())
                .onActivityBlocked(fromDisplay, activityInfo);
        verify(mIntentListenerCallback, never()).shouldInterceptIntent(any(Intent.class));
    }
}
