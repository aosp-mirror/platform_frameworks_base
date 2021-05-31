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

package com.android.server.accessibility;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static junit.framework.Assert.assertFalse;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.assertTrue;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AppOpsManager;
import android.appwidget.AppWidgetManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.SigningInfo;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.testing.DexmakerShareClassLoaderRule;
import android.testing.TestableContext;
import android.util.ArraySet;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Tests for the AccessibilitySecurityPolicy
 */
public class AccessibilitySecurityPolicyTest {
    private static final String PACKAGE_NAME = "com.android.server.accessibility";
    private static final String PACKAGE_NAME2 = "com.android.server.accessibility2";
    private static final int WINDOWID = 0x000a;
    private static final int WINDOWID2 = 0x000b;
    private static final int APP_UID = 10400;
    private static final int APP_PID = 2000;
    private static final int SYSTEM_PID = 558;
    private static final int TEST_USER_ID = UserHandle.USER_SYSTEM;
    private static final String TEST_PACKAGE_NAME = "com.android.server.accessibility";
    private static final ComponentName TEST_COMPONENT_NAME = new ComponentName(
            TEST_PACKAGE_NAME, "AccessibilitySecurityPolicyTest");
    private static final String ALLOWED_INSTALL_PACKAGE_NAME = "com.allowed.install.package";

    private static final int[] ALWAYS_DISPATCH_EVENTS = {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_END,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT,
            AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
    };
    private static final int[] OTHER_EVENTS = {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED,
    };

    private AccessibilitySecurityPolicy mA11ySecurityPolicy;

    @Rule
    public final TestableContext mContext = new TestableContext(
            getInstrumentation().getTargetContext(), null);

    // To mock package-private class
    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private UserManager mMockUserManager;
    @Mock
    private AppOpsManager mMockAppOpsManager;
    @Mock
    private AccessibilityServiceConnection mMockA11yServiceConnection;
    @Mock
    private AccessibilityWindowManager mMockA11yWindowManager;
    @Mock
    private AppWidgetManagerInternal mMockAppWidgetManager;
    @Mock
    private AccessibilitySecurityPolicy.AccessibilityUserManager mMockA11yUserManager;
    @Mock
    private AccessibilityServiceInfo mMockA11yServiceInfo;
    @Mock
    private ResolveInfo mMockResolveInfo;
    @Mock
    private ServiceInfo mMockServiceInfo;
    @Mock
    private ApplicationInfo mMockApplicationInfo;
    @Mock
    private ApplicationInfo mMockSourceApplicationInfo;
    @Mock
    private PackageInfo mMockSourcePackageInfo;
    @Mock
    private PolicyWarningUIController mPolicyWarningUIController;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        MockitoAnnotations.initMocks(this);
        mContext.setMockPackageManager(mMockPackageManager);
        mContext.addMockSystemService(Context.USER_SERVICE, mMockUserManager);
        mContext.addMockSystemService(Context.APP_OPS_SERVICE, mMockAppOpsManager);
        mContext.getOrCreateTestableResources().addOverride(
                R.dimen.accessibility_focus_highlight_stroke_width, 1);
        mContext.getOrCreateTestableResources().addOverride(R.array
                        .config_accessibility_allowed_install_source,
                new String[]{ALLOWED_INSTALL_PACKAGE_NAME});

        when(mMockA11yServiceInfo.getResolveInfo()).thenReturn(mMockResolveInfo);
        when(mMockA11yServiceInfo.getComponentName()).thenReturn(TEST_COMPONENT_NAME);
        when(mMockA11yServiceConnection.getServiceInfo()).thenReturn(mMockA11yServiceInfo);
        when(mMockPackageManager.getPackageInfo(ALLOWED_INSTALL_PACKAGE_NAME, 0)).thenReturn(
                mMockSourcePackageInfo);

        mMockResolveInfo.serviceInfo = mMockServiceInfo;
        mMockServiceInfo.applicationInfo = mMockApplicationInfo;
        mMockServiceInfo.packageName = TEST_PACKAGE_NAME;
        mMockSourcePackageInfo.applicationInfo = mMockSourceApplicationInfo;

