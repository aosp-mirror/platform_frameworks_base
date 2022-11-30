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

import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_NONE;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;

import static com.android.internal.accessibility.AccessibilityShortcutController.ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Bundle;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.TestableContext;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.DisplayInfo;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityWindowAttributes;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.compatibility.common.util.TestUtils;
import com.android.internal.compat.IPlatformCompat;
import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityManagerService.AccessibilityDisplayListener;
import com.android.server.accessibility.magnification.FullScreenMagnificationController;
import com.android.server.accessibility.magnification.MagnificationController;
import com.android.server.accessibility.magnification.MagnificationProcessor;
import com.android.server.accessibility.magnification.WindowMagnificationManager;
import com.android.server.accessibility.test.MessageCapturingHandler;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

/**
 * APCT tests for {@link AccessibilityManagerService}.
 */
public class AccessibilityManagerServiceTest {
    @Rule
    public final A11yTestableContext mTestableContext = new A11yTestableContext(
            ApplicationProvider.getApplicationContext());

    private static final int ACTION_ID = 20;
    private static final String LABEL = "label";
    private static final String INTENT_ACTION = "TESTACTION";
    private static final String DESCRIPTION = "description";
    private static final PendingIntent TEST_PENDING_INTENT = PendingIntent.getBroadcast(
            ApplicationProvider.getApplicationContext(), 0, new Intent(INTENT_ACTION),
            PendingIntent.FLAG_MUTABLE_UNAUDITED);
    private static final RemoteAction TEST_ACTION = new RemoteAction(
            Icon.createWithContentUri("content://test"),
            LABEL,
            DESCRIPTION,
            TEST_PENDING_INTENT);

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY + 1;

    static final ComponentName COMPONENT_NAME = new ComponentName(
            "com.android.server.accessibility", "AccessibilityManagerServiceTest");
    static final int SERVICE_ID = 42;

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
    @Mock private UserManagerInternal mMockUserManagerInternal;
    @Mock private IBinder mMockBinder;
    @Mock private IAccessibilityServiceClient mMockServiceClient;
    @Mock private WindowMagnificationManager mMockWindowMagnificationMgr;
    @Mock private MagnificationController mMockMagnificationController;
    @Mock private FullScreenMagnificationController mMockFullScreenMagnificationController;
    @Mock private ProxyManager mProxyManager;
    @Captor private ArgumentCaptor<Intent> mIntentArgumentCaptor;
    private MessageCapturingHandler mHandler = new MessageCapturingHandler(null);
    private AccessibilityServiceConnection mAccessibilityServiceConnection;
    private AccessibilityInputFilter mInputFilter;
    private AccessibilityManagerService mA11yms;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.addService(
                WindowManagerInternal.class, mMockWindowManagerService);
        LocalServices.addService(
                ActivityTaskManagerInternal.class, mMockActivityTaskManagerInternal);
        LocalServices.addService(
                UserManagerInternal.class, mMockUserManagerInternal);
        mInputFilter = Mockito.mock(FakeInputFilter.class);

        when(mMockMagnificationController.getWindowMagnificationMgr()).thenReturn(
                mMockWindowMagnificationMgr);
        when(mMockMagnificationController.getFullScreenMagnificationController()).thenReturn(
                mMockFullScreenMagnificationController);
        when(mMockMagnificationController.supportWindowMagnification()).thenReturn(true);
        when(mMockWindowManagerService.getAccessibilityController()).thenReturn(
                mMockA11yController);
        when(mMockA11yController.isAccessibilityTracingEnabled()).thenReturn(false);
        when(mMockUserManagerInternal.isUserUnlockingOrUnlocked(anyInt())).thenReturn(true);

