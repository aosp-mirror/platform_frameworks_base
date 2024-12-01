/*
 ** Copyright 2017, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.android.server.accessibility;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityTrace;
import android.accessibilityservice.BrailleDisplayController;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.IBrailleDisplayConnection;
import android.accessibilityservice.IBrailleDisplayController;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.test.FakePermissionEnforcer;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.testing.DexmakerShareClassLoaderRule;
import android.view.Display;
import android.view.WindowManager;

import com.android.server.accessibility.magnification.MagnificationProcessor;
import com.android.server.accessibility.test.MessageCapturingHandler;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Tests for AccessibilityServiceConnection
 */
public class AccessibilityServiceConnectionTest {
    static final ComponentName COMPONENT_NAME = new ComponentName(
            "com.android.server.accessibility", "AccessibilityServiceConnectionTest");
    static final int SERVICE_ID = 42;

    // Mock package-private AccessibilityUserState class
    @Rule
    public final DexmakerShareClassLoaderRule mDexmakerShareClassLoaderRule =
            new DexmakerShareClassLoaderRule();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    AccessibilityServiceConnection mConnection;

    @Mock AccessibilityUserState mMockUserState;
    @Mock Context mMockContext;
    AccessibilityServiceInfo mServiceInfo;
    @Mock ResolveInfo mMockResolveInfo;
    @Mock AccessibilitySecurityPolicy mMockSecurityPolicy;
    @Mock
    AccessibilityWindowManager mMockA11yWindowManager;
    @Mock
    ActivityTaskManagerInternal mMockActivityTaskManagerInternal;
    @Mock
    AbstractAccessibilityServiceConnection.SystemSupport mMockSystemSupport;
    @Mock
    AccessibilityTrace mMockA11yTrace;
    @Mock
    WindowManagerInternal mMockWindowManagerInternal;
    @Mock
    SystemActionPerformer mMockSystemActionPerformer;
    @Mock
    KeyEventDispatcher mMockKeyEventDispatcher;
    @Mock
    MagnificationProcessor mMockMagnificationProcessor;
    @Mock
    IBinder mMockIBinder;
    @Mock
    IAccessibilityServiceClient mMockServiceClient;
    @Mock
    IBrailleDisplayController mMockBrailleDisplayController;
    @Mock
    MotionEventInjector mMockMotionEventInjector;
    FakePermissionEnforcer mFakePermissionEnforcer = new FakePermissionEnforcer();

