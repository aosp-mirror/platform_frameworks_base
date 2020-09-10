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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AppOpsManager;
import android.appwidget.AppWidgetManagerInternal;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.testing.DexmakerShareClassLoaderRule;
import android.util.ArraySet;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
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

    private static final String PERMISSION = "test-permission";
    private static final String FUNCTION = "test-function-name";

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

    // To mock package-private class
    @Rule public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    @Mock private Context mMockContext;
    @Mock private PackageManager mMockPackageManager;
    @Mock private UserManager mMockUserManager;
    @Mock private AppOpsManager mMockAppOpsManager;
    @Mock private AccessibilityServiceConnection mMockA11yServiceConnection;
    @Mock private AccessibilityWindowManager mMockA11yWindowManager;
    @Mock private AppWidgetManagerInternal mMockAppWidgetManager;
    @Mock private AccessibilitySecurityPolicy.AccessibilityUserManager mMockA11yUserManager;
    @Mock private ActivityTaskManagerInternal mMockActivityTaskManagerInternal;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mMockContext.getPackageManager()).thenReturn(mMockPackageManager);
        when(mMockContext.getSystemService(Context.USER_SERVICE)).thenReturn(mMockUserManager);
        when(mMockContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mMockAppOpsManager);

        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.addService(
                ActivityTaskManagerInternal.class, mMockActivityTaskManagerInternal);

        mA11ySecurityPolicy = new AccessibilitySecurityPolicy(mMockContext, mMockA11yUserManager);
        mA11ySecurityPolicy.setAccessibilityWindowManager(mMockA11yWindowManager);
        mA11ySecurityPolicy.setAppWidgetManager(mMockAppWidgetManager);

        when(mMockA11yWindowManager.resolveParentWindowIdLocked(anyInt())).then(returnsFirstArg());
    }

    @Test
    public void canDispatchAccessibilityEvent_alwaysDispatchEvents_returnTrue() {
        for (int i = 0; i < ALWAYS_DISPATCH_EVENTS.length; i++) {
            final AccessibilityEvent event = AccessibilityEvent.obtain(ALWAYS_DISPATCH_EVENTS[i]);
            assertTrue("Should dispatch [" + event + "]",
                    mA11ySecurityPolicy.canDispatchAccessibilityEventLocked(
                            UserHandle.USER_SYSTEM,
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
                            UserHandle.USER_SYSTEM,
                            event));
        }
    }

    @Test
    public void canDispatchAccessibilityEvent_otherEvents_windowIdIsActive_returnTrue() {
        when(mMockA11yWindowManager.getActiveWindowId(UserHandle.USER_SYSTEM))
                .thenReturn(WINDOWID);
        for (int i = 0; i < OTHER_EVENTS.length; i++) {
            final AccessibilityEvent event = AccessibilityEvent.obtain(OTHER_EVENTS[i]);
            event.setWindowId(WINDOWID);
            assertTrue("Should dispatch [" + event + "]",
                    mA11ySecurityPolicy.canDispatchAccessibilityEventLocked(
                            UserHandle.USER_SYSTEM,
                            event));
        }
    }

    @Test
    public void canDispatchAccessibilityEvent_otherEvents_windowIdExist_returnTrue() {
        when(mMockA11yWindowManager.getActiveWindowId(UserHandle.USER_SYSTEM))
                .thenReturn(WINDOWID2);
        when(mMockA11yWindowManager.findA11yWindowInfoByIdLocked(WINDOWID))
                .thenReturn(AccessibilityWindowInfo.obtain());
        for (int i = 0; i < OTHER_EVENTS.length; i++) {
            final AccessibilityEvent event = AccessibilityEvent.obtain(OTHER_EVENTS[i]);
            event.setWindowId(WINDOWID);
            assertTrue("Should dispatch [" + event + "]",
                    mA11ySecurityPolicy.canDispatchAccessibilityEventLocked(
                            UserHandle.USER_SYSTEM,
                            event));
        }
    }

    @Test
    public void resolveValidReportedPackage_nullPkgName_returnNull() {
        assertNull(mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                null, Process.SYSTEM_UID, UserHandle.USER_SYSTEM, SYSTEM_PID));
    }

    @Test
    public void resolveValidReportedPackage_uidIsSystem_returnPkgName() {
        assertEquals(mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                PACKAGE_NAME, Process.SYSTEM_UID, UserHandle.USER_SYSTEM, SYSTEM_PID),
                PACKAGE_NAME);
    }

    @Test
    public void resolveValidReportedPackage_uidAndPkgNameMatched_returnPkgName()
            throws PackageManager.NameNotFoundException {
        when(mMockPackageManager.getPackageUidAsUser(PACKAGE_NAME, UserHandle.USER_SYSTEM))
                .thenReturn(APP_UID);

        assertEquals(mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                PACKAGE_NAME, APP_UID, UserHandle.USER_SYSTEM, APP_PID),
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
        when(mMockPackageManager.getPackageUidAsUser(hostPackageName, UserHandle.USER_SYSTEM))
                .thenReturn(widgetHostUid);

        assertEquals(mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                widgetPackageName, widgetHostUid, UserHandle.USER_SYSTEM, widgetHostPid),
                widgetPackageName);
    }

    @Test
    public void resolveValidReportedPackage_pkgNameIsInvalid_returnFirstCorrectPkgName()
            throws PackageManager.NameNotFoundException {
        final String invalidPackageName = "x";
        final String[] uidPackages = {PACKAGE_NAME, PACKAGE_NAME2};
        when(mMockPackageManager.getPackagesForUid(APP_UID))
                .thenReturn(uidPackages);
        when(mMockPackageManager.getPackageUidAsUser(invalidPackageName, UserHandle.USER_SYSTEM))
                .thenThrow(PackageManager.NameNotFoundException.class);
        when(mMockAppWidgetManager.getHostedWidgetPackages(APP_UID))
                .thenReturn(new ArraySet<>());
        when(mMockContext.checkPermission(
                eq(Manifest.permission.ACT_AS_PACKAGE_FOR_ACCESSIBILITY), anyInt(), eq(APP_UID)))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertEquals(PACKAGE_NAME, mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                invalidPackageName, APP_UID, UserHandle.USER_SYSTEM, APP_PID));
    }

    @Test
    public void resolveValidReportedPackage_anotherPkgNameWithActAsPkgPermission_returnPkg()
            throws PackageManager.NameNotFoundException {
        final String wantedPackageName = PACKAGE_NAME2;
        final int wantedUid = APP_UID + 1;
        final String[] uidPackages = {PACKAGE_NAME};
        when(mMockPackageManager.getPackagesForUid(APP_UID))
                .thenReturn(uidPackages);
        when(mMockPackageManager.getPackageUidAsUser(wantedPackageName, UserHandle.USER_SYSTEM))
                .thenReturn(wantedUid);
        when(mMockAppWidgetManager.getHostedWidgetPackages(APP_UID))
                .thenReturn(new ArraySet<>());
        when(mMockContext.checkPermission(
                eq(Manifest.permission.ACT_AS_PACKAGE_FOR_ACCESSIBILITY), anyInt(), eq(APP_UID)))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        assertEquals(wantedPackageName, mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                wantedPackageName, APP_UID, UserHandle.USER_SYSTEM, APP_PID));
    }

    @Test
    public void resolveValidReportedPackage_anotherPkgNameWithoutActAsPkgPermission_returnUidPkg()
            throws PackageManager.NameNotFoundException {
        final String wantedPackageName = PACKAGE_NAME2;
        final int wantedUid = APP_UID + 1;
        final String[] uidPackages = {PACKAGE_NAME};
        when(mMockPackageManager.getPackagesForUid(APP_UID))
                .thenReturn(uidPackages);
        when(mMockPackageManager.getPackageUidAsUser(wantedPackageName, UserHandle.USER_SYSTEM))
                .thenReturn(wantedUid);
        when(mMockAppWidgetManager.getHostedWidgetPackages(APP_UID))
                .thenReturn(new ArraySet<>());
        when(mMockContext.checkPermission(
                eq(Manifest.permission.ACT_AS_PACKAGE_FOR_ACCESSIBILITY), anyInt(), eq(APP_UID)))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertEquals(PACKAGE_NAME, mA11ySecurityPolicy.resolveValidReportedPackageLocked(
                wantedPackageName, APP_UID, UserHandle.USER_SYSTEM, APP_PID));
    }

    @Test
    public void computeValidReportedPackages_uidIsSystem_returnEmptyArray() {
        assertThat(mA11ySecurityPolicy.computeValidReportedPackages(
                PACKAGE_NAME, Process.SYSTEM_UID), emptyArray());
    }

    @Test
    public void computeValidReportedPackages_uidIsAppWidgetHost_returnTargetAndWidgetName() {
        final int widgetHostUid = APP_UID;
        final String targetPackageName =  PACKAGE_NAME;
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

        assertFalse(mA11ySecurityPolicy.canGetAccessibilityNodeInfoLocked(UserHandle.USER_SYSTEM,
                mMockA11yServiceConnection, invalidWindowId));
    }

    @Test
    public void canGetAccessibilityNodeInfo_hasCapAndWindowIsActive_returnTrue() {
        when(mMockA11yServiceConnection.getCapabilities())
                .thenReturn(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
        when(mMockA11yWindowManager.getActiveWindowId(UserHandle.USER_SYSTEM))
                .thenReturn(WINDOWID);

        assertTrue(mA11ySecurityPolicy.canGetAccessibilityNodeInfoLocked(UserHandle.USER_SYSTEM,
                mMockA11yServiceConnection, WINDOWID));
    }

    @Test
    public void canGetAccessibilityNodeInfo_hasCapAndWindowExist_returnTrue() {
        when(mMockA11yServiceConnection.getCapabilities())
                .thenReturn(AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
        when(mMockA11yWindowManager.getActiveWindowId(UserHandle.USER_SYSTEM))
                .thenReturn(WINDOWID2);
        when(mMockA11yWindowManager.findA11yWindowInfoByIdLocked(WINDOWID))
                .thenReturn(AccessibilityWindowInfo.obtain());

        assertTrue(mA11ySecurityPolicy.canGetAccessibilityNodeInfoLocked(UserHandle.USER_SYSTEM,
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
        when(mMockContext.checkCallingPermission(any()))
                .thenReturn(PackageManager.PERMISSION_DENIED);

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
        when(mMockContext.checkCallingPermission(Manifest.permission.INTERACT_ACROSS_USERS))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

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
        when(mMockContext.checkCallingPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

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
        when(mMockContext.checkCallingPermission(Manifest.permission.INTERACT_ACROSS_USERS))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mMockContext.checkCallingPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL))
                .thenReturn(PackageManager.PERMISSION_DENIED);

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
    public void testEnforceCallerIsRecentsOrHasPermission() {
        mA11ySecurityPolicy.enforceCallerIsRecentsOrHasPermission(PERMISSION, FUNCTION);
        verify(mMockActivityTaskManagerInternal).enforceCallerIsRecentsOrHasPermission(
                PERMISSION, FUNCTION);
    }
}