        final ArrayList<Display> displays = new ArrayList<>();
        final Display defaultDisplay = new Display(DisplayManagerGlobal.getInstance(),
                Display.DEFAULT_DISPLAY, new DisplayInfo(),
                DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        final Display testDisplay = new Display(DisplayManagerGlobal.getInstance(), TEST_DISPLAY,
                new DisplayInfo(), DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        displays.add(defaultDisplay);
        displays.add(testDisplay);
        when(mMockA11yDisplayListener.getValidDisplayList()).thenReturn(displays);

        mA11yms = new AccessibilityManagerService(
                mTestableContext,
                mMockPackageManager,
                mMockSecurityPolicy,
                mMockSystemActionPerformer,
                mMockA11yWindowManager,
                mMockA11yDisplayListener,
                mMockMagnificationController,
                mInputFilter,
                mProxyManager);

        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(mA11yms.getCurrentUserIdLocked(), userState);
    }

    private void setupAccessibilityServiceConnection(int serviceInfoFlag) {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        when(mMockServiceInfo.getResolveInfo()).thenReturn(mMockResolveInfo);
        mMockResolveInfo.serviceInfo = mock(ServiceInfo.class);
        mMockResolveInfo.serviceInfo.applicationInfo = mock(ApplicationInfo.class);

        when(mMockBinder.queryLocalInterface(any())).thenReturn(mMockServiceClient);
        when(mMockSystemSupport.getKeyEventDispatcher()).thenReturn(mock(KeyEventDispatcher.class));
        when(mMockSystemSupport.getMagnificationProcessor()).thenReturn(
                mock(MagnificationProcessor.class));
        mTestableContext.addMockService(COMPONENT_NAME, mMockBinder);

        mMockServiceInfo.flags = serviceInfoFlag;
        mAccessibilityServiceConnection = new AccessibilityServiceConnection(
                userState,
                mTestableContext,
                COMPONENT_NAME,
                mMockServiceInfo,
                SERVICE_ID,
                mHandler,
                new Object(),
                mMockSecurityPolicy,
                mMockSystemSupport,
                mA11yms.getTraceManager(),
                mMockWindowManagerService,
                mMockSystemActionPerformer,
                mMockA11yWindowManager,
                mMockActivityTaskManagerInternal);
        mAccessibilityServiceConnection.bindLocked();
    }

    @SmallTest
    @Test
    public void testRegisterSystemActionWithoutPermission() throws Exception {
        doThrow(SecurityException.class).when(mMockSecurityPolicy)
                .enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ACCESSIBILITY);

        assertThrows(SecurityException.class,
                () -> mA11yms.registerSystemAction(TEST_ACTION, ACTION_ID));
        verify(mMockSystemActionPerformer, never()).registerSystemAction(ACTION_ID, TEST_ACTION);
    }

    @SmallTest
    @Test
    public void testRegisterSystemAction() throws Exception {
        mA11yms.registerSystemAction(TEST_ACTION, ACTION_ID);
        verify(mMockSystemActionPerformer).registerSystemAction(ACTION_ID, TEST_ACTION);
    }

    @Test
    public void testUnregisterSystemActionWithoutPermission() throws Exception {
        doThrow(SecurityException.class).when(mMockSecurityPolicy)
                .enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ACCESSIBILITY);