    MessageCapturingHandler mHandler = new MessageCapturingHandler(null);

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mMockSystemSupport.getKeyEventDispatcher()).thenReturn(mMockKeyEventDispatcher);
        when(mMockSystemSupport.getMagnificationProcessor())
                .thenReturn(mMockMagnificationProcessor);
        when(mMockSystemSupport.getMotionEventInjectorForDisplayLocked(
                Display.DEFAULT_DISPLAY)).thenReturn(mMockMotionEventInjector);

        mServiceInfo = spy(new AccessibilityServiceInfo());
        when(mServiceInfo.getResolveInfo()).thenReturn(mMockResolveInfo);
        mMockResolveInfo.serviceInfo = mock(ServiceInfo.class);
        mMockResolveInfo.serviceInfo.applicationInfo = mock(ApplicationInfo.class);

        when(mMockIBinder.queryLocalInterface(any())).thenReturn(mMockServiceClient);
        when(mMockA11yTrace.isA11yTracingEnabled()).thenReturn(false);
        when(mMockContext.getSystemService(Context.DISPLAY_SERVICE))
                .thenReturn(new DisplayManager(mMockContext));
        when(mMockContext.getSystemService(Context.PERMISSION_ENFORCER_SERVICE))
                .thenReturn(mFakePermissionEnforcer);

        mConnection = new AccessibilityServiceConnection(mMockUserState, mMockContext,
                        COMPONENT_NAME, mServiceInfo, SERVICE_ID, mHandler, new Object(),
                        mMockSecurityPolicy, mMockSystemSupport, mMockA11yTrace,
                        mMockWindowManagerInternal, mMockSystemActionPerformer,
                        mMockA11yWindowManager, mMockActivityTaskManagerInternal);
        when(mMockSecurityPolicy.canPerformGestures(mConnection)).thenReturn(true);
        when(mMockSecurityPolicy.checkAccessibilityAccess(mConnection)).thenReturn(true);
    }

    @After
    public void tearDown() {
        mHandler.removeAllMessages();
    }


    @Test
    public void bind_requestsContextToBindService() {
        mConnection.bindLocked();
        verify(mMockContext).bindServiceAsUser(any(Intent.class), eq(mConnection),
                eq(Context.BIND_AUTO_CREATE
                        | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE
                        | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS
                        | Context.BIND_INCLUDE_CAPABILITIES),
                any(UserHandle.class));
    }

    @Test
    public void unbind_requestsContextToUnbindService() {
        mConnection.unbindLocked();
        verify(mMockContext).unbindService(mConnection);
    }

    @Test
    public void bindConnectUnbind_linksAndUnlinksToServiceDeath() throws RemoteException {
        setServiceBinding(COMPONENT_NAME);
        mConnection.bindLocked();
        mConnection.onServiceConnected(COMPONENT_NAME, mMockIBinder);
        verify(mMockIBinder).linkToDeath(eq(mConnection), anyInt());
        mConnection.unbindLocked();
        verify(mMockIBinder).unlinkToDeath(eq(mConnection), anyInt());
    }

    @Test
    public void connectedServiceCrashedAndRestarted_crashReportedInServiceInfo() {
        setServiceBinding(COMPONENT_NAME);
        mConnection.bindLocked();
        mConnection.onServiceConnected(COMPONENT_NAME, mMockIBinder);
        assertFalse(mConnection.getServiceInfo().crashed);
        mConnection.binderDied();
        assertTrue(mConnection.getServiceInfo().crashed);
        mConnection.onServiceConnected(COMPONENT_NAME, mMockIBinder);
        mHandler.sendAllMessages();
        assertFalse(mConnection.getServiceInfo().crashed);
    }

    @Test
    public void onServiceConnected_addsWindowTokens() {
        setServiceBinding(COMPONENT_NAME);
        mConnection.bindLocked();
        mConnection.onServiceConnected(COMPONENT_NAME, mMockIBinder);

        verify(mMockWindowManagerInternal).addWindowToken(
                any(), eq(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY),
                anyInt(), eq(null));
    }

    private void setServiceBinding(ComponentName componentName) {
        when(mMockUserState.getBindingServicesLocked())
                .thenReturn(new HashSet<>(Arrays.asList(componentName)));
    }

    @Test
    public void binderDied_keysGetFlushed() {
        setServiceBinding(COMPONENT_NAME);
        mConnection.bindLocked();
        mConnection.onServiceConnected(COMPONENT_NAME, mMockIBinder);
        mConnection.binderDied();
        assertTrue(mConnection.getServiceInfo().crashed);
        verify(mMockKeyEventDispatcher).flush(mConnection);
    }

    @Test
    public void connectedService_notInEnabledServiceList_doNotInitClient()
            throws RemoteException {
        when(mMockUserState.getEnabledServicesLocked())
                .thenReturn(Collections.emptySet());
        setServiceBinding(COMPONENT_NAME);

        mConnection.bindLocked();
        mConnection.onServiceConnected(COMPONENT_NAME, mMockIBinder);
        mHandler.sendAllMessages();
        verify(mMockSystemSupport, times(2)).onClientChangeLocked(false);
        verify(mMockServiceClient, times(0)).init(any(), anyInt(), any());
    }

    @Test
    public void sendGesture_touchableDevice_injectEvents()
            throws RemoteException {
        when(mMockWindowManagerInternal.isTouchOrFaketouchDevice()).thenReturn(true);
        setServiceBinding(COMPONENT_NAME);
        mConnection.bindLocked();
        mConnection.onServiceConnected(COMPONENT_NAME, mMockIBinder);

        ParceledListSlice parceledListSlice = mock(ParceledListSlice.class);
        List<GestureDescription.GestureStep> gestureSteps = mock(List.class);
        when(parceledListSlice.getList()).thenReturn(gestureSteps);
        mConnection.dispatchGesture(0, parceledListSlice, Display.DEFAULT_DISPLAY);

        verify(mMockMotionEventInjector).injectEvents(gestureSteps, mMockServiceClient, 0,
                Display.DEFAULT_DISPLAY);
    }

    @Test
    public void sendGesture_untouchableDevice_performGestureResultFailed()
            throws RemoteException {
        when(mMockWindowManagerInternal.isTouchOrFaketouchDevice()).thenReturn(false);
        setServiceBinding(COMPONENT_NAME);
        mConnection.bindLocked();
        mConnection.onServiceConnected(COMPONENT_NAME, mMockIBinder);

        ParceledListSlice parceledListSlice = mock(ParceledListSlice.class);
        List<GestureDescription.GestureStep> gestureSteps = mock(List.class);
        when(parceledListSlice.getList()).thenReturn(gestureSteps);
        mConnection.dispatchGesture(0, parceledListSlice, Display.DEFAULT_DISPLAY);

        verify(mMockMotionEventInjector, never()).injectEvents(gestureSteps, mMockServiceClient, 0,
                Display.DEFAULT_DISPLAY);
        verify(mMockServiceClient).onPerformGestureResult(0, false);
    }

    @Test
    public void sendGesture_invalidDisplay_performGestureResultFailed()
            throws RemoteException {
        setServiceBinding(COMPONENT_NAME);
        mConnection.bindLocked();
        mConnection.onServiceConnected(COMPONENT_NAME, mMockIBinder);

        ParceledListSlice parceledListSlice = mock(ParceledListSlice.class);
        List<GestureDescription.GestureStep> gestureSteps = mock(List.class);
        when(parceledListSlice.getList()).thenReturn(gestureSteps);
        mConnection.dispatchGesture(0, parceledListSlice, Display.INVALID_DISPLAY);

        verify(mMockServiceClient).onPerformGestureResult(0, false);
    }

    @Test
    public void unbind_resetAllMagnification() {
        mConnection.unbindLocked();
        verify(mMockMagnificationProcessor).resetAllIfNeeded(anyInt());
    }

    @Test
    public void binderDied_resetAllMagnification() {
        setServiceBinding(COMPONENT_NAME);
        mConnection.bindLocked();
        mConnection.onServiceConnected(COMPONENT_NAME, mMockIBinder);

        mConnection.binderDied();

        verify(mMockMagnificationProcessor).resetAllIfNeeded(anyInt());
    }

    @Test
    @RequiresFlagsEnabled(android.view.accessibility.Flags.FLAG_BRAILLE_DISPLAY_HID)
    public void connectBluetoothBrailleDisplay() throws Exception {
        mFakePermissionEnforcer.grant(Manifest.permission.BLUETOOTH_CONNECT);
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        final String macAddress = "00:11:22:33:AA:BB";
        final byte[] descriptor = {0x05, 0x41};
        Bundle bd = new Bundle();
        bd.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_HIDRAW_PATH, "/dev/null");
        bd.putByteArray(BrailleDisplayController.TEST_BRAILLE_DISPLAY_DESCRIPTOR, descriptor);
        bd.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_UNIQUE_ID, macAddress);
        bd.putBoolean(BrailleDisplayController.TEST_BRAILLE_DISPLAY_BUS_BLUETOOTH, true);
        mConnection.setTestBrailleDisplayData(List.of(bd));

        mConnection.connectBluetoothBrailleDisplay(macAddress, mMockBrailleDisplayController);

        ArgumentCaptor<IBrailleDisplayConnection> connection =
                ArgumentCaptor.forClass(IBrailleDisplayConnection.class);
        verify(mMockBrailleDisplayController).onConnected(connection.capture(), eq(descriptor));
        // Cleanup the connection.
        connection.getValue().disconnect();
    }

    @Test
    @RequiresFlagsEnabled(android.view.accessibility.Flags.FLAG_BRAILLE_DISPLAY_HID)
    public void connectBluetoothBrailleDisplay_throwsForMissingBluetoothConnectPermission() {
        assertThrows(SecurityException.class,
                () -> mConnection.connectBluetoothBrailleDisplay("unused",
                        mMockBrailleDisplayController));
    }

    @Test
    @RequiresFlagsEnabled(android.view.accessibility.Flags.FLAG_BRAILLE_DISPLAY_HID)
    public void connectBluetoothBrailleDisplay_throwsForNullMacAddress() {
        mFakePermissionEnforcer.grant(Manifest.permission.BLUETOOTH_CONNECT);
        assertThrows(NullPointerException.class,
                () -> mConnection.connectBluetoothBrailleDisplay(null,
                        mMockBrailleDisplayController));
    }

    @Test
    @RequiresFlagsEnabled(android.view.accessibility.Flags.FLAG_BRAILLE_DISPLAY_HID)
    public void connectBluetoothBrailleDisplay_throwsForMisformattedMacAddress() {
        mFakePermissionEnforcer.grant(Manifest.permission.BLUETOOTH_CONNECT);
        assertThrows(IllegalArgumentException.class,
                () -> mConnection.connectBluetoothBrailleDisplay("12:34",
                        mMockBrailleDisplayController));
    }

    @Test
    @RequiresFlagsEnabled(android.view.accessibility.Flags.FLAG_BRAILLE_DISPLAY_HID)
    public void connectUsbBrailleDisplay() throws Exception {
        mFakePermissionEnforcer.grant(Manifest.permission.MANAGE_ACCESSIBILITY);
        final String serialNumber = "myUsbDevice";
        final byte[] descriptor = {0x05, 0x41};
        Bundle bd = new Bundle();
        bd.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_HIDRAW_PATH, "/dev/null");
        bd.putByteArray(BrailleDisplayController.TEST_BRAILLE_DISPLAY_DESCRIPTOR, descriptor);
        bd.putString(BrailleDisplayController.TEST_BRAILLE_DISPLAY_UNIQUE_ID, serialNumber);
        bd.putBoolean(BrailleDisplayController.TEST_BRAILLE_DISPLAY_BUS_BLUETOOTH, false);
        mConnection.setTestBrailleDisplayData(List.of(bd));
        UsbDevice usbDevice = Mockito.mock(UsbDevice.class);
        when(usbDevice.getSerialNumber()).thenReturn(serialNumber);
        UsbManager usbManager = Mockito.mock(UsbManager.class);
        when(mMockContext.getSystemService(Context.USB_SERVICE)).thenReturn(usbManager);
        when(usbManager.hasPermission(eq(usbDevice), eq(COMPONENT_NAME.getPackageName()),
                anyInt(), anyInt())).thenReturn(true);

        mConnection.connectUsbBrailleDisplay(usbDevice, mMockBrailleDisplayController);

        ArgumentCaptor<IBrailleDisplayConnection> connection =
                ArgumentCaptor.forClass(IBrailleDisplayConnection.class);
        verify(mMockBrailleDisplayController).onConnected(connection.capture(), eq(descriptor));
        // Cleanup the connection.
        connection.getValue().disconnect();
    }

    @Test
    @RequiresFlagsEnabled(android.view.accessibility.Flags.FLAG_BRAILLE_DISPLAY_HID)
    public void connectUsbBrailleDisplay_throwsForMissingUsbPermission() {
        UsbManager usbManager = Mockito.mock(UsbManager.class);
        when(mMockContext.getSystemService(Context.USB_SERVICE)).thenReturn(usbManager);
        when(usbManager.hasPermission(notNull(), eq(COMPONENT_NAME.getPackageName()),
                anyInt(), anyInt())).thenReturn(false);

        assertThrows(SecurityException.class,
                () -> mConnection.connectUsbBrailleDisplay(Mockito.mock(UsbDevice.class),
                        mMockBrailleDisplayController));
    }

    @Test
    @RequiresFlagsEnabled(android.view.accessibility.Flags.FLAG_BRAILLE_DISPLAY_HID)
    public void connectUsbBrailleDisplay_throwsForNullDevice() {
        assertThrows(NullPointerException.class,
                () -> mConnection.connectUsbBrailleDisplay(null, mMockBrailleDisplayController));
    }

    @Test
    @RequiresFlagsEnabled(android.view.accessibility.Flags.FLAG_BRAILLE_DISPLAY_HID)
    public void connectUsbBrailleDisplay_callsOnConnectionFailedForEmptySerialNumber()
            throws Exception {
        UsbManager usbManager = Mockito.mock(UsbManager.class);
        when(mMockContext.getSystemService(Context.USB_SERVICE)).thenReturn(usbManager);
        when(usbManager.hasPermission(notNull(), eq(COMPONENT_NAME.getPackageName()),
                anyInt(), anyInt())).thenReturn(true);
        UsbDevice usbDevice = Mockito.mock(UsbDevice.class);
        when(usbDevice.getSerialNumber()).thenReturn("");

        mConnection.connectUsbBrailleDisplay(usbDevice, mMockBrailleDisplayController);

        verify(mMockBrailleDisplayController).onConnectionFailed(
                BrailleDisplayController.BrailleDisplayCallback
                        .FLAG_ERROR_BRAILLE_DISPLAY_NOT_FOUND);
    }

    @Test
    public void binderDied_resetA11yServiceInfo() {
        final int flag = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceBinding(COMPONENT_NAME);
        mConnection.bindLocked();
        mConnection.onServiceConnected(COMPONENT_NAME, mMockIBinder);
        AccessibilityServiceInfo info = mConnection.getServiceInfo();
        assertThat(info.flags & flag).isEqualTo(0);

        info = mConnection.getServiceInfo();
        info.flags |= flag;
        mConnection.setServiceInfo(info);
        assertThat(mConnection.getServiceInfo().flags & flag).isEqualTo(flag);

        mConnection.binderDied();
        assertThat(mConnection.getServiceInfo().flags & flag).isEqualTo(0);
    }

    @Test
    public void setInputMethodEnabled_checksAccessWithProvidedImeIdAndUserId() {
        final String imeId = "test_ime_id";
        final int callingUserId = UserHandle.getCallingUserId();
        mConnection.setInputMethodEnabled(imeId, true);

        verify(mMockSecurityPolicy).canEnableDisableInputMethod(
                eq(imeId), any(), eq(callingUserId));
    }
}
