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

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_RECENTS;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_SENSORS;
import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.content.Context.DEVICE_ID_INVALID;
import static android.content.Intent.ACTION_VIEW;
import static android.content.pm.ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES;
import static android.content.pm.PackageManager.ACTION_REQUEST_PERMISSIONS;

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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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
import android.companion.virtual.IVirtualDeviceIntentInterceptor;
import android.companion.virtual.IVirtualDeviceSoundEffectListener;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.audio.IAudioConfigChangedCallback;
import android.companion.virtual.audio.IAudioRoutingCallback;
import android.companion.virtual.flags.Flags;
import android.companion.virtual.sensor.VirtualSensor;
import android.companion.virtual.sensor.VirtualSensorCallback;
import android.companion.virtual.sensor.VirtualSensorConfig;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.IDisplayManager;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.input.IInputManager;
import android.hardware.input.VirtualDpadConfig;
import android.hardware.input.VirtualKeyEvent;
import android.hardware.input.VirtualKeyboardConfig;
import android.hardware.input.VirtualMouseButtonEvent;
import android.hardware.input.VirtualMouseConfig;
import android.hardware.input.VirtualMouseRelativeEvent;
import android.hardware.input.VirtualMouseScrollEvent;
import android.hardware.input.VirtualNavigationTouchpadConfig;
import android.hardware.input.VirtualTouchEvent;
import android.hardware.input.VirtualTouchscreenConfig;
import android.media.AudioManager;
import android.net.MacAddress;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.LocaleList;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.WorkSource;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArraySet;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.KeyEvent;
import android.view.WindowManager;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;
import com.android.internal.app.BlockedAppStreamingActivity;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.companion.virtual.camera.VirtualCameraController;
import com.android.server.input.InputManagerInternal;
import com.android.server.sensors.SensorManagerInternal;

