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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import androidx.test.InstrumentationRegistry;

import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityManagerService.AccessibilityDisplayListener;
import com.android.server.accessibility.magnification.MagnificationController;
import com.android.server.accessibility.magnification.WindowMagnificationManager;
import com.android.server.accessibility.test.MessageCapturingHandler;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * APCT tests for {@link AccessibilityManagerService}.
 */
public class AccessibilityManagerServiceTest extends AndroidTestCase {
    private static final String TAG = "A11Y_MANAGER_SERVICE_TEST";
    private static final int ACTION_ID = 20;
    private static final String LABEL = "label";
    private static final String INTENT_ACTION = "TESTACTION";
    private static final String DESCRIPTION = "description";
    private static final PendingIntent TEST_PENDING_INTENT = PendingIntent.getBroadcast(
            InstrumentationRegistry.getTargetContext(), 0, new Intent(INTENT_ACTION), PendingIntent.FLAG_MUTABLE_UNAUDITED);
    private static final RemoteAction TEST_ACTION = new RemoteAction(
            Icon.createWithContentUri("content://test"),
            LABEL,
            DESCRIPTION,
            TEST_PENDING_INTENT);
    private static final AccessibilityAction NEW_ACCESSIBILITY_ACTION =
            new AccessibilityAction(ACTION_ID, LABEL);

    static final ComponentName COMPONENT_NAME = new ComponentName(
            "com.android.server.accessibility", "AccessibilityManagerServiceTest");
    static final int SERVICE_ID = 42;

    @Mock private Context mMockContext;
    @Mock private AccessibilityServiceInfo mMockServiceInfo;
    @Mock private ResolveInfo mMockResolveInfo;
    @Mock private AbstractAccessibilityServiceConnection.SystemSupport mMockSystemSupport;
    @Mock private WindowManagerInternal.AccessibilityControllerInternal mMockA11yController;
    @Mock private PackageManager mMockPackageManager;
    @Mock private WindowManagerInternal mMockWindowManagerService;
    @Mock private AccessibilitySecurityPolicy mMockSecurityPolicy;
    @Mock private SystemActionPerformer mMockSystemActionPerformer;
    @Mock private AccessibilityWindowManager mMockA11yWindowManager;
    @Mock private AccessibilityDisplayListener mMockA11yDisplayListener;
    @Mock private ActivityTaskManagerInternal mMockActivityTaskManagerInternal;
    @Mock private IBinder mMockBinder;
    @Mock private IAccessibilityServiceClient mMockServiceClient;
    @Mock private WindowMagnificationManager mMockWindowMagnificationMgr;
    @Mock private MagnificationController mMockMagnificationController;
    @Mock private Resources mMockResources;

    private AccessibilityUserState mUserState;

    private MessageCapturingHandler mHandler = new MessageCapturingHandler(null);
    private AccessibilityServiceConnection mAccessibilityServiceConnection;
    private AccessibilityManagerService mA11yms;

    @Override
    protected void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.addService(
                WindowManagerInternal.class, mMockWindowManagerService);
        LocalServices.addService(
                ActivityTaskManagerInternal.class, mMockActivityTaskManagerInternal);

        when(mMockMagnificationController.getWindowMagnificationMgr()).thenReturn(
                mMockWindowMagnificationMgr);
        when(mMockWindowManagerService.getAccessibilityController()).thenReturn(
                mMockA11yController);
        when(mMockA11yController.isAccessibilityTracingEnabled()).thenReturn(false);
        mA11yms = new AccessibilityManagerService(
            InstrumentationRegistry.getContext(),
            mMockPackageManager,
            mMockSecurityPolicy,
            mMockSystemActionPerformer,
            mMockA11yWindowManager,
            mMockA11yDisplayListener,
            mMockMagnificationController);

        mMockResources = mock(Resources.class);
        when(mMockContext.getResources()).thenReturn(mMockResources);

        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mMockContext, mA11yms);
        mA11yms.mUserStates.put(mA11yms.getCurrentUserIdLocked(), userState);
    }

    private void setupAccessibilityServiceConnection() {
        when(mMockContext.getSystemService(Context.DISPLAY_SERVICE)).thenReturn(
                InstrumentationRegistry.getContext().getSystemService(
                        Context.DISPLAY_SERVICE));
        mUserState = new AccessibilityUserState(UserHandle.USER_SYSTEM, mMockContext, mA11yms);

        when(mMockServiceInfo.getResolveInfo()).thenReturn(mMockResolveInfo);
        mMockResolveInfo.serviceInfo = mock(ServiceInfo.class);
        mMockResolveInfo.serviceInfo.applicationInfo = mock(ApplicationInfo.class);

        when(mMockBinder.queryLocalInterface(any())).thenReturn(mMockServiceClient);
        mAccessibilityServiceConnection = new AccessibilityServiceConnection(
                mUserState,
                mMockContext,
                COMPONENT_NAME,
                mMockServiceInfo,
                SERVICE_ID,
                mHandler,
                new Object(),
                mMockSecurityPolicy,
                mMockSystemSupport,
                mA11yms,
                mMockWindowManagerService,
                mMockSystemActionPerformer,
                mMockA11yWindowManager,
                mMockActivityTaskManagerInternal);
        mAccessibilityServiceConnection.bindLocked();
        mAccessibilityServiceConnection.onServiceConnected(COMPONENT_NAME, mMockBinder);
    }

    @SmallTest
    public void testRegisterSystemActionWithoutPermission() throws Exception {
        doThrow(SecurityException.class).when(mMockSecurityPolicy)
                .enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ACCESSIBILITY);

        try {
            mA11yms.registerSystemAction(TEST_ACTION, ACTION_ID);
            fail();
        } catch (SecurityException expected) {
        }
        verify(mMockSystemActionPerformer, never()).registerSystemAction(ACTION_ID, TEST_ACTION);
    }

    @SmallTest
    public void testRegisterSystemAction() throws Exception {
        mA11yms.registerSystemAction(TEST_ACTION, ACTION_ID);
        verify(mMockSystemActionPerformer).registerSystemAction(ACTION_ID, TEST_ACTION);
    }

    @SmallTest
    public void testUnregisterSystemActionWithoutPermission() throws Exception {
        doThrow(SecurityException.class).when(mMockSecurityPolicy)
                .enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ACCESSIBILITY);

        try {
            mA11yms.unregisterSystemAction(ACTION_ID);
            fail();
        } catch (SecurityException expected) {
        }
        verify(mMockSystemActionPerformer, never()).unregisterSystemAction(ACTION_ID);
    }

    @SmallTest
    public void testUnregisterSystemAction() throws Exception {
        mA11yms.unregisterSystemAction(ACTION_ID);
        verify(mMockSystemActionPerformer).unregisterSystemAction(ACTION_ID);
    }

    @SmallTest
    public void testOnSystemActionsChanged() throws Exception {
        setupAccessibilityServiceConnection();
        mA11yms.notifySystemActionsChangedLocked(mUserState);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        verify(mMockServiceClient).onSystemActionsChanged();
    }

    @SmallTest
    public void testOnMagnificationTransitionFailed_capabilitiesIsAll_fallBackToPreviousMode() {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
        userState.setMagnificationModeLocked(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);

        mA11yms.onMagnificationTransitionEndedLocked(false);

        assertEquals(Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW,
                userState.getMagnificationModeLocked());
    }
}
