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

import static android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_NONE;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
import static android.view.accessibility.Flags.FLAG_SKIP_ACCESSIBILITY_WARNING_DIALOG_FOR_TRUSTED_SERVICES;

import static com.android.internal.accessibility.AccessibilityShortcutController.ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;
import static com.android.server.accessibility.AccessibilityManagerService.ACTION_LAUNCH_HEARING_DEVICES_DIALOG;
import static com.android.window.flags.Flags.FLAG_ALWAYS_DRAW_MAGNIFICATION_FULLSCREEN_BORDER;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManagerGlobal;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.PermissionEnforcer;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.test.FakePermissionEnforcer;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.view.Display;
import android.view.DisplayAdjustments;
import android.view.DisplayInfo;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityWindowAttributes;
import android.view.accessibility.IAccessibilityManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.compatibility.common.util.TestUtils;
import com.android.internal.R;
import com.android.internal.accessibility.AccessibilityShortcutController;
import com.android.internal.accessibility.common.ShortcutConstants;
import com.android.internal.accessibility.common.ShortcutConstants.FloatingMenuSize;
import com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;
import com.android.internal.accessibility.util.AccessibilityUtils;
import com.android.internal.accessibility.util.ShortcutUtils;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.content.PackageMonitor;
import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityManagerService.AccessibilityDisplayListener;
import com.android.server.accessibility.magnification.FullScreenMagnificationController;
import com.android.server.accessibility.magnification.MagnificationConnectionManager;
import com.android.server.accessibility.magnification.MagnificationController;
import com.android.server.accessibility.magnification.MagnificationProcessor;
import com.android.server.pm.UserManagerInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.util.reflection.FieldReader;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * APCT tests for {@link AccessibilityManagerService}.
 */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class AccessibilityManagerServiceTest {
    @Rule
    public final A11yTestableContext mTestableContext = new A11yTestableContext(
            ApplicationProvider.getApplicationContext());

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final int ACTION_ID = 20;
    private static final String LABEL = "label";
    private static final String INTENT_ACTION = "TESTACTION";
    private static final String DESCRIPTION = "description";
    private static final PendingIntent TEST_PENDING_INTENT = PendingIntent.getBroadcast(
            ApplicationProvider.getApplicationContext(), 0, new Intent(INTENT_ACTION)
                    .setPackage(ApplicationProvider.getApplicationContext().getPackageName()),
            PendingIntent.FLAG_MUTABLE_UNAUDITED);
    private static final RemoteAction TEST_ACTION = new RemoteAction(
            Icon.createWithContentUri("content://test"),
            LABEL,
            DESCRIPTION,
            TEST_PENDING_INTENT);

    private static final int TEST_DISPLAY = Display.DEFAULT_DISPLAY + 1;
    private static final String TARGET_MAGNIFICATION = MAGNIFICATION_CONTROLLER_NAME;
    private static final ComponentName TARGET_ALWAYS_ON_A11Y_SERVICE =
            new ComponentName("FakePackage", "AlwaysOnA11yService");
    private static final String TARGET_ALWAYS_ON_A11Y_SERVICE_TILE_CLASS = "TileService";
    private static final ComponentName TARGET_STANDARD_A11Y_SERVICE =
            new ComponentName("FakePackage", "StandardA11yService");

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
    @Mock private MagnificationConnectionManager mMockMagnificationConnectionManager;
    @Mock private MagnificationController mMockMagnificationController;
    @Mock private FullScreenMagnificationController mMockFullScreenMagnificationController;
    @Mock private ProxyManager mProxyManager;
    @Mock private StatusBarManagerInternal mStatusBarManagerInternal;
    @Captor private ArgumentCaptor<Intent> mIntentArgumentCaptor;
    private IAccessibilityManager mA11yManagerServiceOnDevice;
    private AccessibilityServiceConnection mAccessibilityServiceConnection;
    private AccessibilityInputFilter mInputFilter;
    private AccessibilityManagerService mA11yms;
    private TestableLooper mTestableLooper;
    private Handler mHandler;
    private FakePermissionEnforcer mFakePermissionEnforcer;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        mHandler = new Handler(mTestableLooper.getLooper());
        mFakePermissionEnforcer = new FakePermissionEnforcer();
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.removeServiceForTest(StatusBarManagerInternal.class);
        LocalServices.removeServiceForTest(PermissionEnforcer.class);
        LocalServices.addService(
                WindowManagerInternal.class, mMockWindowManagerService);
        LocalServices.addService(
                ActivityTaskManagerInternal.class, mMockActivityTaskManagerInternal);
        LocalServices.addService(
                UserManagerInternal.class, mMockUserManagerInternal);
        LocalServices.addService(StatusBarManagerInternal.class, mStatusBarManagerInternal);
        mInputFilter = Mockito.mock(FakeInputFilter.class);

        when(mMockMagnificationController.getMagnificationConnectionManager()).thenReturn(
                mMockMagnificationConnectionManager);
        when(mMockMagnificationController.getFullScreenMagnificationController()).thenReturn(
                mMockFullScreenMagnificationController);
        when(mMockMagnificationController.isFullScreenMagnificationControllerInitialized())
                .thenReturn(true);
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
                mHandler,
                mMockPackageManager,
                mMockSecurityPolicy,
                mMockSystemActionPerformer,
                mMockA11yWindowManager,
                mMockA11yDisplayListener,
                mMockMagnificationController,
                mInputFilter,
                mProxyManager,
                mFakePermissionEnforcer);

        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(mA11yms.getCurrentUserIdLocked(), userState);
        AccessibilityManager am = mTestableContext.getSystemService(AccessibilityManager.class);
        mA11yManagerServiceOnDevice = (IAccessibilityManager) new FieldReader(am,
                AccessibilityManager.class.getDeclaredField("mService")).read();
        FieldSetter.setField(am, AccessibilityManager.class.getDeclaredField("mService"), mA11yms);
    }

    @After
    public void cleanUp() throws Exception {
        mTestableLooper.processAllMessages();
        AccessibilityManager am = mTestableContext.getSystemService(AccessibilityManager.class);
        FieldSetter.setField(
                am, AccessibilityManager.class.getDeclaredField("mService"),
                mA11yManagerServiceOnDevice);
    }

    private void setupAccessibilityServiceConnection(int serviceInfoFlag) {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        when(mMockServiceInfo.getResolveInfo()).thenReturn(mMockResolveInfo);
        mMockResolveInfo.serviceInfo = mock(ServiceInfo.class);
        mMockResolveInfo.serviceInfo.packageName = "packageName";
        mMockResolveInfo.serviceInfo.name = "className";
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
        mFakePermissionEnforcer.revoke(Manifest.permission.MANAGE_ACCESSIBILITY);
        assertThrows(SecurityException.class,
                () -> mA11yms.registerSystemAction(TEST_ACTION, ACTION_ID));
        verify(mMockSystemActionPerformer, never()).registerSystemAction(ACTION_ID, TEST_ACTION);
    }

    @SmallTest
    @Test
    public void testRegisterSystemAction() throws Exception {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        mA11yms.registerSystemAction(TEST_ACTION, ACTION_ID);
        verify(mMockSystemActionPerformer).registerSystemAction(ACTION_ID, TEST_ACTION);
    }

    @Test
    public void testUnregisterSystemActionWithoutPermission() throws Exception {
        mFakePermissionEnforcer.revoke(Manifest.permission.MANAGE_ACCESSIBILITY);
        assertThrows(SecurityException.class,
                () -> mA11yms.unregisterSystemAction(ACTION_ID));
        verify(mMockSystemActionPerformer, never()).unregisterSystemAction(ACTION_ID);
    }

    @SmallTest
    @Test
    public void testUnregisterSystemAction() throws Exception {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
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
        mTestableLooper.processAllMessages();

        verify(mMockServiceClient).onSystemActionsChanged();
    }

    @SmallTest
    @Test
    public void testRegisterProxy() throws Exception {
        mFakePermissionEnforcer.grant(Manifest.permission.CREATE_VIRTUAL_DEVICE);
        when(mProxyManager.displayBelongsToCaller(anyInt(), anyInt())).thenReturn(true);
        mA11yms.registerProxyForDisplay(mMockServiceClient, TEST_DISPLAY);
        verify(mProxyManager).registerProxy(eq(mMockServiceClient), eq(TEST_DISPLAY), anyInt(),
                eq(mMockSecurityPolicy),
                eq(mA11yms), eq(mA11yms.getTraceManager()),
                eq(mMockWindowManagerService));
    }

    @SmallTest
    @Test
    public void testRegisterProxyWithoutA11yPermissionOrRole() throws Exception {
        mFakePermissionEnforcer.grant(Manifest.permission.CREATE_VIRTUAL_DEVICE);
        doThrow(SecurityException.class).when(mMockSecurityPolicy)
                .checkForAccessibilityPermissionOrRole();

        assertThrows(SecurityException.class,
                () -> mA11yms.registerProxyForDisplay(mMockServiceClient, TEST_DISPLAY));
        verify(mProxyManager, never()).registerProxy(any(), anyInt(), anyInt(), any(),
                any(), any(), any());
    }

    @SmallTest
    @Test
    public void testRegisterProxyWithoutDevicePermission() throws Exception {
        mFakePermissionEnforcer.revoke(Manifest.permission.CREATE_VIRTUAL_DEVICE);
        assertThrows(SecurityException.class,
                () -> mA11yms.registerProxyForDisplay(mMockServiceClient, TEST_DISPLAY));
        verify(mProxyManager, never()).registerProxy(any(), anyInt(), anyInt(), any(),
                any(), any(), any());
    }

    @SmallTest
    @Test
    public void testRegisterProxyForDefaultDisplay() throws Exception {
        assertThrows(SecurityException.class,
                () -> mA11yms.registerProxyForDisplay(mMockServiceClient, Display.DEFAULT_DISPLAY));
        verify(mProxyManager, never()).registerProxy(any(), anyInt(), anyInt(), any(),
                any(), any(), any());
    }

    @SmallTest
    @Test
    public void testRegisterProxyForInvalidDisplay() throws Exception {
        mFakePermissionEnforcer.grant(Manifest.permission.CREATE_VIRTUAL_DEVICE);
        assertThrows(IllegalArgumentException.class,
                () -> mA11yms.registerProxyForDisplay(mMockServiceClient, Display.INVALID_DISPLAY));
        verify(mProxyManager, never()).registerProxy(any(), anyInt(), anyInt(), any(),
                any(), any(), any());
    }

    @SmallTest
    @Test
    public void testUnRegisterProxyWithPermission() throws Exception {
        mFakePermissionEnforcer.grant(Manifest.permission.CREATE_VIRTUAL_DEVICE);
        when(mProxyManager.displayBelongsToCaller(anyInt(), anyInt())).thenReturn(true);
        mA11yms.registerProxyForDisplay(mMockServiceClient, TEST_DISPLAY);
        mA11yms.unregisterProxyForDisplay(TEST_DISPLAY);

        verify(mProxyManager).unregisterProxy(TEST_DISPLAY);
    }

    @SmallTest
    @Test
    public void testUnRegisterProxyWithoutA11yPermissionOrRole() {
        doThrow(SecurityException.class).when(mMockSecurityPolicy)
                .checkForAccessibilityPermissionOrRole();

        assertThrows(SecurityException.class,
                () -> mA11yms.unregisterProxyForDisplay(TEST_DISPLAY));
        verify(mProxyManager, never()).unregisterProxy(TEST_DISPLAY);
    }

    @SmallTest
    @Test
    public void testUnRegisterProxyWithoutDevicePermission() {
        mFakePermissionEnforcer.revoke(Manifest.permission.CREATE_VIRTUAL_DEVICE);
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
        mTestableLooper.processAllMessages();

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
        // This test only makes sense for devices that support Window magnification
        assumeTrue(mTestableContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WINDOW_MAGNIFICATION));

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

    @Test
    public void testSettingsAlwaysOn_setEnabled_featureFlagDisabled_doNothing() {
        when(mMockMagnificationController.isAlwaysOnMagnificationFeatureFlagEnabled())
                .thenReturn(false);

        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        Settings.Secure.putIntForUser(
                mTestableContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_ALWAYS_ON_ENABLED,
                1, mA11yms.getCurrentUserIdLocked());

        mA11yms.readAlwaysOnMagnificationLocked(userState);

        verify(mMockMagnificationController, never()).setAlwaysOnMagnificationEnabled(anyBoolean());
    }

    @Test
    public void testSettingsAlwaysOn_setEnabled_featureFlagEnabled_propagateToController() {
        when(mMockMagnificationController.isAlwaysOnMagnificationFeatureFlagEnabled())
                .thenReturn(true);

        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        Settings.Secure.putIntForUser(
                mTestableContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_ALWAYS_ON_ENABLED,
                1, mA11yms.getCurrentUserIdLocked());

        mA11yms.readAlwaysOnMagnificationLocked(userState);

        verify(mMockMagnificationController).setAlwaysOnMagnificationEnabled(eq(true));
    }

    @Test
    @EnableFlags(FLAG_ALWAYS_DRAW_MAGNIFICATION_FULLSCREEN_BORDER)
    public void testSetConnectionNull_borderFlagEnabled_unregisterFullScreenMagnification()
            throws RemoteException {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        mA11yms.setMagnificationConnection(null);

        verify(mMockFullScreenMagnificationController, atLeastOnce()).reset(
                /* displayId= */ anyInt(), /* animate= */ anyBoolean());
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

        verify(mMockMagnificationConnectionManager).requestConnection(true);
    }

    @SmallTest
    @Test
    public void testOnClientChange_magnificationTripleTapEnabled_requestConnection() {
        when(mProxyManager.canRetrieveInteractiveWindowsLocked()).thenReturn(false);

        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
        userState.setMagnificationSingleFingerTripleTapEnabledLocked(true);

        // Invokes client change to trigger onUserStateChanged.
        mA11yms.onClientChangeLocked(/* serviceInfoChanged= */false);

        verify(mMockMagnificationConnectionManager).requestConnection(true);
    }

    @SmallTest
    @Test
    public void testOnClientChange_magnificationTripleTapDisabled_requestDisconnection() {
        when(mProxyManager.canRetrieveInteractiveWindowsLocked()).thenReturn(false);

        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
        //userState.setMagnificationSingleFingerTripleTapEnabledLocked(false);
        userState.setMagnificationSingleFingerTripleTapEnabledLocked(false);

        // Invokes client change to trigger onUserStateChanged.
        mA11yms.onClientChangeLocked(/* serviceInfoChanged= */false);

        verify(mMockMagnificationConnectionManager).requestConnection(false);
    }

    @SmallTest
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_MAGNIFICATION_MULTIPLE_FINGER_MULTIPLE_TAP_GESTURE)
    public void testOnClientChange_magnificationTwoFingerTripleTapEnabled_requestConnection() {
        when(mProxyManager.canRetrieveInteractiveWindowsLocked()).thenReturn(false);

        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
        userState.setMagnificationTwoFingerTripleTapEnabledLocked(true);

        // Invokes client change to trigger onUserStateChanged.
        mA11yms.onClientChangeLocked(/* serviceInfoChanged= */false);

        verify(mMockMagnificationConnectionManager).requestConnection(true);
    }

    @SmallTest
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_MAGNIFICATION_MULTIPLE_FINGER_MULTIPLE_TAP_GESTURE)
    public void testOnClientChange_magnificationTwoFingerTripleTapDisabled_requestDisconnection() {
        when(mProxyManager.canRetrieveInteractiveWindowsLocked()).thenReturn(false);

        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
        //userState.setMagnificationSingleFingerTripleTapEnabledLocked(false);
        userState.setMagnificationTwoFingerTripleTapEnabledLocked(false);

        // Invokes client change to trigger onUserStateChanged.
        mA11yms.onClientChangeLocked(/* serviceInfoChanged= */false);

        verify(mMockMagnificationConnectionManager).requestConnection(false);
    }

    @SmallTest
    @Test
    public void testOnClientChange_boundServiceCanControlMagnification_requestConnection() {
        when(mProxyManager.canRetrieveInteractiveWindowsLocked()).thenReturn(false);

        setupAccessibilityServiceConnection(0);
        when(mMockSecurityPolicy.canControlMagnification(any())).thenReturn(true);

        // Invokes client change to trigger onUserStateChanged.
        mA11yms.onClientChangeLocked(/* serviceInfoChanged= */false);

        verify(mMockMagnificationConnectionManager).requestConnection(true);
    }

    @SmallTest
    @Test
    public void testOnClientChange_magnificationTripleTapDisabled_removeMagnificationButton() {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        userState.setMagnificationSingleFingerTripleTapEnabledLocked(false);

        // Invokes client change to trigger onUserStateChanged.
        mA11yms.onClientChangeLocked(/* serviceInfoChanged= */false);

        verify(mMockMagnificationConnectionManager, atLeastOnce())
                .removeMagnificationButton(anyInt());
    }

    @SmallTest
    @Test
    public void testOnClientChange_magnificationTripleTapEnabled_keepMagnificationButton() {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
        userState.setMagnificationSingleFingerTripleTapEnabledLocked(true);

        // Invokes client change to trigger onUserStateChanged.
        mA11yms.onClientChangeLocked(/* serviceInfoChanged= */false);

        verify(mMockMagnificationConnectionManager, never()).removeMagnificationButton(anyInt());
    }

    @SmallTest
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_MAGNIFICATION_MULTIPLE_FINGER_MULTIPLE_TAP_GESTURE)
    public void onClientChange_magnificationTwoFingerTripleTapDisabled_removeMagnificationButton() {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        userState.setMagnificationTwoFingerTripleTapEnabledLocked(false);

        // Invokes client change to trigger onUserStateChanged.
        mA11yms.onClientChangeLocked(/* serviceInfoChanged= */false);

        verify(mMockMagnificationConnectionManager, atLeastOnce())
                .removeMagnificationButton(anyInt());
    }

    @SmallTest
    @Test
    @EnableFlags(Flags.FLAG_ENABLE_MAGNIFICATION_MULTIPLE_FINGER_MULTIPLE_TAP_GESTURE)
    public void onClientChange_magnificationTwoFingerTripleTapEnabled_keepMagnificationButton() {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        userState.setMagnificationCapabilitiesLocked(ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
        userState.setMagnificationTwoFingerTripleTapEnabledLocked(true);

        // Invokes client change to trigger onUserStateChanged.
        mA11yms.onClientChangeLocked(/* serviceInfoChanged= */false);

        verify(mMockMagnificationConnectionManager, never()).removeMagnificationButton(anyInt());
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
    @DisableFlags(com.android.systemui.Flags.FLAG_HEARING_AIDS_QS_TILE_DIALOG)
    public void testPerformAccessibilityShortcut_hearingAids_startActivityWithExpectedComponent() {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        userState.mAccessibilityShortcutKeyTargets.add(
                ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());

        mA11yms.performAccessibilityShortcut(
                ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());
        mTestableLooper.processAllMessages();

        assertStartActivityWithExpectedComponentName(mTestableContext.getMockContext(),
                ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());
    }

    @SmallTest
    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_HEARING_AIDS_QS_TILE_DIALOG)
    public void testPerformAccessibilityShortcut_hearingAids_sendExpectedBroadcast() {
        final AccessibilityUserState userState = mA11yms.mUserStates.get(
                mA11yms.getCurrentUserIdLocked());
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        userState.mAccessibilityShortcutKeyTargets.add(
                ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());

        mA11yms.performAccessibilityShortcut(
                ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());
        mTestableLooper.processAllMessages();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mTestableContext.getMockContext()).sendBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.SYSTEM));
        assertThat(intentCaptor.getValue().getAction()).isEqualTo(
                ACTION_LAUNCH_HEARING_DEVICES_DIALOG);
    }

    @Test
    public void testPackagesForceStopped_disablesRelevantService() {
        final AccessibilityServiceInfo info_a = new AccessibilityServiceInfo();
        info_a.setComponentName(COMPONENT_NAME);
        final AccessibilityServiceInfo info_b = new AccessibilityServiceInfo();
        info_b.setComponentName(new ComponentName("package", "class"));

        AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mInstalledServices.clear();
        userState.mInstalledServices.add(info_a);
        userState.mInstalledServices.add(info_b);
        userState.mEnabledServices.clear();
        userState.mEnabledServices.add(info_a.getComponentName());
        userState.mEnabledServices.add(info_b.getComponentName());

        synchronized (mA11yms.getLock()) {
            mA11yms.onPackagesForceStoppedLocked(
                    new String[]{info_a.getComponentName().getPackageName()}, userState);
        }

        //Assert user state change
        userState = mA11yms.getCurrentUserState();
        assertThat(userState.mEnabledServices).containsExactly(info_b.getComponentName());
        //Assert setting change
        final Set<ComponentName> componentsFromSetting = new ArraySet<>();
        mA11yms.readComponentNamesFromSettingLocked(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userState.mUserId, componentsFromSetting);
        assertThat(componentsFromSetting).containsExactly(info_b.getComponentName());
    }

    @Test
    public void testPackagesForceStopped_fromContinuousService_removesButtonTarget() {
        final AccessibilityServiceInfo info_a = new AccessibilityServiceInfo();
        info_a.setComponentName(COMPONENT_NAME);
        info_a.flags = FLAG_REQUEST_ACCESSIBILITY_BUTTON;
        final AccessibilityServiceInfo info_b = new AccessibilityServiceInfo();
        info_b.setComponentName(new ComponentName("package", "class"));

        AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mInstalledServices.clear();
        userState.mInstalledServices.add(info_a);
        userState.mInstalledServices.add(info_b);
        userState.mAccessibilityButtonTargets.clear();
        userState.mAccessibilityButtonTargets.add(info_a.getComponentName().flattenToString());
        userState.mAccessibilityButtonTargets.add(info_b.getComponentName().flattenToString());

        // despite force stopping both packages, only the first service has the relevant flag,
        // so only the first should be removed.
        synchronized (mA11yms.getLock()) {
            mA11yms.onPackagesForceStoppedLocked(
                    new String[]{
                            info_a.getComponentName().getPackageName(),
                            info_b.getComponentName().getPackageName()},
                    userState);
        }

        //Assert user state change
        userState = mA11yms.getCurrentUserState();
        assertThat(userState.mAccessibilityButtonTargets).containsExactly(
                info_b.getComponentName().flattenToString());
        //Assert setting change
        final Set<String> targetsFromSetting = new ArraySet<>();
        mA11yms.readColonDelimitedSettingToSet(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                userState.mUserId, str -> str, targetsFromSetting);
        assertThat(targetsFromSetting).containsExactly(info_b.getComponentName().flattenToString());
    }

    @Test
    public void testPackageMonitorScanPackages_scansWithoutHoldingLock() {
        setupAccessibilityServiceConnection(0);
        final AtomicReference<Set<Boolean>> lockState = collectLockStateWhilePackageScanning();
        when(mMockPackageManager.queryIntentServicesAsUser(any(), anyInt(), anyInt()))
                .thenReturn(List.of(mMockResolveInfo));
        when(mMockSecurityPolicy.canRegisterService(any())).thenReturn(true);

        final Intent packageIntent = new Intent(Intent.ACTION_PACKAGE_ADDED);
        packageIntent.setData(Uri.parse("test://package"));
        packageIntent.putExtra(Intent.EXTRA_USER_HANDLE, mA11yms.getCurrentUserIdLocked());
        packageIntent.putExtra(Intent.EXTRA_REPLACING, true);
        mA11yms.getPackageMonitor().doHandlePackageEvent(packageIntent);

        assertThat(lockState.get()).containsExactly(false);
    }

    @Test
    public void testSwitchUserScanPackages_scansWithoutHoldingLock() {
        setupAccessibilityServiceConnection(0);
        final AtomicReference<Set<Boolean>> lockState = collectLockStateWhilePackageScanning();
        when(mMockPackageManager.queryIntentServicesAsUser(any(), anyInt(), anyInt()))
                .thenReturn(List.of(mMockResolveInfo));
        when(mMockSecurityPolicy.canRegisterService(any())).thenReturn(true);

        mA11yms.switchUser(mA11yms.getCurrentUserIdLocked() + 1);

        assertThat(lockState.get()).containsExactly(false);
    }

    @Test
    public void testIsAccessibilityServiceWarningRequired_requiredByDefault() {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        final AccessibilityServiceInfo info = mockAccessibilityServiceInfo(COMPONENT_NAME);

        assertThat(mA11yms.isAccessibilityServiceWarningRequired(info)).isTrue();
    }

    @Test
    public void testIsAccessibilityServiceWarningRequired_notRequiredIfAlreadyEnabled() {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        final AccessibilityServiceInfo info_a = mockAccessibilityServiceInfo(COMPONENT_NAME);
        final AccessibilityServiceInfo info_b = mockAccessibilityServiceInfo(
                new ComponentName("package_b", "class_b"));
        final AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mEnabledServices.clear();
        userState.mEnabledServices.add(info_b.getComponentName());

        assertThat(mA11yms.isAccessibilityServiceWarningRequired(info_a)).isTrue();
        assertThat(mA11yms.isAccessibilityServiceWarningRequired(info_b)).isFalse();
    }

    @Test
    public void testIsAccessibilityServiceWarningRequired_notRequiredIfExistingShortcut() {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        final AccessibilityServiceInfo info_a = mockAccessibilityServiceInfo(
                new ComponentName("package_a", "class_a"));
        final AccessibilityServiceInfo info_b = mockAccessibilityServiceInfo(
                new ComponentName("package_b", "class_b"));
        final AccessibilityServiceInfo info_c = mockAccessibilityServiceInfo(
                new ComponentName("package_c", "class_c"));
        final AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mAccessibilityButtonTargets.clear();
        userState.mAccessibilityButtonTargets.add(info_b.getComponentName().flattenToString());
        userState.mAccessibilityShortcutKeyTargets.clear();
        userState.mAccessibilityShortcutKeyTargets.add(info_c.getComponentName().flattenToString());

        assertThat(mA11yms.isAccessibilityServiceWarningRequired(info_a)).isTrue();
        assertThat(mA11yms.isAccessibilityServiceWarningRequired(info_b)).isFalse();
        assertThat(mA11yms.isAccessibilityServiceWarningRequired(info_c)).isFalse();
    }

    @Test
    @EnableFlags(FLAG_SKIP_ACCESSIBILITY_WARNING_DIALOG_FOR_TRUSTED_SERVICES)
    public void testIsAccessibilityServiceWarningRequired_notRequiredIfAllowlisted() {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        final AccessibilityServiceInfo info_a = mockAccessibilityServiceInfo(
                new ComponentName("package_a", "class_a"),
                /* isSystemApp= */ true, /* isAlwaysOnService= */ false);
        final AccessibilityServiceInfo info_b = mockAccessibilityServiceInfo(
                new ComponentName("package_b", "class_b"),
                /* isSystemApp= */ false, /* isAlwaysOnService= */ false);
        final AccessibilityServiceInfo info_c = mockAccessibilityServiceInfo(
                new ComponentName("package_c", "class_c"),
                /* isSystemApp= */ true, /* isAlwaysOnService= */ false);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.array.config_trustedAccessibilityServices,
                new String[]{
                        info_b.getComponentName().flattenToString(),
                        info_c.getComponentName().flattenToString()});

        // info_a is not in the allowlist => require the warning
        assertThat(mA11yms.isAccessibilityServiceWarningRequired(info_a)).isTrue();
        // info_b is not preinstalled => require the warning
        assertThat(mA11yms.isAccessibilityServiceWarningRequired(info_b)).isTrue();
        // info_c is both in the allowlist and preinstalled => do not require the warning
        assertThat(mA11yms.isAccessibilityServiceWarningRequired(info_c)).isFalse();
    }

    @Test
    public void enableShortcutsForTargets_permissionNotGranted_throwsException() {
        mTestableContext.getTestablePermissions().setPermission(
                Manifest.permission.MANAGE_ACCESSIBILITY, PackageManager.PERMISSION_DENIED);

        assertThrows(SecurityException.class,
                () -> mA11yms.enableShortcutsForTargets(
                        /* enable= */true,
                        UserShortcutType.SOFTWARE,
                        List.of(TARGET_MAGNIFICATION),
                        mA11yms.getCurrentUserIdLocked()));
    }

    @Test
    public void enableShortcutsForTargets_enableSoftwareShortcut_shortcutTurnedOn()
            throws Exception {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();
        String target = TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                UserShortcutType.SOFTWARE,
                List.of(target),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(ShortcutUtils.isComponentIdExistingInSettings(
                mTestableContext, UserShortcutType.SOFTWARE, target
        )).isTrue();
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_HARDWARE_SHORTCUT_DISABLES_WARNING)
    public void enableHardwareShortcutsForTargets_shortcutDialogSetting_isShown() {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        Settings.Secure.putInt(
                mTestableContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN,
                AccessibilityShortcutController.DialogStatus.NOT_SHOWN
        );

        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();
        String target = TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                UserShortcutType.HARDWARE,
                List.of(target),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(Settings.Secure.getInt(
                mTestableContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_SHORTCUT_DIALOG_SHOWN,
                AccessibilityShortcutController.DialogStatus.NOT_SHOWN))
                .isEqualTo(AccessibilityShortcutController.DialogStatus.SHOWN);
    }

    @Test
    public void enableShortcutsForTargets_disableSoftwareShortcut_shortcutTurnedOff()
            throws Exception {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        String target = TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString();
        enableShortcutsForTargets_enableSoftwareShortcut_shortcutTurnedOn();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ false,
                UserShortcutType.SOFTWARE,
                List.of(target),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(ShortcutUtils.isComponentIdExistingInSettings(
                mTestableContext, UserShortcutType.SOFTWARE, target
        )).isFalse();
    }

    @Test
    public void enableShortcutsForTargets_enableSoftwareShortcutWithMagnification_menuSizeIncreased() {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                UserShortcutType.SOFTWARE,
                List.of(MAGNIFICATION_CONTROLLER_NAME),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                Settings.Secure.getInt(
                        mTestableContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE,
                        FloatingMenuSize.UNKNOWN))
                .isEqualTo(FloatingMenuSize.LARGE);
    }

    @Test
    public void enableShortcutsForTargets_enableSoftwareShortcutWithMagnification_userConfigureSmallMenuSize_menuSizeNotChanged() {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        Settings.Secure.putInt(
                mTestableContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE,
                FloatingMenuSize.SMALL);

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                UserShortcutType.SOFTWARE,
                List.of(MAGNIFICATION_CONTROLLER_NAME),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                Settings.Secure.getInt(
                        mTestableContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_FLOATING_MENU_SIZE,
                        FloatingMenuSize.UNKNOWN))
                .isEqualTo(FloatingMenuSize.SMALL);
    }

    @Test
    public void enableShortcutsForTargets_enableAlwaysOnServiceSoftwareShortcut_turnsOnAlwaysOnService()
            throws Exception {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                UserShortcutType.SOFTWARE,
                List.of(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                AccessibilityUtils.getEnabledServicesFromSettings(
                        mTestableContext,
                        mA11yms.getCurrentUserIdLocked())
        ).contains(TARGET_ALWAYS_ON_A11Y_SERVICE);
    }

    @Test
    public void enableShortcutsForTargets_disableAlwaysOnServiceSoftwareShortcut_turnsOffAlwaysOnService()
            throws Exception {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        enableShortcutsForTargets_enableAlwaysOnServiceSoftwareShortcut_turnsOnAlwaysOnService();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ false,
                UserShortcutType.SOFTWARE,
                List.of(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                AccessibilityUtils.getEnabledServicesFromSettings(
                        mTestableContext,
                        mA11yms.getCurrentUserIdLocked())
        ).doesNotContain(TARGET_ALWAYS_ON_A11Y_SERVICE);
    }

    @Test
    public void enableShortcutsForTargets_enableStandardServiceSoftwareShortcut_wontTurnOnService()
            throws Exception {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                UserShortcutType.SOFTWARE,
                List.of(TARGET_STANDARD_A11Y_SERVICE.flattenToString()),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                AccessibilityUtils.getEnabledServicesFromSettings(
                        mTestableContext,
                        mA11yms.getCurrentUserIdLocked())
        ).doesNotContain(TARGET_STANDARD_A11Y_SERVICE);
    }

    @Test
    public void enableShortcutsForTargets_disableStandardServiceSoftwareShortcutWithServiceOn_wontTurnOffService()
            throws Exception {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        enableShortcutsForTargets_enableStandardServiceSoftwareShortcut_wontTurnOnService();
        AccessibilityUtils.setAccessibilityServiceState(
                mTestableContext, TARGET_STANDARD_A11Y_SERVICE, /* enabled= */ true);

        mA11yms.enableShortcutsForTargets(
                /* enable= */ false,
                UserShortcutType.SOFTWARE,
                List.of(TARGET_STANDARD_A11Y_SERVICE.flattenToString()),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                AccessibilityUtils.getEnabledServicesFromSettings(
                        mTestableContext,
                        mA11yms.getCurrentUserIdLocked())
        ).contains(TARGET_STANDARD_A11Y_SERVICE);
    }

    @Test
    public void enableShortcutsForTargets_enableTripleTapShortcut_settingUpdated() {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                UserShortcutType.TRIPLETAP,
                List.of(TARGET_MAGNIFICATION),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                Settings.Secure.getInt(
                        mTestableContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                        AccessibilityUtils.State.OFF)
        ).isEqualTo(AccessibilityUtils.State.ON);
    }

    @Test
    public void enableShortcutsForTargets_disableTripleTapShortcut_settingUpdated() {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        enableShortcutsForTargets_enableTripleTapShortcut_settingUpdated();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ false,
                UserShortcutType.TRIPLETAP,
                List.of(TARGET_MAGNIFICATION),
                mA11yms.getCurrentUserIdLocked());

        assertThat(
                Settings.Secure.getInt(
                        mTestableContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED,
                        AccessibilityUtils.State.OFF)
        ).isEqualTo(AccessibilityUtils.State.OFF);
    }

    @Test
    public void enableShortcutsForTargets_enableMultiFingerMultiTapsShortcut_settingUpdated() {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                UserShortcutType.TWOFINGER_DOUBLETAP,
                List.of(TARGET_MAGNIFICATION),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                Settings.Secure.getInt(
                        mTestableContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_TWO_FINGER_TRIPLE_TAP_ENABLED,
                        AccessibilityUtils.State.OFF)
        ).isEqualTo(AccessibilityUtils.State.ON);
    }

    @Test
    public void enableShortcutsForTargets_disableMultiFingerMultiTapsShortcut_settingUpdated() {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        enableShortcutsForTargets_enableMultiFingerMultiTapsShortcut_settingUpdated();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ false,
                UserShortcutType.TWOFINGER_DOUBLETAP,
                List.of(TARGET_MAGNIFICATION),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                Settings.Secure.getInt(
                        mTestableContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_MAGNIFICATION_TWO_FINGER_TRIPLE_TAP_ENABLED,
                        AccessibilityUtils.State.OFF)
        ).isEqualTo(AccessibilityUtils.State.OFF);
    }

    @Test
    public void enableShortcutsForTargets_enableVolumeKeysShortcut_shortcutSet() {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                UserShortcutType.HARDWARE,
                List.of(TARGET_STANDARD_A11Y_SERVICE.flattenToString()),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                ShortcutUtils.isComponentIdExistingInSettings(
                        mTestableContext, ShortcutConstants.UserShortcutType.HARDWARE,
                        TARGET_STANDARD_A11Y_SERVICE.flattenToString())
        ).isTrue();
    }

    @Test
    public void enableShortcutsForTargets_disableVolumeKeysShortcut_shortcutNotSet() {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        enableShortcutsForTargets_enableVolumeKeysShortcut_shortcutSet();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ false,
                UserShortcutType.HARDWARE,
                List.of(TARGET_STANDARD_A11Y_SERVICE.flattenToString()),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                        ShortcutUtils.isComponentIdExistingInSettings(
                                mTestableContext,
                                ShortcutConstants.UserShortcutType.HARDWARE,
                                TARGET_STANDARD_A11Y_SERVICE.flattenToString()))
                .isFalse();
    }

    @Test
    public void enableShortcutsForTargets_enableQuickSettings_shortcutSet() {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                UserShortcutType.QUICK_SETTINGS,
                List.of(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                ShortcutUtils.isComponentIdExistingInSettings(
                        mTestableContext, UserShortcutType.QUICK_SETTINGS,
                        TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString())
        ).isTrue();
        verify(mStatusBarManagerInternal)
                .addQsTileToFrontOrEnd(
                        new ComponentName(
                                TARGET_ALWAYS_ON_A11Y_SERVICE.getPackageName(),
                                TARGET_ALWAYS_ON_A11Y_SERVICE_TILE_CLASS),
                        /* end= */ true);
    }

    @Test
    public void enableShortcutsForTargets_disableQuickSettings_shortcutNotSet() {
        // TODO(b/111889696): Remove the user 0 assumption once we support multi-user
        Assume.assumeTrue("The test is setup to run as a user 0",
                isSameCurrentUser(mA11yms, mTestableContext));
        enableShortcutsForTargets_enableQuickSettings_shortcutSet();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ false,
                UserShortcutType.QUICK_SETTINGS,
                List.of(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                ShortcutUtils.isComponentIdExistingInSettings(
                        mTestableContext, UserShortcutType.QUICK_SETTINGS,
                        TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString())
        ).isFalse();
        verify(mStatusBarManagerInternal)
                .removeQsTile(
                        new ComponentName(
                                TARGET_ALWAYS_ON_A11Y_SERVICE.getPackageName(),
                                TARGET_ALWAYS_ON_A11Y_SERVICE_TILE_CLASS));
    }

    @Test
    public void getA11yFeatureToTileMap_permissionNotGranted_throwsException() {
        mTestableContext.getTestablePermissions().setPermission(
                Manifest.permission.MANAGE_ACCESSIBILITY, PackageManager.PERMISSION_DENIED);

        assertThrows(SecurityException.class,
                () -> mA11yms.getA11yFeatureToTileMap(mA11yms.getCurrentUserIdLocked()));
    }

    @Test
    public void getA11yFeatureToTileMap() {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();

        Bundle bundle = mA11yms.getA11yFeatureToTileMap(mA11yms.getCurrentUserIdLocked());

        // Framework tile size + TARGET_ALWAYS_ON_A11Y_SERVICE_TILE_CLASS
        assertThat(bundle.size())
                .isEqualTo(ShortcutConstants.A11Y_FEATURE_TO_FRAMEWORK_TILE.size() + 1);
        assertThat(
                bundle.getParcelable(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString(),
                        ComponentName.class)
        ).isEqualTo(
                new ComponentName(
                        TARGET_ALWAYS_ON_A11Y_SERVICE.getPackageName(),
                        TARGET_ALWAYS_ON_A11Y_SERVICE_TILE_CLASS));
        for (Map.Entry<ComponentName, ComponentName> entry :
                ShortcutConstants.A11Y_FEATURE_TO_FRAMEWORK_TILE.entrySet()) {
            assertThat(bundle.getParcelable(entry.getKey().flattenToString(), ComponentName.class))
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void notifyQuickSettingsTilesChanged_statusBarServiceNotGranted_throwsException() {
        mFakePermissionEnforcer.revoke(Manifest.permission.STATUS_BAR_SERVICE);
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);

        assertThrows(SecurityException.class,
                () -> mA11yms.notifyQuickSettingsTilesChanged(
                        mA11yms.getCurrentUserState().mUserId,
                        List.of(
                                AccessibilityShortcutController.DALTONIZER_TILE_COMPONENT_NAME)));
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void notifyQuickSettingsTilesChanged_manageAccessibilityNotGranted_throwsException() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        mTestableContext.getTestablePermissions().setPermission(
                Manifest.permission.STATUS_BAR_SERVICE, PackageManager.PERMISSION_DENIED);

        assertThrows(SecurityException.class,
                () -> mA11yms.notifyQuickSettingsTilesChanged(
                        mA11yms.getCurrentUserState().mUserId,
                        List.of(
                                AccessibilityShortcutController.DALTONIZER_TILE_COMPONENT_NAME)));
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void notifyQuickSettingsTilesChanged_qsTileChanges_updateA11yTilesInQsPanel() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        List<ComponentName> tiles = List.of(
                AccessibilityShortcutController.DALTONIZER_TILE_COMPONENT_NAME,
                AccessibilityShortcutController.COLOR_INVERSION_TILE_COMPONENT_NAME
        );

        mA11yms.notifyQuickSettingsTilesChanged(
                mA11yms.getCurrentUserState().mUserId,
                tiles
        );

        assertThat(
                mA11yms.getCurrentUserState().getA11yQsTilesInQsPanel()
        ).containsExactlyElementsIn(tiles);
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void notifyQuickSettingsTilesChanged_sameQsTiles_noUpdateToA11yTilesInQsPanel() {
        notifyQuickSettingsTilesChanged_qsTileChanges_updateA11yTilesInQsPanel();
        List<ComponentName> tiles =
                mA11yms.getCurrentUserState().getA11yQsTilesInQsPanel().stream().toList();

        mA11yms.notifyQuickSettingsTilesChanged(
                mA11yms.getCurrentUserState().mUserId,
                tiles
        );

        assertThat(
                mA11yms.getCurrentUserState().getA11yQsTilesInQsPanel()
        ).containsExactlyElementsIn(tiles);
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void notifyQuickSettingsTilesChanged_serviceWarningRequired_qsShortcutRemainDisabled() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();
        ComponentName tile = new ComponentName(
                TARGET_ALWAYS_ON_A11Y_SERVICE.getPackageName(),
                TARGET_ALWAYS_ON_A11Y_SERVICE_TILE_CLASS);

        mA11yms.notifyQuickSettingsTilesChanged(
                mA11yms.getCurrentUserState().mUserId,
                List.of(tile)
        );

        assertThat(mA11yms.getCurrentUserState().getA11yQsTargets()).doesNotContain(tile);
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void notifyQuickSettingsTilesChanged_serviceWarningNotRequired_qsShortcutEnabled() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();
        final AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mAccessibilityButtonTargets.clear();
        userState.mAccessibilityButtonTargets.add(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString());
        ComponentName tile = new ComponentName(
                TARGET_ALWAYS_ON_A11Y_SERVICE.getPackageName(),
                TARGET_ALWAYS_ON_A11Y_SERVICE_TILE_CLASS);

        mA11yms.notifyQuickSettingsTilesChanged(
                mA11yms.getCurrentUserState().mUserId,
                List.of(tile)
        );

        assertThat(mA11yms.getCurrentUserState().getA11yQsTargets())
                .contains(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString());
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void notifyQuickSettingsTilesChanged_addFrameworkTile_qsShortcutEnabled() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        List<ComponentName> tiles = List.of(
                AccessibilityShortcutController.DALTONIZER_TILE_COMPONENT_NAME,
                AccessibilityShortcutController.COLOR_INVERSION_TILE_COMPONENT_NAME
        );

        mA11yms.notifyQuickSettingsTilesChanged(
                mA11yms.getCurrentUserState().mUserId,
                tiles
        );

        assertThat(
                mA11yms.getCurrentUserState().getA11yQsTargets()
        ).containsExactlyElementsIn(List.of(
                AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME.flattenToString(),
                AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME.flattenToString())
        );
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void notifyQuickSettingsTilesChanged_removeFrameworkTile_qsShortcutDisabled() {
        notifyQuickSettingsTilesChanged_addFrameworkTile_qsShortcutEnabled();
        Set<ComponentName> qsTiles = mA11yms.getCurrentUserState().getA11yQsTilesInQsPanel();
        qsTiles.remove(AccessibilityShortcutController.DALTONIZER_TILE_COMPONENT_NAME);

        mA11yms.notifyQuickSettingsTilesChanged(
                mA11yms.getCurrentUserState().mUserId,
                qsTiles.stream().toList()
        );

        assertThat(
                mA11yms.getCurrentUserState().getA11yQsTargets()
        ).doesNotContain(
                AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME.flattenToString());
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void restoreAccessibilityQsTargets_a11yQsTargetsRestored() {
        String daltonizerTile =
                AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME.flattenToString();
        String colorInversionTile =
                AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME.flattenToString();
        final AccessibilityUserState userState = new AccessibilityUserState(
                UserHandle.USER_SYSTEM, mTestableContext, mA11yms);
        userState.updateA11yQsTargetLocked(Set.of(daltonizerTile));
        mA11yms.mUserStates.put(UserHandle.USER_SYSTEM, userState);

        broadcastSettingRestored(
                Settings.Secure.ACCESSIBILITY_QS_TARGETS,
                /*previousValue=*/null,
                /*newValue=*/colorInversionTile);

        assertThat(mA11yms.mUserStates.get(UserHandle.USER_SYSTEM).getA11yQsTargets())
                .containsExactlyElementsIn(Set.of(daltonizerTile, colorInversionTile));
    }

    @Test
    @DisableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void restoreAccessibilityQsTargets_a11yQsTargetsNotRestored() {
        String daltonizerTile =
                AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME.flattenToString();
        String colorInversionTile =
                AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME.flattenToString();
        final AccessibilityUserState userState = new AccessibilityUserState(
                UserHandle.USER_SYSTEM, mTestableContext, mA11yms);
        userState.updateA11yQsTargetLocked(Set.of(daltonizerTile));
        mA11yms.mUserStates.put(UserHandle.USER_SYSTEM, userState);

        broadcastSettingRestored(
                Settings.Secure.ACCESSIBILITY_QS_TARGETS,
                /*previousValue=*/null,
                /*newValue=*/colorInversionTile);

        assertThat(userState.getA11yQsTargets())
                .containsExactlyElementsIn(Set.of(daltonizerTile));
    }

    @Test
    @EnableFlags(Flags.FLAG_MANAGER_PACKAGE_MONITOR_LOGIC_FIX)
    public void onHandleForceStop_dontDoIt_packageEnabled_returnsTrue() {
        setupShortcutTargetServices();
        AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mEnabledServices.addAll(
                userState.mInstalledServices.stream().map(
                        (AccessibilityServiceInfo::getComponentName)).toList());
        String[] packages = userState.mEnabledServices.stream().map(
                ComponentName::getPackageName).toList().toArray(new String[0]);

        PackageMonitor monitor = spy(mA11yms.getPackageMonitor());
        when(monitor.getChangingUserId()).thenReturn(UserHandle.USER_SYSTEM);
        mA11yms.setPackageMonitor(monitor);

        assertTrue(mA11yms.getPackageMonitor().onHandleForceStop(
                new Intent(),
                packages,
                UserHandle.USER_SYSTEM,
                false
        ));
    }

    @Test
    @EnableFlags(Flags.FLAG_MANAGER_PACKAGE_MONITOR_LOGIC_FIX)
    public void onHandleForceStop_doIt_packageEnabled_returnsFalse() {
        setupShortcutTargetServices();
        AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mEnabledServices.addAll(
                userState.mInstalledServices.stream().map(
                        (AccessibilityServiceInfo::getComponentName)).toList());
        String[] packages = userState.mEnabledServices.stream().map(
                ComponentName::getPackageName).toList().toArray(new String[0]);

        PackageMonitor monitor = spy(mA11yms.getPackageMonitor());
        when(monitor.getChangingUserId()).thenReturn(UserHandle.USER_SYSTEM);
        mA11yms.setPackageMonitor(monitor);

        assertFalse(mA11yms.getPackageMonitor().onHandleForceStop(
                new Intent(),
                packages,
                UserHandle.USER_SYSTEM,
                true
        ));
    }

    @Test
    @EnableFlags(Flags.FLAG_MANAGER_PACKAGE_MONITOR_LOGIC_FIX)
    public void onHandleForceStop_dontDoIt_packageNotEnabled_returnsFalse() {
        PackageMonitor monitor = spy(mA11yms.getPackageMonitor());
        when(monitor.getChangingUserId()).thenReturn(UserHandle.USER_SYSTEM);
        mA11yms.setPackageMonitor(monitor);

        assertFalse(mA11yms.getPackageMonitor().onHandleForceStop(
                new Intent(),
                new String[]{"FOO", "BAR"},
                UserHandle.USER_SYSTEM,
                false
        ));
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_RESTORE_A11Y_SHORTCUT_TARGET_SERVICE)
    public void restoreA11yShortcutTargetService_targetsMerged() {
        final String servicePrevious = TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString();
        final String otherPrevious = TARGET_MAGNIFICATION;
        final String combinedPrevious = String.join(":", servicePrevious, otherPrevious);
        final String serviceRestored = TARGET_STANDARD_A11Y_SERVICE.flattenToString();
        final AccessibilityUserState userState = new AccessibilityUserState(
                UserHandle.USER_SYSTEM, mTestableContext, mA11yms);
        mA11yms.mUserStates.put(UserHandle.USER_SYSTEM, userState);
        setupShortcutTargetServices(userState);

        broadcastSettingRestored(
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
                /*previousValue=*/combinedPrevious,
                /*newValue=*/serviceRestored);

        final Set<String> expected = Set.of(servicePrevious, otherPrevious, serviceRestored);
        assertThat(readStringsFromSetting(
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE))
                .containsExactlyElementsIn(expected);
        assertThat(mA11yms.mUserStates.get(UserHandle.USER_SYSTEM)
                .getShortcutTargetsLocked(UserShortcutType.HARDWARE))
                .containsExactlyElementsIn(expected);
    }

    private Set<String> readStringsFromSetting(String setting) {
        final Set<String> result = new ArraySet<>();
        mA11yms.readColonDelimitedSettingToSet(
                setting, UserHandle.USER_SYSTEM, str -> str, result);
        return result;
    }

    private void broadcastSettingRestored(String setting, String previousValue, String newValue) {
        Intent intent = new Intent(Intent.ACTION_SETTING_RESTORED)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .putExtra(Intent.EXTRA_SETTING_NAME, setting)
                .putExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE, previousValue)
                .putExtra(Intent.EXTRA_SETTING_NEW_VALUE, newValue);
        sendBroadcastToAccessibilityManagerService(intent);
        mTestableLooper.processAllMessages();
    }

    private static AccessibilityServiceInfo mockAccessibilityServiceInfo(
            ComponentName componentName) {
        return mockAccessibilityServiceInfo(
                componentName, /* isSystemApp= */ false, /* isAlwaysOnService=*/ false);
    }

    private static AccessibilityServiceInfo mockAccessibilityServiceInfo(
            ComponentName componentName,
            boolean isSystemApp, boolean isAlwaysOnService) {
        AccessibilityServiceInfo accessibilityServiceInfo =
                spy(new AccessibilityServiceInfo());
        accessibilityServiceInfo.setComponentName(componentName);
        ResolveInfo mockResolveInfo = Mockito.mock(ResolveInfo.class);
        when(accessibilityServiceInfo.getResolveInfo()).thenReturn(mockResolveInfo);
        mockResolveInfo.serviceInfo = Mockito.mock(ServiceInfo.class);
        mockResolveInfo.serviceInfo.applicationInfo = Mockito.mock(ApplicationInfo.class);
        mockResolveInfo.serviceInfo.packageName = componentName.getPackageName();
        mockResolveInfo.serviceInfo.name = componentName.getClassName();
        when(mockResolveInfo.serviceInfo.applicationInfo.isSystemApp()).thenReturn(isSystemApp);
        if (isAlwaysOnService) {
            accessibilityServiceInfo.flags |=
                    AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON;
            mockResolveInfo.serviceInfo.applicationInfo.targetSdkVersion =
                    Build.VERSION_CODES.R;
        }
        return accessibilityServiceInfo;
    }

    // Single package intents can trigger multiple PackageMonitor callbacks.
    // Collect the state of the lock in a set, since tests only care if calls
    // were all locked or all unlocked.
    private AtomicReference<Set<Boolean>> collectLockStateWhilePackageScanning() {
        final AtomicReference<Set<Boolean>> lockState =
                new AtomicReference<>(new HashSet<Boolean>());
        doAnswer((Answer<XmlResourceParser>) invocation -> {
            lockState.updateAndGet(set -> {
                set.add(mA11yms.unsafeIsLockHeld());
                return set;
            });
            return null;
        }).when(mMockResolveInfo.serviceInfo).loadXmlMetaData(any(), any());
        return lockState;
    }

    private void assertStartActivityWithExpectedComponentName(Context mockContext,
            String componentName) {
        verify(mockContext).startActivityAsUser(mIntentArgumentCaptor.capture(),
                any(Bundle.class), any(UserHandle.class));
        assertThat(mIntentArgumentCaptor.getValue().getStringExtra(
                Intent.EXTRA_COMPONENT_NAME)).isEqualTo(componentName);
    }

    private void setupShortcutTargetServices() {
        setupShortcutTargetServices(mA11yms.getCurrentUserState());
    }

    private void setupShortcutTargetServices(AccessibilityUserState userState) {
        AccessibilityServiceInfo alwaysOnServiceInfo = mockAccessibilityServiceInfo(
                TARGET_ALWAYS_ON_A11Y_SERVICE,
                /* isSystemApp= */ false,
                /* isAlwaysOnService= */ true);
        when(alwaysOnServiceInfo.getTileServiceName())
                .thenReturn(TARGET_ALWAYS_ON_A11Y_SERVICE_TILE_CLASS);
        AccessibilityServiceInfo standardServiceInfo = mockAccessibilityServiceInfo(
                TARGET_STANDARD_A11Y_SERVICE,
                /* isSystemApp= */ false,
                /* isAlwaysOnService= */ false);
        userState.mInstalledServices.addAll(
                List.of(alwaysOnServiceInfo, standardServiceInfo));
        userState.updateTileServiceMapForAccessibilityServiceLocked();
    }

    private void sendBroadcastToAccessibilityManagerService(Intent intent) {
        if (!mTestableContext.getBroadcastReceivers().containsKey(intent.getAction())) {
            return;
        }
        mTestableContext.getBroadcastReceivers().get(intent.getAction()).forEach(
                broadcastReceiver -> broadcastReceiver.onReceive(mTestableContext, intent));
    }

    public static class FakeInputFilter extends AccessibilityInputFilter {
        FakeInputFilter(Context context,
                AccessibilityManagerService service) {
            super(context, service);
        }
    }

    private static class A11yTestableContext extends TestableContext {

        private final Context mMockContext;
        private final Map<String, List<BroadcastReceiver>> mBroadcastReceivers = new ArrayMap<>();

        A11yTestableContext(Context base) {
            super(base);
            mMockContext = Mockito.mock(Context.class);
        }

        @Override
        public void startActivityAsUser(Intent intent, Bundle options, UserHandle user) {
            mMockContext.startActivityAsUser(intent, options, user);
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle user) {
            mMockContext.sendBroadcastAsUser(intent, user);
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {
            Iterator<String> actions = filter.actionsIterator();
            if (actions != null) {
                while (actions.hasNext()) {
                    String action = actions.next();
                    List<BroadcastReceiver> actionReceivers =
                            mBroadcastReceivers.getOrDefault(action, new ArrayList<>());
                    actionReceivers.add(receiver);
                    mBroadcastReceivers.put(action, actionReceivers);
                }
            }
            return super.registerReceiverAsUser(
                    receiver, user, filter, broadcastPermission, scheduler);
        }

        Context getMockContext() {
            return mMockContext;
        }

        Map<String, List<BroadcastReceiver>> getBroadcastReceivers() {
            return mBroadcastReceivers;
        }
    }

    private static boolean isSameCurrentUser(AccessibilityManagerService service, Context context) {
        return service.getCurrentUserIdLocked() == context.getUserId();
    }
}