import com.google.android.collect.Sets;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Set;
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
    private static final String DEVICE_NAME_1 = "device name 1";
    private static final String DEVICE_NAME_2 = "device name 2";
    private static final String DEVICE_NAME_3 = "device name 3";
    private static final int DISPLAY_ID_1 = 2;
    private static final int DISPLAY_ID_2 = 3;
    private static final int NON_EXISTENT_DISPLAY_ID = 42;
    private static final int DEVICE_OWNER_UID_1 = 50;
    private static final int DEVICE_OWNER_UID_2 = 51;
    private static final int UID_1 = 0;
    private static final int UID_2 = 10;
    private static final int UID_3 = 10000;
    private static final int UID_4 = 10001;
    private static final int PRODUCT_ID = 10;
    private static final int VENDOR_ID = 5;
    private static final String UNIQUE_ID = "uniqueid";
    private static final String PHYS = "phys";
    private static final int INPUT_DEVICE_ID = 53;
    private static final int HEIGHT = 1800;
    private static final int WIDTH = 900;
    private static final int SENSOR_HANDLE = 64;
    private static final Binder BINDER = new Binder("binder");
    private static final int FLAG_CANNOT_DISPLAY_ON_REMOTE_DEVICES = 0x00000;
    private static final int VIRTUAL_DEVICE_ID_1 = 42;
    private static final int VIRTUAL_DEVICE_ID_2 = 43;
    private static final VirtualDisplayConfig VIRTUAL_DISPLAY_CONFIG =
            new VirtualDisplayConfig.Builder("virtual_display", 640, 480, 400).build();
    private static final VirtualDpadConfig DPAD_CONFIG =
            new VirtualDpadConfig.Builder()
                    .setVendorId(VENDOR_ID)
                    .setProductId(PRODUCT_ID)
                    .setInputDeviceName(DEVICE_NAME_1)
                    .setAssociatedDisplayId(DISPLAY_ID_1)
                    .build();
    private static final VirtualKeyboardConfig KEYBOARD_CONFIG =
            new VirtualKeyboardConfig.Builder()
                    .setVendorId(VENDOR_ID)
                    .setProductId(PRODUCT_ID)
                    .setInputDeviceName(DEVICE_NAME_1)
                    .setAssociatedDisplayId(DISPLAY_ID_1)
                    .setLanguageTag(VirtualKeyboardConfig.DEFAULT_LANGUAGE_TAG)
                    .setLayoutType(VirtualKeyboardConfig.DEFAULT_LAYOUT_TYPE)
                    .build();
    private static final VirtualMouseConfig MOUSE_CONFIG =
            new VirtualMouseConfig.Builder()
                    .setVendorId(VENDOR_ID)
                    .setProductId(PRODUCT_ID)
                    .setInputDeviceName(DEVICE_NAME_1)
                    .setAssociatedDisplayId(DISPLAY_ID_1)
                    .build();
    private static final VirtualTouchscreenConfig TOUCHSCREEN_CONFIG =
            new VirtualTouchscreenConfig.Builder(WIDTH, HEIGHT)
                    .setVendorId(VENDOR_ID)
                    .setProductId(PRODUCT_ID)
                    .setInputDeviceName(DEVICE_NAME_1)
                    .setAssociatedDisplayId(DISPLAY_ID_1)
                    .build();
    private static final VirtualNavigationTouchpadConfig NAVIGATION_TOUCHPAD_CONFIG =
            new VirtualNavigationTouchpadConfig.Builder(WIDTH, HEIGHT)
                    .setVendorId(VENDOR_ID)
                    .setProductId(PRODUCT_ID)
                    .setInputDeviceName(DEVICE_NAME_1)
                    .setAssociatedDisplayId(DISPLAY_ID_1)
                    .build();
    private static final String TEST_SITE = "http://test";

    @Rule
    public SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule = new AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            Manifest.permission.CREATE_VIRTUAL_DEVICE);

    private Context mContext;
    private InputManagerMockHelper mInputManagerMockHelper;
    private VirtualDeviceImpl mDeviceImpl;
    private InputController mInputController;
    private SensorController mSensorController;
    private CameraAccessController mCameraAccessController;
    private AssociationInfo mAssociationInfo;
    private VirtualDeviceManagerService mVdms;
    private VirtualDeviceManagerInternal mLocalService;
    private VirtualDeviceManagerService.VirtualDeviceManagerImpl mVdm;
    private VirtualDeviceManagerService.VirtualDeviceManagerNativeImpl mVdmNative;
    private VirtualDeviceLog mVirtualDeviceLog;
    @Mock
    private InputController.NativeWrapper mNativeWrapperMock;
    @Mock
    private DisplayManagerInternal mDisplayManagerInternalMock;
    @Mock
    private IDisplayManager mIDisplayManager;
    @Mock
    private VirtualDeviceImpl.PendingTrampolineCallback mPendingTrampolineCallback;
    @Mock
    private DevicePolicyManager mDevicePolicyManagerMock;
    @Mock
    private InputManagerInternal mInputManagerInternalMock;
    @Mock
    private SensorManagerInternal mSensorManagerInternalMock;
    @Mock
    private VirtualSensorCallback mSensorCallback;
    @Mock
    private IVirtualDeviceActivityListener mActivityListener;
    @Mock
    private IVirtualDeviceSoundEffectListener mSoundEffectListener;
    @Mock
    private IVirtualDisplayCallback mVirtualDisplayCallback;
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
    @Mock
    private IAudioRoutingCallback mRoutingCallback;
    @Mock
    private IAudioConfigChangedCallback mConfigChangedCallback;
    @Mock
    private CameraAccessController.CameraAccessBlockedCallback mCameraAccessBlockedCallback;
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

    private Intent createRestrictedActivityBlockedIntent(Set<String> displayCategories,
            String targetDisplayCategory) {
        when(mDisplayManagerInternalMock.createVirtualDisplay(any(), any(), any(), any(),
                eq(NONBLOCKED_APP_PACKAGE_NAME))).thenReturn(DISPLAY_ID_1);
        VirtualDisplayConfig config = new VirtualDisplayConfig.Builder("display", 640, 480,
                420).setDisplayCategories(displayCategories).build();
        mDeviceImpl.createVirtualDisplay(config, mVirtualDisplayCallback,
                NONBLOCKED_APP_PACKAGE_NAME);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices= */ true,
                targetDisplayCategory);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        gwpc.canActivityBeLaunched(activityInfo, blockedAppIntent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false);
        return blockedAppIntent;
    }


    private ActivityInfo getActivityInfo(
            String packageName, String name, boolean displayOnRemoteDevices,
            String requiredDisplayCategory) {
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = packageName;
        activityInfo.name = name;
        activityInfo.flags = displayOnRemoteDevices
                ? FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES : FLAG_CANNOT_DISPLAY_ON_REMOTE_DEVICES;
        activityInfo.applicationInfo = mApplicationInfoMock;
        activityInfo.requiredDisplayCategory = requiredDisplayCategory;
        return activityInfo;
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternalMock);

        mSetFlagsRule.initAllFlagsToReleaseConfigDefault();

        doReturn(true).when(mInputManagerInternalMock).setVirtualMousePointerDisplayId(anyInt());
        doNothing().when(mInputManagerInternalMock).setPointerAcceleration(anyFloat(), anyInt());
        doNothing().when(mInputManagerInternalMock).setPointerIconVisible(anyBoolean(), anyInt());
        LocalServices.removeServiceForTest(InputManagerInternal.class);
        LocalServices.addService(InputManagerInternal.class, mInputManagerInternalMock);

        LocalServices.removeServiceForTest(SensorManagerInternal.class);
        LocalServices.addService(SensorManagerInternal.class, mSensorManagerInternalMock);

        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.uniqueId = UNIQUE_ID;
        doReturn(displayInfo).when(mDisplayManagerInternalMock).getDisplayInfo(anyInt());
        doReturn(Display.INVALID_DISPLAY).when(mDisplayManagerInternalMock)
                .getDisplayIdToMirror(anyInt());
        LocalServices.removeServiceForTest(DisplayManagerInternal.class);
        LocalServices.addService(DisplayManagerInternal.class, mDisplayManagerInternalMock);

        mContext = Mockito.spy(new ContextWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext()));
        doReturn(mContext).when(mContext).createContextAsUser(eq(Process.myUserHandle()), anyInt());
        doNothing().when(mContext).sendBroadcastAsUser(any(), any());
        when(mContext.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(
                mDevicePolicyManagerMock);

        PowerManager powerManager = new PowerManager(mContext, mIPowerManagerMock,
                mIThermalServiceMock,
                new Handler(TestableLooper.get(this).getLooper()));
        when(mContext.getSystemService(Context.POWER_SERVICE)).thenReturn(powerManager);

        mInputManagerMockHelper = new InputManagerMockHelper(
                TestableLooper.get(this), mNativeWrapperMock, mIInputManagerMock);
        // Allow virtual devices to be created on the looper thread for testing.
        final InputController.DeviceCreationThreadVerifier threadVerifier = () -> true;
        mInputController = new InputController(mNativeWrapperMock,
                new Handler(TestableLooper.get(this).getLooper()),
                mContext.getSystemService(WindowManager.class), threadVerifier);
        mCameraAccessController =
                new CameraAccessController(mContext, mLocalService, mCameraAccessBlockedCallback);

        mAssociationInfo = new AssociationInfo(/* associationId= */ 1, 0, null,
                null, MacAddress.BROADCAST_ADDRESS, "", null, null, true, false, false,
                0, 0, -1);

        mVdms = new VirtualDeviceManagerService(mContext);
        mLocalService = mVdms.getLocalServiceInstance();
        mVdm = mVdms.new VirtualDeviceManagerImpl();
        mVdmNative = mVdms.new VirtualDeviceManagerNativeImpl();
        mVirtualDeviceLog = new VirtualDeviceLog(mContext);
        mDeviceImpl = createVirtualDevice(VIRTUAL_DEVICE_ID_1, DEVICE_OWNER_UID_1);
        mSensorController = mDeviceImpl.getSensorControllerForTest();
    }

    @After
    public void tearDown() {
        mDeviceImpl.close();
        mInputManagerMockHelper.tearDown();
    }

    @Test
    public void getDeviceIdForDisplayId_invalidDisplayId_returnsDefault() {
        assertThat(mVdm.getDeviceIdForDisplayId(Display.INVALID_DISPLAY))
                .isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    public void getDeviceIdForDisplayId_defaultDisplayId_returnsDefault() {
        assertThat(mVdm.getDeviceIdForDisplayId(Display.DEFAULT_DISPLAY))
                .isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    public void getDeviceIdForDisplayId_nonExistentDisplayId_returnsDefault() {
        assertThat(mVdm.getDeviceIdForDisplayId(NON_EXISTENT_DISPLAY_ID))
                .isEqualTo(DEVICE_ID_DEFAULT);
    }

    @Test
    public void getDeviceIdForDisplayId_withValidVirtualDisplayId_returnsDeviceId() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);

        assertThat(mVdm.getDeviceIdForDisplayId(DISPLAY_ID_1))
                .isEqualTo(mDeviceImpl.getDeviceId());
    }

    @Test
    public void isDeviceIdValid_defaultDeviceId_returnsFalse() {
        assertThat(mVdm.isValidVirtualDeviceId(DEVICE_ID_DEFAULT)).isFalse();
    }

    @Test
    public void isDeviceIdValid_validVirtualDeviceId_returnsTrue() {
        assertThat(mVdm.isValidVirtualDeviceId(mDeviceImpl.getDeviceId())).isTrue();
    }

    @Test
    public void isDeviceIdValid_nonExistentDeviceId_returnsFalse() {
        assertThat(mVdm.isValidVirtualDeviceId(mDeviceImpl.getDeviceId() + 1)).isFalse();
    }

    @Test
    public void getDevicePolicy_invalidDeviceId_returnsDefault() {
        assertThat(mVdm.getDevicePolicy(DEVICE_ID_INVALID, POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_DEFAULT);
        assertThat(mVdmNative.getDevicePolicy(DEVICE_ID_INVALID, POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_DEFAULT);
    }

    @Test
    public void getDevicePolicy_defaultDeviceId_returnsDefault() {
        assertThat(mVdm.getDevicePolicy(DEVICE_ID_DEFAULT, POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_DEFAULT);
        assertThat(mVdmNative.getDevicePolicy(DEVICE_ID_DEFAULT, POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_DEFAULT);
    }

    @Test
    public void getDevicePolicy_nonExistentDeviceId_returnsDefault() {
        assertThat(mVdm.getDevicePolicy(mDeviceImpl.getDeviceId() + 1, POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_DEFAULT);
        assertThat(mVdmNative.getDevicePolicy(mDeviceImpl.getDeviceId() + 1, POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_DEFAULT);
    }

    @Test
    public void getDevicePolicy_unspecifiedPolicy_returnsDefault() {
        assertThat(mVdm.getDevicePolicy(mDeviceImpl.getDeviceId(), POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_DEFAULT);
        assertThat(mVdmNative.getDevicePolicy(mDeviceImpl.getDeviceId(), POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_DEFAULT);
    }

    @Test
    public void getDevicePolicy_returnsCustom() {
        VirtualDeviceParams params = new VirtualDeviceParams
                .Builder()
                .setBlockedActivities(getBlockedActivities())
                .setDevicePolicy(POLICY_TYPE_SENSORS, DEVICE_POLICY_CUSTOM)
                .build();
        mDeviceImpl.close();
        mDeviceImpl = createVirtualDevice(VIRTUAL_DEVICE_ID_1, DEVICE_OWNER_UID_1, params);

        assertThat(mVdm.getDevicePolicy(mDeviceImpl.getDeviceId(), POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_CUSTOM);
        assertThat(mVdmNative.getDevicePolicy(mDeviceImpl.getDeviceId(), POLICY_TYPE_SENSORS))
                .isEqualTo(DEVICE_POLICY_CUSTOM);
    }

    @Test
    public void getDevicePolicy_defaultRecentsPolicy_gwpcCanShowRecentsOnHostDevice() {
        VirtualDeviceParams params = new VirtualDeviceParams
                .Builder()
                .build();
        mDeviceImpl.close();
        mDeviceImpl = createVirtualDevice(VIRTUAL_DEVICE_ID_1, DEVICE_OWNER_UID_1, params);
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);

        GenericWindowPolicyController gwpc =
                mDeviceImpl.getDisplayWindowPolicyControllerForTest(DISPLAY_ID_1);
        assertThat(gwpc.canShowTasksInHostDeviceRecents()).isTrue();
    }

    @Test
    public void getDevicePolicy_customRecentsPolicy_gwpcCannotShowRecentsOnHostDevice() {
        VirtualDeviceParams params = new VirtualDeviceParams
                .Builder()
                .setDevicePolicy(POLICY_TYPE_RECENTS, DEVICE_POLICY_CUSTOM)
                .build();
        mDeviceImpl.close();
        mDeviceImpl = createVirtualDevice(VIRTUAL_DEVICE_ID_1, DEVICE_OWNER_UID_1, params);
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);

        GenericWindowPolicyController gwpc =
                mDeviceImpl.getDisplayWindowPolicyControllerForTest(DISPLAY_ID_1);
        assertThat(gwpc.canShowTasksInHostDeviceRecents()).isFalse();
    }

    @Test
    public void getDeviceOwnerUid_oneDevice_returnsCorrectId() {
        int ownerUid = mLocalService.getDeviceOwnerUid(mDeviceImpl.getDeviceId());
        assertThat(ownerUid).isEqualTo(mDeviceImpl.getOwnerUid());
    }

    @Test
    public void getDeviceOwnerUid_twoDevices_returnsCorrectId() {
        createVirtualDevice(VIRTUAL_DEVICE_ID_2, DEVICE_OWNER_UID_2);

        int secondDeviceOwner = mLocalService.getDeviceOwnerUid(VIRTUAL_DEVICE_ID_2);
        assertThat(secondDeviceOwner).isEqualTo(DEVICE_OWNER_UID_2);

        int firstDeviceOwner = mLocalService.getDeviceOwnerUid(VIRTUAL_DEVICE_ID_1);
        assertThat(firstDeviceOwner).isEqualTo(DEVICE_OWNER_UID_1);
    }

    @Test
    public void getDeviceOwnerUid_nonExistentDevice_returnsInvalidUid() {
        int nonExistentDeviceId = DEVICE_ID_DEFAULT;
        int ownerUid = mLocalService.getDeviceOwnerUid(nonExistentDeviceId);
        assertThat(ownerUid).isEqualTo(Process.INVALID_UID);
    }

    @Test
    public void getVirtualSensor_defaultDeviceId_returnsNull() {
        assertThat(mLocalService.getVirtualSensor(DEVICE_ID_DEFAULT, SENSOR_HANDLE)).isNull();
    }

    @Test
    public void getVirtualSensor_invalidDeviceId_returnsNull() {
        assertThat(mLocalService.getVirtualSensor(DEVICE_ID_INVALID, SENSOR_HANDLE)).isNull();
    }

    @Test
    public void getVirtualSensor_noSensors_returnsNull() {
        assertThat(mLocalService.getVirtualSensor(VIRTUAL_DEVICE_ID_1, SENSOR_HANDLE)).isNull();
    }

    @Test
    public void getVirtualSensor_returnsCorrectSensor() {
        VirtualDeviceParams params = new VirtualDeviceParams.Builder()
                .setDevicePolicy(POLICY_TYPE_SENSORS, DEVICE_POLICY_CUSTOM)
                .addVirtualSensorConfig(
                        new VirtualSensorConfig.Builder(Sensor.TYPE_ACCELEROMETER, DEVICE_NAME_1)
                                .build())
                .setVirtualSensorCallback(BackgroundThread.getExecutor(), mSensorCallback)
                .build();

        doReturn(SENSOR_HANDLE).when(mSensorManagerInternalMock).createRuntimeSensor(
                anyInt(), anyInt(), anyString(), anyString(), anyFloat(), anyFloat(), anyFloat(),
                anyInt(), anyInt(), anyInt(), any());
        mDeviceImpl.close();
        mDeviceImpl = createVirtualDevice(VIRTUAL_DEVICE_ID_1, DEVICE_OWNER_UID_1, params);

        VirtualSensor sensor = mLocalService.getVirtualSensor(VIRTUAL_DEVICE_ID_1, SENSOR_HANDLE);
        assertThat(sensor).isNotNull();
        assertThat(sensor.getDeviceId()).isEqualTo(VIRTUAL_DEVICE_ID_1);
        assertThat(sensor.getHandle()).isEqualTo(SENSOR_HANDLE);
        assertThat(sensor.getType()).isEqualTo(Sensor.TYPE_ACCELEROMETER);
    }

    @Test
    public void getDeviceIdsForUid_noRunningApps_returnsNull() {
        assertThat(mLocalService.getDeviceIdsForUid(UID_1)).isEmpty();
        assertThat(mVdmNative.getDeviceIdsForUid(UID_1)).isEmpty();
    }

    @Test
    public void getDeviceIdsForUid_differentUidOnDevice_returnsNull() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        mDeviceImpl.getDisplayWindowPolicyControllerForTest(DISPLAY_ID_1).onRunningAppsChanged(
                Sets.newArraySet(UID_2));

        assertThat(mLocalService.getDeviceIdsForUid(UID_1)).isEmpty();
        assertThat(mVdmNative.getDeviceIdsForUid(UID_1)).isEmpty();
    }

    @Test
    public void getDeviceIdsForUid_oneUidOnDevice_returnsCorrectId() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        mDeviceImpl.getDisplayWindowPolicyControllerForTest(DISPLAY_ID_1).onRunningAppsChanged(
                Sets.newArraySet(UID_1));

        int deviceId = mDeviceImpl.getDeviceId();
        assertThat(mLocalService.getDeviceIdsForUid(UID_1)).containsExactly(deviceId);
        assertThat(mVdmNative.getDeviceIdsForUid(UID_1)).asList().containsExactly(deviceId);
    }

    @Test
    public void getDeviceIdsForUid_twoUidsOnDevice_returnsCorrectId() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);

        mDeviceImpl.getDisplayWindowPolicyControllerForTest(DISPLAY_ID_1).onRunningAppsChanged(
                Sets.newArraySet(UID_1, UID_2));

        int deviceId = mDeviceImpl.getDeviceId();
        assertThat(mLocalService.getDeviceIdsForUid(UID_1)).containsExactly(deviceId);
        assertThat(mVdmNative.getDeviceIdsForUid(UID_1)).asList().containsExactly(deviceId);
    }

    @Test
    public void getDeviceIdsForUid_twoDevicesUidOnOne_returnsCorrectId() {
        VirtualDeviceImpl secondDevice = createVirtualDevice(VIRTUAL_DEVICE_ID_2,
                DEVICE_OWNER_UID_2);
        addVirtualDisplay(secondDevice, DISPLAY_ID_2);

        secondDevice.getDisplayWindowPolicyControllerForTest(DISPLAY_ID_2).onRunningAppsChanged(
                Sets.newArraySet(UID_1));

        int deviceId = secondDevice.getDeviceId();
        assertThat(mLocalService.getDeviceIdsForUid(UID_1)).containsExactly(deviceId);
        assertThat(mVdmNative.getDeviceIdsForUid(UID_1)).asList().containsExactly(deviceId);
    }

    @Test
    public void getDeviceIdsForUid_twoDevicesUidOnBoth_returnsCorrectId() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        VirtualDeviceImpl secondDevice = createVirtualDevice(VIRTUAL_DEVICE_ID_2,
                DEVICE_OWNER_UID_2);
        addVirtualDisplay(secondDevice, DISPLAY_ID_2);


        mDeviceImpl.getDisplayWindowPolicyControllerForTest(DISPLAY_ID_1).onRunningAppsChanged(
                Sets.newArraySet(UID_1));
        secondDevice.getDisplayWindowPolicyControllerForTest(DISPLAY_ID_2).onRunningAppsChanged(
                Sets.newArraySet(UID_1, UID_2));

        assertThat(mLocalService.getDeviceIdsForUid(UID_1)).containsExactly(
                mDeviceImpl.getDeviceId(), secondDevice.getDeviceId());
        assertThat(mVdmNative.getDeviceIdsForUid(UID_1)).asList().containsExactly(
                mDeviceImpl.getDeviceId(), secondDevice.getDeviceId());
    }

    @Test
    public void getPreferredLocaleListForApp_keyboardAttached_returnLocaleHints() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        mDeviceImpl.createVirtualKeyboard(KEYBOARD_CONFIG, BINDER);

        mVdms.notifyRunningAppsChanged(mDeviceImpl.getDeviceId(), Sets.newArraySet(UID_1));

        LocaleList localeList = mLocalService.getPreferredLocaleListForUid(UID_1);
        assertThat(localeList).isEqualTo(
                LocaleList.forLanguageTags(KEYBOARD_CONFIG.getLanguageTag()));
    }

    @Test
    public void getPreferredLocaleListForApp_noKeyboardAttached_nullLocaleHints() {
        mVdms.notifyRunningAppsChanged(mDeviceImpl.getDeviceId(), Sets.newArraySet(UID_1));

        // no preceding call to createVirtualKeyboard()
        assertThat(mLocalService.getPreferredLocaleListForUid(UID_1)).isNull();
    }

    @Test
    public void getPreferredLocaleListForApp_appOnMultipleVD_localeOnFirstVDReturned() {
        VirtualDeviceImpl secondDevice = createVirtualDevice(VIRTUAL_DEVICE_ID_2,
                DEVICE_OWNER_UID_2);
        Binder secondBinder = new Binder("secondBinder");
        VirtualKeyboardConfig firstKeyboardConfig =
                new VirtualKeyboardConfig.Builder()
                        .setVendorId(VENDOR_ID)
                        .setProductId(PRODUCT_ID)
                        .setInputDeviceName(DEVICE_NAME_1)
                        .setAssociatedDisplayId(DISPLAY_ID_1)
                        .setLanguageTag("zh-CN")
                        .build();
        VirtualKeyboardConfig secondKeyboardConfig =
                new VirtualKeyboardConfig.Builder()
                        .setVendorId(VENDOR_ID)
                        .setProductId(PRODUCT_ID)
                        .setInputDeviceName(DEVICE_NAME_2)
                        .setAssociatedDisplayId(DISPLAY_ID_2)
                        .setLanguageTag("fr-FR")
                        .build();

        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        addVirtualDisplay(secondDevice, DISPLAY_ID_2);

        mDeviceImpl.createVirtualKeyboard(firstKeyboardConfig, BINDER);
        secondDevice.createVirtualKeyboard(secondKeyboardConfig, secondBinder);

        mVdms.notifyRunningAppsChanged(mDeviceImpl.getDeviceId(), Sets.newArraySet(UID_1));
        mVdms.notifyRunningAppsChanged(secondDevice.getDeviceId(), Sets.newArraySet(UID_1));

        LocaleList localeList = mLocalService.getPreferredLocaleListForUid(UID_1);
        assertThat(localeList).isEqualTo(
                LocaleList.forLanguageTags(firstKeyboardConfig.getLanguageTag()));
    }

    @Test
    public void cameraAccessController_observerCountUpdated() {
        assertThat(mCameraAccessController.getObserverCount()).isEqualTo(1);

        VirtualDeviceImpl secondDevice =
                createVirtualDevice(VIRTUAL_DEVICE_ID_2, DEVICE_OWNER_UID_2);
        assertThat(mCameraAccessController.getObserverCount()).isEqualTo(2);

        mDeviceImpl.close();
        assertThat(mCameraAccessController.getObserverCount()).isEqualTo(1);

        secondDevice.close();
        assertThat(mCameraAccessController.getObserverCount()).isEqualTo(0);
    }

    @Test
    public void onVirtualDisplayRemovedLocked_doesNotThrowException() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        // This call should not throw any exceptions.
        mDeviceImpl.onVirtualDisplayRemoved(DISPLAY_ID_1);
    }

    @Test
    public void onVirtualDisplayCreatedLocked_listenersNotified() {
        mLocalService.registerVirtualDisplayListener(mDisplayListener);

        mLocalService.onVirtualDisplayCreated(DISPLAY_ID_1);
        TestableLooper.get(this).processAllMessages();

        verify(mDisplayListener).onVirtualDisplayCreated(DISPLAY_ID_1);
    }

    @Test
    public void onVirtualDisplayRemovedLocked_listenersNotified() {
        mLocalService.registerVirtualDisplayListener(mDisplayListener);

        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);

        mLocalService.onVirtualDisplayRemoved(mDeviceImpl, DISPLAY_ID_1);
        TestableLooper.get(this).processAllMessages();

        verify(mDisplayListener).onVirtualDisplayRemoved(DISPLAY_ID_1);
    }

    @Test
    public void onAppsOnVirtualDeviceChanged_singleVirtualDevice_listenersNotified() {
        ArraySet<Integer> uids = new ArraySet<>(Arrays.asList(UID_1, UID_2));
        mLocalService.registerAppsOnVirtualDeviceListener(mAppsOnVirtualDeviceListener);

        mVdms.notifyRunningAppsChanged(mDeviceImpl.getDeviceId(), uids);
        TestableLooper.get(this).processAllMessages();

        verify(mAppsOnVirtualDeviceListener).onAppsOnAnyVirtualDeviceChanged(uids);
    }

    @Test
    public void onAppsOnVirtualDeviceChanged_multipleVirtualDevices_listenersNotified() {
        createVirtualDevice(VIRTUAL_DEVICE_ID_2, DEVICE_OWNER_UID_2);

        ArraySet<Integer> uidsOnDevice1 = new ArraySet<>(Arrays.asList(UID_1, UID_2));
        ArraySet<Integer> uidsOnDevice2 = new ArraySet<>(Arrays.asList(UID_3, UID_4));
        mLocalService.registerAppsOnVirtualDeviceListener(mAppsOnVirtualDeviceListener);

        // Notifies that the running apps on the first virtual device has changed.
        mVdms.notifyRunningAppsChanged(mDeviceImpl.getDeviceId(), uidsOnDevice1);
        TestableLooper.get(this).processAllMessages();
        verify(mAppsOnVirtualDeviceListener).onAppsOnAnyVirtualDeviceChanged(
                new ArraySet<>(Arrays.asList(UID_1, UID_2)));

        // Notifies that the running apps on the second virtual device has changed.
        mVdms.notifyRunningAppsChanged(VIRTUAL_DEVICE_ID_2, uidsOnDevice2);
        TestableLooper.get(this).processAllMessages();
        // The union of the apps running on both virtual devices are sent to the listeners.
        verify(mAppsOnVirtualDeviceListener).onAppsOnAnyVirtualDeviceChanged(
                new ArraySet<>(Arrays.asList(UID_1, UID_2, UID_3, UID_4)));

        // Notifies that the running apps on the first virtual device has changed again.
        uidsOnDevice1.remove(UID_2);
        mVdms.notifyRunningAppsChanged(mDeviceImpl.getDeviceId(), uidsOnDevice1);
        mLocalService.onAppsOnVirtualDeviceChanged();
        TestableLooper.get(this).processAllMessages();
        // The union of the apps running on both virtual devices are sent to the listeners.
        verify(mAppsOnVirtualDeviceListener).onAppsOnAnyVirtualDeviceChanged(
                new ArraySet<>(Arrays.asList(UID_1, UID_3, UID_4)));

        // Notifies that the running apps on the first virtual device has changed but with the same
        // set of UIDs.
        mVdms.notifyRunningAppsChanged(mDeviceImpl.getDeviceId(), uidsOnDevice1);
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
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        verify(mIPowerManagerMock).acquireWakeLock(any(Binder.class), anyInt(),
                nullable(String.class), nullable(String.class), nullable(WorkSource.class),
                nullable(String.class), eq(DISPLAY_ID_1), eq(null));
    }

    @Test
    public void onVirtualDisplayCreatedLocked_duplicateCalls_onlyOneWakeLockIsAcquired()
            throws RemoteException {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        assertThrows(IllegalStateException.class,
                () -> addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1));
        TestableLooper.get(this).processAllMessages();
        verify(mIPowerManagerMock).acquireWakeLock(any(Binder.class), anyInt(),
                nullable(String.class), nullable(String.class), nullable(WorkSource.class),
                nullable(String.class), eq(DISPLAY_ID_1), eq(null));
    }

    @Test
    public void onVirtualDisplayRemovedLocked_wakeLockIsReleased() throws RemoteException {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        ArgumentCaptor<IBinder> wakeLockCaptor = ArgumentCaptor.forClass(IBinder.class);
        TestableLooper.get(this).processAllMessages();
        verify(mIPowerManagerMock).acquireWakeLock(wakeLockCaptor.capture(),
                anyInt(),
                nullable(String.class), nullable(String.class), nullable(WorkSource.class),
                nullable(String.class), eq(DISPLAY_ID_1), eq(null));

        IBinder wakeLock = wakeLockCaptor.getValue();
        mDeviceImpl.onVirtualDisplayRemoved(DISPLAY_ID_1);
        verify(mIPowerManagerMock).releaseWakeLock(eq(wakeLock), anyInt());
    }

    @Test
    public void addVirtualDisplay_displayNotReleased_wakeLockIsReleased() throws RemoteException {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        ArgumentCaptor<IBinder> wakeLockCaptor = ArgumentCaptor.forClass(IBinder.class);
        TestableLooper.get(this).processAllMessages();
        verify(mIPowerManagerMock).acquireWakeLock(wakeLockCaptor.capture(),
                anyInt(),
                nullable(String.class), nullable(String.class), nullable(WorkSource.class),
                nullable(String.class), eq(DISPLAY_ID_1), eq(null));
        IBinder wakeLock = wakeLockCaptor.getValue();

        // Close the VirtualDevice without first notifying it of the VirtualDisplay removal.
        mDeviceImpl.close();
        verify(mIPowerManagerMock).releaseWakeLock(eq(wakeLock), anyInt());
    }

    @Test
    public void createVirtualDpad_noDisplay_failsSecurityException() {
        assertThrows(SecurityException.class,
                () -> mDeviceImpl.createVirtualDpad(DPAD_CONFIG, BINDER));
    }

    @Test
    public void createVirtualKeyboard_noDisplay_failsSecurityException() {
        assertThrows(SecurityException.class,
                () -> mDeviceImpl.createVirtualKeyboard(KEYBOARD_CONFIG, BINDER));
    }

    @Test
    public void createVirtualMouse_noDisplay_failsSecurityException() {
        assertThrows(SecurityException.class,
                () -> mDeviceImpl.createVirtualMouse(MOUSE_CONFIG, BINDER));
    }

    @Test
    public void createVirtualTouchscreen_noDisplay_failsSecurityException() {
        assertThrows(SecurityException.class,
                () -> mDeviceImpl.createVirtualTouchscreen(TOUCHSCREEN_CONFIG, BINDER));
    }

    @Test
    public void createVirtualTouchscreen_zeroDisplayDimension_failsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualTouchscreenConfig.Builder(
                        /* touchscrenWidth= */ 0, /* touchscreenHeight= */ 0));
    }

    @Test
    public void createVirtualTouchscreen_negativeDisplayDimension_failsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualTouchscreenConfig.Builder(
                        /* touchscrenWidth= */ -100, /* touchscreenHeight= */ -100));
    }

    @Test
    public void createVirtualTouchscreen_positiveDisplayDimension_successful() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        VirtualTouchscreenConfig positiveConfig =
                new VirtualTouchscreenConfig.Builder(
                        /* touchscrenWidth= */ 600, /* touchscreenHeight= */ 800)
                        .setVendorId(VENDOR_ID)
                        .setProductId(PRODUCT_ID)
                        .setInputDeviceName(DEVICE_NAME_1)
                        .setAssociatedDisplayId(DISPLAY_ID_1)
                        .build();
        mDeviceImpl.createVirtualTouchscreen(positiveConfig, BINDER);
        assertWithMessage(
                "Virtual touchscreen should create input device descriptor on successful creation"
                        + ".").that(mInputController.getInputDeviceDescriptors()).isNotEmpty();
    }

    @Test
    public void createVirtualNavigationTouchpad_noDisplay_failsSecurityException() {
        assertThrows(SecurityException.class,
                () -> mDeviceImpl.createVirtualNavigationTouchpad(NAVIGATION_TOUCHPAD_CONFIG,
                        BINDER));
    }

    @Test
    public void createVirtualNavigationTouchpad_zeroDisplayDimension_failsWithException() {
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualNavigationTouchpadConfig.Builder(
                        /* touchpadHeight= */ 0, /* touchpadWidth= */ 0));
    }

    @Test
    public void createVirtualNavigationTouchpad_negativeDisplayDimension_failsWithException() {
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualNavigationTouchpadConfig.Builder(
                        /* touchpadHeight= */ -50, /* touchpadWidth= */ 50));
    }

    @Test
    public void createVirtualNavigationTouchpad_positiveDisplayDimension_successful() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        VirtualNavigationTouchpadConfig positiveConfig =
                new VirtualNavigationTouchpadConfig.Builder(
                        /* touchpadHeight= */ 50, /* touchpadWidth= */ 50)
                        .setVendorId(VENDOR_ID)
                        .setProductId(PRODUCT_ID)
                        .setInputDeviceName(DEVICE_NAME_1)
                        .setAssociatedDisplayId(DISPLAY_ID_1)
                        .build();
        mDeviceImpl.createVirtualNavigationTouchpad(positiveConfig, BINDER);
        assertWithMessage(
                "Virtual navigation touchpad should create input device descriptor on successful "
                        + "creation"
                        + ".").that(mInputController.getInputDeviceDescriptors()).isNotEmpty();
    }

    @Test
    public void onAudioSessionStarting_noDisplay_failsSecurityException() {
        assertThrows(SecurityException.class,
                () -> mDeviceImpl.onAudioSessionStarting(
                        DISPLAY_ID_1, mRoutingCallback, mConfigChangedCallback));
    }

    @Test
    public void createVirtualDpad_noPermission_failsSecurityException() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        try (DropShellPermissionsTemporarily drop = new DropShellPermissionsTemporarily()) {
            assertThrows(SecurityException.class,
                    () -> mDeviceImpl.createVirtualDpad(DPAD_CONFIG, BINDER));
        }
    }

    @Test
    public void createVirtualKeyboard_noPermission_failsSecurityException() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        try (DropShellPermissionsTemporarily drop = new DropShellPermissionsTemporarily()) {
            assertThrows(SecurityException.class,
                    () -> mDeviceImpl.createVirtualKeyboard(KEYBOARD_CONFIG, BINDER));
        }
    }

    @Test
    public void createVirtualMouse_noPermission_failsSecurityException() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        try (DropShellPermissionsTemporarily drop = new DropShellPermissionsTemporarily()) {
            assertThrows(SecurityException.class,
                    () -> mDeviceImpl.createVirtualMouse(MOUSE_CONFIG, BINDER));
        }
    }

    @Test
    public void createVirtualTouchscreen_noPermission_failsSecurityException() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        try (DropShellPermissionsTemporarily drop = new DropShellPermissionsTemporarily()) {
            assertThrows(SecurityException.class,
                    () -> mDeviceImpl.createVirtualTouchscreen(TOUCHSCREEN_CONFIG, BINDER));
        }
    }

    @Test
    public void createVirtualNavigationTouchpad_noPermission_failsSecurityException() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        try (DropShellPermissionsTemporarily drop = new DropShellPermissionsTemporarily()) {
            assertThrows(SecurityException.class,
                    () -> mDeviceImpl.createVirtualNavigationTouchpad(NAVIGATION_TOUCHPAD_CONFIG,
                            BINDER));
        }
    }

    @Test
    public void onAudioSessionStarting_noPermission_failsSecurityException() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        try (DropShellPermissionsTemporarily drop = new DropShellPermissionsTemporarily()) {
            assertThrows(SecurityException.class,
                    () -> mDeviceImpl.onAudioSessionStarting(
                            DISPLAY_ID_1, mRoutingCallback, mConfigChangedCallback));
        }
    }

    @Test
    public void onAudioSessionEnded_noPermission_failsSecurityException() {
        try (DropShellPermissionsTemporarily drop = new DropShellPermissionsTemporarily()) {
            assertThrows(SecurityException.class, () -> mDeviceImpl.onAudioSessionEnded());
        }
    }

    @Test
    public void createVirtualDpad_hasDisplay_obtainFileDescriptor() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        mDeviceImpl.createVirtualDpad(DPAD_CONFIG, BINDER);
        assertWithMessage("Virtual dpad should register fd when the display matches").that(
                mInputController.getInputDeviceDescriptors()).isNotEmpty();
        verify(mNativeWrapperMock).openUinputDpad(eq(DEVICE_NAME_1), eq(VENDOR_ID), eq(PRODUCT_ID),
                anyString());
    }

    @Test
    public void createVirtualKeyboard_hasDisplay_obtainFileDescriptor() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        mDeviceImpl.createVirtualKeyboard(KEYBOARD_CONFIG, BINDER);
        assertWithMessage("Virtual keyboard should register fd when the display matches").that(
                mInputController.getInputDeviceDescriptors()).isNotEmpty();
        verify(mNativeWrapperMock).openUinputKeyboard(eq(DEVICE_NAME_1), eq(VENDOR_ID),
                eq(PRODUCT_ID), anyString());
    }

    @Test
    public void createVirtualKeyboard_keyboardCreated_localeUpdated() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        mDeviceImpl.createVirtualKeyboard(KEYBOARD_CONFIG, BINDER);
        assertWithMessage("Virtual keyboard should register fd when the display matches")
                .that(mInputController.getInputDeviceDescriptors())
                .isNotEmpty();
        verify(mNativeWrapperMock).openUinputKeyboard(eq(DEVICE_NAME_1), eq(VENDOR_ID),
                eq(PRODUCT_ID), anyString());
        assertThat(mDeviceImpl.getDeviceLocaleList()).isEqualTo(
                LocaleList.forLanguageTags(KEYBOARD_CONFIG.getLanguageTag()));
    }

    @Test
    public void createVirtualKeyboard_keyboardWithoutExplicitLayoutInfo_localeUpdatedWithDefault() {
        VirtualKeyboardConfig configWithoutExplicitLayoutInfo =
                new VirtualKeyboardConfig.Builder()
                        .setVendorId(VENDOR_ID)
                        .setProductId(PRODUCT_ID)
                        .setInputDeviceName(DEVICE_NAME_1)
                        .setAssociatedDisplayId(DISPLAY_ID_1)
                        .build();

        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        mDeviceImpl.createVirtualKeyboard(configWithoutExplicitLayoutInfo, BINDER);
        assertWithMessage("Virtual keyboard should register fd when the display matches")
                .that(mInputController.getInputDeviceDescriptors())
                .isNotEmpty();
        verify(mNativeWrapperMock).openUinputKeyboard(eq(DEVICE_NAME_1), eq(VENDOR_ID),
                eq(PRODUCT_ID), anyString());
        assertThat(mDeviceImpl.getDeviceLocaleList()).isEqualTo(
                LocaleList.forLanguageTags(VirtualKeyboardConfig.DEFAULT_LANGUAGE_TAG));
    }

    @Test
    public void virtualDeviceWithoutKeyboard_noLocaleUpdate() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);

        // no preceding call to createVirtualKeyboard()
        assertThat(mDeviceImpl.getDeviceLocaleList()).isNull();
    }

    @Test
    public void createVirtualMouse_hasDisplay_obtainFileDescriptor() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        mDeviceImpl.createVirtualMouse(MOUSE_CONFIG, BINDER);
        assertWithMessage("Virtual mouse should register fd when the display matches").that(
                mInputController.getInputDeviceDescriptors()).isNotEmpty();
        verify(mNativeWrapperMock).openUinputMouse(eq(DEVICE_NAME_1), eq(VENDOR_ID), eq(PRODUCT_ID),
                anyString());
    }

    @Test
    public void createVirtualTouchscreen_hasDisplay_obtainFileDescriptor() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        mDeviceImpl.createVirtualTouchscreen(TOUCHSCREEN_CONFIG, BINDER);
        assertWithMessage("Virtual touchscreen should register fd when the display matches").that(
                mInputController.getInputDeviceDescriptors()).isNotEmpty();
        verify(mNativeWrapperMock).openUinputTouchscreen(eq(DEVICE_NAME_1), eq(VENDOR_ID),
                eq(PRODUCT_ID), anyString(), eq(HEIGHT), eq(WIDTH));
    }

    @Test
    public void createVirtualNavigationTouchpad_hasDisplay_obtainFileDescriptor() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        mDeviceImpl.createVirtualNavigationTouchpad(NAVIGATION_TOUCHPAD_CONFIG, BINDER);
        assertWithMessage("Virtual navigation touchpad should register fd when the display matches")
                .that(
                        mInputController.getInputDeviceDescriptors()).isNotEmpty();
        verify(mNativeWrapperMock).openUinputTouchscreen(eq(DEVICE_NAME_1), eq(VENDOR_ID),
                eq(PRODUCT_ID), anyString(), eq(HEIGHT), eq(WIDTH));
    }

    @Test
    public void createVirtualKeyboard_inputDeviceId_obtainFromInputController() {
        final int fd = 1;
        mInputController.addDeviceForTesting(BINDER, fd,
                InputController.InputDeviceDescriptor.TYPE_KEYBOARD, DISPLAY_ID_1, PHYS,
                DEVICE_NAME_1, INPUT_DEVICE_ID);
        assertWithMessage(
                "InputController should return device id from InputDeviceDescriptor").that(
                mInputController.getInputDeviceId(BINDER)).isEqualTo(INPUT_DEVICE_ID);
    }

    @Test
    public void onAudioSessionStarting_hasVirtualAudioController() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);

        mDeviceImpl.onAudioSessionStarting(DISPLAY_ID_1, mRoutingCallback, mConfigChangedCallback);

        assertThat(mDeviceImpl.getVirtualAudioControllerForTesting()).isNotNull();
    }

    @Test
    public void onAudioSessionEnded_noVirtualAudioController() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        mDeviceImpl.onAudioSessionStarting(DISPLAY_ID_1, mRoutingCallback, mConfigChangedCallback);

        mDeviceImpl.onAudioSessionEnded();

        assertThat(mDeviceImpl.getVirtualAudioControllerForTesting()).isNull();
    }

    @Test
    public void close_cleanVirtualAudioController() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        mDeviceImpl.onAudioSessionStarting(DISPLAY_ID_1, mRoutingCallback, mConfigChangedCallback);

        mDeviceImpl.close();

        assertThat(mDeviceImpl.getVirtualAudioControllerForTesting()).isNull();
    }

    @Test
    public void close_cleanSensorController() {
        mSensorController.addSensorForTesting(
                BINDER, SENSOR_HANDLE, Sensor.TYPE_ACCELEROMETER, DEVICE_NAME_1);

        mDeviceImpl.close();

        assertThat(mSensorController.getSensorDescriptors()).isEmpty();
        verify(mSensorManagerInternalMock).removeRuntimeSensor(SENSOR_HANDLE);
    }

    @Test
    public void closedDevice_lateCallToRunningAppsChanged_isIgnored() {
        mLocalService.registerAppsOnVirtualDeviceListener(mAppsOnVirtualDeviceListener);
        int deviceId = mDeviceImpl.getDeviceId();
        mDeviceImpl.close();
        mVdms.notifyRunningAppsChanged(deviceId, Sets.newArraySet(UID_1));
        TestableLooper.get(this).processAllMessages();
        verify(mAppsOnVirtualDeviceListener, never()).onAppsOnAnyVirtualDeviceChanged(any());
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
        final long eventTimeNanos = 5000L;
        mInputController.addDeviceForTesting(BINDER, fd,
                InputController.InputDeviceDescriptor.TYPE_KEYBOARD, DISPLAY_ID_1, PHYS,
                DEVICE_NAME_1, INPUT_DEVICE_ID);

        mDeviceImpl.sendKeyEvent(BINDER, new VirtualKeyEvent.Builder()
                .setKeyCode(keyCode)
                .setAction(action)
                .setEventTimeNanos(eventTimeNanos)
                .build());
        verify(mNativeWrapperMock).writeKeyEvent(fd, keyCode, action, eventTimeNanos);
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
        final long eventTimeNanos = 5000L;
        mInputController.addDeviceForTesting(BINDER, fd,
                InputController.InputDeviceDescriptor.TYPE_MOUSE, DISPLAY_ID_1, PHYS,
                DEVICE_NAME_1, INPUT_DEVICE_ID);
        doReturn(DISPLAY_ID_1).when(mInputManagerInternalMock).getVirtualMousePointerDisplayId();
        mDeviceImpl.sendButtonEvent(BINDER, new VirtualMouseButtonEvent.Builder()
                .setButtonCode(buttonCode)
                .setAction(action)
                .setEventTimeNanos(eventTimeNanos)
                .build());
        verify(mNativeWrapperMock).writeButtonEvent(fd, buttonCode, action, eventTimeNanos);
    }

    @Test
    public void sendButtonEvent_hasFd_wrongDisplay_throwsIllegalStateException() {
        final int fd = 1;
        final int buttonCode = VirtualMouseButtonEvent.BUTTON_BACK;
        final int action = VirtualMouseButtonEvent.ACTION_BUTTON_PRESS;
        mInputController.addDeviceForTesting(BINDER, fd,
                InputController.InputDeviceDescriptor.TYPE_MOUSE, DISPLAY_ID_1, PHYS, DEVICE_NAME_1,
                INPUT_DEVICE_ID);
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
        final long eventTimeNanos = 5000L;
        mInputController.addDeviceForTesting(BINDER, fd,
                InputController.InputDeviceDescriptor.TYPE_MOUSE, DISPLAY_ID_1, PHYS, DEVICE_NAME_1,
                INPUT_DEVICE_ID);
        doReturn(DISPLAY_ID_1).when(mInputManagerInternalMock).getVirtualMousePointerDisplayId();
        mDeviceImpl.sendRelativeEvent(BINDER, new VirtualMouseRelativeEvent.Builder()
                .setRelativeX(x)
                .setRelativeY(y)
                .setEventTimeNanos(eventTimeNanos)
                .build());
        verify(mNativeWrapperMock).writeRelativeEvent(fd, x, y, eventTimeNanos);
    }

    @Test
    public void sendRelativeEvent_hasFd_wrongDisplay_throwsIllegalStateException() {
        final int fd = 1;
        final float x = -0.2f;
        final float y = 0.7f;
        mInputController.addDeviceForTesting(BINDER, fd,
                InputController.InputDeviceDescriptor.TYPE_MOUSE, DISPLAY_ID_1, PHYS, DEVICE_NAME_1,
                INPUT_DEVICE_ID);
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
        final long eventTimeNanos = 5000L;
        mInputController.addDeviceForTesting(BINDER, fd,
                InputController.InputDeviceDescriptor.TYPE_MOUSE, DISPLAY_ID_1, PHYS, DEVICE_NAME_1,
                INPUT_DEVICE_ID);
        doReturn(DISPLAY_ID_1).when(mInputManagerInternalMock).getVirtualMousePointerDisplayId();
        mDeviceImpl.sendScrollEvent(BINDER, new VirtualMouseScrollEvent.Builder()
                .setXAxisMovement(x)
                .setYAxisMovement(y)
                .setEventTimeNanos(eventTimeNanos)
                .build());
        verify(mNativeWrapperMock).writeScrollEvent(fd, x, y, eventTimeNanos);
    }

    @Test
    public void sendScrollEvent_hasFd_wrongDisplay_throwsIllegalStateException() {
        final int fd = 1;
        final float x = 0.5f;
        final float y = 1f;
        mInputController.addDeviceForTesting(BINDER, fd,
                InputController.InputDeviceDescriptor.TYPE_MOUSE, DISPLAY_ID_1, PHYS, DEVICE_NAME_1,
                INPUT_DEVICE_ID);
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
        final long eventTimeNanos = 5000L;
        mInputController.addDeviceForTesting(BINDER, fd,
                InputController.InputDeviceDescriptor.TYPE_TOUCHSCREEN, DISPLAY_ID_1, PHYS,
                DEVICE_NAME_1, INPUT_DEVICE_ID);
        mDeviceImpl.sendTouchEvent(BINDER, new VirtualTouchEvent.Builder()
                .setX(x)
                .setY(y)
                .setAction(action)
                .setPointerId(pointerId)
                .setToolType(toolType)
                .setEventTimeNanos(eventTimeNanos)
                .build());
        verify(mNativeWrapperMock).writeTouchEvent(fd, pointerId, toolType, action, x, y, Float.NaN,
                Float.NaN, eventTimeNanos);
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
        final long eventTimeNanos = 5000L;
        mInputController.addDeviceForTesting(BINDER, fd,
                InputController.InputDeviceDescriptor.TYPE_TOUCHSCREEN, DISPLAY_ID_1, PHYS,
                DEVICE_NAME_1, INPUT_DEVICE_ID);
        mDeviceImpl.sendTouchEvent(BINDER, new VirtualTouchEvent.Builder()
                .setX(x)
                .setY(y)
                .setAction(action)
                .setPointerId(pointerId)
                .setToolType(toolType)
                .setPressure(pressure)
                .setMajorAxisSize(majorAxisSize)
                .setEventTimeNanos(eventTimeNanos)
                .build());
        verify(mNativeWrapperMock).writeTouchEvent(fd, pointerId, toolType, action, x, y, pressure,
                majorAxisSize, eventTimeNanos);
    }

    @Test
    public void setShowPointerIcon_setsValueForAllDisplays() {
        addVirtualDisplay(mDeviceImpl, 1);
        addVirtualDisplay(mDeviceImpl, 2);
        addVirtualDisplay(mDeviceImpl, 3);
        VirtualMouseConfig config1 = new VirtualMouseConfig.Builder()
                .setAssociatedDisplayId(1)
                .setInputDeviceName(DEVICE_NAME_1)
                .setVendorId(VENDOR_ID)
                .setProductId(PRODUCT_ID)
                .build();
        VirtualMouseConfig config2 = new VirtualMouseConfig.Builder()
                .setAssociatedDisplayId(2)
                .setInputDeviceName(DEVICE_NAME_2)
                .setVendorId(VENDOR_ID)
                .setProductId(PRODUCT_ID)
                .build();
        VirtualMouseConfig config3 = new VirtualMouseConfig.Builder()
                .setAssociatedDisplayId(3)
                .setInputDeviceName(DEVICE_NAME_3)
                .setVendorId(VENDOR_ID)
                .setProductId(PRODUCT_ID)
                .build();

        mDeviceImpl.createVirtualMouse(config1, BINDER);
        mDeviceImpl.createVirtualMouse(config2, BINDER);
        mDeviceImpl.createVirtualMouse(config3, BINDER);
        clearInvocations(mInputManagerInternalMock);
        mDeviceImpl.setShowPointerIcon(false);

        verify(mInputManagerInternalMock, times(3)).setPointerIconVisible(eq(false), anyInt());
        verify(mInputManagerInternalMock, never()).setPointerIconVisible(eq(true), anyInt());
        mDeviceImpl.setShowPointerIcon(true);
        verify(mInputManagerInternalMock, times(3)).setPointerIconVisible(eq(true), anyInt());
    }

    @Test
    public void openNonBlockedAppOnVirtualDisplay_doesNotStartBlockedAlertActivity() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        gwpc.canActivityBeLaunched(activityInfo, blockedAppIntent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false);

        verify(mContext, never()).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openPermissionControllerOnVirtualDisplay_startBlockedAlertActivity() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ActivityInfo activityInfo = getActivityInfo(
                PERMISSION_CONTROLLER_PACKAGE_NAME,
                PERMISSION_CONTROLLER_PACKAGE_NAME,
                /* displayOnRemoteDevices */  false,
                /* targetDisplayCategory */ null);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        gwpc.canActivityBeLaunched(activityInfo, blockedAppIntent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false);

        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openPermissionControllerOnVirtualDisplay_displayOnRemoteDevices_startsWhenFlagIsEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_STREAM_PERMISSIONS);
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ActivityInfo activityInfo = getActivityInfo(
                PERMISSION_CONTROLLER_PACKAGE_NAME,
                PERMISSION_CONTROLLER_PACKAGE_NAME,
                /* displayOnRemoveDevices */ true,
                /* targetDisplayCategory */ null);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        gwpc.canActivityBeLaunched(activityInfo, blockedAppIntent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false);

        verify(mContext, never()).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openPermissionControllerOnVirtualDisplay_dontDisplayOnRemoteDevices_startsWhenFlagIsEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_STREAM_PERMISSIONS);
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ActivityInfo activityInfo = getActivityInfo(
                PERMISSION_CONTROLLER_PACKAGE_NAME,
                PERMISSION_CONTROLLER_PACKAGE_NAME,
                /* displayOnRemoveDevices */ false,
                /* targetDisplayCategory */ null);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        gwpc.canActivityBeLaunched(activityInfo, blockedAppIntent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false);

        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openSettingsOnVirtualDisplay_startBlockedAlertActivity() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ActivityInfo activityInfo = getActivityInfo(
                SETTINGS_PACKAGE_NAME,
                SETTINGS_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        gwpc.canActivityBeLaunched(activityInfo, blockedAppIntent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false);

        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openVendingOnVirtualDisplay_startBlockedAlertActivity() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ActivityInfo activityInfo = getActivityInfo(
                VENDING_PACKAGE_NAME,
                VENDING_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        gwpc.canActivityBeLaunched(activityInfo, blockedAppIntent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false);

        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openGoogleDialerOnVirtualDisplay_startBlockedAlertActivity() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ActivityInfo activityInfo = getActivityInfo(
                GOOGLE_DIALER_PACKAGE_NAME,
                GOOGLE_DIALER_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        gwpc.canActivityBeLaunched(activityInfo, blockedAppIntent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false);

        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openGoogleMapsOnVirtualDisplay_startBlockedAlertActivity() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ActivityInfo activityInfo = getActivityInfo(
                GOOGLE_MAPS_PACKAGE_NAME,
                GOOGLE_MAPS_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        gwpc.canActivityBeLaunched(activityInfo, blockedAppIntent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false);

        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openNonBlockedAppOnMirrorDisplay_flagEnabled_cannotBeLaunched() {
        mSetFlagsRule.enableFlags(Flags.FLAG_INTERACTIVE_SCREEN_MIRROR);
        when(mDisplayManagerInternalMock.getDisplayIdToMirror(anyInt()))
                .thenReturn(Display.DEFAULT_DISPLAY);
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertThat(gwpc.canActivityBeLaunched(activityInfo, null,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/ false))
                .isFalse();
        // Verify that BlockedAppStreamingActivity also doesn't launch for mirror displays.
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        verify(mContext, never()).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void openNonBlockedAppOnMirrorDisplay_flagDisabled_launchesActivity() {
        mSetFlagsRule.disableFlags(Flags.FLAG_INTERACTIVE_SCREEN_MIRROR);
        when(mDisplayManagerInternalMock.getDisplayIdToMirror(anyInt()))
                .thenReturn(Display.DEFAULT_DISPLAY);
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertThat(gwpc.canActivityBeLaunched(activityInfo, null,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/ false))
                .isTrue();
        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        verify(mContext, never()).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void registerRunningAppsChangedListener_onRunningAppsChanged_listenersNotified() {
        ArraySet<Integer> uids = new ArraySet<>(Arrays.asList(UID_1, UID_2));
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);

        gwpc.onRunningAppsChanged(uids);
        mDeviceImpl.onRunningAppsChanged(uids);

        assertThat(gwpc.getRunningAppsChangedListenersSizeForTesting()).isEqualTo(1);
        verify(mRunningAppsChangedCallback).accept(new ArraySet<>(Arrays.asList(UID_1, UID_2)));
    }

    @Test
    public void noRunningAppsChangedListener_onRunningAppsChanged_doesNotThrowException() {
        ArraySet<Integer> uids = new ArraySet<>(Arrays.asList(UID_1, UID_2));
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        gwpc.unregisterRunningAppsChangedListener(mDeviceImpl);

        // This call should not throw any exceptions.
        gwpc.onRunningAppsChanged(uids);

        assertThat(gwpc.getRunningAppsChangedListenersSizeForTesting()).isEqualTo(0);
    }

    @Test
    public void canActivityBeLaunched_permissionDialog_flagDisabled_isBlocked() {
        mSetFlagsRule.disableFlags(Flags.FLAG_STREAM_PERMISSIONS);
        VirtualDeviceParams params = new VirtualDeviceParams.Builder().build();
        mDeviceImpl.close();
        mDeviceImpl = createVirtualDevice(VIRTUAL_DEVICE_ID_1, DEVICE_OWNER_UID_1, params);
        doNothing().when(mContext).startActivityAsUser(any(), any(), any());

        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        ComponentName permissionComponent = getPermissionDialogComponent();
        ActivityInfo activityInfo = getActivityInfo(
                permissionComponent.getPackageName(),
                permissionComponent.getClassName(),
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertThat(gwpc.canActivityBeLaunched(activityInfo, null,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false))
                .isFalse();

        Intent blockedAppIntent = BlockedAppStreamingActivity.createIntent(
                activityInfo, mAssociationInfo.getDisplayName());
        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void canActivityBeLaunched_permissionDialog_flagEnabled_isStreamed() {
        mSetFlagsRule.enableFlags(Flags.FLAG_STREAM_PERMISSIONS);
        VirtualDeviceParams params = new VirtualDeviceParams.Builder().build();
        mDeviceImpl.close();
        mDeviceImpl = createVirtualDevice(VIRTUAL_DEVICE_ID_1, DEVICE_OWNER_UID_1, params);

        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        ComponentName permissionComponent = getPermissionDialogComponent();
        ActivityInfo activityInfo = getActivityInfo(
                permissionComponent.getPackageName(),
                permissionComponent.getClassName(),
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertThat(gwpc.canActivityBeLaunched(activityInfo, null,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false))
                .isTrue();
    }

    @Test
    public void canActivityBeLaunched_activityCanLaunch() {
        Intent intent = new Intent(ACTION_VIEW, Uri.parse(TEST_SITE));
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);
        assertThat(gwpc.canActivityBeLaunched(activityInfo, intent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false))
                .isTrue();
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

        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);

        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_VIEW);
        intentFilter.addDataScheme(IntentFilter.SCHEME_HTTP);
        intentFilter.addDataScheme(IntentFilter.SCHEME_HTTPS);

        // register interceptor and intercept intent
        mDeviceImpl.registerIntentInterceptor(interceptor, intentFilter);
        assertThat(gwpc.canActivityBeLaunched(activityInfo, intent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false))
                .isFalse();
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(interceptor).onIntentIntercepted(intentCaptor.capture());
        Intent cIntent = intentCaptor.getValue();
        assertThat(cIntent).isNotNull();
        assertThat(cIntent.getAction()).isEqualTo(Intent.ACTION_VIEW);
        assertThat(cIntent.getData().toString()).isEqualTo(TEST_SITE);

        // unregister interceptor and launch activity
        mDeviceImpl.unregisterIntentInterceptor(interceptor);
        assertThat(gwpc.canActivityBeLaunched(activityInfo, intent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false))
                .isTrue();
    }

    @Test
    public void canActivityBeLaunched_noMatchIntentFilter_activityLaunches()
            throws RemoteException {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("testing"));

        IVirtualDeviceIntentInterceptor.Stub interceptor =
                mock(IVirtualDeviceIntentInterceptor.Stub.class);
        doNothing().when(interceptor).onIntentIntercepted(any());
        doReturn(interceptor).when(interceptor).asBinder();
        doReturn(interceptor).when(interceptor).queryLocalInterface(anyString());

        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        GenericWindowPolicyController gwpc = mDeviceImpl.getDisplayWindowPolicyControllerForTest(
                DISPLAY_ID_1);
        ActivityInfo activityInfo = getActivityInfo(
                NONBLOCKED_APP_PACKAGE_NAME,
                NONBLOCKED_APP_PACKAGE_NAME,
                /* displayOnRemoteDevices */ true,
                /* targetDisplayCategory */ null);

        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_VIEW);
        intentFilter.addDataScheme("mailto");

        // register interceptor with different filter
        mDeviceImpl.registerIntentInterceptor(interceptor, intentFilter);

        assertThat(gwpc.canActivityBeLaunched(activityInfo, intent,
                WindowConfiguration.WINDOWING_MODE_FULLSCREEN, DISPLAY_ID_1, /*isNewTask=*/false))
                .isTrue();
    }

    @Test
    public void nonRestrictedActivityOnRestrictedVirtualDisplay_startBlockedAlertActivity() {
        Intent blockedAppIntent = createRestrictedActivityBlockedIntent(Set.of("abc"),
                /* targetDisplayCategory= */ null);
        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void restrictedActivityOnRestrictedVirtualDisplay_doesNotStartBlockedAlertActivity() {
        Intent blockedAppIntent = createRestrictedActivityBlockedIntent(Set.of("abc"), "abc");
        verify(mContext, never()).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void restrictedActivityOnNonRestrictedVirtualDisplay_startBlockedAlertActivity() {
        Intent blockedAppIntent = createRestrictedActivityBlockedIntent(
                /* displayCategories= */ Set.of(), "abc");
        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void restrictedActivityNonMatchingRestrictedVirtualDisplay_startBlockedAlertActivity() {
        Intent blockedAppIntent = createRestrictedActivityBlockedIntent(Set.of("abc"), "def");
        verify(mContext).startActivityAsUser(argThat(intent ->
                intent.filterEquals(blockedAppIntent)), any(), any());
    }

    @Test
    public void playSoundEffect_callsSoundEffectListener() throws Exception {
        mVdm.playSoundEffect(mDeviceImpl.getDeviceId(), AudioManager.FX_KEY_CLICK);

        verify(mSoundEffectListener).onPlaySoundEffect(AudioManager.FX_KEY_CLICK);
    }

    @Test
    public void getDisplayIdsForDevice_invalidDeviceId_emptyResult() {
        ArraySet<Integer> displayIds = mLocalService.getDisplayIdsForDevice(VIRTUAL_DEVICE_ID_2);
        assertThat(displayIds).isEmpty();
    }

    @Test
    public void getDisplayIdsForDevice_noDisplays_emptyResult() {
        ArraySet<Integer> displayIds = mLocalService.getDisplayIdsForDevice(VIRTUAL_DEVICE_ID_1);
        assertThat(displayIds).isEmpty();
    }

    @Test
    public void getDisplayIdsForDevice_oneDisplay_resultContainsCorrectDisplayId() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        ArraySet<Integer> displayIds = mLocalService.getDisplayIdsForDevice(VIRTUAL_DEVICE_ID_1);
        assertThat(displayIds).containsExactly(DISPLAY_ID_1);
    }

    @Test
    public void getDisplayIdsForDevice_twoDisplays_resultContainsCorrectDisplayIds() {
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_1);
        addVirtualDisplay(mDeviceImpl, DISPLAY_ID_2);
        ArraySet<Integer> displayIds = mLocalService.getDisplayIdsForDevice(VIRTUAL_DEVICE_ID_1);
        assertThat(displayIds).containsExactly(DISPLAY_ID_1, DISPLAY_ID_2);
    }

    @Test
    public void getPersistentIdForDevice_invalidDeviceId_returnsNull() {
        assertThat(mLocalService.getPersistentIdForDevice(DEVICE_ID_INVALID)).isNull();
        assertThat(mLocalService.getPersistentIdForDevice(DEVICE_ID_DEFAULT)).isNull();
        assertThat(mLocalService.getPersistentIdForDevice(VIRTUAL_DEVICE_ID_2)).isNull();
    }

    @Test
    public void getPersistentIdForDevice_returnsCorrectId() {
        assertThat(mLocalService.getPersistentIdForDevice(VIRTUAL_DEVICE_ID_1))
                .isEqualTo(mDeviceImpl.getPersistentDeviceId());
    }

    private VirtualDeviceImpl createVirtualDevice(int virtualDeviceId, int ownerUid) {
        VirtualDeviceParams params = new VirtualDeviceParams.Builder()
                .setBlockedActivities(getBlockedActivities())
                .build();
        return createVirtualDevice(virtualDeviceId, ownerUid, params);
    }

    private VirtualDeviceImpl createVirtualDevice(int virtualDeviceId, int ownerUid,
            VirtualDeviceParams params) {
        VirtualDeviceImpl virtualDeviceImpl =
                new VirtualDeviceImpl(
                        mContext,
                        mAssociationInfo,
                        mVdms,
                        mVirtualDeviceLog,
                        new Binder(),
                        new AttributionSource(
                                ownerUid, "com.android.virtualdevice.test", "virtualdevice"),
                        virtualDeviceId,
                        mInputController,
                        mCameraAccessController,
                        mPendingTrampolineCallback,
                        mActivityListener,
                        mSoundEffectListener,
                        mRunningAppsChangedCallback,
                        params,
                        new DisplayManagerGlobal(mIDisplayManager),
                        new VirtualCameraController());
        mVdms.addVirtualDevice(virtualDeviceImpl);
        assertThat(virtualDeviceImpl.getAssociationId()).isEqualTo(mAssociationInfo.getId());
        assertThat(virtualDeviceImpl.getPersistentDeviceId())
                .isEqualTo("companion:" + mAssociationInfo.getId());
        return virtualDeviceImpl;
    }

    private void addVirtualDisplay(VirtualDeviceImpl virtualDevice, int displayId) {
        when(mDisplayManagerInternalMock.createVirtualDisplay(any(), eq(mVirtualDisplayCallback),
                eq(virtualDevice), any(), any())).thenReturn(displayId);
        virtualDevice.createVirtualDisplay(VIRTUAL_DISPLAY_CONFIG, mVirtualDisplayCallback,
                NONBLOCKED_APP_PACKAGE_NAME);
    }

    private ComponentName getPermissionDialogComponent() {
        Intent intent = new Intent(ACTION_REQUEST_PERMISSIONS);
        PackageManager packageManager = mContext.getPackageManager();
        intent.setPackage(packageManager.getPermissionControllerPackageName());
        return intent.resolveActivity(packageManager);
    }

    /** Helper class to drop permissions temporarily and restore them at the end of a test. */
    static final class DropShellPermissionsTemporarily implements AutoCloseable {
        DropShellPermissionsTemporarily() {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }

        @Override
        public void close() {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .adoptShellPermissionIdentity();
        }
    }
}
