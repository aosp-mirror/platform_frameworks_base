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

package com.android.server.companion.virtual;

import static android.content.pm.ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.Manifest;
import android.app.WindowConfiguration;
import android.app.admin.DevicePolicyManager;
import android.companion.AssociationInfo;
import android.companion.virtual.IVirtualDeviceActivityListener;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.audio.IAudioConfigChangedCallback;
import android.companion.virtual.audio.IAudioRoutingCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.graphics.Point;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManagerInternal;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualMouseButtonEvent;
import android.hardware.input.VirtualMouseRelativeEvent;
import android.hardware.input.VirtualMouseScrollEvent;
import android.hardware.input.VirtualTouchEvent;
import android.net.MacAddress;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.WorkSource;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArraySet;
import android.view.DisplayInfo;
import android.view.KeyEvent;
import android.view.WindowManager;

import androidx.test.InstrumentationRegistry;

import com.android.internal.app.BlockedAppStreamingActivity;
import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;

@Presubmit
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class VirtualDeviceManagerServiceTest {

    private static final String NONBLOCKED_APP_PACKAGE_NAME = "com.someapp";
    private static final String PERMISSION_CONTROLLER_PACKAGE_NAME =
            "com.android.permissioncontroller";
    private static final String SETTINGS_PACKAGE_NAME = "com.android.settings";
    private static final String VENDING_PACKAGE_NAME = "com.android.vending";
    private static final String GOOGLE_DIALER_PACKAGE_NAME = "com.google.android.dialer";
    private static final String GOOGLE_MAPS_PACKAGE_NAME = "com.google.android.apps.maps";
    private static final String DEVICE_NAME = "device name";
    private static final int DISPLAY_ID = 2;
    private static final int UID_1 = 0;
    private static final int UID_2 = 10;
    private static final int UID_3 = 10000;
    private static final int UID_4 = 10001;
    private static final int ASSOCIATION_ID_1 = 1;
    private static final int ASSOCIATION_ID_2 = 2;
    private static final int PRODUCT_ID = 10;
    private static final int VENDOR_ID = 5;
    private static final String UNIQUE_ID = "uniqueid";
    private static final String PHYS = "phys";
    private static final int HEIGHT = 1800;
    private static final int WIDTH = 900;
    private static final Binder BINDER = new Binder("binder");
    private static final int FLAG_CANNOT_DISPLAY_ON_REMOTE_DEVICES = 0x00000;

    private Context mContext;
    private InputManagerMockHelper mInputManagerMockHelper;
    private VirtualDeviceImpl mDeviceImpl;
    private InputController mInputController;
    private AssociationInfo mAssociationInfo;
    private VirtualDeviceManagerService mVdms;
    private VirtualDeviceManagerInternal mLocalService;
    @Mock
    private InputController.NativeWrapper mNativeWrapperMock;
    @Mock
    private DisplayManagerInternal mDisplayManagerInternalMock;
    @Mock
    private VirtualDeviceImpl.PendingTrampolineCallback mPendingTrampolineCallback;
    @Mock
    private DevicePolicyManager mDevicePolicyManagerMock;
    @Mock
    private InputManagerInternal mInputManagerInternalMock;
    @Mock
    private IVirtualDeviceActivityListener mActivityListener;
    @Mock
    private Consumer<ArraySet<Integer>> mRunningAppsChangedCallback;
    @Mock
    private VirtualDeviceManagerInternal.VirtualDisplayListener mDisplayListener;
    @Mock
    private VirtualDeviceManagerInternal.AppsOnVirtualDeviceListener mAppsOnVirtualDeviceListener;
    @Mock
    IPowerManager mIPowerManagerMock;
    @Mock
    IThermalService mIThermalServiceMock;
    private PowerManager mPowerManager;
    @Mock
    private IAudioRoutingCallback mRoutingCallback;
    @Mock
    private IAudioConfigChangedCallback mConfigChangedCallback;
    @Mock
    private ApplicationInfo mApplicationInfoMock;
    @Mock
    IInputManager mIInputManagerMock;

    private ArraySet<ComponentName> getBlockedActivities() {
        ArraySet<ComponentName> blockedActivities = new ArraySet<>();
        blockedActivities.add(new ComponentName(SETTINGS_PACKAGE_NAME, SETTINGS_PACKAGE_NAME));
        blockedActivities.add(new ComponentName(VENDING_PACKAGE_NAME, VENDING_PACKAGE_NAME));
        blockedActivities.add(
                new ComponentName(GOOGLE_DIALER_PACKAGE_NAME, GOOGLE_DIALER_PACKAGE_NAME));
        blockedActivities.add(
                new ComponentName(GOOGLE_MAPS_PACKAGE_NAME, GOOGLE_MAPS_PACKAGE_NAME));
        return blockedActivities;
    }

    private ArrayList<ActivityInfo> getActivityInfoList(
            String packageName, String name, boolean displayOnRemoveDevices) {
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = packageName;
        activityInfo.name = name;
        activityInfo.flags = displayOnRemoveDevices
                ? FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES : FLAG_CANNOT_DISPLAY_ON_REMOTE_DEVICES;
        activityInfo.applicationInfo = mApplicationInfoMock;
        return new ArrayList<>(Arrays.asList(activityInfo));
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternalMock);

        doReturn(true).when(mInputManagerInternalMock).setVirtualMousePointerDisplayId(anyInt());
        doNothing().when(mInputManagerInternalMock).setPointerAcceleration(anyFloat(), anyInt());
        doNothing().when(mInputManagerInternalMock).setPointerIconVisible(anyBoolean(), anyInt());
        LocalServices.removeServiceForTest(InputManagerInternal.class);
        LocalServices.addService(InputManagerInternal.class, mInputManagerInternalMock);

        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.uniqueId = UNIQUE_ID;
        doReturn(displayInfo).when(mDisplayManagerInternalMock).getDisplayInfo(anyInt());
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternalMock);

        mContext = Mockito.spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        doReturn(mContext).when(mContext).createContextAsUser(eq(Process.myUserHandle()), anyInt());
        doNothing().when(mContext).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CREATE_VIRTUAL_DEVICE), anyString());
        when(mContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(
                mDevicePolicyManagerMock);

        mPowerManager = new PowerManager(mContext, mIPowerManagerMock, mIThermalServiceMock,
                new Handler(TestableLooper.get(this).getLooper()));
        when(mContext.getSystemService(Context.POWER_SERVICE)).thenReturn(mPowerManager);

        mInputManagerMockHelper = new InputManagerMockHelper(
                TestableLooper.get(this), mNativeWrapperMock, mIInputManagerMock);
        // Allow virtual devices to be created on the looper thread for testing.
        final InputController.DeviceCreationThreadVerifier threadVerifier = () -> true;
        mInputController = new InputController(new Object(), mNativeWrapperMock,
                new Handler(TestableLooper.get(this).getLooper()),
                mContext.getSystemService(WindowManager.class), threadVerifier);

        mAssociationInfo = new AssociationInfo(1, 0, null,
                MacAddress.BROADCAST_ADDRESS, "", null, true, false, false, 0, 0);

        mVdms = new VirtualDeviceManagerService(mContext);
        mLocalService = mVdms.getLocalServiceInstance();

        VirtualDeviceParams params = new VirtualDeviceParams
                .Builder()
                .setBlockedActivities(getBlockedActivities())
                .build();
        mDeviceImpl = new VirtualDeviceImpl(mContext,
                mAssociationInfo, new Binder(), /* uid */ 0, mInputController,
                (int associationId) -> {
                }, mPendingTrampolineCallback, mActivityListener, mRunningAppsChangedCallback,
                params);
    }

    @Test
    public void onVirtualDisplayRemovedLocked_doesNotThrowException() {
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        // This call should not throw any exceptions.
        mDeviceImpl.onVirtualDisplayRemovedLocked(DISPLAY_ID);
    }

    @Test
    public void onVirtualDisplayCreatedLocked_listenersNotified() {
        mLocalService.registerVirtualDisplayListener(mDisplayListener);

        mLocalService.onVirtualDisplayCreated(DISPLAY_ID);
        TestableLooper.get(this).processAllMessages();

        verify(mDisplayListener).onVirtualDisplayCreated(DISPLAY_ID);
    }

    @Test
    public void onVirtualDisplayRemovedLocked_listenersNotified() {
        mLocalService.registerVirtualDisplayListener(mDisplayListener);
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);

        mLocalService.onVirtualDisplayRemoved(mDeviceImpl, DISPLAY_ID);
        TestableLooper.get(this).processAllMessages();

        verify(mDisplayListener).onVirtualDisplayRemoved(DISPLAY_ID);
    }

    @Test
    public void onAppsOnVirtualDeviceChanged_singleVirtualDevice_listenersNotified() {
        ArraySet<Integer> uids = new ArraySet<>(Arrays.asList(UID_1, UID_2));
        mLocalService.registerAppsOnVirtualDeviceListener(mAppsOnVirtualDeviceListener);

        mVdms.notifyRunningAppsChanged(ASSOCIATION_ID_1, uids);
        TestableLooper.get(this).processAllMessages();

        verify(mAppsOnVirtualDeviceListener).onAppsOnAnyVirtualDeviceChanged(uids);
    }

    @Test
    public void onAppsOnVirtualDeviceChanged_multipleVirtualDevices_listenersNotified() {
        ArraySet<Integer> uidsOnDevice1 = new ArraySet<>(Arrays.asList(UID_1, UID_2));
        ArraySet<Integer> uidsOnDevice2 = new ArraySet<>(Arrays.asList(UID_3, UID_4));
        mLocalService.registerAppsOnVirtualDeviceListener(mAppsOnVirtualDeviceListener);

        // Notifies that the running apps on the first virtual device has changed.
        mVdms.notifyRunningAppsChanged(ASSOCIATION_ID_1, uidsOnDevice1);
        TestableLooper.get(this).processAllMessages();
        verify(mAppsOnVirtualDeviceListener).onAppsOnAnyVirtualDeviceChanged(
                new ArraySet<>(Arrays.asList(UID_1, UID_2)));

        // Notifies that the running apps on the second virtual device has changed.
        mVdms.notifyRunningAppsChanged(ASSOCIATION_ID_2, uidsOnDevice2);
        TestableLooper.get(this).processAllMessages();
        // The union of the apps running on both virtual devices are sent to the listeners.
        verify(mAppsOnVirtualDeviceListener).onAppsOnAnyVirtualDeviceChanged(
                new ArraySet<>(Arrays.asList(UID_1, UID_2, UID_3, UID_4)));

        // Notifies that the running apps on the first virtual device has changed again.
        uidsOnDevice1.remove(UID_2);
        mVdms.notifyRunningAppsChanged(ASSOCIATION_ID_1, uidsOnDevice1);
        mLocalService.onAppsOnVirtualDeviceChanged();
        TestableLooper.get(this).processAllMessages();
        // The union of the apps running on both virtual devices are sent to the listeners.
        verify(mAppsOnVirtualDeviceListener).onAppsOnAnyVirtualDeviceChanged(
                new ArraySet<>(Arrays.asList(UID_1, UID_3, UID_4)));

        // Notifies that the running apps on the first virtual device has changed but with the same
        // set of UIDs.
        mVdms.notifyRunningAppsChanged(ASSOCIATION_ID_1, uidsOnDevice1);
        mLocalService.onAppsOnVirtualDeviceChanged();
        TestableLooper.get(this).processAllMessages();
        // Listeners should not be notified.
        verifyNoMoreInteractions(mAppsOnVirtualDeviceListener);
    }

    @Test
    public void onVirtualDisplayCreatedLocked_wakeLockIsAcquired() throws RemoteException {
        verify(mIPowerManagerMock, never()).acquireWakeLock(any(Binder.class), anyInt(),
                nullable(String.class), nullable(String.class), nullable(WorkSource.class),
                nullable(String.class), anyInt(), eq(null));
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        verify(mIPowerManagerMock).acquireWakeLock(any(Binder.class), anyInt(),
                nullable(String.class), nullable(String.class), nullable(WorkSource.class),
                nullable(String.class), eq(DISPLAY_ID), eq(null));
    }

    @Test
    public void onVirtualDisplayCreatedLocked_duplicateCalls_onlyOneWakeLockIsAcquired()
            throws RemoteException {
        GenericWindowPolicyController gwpc = mDeviceImpl.createWindowPolicyController();
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        assertThrows(IllegalStateException.class,
                () -> mDeviceImpl.onVirtualDisplayCreatedLocked(gwpc, DISPLAY_ID));
        TestableLooper.get(this).processAllMessages();
        verify(mIPowerManagerMock).acquireWakeLock(any(Binder.class), anyInt(),
                nullable(String.class), nullable(String.class), nullable(WorkSource.class),
                nullable(String.class), eq(DISPLAY_ID), eq(null));
    }

    @Test
    public void onVirtualDisplayRemovedLocked_unknownDisplayId_throwsException() {
        final int unknownDisplayId = 999;
        assertThrows(IllegalStateException.class,
                () -> mDeviceImpl.onVirtualDisplayRemovedLocked(unknownDisplayId));
    }

    @Test
    public void onVirtualDisplayRemovedLocked_wakeLockIsReleased() throws RemoteException {
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        ArgumentCaptor<IBinder> wakeLockCaptor = ArgumentCaptor.forClass(IBinder.class);
        TestableLooper.get(this).processAllMessages();
        verify(mIPowerManagerMock).acquireWakeLock(wakeLockCaptor.capture(),
                anyInt(),
                nullable(String.class), nullable(String.class), nullable(WorkSource.class),
                nullable(String.class), eq(DISPLAY_ID), eq(null));

        IBinder wakeLock = wakeLockCaptor.getValue();
        mDeviceImpl.onVirtualDisplayRemovedLocked(DISPLAY_ID);
        verify(mIPowerManagerMock).releaseWakeLock(eq(wakeLock), anyInt());
    }

    @Test
    public void addVirtualDisplay_displayNotReleased_wakeLockIsReleased() throws RemoteException {
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        ArgumentCaptor<IBinder> wakeLockCaptor = ArgumentCaptor.forClass(IBinder.class);
        TestableLooper.get(this).processAllMessages();
        verify(mIPowerManagerMock).acquireWakeLock(wakeLockCaptor.capture(),
                anyInt(),
                nullable(String.class), nullable(String.class), nullable(WorkSource.class),
                nullable(String.class), eq(DISPLAY_ID), eq(null));
        IBinder wakeLock = wakeLockCaptor.getValue();

        // Close the VirtualDevice without first notifying it of the VirtualDisplay removal.
        mDeviceImpl.close();
        verify(mIPowerManagerMock).releaseWakeLock(eq(wakeLock), anyInt());
    }

    @Test
    public void createVirtualKeyboard_noDisplay_failsSecurityException() {
        assertThrows(
                SecurityException.class,
                () -> mDeviceImpl.createVirtualKeyboard(DISPLAY_ID, DEVICE_NAME, VENDOR_ID,
                        PRODUCT_ID, BINDER));
    }

    @Test
    public void createVirtualMouse_noDisplay_failsSecurityException() {
        assertThrows(
                SecurityException.class,
                () -> mDeviceImpl.createVirtualMouse(DISPLAY_ID, DEVICE_NAME, VENDOR_ID,
                        PRODUCT_ID, BINDER));
    }

    @Test
    public void createVirtualTouchscreen_noDisplay_failsSecurityException() {
        assertThrows(
                SecurityException.class,
                () -> mDeviceImpl.createVirtualTouchscreen(DISPLAY_ID, DEVICE_NAME,
                        VENDOR_ID, PRODUCT_ID, BINDER, new Point(WIDTH, HEIGHT)));
    }

    @Test
    public void onAudioSessionStarting_noDisplay_failsSecurityException() {
        assertThrows(SecurityException.class,
                () -> mDeviceImpl.onAudioSessionStarting(
                        DISPLAY_ID, mRoutingCallback, mConfigChangedCallback));
    }

    @Test
    public void createVirtualKeyboard_noPermission_failsSecurityException() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        doCallRealMethod().when(mContext).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CREATE_VIRTUAL_DEVICE), anyString());
        assertThrows(
                SecurityException.class,
                () -> mDeviceImpl.createVirtualKeyboard(DISPLAY_ID, DEVICE_NAME, VENDOR_ID,
                        PRODUCT_ID, BINDER));
    }

    @Test
    public void createVirtualMouse_noPermission_failsSecurityException() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        doCallRealMethod().when(mContext).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CREATE_VIRTUAL_DEVICE), anyString());
        assertThrows(
                SecurityException.class,
                () -> mDeviceImpl.createVirtualMouse(DISPLAY_ID, DEVICE_NAME, VENDOR_ID,
                        PRODUCT_ID, BINDER));
    }

    @Test
    public void createVirtualTouchscreen_noPermission_failsSecurityException() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        doCallRealMethod().when(mContext).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CREATE_VIRTUAL_DEVICE), anyString());
        assertThrows(
                SecurityException.class,
                () -> mDeviceImpl.createVirtualTouchscreen(DISPLAY_ID, DEVICE_NAME,
                        VENDOR_ID, PRODUCT_ID, BINDER, new Point(WIDTH, HEIGHT)));
    }

    @Test
    public void onAudioSessionStarting_noPermission_failsSecurityException() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        doCallRealMethod().when(mContext).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CREATE_VIRTUAL_DEVICE), anyString());
        assertThrows(SecurityException.class,
                () -> mDeviceImpl.onAudioSessionStarting(
                        DISPLAY_ID, mRoutingCallback, mConfigChangedCallback));
    }

    @Test
    public void onAudioSessionEnded_noPermission_failsSecurityException() {
        doCallRealMethod().when(mContext).enforceCallingOrSelfPermission(
                eq(Manifest.permission.CREATE_VIRTUAL_DEVICE), anyString());
        assertThrows(SecurityException.class, () -> mDeviceImpl.onAudioSessionEnded());
    }

    @Test
    public void createVirtualKeyboard_hasDisplay_obtainFileDescriptor() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        mDeviceImpl.createVirtualKeyboard(DISPLAY_ID, DEVICE_NAME, VENDOR_ID, PRODUCT_ID,
                BINDER);
        assertWithMessage("Virtual keyboard should register fd when the display matches")
                .that(mInputController.mInputDeviceDescriptors).isNotEmpty();
        verify(mNativeWrapperMock).openUinputKeyboard(eq(DEVICE_NAME), eq(VENDOR_ID),
                eq(PRODUCT_ID), anyString());
    }

    @Test
    public void createVirtualMouse_hasDisplay_obtainFileDescriptor() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        mDeviceImpl.createVirtualMouse(DISPLAY_ID, DEVICE_NAME, VENDOR_ID, PRODUCT_ID,
                BINDER);
        assertWithMessage("Virtual keyboard should register fd when the display matches")
                .that(mInputController.mInputDeviceDescriptors).isNotEmpty();
        verify(mNativeWrapperMock).openUinputMouse(eq(DEVICE_NAME), eq(VENDOR_ID), eq(PRODUCT_ID),
                anyString());
    }

    @Test
    public void createVirtualTouchscreen_hasDisplay_obtainFileDescriptor() {
        mDeviceImpl.mVirtualDisplayIds.add(DISPLAY_ID);
        mDeviceImpl.createVirtualTouchscreen(DISPLAY_ID, DEVICE_NAME, VENDOR_ID, PRODUCT_ID,
                BINDER, new Point(WIDTH, HEIGHT));
        assertWithMessage("Virtual keyboard should register fd when the display matches")
                .that(mInputController.mInputDeviceDescriptors).isNotEmpty();
        verify(mNativeWrapperMock).openUinputTouchscreen(eq(DEVICE_NAME), eq(VENDOR_ID),
                eq(PRODUCT_ID), anyString(), eq(HEIGHT), eq(WIDTH));
    }

    @Test
    public void onAudioSessionStarting_hasVirtualAudioController() {
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);

        mDeviceImpl.onAudioSessionStarting(DISPLAY_ID, mRoutingCallback, mConfigChangedCallback);

        assertThat(mDeviceImpl.getVirtualAudioControllerForTesting()).isNotNull();
    }

    @Test
    public void onAudioSessionEnded_noVirtualAudioController() {
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        mDeviceImpl.onAudioSessionStarting(DISPLAY_ID, mRoutingCallback, mConfigChangedCallback);

        mDeviceImpl.onAudioSessionEnded();

        assertThat(mDeviceImpl.getVirtualAudioControllerForTesting()).isNull();
    }

    @Test
    public void close_cleanVirtualAudioController() {
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        mDeviceImpl.onAudioSessionStarting(DISPLAY_ID, mRoutingCallback, mConfigChangedCallback);

        mDeviceImpl.close();

        assertThat(mDeviceImpl.getVirtualAudioControllerForTesting()).isNull();
    }

    @Test
    public void sendKeyEvent_noFd() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mDeviceImpl.sendKeyEvent(BINDER, new VirtualKeyEvent.Builder()
                                .setKeyCode(KeyEvent.KEYCODE_A)
                                .setAction(VirtualKeyEvent.ACTION_DOWN).build()));
    }

    @Test
    public void sendKeyEvent_hasFd_writesEvent() {
        final int fd = 1;
        final int keyCode = KeyEvent.KEYCODE_A;
        final int action = VirtualKeyEvent.ACTION_UP;
        mInputController.mInputDeviceDescriptors.put(BINDER,
                new InputController.InputDeviceDescriptor(fd, () -> {}, /* type= */ 1,
                        /* displayId= */ 1, PHYS));
        mDeviceImpl.sendKeyEvent(BINDER, new VirtualKeyEvent.Builder().setKeyCode(keyCode)
                .setAction(action).build());
        verify(mNativeWrapperMock).writeKeyEvent(fd, keyCode, action);
    }

    @Test
    public void sendButtonEvent_noFd() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mDeviceImpl.sendButtonEvent(BINDER,
                                new VirtualMouseButtonEvent.Builder()
                                        .setButtonCode(VirtualMouseButtonEvent.BUTTON_BACK)
                                        .setAction(VirtualMouseButtonEvent.ACTION_BUTTON_PRESS)
                                        .build()));
    }

    @Test
    public void sendButtonEvent_hasFd_writesEvent() {
        final int fd = 1;
        final int buttonCode = VirtualMouseButtonEvent.BUTTON_BACK;
        final int action = VirtualMouseButtonEvent.ACTION_BUTTON_PRESS;
        mInputController.mInputDeviceDescriptors.put(BINDER,
                new InputController.InputDeviceDescriptor(fd, () -> {}, /* type= */ 2,
                        /* displayId= */ 1, PHYS));
        doReturn(1).when(mInputManagerInternalMock).getVirtualMousePointerDisplayId();
        mDeviceImpl.sendButtonEvent(BINDER, new VirtualMouseButtonEvent.Builder()
                .setButtonCode(buttonCode)
                .setAction(action).build());
        verify(mNativeWrapperMock).writeButtonEvent(fd, buttonCode, action);
    }

    @Test
    public void sendButtonEvent_hasFd_wrongDisplay_throwsIllegalStateException() {
        final int fd = 1;
        final int buttonCode = VirtualMouseButtonEvent.BUTTON_BACK;
        final int action = VirtualMouseButtonEvent.ACTION_BUTTON_PRESS;
        mInputController.mInputDeviceDescriptors.put(BINDER,
                new InputController.InputDeviceDescriptor(fd, () -> {}, /* type= */ 2,
                        /* displayId= */ 1, PHYS));
        assertThrows(
                IllegalStateException.class,
                () ->
                        mDeviceImpl.sendButtonEvent(BINDER, new VirtualMouseButtonEvent.Builder()
                                .setButtonCode(buttonCode)
                                .setAction(action).build()));
    }

    @Test
    public void sendRelativeEvent_noFd() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mDeviceImpl.sendRelativeEvent(BINDER,
                                new VirtualMouseRelativeEvent.Builder().setRelativeX(
                                        0.0f).setRelativeY(0.0f).build()));
    }

    @Test
    public void sendRelativeEvent_hasFd_writesEvent() {
        final int fd = 1;
        final float x = -0.2f;
        final float y = 0.7f;
        mInputController.mInputDeviceDescriptors.put(BINDER,
                new InputController.InputDeviceDescriptor(fd, () -> {}, /* type= */ 2,
                        /* displayId= */ 1, PHYS));
        doReturn(1).when(mInputManagerInternalMock).getVirtualMousePointerDisplayId();
        mDeviceImpl.sendRelativeEvent(BINDER, new VirtualMouseRelativeEvent.Builder()
                .setRelativeX(x).setRelativeY(y).build());
        verify(mNativeWrapperMock).writeRelativeEvent(fd, x, y);
    }

    @Test
    public void sendRelativeEvent_hasFd_wrongDisplay_throwsIllegalStateException() {
        final int fd = 1;
        final float x = -0.2f;
        final float y = 0.7f;
        mInputController.mInputDeviceDescriptors.put(BINDER,
                new InputController.InputDeviceDescriptor(fd, () -> {}, /* type= */ 2,
                        /* displayId= */ 1, PHYS));
        assertThrows(
                IllegalStateException.class,
                () ->
                        mDeviceImpl.sendRelativeEvent(BINDER,
                                new VirtualMouseRelativeEvent.Builder()
                                        .setRelativeX(x).setRelativeY(y).build()));
    }

    @Test
    public void sendScrollEvent_noFd() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mDeviceImpl.sendScrollEvent(BINDER,
                                new VirtualMouseScrollEvent.Builder()
                                        .setXAxisMovement(-1f)
                                        .setYAxisMovement(1f).build()));
    }

    @Test
    public void sendScrollEvent_hasFd_writesEvent() {
        final int fd = 1;
        final float x = 0.5f;
        final float y = 1f;
        mInputController.mInputDeviceDescriptors.put(BINDER,
                new InputController.InputDeviceDescriptor(fd, () -> {}, /* type= */ 2,
                        /* displayId= */ 1, PHYS));
        doReturn(1).when(mInputManagerInternalMock).getVirtualMousePointerDisplayId();
        mDeviceImpl.sendScrollEvent(BINDER, new VirtualMouseScrollEvent.Builder()
                .setXAxisMovement(x)
                .setYAxisMovement(y).build());
        verify(mNativeWrapperMock).writeScrollEvent(fd, x, y);
    }

    @Test
    public void sendScrollEvent_hasFd_wrongDisplay_throwsIllegalStateException() {
        final int fd = 1;
        final float x = 0.5f;
        final float y = 1f;
        mInputController.mInputDeviceDescriptors.put(BINDER,
                new InputController.InputDeviceDescriptor(fd, () -> {}, /* type= */ 2,
                        /* displayId= */ 1, PHYS));
        assertThrows(
                IllegalStateException.class,
                () ->
                        mDeviceImpl.sendScrollEvent(BINDER, new VirtualMouseScrollEvent.Builder()
                                .setXAxisMovement(x)
                                .setYAxisMovement(y).build()));
    }

    @Test
    public void sendTouchEvent_noFd() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mDeviceImpl.sendTouchEvent(BINDER, new VirtualTouchEvent.Builder()
                                .setX(0.0f)
                                .setY(0.0f)
                                .setAction(VirtualTouchEvent.ACTION_UP)
                                .setPointerId(1)
                                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                                .build()));
    }

    @Test
    public void sendTouchEvent_hasFd_writesEvent_withoutPressureOrMajorAxisSize() {
        final int fd = 1;
        final int pointerId = 5;
        final int toolType = VirtualTouchEvent.TOOL_TYPE_FINGER;
        final float x = 100.5f;
        final float y = 200.5f;
        final int action = VirtualTouchEvent.ACTION_UP;
        mInputController.mInputDeviceDescriptors.put(BINDER,
                new InputController.InputDeviceDescriptor(fd, () -> {}, /* type= */ 3,
                        /* displayId= */ 1, PHYS));
        mDeviceImpl.sendTouchEvent(BINDER, new VirtualTouchEvent.Builder().setX(x)
                .setY(y).setAction(action).setPointerId(pointerId).setToolType(toolType).build());
        verify(mNativeWrapperMock).writeTouchEvent(fd, pointerId, toolType, action, x, y, Float.NaN,
                Float.NaN);
    }

    @Test
    public void sendTouchEvent_hasFd_writesEvent() {
        final int fd = 1;
        final int pointerId = 5;
        final int toolType = VirtualTouchEvent.TOOL_TYPE_FINGER;
        final float x = 100.5f;
        final float y = 200.5f;
        final int action = VirtualTouchEvent.ACTION_UP;
        final float pressure = 1.0f;
        final float majorAxisSize = 10.0f;
        mInputController.mInputDeviceDescriptors.put(BINDER,
                new InputController.InputDeviceDescriptor(fd, () -> {}, /* type= */ 3,
                        /* displayId= */ 1, PHYS));
        mDeviceImpl.sendTouchEvent(BINDER, new VirtualTouchEvent.Builder().setX(x)
                .setY(y).setAction(action).setPointerId(pointerId).setToolType(toolType)
                .setPressure(pressure).setMajorAxisSize(majorAxisSize).build());
        verify(mNativeWrapperMock).writeTouchEvent(fd, pointerId, toolType, action, x, y, pressure,
                majorAxisSize);
    }

    @Test
    public void setShowPointerIcon_setsValueForAllDisplays() {
        mDeviceImpl.mVirtualDisplayIds.add(1);
        mDeviceImpl.mVirtualDisplayIds.add(2);
        mDeviceImpl.mVirtualDisplayIds.add(3);
        mDeviceImpl.createVirtualMouse(1, DEVICE_NAME, VENDOR_ID, PRODUCT_ID, BINDER);
        mDeviceImpl.createVirtualMouse(2, DEVICE_NAME, VENDOR_ID, PRODUCT_ID, BINDER);
        mDeviceImpl.createVirtualMouse(3, DEVICE_NAME, VENDOR_ID, PRODUCT_ID, BINDER);
        mDeviceImpl.setShowPointerIcon(false);
        verify(mInputManagerInternalMock, times(3)).setPointerIconVisible(eq(false), anyInt());
        verify(mInputManagerInternalMock, never()).setPointerIconVisible(eq(true), anyInt());
        mDeviceImpl.setShowPointerIcon(true);
        verify(mInputManagerInternalMock, times(3)).setPointerIconVisible(eq(true), anyInt());
    }

    @Test
    public void openNonBlockedAppOnVirtualDisplay_doesNotStartBlockedAlertActivity() {
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        GenericWindowPolicyController gwpc = mDeviceImpl.getWindowPolicyControllersForTesting().get(
                DISPLAY_ID);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ArrayList<ActivityInfo> activityInfos = getActivityInfoList(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME, /* displayOnRemoveDevices */ true);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfos.get(0), mAssociationInfo.getDisplayName());
        gwpc.canContainActivities(activityInfos, WindowConfiguration.WINDOWING_MODE_FULLSCREEN);

        verify(mContext, never()).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openPermissionControllerOnVirtualDisplay_startBlockedAlertActivity() {
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        GenericWindowPolicyController gwpc = mDeviceImpl.getWindowPolicyControllersForTesting().get(
                DISPLAY_ID);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ArrayList<ActivityInfo> activityInfos = getActivityInfoList(
                PERMISSION_CONTROLLER_PACKAGE_NAME,
                PERMISSION_CONTROLLER_PACKAGE_NAME, /* displayOnRemoveDevices */  false);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfos.get(0), mAssociationInfo.getDisplayName());
        gwpc.canContainActivities(activityInfos, WindowConfiguration.WINDOWING_MODE_FULLSCREEN);

        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openSettingsOnVirtualDisplay_startBlockedAlertActivity() {
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        GenericWindowPolicyController gwpc = mDeviceImpl.getWindowPolicyControllersForTesting().get(
                DISPLAY_ID);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ArrayList<ActivityInfo> activityInfos = getActivityInfoList(
                SETTINGS_PACKAGE_NAME,
                SETTINGS_PACKAGE_NAME, /* displayOnRemoveDevices */  true);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfos.get(0), mAssociationInfo.getDisplayName());
        gwpc.canContainActivities(activityInfos, WindowConfiguration.WINDOWING_MODE_FULLSCREEN);

        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openVendingOnVirtualDisplay_startBlockedAlertActivity() {
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        GenericWindowPolicyController gwpc = mDeviceImpl.getWindowPolicyControllersForTesting().get(
                DISPLAY_ID);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ArrayList<ActivityInfo> activityInfos = getActivityInfoList(
                VENDING_PACKAGE_NAME,
                VENDING_PACKAGE_NAME, /* displayOnRemoveDevices */  true);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfos.get(0), mAssociationInfo.getDisplayName());
        gwpc.canContainActivities(activityInfos, WindowConfiguration.WINDOWING_MODE_FULLSCREEN);

        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openGoogleDialerOnVirtualDisplay_startBlockedAlertActivity() {
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        GenericWindowPolicyController gwpc = mDeviceImpl.getWindowPolicyControllersForTesting().get(
                DISPLAY_ID);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ArrayList<ActivityInfo> activityInfos = getActivityInfoList(
                GOOGLE_DIALER_PACKAGE_NAME,
                GOOGLE_DIALER_PACKAGE_NAME, /* displayOnRemoveDevices */  true);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfos.get(0), mAssociationInfo.getDisplayName());
        gwpc.canContainActivities(activityInfos, WindowConfiguration.WINDOWING_MODE_FULLSCREEN);

        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openGoogleMapsOnVirtualDisplay_startBlockedAlertActivity() {
        mDeviceImpl.onVirtualDisplayCreatedLocked(
                mDeviceImpl.createWindowPolicyController(), DISPLAY_ID);
        GenericWindowPolicyController gwpc = mDeviceImpl.getWindowPolicyControllersForTesting().get(
                DISPLAY_ID);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ArrayList<ActivityInfo> activityInfos = getActivityInfoList(
                GOOGLE_MAPS_PACKAGE_NAME,
                GOOGLE_MAPS_PACKAGE_NAME, /* displayOnRemoveDevices */  true);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfos.get(0), mAssociationInfo.getDisplayName());
        gwpc.canContainActivities(activityInfos, WindowConfiguration.WINDOWING_MODE_FULLSCREEN);

        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }
}
