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
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_GESTURE;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_NONE;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
import static android.provider.Settings.Secure.NAVIGATION_MODE;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;
import static android.view.accessibility.Flags.FLAG_SKIP_ACCESSIBILITY_WARNING_DIALOG_FOR_TRUSTED_SERVICES;

import static com.android.internal.accessibility.AccessibilityShortcutController.ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.GESTURE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.HARDWARE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.KEY_GESTURE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.QUICK_SETTINGS;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.SOFTWARE;
import static com.android.internal.accessibility.dialog.AccessibilityButtonChooserActivity.EXTRA_TYPE_TO_CHOOSE;
import static com.android.server.accessibility.AccessibilityManagerService.ACTION_LAUNCH_HEARING_DEVICES_DIALOG;

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
import android.annotation.NonNull;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.app.admin.DevicePolicyManager;
import android.app.ecm.EnhancedConfirmationManager;
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
import android.hardware.input.KeyGestureEvent;
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
import android.view.accessibility.IUserInitializationCompleteCallback;

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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
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
    private static final String TARGET_STANDARD_A11Y_SERVICE_NAME =
            TARGET_STANDARD_A11Y_SERVICE.flattenToString();

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
    @Mock private DevicePolicyManager mDevicePolicyManager;
    @Spy private IUserInitializationCompleteCallback mUserInitializationCompleteCallback;
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
        mInputFilter = mock(FakeInputFilter.class);
        mTestableContext.addMockSystemService(DevicePolicyManager.class, mDevicePolicyManager);

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
        when(mMockSecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(anyInt()))
                .then(AdditionalAnswers.returnsFirstArg());
        when(mMockSecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(
                eq(UserHandle.USER_CURRENT)))
                .thenReturn(mTestableContext.getUserId());

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
        mA11yms.switchUser(mTestableContext.getUserId());
        mTestableLooper.processAllMessages();

        FieldSetter.setField(mA11yms,
                AccessibilityManagerService.class.getDeclaredField("mHasInputFilter"), true);
        FieldSetter.setField(mA11yms,
                AccessibilityManagerService.class.getDeclaredField("mInputFilter"), mInputFilter);
        final AccessibilityUserState userState = new AccessibilityUserState(
                mTestableContext.getUserId(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        AccessibilityManager am = mTestableContext.getSystemService(AccessibilityManager.class);
        mA11yManagerServiceOnDevice = (IAccessibilityManager) new FieldReader(am,
                AccessibilityManager.class.getDeclaredField("mService")).read();
        FieldSetter.setField(am, AccessibilityManager.class.getDeclaredField("mService"), mA11yms);
        Mockito.clearInvocations(mMockMagnificationConnectionManager);
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
        userState.updateShortcutTargetsLocked(Set.of(MAGNIFICATION_CONTROLLER_NAME), HARDWARE);
        userState.setMagnificationCapabilitiesLocked(
                ACCESSIBILITY_MAGNIFICATION_MODE_ALL);

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
                ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
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
                ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
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
                ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
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
                ACCESSIBILITY_MAGNIFICATION_MODE_ALL);
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
        userState.updateShortcutTargetsLocked(
                Set.of(ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString()), HARDWARE);

        mA11yms.performAccessibilityShortcut(
                Display.DEFAULT_DISPLAY, HARDWARE,
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
        userState.updateShortcutTargetsLocked(
                Set.of(ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString()), HARDWARE);

        mA11yms.performAccessibilityShortcut(
                Display.DEFAULT_DISPLAY, HARDWARE,
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
        writeStringsToSetting(Set.of(
                info_a.getComponentName().flattenToString(),
                info_b.getComponentName().flattenToString()),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

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
        final Set<String> enabledServices =
                readStringsFromSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        assertThat(enabledServices).containsExactly(info_b.getComponentName().flattenToString());
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
        userState.updateShortcutTargetsLocked(Set.of(
                        info_a.getComponentName().flattenToString(),
                        info_b.getComponentName().flattenToString()),
                SOFTWARE);
        writeStringsToSetting(Set.of(
                        info_a.getComponentName().flattenToString(),
                        info_b.getComponentName().flattenToString()),
                ShortcutUtils.convertToKey(SOFTWARE));

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
        assertThat(userState.getShortcutTargetsLocked(SOFTWARE)).containsExactly(
                info_b.getComponentName().flattenToString());
        //Assert setting change
        final Set<String> targetsFromSetting = readStringsFromSetting(
                ShortcutUtils.convertToKey(SOFTWARE));
        assertThat(targetsFromSetting).containsExactly(info_b.getComponentName().flattenToString());
    }

    @Test
    public void testPackagesForceStopped_otherServiceStopped_doesNotRemoveContinuousTarget() {
        final AccessibilityServiceInfo info_a = new AccessibilityServiceInfo();
        info_a.setComponentName(COMPONENT_NAME);
        info_a.flags = FLAG_REQUEST_ACCESSIBILITY_BUTTON;
        final AccessibilityServiceInfo info_b = new AccessibilityServiceInfo();
        info_b.setComponentName(new ComponentName("package", "class"));
        writeStringsToSetting(Set.of(
                        info_a.getComponentName().flattenToString(),
                        info_b.getComponentName().flattenToString()),
                ShortcutUtils.convertToKey(SOFTWARE));

        AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mInstalledServices.clear();
        userState.mInstalledServices.add(info_a);
        userState.mInstalledServices.add(info_b);
        userState.updateShortcutTargetsLocked(Set.of(
                        info_a.getComponentName().flattenToString(),
                        info_b.getComponentName().flattenToString()),
                SOFTWARE);

        // Force stopping a service should not disable unrelated continuous services.
        synchronized (mA11yms.getLock()) {
            mA11yms.onPackagesForceStoppedLocked(
                    new String[]{info_b.getComponentName().getPackageName()},
                    userState);
        }

        //Assert user state change
        userState = mA11yms.getCurrentUserState();
        assertThat(userState.getShortcutTargetsLocked(SOFTWARE)).containsExactly(
                info_a.getComponentName().flattenToString(),
                info_b.getComponentName().flattenToString());
        //Assert setting unchanged
        final Set<String> targetsFromSetting = readStringsFromSetting(
                ShortcutUtils.convertToKey(SOFTWARE));
        assertThat(targetsFromSetting).containsExactly(
                info_a.getComponentName().flattenToString(),
                info_b.getComponentName().flattenToString());
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
    public void onPackageChanged_disableComponent_updateInstalledServices() {
        // Sets up two accessibility services as installed services
        setupShortcutTargetServices();
        assertThat(mA11yms.getCurrentUserState().mInstalledServices).hasSize(2);
        AccessibilityServiceInfo installedService1 =
                mA11yms.getCurrentUserState().mInstalledServices.getFirst();
        ResolveInfo resolveInfo1 = installedService1.getResolveInfo();
        AccessibilityServiceInfo installedService2 =
                mA11yms.getCurrentUserState().mInstalledServices.getLast();
        // Invokes client change to trigger onUserStateChanged.
        mA11yms.onClientChangeLocked(false);

        // Disables `installedService2`
        when(mMockPackageManager.queryIntentServicesAsUser(any(), anyInt(), anyInt()))
                .thenReturn(List.of(resolveInfo1));
        when(mMockSecurityPolicy.canRegisterService(any())).thenReturn(true);
        final Intent packageIntent = new Intent(Intent.ACTION_PACKAGE_CHANGED);
        packageIntent.setData(
                Uri.parse("package:" + installedService2.getResolveInfo().serviceInfo.packageName));
        packageIntent.putExtra(Intent.EXTRA_UID, UserHandle.myUserId());
        packageIntent.putExtra(Intent.EXTRA_USER_HANDLE, mA11yms.getCurrentUserIdLocked());
        packageIntent.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST,
                new String[]{
                        installedService2.getComponentName().flattenToString()});
        mA11yms.getPackageMonitor().doHandlePackageEvent(packageIntent);

        assertThat(mA11yms.getCurrentUserState().mInstalledServices).hasSize(1);
        ComponentName installedService =
                mA11yms.getCurrentUserState().mInstalledServices.getFirst().getComponentName();
        assertThat(installedService)
                .isEqualTo(installedService1.getComponentName());
    }

    @Test
    public void testSwitchUserScanPackages_scansWithoutHoldingLock() {
        setupAccessibilityServiceConnection(0);
        final AtomicReference<Set<Boolean>> lockState = collectLockStateWhilePackageScanning();
        when(mMockPackageManager.queryIntentServicesAsUser(any(), anyInt(), anyInt()))
                .thenReturn(List.of(mMockResolveInfo));
        when(mMockSecurityPolicy.canRegisterService(any())).thenReturn(true);

        mA11yms.switchUser(mA11yms.getCurrentUserIdLocked() + 1);
        mTestableLooper.processAllMessages();

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
        userState.updateShortcutTargetsLocked(
                Set.of(info_b.getComponentName().flattenToString()), SOFTWARE);
        userState.updateShortcutTargetsLocked(
                Set.of(info_c.getComponentName().flattenToString()), HARDWARE);

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
                        SOFTWARE,
                        List.of(TARGET_MAGNIFICATION),
                        mA11yms.getCurrentUserIdLocked()));
    }

    @Test
    public void enableShortcutsForTargets_enableSoftwareShortcut_shortcutTurnedOn()
            throws Exception {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();
        String target = TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                SOFTWARE,
                List.of(target),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(ShortcutUtils.isComponentIdExistingInSettings(
                mTestableContext, SOFTWARE, target
        )).isTrue();
    }

    @Test
    public void enableHardwareShortcutsForTargets_shortcutDialogSetting_isShown() {
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
                HARDWARE,
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
        String target = TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString();
        enableShortcutsForTargets_enableSoftwareShortcut_shortcutTurnedOn();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ false,
                SOFTWARE,
                List.of(target),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(ShortcutUtils.isComponentIdExistingInSettings(
                mTestableContext, SOFTWARE, target
        )).isFalse();
    }

    @Test
    public void enableShortcutsForTargets_enableSoftwareShortcutWithMagnification_menuSizeIncreased() {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                SOFTWARE,
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
                SOFTWARE,
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
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                SOFTWARE,
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
        enableShortcutsForTargets_enableAlwaysOnServiceSoftwareShortcut_turnsOnAlwaysOnService();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ false,
                SOFTWARE,
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
                SOFTWARE,
                List.of(TARGET_STANDARD_A11Y_SERVICE_NAME),
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
        enableShortcutsForTargets_enableStandardServiceSoftwareShortcut_wontTurnOnService();
        AccessibilityUtils.setAccessibilityServiceState(
                mTestableContext, TARGET_STANDARD_A11Y_SERVICE, /* enabled= */ true);

        mA11yms.enableShortcutsForTargets(
                /* enable= */ false,
                SOFTWARE,
                List.of(TARGET_STANDARD_A11Y_SERVICE_NAME),
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
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                HARDWARE,
                List.of(TARGET_STANDARD_A11Y_SERVICE_NAME),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                ShortcutUtils.isComponentIdExistingInSettings(
                        mTestableContext, HARDWARE,
                        TARGET_STANDARD_A11Y_SERVICE_NAME)
        ).isTrue();
    }

    @Test
    public void enableShortcutsForTargets_disableVolumeKeysShortcut_shortcutNotSet() {
        enableShortcutsForTargets_enableVolumeKeysShortcut_shortcutSet();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ false,
                HARDWARE,
                List.of(TARGET_STANDARD_A11Y_SERVICE_NAME),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                        ShortcutUtils.isComponentIdExistingInSettings(
                                mTestableContext,
                                HARDWARE,
                                TARGET_STANDARD_A11Y_SERVICE_NAME))
                .isFalse();
    }

    @Test
    public void enableShortcutsForTargets_enableQuickSettings_shortcutSet() {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ true,
                QUICK_SETTINGS,
                List.of(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                ShortcutUtils.isComponentIdExistingInSettings(
                        mTestableContext, QUICK_SETTINGS,
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
        enableShortcutsForTargets_enableQuickSettings_shortcutSet();

        mA11yms.enableShortcutsForTargets(
                /* enable= */ false,
                QUICK_SETTINGS,
                List.of(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()),
                mA11yms.getCurrentUserIdLocked());
        mTestableLooper.processAllMessages();

        assertThat(
                ShortcutUtils.isComponentIdExistingInSettings(
                        mTestableContext, QUICK_SETTINGS,
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

        assertThat(mA11yms.getCurrentUserState()
                .getShortcutTargetsLocked(QUICK_SETTINGS)).doesNotContain(tile.flattenToString());
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void notifyQuickSettingsTilesChanged_serviceWarningNotRequired_qsShortcutEnabled() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        setupShortcutTargetServices();
        final AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.updateShortcutTargetsLocked(
                Set.of(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()), SOFTWARE);
        ComponentName tile = new ComponentName(
                TARGET_ALWAYS_ON_A11Y_SERVICE.getPackageName(),
                TARGET_ALWAYS_ON_A11Y_SERVICE_TILE_CLASS);

        mA11yms.notifyQuickSettingsTilesChanged(
                mA11yms.getCurrentUserState().mUserId,
                List.of(tile)
        );

        assertThat(mA11yms.getCurrentUserState().getShortcutTargetsLocked(QUICK_SETTINGS))
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
                mA11yms.getCurrentUserState().getShortcutTargetsLocked(QUICK_SETTINGS)
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
                mA11yms.getCurrentUserState().getShortcutTargetsLocked(QUICK_SETTINGS)
        ).doesNotContain(
                AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME.flattenToString());
    }

    @Test
    @EnableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void restoreShortcutTargets_qs_a11yQsTargetsRestored() {
        // TODO: remove the assumption when we fix b/381294327
        assumeTrue("The test is setup to run as a user 0",
                mTestableContext.getUserId() == UserHandle.USER_SYSTEM);
        String daltonizerTile =
                AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME.flattenToString();
        String colorInversionTile =
                AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME.flattenToString();
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        userState.updateShortcutTargetsLocked(Set.of(daltonizerTile), QUICK_SETTINGS);
        mA11yms.mUserStates.put(userState.mUserId, userState);

        broadcastSettingRestored(
                ShortcutUtils.convertToKey(QUICK_SETTINGS),
                /*newValue=*/colorInversionTile);

        Set<String> expected = Set.of(daltonizerTile, colorInversionTile);
        assertThat(readStringsFromSetting(ShortcutUtils.convertToKey(QUICK_SETTINGS)))
                .containsExactlyElementsIn(expected);
        assertThat(userState.getShortcutTargetsLocked(QUICK_SETTINGS))
                .containsExactlyElementsIn(expected);
    }

    @Test
    @DisableFlags(android.view.accessibility.Flags.FLAG_A11Y_QS_SHORTCUT)
    public void restoreShortcutTargets_qs_a11yQsTargetsNotRestored() {
        // TODO: remove the assumption when we fix b/381294327
        assumeTrue("The test is setup to run as a user 0",
                mTestableContext.getUserId() == UserHandle.USER_SYSTEM);
        String daltonizerTile =
                AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME.flattenToString();
        String colorInversionTile =
                AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME.flattenToString();
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        userState.updateShortcutTargetsLocked(Set.of(daltonizerTile), QUICK_SETTINGS);
        putShortcutSettingForUser(QUICK_SETTINGS, daltonizerTile, userState.mUserId);
        mA11yms.mUserStates.put(userState.mUserId, userState);

        broadcastSettingRestored(
                ShortcutUtils.convertToKey(QUICK_SETTINGS),
                /*newValue=*/colorInversionTile);

        Set<String> expected = Set.of(daltonizerTile);
        assertThat(readStringsFromSetting(ShortcutUtils.convertToKey(QUICK_SETTINGS)))
                .containsExactlyElementsIn(expected);
        assertThat(userState.getShortcutTargetsLocked(QUICK_SETTINGS))
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void onHandleForceStop_dontDoIt_packageEnabled_returnsTrue() {
        setupShortcutTargetServices();
        AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mEnabledServices.addAll(
                userState.mInstalledServices.stream().map(
                        (AccessibilityServiceInfo::getComponentName)).toList());
        String[] packages = userState.mEnabledServices.stream().map(
                ComponentName::getPackageName).toList().toArray(new String[0]);

        PackageMonitor monitor = spy(mA11yms.getPackageMonitor());
        when(monitor.getChangingUserId()).thenReturn(userState.mUserId);
        mA11yms.setPackageMonitor(monitor);

        assertTrue(mA11yms.getPackageMonitor().onHandleForceStop(
                new Intent(),
                packages,
                userState.mUserId,
                false
        ));
    }

    @Test
    public void onHandleForceStop_doIt_packageEnabled_returnsFalse() {
        setupShortcutTargetServices();
        AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mEnabledServices.addAll(
                userState.mInstalledServices.stream().map(
                        (AccessibilityServiceInfo::getComponentName)).toList());
        String[] packages = userState.mEnabledServices.stream().map(
                ComponentName::getPackageName).toList().toArray(new String[0]);

        PackageMonitor monitor = spy(mA11yms.getPackageMonitor());
        when(monitor.getChangingUserId()).thenReturn(userState.mUserId);
        mA11yms.setPackageMonitor(monitor);

        assertFalse(mA11yms.getPackageMonitor().onHandleForceStop(
                new Intent(),
                packages,
                userState.mUserId,
                true
        ));
    }

    @Test
    public void onHandleForceStop_dontDoIt_packageNotEnabled_returnsFalse() {
        PackageMonitor monitor = spy(mA11yms.getPackageMonitor());
        when(monitor.getChangingUserId()).thenReturn(mA11yms.getCurrentUserIdLocked());
        mA11yms.setPackageMonitor(monitor);

        assertFalse(mA11yms.getPackageMonitor().onHandleForceStop(
                new Intent(),
                new String[]{"FOO", "BAR"},
                mA11yms.getCurrentUserIdLocked(),
                false
        ));
    }

    @Test
    public void restoreShortcutTargets_hardware_targetsMerged() {
        // TODO: remove the assumption when we fix b/381294327
        assumeTrue("The test is setup to run as a user 0",
                mTestableContext.getUserId() == UserHandle.USER_SYSTEM);
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        final String servicePrevious = TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString();
        final String otherPrevious = TARGET_MAGNIFICATION;
        final String serviceRestored = TARGET_STANDARD_A11Y_SERVICE_NAME;
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        setupShortcutTargetServices(userState);
        mA11yms.enableShortcutsForTargets(
                true, HARDWARE, List.of(servicePrevious, otherPrevious), userState.mUserId);

        broadcastSettingRestored(
                ShortcutUtils.convertToKey(HARDWARE),
                /*newValue=*/serviceRestored);

        final Set<String> expected = Set.of(servicePrevious, otherPrevious, serviceRestored);
        assertThat(readStringsFromSetting(ShortcutUtils.convertToKey(HARDWARE)))
                .containsExactlyElementsIn(expected);
        assertThat(userState.getShortcutTargetsLocked(HARDWARE))
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void restoreShortcutTargets_hardware_alreadyHadDefaultService_doesNotClear() {
        // TODO: remove the assumption when we fix b/381294327
        assumeTrue("The test is setup to run as a user 0",
                mTestableContext.getUserId() == UserHandle.USER_SYSTEM);
        final String serviceDefault = TARGET_STANDARD_A11Y_SERVICE_NAME;
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.string.config_defaultAccessibilityService, serviceDefault);
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        setupShortcutTargetServices(userState);

        // default is present in userState & setting, so it's not cleared
        putShortcutSettingForUser(HARDWARE, serviceDefault, userState.mUserId);
        userState.updateShortcutTargetsLocked(Set.of(serviceDefault), HARDWARE);

        broadcastSettingRestored(
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE,
                /*newValue=*/serviceDefault);

        final Set<String> expected = Set.of(serviceDefault);
        assertThat(readStringsFromSetting(ShortcutUtils.convertToKey(HARDWARE)))
                .containsExactlyElementsIn(expected);
        assertThat(userState.getShortcutTargetsLocked(HARDWARE))
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void restoreShortcutTargets_hardware_didNotHaveDefaultService_clearsDefaultService() {
        // TODO: remove the assumption when we fix b/381294327
        assumeTrue("The test is setup to run as a user 0",
                mTestableContext.getUserId() == UserHandle.USER_SYSTEM);
        final String serviceDefault = TARGET_STANDARD_A11Y_SERVICE_NAME;
        final String serviceRestored = TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString();
        // Restored value from the broadcast contains both default and non-default service.
        final String combinedRestored = String.join(":", serviceDefault, serviceRestored);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.string.config_defaultAccessibilityService, serviceDefault);
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        setupShortcutTargetServices(userState);

        broadcastSettingRestored(ShortcutUtils.convertToKey(HARDWARE),
                /*newValue=*/combinedRestored);

        // The default service is cleared from the final restored value.
        final Set<String> expected = Set.of(serviceRestored);
        assertThat(readStringsFromSetting(ShortcutUtils.convertToKey(HARDWARE)))
                .containsExactlyElementsIn(expected);
        assertThat(userState.getShortcutTargetsLocked(HARDWARE))
                .containsExactlyElementsIn(expected);
    }

    @Test
    public void restoreShortcutTargets_hardware_nullSetting_clearsDefaultService() {
        // TODO: remove the assumption when we fix b/381294327
        assumeTrue("The test is setup to run as a user 0",
                mTestableContext.getUserId() == UserHandle.USER_SYSTEM);
        final String serviceDefault = TARGET_STANDARD_A11Y_SERVICE_NAME;
        final String serviceRestored = TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString();
        // Restored value from the broadcast contains both default and non-default service.
        final String combinedRestored = String.join(":", serviceDefault, serviceRestored);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.string.config_defaultAccessibilityService, serviceDefault);
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        setupShortcutTargetServices(userState);

        // UserState has default, but setting is null (this emulates a typical scenario in SUW).
        userState.updateShortcutTargetsLocked(Set.of(serviceDefault), HARDWARE);
        putShortcutSettingForUser(HARDWARE, null, userState.mUserId);

        broadcastSettingRestored(ShortcutUtils.convertToKey(HARDWARE),
                /*newValue=*/combinedRestored);

        // The default service is cleared from the final restored value.
        final Set<String> expected = Set.of(serviceRestored);
        assertThat(readStringsFromSetting(ShortcutUtils.convertToKey(HARDWARE)))
                .containsExactlyElementsIn(expected);
        assertThat(userState.getShortcutTargetsLocked(HARDWARE))
                .containsExactlyElementsIn(expected);
    }

    @Test
    @EnableFlags(android.provider.Flags.FLAG_A11Y_STANDALONE_GESTURE_ENABLED)
    public void onNavButtonNavigation_migratesGestureTargets() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        setupShortcutTargetServices(userState);
        userState.updateShortcutTargetsLocked(
                Set.of(TARGET_STANDARD_A11Y_SERVICE_NAME), SOFTWARE);
        userState.updateShortcutTargetsLocked(
                Set.of(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()), GESTURE);

        Settings.Secure.putIntForUser(mTestableContext.getContentResolver(),
                NAVIGATION_MODE, NAV_BAR_MODE_3BUTTON, userState.mUserId);
        mA11yms.updateShortcutsForCurrentNavigationMode();

        assertShortcutUserStateAndSetting(userState, GESTURE, Set.of());
        assertShortcutUserStateAndSetting(userState, SOFTWARE, Set.of(
                TARGET_STANDARD_A11Y_SERVICE_NAME,
                TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()
        ));
    }

    @Test
    @EnableFlags(android.provider.Flags.FLAG_A11Y_STANDALONE_GESTURE_ENABLED)
    public void onNavButtonNavigation_gestureTargets_noButtonTargets_navBarButtonMode() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        setupShortcutTargetServices(userState);
        userState.updateShortcutTargetsLocked(Set.of(), SOFTWARE);
        userState.updateShortcutTargetsLocked(
                Set.of(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()), GESTURE);
        ShortcutUtils.setButtonMode(
                mTestableContext, ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU, userState.mUserId);

        Settings.Secure.putIntForUser(mTestableContext.getContentResolver(),
                NAVIGATION_MODE, NAV_BAR_MODE_3BUTTON, userState.mUserId);
        mA11yms.updateShortcutsForCurrentNavigationMode();

        assertThat(ShortcutUtils.getButtonMode(mTestableContext, userState.mUserId))
                .isEqualTo(ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR);
    }

    @Test
    @EnableFlags(android.provider.Flags.FLAG_A11Y_STANDALONE_GESTURE_ENABLED)
    public void onGestureNavigation_floatingMenuMode() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        setupShortcutTargetServices(userState);
        ShortcutUtils.setButtonMode(
                mTestableContext, ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR, userState.mUserId);

        Settings.Secure.putIntForUser(mTestableContext.getContentResolver(),
                NAVIGATION_MODE, NAV_BAR_MODE_GESTURAL, userState.mUserId);
        mA11yms.updateShortcutsForCurrentNavigationMode();

        assertThat(ShortcutUtils.getButtonMode(mTestableContext, userState.mUserId))
                .isEqualTo(ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU);
    }

    @Test
    @DisableFlags(android.provider.Flags.FLAG_A11Y_STANDALONE_GESTURE_ENABLED)
    public void onNavigation_revertGestureTargets() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        setupShortcutTargetServices(userState);
        userState.updateShortcutTargetsLocked(
                Set.of(TARGET_STANDARD_A11Y_SERVICE_NAME), SOFTWARE);
        userState.updateShortcutTargetsLocked(
                Set.of(TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()), GESTURE);

        Settings.Secure.putIntForUser(mTestableContext.getContentResolver(),
                NAVIGATION_MODE, NAV_BAR_MODE_3BUTTON, userState.mUserId);
        mA11yms.updateShortcutsForCurrentNavigationMode();

        assertShortcutUserStateAndSetting(userState, GESTURE, Set.of());
        assertShortcutUserStateAndSetting(userState, SOFTWARE, Set.of(
                TARGET_STANDARD_A11Y_SERVICE_NAME,
                TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()
        ));
    }

    @Test
    @EnableFlags(android.provider.Flags.FLAG_A11Y_STANDALONE_GESTURE_ENABLED)
    public void onNavigation_gestureNavigation_gestureButtonMode_migratesTargetsToGesture() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        setupShortcutTargetServices(userState);
        userState.updateShortcutTargetsLocked(Set.of(
                TARGET_STANDARD_A11Y_SERVICE_NAME,
                TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()), SOFTWARE);
        userState.updateShortcutTargetsLocked(Set.of(), GESTURE);

        ShortcutUtils.setButtonMode(
                mTestableContext, ACCESSIBILITY_BUTTON_MODE_GESTURE, userState.mUserId);
        Settings.Secure.putIntForUser(mTestableContext.getContentResolver(),
                NAVIGATION_MODE, NAV_BAR_MODE_GESTURAL, userState.mUserId);
        mA11yms.updateShortcutsForCurrentNavigationMode();

        assertShortcutUserStateAndSetting(userState, SOFTWARE, Set.of());
        assertShortcutUserStateAndSetting(userState, GESTURE, Set.of(
                TARGET_STANDARD_A11Y_SERVICE_NAME,
                TARGET_ALWAYS_ON_A11Y_SERVICE.flattenToString()
        ));
    }

    @Test
    @DisableFlags(android.provider.Flags.FLAG_A11Y_STANDALONE_GESTURE_ENABLED)
    public void onNavigation_gestureNavigation_correctsButtonMode() {
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        setupShortcutTargetServices(userState);
        ShortcutUtils.setButtonMode(
                mTestableContext, ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR, userState.mUserId);

        Settings.Secure.putIntForUser(mTestableContext.getContentResolver(),
                NAVIGATION_MODE, NAV_BAR_MODE_GESTURAL, userState.mUserId);
        mA11yms.updateShortcutsForCurrentNavigationMode();

        assertThat(ShortcutUtils.getButtonMode(mTestableContext, userState.mUserId))
                .isEqualTo(ACCESSIBILITY_BUTTON_MODE_GESTURE);
    }

    @Test
    @DisableFlags(android.provider.Flags.FLAG_A11Y_STANDALONE_GESTURE_ENABLED)
    public void onNavigation_navBarNavigation_correctsButtonMode() {
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        setupShortcutTargetServices(userState);
        ShortcutUtils.setButtonMode(
                mTestableContext, ACCESSIBILITY_BUTTON_MODE_GESTURE, userState.mUserId);

        Settings.Secure.putIntForUser(mTestableContext.getContentResolver(),
                NAVIGATION_MODE, NAV_BAR_MODE_3BUTTON, userState.mUserId);
        mA11yms.updateShortcutsForCurrentNavigationMode();

        assertThat(ShortcutUtils.getButtonMode(mTestableContext, userState.mUserId))
                .isEqualTo(ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR);
    }

    @Test
    public void showAccessibilityTargetSelection_navBarNavigationMode_softwareExtra() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        Settings.Secure.putIntForUser(mTestableContext.getContentResolver(),
                NAVIGATION_MODE, NAV_BAR_MODE_3BUTTON, userState.mUserId);

        mA11yms.notifyAccessibilityButtonLongClicked(Display.DEFAULT_DISPLAY);
        mTestableLooper.processAllMessages();

        assertStartActivityWithExpectedShortcutType(mTestableContext.getMockContext(), SOFTWARE);
    }

    @Test
    @DisableFlags(android.provider.Flags.FLAG_A11Y_STANDALONE_GESTURE_ENABLED)
    public void showAccessibilityTargetSelection_gestureNavigationMode_softwareExtra() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        Settings.Secure.putIntForUser(mTestableContext.getContentResolver(),
                NAVIGATION_MODE, NAV_BAR_MODE_GESTURAL, userState.mUserId);

        mA11yms.notifyAccessibilityButtonLongClicked(Display.DEFAULT_DISPLAY);
        mTestableLooper.processAllMessages();

        assertStartActivityWithExpectedShortcutType(mTestableContext.getMockContext(), SOFTWARE);
    }

    @Test
    @EnableFlags(android.provider.Flags.FLAG_A11Y_STANDALONE_GESTURE_ENABLED)
    public void showAccessibilityTargetSelection_gestureNavigationMode_gestureExtra() {
        mFakePermissionEnforcer.grant(Manifest.permission.STATUS_BAR_SERVICE);
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        Settings.Secure.putIntForUser(mTestableContext.getContentResolver(),
                NAVIGATION_MODE, NAV_BAR_MODE_GESTURAL, userState.mUserId);

        mA11yms.notifyAccessibilityButtonLongClicked(Display.DEFAULT_DISPLAY);
        mTestableLooper.processAllMessages();

        assertStartActivityWithExpectedShortcutType(mTestableContext.getMockContext(), GESTURE);
    }

    @Test
    public void registerUserInitializationCompleteCallback_isRegistered() {
        mA11yms.mUserInitializationCompleteCallbacks.clear();

        mA11yms.registerUserInitializationCompleteCallback(mUserInitializationCompleteCallback);

        assertThat(mA11yms.mUserInitializationCompleteCallbacks).containsExactly(
                mUserInitializationCompleteCallback);
    }

    @Test
    public void unregisterUserInitializationCompleteCallback_isUnregistered() {
        mA11yms.mUserInitializationCompleteCallbacks.clear();
        mA11yms.mUserInitializationCompleteCallbacks.add(mUserInitializationCompleteCallback);

        mA11yms.unregisterUserInitializationCompleteCallback(mUserInitializationCompleteCallback);

        assertThat(mA11yms.mUserInitializationCompleteCallbacks).isEmpty();
    }

    @Test
    public void switchUser_callsUserInitializationCompleteCallback() throws RemoteException {
        mA11yms.mUserInitializationCompleteCallbacks.add(mUserInitializationCompleteCallback);

        int newUserId = mA11yms.getCurrentUserIdLocked() + 1;
        mA11yms.switchUser(newUserId);
        mTestableLooper.processAllMessages();

        verify(mUserInitializationCompleteCallback).onUserInitializationComplete(newUserId);
    }

    @Test
    @DisableFlags(android.provider.Flags.FLAG_A11Y_STANDALONE_GESTURE_ENABLED)
    public void getShortcutTypeForGenericShortcutCalls_softwareType() {
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);

        assertThat(mA11yms.getShortcutTypeForGenericShortcutCalls(userState.mUserId))
                .isEqualTo(SOFTWARE);
    }

    @Test
    @EnableFlags(android.provider.Flags.FLAG_A11Y_STANDALONE_GESTURE_ENABLED)
    public void getShortcutTypeForGenericShortcutCalls_gestureNavigationMode_gestureType() {
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        Settings.Secure.putIntForUser(mTestableContext.getContentResolver(),
                NAVIGATION_MODE, NAV_BAR_MODE_GESTURAL, userState.mUserId);

        assertThat(mA11yms.getShortcutTypeForGenericShortcutCalls(userState.mUserId))
                .isEqualTo(GESTURE);
    }

    @Test
    @EnableFlags(android.provider.Flags.FLAG_A11Y_STANDALONE_GESTURE_ENABLED)
    public void getShortcutTypeForGenericShortcutCalls_buttonNavigationMode_softwareType() {
        final AccessibilityUserState userState = new AccessibilityUserState(
                mA11yms.getCurrentUserIdLocked(), mTestableContext, mA11yms);
        mA11yms.mUserStates.put(userState.mUserId, userState);
        Settings.Secure.putIntForUser(mTestableContext.getContentResolver(),
                NAVIGATION_MODE, NAV_BAR_MODE_3BUTTON, userState.mUserId);

        assertThat(mA11yms.getShortcutTypeForGenericShortcutCalls(userState.mUserId))
                .isEqualTo(SOFTWARE);
    }

    @Test
    @EnableFlags({android.permission.flags.Flags.FLAG_ENHANCED_CONFIRMATION_MODE_APIS_ENABLED,
            android.security.Flags.FLAG_EXTEND_ECM_TO_ALL_SETTINGS})
    public void isAccessibilityTargetAllowed_nonSystemUserId_useEcmWithNonSystemUserId() {
        String fakePackageName = "FAKE_PACKAGE_NAME";
        int uid = 0; // uid is not used in the actual implementation when flags are on
        int userId = mTestableContext.getUserId() + 1234;
        when(mDevicePolicyManager.getPermittedAccessibilityServices(userId)).thenReturn(
                List.of(fakePackageName));
        Context mockUserContext = mock(Context.class);
        mTestableContext.addMockUserContext(userId, mockUserContext);

        mA11yms.isAccessibilityTargetAllowed(fakePackageName, uid, userId);

        verify(mockUserContext).getSystemService(EnhancedConfirmationManager.class);
    }

    @Test
    @EnableFlags(com.android.hardware.input.Flags.FLAG_ENABLE_TALKBACK_AND_MAGNIFIER_KEY_GESTURES)
    public void handleKeyGestureEvent_toggleMagnifier() {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        assertThat(ShortcutUtils.getShortcutTargetsFromSettings(mTestableContext, KEY_GESTURE,
                mA11yms.getCurrentUserIdLocked())).isEmpty();

        mA11yms.handleKeyGestureEvent(new KeyGestureEvent.Builder().setKeyGestureType(
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MAGNIFICATION).setAction(
                        KeyGestureEvent.ACTION_GESTURE_COMPLETE).build());

        assertThat(ShortcutUtils.getShortcutTargetsFromSettings(mTestableContext, KEY_GESTURE,
                mA11yms.getCurrentUserIdLocked())).containsExactly(MAGNIFICATION_CONTROLLER_NAME);

        // The magnifier will only be toggled on the second event received since the first is
        // used to toggle the feature on.
        mA11yms.handleKeyGestureEvent(new KeyGestureEvent.Builder().setKeyGestureType(
                KeyGestureEvent.KEY_GESTURE_TYPE_TOGGLE_MAGNIFICATION).setAction(
                KeyGestureEvent.ACTION_GESTURE_COMPLETE).build());

        verify(mInputFilter).notifyMagnificationShortcutTriggered(anyInt());
    }

    @Test
    @EnableFlags(com.android.hardware.input.Flags.FLAG_ENABLE_TALKBACK_AND_MAGNIFIER_KEY_GESTURES)
    public void handleKeyGestureEvent_activateSelectToSpeak_trustedService() {
        setupAccessibilityServiceConnection(FLAG_REQUEST_ACCESSIBILITY_BUTTON);
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);

        final AccessibilityServiceInfo trustedService = mockAccessibilityServiceInfo(
                new ComponentName("package_a", "class_a"),
                /* isSystemApp= */ true, /* isAlwaysOnService= */ true);
        AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mInstalledServices.add(trustedService);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.string.config_defaultSelectToSpeakService,
                trustedService.getComponentName().flattenToString());
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.array.config_trustedAccessibilityServices,
                new String[]{trustedService.getComponentName().flattenToString()});

        assertThat(ShortcutUtils.getShortcutTargetsFromSettings(mTestableContext, KEY_GESTURE,
                mA11yms.getCurrentUserIdLocked())).isEmpty();

        mA11yms.handleKeyGestureEvent(new KeyGestureEvent.Builder().setKeyGestureType(
                KeyGestureEvent.KEY_GESTURE_TYPE_ACTIVATE_SELECT_TO_SPEAK).setAction(
                KeyGestureEvent.ACTION_GESTURE_COMPLETE).build());

        assertThat(ShortcutUtils.getShortcutTargetsFromSettings(mTestableContext, KEY_GESTURE,
                mA11yms.getCurrentUserIdLocked())).containsExactly(
                trustedService.getComponentName().flattenToString());
    }

    @Test
    @EnableFlags(com.android.hardware.input.Flags.FLAG_ENABLE_TALKBACK_AND_MAGNIFIER_KEY_GESTURES)
    public void handleKeyGestureEvent_activateSelectToSpeak_preinstalledService() {
        setupAccessibilityServiceConnection(FLAG_REQUEST_ACCESSIBILITY_BUTTON);
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);

        final AccessibilityServiceInfo untrustedService = mockAccessibilityServiceInfo(
                new ComponentName("package_a", "class_a"),
                /* isSystemApp= */ true, /* isAlwaysOnService= */ true);
        AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mInstalledServices.add(untrustedService);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.string.config_defaultSelectToSpeakService,
                untrustedService.getComponentName().flattenToString());

        assertThat(ShortcutUtils.getShortcutTargetsFromSettings(mTestableContext, KEY_GESTURE,
                mA11yms.getCurrentUserIdLocked())).isEmpty();

        mA11yms.handleKeyGestureEvent(new KeyGestureEvent.Builder().setKeyGestureType(
                KeyGestureEvent.KEY_GESTURE_TYPE_ACTIVATE_SELECT_TO_SPEAK).setAction(
                KeyGestureEvent.ACTION_GESTURE_COMPLETE).build());

        assertThat(ShortcutUtils.getShortcutTargetsFromSettings(mTestableContext, KEY_GESTURE,
                mA11yms.getCurrentUserIdLocked())).isEmpty();
    }

    @Test
    @EnableFlags(com.android.hardware.input.Flags.FLAG_ENABLE_TALKBACK_AND_MAGNIFIER_KEY_GESTURES)
    public void handleKeyGestureEvent_activateSelectToSpeak_downloadedService() {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);

        final AccessibilityServiceInfo downloadedService = mockAccessibilityServiceInfo(
                new ComponentName("package_a", "class_a"),
                /* isSystemApp= */ false, /* isAlwaysOnService= */ true);
        AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mInstalledServices.add(downloadedService);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.string.config_defaultSelectToSpeakService,
                downloadedService.getComponentName().flattenToString());
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.array.config_trustedAccessibilityServices,
                new String[]{downloadedService.getComponentName().flattenToString()});

        assertThat(ShortcutUtils.getShortcutTargetsFromSettings(mTestableContext, KEY_GESTURE,
                mA11yms.getCurrentUserIdLocked())).isEmpty();

        mA11yms.handleKeyGestureEvent(new KeyGestureEvent.Builder().setKeyGestureType(
                KeyGestureEvent.KEY_GESTURE_TYPE_ACTIVATE_SELECT_TO_SPEAK).setAction(
                KeyGestureEvent.ACTION_GESTURE_COMPLETE).build());

        assertThat(ShortcutUtils.getShortcutTargetsFromSettings(mTestableContext, KEY_GESTURE,
                mA11yms.getCurrentUserIdLocked())).isEmpty();
    }

    @Test
    @EnableFlags(com.android.hardware.input.Flags.FLAG_ENABLE_TALKBACK_AND_MAGNIFIER_KEY_GESTURES)
    public void handleKeyGestureEvent_activateSelectToSpeak_defaultNotInstalled() {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);

        final AccessibilityServiceInfo installedService = mockAccessibilityServiceInfo(
                new ComponentName("package_a", "class_a"),
                /* isSystemApp= */ true, /* isAlwaysOnService= */ true);
        final AccessibilityServiceInfo defaultService = mockAccessibilityServiceInfo(
                new ComponentName("package_b", "class_b"),
                /* isSystemApp= */ true, /* isAlwaysOnService= */ true);
        AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mInstalledServices.add(installedService);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.string.config_defaultSelectToSpeakService,
                defaultService.getComponentName().flattenToString());
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.array.config_trustedAccessibilityServices,
                new String[]{defaultService.getComponentName().flattenToString()});

        assertThat(ShortcutUtils.getShortcutTargetsFromSettings(mTestableContext, KEY_GESTURE,
                mA11yms.getCurrentUserIdLocked())).isEmpty();

        mA11yms.handleKeyGestureEvent(new KeyGestureEvent.Builder().setKeyGestureType(
                KeyGestureEvent.KEY_GESTURE_TYPE_ACTIVATE_SELECT_TO_SPEAK).setAction(
                KeyGestureEvent.ACTION_GESTURE_COMPLETE).build());

        assertThat(ShortcutUtils.getShortcutTargetsFromSettings(mTestableContext, KEY_GESTURE,
                mA11yms.getCurrentUserIdLocked())).isEmpty();
    }

    @Test
    @EnableFlags(com.android.hardware.input.Flags.FLAG_ENABLE_TALKBACK_AND_MAGNIFIER_KEY_GESTURES)
    public void handleKeyGestureEvent_activateSelectToSpeak_noDefault() {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);

        final AccessibilityServiceInfo installedService = mockAccessibilityServiceInfo(
                new ComponentName("package_a", "class_a"),
                /* isSystemApp= */ true, /* isAlwaysOnService= */ true);
        AccessibilityUserState userState = mA11yms.getCurrentUserState();
        userState.mInstalledServices.add(installedService);
        mTestableContext.getOrCreateTestableResources().addOverride(
                R.array.config_trustedAccessibilityServices,
                new String[]{installedService.getComponentName().flattenToString()});

        assertThat(ShortcutUtils.getShortcutTargetsFromSettings(mTestableContext, KEY_GESTURE,
                mA11yms.getCurrentUserIdLocked())).isEmpty();

        mA11yms.handleKeyGestureEvent(new KeyGestureEvent.Builder().setKeyGestureType(
                KeyGestureEvent.KEY_GESTURE_TYPE_ACTIVATE_SELECT_TO_SPEAK).setAction(
                KeyGestureEvent.ACTION_GESTURE_COMPLETE).build());

        assertThat(ShortcutUtils.getShortcutTargetsFromSettings(mTestableContext, KEY_GESTURE,
                mA11yms.getCurrentUserIdLocked())).isEmpty();
    }

    private Set<String> readStringsFromSetting(String setting) {
        final Set<String> result = new ArraySet<>();
        mA11yms.readColonDelimitedSettingToSet(
                setting, mA11yms.getCurrentUserIdLocked(), str -> str, result);
        return result;
    }

    private void writeStringsToSetting(Set<String> strings, String setting) {
        mA11yms.persistColonDelimitedSetToSettingLocked(
                setting, mA11yms.getCurrentUserIdLocked(), strings, str -> str);
    }

    private void broadcastSettingRestored(String setting, String newValue) {
        Intent intent = new Intent(Intent.ACTION_SETTING_RESTORED)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .putExtra(Intent.EXTRA_SETTING_NAME, setting)
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
        ResolveInfo mockResolveInfo = mock(ResolveInfo.class);
        when(accessibilityServiceInfo.getResolveInfo()).thenReturn(mockResolveInfo);
        mockResolveInfo.serviceInfo = mock(ServiceInfo.class);
        mockResolveInfo.serviceInfo.applicationInfo = mock(ApplicationInfo.class);
        mockResolveInfo.serviceInfo.packageName = componentName.getPackageName();
        mockResolveInfo.serviceInfo.name = componentName.getClassName();
        when(mockResolveInfo.serviceInfo.applicationInfo.isSystemApp()).thenReturn(isSystemApp);
        if (isAlwaysOnService) {
            accessibilityServiceInfo.flags |=
                    FLAG_REQUEST_ACCESSIBILITY_BUTTON;
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

    private void assertStartActivityWithExpectedShortcutType(Context mockContext,
            @UserShortcutType int shortcutType) {
        verify(mockContext).startActivityAsUser(mIntentArgumentCaptor.capture(),
                any(Bundle.class), any(UserHandle.class));
        assertThat(mIntentArgumentCaptor.getValue().getIntExtra(
                EXTRA_TYPE_TO_CHOOSE, -1)).isEqualTo(shortcutType);
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

        @Override
        void notifyMagnificationShortcutTriggered(int displayId) {
        }
    }

    private static class A11yTestableContext extends TestableContext {

        private final Context mMockContext;
        private final Map<String, List<BroadcastReceiver>> mBroadcastReceivers = new ArrayMap<>();
        private ArrayMap<Integer, Context> mMockUserContexts = new ArrayMap<>();

        A11yTestableContext(Context base) {
            super(base);
            mMockContext = mock(Context.class);
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

        public void addMockUserContext(int userId, Context context) {
            mMockUserContexts.put(userId, context);
        }

        @Override
        @NonNull
        public Context createContextAsUser(UserHandle user, int flags) {
            if (mMockUserContexts.containsKey(user.getIdentifier())) {
                return mMockUserContexts.get(user.getIdentifier());
            }
            return super.createContextAsUser(user, flags);
        }

        Map<String, List<BroadcastReceiver>> getBroadcastReceivers() {
            return mBroadcastReceivers;
        }
    }

    private void putShortcutSettingForUser(@UserShortcutType int shortcutType,
            String shortcutValue, int userId) {
        Settings.Secure.putStringForUser(
                mTestableContext.getContentResolver(),
                ShortcutUtils.convertToKey(shortcutType),
                shortcutValue,
                userId);
    }

    private void assertShortcutUserStateAndSetting(AccessibilityUserState userState,
            @UserShortcutType int shortcutType, Set<String> value) {
        assertThat(userState.getShortcutTargetsLocked(shortcutType))
                .containsExactlyElementsIn(value);
        Set<String> setting = readStringsFromSetting(ShortcutUtils.convertToKey(shortcutType));
        assertThat(setting).containsExactlyElementsIn(value);
    }
}