        assertThrows(SecurityException.class,
                () -> mA11yms.unregisterSystemAction(ACTION_ID));
        verify(mMockSystemActionPerformer, never()).unregisterSystemAction(ACTION_ID);
    }

    @SmallTest
    @Test
    public void testUnregisterSystemAction() throws Exception {
        mA11yms.unregisterSystemAction(ACTION_ID);
        verify(mMockSystemActionPerformer).unregisterSystemAction(ACTION_ID);
    }

    @SmallTest
    @Test
    public void testOnSystemActionsChanged() throws Exception {
        setupAccessibilityServiceConnection(0);
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());

        mA11yms.notifySystemActionsChangedLocked(userState);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        verify(mMockServiceClient).onSystemActionsChanged();
    }

    @SmallTest
    @Test
    public void testRegisterProxy() throws Exception {
        mA11yms.registerProxyForDisplay(mMockServiceClient, TEST_DISPLAY);
        verify(mProxyManager).registerProxy(eq(mMockServiceClient), eq(TEST_DISPLAY),
                eq(mTestableContext), anyInt(), any(), eq(mMockSecurityPolicy),
                eq(mA11yms), eq(mA11yms.getTraceManager()),
                eq(mMockWindowManagerService), eq(mMockA11yWindowManager));
    }

    @SmallTest
    @Test
    public void testRegisterProxyWithoutPermission() throws Exception {
        doThrow(SecurityException.class).when(mMockSecurityPolicy)
                .enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ACCESSIBILITY);

        assertThrows(SecurityException.class,
                () -> mA11yms.registerProxyForDisplay(mMockServiceClient, TEST_DISPLAY));
        verify(mProxyManager, never()).registerProxy(any(), anyInt(), any(), anyInt(), any(), any(),
                any(), any(), any(), any());
    }

    @SmallTest
    @Test
    public void testRegisterProxyForDefaultDisplay() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mA11yms.registerProxyForDisplay(mMockServiceClient, Display.DEFAULT_DISPLAY));
        verify(mProxyManager, never()).registerProxy(any(), anyInt(), any(), anyInt(), any(), any(),
                any(), any(), any(), any());
    }

    @SmallTest
    @Test
    public void testRegisterProxyForInvalidDisplay() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mA11yms.registerProxyForDisplay(mMockServiceClient, Display.INVALID_DISPLAY));
        verify(mProxyManager, never()).registerProxy(any(), anyInt(), any(), anyInt(), any(), any(),
                any(), any(), any(), any());
    }

    @SmallTest
    @Test
    public void testUnRegisterProxyWithPermission() throws Exception {
        mA11yms.registerProxyForDisplay(mMockServiceClient, TEST_DISPLAY);
        mA11yms.unregisterProxyForDisplay(TEST_DISPLAY);

        verify(mProxyManager).unregisterProxy(TEST_DISPLAY);
    }

    @SmallTest
    @Test
    public void testUnRegisterProxyWithoutPermission() throws Exception {
        doThrow(SecurityException.class).when(mMockSecurityPolicy)
                .enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ACCESSIBILITY);

        assertThrows(SecurityException.class,
                () -> mA11yms.unregisterProxyForDisplay(TEST_DISPLAY));
        verify(mProxyManager, never()).unregisterProxy(TEST_DISPLAY);
    }

    @SmallTest
    @Test
    public void testOnMagnificationTransitionFailed_capabilitiesIsAll_fallBackToPreviousMode() {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
        userState.setMagnificationModeLocked(Display.DEFAULT_DISPLAY,
                ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
        );

        mA11yms.onMagnificationTransitionEndedLocked(Display.DEFAULT_DISPLAY, false);

        assertThat(userState.getMagnificationModeLocked(Display.DEFAULT_DISPLAY)).isEqualTo(
                ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

    }

    @SmallTest
    @Test
    public void testOnMagnificationTransitionSuccess_capabilitiesIsAll_inputFilterRefreshMode() {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(
                ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
        userState.setMagnificationModeLocked(Display.DEFAULT_DISPLAY,
                ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
        );

        mA11yms.onMagnificationTransitionEndedLocked(Display.DEFAULT_DISPLAY, true);

        ArgumentCaptor<Display> displayCaptor = ArgumentCaptor.forClass(Display.class);
        verify(mInputFilter, timeout(100)).refreshMagnificationMode(displayCaptor.capture());
        assertThat(displayCaptor.getValue().getDisplayId()).isEqualTo(Display.DEFAULT_DISPLAY);
    }

    @SmallTest
    @Test
    public void testChangeMagnificationModeOnDefaultDisplay_capabilitiesIsAll_persistChangedMode()
            throws Exception {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(
                ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
        userState.setMagnificationModeLocked(Display.DEFAULT_DISPLAY,
                ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
        );

        mA11yms.changeMagnificationMode(Display.DEFAULT_DISPLAY,
                ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        TestUtils.waitUntil("magnification mode " + ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
                        + "  is not persisted in setting", 1,
                () -> {
                    final int userMode = Settings.Secure.getIntForUser(
                            mTestableContext.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE,
                            ACCESSIBILITY_MAGNIFICATION_MODE_NONE,
                            mA11yms.getCurrentUserIdLocked());
                    return userMode == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
                });
    }

    @SmallTest
    @Test
    public void testChangeMagnificationModeOnTestDisplay_capabilitiesIsAll_transitMode() {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(
                ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
        userState.setMagnificationModeLocked(TEST_DISPLAY,
                ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN
        );

        mA11yms.changeMagnificationMode(TEST_DISPLAY,
                ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);

        verify(mMockMagnificationController).transitionMagnificationModeLocked(eq(TEST_DISPLAY),
                eq(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW), ArgumentMatchers.isNotNull());
    }

    @Test
    public void testFollowTypingEnabled_defaultEnabledAndThenDisable_propagateToController() {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        Settings.Secure.putIntForUser(
                mTestableContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_FOLLOW_TYPING_ENABLED,
                0, mA11yms.getCurrentUserIdLocked());

        mA11yms.readMagnificationFollowTypingLocked(userState);

        verify(mMockMagnificationController).setMagnificationFollowTypingEnabled(false);
    }

    @SmallTest
    @Test
    public void testOnClientChange_magnificationEnabledAndCapabilityAll_requestConnection() {
        when(mProxyManager.canRetrieveInteractiveWindowsLocked()).thenReturn(false);

        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.mAccessibilityShortcutKeyTargets.add(MAGNIFICATION_CONTROLLER_NAME);
        userState.setMagnificationCapabilitiesLocked(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL);

        // Invokes client change to trigger onUserStateChanged.
        mA11yms.onClientChangeLocked(/* serviceInfoChanged= */false);

        verify(mMockWindowMagnificationMgr).requestConnection(true);
    }

    @SmallTest
    @Test
    public void testOnClientChange_boundServiceCanControlMagnification_requestConnection() {
        when(mProxyManager.canRetrieveInteractiveWindowsLocked()).thenReturn(false);

        setupAccessibilityServiceConnection(0);
        when(mMockSecurityPolicy.canControlMagnification(any())).thenReturn(true);

        // Invokes client change to trigger onUserStateChanged.
        mA11yms.onClientChangeLocked(/* serviceInfoChanged= */false);

        verify(mMockWindowMagnificationMgr).requestConnection(true);
    }

    @Test
    public void testUnbindIme_whenServiceUnbinds() {
        setupAccessibilityServiceConnection(AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR);
        mAccessibilityServiceConnection.unbindLocked();
        verify(mMockSystemSupport, atLeastOnce()).unbindImeLocked(mAccessibilityServiceConnection);
    }

    @Test
    public void testUnbindIme_whenServiceCrashed() {
        setupAccessibilityServiceConnection(AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR);
        mAccessibilityServiceConnection.binderDied();
        verify(mMockSystemSupport).unbindImeLocked(mAccessibilityServiceConnection);
    }

    @Test
    public void testUnbindIme_whenServiceStopsRequestingIme() {
        setupAccessibilityServiceConnection(AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR);
        doCallRealMethod().when(mMockServiceInfo).updateDynamicallyConfigurableProperties(
                any(IPlatformCompat.class), any(AccessibilityServiceInfo.class));
        mAccessibilityServiceConnection.setServiceInfo(new AccessibilityServiceInfo());
        verify(mMockSystemSupport).unbindImeLocked(mAccessibilityServiceConnection);
    }

    @Test
    public void testSetAccessibilityWindowAttributes_passThrough() {
        final int displayId = Display.DEFAULT_DISPLAY;
        final int userid = 10;
        final int windowId = 100;
        final AccessibilityWindowAttributes attributes = new AccessibilityWindowAttributes(
                new WindowManager.LayoutParams(), LocaleList.getEmptyLocaleList());

        mA11yms.setAccessibilityWindowAttributes(displayId, windowId, userid, attributes);

        verify(mMockA11yWindowManager).setAccessibilityWindowAttributes(displayId, windowId, userid,
                attributes);
    }

    @SmallTest
    @Test
    public void testPerformAccessibilityShortcut_hearingAids_startActivityWithExpectedComponent() {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        mockManageAccessibilityGranted(mTestableContext);
        userState.mAccessibilityShortcutKeyTargets.add(
                ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());

        mA11yms.performAccessibilityShortcut(
                ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());
        mHandler.sendAllMessages();

        assertStartActivityWithExpectedComponentName(mTestableContext.getMockContext(),
                ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());
    }

    private void mockManageAccessibilityGranted(TestableContext context) {
        context.getTestablePermissions().setPermission(Manifest.permission.MANAGE_ACCESSIBILITY,
                PackageManager.PERMISSION_GRANTED);
    }

    private void assertStartActivityWithExpectedComponentName(Context mockContext,
            String componentName) {
        verify(mockContext).startActivityAsUser(mIntentArgumentCaptor.capture(),
                any(Bundle.class), any(UserHandle.class));
        assertThat(mIntentArgumentCaptor.getValue().getStringExtra(
                Intent.EXTRA_COMPONENT_NAME)).isEqualTo(componentName);
    }

    public static class FakeInputFilter extends AccessibilityInputFilter {
        FakeInputFilter(Context context,
                AccessibilityManagerService service) {
            super(context, service);
        }
    }

    private static class A11yTestableContext extends TestableContext {

        private final Context mMockContext;

        A11yTestableContext(Context base) {
            super(base);
            mMockContext = Mockito.mock(Context.class);
        }

        @Override
        public void startActivityAsUser(Intent intent, Bundle options, UserHandle user) {
            mMockContext.startActivityAsUser(intent, options, user);
        }

        Context getMockContext() {
            return mMockContext;
        }
    }
}