        mA11ySecurityPolicy = new AccessibilitySecurityPolicy(
                mPolicyWarningUIController, mContext, mMockA11yUserManager);
        mA11ySecurityPolicy.setAccessibilityWindowManager(mMockA11yWindowManager);
        mA11ySecurityPolicy.setAppWidgetManager(mMockAppWidgetManager);
        mA11ySecurityPolicy.onSwitchUserLocked(TEST_USER_ID, new HashSet<>());

        when(mMockA11yWindowManager.resolveParentWindowIdLocked(anyInt())).then(returnsFirstArg());
    }

    @Test
    public void canDispatchAccessibilityEvent_alwaysDispatchEvents_returnTrue() {
        for (int i = 0; i < ALWAYS_DISPATCH_EVENTS.length; i++) {
            final AccessibilityEvent event = AccessibilityEvent.obtain(ALWAYS_DISPATCH_EVENTS[i]);
            assertTrue("Should dispatch [" + event + "]",
                    mA11ySecurityPolicy.canDispatchAccessibilityEventLocked(
                            TEST_USER_ID,
                            event));
        }
    }

    @Test
    public void canDispatchAccessibilityEvent_otherEvents_invalidWindowId_returnFalse() {
        final int invalidWindowId = WINDOWID;
        for (int i = 0; i < OTHER_EVENTS.length; i++) {
            final AccessibilityEvent event = AccessibilityEvent.obtain(OTHER_EVENTS[i]);
            event.setWindowId(invalidWindowId);
            assertFalse("Shouldn't dispatch [" + event + "]",
                    mA11ySecurityPolicy.canDispatchAccessibilityEventLocked(
                            TEST_USER_ID,
                            event));
        }
    }

    @Test
    public void canDispatchAccessibilityEvent_otherEvents_windowIdIsActive_returnTrue() {
        when(mMockA11yWindowManager.getActiveWindowId(TEST_USER_ID))
                .thenReturn(WINDOWID);
        for (int i = 0; i < OTHER_EVENTS.length; i++) {
            final AccessibilityEvent event = AccessibilityEvent.obtain(OTHER_EVENTS[i]);
            event.setWindowId(WINDOWID);
            assertTrue("Should dispatch [" + event + "]",
                    mA11ySecurityPolicy.canDispatchAccessibilityEventLocked(
                            TEST_USER_ID,
                            event));
        }
    }

    @Test
    public void canDispatchAccessibilityEvent_otherEvents_windowIdExist_returnTrue() {
        when(mMockA11yWindowManager.getActiveWindowId(TEST_USER_ID))
                .thenReturn(WINDOWID2);
        when(mMockA11yWindowManager.findA11yWindowInfoByIdLocked(WINDOWID))
                .thenReturn(AccessibilityWindowInfo.obtain());
        for (int i = 0; i < OTHER_EVENTS.length; i++) {
            final AccessibilityEvent event = AccessibilityEvent.obtain(OTHER_EVENTS[i]);
            event.setWindowId(WINDOWID);
            assertTrue("Should dispatch [" + event + "]",
                    mA11ySecurityPolicy.canDispatchAccessibilityEventLocked(
                            TEST_USER_ID,
                            event));
        }
    }

    @Test
    public void resolveValidReportedPackage_nullPkgName_returnNull() {
        assertNull(mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                null, Process.SYSTEM_UID, TEST_USER_ID, SYSTEM_PID));
    }

    @Test
    public void resolveValidReportedPackage_uidIsSystem_returnPkgName() {
        assertEquals(mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                PACKAGE_NAME, Process.SYSTEM_UID, TEST_USER_ID, SYSTEM_PID),
                PACKAGE_NAME);
    }

    @Test
    public void resolveValidReportedPackage_uidAndPkgNameMatched_returnPkgName()
            throws PackageManager.NameNotFoundException {
        when(mMockPackageManager.getPackageUidAsUser(PACKAGE_NAME,
                PackageManager.MATCH_ANY_USER, TEST_USER_ID)).thenReturn(APP_UID);

        assertEquals(mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                PACKAGE_NAME, APP_UID, TEST_USER_ID, APP_PID),
                PACKAGE_NAME);
    }

    @Test
    public void resolveValidReportedPackage_uidIsWidgetHost_pkgNameIsAppWidget_returnPkgName()
            throws PackageManager.NameNotFoundException {
        final int widgetHostUid = APP_UID;
        final int widgetHostPid = APP_PID;
        final String hostPackageName = PACKAGE_NAME;
        final String widgetPackageName = PACKAGE_NAME2;
        final ArraySet<String> widgetPackages = new ArraySet<>();
        widgetPackages.add(widgetPackageName);

        when(mMockAppWidgetManager.getHostedWidgetPackages(widgetHostUid))
                .thenReturn(widgetPackages);
        when(mMockPackageManager.getPackageUidAsUser(hostPackageName, TEST_USER_ID))
                .thenReturn(widgetHostUid);

        assertEquals(mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                widgetPackageName, widgetHostUid, TEST_USER_ID, widgetHostPid),
                widgetPackageName);
    }

    @Test
    public void resolveValidReportedPackage_pkgNameIsInvalid_returnFirstCorrectPkgName()
            throws PackageManager.NameNotFoundException {
        final String invalidPackageName = "x";
        final String[] uidPackages = {PACKAGE_NAME, PACKAGE_NAME2};
        when(mMockPackageManager.getPackagesForUid(APP_UID))
                .thenReturn(uidPackages);
        when(mMockPackageManager.getPackageUidAsUser(invalidPackageName, TEST_USER_ID))
                .thenThrow(PackageManager.NameNotFoundException.class);
        when(mMockAppWidgetManager.getHostedWidgetPackages(APP_UID))
                .thenReturn(new ArraySet<>());
        mContext.getTestablePermissions().setPermission(
                Manifest.permission.ACT_AS_PACKAGE_FOR_ACCESSIBILITY,
                PackageManager.PERMISSION_DENIED);

        assertEquals(PACKAGE_NAME, mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                invalidPackageName, APP_UID, TEST_USER_ID, APP_PID));
    }

    @Test
    public void resolveValidReportedPackage_anotherPkgNameWithActAsPkgPermission_returnPkg()
            throws PackageManager.NameNotFoundException {
        final String wantedPackageName = PACKAGE_NAME2;
        final int wantedUid = APP_UID + 1;
        final String[] uidPackages = {PACKAGE_NAME};
        when(mMockPackageManager.getPackagesForUid(APP_UID))
                .thenReturn(uidPackages);
        when(mMockPackageManager.getPackageUidAsUser(wantedPackageName, TEST_USER_ID))
                .thenReturn(wantedUid);
        when(mMockAppWidgetManager.getHostedWidgetPackages(APP_UID))
                .thenReturn(new ArraySet<>());
        mContext.getTestablePermissions().setPermission(
                Manifest.permission.ACT_AS_PACKAGE_FOR_ACCESSIBILITY,
                PackageManager.PERMISSION_GRANTED);

        assertEquals(wantedPackageName, mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                wantedPackageName, APP_UID, TEST_USER_ID, APP_PID));
    }

    @Test
    public void resolveValidReportedPackage_anotherPkgNameWithoutActAsPkgPermission_returnUidPkg()
            throws PackageManager.NameNotFoundException {
        final String wantedPackageName = PACKAGE_NAME2;
        final int wantedUid = APP_UID + 1;
        final String[] uidPackages = {PACKAGE_NAME};
        when(mMockPackageManager.getPackagesForUid(APP_UID))
                .thenReturn(uidPackages);
        when(mMockPackageManager.getPackageUidAsUser(wantedPackageName, TEST_USER_ID))
                .thenReturn(wantedUid);
        when(mMockAppWidgetManager.getHostedWidgetPackages(APP_UID))
                .thenReturn(new ArraySet<>());
        mContext.getTestablePermissions().setPermission(
                Manifest.permission.ACT_AS_PACKAGE_FOR_ACCESSIBILITY,
                PackageManager.PERMISSION_DENIED);

        assertEquals(PACKAGE_NAME, mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                wantedPackageName, APP_UID, TEST_USER_ID, APP_PID));
    }

    @Test
    public void computeValidReportedPackages_uidIsSystem_returnEmptyArray() {
        assertThat(mA11ySecurityPolicy.computeValidReportedPackages(
                PACKAGE_NAME, Process.SYSTEM_UID), emptyArray());
    }

    @Test
    public void computeValidReportedPackages_uidIsAppWidgetHost_returnTargetAndWidgetName() {
        final int widgetHostUid = APP_UID;
        final String targetPackageName = PACKAGE_NAME;
        final String widgetPackageName = PACKAGE_NAME2;
        final ArraySet<String> widgetPackages = new ArraySet<>();
        widgetPackages.add(widgetPackageName);
        when(mMockAppWidgetManager.getHostedWidgetPackages(widgetHostUid))
                .thenReturn(widgetPackages);

        List<String> packages = Arrays.asList(mA11ySecurityPolicy.computeValidReportedPackages(
                targetPackageName, widgetHostUid));
        assertThat(packages, hasSize(2));
        assertThat(packages, containsInAnyOrder(targetPackageName, widgetPackageName));
    }

    @Test
    public void canGetAccessibilityNodeInfo_windowIdNotExist_returnFalse() {
        final int invalidWindowId = WINDOWID;
        when(mMockA11yServiceConnection.getCapabilities())
                .thenReturn(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);

        assertFalse(mA11ySecurityPolicy.canGetAccessibilityNodeInfoLocked(TEST_USER_ID,
                mMockA11yServiceConnection, invalidWindowId));
    }

    @Test
    public void canGetAccessibilityNodeInfo_hasCapAndWindowIsActive_returnTrue() {
        when(mMockA11yServiceConnection.getCapabilities())
                .thenReturn(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
        when(mMockA11yWindowManager.getActiveWindowId(TEST_USER_ID))
                .thenReturn(WINDOWID);

        assertTrue(mA11ySecurityPolicy.canGetAccessibilityNodeInfoLocked(TEST_USER_ID,
                mMockA11yServiceConnection, WINDOWID));
    }

    @Test
    public void canGetAccessibilityNodeInfo_hasCapAndWindowExist_returnTrue() {
        when(mMockA11yServiceConnection.getCapabilities())
                .thenReturn(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
        when(mMockA11yWindowManager.getActiveWindowId(TEST_USER_ID))
                .thenReturn(WINDOWID2);
        when(mMockA11yWindowManager.findA11yWindowInfoByIdLocked(WINDOWID))
                .thenReturn(AccessibilityWindowInfo.obtain());

        assertTrue(mA11ySecurityPolicy.canGetAccessibilityNodeInfoLocked(TEST_USER_ID,
                mMockA11yServiceConnection, WINDOWID));
    }

    @Test
    public void canRetrieveWindows_retrieveWindowsFlagIsFalse_returnFalse() {
        when(mMockA11yServiceConnection.getCapabilities())
                .thenReturn(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);

        assertFalse(mA11ySecurityPolicy.canRetrieveWindowsLocked(mMockA11yServiceConnection));
    }

    @Test
    public void canRetrieveWindows_hasCapabilityAndRetrieveWindowsFlag_returnTrue() {
        when(mMockA11yServiceConnection.getCapabilities())
                .thenReturn(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
        mMockA11yServiceConnection.mRetrieveInteractiveWindows = true;

        assertTrue(mA11ySecurityPolicy.canRetrieveWindowsLocked(mMockA11yServiceConnection));
    }

    @Test
    public void canRetrieveWindowContent_hasCapability_returnTrue() {
        when(mMockA11yServiceConnection.getCapabilities())
                .thenReturn(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);

        assertTrue(mA11ySecurityPolicy.canRetrieveWindowContentLocked(mMockA11yServiceConnection));
    }

    @Test
    public void canControlMagnification_hasCapability_returnTrue() {
        when(mMockA11yServiceConnection.getCapabilities())
                .thenReturn(AccessibilityServiceInfo.CAPABILITY_CAN_CONTROL_MAGNIFICATION);

        assertTrue(mA11ySecurityPolicy.canControlMagnification(mMockA11yServiceConnection));
    }

    @Test
    public void canPerformGestures_hasCapability_returnTrue() {
        assertFalse(mA11ySecurityPolicy.canPerformGestures(mMockA11yServiceConnection));
        when(mMockA11yServiceConnection.getCapabilities())
                .thenReturn(AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES);

        assertTrue(mA11ySecurityPolicy.canPerformGestures(mMockA11yServiceConnection));
    }

    @Test
    public void canCaptureFingerprintGestures_hasCapability_returnTrue() {
        assertFalse(mA11ySecurityPolicy.canCaptureFingerprintGestures(mMockA11yServiceConnection));
        when(mMockA11yServiceConnection.getCapabilities())
                .thenReturn(AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES);

        assertTrue(mA11ySecurityPolicy.canCaptureFingerprintGestures(mMockA11yServiceConnection));
    }

    @Test
    public void canTakeScreenshot_hasCapability_returnTrue() {
        when(mMockA11yServiceConnection.getCapabilities())
                .thenReturn(AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT);

        assertTrue(mA11ySecurityPolicy.canTakeScreenshotLocked(mMockA11yServiceConnection));
    }

    @Test
    public void resolveProfileParent_userIdIsCurrentUser_returnCurrentUser() {
        final int currentUserId = 10;
        final int userId = currentUserId;
        when(mMockA11yUserManager.getCurrentUserIdLocked())
                .thenReturn(currentUserId);

        assertEquals(mA11ySecurityPolicy.resolveProfileParentLocked(userId),
                currentUserId);
    }

    @Test
    public void resolveProfileParent_userIdNotCurrentUser_shouldGetProfileParent() {
        final int userId = 15;
        final int currentUserId = 20;
        when(mMockA11yUserManager.getCurrentUserIdLocked()).thenReturn(currentUserId);

        mA11ySecurityPolicy.resolveProfileParentLocked(userId);
        verify(mMockUserManager).getProfileParent(userId);
    }

    @Test
    public void resolveCallingUserId_userIdIsCallingUser_shouldResolveProfileParent() {
        final AccessibilitySecurityPolicy spySecurityPolicy = Mockito.spy(mA11ySecurityPolicy);
        final int callingUserId = UserHandle.getUserId(Process.myUid());
        final int userId = callingUserId;

        spySecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(userId);
        verify(spySecurityPolicy).resolveProfileParentLocked(userId);
    }

    @Test
    public void resolveCallingUserId_callingParentIsCurrentUser_returnCurrentUser() {
        final AccessibilitySecurityPolicy spySecurityPolicy = Mockito.spy(mA11ySecurityPolicy);
        final int callingUserId = UserHandle.getUserId(Process.myUid());
        final int callingParentId = 20;
        final int currentUserId = callingParentId;
        when(mMockA11yUserManager.getCurrentUserIdLocked())
                .thenReturn(currentUserId);
        doReturn(callingParentId).when(spySecurityPolicy).resolveProfileParentLocked(
                callingUserId);

        assertEquals(spySecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(
                UserHandle.USER_CURRENT_OR_SELF), currentUserId);

    }

    @Test(expected = SecurityException.class)
    public void resolveCallingUserId_callingParentNotCurrentUserAndNoPerm_shouldException() {
        final AccessibilitySecurityPolicy spySecurityPolicy = Mockito.spy(mA11ySecurityPolicy);
        final int callingUserId = UserHandle.getUserId(Process.myUid());
        final int callingParentId = 20;
        final int currentUserId = 30;
        when(mMockA11yUserManager.getCurrentUserIdLocked())
                .thenReturn(currentUserId);
        doReturn(callingParentId).when(spySecurityPolicy).resolveProfileParentLocked(
                callingUserId);
        mContext.getTestablePermissions().setPermission(Manifest.permission.INTERACT_ACROSS_USERS,
                PackageManager.PERMISSION_DENIED);
        mContext.getTestablePermissions().setPermission(
                Manifest.permission.INTERACT_ACROSS_USERS_FULL, PackageManager.PERMISSION_DENIED);

        spySecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(
                UserHandle.USER_CURRENT_OR_SELF);
    }

    @Test
    public void resolveCallingUserId_anotherUserIdWithCrossUserPermission_returnUserId() {
        final AccessibilitySecurityPolicy spySecurityPolicy = Mockito.spy(mA11ySecurityPolicy);
        final int callingUserId = UserHandle.getUserId(Process.myUid());
        final int callingParentId = 20;
        final int currentUserId = 30;
        final int wantedUserId = 40;
        when(mMockA11yUserManager.getCurrentUserIdLocked())
                .thenReturn(currentUserId);
        doReturn(callingParentId).when(spySecurityPolicy).resolveProfileParentLocked(
                callingUserId);
        mContext.getTestablePermissions().setPermission(Manifest.permission.INTERACT_ACROSS_USERS,
                PackageManager.PERMISSION_GRANTED);

        assertEquals(wantedUserId,
                spySecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(wantedUserId));
    }

    @Test
    public void resolveCallingUserId_anotherUserIdWithCrossUserFullPermission_returnUserId() {
        final AccessibilitySecurityPolicy spySecurityPolicy = Mockito.spy(mA11ySecurityPolicy);
        final int callingUserId = UserHandle.getUserId(Process.myUid());
        final int callingParentId = 20;
        final int currentUserId = 30;
        final int wantedUserId = 40;
        when(mMockA11yUserManager.getCurrentUserIdLocked())
                .thenReturn(currentUserId);
        doReturn(callingParentId).when(spySecurityPolicy).resolveProfileParentLocked(
                callingUserId);
        mContext.getTestablePermissions().setPermission(
                Manifest.permission.INTERACT_ACROSS_USERS_FULL, PackageManager.PERMISSION_GRANTED);

        assertEquals(wantedUserId,
                spySecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(wantedUserId));
    }

    @Test(expected = SecurityException.class)
    public void resolveCallingUserId_anotherUserIdWithoutCrossUserPermission_shouldException() {
        final AccessibilitySecurityPolicy spySecurityPolicy = Mockito.spy(mA11ySecurityPolicy);
        final int callingUserId = UserHandle.getUserId(Process.myUid());
        final int callingParentId = 20;
        final int currentUserId = 30;
        final int wantedUserId = 40;
        when(mMockA11yUserManager.getCurrentUserIdLocked())
                .thenReturn(currentUserId);
        doReturn(callingParentId).when(spySecurityPolicy).resolveProfileParentLocked(
                callingUserId);
        mContext.getTestablePermissions().setPermission(Manifest.permission.INTERACT_ACROSS_USERS,
                PackageManager.PERMISSION_DENIED);
        mContext.getTestablePermissions().setPermission(
                Manifest.permission.INTERACT_ACROSS_USERS_FULL, PackageManager.PERMISSION_DENIED);

        spySecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(wantedUserId);
    }

    @Test
    public void canRegisterService_shouldCheckAppOps() {
        final ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.applicationInfo = new ApplicationInfo();
        serviceInfo.packageName = PACKAGE_NAME;
        serviceInfo.name = AccessibilitySecurityPolicyTest.class.getSimpleName();

        assertFalse(mA11ySecurityPolicy.canRegisterService(serviceInfo));
        serviceInfo.permission = android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE;
        mA11ySecurityPolicy.canRegisterService(serviceInfo);
        verify(mMockAppOpsManager).noteOpNoThrow(AppOpsManager.OPSTR_BIND_ACCESSIBILITY_SERVICE,
                serviceInfo.applicationInfo.uid, serviceInfo.packageName);
    }

    @Test
    public void checkAccessibilityAccess_shouldCheckAppOps() {
        final AccessibilityServiceInfo mockServiceInfo = Mockito.mock(
                AccessibilityServiceInfo.class);
        final ResolveInfo mockResolveInfo = Mockito.mock(
                ResolveInfo.class);
        final ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.applicationInfo = new ApplicationInfo();
        serviceInfo.applicationInfo.uid = APP_UID;
        mockResolveInfo.serviceInfo = serviceInfo;

        when(mMockA11yServiceConnection.getServiceInfo()).thenReturn(mockServiceInfo);
        when(mockServiceInfo.getResolveInfo()).thenReturn(mockResolveInfo);
        when(mMockA11yServiceConnection.getComponentName())
                .thenReturn(new ComponentName(
                        PACKAGE_NAME, AccessibilitySecurityPolicyTest.class.getSimpleName()));

        mA11ySecurityPolicy.checkAccessibilityAccess(mMockA11yServiceConnection);
        verify(mMockAppOpsManager).noteOpNoThrow(AppOpsManager.OPSTR_ACCESS_ACCESSIBILITY,
                APP_UID, PACKAGE_NAME);
    }

    @Test
    public void onBoundServicesChanged_bindNonA11yToolService_activateUIControllerAction() {
        final ArrayList<AccessibilityServiceConnection> boundServices = new ArrayList<>();
        boundServices.add(mMockA11yServiceConnection);
        when(mMockA11yServiceInfo.isAccessibilityTool()).thenReturn(false);

        mA11ySecurityPolicy.onBoundServicesChangedLocked(TEST_USER_ID, boundServices);

        verify(mPolicyWarningUIController).onNonA11yCategoryServiceBound(eq(TEST_USER_ID),
                eq(TEST_COMPONENT_NAME));
    }

    @Test
    public void onBoundServicesChanged_unbindNonA11yToolService_activateUIControllerAction() {
        onBoundServicesChanged_bindNonA11yToolService_activateUIControllerAction();

        mA11ySecurityPolicy.onBoundServicesChangedLocked(TEST_USER_ID, new ArrayList<>());

        verify(mPolicyWarningUIController).onNonA11yCategoryServiceUnbound(eq(TEST_USER_ID),
                eq(TEST_COMPONENT_NAME));
    }

    @Test
    public void onBoundServicesChanged_bindSystemA11yToolService_noUIControllerAction() {
        final ArrayList<AccessibilityServiceConnection> boundServices = new ArrayList<>();
        boundServices.add(mMockA11yServiceConnection);
        when(mMockApplicationInfo.isSystemApp()).thenReturn(true);
        when(mMockA11yServiceInfo.isAccessibilityTool()).thenReturn(true);

        mA11ySecurityPolicy.onBoundServicesChangedLocked(TEST_USER_ID, boundServices);

        verify(mPolicyWarningUIController, never()).onNonA11yCategoryServiceBound(anyInt(), any());
    }

    @Test
    public void onBoundServicesChanged_unbindSystemA11yToolService_noUIControllerAction() {
        onBoundServicesChanged_bindSystemA11yToolService_noUIControllerAction();

        mA11ySecurityPolicy.onBoundServicesChangedLocked(TEST_USER_ID, new ArrayList<>());

        verify(mPolicyWarningUIController, never()).onNonA11yCategoryServiceUnbound(anyInt(),
                any());
    }

    @Test
    public void onBoundServicesChanged_bindAllowedSourceA11yToolService_noUIControllerAction()
            throws PackageManager.NameNotFoundException {
        final ArrayList<AccessibilityServiceConnection> boundServices = new ArrayList<>();
        boundServices.add(mMockA11yServiceConnection);
        when(mMockApplicationInfo.isSystemApp()).thenReturn(false);
        final InstallSourceInfo installSourceInfo = new InstallSourceInfo(
                ALLOWED_INSTALL_PACKAGE_NAME, new SigningInfo(), null,
                ALLOWED_INSTALL_PACKAGE_NAME);
        when(mMockPackageManager.getInstallSourceInfo(TEST_PACKAGE_NAME)).thenReturn(
                installSourceInfo);
        when(mMockSourceApplicationInfo.isSystemApp()).thenReturn(true);
        when(mMockA11yServiceInfo.isAccessibilityTool()).thenReturn(true);

        mA11ySecurityPolicy.onBoundServicesChangedLocked(TEST_USER_ID, boundServices);

        verify(mPolicyWarningUIController, never()).onNonA11yCategoryServiceBound(anyInt(), any());
    }

    @Test
    public void onBoundServicesChanged_bindUnknownSourceA11yToolService_activateUIControllerAction()
            throws PackageManager.NameNotFoundException {
        final ArrayList<AccessibilityServiceConnection> boundServices = new ArrayList<>();
        boundServices.add(mMockA11yServiceConnection);
        when(mMockA11yServiceInfo.isAccessibilityTool()).thenReturn(true);
        final InstallSourceInfo installSourceInfo = new InstallSourceInfo(null, null, null, null);
        when(mMockPackageManager.getInstallSourceInfo(TEST_PACKAGE_NAME)).thenReturn(
                installSourceInfo);

        mA11ySecurityPolicy.onBoundServicesChangedLocked(TEST_USER_ID, boundServices);

        verify(mPolicyWarningUIController).onNonA11yCategoryServiceBound(eq(TEST_USER_ID),
                eq(TEST_COMPONENT_NAME));
    }

    @Test
    public void onSwitchUser_differentUser_activateUIControllerAction() {
        onBoundServicesChanged_bindNonA11yToolService_activateUIControllerAction();

        mA11ySecurityPolicy.onSwitchUserLocked(2, new HashSet<>());

        verify(mPolicyWarningUIController).onSwitchUserLocked(eq(2), eq(new HashSet<>()));
        verify(mPolicyWarningUIController).onNonA11yCategoryServiceUnbound(eq(TEST_USER_ID),
                eq(TEST_COMPONENT_NAME));
    }
}
