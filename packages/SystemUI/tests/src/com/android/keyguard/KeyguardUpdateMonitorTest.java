/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard;

import static android.app.StatusBarManager.SESSION_KEYGUARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT_PERMANENT;
import static android.hardware.biometrics.SensorProperties.STRENGTH_CONVENIENCE;
import static android.hardware.biometrics.SensorProperties.STRENGTH_STRONG;
import static android.hardware.fingerprint.FingerprintSensorProperties.TYPE_UDFPS_OPTICAL;
import static android.telephony.SubscriptionManager.DATA_ROAMING_DISABLE;
import static android.telephony.SubscriptionManager.NAME_SOURCE_CARRIER_ID;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;
import static com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_STATE_CANCELLING_RESTARTING;
import static com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_STATE_STOPPED;
import static com.android.keyguard.KeyguardUpdateMonitor.DEFAULT_CANCEL_SIGNAL_TIMEOUT;
import static com.android.keyguard.KeyguardUpdateMonitor.HAL_POWER_PRESS_TIMEOUT;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_OPENED;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_UNKNOWN;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.IStrongAuthTracker;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.dreams.IDreamManager;
import android.service.trust.TrustAgentService;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.text.TextUtils;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.InstanceId;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor.BiometricAuthenticated;
import com.android.keyguard.logging.KeyguardUpdateMonitorLogger;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.FingerprintInteractiveToAuthProvider;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.domain.interactor.FaceAuthenticationListener;
import com.android.systemui.keyguard.domain.interactor.KeyguardFaceAuthInteractor;
import com.android.systemui.keyguard.shared.model.ErrorFaceAuthenticationStatus;
import com.android.systemui.keyguard.shared.model.FaceDetectionStatus;
import com.android.systemui.keyguard.shared.model.FailedFaceAuthenticationStatus;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.settings.GlobalSettings;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.internal.util.reflection.FieldSetter;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardUpdateMonitorTest extends SysuiTestCase {
    private static final String PKG_ALLOWING_FP_LISTEN_ON_OCCLUDING_ACTIVITY =
            "test_app_fp_listen_on_occluding_activity";
    private static final String TEST_CARRIER = "TEST_CARRIER";
    private static final String TEST_CARRIER_2 = "TEST_CARRIER_2";
    private static final int TEST_CARRIER_ID = 1;
    private static final String TEST_GROUP_UUID = "59b5c870-fc4c-47a4-a99e-9db826b48b24";
    private static final SubscriptionInfo TEST_SUBSCRIPTION = new SubscriptionInfo(1, "", 0,
            TEST_CARRIER, TEST_CARRIER, NAME_SOURCE_CARRIER_ID, 0xFFFFFF, "",
            DATA_ROAMING_DISABLE, null, null, null, null, false, null, "", false, TEST_GROUP_UUID,
            TEST_CARRIER_ID, 0);
    private static final SubscriptionInfo TEST_SUBSCRIPTION_2 = new SubscriptionInfo(2, "", 0,
            TEST_CARRIER, TEST_CARRIER_2, NAME_SOURCE_CARRIER_ID, 0xFFFFFF, "",
            DATA_ROAMING_DISABLE, null, null, null, null, false, null, "", true, TEST_GROUP_UUID,
            TEST_CARRIER_ID, 0);
    private static final int FINGERPRINT_SENSOR_ID = 1;

    @Mock
    private UserTracker mUserTracker;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private KeyguardUpdateMonitor.StrongAuthTracker mStrongAuthTracker;
    @Mock
    private TrustManager mTrustManager;
    @Mock
    private LockPatternUtils mLockPatternUtils;
    @Mock
    private ILockSettings mLockSettings;
    @Mock
    private FingerprintManager mFingerprintManager;
    @Mock
    private BiometricManager mBiometricManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private DevicePolicyManager mDevicePolicyManager;
    @Mock
    private DevicePostureController mDevicePostureController;
    @Mock
    private IDreamManager mDreamManager;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private SubscriptionManager mSubscriptionManager;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private SensorPrivacyManager mSensorPrivacyManager;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private AuthController mAuthController;
    @Mock
    private TelephonyListenerManager mTelephonyListenerManager;
    @Mock
    private InteractionJankMonitor mInteractionJankMonitor;
    @Mock
    private LatencyTracker mLatencyTracker;
    @Captor
    private ArgumentCaptor<StatusBarStateController.StateListener> mStatusBarStateListenerCaptor;
    @Mock
    private KeyguardUpdateMonitorCallback mTestCallback;
    @Mock
    private ActiveUnlockConfig mActiveUnlockConfig;
    @Mock
    private KeyguardUpdateMonitorLogger mKeyguardUpdateMonitorLogger;
    @Mock
    private SessionTracker mSessionTracker;
    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private GlobalSettings mGlobalSettings;
    @Mock
    private FingerprintInteractiveToAuthProvider mInteractiveToAuthProvider;
    @Mock
    private UsbPort mUsbPort;
    @Mock
    private UsbManager mUsbManager;
    @Mock
    private UsbPortStatus mUsbPortStatus;
    @Mock
    private TaskStackChangeListeners mTaskStackChangeListeners;
    @Mock
    private IActivityTaskManager mActivityTaskManager;
    @Mock
    private SelectedUserInteractor mSelectedUserInteractor;
    @Mock
    private KeyguardFaceAuthInteractor mFaceAuthInteractor;
    @Captor
    private ArgumentCaptor<FaceAuthenticationListener> mFaceAuthenticationListener;

    private List<FingerprintSensorPropertiesInternal> mFingerprintSensorProperties;
    private final int mCurrentUserId = 100;

    @Captor
    private ArgumentCaptor<IBiometricEnabledOnKeyguardCallback>
            mBiometricEnabledCallbackArgCaptor;

    // Direct executor
    private final Executor mBackgroundExecutor = Runnable::run;
    private final Executor mMainExecutor = Runnable::run;
    private TestableLooper mTestableLooper;
    private Handler mHandler;
    private TestableKeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private MockitoSession mMockitoSession;
    private StatusBarStateController.StateListener mStatusBarStateListener;
    private IBiometricEnabledOnKeyguardCallback mBiometricEnabledOnKeyguardCallback;
    private FaceWakeUpTriggersConfig mFaceWakeUpTriggersConfig;
    private IFingerprintAuthenticatorsRegisteredCallback
            mFingerprintAuthenticatorsRegisteredCallback;
    private final InstanceId mKeyguardInstanceId = InstanceId.fakeInstanceId(999);

    @Before
    public void setup() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        when(mSessionTracker.getSessionId(SESSION_KEYGUARD)).thenReturn(mKeyguardInstanceId);

        when(mUserManager.isUserUnlocked(anyInt())).thenReturn(true);
        currentUserIsSystem();
        when(mStrongAuthTracker.getStub()).thenReturn(mock(IStrongAuthTracker.Stub.class));
        when(mStrongAuthTracker
                .isUnlockingWithBiometricAllowed(anyBoolean() /* isClass3Biometric */))
                .thenReturn(true);
        when(mTelephonyManager.getServiceStateForSubscriber(anyInt()))
                .thenReturn(new ServiceState());
        when(mLockPatternUtils.getLockSettings()).thenReturn(mLockSettings);
        when(mAuthController.isUdfpsEnrolled(anyInt())).thenReturn(false);
        when(mDevicePostureController.getDevicePosture()).thenReturn(DEVICE_POSTURE_UNKNOWN);

        mMockitoSession = ExtendedMockito.mockitoSession()
                .spyStatic(SubscriptionManager.class)
                .strictness(Strictness.WARN)
                .startMocking();
        ExtendedMockito.doReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                .when(SubscriptionManager::getDefaultSubscriptionId);
        when(mSelectedUserInteractor.getSelectedUserId()).thenReturn(mCurrentUserId);
        when(mSelectedUserInteractor.getSelectedUserId(anyBoolean())).thenReturn(mCurrentUserId);

        mContext.getOrCreateTestableResources().addOverride(
                com.android.systemui.res.R.integer.config_face_auth_supported_posture,
                DEVICE_POSTURE_UNKNOWN);
        mFaceWakeUpTriggersConfig = new FaceWakeUpTriggersConfig(
                mContext.getResources(),
                mGlobalSettings,
                mDumpManager
        );

        mContext.getOrCreateTestableResources().addOverride(com.android.systemui.res
                        .R.array.config_fingerprint_listen_on_occluding_activity_packages,
                new String[]{PKG_ALLOWING_FP_LISTEN_ON_OCCLUDING_ACTIVITY});

        mTestableLooper = TestableLooper.get(this);
        allowTestableLooperAsMainThread();

        final ContentResolver contentResolver = mContext.getContentResolver();
        ExtendedMockito.spyOn(contentResolver);
        doNothing().when(contentResolver)
                .registerContentObserver(any(Uri.class), anyBoolean(), any(ContentObserver.class),
                        anyInt());

        mKeyguardUpdateMonitor = new TestableKeyguardUpdateMonitor(mContext);
        setupBiometrics(mKeyguardUpdateMonitor);
        mKeyguardUpdateMonitor.setFaceAuthInteractor(mFaceAuthInteractor);
        verify(mFaceAuthInteractor).registerListener(mFaceAuthenticationListener.capture());
    }

    private void setupBiometrics(KeyguardUpdateMonitor keyguardUpdateMonitor)
            throws RemoteException {
        captureAuthenticatorsRegisteredCallbacks();
        when(mFaceAuthInteractor.isFaceAuthStrong()).thenReturn(false);
        setupFingerprintAuth(/* isClass3 */ true);

        verify(mBiometricManager)
                .registerEnabledOnKeyguardCallback(mBiometricEnabledCallbackArgCaptor.capture());
        mBiometricEnabledOnKeyguardCallback = mBiometricEnabledCallbackArgCaptor.getValue();
        biometricsEnabledForCurrentUser();

        mHandler = spy(keyguardUpdateMonitor.getHandler());
        try {
            FieldSetter.setField(keyguardUpdateMonitor,
                    KeyguardUpdateMonitor.class.getDeclaredField("mHandler"), mHandler);
        } catch (NoSuchFieldException e) {

        }
        verify(mStatusBarStateController).addCallback(mStatusBarStateListenerCaptor.capture());
        mStatusBarStateListener = mStatusBarStateListenerCaptor.getValue();
        mKeyguardUpdateMonitor.registerCallback(mTestCallback);

        mTestableLooper.processAllMessages();
        when(mAuthController.areAllFingerprintAuthenticatorsRegistered()).thenReturn(true);
    }

    private void captureAuthenticatorsRegisteredCallbacks() throws RemoteException {
        ArgumentCaptor<IFingerprintAuthenticatorsRegisteredCallback> fingerprintCaptor =
                ArgumentCaptor.forClass(IFingerprintAuthenticatorsRegisteredCallback.class);
        verify(mFingerprintManager).addAuthenticatorsRegisteredCallback(
                fingerprintCaptor.capture());
        mFingerprintAuthenticatorsRegisteredCallback = fingerprintCaptor.getValue();
        mFingerprintAuthenticatorsRegisteredCallback
                .onAllAuthenticatorsRegistered(mFingerprintSensorProperties);
    }

    private void setupFingerprintAuth(boolean isClass3) throws RemoteException {
        when(mAuthController.isFingerprintEnrolled(anyInt())).thenReturn(true);
        when(mFingerprintManager.isHardwareDetected()).thenReturn(true);
        when(mFingerprintManager.hasEnrolledTemplates(anyInt())).thenReturn(true);
        mFingerprintSensorProperties = List.of(
                createFingerprintSensorPropertiesInternal(TYPE_UDFPS_OPTICAL, isClass3));
        when(mFingerprintManager.getSensorPropertiesInternal()).thenReturn(
                mFingerprintSensorProperties);
        mFingerprintAuthenticatorsRegisteredCallback.onAllAuthenticatorsRegistered(
                mFingerprintSensorProperties);
        assertEquals(isClass3, mKeyguardUpdateMonitor.isFingerprintClass3());
    }

    private FingerprintSensorPropertiesInternal createFingerprintSensorPropertiesInternal(
            @FingerprintSensorProperties.SensorType int sensorType,
            boolean isClass3) {
        final List<ComponentInfoInternal> componentInfo =
                List.of(new ComponentInfoInternal("fingerprintSensor" /* componentId */,
                        "vendor/model/revision" /* hardwareVersion */,
                        "1.01" /* firmwareVersion */,
                        "00000001" /* serialNumber */, "" /* softwareVersion */));
        return new FingerprintSensorPropertiesInternal(
                FINGERPRINT_SENSOR_ID,
                isClass3 ? STRENGTH_STRONG : STRENGTH_CONVENIENCE,
                1 /* maxEnrollmentsPerUser */,
                componentInfo,
                sensorType,
                true /* resetLockoutRequiresHardwareAuthToken */);
    }

    @After
    public void tearDown() {
        if (mMockitoSession != null) {
            mMockitoSession.finishMocking();
        }
        cleanupKeyguardUpdateMonitor();
    }

    @Test
    public void testInitialBatteryLevelRequested() {
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.mBatteryStatus).isNotNull();
    }

    @Test
    public void testReceiversRegistered() {
        verify(mBroadcastDispatcher, atLeastOnce()).registerReceiverWithHandler(
                eq(mKeyguardUpdateMonitor.mBroadcastReceiver),
                any(IntentFilter.class), any(Handler.class));
        verify(mBroadcastDispatcher, atLeastOnce()).registerReceiverWithHandler(
                eq(mKeyguardUpdateMonitor.mBroadcastAllReceiver),
                any(IntentFilter.class), any(Handler.class), eq(UserHandle.ALL));
    }

    @Test
    public void testSimStateInitialized() {
        cleanupKeyguardUpdateMonitor();
        final int subId = 3;
        final int state = TelephonyManager.SIM_STATE_ABSENT;

        when(mTelephonyManager.getActiveModemCount()).thenReturn(1);
        when(mTelephonyManager.getSimState(anyInt())).thenReturn(state);
        when(mSubscriptionManager.getSubscriptionIds(anyInt())).thenReturn(new int[]{subId});

        KeyguardUpdateMonitor testKUM = new TestableKeyguardUpdateMonitor(mContext);

        mTestableLooper.processAllMessages();

        assertThat(testKUM.getSimState(subId)).isEqualTo(state);
    }

    @Test
    public void testIgnoresSimStateCallback_rebroadcast() {
        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);

        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(), intent);
        mTestableLooper.processAllMessages();
        Assert.assertTrue("onSimStateChanged not called",
                mKeyguardUpdateMonitor.hasSimStateJustChanged());

        intent.putExtra(Intent.EXTRA_REBROADCAST_ON_UNLOCK, true);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(), intent);
        mTestableLooper.processAllMessages();
        Assert.assertFalse("onSimStateChanged should have been skipped",
                mKeyguardUpdateMonitor.hasSimStateJustChanged());
    }

    @Test
    public void testTelephonyCapable_BootInitState() {
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isFalse();
    }

    @Test
    public void testTelephonyCapable_SimState_Absent() {
        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_SIM_STATE,
                Intent.SIM_STATE_ABSENT);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, false));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isTrue();
    }

    @Test
    public void testTelephonyCapable_SimState_CardIOError() {
        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_SIM_STATE,
                Intent.SIM_STATE_CARD_IO_ERROR);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, false));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isTrue();
    }

    @Test
    public void testTelephonyCapable_SimInvalid_ServiceState_InService() {
        // SERVICE_STATE - IN_SERVICE, but SIM_STATE is invalid TelephonyCapable should be False
        Intent intent = new Intent(Intent.ACTION_SERVICE_STATE);
        Bundle data = new Bundle();
        ServiceState state = new ServiceState();
        state.setState(ServiceState.STATE_IN_SERVICE);
        state.fillInNotifierBundle(data);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext()
                , putPhoneInfo(intent, data, false));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isFalse();
    }

    @Test
    public void testTelephonyCapable_SimValid_ServiceState_PowerOff() {
        // Simulate AirplaneMode case, SERVICE_STATE - POWER_OFF, check TelephonyCapable False
        // Only receive ServiceState callback IN_SERVICE -> OUT_OF_SERVICE -> POWER_OFF
        Intent intent = new Intent(Intent.ACTION_SERVICE_STATE);
        intent.putExtra(Intent.EXTRA_SIM_STATE
                , Intent.SIM_STATE_LOADED);
        Bundle data = new Bundle();
        ServiceState state = new ServiceState();
        state.setState(ServiceState.STATE_POWER_OFF);
        state.fillInNotifierBundle(data);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext()
                , putPhoneInfo(intent, data, true));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isTrue();
    }

    /* Normal SIM inserted flow
     * ServiceState:    ---OutOfServie----->PowerOff->OutOfServie--->InService
     * SimState:        ----NOT_READY---->READY----------------------LOADED>>>
     * Subscription:    --------null---->null--->"Chunghwa Telecom"-------->>>
     * System:          -------------------------------BOOT_COMPLETED------>>>
     * TelephonyCapable:(F)-(F)-(F)-(F)-(F)-(F)-(F)-(F)-(F)-(F)------(T)-(T)>>
     */
    @Test
    public void testTelephonyCapable_BootInitState_ServiceState_OutOfService() {
        Intent intent = new Intent(Intent.ACTION_SERVICE_STATE);
        Bundle data = new Bundle();
        ServiceState state = new ServiceState();
        state.setState(ServiceState.STATE_OUT_OF_SERVICE);
        state.fillInNotifierBundle(data);
        intent.putExtras(data);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext()
                , putPhoneInfo(intent, data, false));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isFalse();
    }

    @Test
    public void testTelephonyCapable_BootInitState_SimState_NotReady() {
        Bundle data = new Bundle();
        ServiceState state = new ServiceState();
        state.setState(ServiceState.STATE_OUT_OF_SERVICE);
        state.fillInNotifierBundle(data);
        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_SIM_STATE
                , Intent.SIM_STATE_NOT_READY);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext()
                , putPhoneInfo(intent, data, false));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isFalse();
    }

    @Test
    public void testTelephonyCapable_BootInitState_SimState_Ready() {
        Bundle data = new Bundle();
        ServiceState state = new ServiceState();
        state.setState(ServiceState.STATE_OUT_OF_SERVICE);
        state.fillInNotifierBundle(data);
        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_SIM_STATE
                , Intent.SIM_STATE_READY);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext()
                , putPhoneInfo(intent, data, false));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isFalse();
    }

    @Test
    public void testTelephonyCapable_BootInitState_ServiceState_PowerOff() {
        Intent intent = new Intent(Intent.ACTION_SERVICE_STATE);
        Bundle data = new Bundle();
        ServiceState state = new ServiceState();
        state.setState(ServiceState.STATE_POWER_OFF);
        state.fillInNotifierBundle(data);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext()
                , putPhoneInfo(intent, data, false));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isFalse();
    }

    @Test
    public void testTelephonyCapable_SimValid_ServiceState_InService() {
        Intent intent = new Intent(Intent.ACTION_SERVICE_STATE);
        Bundle data = new Bundle();
        ServiceState state = new ServiceState();
        state.setState(ServiceState.STATE_IN_SERVICE);
        state.fillInNotifierBundle(data);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext()
                , putPhoneInfo(intent, data, true));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isTrue();
    }

    @Test
    public void testTelephonyCapable_SimValid_SimState_Loaded() {
        Bundle data = new Bundle();
        ServiceState state = new ServiceState();
        state.setState(ServiceState.STATE_IN_SERVICE);
        state.fillInNotifierBundle(data);
        Intent intentSimState = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intentSimState.putExtra(Intent.EXTRA_SIM_STATE
                , Intent.SIM_STATE_LOADED);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext()
                , putPhoneInfo(intentSimState, data, true));
        mTestableLooper.processAllMessages();
        // Even SimState Loaded, still need ACTION_SERVICE_STATE turn on mTelephonyCapable
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isFalse();

        Intent intentServiceState = new Intent(Intent.ACTION_SERVICE_STATE);
        intentSimState.putExtra(Intent.EXTRA_SIM_STATE
                , Intent.SIM_STATE_LOADED);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext()
                , putPhoneInfo(intentServiceState, data, true));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isTrue();
    }

    @Test
    public void serviceProvidersUpdated_broadcastTriggersInfoRefresh() {
        // The callback is invoked once on init
        verify(mTestCallback, times(1)).onRefreshCarrierInfo();

        // WHEN the SERVICE_PROVIDERS_UPDATED broadcast is sent
        Intent intent = new Intent(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED);
        intent.putExtra(TelephonyManager.EXTRA_SPN, "spn");
        intent.putExtra(TelephonyManager.EXTRA_PLMN, "plmn");
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, true));
        mTestableLooper.processAllMessages();

        // THEN verify keyguardUpdateMonitorCallback receives a refresh callback
        // Note that we have times(2) here because it's been called once already
        verify(mTestCallback, times(2)).onRefreshCarrierInfo();
    }

    @Test
    public void testHandleSimStateChange_Unknown() {
        Intent intent = new Intent(Intent.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_SIM_STATE, Intent.SIM_STATE_UNKNOWN);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, false));
        mTestableLooper.processAllMessages();
        Assert.assertEquals(TelephonyManager.SIM_STATE_UNKNOWN,
                mKeyguardUpdateMonitor.getCachedSimState());
    }

    @Test
    public void testHandleSimStateChange_Absent() {
        Intent intent = new Intent(Intent.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_SIM_STATE, Intent.SIM_STATE_ABSENT);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, false));
        mTestableLooper.processAllMessages();
        Assert.assertEquals(TelephonyManager.SIM_STATE_ABSENT,
                mKeyguardUpdateMonitor.getCachedSimState());
    }

    @Test
    public void testHandleSimStateChange_CardIOError() {
        Intent intent = new Intent(Intent.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_SIM_STATE, Intent.SIM_STATE_CARD_IO_ERROR);
        intent.putExtra(Intent.EXTRA_SIM_LOCKED_REASON, Intent.SIM_STATE_CARD_IO_ERROR);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, false));
        mTestableLooper.processAllMessages();
        Assert.assertEquals(TelephonyManager.SIM_STATE_CARD_IO_ERROR,
                mKeyguardUpdateMonitor.getCachedSimState());
    }

    @Test
    public void testHandleSimStateChange_CardRestricted() {
        Intent intent = new Intent(Intent.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_SIM_STATE, Intent.SIM_STATE_CARD_RESTRICTED);
        intent.putExtra(Intent.EXTRA_SIM_LOCKED_REASON, Intent.SIM_STATE_CARD_RESTRICTED);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, false));
        mTestableLooper.processAllMessages();
        Assert.assertEquals(TelephonyManager.SIM_STATE_CARD_RESTRICTED,
                mKeyguardUpdateMonitor.getCachedSimState());
    }

    @Test
    public void testHandleSimStateChange_Locked() {
        Intent intent = new Intent(Intent.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_SIM_STATE, Intent.SIM_STATE_LOCKED);

        // locked on PIN1
        intent.putExtra(Intent.EXTRA_SIM_LOCKED_REASON, Intent.SIM_LOCKED_ON_PIN);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, true));
        mTestableLooper.processAllMessages();
        Assert.assertEquals(TelephonyManager.SIM_STATE_PIN_REQUIRED,
                mKeyguardUpdateMonitor.getCachedSimState());

        // locked on PUK1
        intent.putExtra(Intent.EXTRA_SIM_LOCKED_REASON, Intent.SIM_LOCKED_ON_PUK);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, true));
        mTestableLooper.processAllMessages();
        Assert.assertEquals(TelephonyManager.SIM_STATE_PUK_REQUIRED,
                mKeyguardUpdateMonitor.getCachedSimState());

        // locked on network personalization
        intent.putExtra(Intent.EXTRA_SIM_LOCKED_REASON, Intent.SIM_LOCKED_NETWORK);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, true));
        mTestableLooper.processAllMessages();
        Assert.assertEquals(TelephonyManager.SIM_STATE_NETWORK_LOCKED,
                mKeyguardUpdateMonitor.getCachedSimState());

        // permanently disabled due to puk fails
        intent.putExtra(Intent.EXTRA_SIM_LOCKED_REASON, Intent.SIM_ABSENT_ON_PERM_DISABLED);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, true));
        mTestableLooper.processAllMessages();
        Assert.assertEquals(TelephonyManager.SIM_STATE_PERM_DISABLED,
                mKeyguardUpdateMonitor.getCachedSimState());
    }

    @Test
    public void testHandleSimStateChange_NotReady() {
        Intent intent = new Intent(Intent.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(Intent.EXTRA_SIM_STATE, Intent.SIM_STATE_NOT_READY);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, false));
        mTestableLooper.processAllMessages();
        Assert.assertEquals(TelephonyManager.SIM_STATE_NOT_READY,
                mKeyguardUpdateMonitor.getCachedSimState());
    }

    @Test
    public void testHandleSimStateChange_Ready() {
        Intent intent = new Intent(Intent.ACTION_SIM_STATE_CHANGED);

        // ICC IMSI is ready in property
        intent.putExtra(Intent.EXTRA_SIM_STATE, Intent.SIM_STATE_IMSI);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, false));
        mTestableLooper.processAllMessages();
        Assert.assertEquals(TelephonyManager.SIM_STATE_READY,
                mKeyguardUpdateMonitor.getCachedSimState());

        // ICC is ready to access
        intent.putExtra(Intent.EXTRA_SIM_STATE, Intent.SIM_STATE_READY);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, false));
        mTestableLooper.processAllMessages();
        Assert.assertEquals(TelephonyManager.SIM_STATE_READY,
                mKeyguardUpdateMonitor.getCachedSimState());

        // all ICC records, including IMSI, are loaded
        intent.putExtra(Intent.EXTRA_SIM_STATE, Intent.SIM_STATE_LOADED);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, true));
        mTestableLooper.processAllMessages();
        Assert.assertEquals(TelephonyManager.SIM_STATE_READY,
                mKeyguardUpdateMonitor.getCachedSimState());
    }

    @Test
    public void testTriesToAuthenticateFingerprint_whenKeyguard() {
        mKeyguardUpdateMonitor.dispatchStartedGoingToSleep(0 /* why */);
        mTestableLooper.processAllMessages();

        verifyFingerprintAuthenticateCall();
        verifyFingerprintDetectNeverCalled();
    }

    @Test
    public void test_doesNotTryToAuthenticateFingerprint_whenAuthenticatorsNotRegistered() {
        when(mAuthController.areAllFingerprintAuthenticatorsRegistered()).thenReturn(false);

        mKeyguardUpdateMonitor.dispatchStartedGoingToSleep(0 /* why */);
        mTestableLooper.processAllMessages();

        verifyFingerprintAuthenticateNeverCalled();
        verifyFingerprintDetectNeverCalled();
    }

    @Test
    public void testOnlyDetectFingerprint_whenFingerprintUnlockNotAllowed() {
        givenDetectFingerprintWithClearingFingerprintManagerInvocations();

        verifyFingerprintAuthenticateNeverCalled();
        verifyFingerprintDetectCall();
    }

    @Test
    public void whenDetectFingerprint_biometricDetectCallback() {
        ArgumentCaptor<FingerprintManager.FingerprintDetectionCallback> fpDetectCallbackCaptor =
                ArgumentCaptor.forClass(FingerprintManager.FingerprintDetectionCallback.class);

        givenDetectFingerprintWithClearingFingerprintManagerInvocations();
        verify(mFingerprintManager).detectFingerprint(
                any(), fpDetectCallbackCaptor.capture(), any());
        fpDetectCallbackCaptor.getValue().onFingerprintDetected(0, 0, true);

        // THEN verify keyguardUpdateMonitorCallback receives a detect callback
        // and NO authenticate callbacks
        verify(mTestCallback).onBiometricDetected(
                eq(0), eq(BiometricSourceType.FINGERPRINT), eq(true));
        verify(mTestCallback, never()).onBiometricAuthenticated(
                anyInt(), any(), anyBoolean());
    }

    @Test
    public void whenDetectFingerprint_detectError() {
        ArgumentCaptor<FingerprintManager.FingerprintDetectionCallback> fpDetectCallbackCaptor =
                ArgumentCaptor.forClass(FingerprintManager.FingerprintDetectionCallback.class);

        givenDetectFingerprintWithClearingFingerprintManagerInvocations();
        verify(mFingerprintManager).detectFingerprint(
                any(), fpDetectCallbackCaptor.capture(), any());
        fpDetectCallbackCaptor.getValue().onDetectionError(/* msgId */ 10);

        // THEN verify keyguardUpdateMonitorCallback receives a biometric error
        verify(mTestCallback).onBiometricError(
                eq(10), eq(""), eq(BiometricSourceType.FINGERPRINT));
        verify(mTestCallback, never()).onBiometricAuthenticated(
                anyInt(), any(), anyBoolean());
    }

    @Test
    public void whenDetectFace_biometricDetectCallback() {
        mFaceAuthenticationListener.getValue().onDetectionStatusChanged(
                new FaceDetectionStatus(0, 0, false, 0L));

        // THEN verify keyguardUpdateMonitorCallback receives a detect callback
        // and NO authenticate callbacks
        verify(mTestCallback).onBiometricDetected(
                eq(0), eq(BiometricSourceType.FACE), eq(false));
        verify(mTestCallback, never()).onBiometricAuthenticated(
                anyInt(), any(), anyBoolean());
    }

    @Test
    public void testUnlockingWithFaceAllowed_strongAuthTrackerUnlockingWithBiometricAllowed() {
        // GIVEN unlocking with biometric is allowed
        primaryAuthNotRequiredByStrongAuthTracker();

        // THEN unlocking with face and fp is allowed
        Assert.assertTrue(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                BiometricSourceType.FACE));
        Assert.assertTrue(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                BiometricSourceType.FINGERPRINT));
    }

    @Test
    public void testUnlockingWithFaceAllowed_strongAuthTrackerUnlockingWithBiometricNotAllowed() {
        // GIVEN unlocking with biometric is not allowed
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);

        // THEN unlocking with face is not allowed
        Assert.assertFalse(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                BiometricSourceType.FACE));
    }

    @Test
    public void class3FaceLockOut_lockOutClass3Fingerprint() throws RemoteException {
        when(mFaceAuthInteractor.isFaceAuthStrong()).thenReturn(true);
        when(mFaceAuthInteractor.isFaceAuthEnabledAndEnrolled()).thenReturn(true);

        setupFingerprintAuth(/* isClass3 */ true);

        // GIVEN primary auth is not required by StrongAuthTracker
        primaryAuthNotRequiredByStrongAuthTracker();

        // WHEN face (class 3) is lock out
        faceAuthLockOut();

        // THEN unlocking with fingerprint is not allowed
        Assert.assertFalse(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                BiometricSourceType.FINGERPRINT));
    }

    @Test
    public void class1FaceLockOut_doesNotLockOutClass3Fingerprint() throws RemoteException {
        when(mFaceAuthInteractor.isFaceAuthStrong()).thenReturn(false);
        setupFingerprintAuth(/* isClass3 */ true);

        // GIVEN primary auth is not required by StrongAuthTracker
        primaryAuthNotRequiredByStrongAuthTracker();

        // WHEN face (class 1) is lock out
        faceAuthLockOut();

        // THEN unlocking with fingerprint is still allowed
        Assert.assertTrue(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                BiometricSourceType.FINGERPRINT));
    }

    @Test
    public void testUnlockingWithFpAllowed_strongAuthTrackerUnlockingWithBiometricNotAllowed() {
        // GIVEN unlocking with biometric is not allowed
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);

        // THEN unlocking with fingerprint is not allowed
        Assert.assertFalse(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                BiometricSourceType.FINGERPRINT));
    }

    @Test
    public void testUnlockingWithFpAllowed_fingerprintLockout() {
        // GIVEN unlocking with biometric is allowed
        primaryAuthNotRequiredByStrongAuthTracker();

        // WHEN fingerprint is lock out
        fingerprintErrorTemporaryLockOut();

        // THEN unlocking with fingerprint is not allowed
        Assert.assertFalse(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                BiometricSourceType.FINGERPRINT));
    }

    @Test
    public void trustAgentHasTrust() {
        // WHEN user has trust
        givenSelectedUserCanSkipBouncerFromTrustedState();

        // THEN user is considered as "having trust" and bouncer can be skipped
        Assert.assertTrue(mKeyguardUpdateMonitor.getUserHasTrust(
                mSelectedUserInteractor.getSelectedUserId()));
        Assert.assertTrue(mKeyguardUpdateMonitor.getUserCanSkipBouncer(
                mSelectedUserInteractor.getSelectedUserId()));
    }

    @Test
    public void testOnEnabledTrustAgentsChangedCallback() {
        final Random random = new Random();
        final int userId = random.nextInt();
        final KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);

        mKeyguardUpdateMonitor.registerCallback(callback);
        mKeyguardUpdateMonitor.onEnabledTrustAgentsChanged(userId);

        verify(callback).onEnabledTrustAgentsChanged(eq(userId));
    }

    @Test
    public void trustAgentHasTrust_fingerprintLockout() {
        // GIVEN user has trust
        givenSelectedUserCanSkipBouncerFromTrustedState();
        Assert.assertTrue(mKeyguardUpdateMonitor.getUserHasTrust(
                mSelectedUserInteractor.getSelectedUserId()));

        // WHEN fingerprint is lock out
        fingerprintErrorTemporaryLockOut();

        // THEN user is NOT considered as "having trust" and bouncer cannot be skipped
        Assert.assertFalse(mKeyguardUpdateMonitor.getUserHasTrust(
                mSelectedUserInteractor.getSelectedUserId()));
        Assert.assertFalse(mKeyguardUpdateMonitor.getUserCanSkipBouncer(
                mSelectedUserInteractor.getSelectedUserId()));
    }

    @Test
    public void noFpListeningWhenKeyguardIsOccluded_unlessAlternateBouncerShowing() {
        // GIVEN device is awake but occluded
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mKeyguardUpdateMonitor.setKeyguardShowing(false, true);

        // THEN fingerprint shouldn't listen
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isFalse();
        verifyFingerprintAuthenticateNeverCalled();
        // WHEN alternate bouncer is shown
        mKeyguardUpdateMonitor.setKeyguardShowing(true, true);
        mKeyguardUpdateMonitor.setAlternateBouncerShowing(true);

        // THEN make sure FP listening begins
        verifyFingerprintAuthenticateCall();
    }

    @Test
    public void fpStopsListeningWhenBiometricPromptShows_resumesOnBpHidden() {
        // verify AuthController.Callback is added:
        ArgumentCaptor<AuthController.Callback> captor = ArgumentCaptor.forClass(
                AuthController.Callback.class);
        verify(mAuthController).addCallback(captor.capture());
        AuthController.Callback callback = captor.getValue();

        // GIVEN keyguard showing
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mKeyguardUpdateMonitor.setKeyguardShowing(true, false);

        // THEN fingerprint should listen
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isTrue();

        // WHEN biometric prompt is shown
        callback.onBiometricPromptShown();

        // THEN shouldn't listen for fingerprint
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isFalse();

        // WHEN biometric prompt is dismissed
        callback.onBiometricPromptDismissed();

        // THEN we should listen for fingerprint
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isTrue();
    }

    @Test
    public void testFingerprintPowerPressed_restartsFingerprintListeningStateWithDelay() {
        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback
                .onAuthenticationError(FingerprintManager.BIOMETRIC_ERROR_POWER_PRESSED, "");

        // THEN doesn't authenticate immediately
        verifyFingerprintAuthenticateNeverCalled();

        // WHEN all messages (with delays) are processed
        mTestableLooper.moveTimeForward(HAL_POWER_PRESS_TIMEOUT);
        mTestableLooper.processAllMessages();

        // THEN fingerprint manager attempts to authenticate again
        verifyFingerprintAuthenticateCall();
    }

    @Test
    public void testFaceAndFingerprintLockout_onlyFace() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        keyguardIsVisible();

        faceAuthLockOut();

        verify(mLockPatternUtils, never()).requireStrongAuth(anyInt(), anyInt());
    }

    @Test
    public void testFaceAndFingerprintLockout_onlyFingerprint() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        keyguardIsVisible();

        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback
                .onAuthenticationError(FINGERPRINT_ERROR_LOCKOUT_PERMANENT, "");

        verify(mLockPatternUtils).requireStrongAuth(anyInt(), anyInt());
    }

    @Test
    public void testFaceAndFingerprintLockout() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        keyguardIsVisible();

        faceAuthLockOut();
        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback
                .onAuthenticationError(FINGERPRINT_ERROR_LOCKOUT_PERMANENT, "");

        verify(mLockPatternUtils).requireStrongAuth(anyInt(), anyInt());
    }

    @Test
    public void testGetUserCanSkipBouncer_whenFace() {
        int user = mSelectedUserInteractor.getSelectedUserId();
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(true /* isClass3Biometric */))
                .thenReturn(true);
        when(mFaceAuthInteractor.isFaceAuthStrong()).thenReturn(true);
        when(mFaceAuthInteractor.isAuthenticated()).thenReturn(true);

        assertThat(mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)).isTrue();
    }

    @Test
    public void testGetUserCanSkipBouncer_whenFace_nonStrongAndDisallowed() {
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(false /* isClass3Biometric */))
                .thenReturn(false);
        int user = mSelectedUserInteractor.getSelectedUserId();
        when(mFaceAuthInteractor.isFaceAuthStrong()).thenReturn(false);
        when(mFaceAuthInteractor.isAuthenticated()).thenReturn(true);

        assertThat(mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)).isFalse();
    }

    @Test
    public void testGetUserCanSkipBouncer_whenFingerprint() {
        int user = mSelectedUserInteractor.getSelectedUserId();
        mKeyguardUpdateMonitor.onFingerprintAuthenticated(user, true /* isClass3Biometric */);
        assertThat(mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)).isTrue();
    }

    @Test
    public void testGetUserCanSkipBouncer_whenFingerprint_nonStrongAndDisallowed() {
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(false /* isClass3Biometric */))
                .thenReturn(false);
        int user = mSelectedUserInteractor.getSelectedUserId();
        mKeyguardUpdateMonitor.onFingerprintAuthenticated(user, false /* isClass3Biometric */);
        assertThat(mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)).isFalse();
    }

    @Test
    public void testBiometricsCleared_whenUserSwitches() {
        final BiometricAuthenticated dummyAuthentication =
                new BiometricAuthenticated(true /* authenticated */, true /* strong */);
        mKeyguardUpdateMonitor.mUserFingerprintAuthenticated.put(0 /* user */, dummyAuthentication);
        assertThat(mKeyguardUpdateMonitor.mUserFingerprintAuthenticated.size()).isEqualTo(1);

        mKeyguardUpdateMonitor.handleUserSwitching(10 /* user */, () -> {
        });
        assertThat(mKeyguardUpdateMonitor.mUserFingerprintAuthenticated.size()).isEqualTo(0);
    }

    @Test
    public void testMultiUserJankMonitor_whenUserSwitches() {
        mKeyguardUpdateMonitor.handleUserSwitchComplete(10 /* user */);
        verify(mInteractionJankMonitor).end(InteractionJankMonitor.CUJ_USER_SWITCH);
        verify(mLatencyTracker).onActionEnd(LatencyTracker.ACTION_USER_SWITCH);
    }

    @Test
    public void testMultiUserLockoutChanged_whenUserSwitches() {
        testMultiUserLockout_whenUserSwitches(BiometricConstants.BIOMETRIC_LOCKOUT_PERMANENT);
    }

    @Test
    public void testMultiUserLockoutNotChanged_whenUserSwitches() {
        testMultiUserLockout_whenUserSwitches(BiometricConstants.BIOMETRIC_LOCKOUT_NONE);
    }

    private void testMultiUserLockout_whenUserSwitches(
            @BiometricConstants.LockoutMode int fingerprintLockoutMode) {
        final int newUser = 12;
        final boolean fpLockOut =
                fingerprintLockoutMode != BiometricConstants.BIOMETRIC_LOCKOUT_NONE;

        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        keyguardIsVisible();

        verifyFingerprintAuthenticateCall();

        when(mFingerprintManager.getLockoutModeForUser(eq(FINGERPRINT_SENSOR_ID), eq(newUser)))
                .thenReturn(fingerprintLockoutMode);
        final CancellationSignal fpCancel = spy(mKeyguardUpdateMonitor.mFingerprintCancelSignal);
        mKeyguardUpdateMonitor.mFingerprintCancelSignal = fpCancel;
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        mKeyguardUpdateMonitor.handleUserSwitchComplete(newUser);
        mTestableLooper.processAllMessages();

        // THEN fingerprint listening are always cancelled immediately
        verify(fpCancel).cancel();
        verify(callback).onBiometricRunningStateChanged(
                eq(false), eq(BiometricSourceType.FINGERPRINT));

        // THEN locked out states are updated
        assertThat(mKeyguardUpdateMonitor.isFingerprintLockedOut()).isEqualTo(fpLockOut);

        // Fingerprint should be cancelled on lockout if going to lockout state, else
        // restarted if it's not
        assertThat(mKeyguardUpdateMonitor.mFingerprintRunningState)
                .isEqualTo(BIOMETRIC_STATE_CANCELLING_RESTARTING);
    }

    @Test
    public void testGetUserCanSkipBouncer_whenTrust() {
        int user = mSelectedUserInteractor.getSelectedUserId();
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */, true /* newlyUnlocked */,
                user, 0 /* flags */, new ArrayList<>());
        assertThat(mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)).isTrue();
    }

    @Test
    public void testGetSubscriptionInfo_whenInGroupedSubWithOpportunistic() {
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        list.add(TEST_SUBSCRIPTION_2);
        when(mSubscriptionManager.getCompleteActiveSubscriptionInfoList()).thenReturn(list);
        mKeyguardUpdateMonitor.mPhoneStateListener.onActiveDataSubscriptionIdChanged(
                TEST_SUBSCRIPTION_2.getSubscriptionId());
        mTestableLooper.processAllMessages();

        List<SubscriptionInfo> listToVerify = mKeyguardUpdateMonitor
                .getFilteredSubscriptionInfo();
        assertThat(listToVerify.size()).isEqualTo(1);
        assertThat(listToVerify.get(0)).isEqualTo(TEST_SUBSCRIPTION_2);
    }

    @Test
    public void testActiveSubscriptionBecomesInactive() {
        List<SubscriptionInfo> list = new ArrayList<>();
        list.add(TEST_SUBSCRIPTION);
        when(mSubscriptionManager.getCompleteActiveSubscriptionInfoList()).thenReturn(list);
        mKeyguardUpdateMonitor.mPhoneStateListener.onActiveDataSubscriptionIdChanged(
                TEST_SUBSCRIPTION.getSubscriptionId());
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mSimDatas.get(TEST_SUBSCRIPTION.getSubscriptionId()))
                .isNotNull();

        when(mSubscriptionManager.getCompleteActiveSubscriptionInfoList()).thenReturn(null);
        mKeyguardUpdateMonitor.mPhoneStateListener.onActiveDataSubscriptionIdChanged(
                SubscriptionManager.INVALID_SUBSCRIPTION_ID);
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.mSimDatas.get(TEST_SUBSCRIPTION.getSubscriptionId()))
                .isNull();
        assertThat(mKeyguardUpdateMonitor.mSimDatas.get(
                SubscriptionManager.INVALID_SUBSCRIPTION_ID)).isNull();
    }

    @Test
    public void testIsUserUnlocked() {
        // mUserManager will report the user as unlocked on @Before
        assertThat(
                mKeyguardUpdateMonitor.isUserUnlocked(mSelectedUserInteractor.getSelectedUserId()))
                .isTrue();
        // Invalid user should not be unlocked.
        int randomUser = 99;
        assertThat(mKeyguardUpdateMonitor.isUserUnlocked(randomUser)).isFalse();
    }

    @Test
    public void testTrustUsuallyManaged_whenTrustChanges() {
        int user = mSelectedUserInteractor.getSelectedUserId();
        when(mTrustManager.isTrustUsuallyManaged(eq(user))).thenReturn(true);
        mKeyguardUpdateMonitor.onTrustManagedChanged(false /* managed */, user);
        assertThat(mKeyguardUpdateMonitor.isTrustUsuallyManaged(user)).isTrue();
    }

    @Test
    public void testTrustUsuallyManaged_resetWhenUserIsRemoved() {
        int user = mSelectedUserInteractor.getSelectedUserId();
        when(mTrustManager.isTrustUsuallyManaged(eq(user))).thenReturn(true);
        mKeyguardUpdateMonitor.onTrustManagedChanged(false /* managed */, user);
        assertThat(mKeyguardUpdateMonitor.isTrustUsuallyManaged(user)).isTrue();

        mKeyguardUpdateMonitor.handleUserRemoved(user);
        assertThat(mKeyguardUpdateMonitor.isTrustUsuallyManaged(user)).isFalse();
    }

    @Test
    public void testSecondaryLockscreenRequirement() {
        when(mSelectedUserInteractor.getSelectedUserId()).thenReturn(UserHandle.myUserId());
        when(mUserTracker.getUserId()).thenReturn(UserHandle.myUserId());
        int user = mSelectedUserInteractor.getSelectedUserId();
        String packageName = "fake.test.package";
        String cls = "FakeService";
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.packageName = packageName;
        serviceInfo.name = cls;
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = serviceInfo;
        when(mPackageManager.resolveService(any(Intent.class), eq(0))).thenReturn(resolveInfo);
        when(mDevicePolicyManager.isSecondaryLockscreenEnabled(eq(UserHandle.of(user))))
                .thenReturn(true, false);
        when(mDevicePolicyManager.getProfileOwnerOrDeviceOwnerSupervisionComponent(
                UserHandle.of(user)))
                .thenReturn(new ComponentName(packageName, cls));

        // Initially null.
        assertThat(mKeyguardUpdateMonitor.getSecondaryLockscreenRequirement(user)).isNull();

        // Set non-null after DPM change.
        setBroadcastReceiverPendingResult(mKeyguardUpdateMonitor.mBroadcastAllReceiver);
        Intent intent = new Intent(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mKeyguardUpdateMonitor.mBroadcastAllReceiver.onReceive(getContext(), intent);
        mTestableLooper.processAllMessages();

        Intent storedIntent = mKeyguardUpdateMonitor.getSecondaryLockscreenRequirement(user);
        assertThat(storedIntent.getComponent().getClassName()).isEqualTo(cls);
        assertThat(storedIntent.getComponent().getPackageName()).isEqualTo(packageName);

        // Back to null after another DPM change.
        mKeyguardUpdateMonitor.mBroadcastAllReceiver.onReceive(getContext(), intent);
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.getSecondaryLockscreenRequirement(user)).isNull();
    }

    @Test
    public void testRegisterAuthControllerCallback() {
        assertThat(mKeyguardUpdateMonitor.isUdfpsEnrolled()).isFalse();

        // verify AuthController.Callback is added:
        ArgumentCaptor<AuthController.Callback> captor = ArgumentCaptor.forClass(
                AuthController.Callback.class);
        verify(mAuthController).addCallback(captor.capture());
        AuthController.Callback callback = captor.getValue();

        // WHEN udfps is now enrolled
        when(mAuthController.isUdfpsEnrolled(anyInt())).thenReturn(true);
        callback.onEnrollmentsChanged(TYPE_FINGERPRINT);

        // THEN isUdfspEnrolled is TRUE
        assertThat(mKeyguardUpdateMonitor.isUdfpsEnrolled()).isTrue();
    }


    @Test
    public void testStartUdfpsServiceBeginsOnKeyguard() {
        // GIVEN
        // - status bar state is on the keyguard
        // - user has authenticated since boot
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);
        when(mStrongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);

        // THEN we should listen for udfps
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(true);
    }

    @Test
    public void listenForFingerprint_whenOccludingAppPkgOnAllowlist()
            throws RemoteException {
        // GIVEN keyguard isn't visible (app occluding)
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mKeyguardUpdateMonitor.setKeyguardShowing(true, true);
        when(mStrongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);

        // GIVEN the top activity is from a package that allows fingerprint listening over its
        // occluding activities
        setTopStandardActivity(PKG_ALLOWING_FP_LISTEN_ON_OCCLUDING_ACTIVITY);
        onTaskStackChanged();

        // THEN we SHOULD listen for non-UDFPS fingerprint
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isEqualTo(true);

        // THEN we should listen for udfps (hiding mechanism to actually auth is
        // controlled by UdfpsKeyguardViewController)
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(true);
    }

    @Test
    public void doNotListenForFingerprint_whenOccludingAppPkgNotOnAllowlist()
            throws RemoteException {
        // GIVEN keyguard isn't visible (app occluding)
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mKeyguardUpdateMonitor.setKeyguardShowing(true, true);
        when(mStrongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);

        // GIVEN top activity is not in the allowlist for listening to fp over occluding activities
        setTopStandardActivity("notInAllowList");

        // THEN we should not listen for non-UDFPS fingerprint
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isEqualTo(false);

        // THEN we should listen for udfps (hiding mechanism to actually auth is
        // controlled by UdfpsKeyguardViewController)
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(true);
    }

    @Test
    public void testOccludingAppFingerprintListeningState() {
        // GIVEN keyguard isn't visible (app occluding)
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mKeyguardUpdateMonitor.setKeyguardShowing(true, true);
        when(mStrongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);

        // THEN we shouldn't listen for fingerprints
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isEqualTo(false);

        // THEN we should listen for udfps (hiding of mechanism to actually auth is
        // controlled by UdfpsKeyguardViewController)
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(true);
    }

    @Test
    public void testOccludingAppRequestsFingerprint() {
        // GIVEN keyguard isn't visible (app occluding)
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mKeyguardUpdateMonitor.setKeyguardShowing(true, true);

        // WHEN an occluding app requests fp
        mKeyguardUpdateMonitor.requestFingerprintAuthOnOccludingApp(true);

        // THEN we should listen for fingerprints
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isEqualTo(true);

        // WHEN an occluding app stops requesting fp
        mKeyguardUpdateMonitor.requestFingerprintAuthOnOccludingApp(false);

        // THEN we shouldn't listen for fingeprints
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isEqualTo(false);
    }

    @Test
    public void testStartUdfpsServiceNoAuthenticationSinceLastBoot() {
        // GIVEN status bar state is on the keyguard
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);

        // WHEN user hasn't authenticated since last boot, cannot unlock with FP
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);

        // THEN we shouldn't listen for udfps
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(false);
    }

    @Test
    public void testStartUdfpsServiceStrongAuthRequiredAfterTimeout() {
        // GIVEN status bar state is on the keyguard
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);

        // WHEN user loses smart unlock trust
        when(mStrongAuthTracker.getStrongAuthForUser(mSelectedUserInteractor.getSelectedUserId()))
                .thenReturn(SOME_AUTH_REQUIRED_AFTER_USER_REQUEST);

        // THEN we should still listen for udfps
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(true);
    }

    @Test
    public void testShouldNotListenForUdfps_whenTrustEnabled() {
        // GIVEN a "we should listen for udfps" state
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);
        when(mStrongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);

        // WHEN trust is enabled (ie: via smartlock)
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */, true /* newlyUnlocked */,
                mSelectedUserInteractor.getSelectedUserId(), 0 /* flags */, new ArrayList<>());

        // THEN we shouldn't listen for udfps
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(false);
    }

    @Test
    public void testShouldNotListenForUdfps_whenFaceAuthenticated() {
        // GIVEN a "we should listen for udfps" state
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);
        when(mFaceAuthInteractor.isFaceAuthEnabledAndEnrolled()).thenReturn(true);
        when(mStrongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);

        // WHEN face authenticated
        when(mFaceAuthInteractor.isAuthenticated()).thenReturn(true);

        // THEN we shouldn't listen for udfps
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(false);
    }

    @Test
    public void testShouldNotListenForUdfps_whenInLockDown() {
        // GIVEN a "we should listen for udfps" state
        setKeyguardBouncerVisibility(false /* isVisible */);
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);
        when(mStrongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);

        // WHEN device in lock down
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);

        // THEN we shouldn't listen for udfps
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(false);
    }

    @Test
    public void testRequireUnlockForNfc_Broadcast() {
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);
        Intent intent = new Intent(NfcAdapter.ACTION_REQUIRE_UNLOCK_FOR_NFC);
        mKeyguardUpdateMonitor.mBroadcastAllReceiver.onReceive(getContext(), intent);
        mTestableLooper.processAllMessages();

        verify(callback, atLeastOnce()).onRequireUnlockForNfc();
    }

    @Test
    public void testShowTrustGrantedMessage_onTrustGranted() {
        // WHEN trust is enabled (ie: via some trust agent) with a trustGranted string
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */, true /* newlyUnlocked */,
                mSelectedUserInteractor.getSelectedUserId(), 0 /* flags */,
                Arrays.asList("Unlocked by wearable"));

        // THEN the showTrustGrantedMessage should be called with the first message
        verify(mTestCallback).onTrustGrantedForCurrentUser(
                anyBoolean() /* dismissKeyguard */,
                eq(true) /* newlyUnlocked */,
                eq(new TrustGrantFlags(0)),
                eq("Unlocked by wearable"));
    }

    @Test
    public void testFingerprintCanAuth_whenCancellationNotReceivedAndAuthFailed() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        keyguardIsVisible();

        verifyFingerprintAuthenticateCall();

        when(mFaceAuthInteractor.isAuthenticated()).thenReturn(true);
        when(mFaceAuthInteractor.isFaceAuthStrong()).thenReturn(false);
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(false /* isClass3Biometric */))
                .thenReturn(false);
        // Make sure keyguard is going away after face auth attempt, and that it calls
        // updateBiometricStateListeningState.
        mKeyguardUpdateMonitor.setKeyguardShowing(false, false);
        mTestableLooper.processAllMessages();

        verify(mHandler).postDelayed(mKeyguardUpdateMonitor.mFpCancelNotReceived,
                DEFAULT_CANCEL_SIGNAL_TIMEOUT);

        mKeyguardUpdateMonitor.onFingerprintAuthenticated(0, true);
        mTestableLooper.processAllMessages();

        verify(mHandler, times(1)).removeCallbacks(mKeyguardUpdateMonitor.mFpCancelNotReceived);
        mKeyguardUpdateMonitor.dispatchStartedGoingToSleep(0 /* why */);
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isEqualTo(true);
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(true);
    }

    @Test
    public void testOnTrustGrantedForCurrentUser_dismissKeyguardRequested_deviceInteractive() {
        // GIVEN device is interactive
        deviceIsInteractive();

        // GIVEN callback is registered
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        // WHEN onTrustChanged with TRUST_DISMISS_KEYGUARD flag
        mKeyguardUpdateMonitor.onTrustChanged(
                true /* enabled */,
                true /* newlyUnlocked */,
                mSelectedUserInteractor.getSelectedUserId() /* userId */,
                TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD /* flags */,
                null /* trustGrantedMessages */);

        // THEN onTrustGrantedForCurrentUser callback called
        verify(callback).onTrustGrantedForCurrentUser(
                eq(true) /* dismissKeyguard */,
                eq(true) /* newlyUnlocked */,
                eq(new TrustGrantFlags(TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD)),
                eq(null) /* message */
        );
    }

    @Test
    public void testOnTrustGrantedForCurrentUser_dismissKeyguardRequested_doesNotDismiss() {
        // GIVEN device is NOT interactive

        // GIVEN callback is registered
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        // WHEN onTrustChanged with TRUST_DISMISS_KEYGUARD flag
        mKeyguardUpdateMonitor.onTrustChanged(
                true /* enabled */,
                true /* newlyUnlocked */,
                mSelectedUserInteractor.getSelectedUserId() /* userId */,
                TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD /* flags */,
                null /* trustGrantedMessages */);

        // THEN onTrustGrantedForCurrentUser callback called
        verify(callback).onTrustGrantedForCurrentUser(
                eq(false) /* dismissKeyguard */,
                eq(true) /* newlyUnlocked */,
                eq(new TrustGrantFlags(TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD)),
                eq(null) /* message */
        );
    }

    @Test
    public void testOnTrustGrantedForCurrentUser_dismissKeyguardRequested_temporaryAndRenewable() {
        // GIVEN device is interactive
        deviceIsInteractive();

        // GIVEN callback is registered
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        // WHEN onTrustChanged for a different user
        mKeyguardUpdateMonitor.onTrustChanged(
                true /* enabled */,
                true /* newlyUnlocked */,
                546 /* userId, not the current userId */,
                0 /* flags */,
                null /* trustGrantedMessages */);

        // THEN onTrustGrantedForCurrentUser callback called
        verify(callback, never()).onTrustGrantedForCurrentUser(
                anyBoolean() /* dismissKeyguard */,
                eq(true) /* newlyUnlocked */,
                anyObject() /* flags */,
                anyString() /* message */
        );
    }

    @Test
    public void testOnTrustGranted_differentUser_noCallback() {
        // GIVEN device is interactive

        // GIVEN callback is registered
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        // WHEN onTrustChanged with TRUST_DISMISS_KEYGUARD AND TRUST_TEMPORARY_AND_RENEWABLE
        // flags (temporary & rewable is active unlock)
        mKeyguardUpdateMonitor.onTrustChanged(
                true /* enabled */,
                true /* newlyUnlocked */,
                mSelectedUserInteractor.getSelectedUserId() /* userId */,
                TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD
                        | TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE /* flags */,
                null /* trustGrantedMessages */);

        // THEN onTrustGrantedForCurrentUser callback called
        verify(callback).onTrustGrantedForCurrentUser(
                eq(true) /* dismissKeyguard */,
                eq(true) /* newlyUnlocked */,
                eq(new TrustGrantFlags(TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD
                        | TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE)),
                eq(null) /* message */
        );
    }

    @Test
    public void testOnTrustGrantedForCurrentUser_bouncerShowing_initiatedByUser() {
        // GIVEN device is interactive & bouncer is showing
        deviceIsInteractive();
        bouncerFullyVisible();

        // GIVEN callback is registered
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        // WHEN onTrustChanged with INITIATED_BY_USER flag
        mKeyguardUpdateMonitor.onTrustChanged(
                true /* enabled */,
                true /* newlyUnlocked */,
                mSelectedUserInteractor.getSelectedUserId() /* userId, not the current userId */,
                TrustAgentService.FLAG_GRANT_TRUST_INITIATED_BY_USER /* flags */,
                null /* trustGrantedMessages */);

        // THEN onTrustGrantedForCurrentUser callback called
        verify(callback, never()).onTrustGrantedForCurrentUser(
                eq(true) /* dismissKeyguard */,
                eq(true) /* newlyUnlocked */,
                eq(new TrustGrantFlags(TrustAgentService.FLAG_GRANT_TRUST_INITIATED_BY_USER)),
                anyString() /* message */
        );
    }

    @Test
    public void testOnTrustGrantedForCurrentUser_bouncerShowing_temporaryRenewable() {
        // GIVEN device is NOT interactive & bouncer is showing
        bouncerFullyVisible();

        // GIVEN callback is registered
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        // WHEN onTrustChanged with INITIATED_BY_USER flag
        mKeyguardUpdateMonitor.onTrustChanged(
                true /* enabled */,
                true /* newlyUnlocked */,
                mSelectedUserInteractor.getSelectedUserId() /* userId, not the current userId */,
                TrustAgentService.FLAG_GRANT_TRUST_INITIATED_BY_USER
                        | TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE /* flags */,
                null /* trustGrantedMessages */);

        // THEN onTrustGrantedForCurrentUser callback called
        verify(callback, never()).onTrustGrantedForCurrentUser(
                eq(true) /* dismissKeyguard */,
                eq(true) /* newlyUnlocked */,
                eq(new TrustGrantFlags(TrustAgentService.FLAG_GRANT_TRUST_INITIATED_BY_USER
                        | TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE)),
                anyString() /* message */
        );
    }

    @Test
    public void testStrongAuthChange_lockDown_stopsFpListeningState() {
        // GIVEN device is listening for fingerprint
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        keyguardIsVisible();

        verifyFingerprintAuthenticateCall();

        final CancellationSignal fpCancel = spy(mKeyguardUpdateMonitor.mFingerprintCancelSignal);
        mKeyguardUpdateMonitor.mFingerprintCancelSignal = fpCancel;
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        // WHEN strong auth changes and device is in user lockdown
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        userDeviceLockDown();
        mKeyguardUpdateMonitor.notifyStrongAuthAllowedChanged(
                mSelectedUserInteractor.getSelectedUserId());
        mTestableLooper.processAllMessages();

        // THEN fingerprint listening are cancelled
        verify(fpCancel).cancel();
        verify(callback).onBiometricRunningStateChanged(
                eq(false), eq(BiometricSourceType.FINGERPRINT));
    }

    @Test
    public void assistantVisible_requestActiveUnlock() {
        // GIVEN active unlock requests from the assistant are allowed
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.ASSISTANT)).thenReturn(true);

        // GIVEN should trigger active unlock
        keyguardIsVisible();
        keyguardNotGoingAway();
        statusBarShadeIsNotLocked();
        when(mLockPatternUtils.isSecure(mSelectedUserInteractor.getSelectedUserId())).thenReturn(
                true);

        // WHEN the assistant is visible
        mKeyguardUpdateMonitor.setAssistantVisible(true);

        // THEN request unlock with keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(
                eq(mSelectedUserInteractor.getSelectedUserId()),
                eq(true));
    }

    @Test
    public void fingerprintFailure_requestActiveUnlock_dismissKeyguard() {
        // GIVEN shouldTriggerActiveUnlock
        bouncerFullyVisible();
        when(mLockPatternUtils.isSecure(mSelectedUserInteractor.getSelectedUserId())).thenReturn(
                true);

        // GIVEN active unlock triggers on biometric failures
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL))
                .thenReturn(true);

        // WHEN fingerprint fails
        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback.onAuthenticationFailed();

        // ALWAYS request unlock with a keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(
                eq(mSelectedUserInteractor.getSelectedUserId()),
                eq(true));
    }

    @Test
    public void faceNonBypassFailure_requestActiveUnlock_doesNotDismissKeyguard() {
        // GIVEN shouldTriggerActiveUnlock
        when(mAuthController.isUdfpsFingerDown()).thenReturn(false);
        keyguardIsVisible();
        keyguardNotGoingAway();
        statusBarShadeIsNotLocked();
        when(mLockPatternUtils.isSecure(mSelectedUserInteractor.getSelectedUserId())).thenReturn(
                true);

        // GIVEN active unlock triggers on biometric failures
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL))
                .thenReturn(true);

        // WHEN face fails & bypass is not allowed
        lockscreenBypassIsNotAllowed();
        mFaceAuthenticationListener.getValue().onAuthenticationStatusChanged(
                new FailedFaceAuthenticationStatus());

        // THEN request unlock with NO keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(
                eq(mSelectedUserInteractor.getSelectedUserId()),
                eq(false));
    }

    @Test
    public void faceBypassFailure_requestActiveUnlock_dismissKeyguard() {
        // GIVEN shouldTriggerActiveUnlock
        when(mAuthController.isUdfpsFingerDown()).thenReturn(false);
        when(mFaceAuthInteractor.isFaceAuthEnabledAndEnrolled()).thenReturn(true);
        keyguardIsVisible();
        keyguardNotGoingAway();
        statusBarShadeIsNotLocked();
        when(mLockPatternUtils.isSecure(mSelectedUserInteractor.getSelectedUserId())).thenReturn(
                true);

        // GIVEN active unlock triggers on biometric failures
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL))
                .thenReturn(true);

        // WHEN face fails & bypass is not allowed
        lockscreenBypassIsAllowed();
        mFaceAuthenticationListener.getValue().onAuthenticationStatusChanged(
                new FailedFaceAuthenticationStatus());

        // THEN request unlock with a keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(
                eq(mSelectedUserInteractor.getSelectedUserId()),
                eq(true));
    }

    @Test
    public void faceNonBypassFailure_requestActiveUnlock_dismissKeyguard() {
        // GIVEN shouldTriggerActiveUnlock
        when(mAuthController.isUdfpsFingerDown()).thenReturn(false);
        when(mFaceAuthInteractor.isFaceAuthEnabledAndEnrolled()).thenReturn(true);
        lockscreenBypassIsNotAllowed();
        when(mLockPatternUtils.isSecure(mSelectedUserInteractor.getSelectedUserId())).thenReturn(
                true);

        // GIVEN active unlock triggers on biometric failures
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.BIOMETRIC_FAIL))
                .thenReturn(true);

        // WHEN face fails & on the bouncer
        bouncerFullyVisible();
        mFaceAuthenticationListener.getValue().onAuthenticationStatusChanged(
                new FailedFaceAuthenticationStatus());

        // THEN request unlock with a keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(
                eq(mSelectedUserInteractor.getSelectedUserId()),
                eq(true));
    }

    @Test
    public void testBatteryChangedIntent_refreshBatteryInfo() {
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(mContext, getBatteryIntent());

        BatteryStatus status = verifyRefreshBatteryInfo();
        assertThat(status.incompatibleCharger.get()).isFalse();
        assertThat(mKeyguardUpdateMonitor.mIncompatibleCharger).isFalse();
    }

    @Test
    public void testUsbComplianceIntent_refreshBatteryInfo() {
        Context contextSpy = getSpyContext();

        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(
                contextSpy, new Intent(UsbManager.ACTION_USB_PORT_COMPLIANCE_CHANGED));

        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mIncompatibleCharger).isFalse();
    }

    @Test
    public void testUsbComplianceIntent_refreshBatteryInfoWithIncompatibleCharger() {
        Context contextSpy = getSpyContext();
        setupIncompatibleCharging();

        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(
                contextSpy, new Intent(UsbManager.ACTION_USB_PORT_COMPLIANCE_CHANGED));

        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mIncompatibleCharger).isTrue();
    }

    @Test
    public void unfoldWakeup_requestActiveUnlock_forceDismissKeyguard() {
        // GIVEN shouldTriggerActiveUnlock
        keyguardIsVisible();
        when(mLockPatternUtils.isSecure(mSelectedUserInteractor.getSelectedUserId())).thenReturn(
                true);

        // GIVEN active unlock triggers on wakeup
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE))
                .thenReturn(true);

        // GIVEN an unfold should force dismiss the keyguard
        when(mActiveUnlockConfig.shouldWakeupForceDismissKeyguard(
                PowerManager.WAKE_REASON_UNFOLD_DEVICE)).thenReturn(true);

        // WHEN device wakes up from an unfold
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_UNFOLD_DEVICE);
        mTestableLooper.processAllMessages();

        // THEN request unlock with a keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(
                eq(mSelectedUserInteractor.getSelectedUserId()),
                eq(true));
    }

    @Test
    public void unfoldWakeup_requestActiveUnlock_noDismissKeyguard() {
        // GIVEN shouldTriggerActiveUnlock on wake from UNFOLD_DEVICE
        keyguardIsVisible();
        when(mLockPatternUtils.isSecure(mSelectedUserInteractor.getSelectedUserId())).thenReturn(
                true);

        // GIVEN active unlock triggers on wakeup
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE))
                .thenReturn(true);

        // GIVEN an unfold should NOT force dismiss the keyguard
        when(mActiveUnlockConfig.shouldWakeupForceDismissKeyguard(
                PowerManager.WAKE_REASON_UNFOLD_DEVICE)).thenReturn(false);

        // WHEN device wakes up from an unfold
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_UNFOLD_DEVICE);
        mTestableLooper.processAllMessages();

        // THEN request unlock WITHOUT a keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(
                eq(mSelectedUserInteractor.getSelectedUserId()),
                eq(false));
    }

    @Test
    public void unfoldFromPostureChange_requestActiveUnlock_forceDismissKeyguard() {
        // GIVEN shouldTriggerActiveUnlock
        keyguardIsVisible();
        when(mLockPatternUtils.isSecure(mSelectedUserInteractor.getSelectedUserId())).thenReturn(
                true);

        // GIVEN active unlock triggers on wakeup
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE))
                .thenReturn(true);

        // GIVEN an unfold should force dismiss the keyguard
        when(mActiveUnlockConfig.shouldWakeupForceDismissKeyguard(
                PowerManager.WAKE_REASON_UNFOLD_DEVICE)).thenReturn(true);

        // WHEN device posture changes to unfold
        deviceInPostureStateOpened();
        mTestableLooper.processAllMessages();

        // THEN request unlock with a keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(
                eq(mSelectedUserInteractor.getSelectedUserId()),
                eq(true));
    }


    @Test
    public void unfoldFromPostureChange_requestActiveUnlock_noDismissKeyguard() {
        // GIVEN shouldTriggerActiveUnlock on wake from UNFOLD_DEVICE
        keyguardIsVisible();
        when(mLockPatternUtils.isSecure(mSelectedUserInteractor.getSelectedUserId())).thenReturn(
                true);

        // GIVEN active unlock triggers on wakeup
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ActiveUnlockRequestOrigin.WAKE))
                .thenReturn(true);

        // GIVEN an unfold should NOT force dismiss the keyguard
        when(mActiveUnlockConfig.shouldWakeupForceDismissKeyguard(
                PowerManager.WAKE_REASON_UNFOLD_DEVICE)).thenReturn(false);

        // WHEN device posture changes to unfold
        deviceInPostureStateOpened();
        mTestableLooper.processAllMessages();

        // THEN request unlock WITHOUT a keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(
                eq(mSelectedUserInteractor.getSelectedUserId()),
                eq(false));
    }

    @Test
    public void detectFingerprint_onTemporaryLockoutReset_authenticateFingerprint() {
        ArgumentCaptor<FingerprintManager.LockoutResetCallback> fpLockoutResetCallbackCaptor =
                ArgumentCaptor.forClass(FingerprintManager.LockoutResetCallback.class);
        verify(mFingerprintManager).addLockoutResetCallback(fpLockoutResetCallbackCaptor.capture());

        // GIVEN device is locked out
        fingerprintErrorTemporaryLockOut();

        // GIVEN FP detection is running
        givenDetectFingerprintWithClearingFingerprintManagerInvocations();
        verifyFingerprintDetectCall();
        verifyFingerprintAuthenticateNeverCalled();

        // WHEN temporary lockout resets
        fpLockoutResetCallbackCaptor.getValue().onLockoutReset(0);
        mTestableLooper.processAllMessages();

        // THEN fingerprint detect state should cancel & then restart (for authenticate call)
        assertThat(mKeyguardUpdateMonitor.mFingerprintRunningState)
                .isEqualTo(BIOMETRIC_STATE_CANCELLING_RESTARTING);
    }

    @Test
    public void detectFingerprint_onSuccess_biometricStateStopped() {
        // GIVEN FP detection is running
        givenDetectFingerprintWithClearingFingerprintManagerInvocations();

        // WHEN detection is successful
        ArgumentCaptor<FingerprintManager.FingerprintDetectionCallback> fpDetectCallbackCaptor =
                ArgumentCaptor.forClass(FingerprintManager.FingerprintDetectionCallback.class);
        verify(mFingerprintManager).detectFingerprint(
                any(), fpDetectCallbackCaptor.capture(), any());
        fpDetectCallbackCaptor.getValue().onFingerprintDetected(0, 0, true);
        mTestableLooper.processAllMessages();

        // THEN fingerprint detect state should immediately update to STOPPED
        assertThat(mKeyguardUpdateMonitor.mFingerprintRunningState)
                .isEqualTo(BIOMETRIC_STATE_STOPPED);
    }

    @Test
    public void runFpDetectFlagDisabled_sideFps_keyguardDismissible_fingerprintAuthenticateRuns() {
        mSetFlagsRule.disableFlags(Flags.FLAG_RUN_FINGERPRINT_DETECT_ON_DISMISSIBLE_KEYGUARD);

        // Clear invocations, since previous setup (e.g. registering BiometricManager callbacks)
        // will trigger updateBiometricListeningState();
        clearInvocations(mFingerprintManager);
        mKeyguardUpdateMonitor.resetBiometricListeningState();

        // GIVEN the user can skip the bouncer
        givenSelectedUserCanSkipBouncerFromTrustedState();
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        mKeyguardUpdateMonitor.dispatchStartedGoingToSleep(0 /* why */);
        mTestableLooper.processAllMessages();

        // WHEN verify authenticate runs
        verifyFingerprintAuthenticateCall();
    }

    @Test
    public void sideFps_keyguardDismissible_fingerprintDetectRuns() {
        mSetFlagsRule.enableFlags(Flags.FLAG_RUN_FINGERPRINT_DETECT_ON_DISMISSIBLE_KEYGUARD);
        // Clear invocations, since previous setup (e.g. registering BiometricManager callbacks)
        // will trigger updateBiometricListeningState();
        clearInvocations(mFingerprintManager);
        mKeyguardUpdateMonitor.resetBiometricListeningState();

        // GIVEN the user can skip the bouncer
        givenSelectedUserCanSkipBouncerFromTrustedState();
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
        mKeyguardUpdateMonitor.dispatchStartedGoingToSleep(0 /* why */);
        mTestableLooper.processAllMessages();

        // WHEN verify detect runs
        verifyFingerprintDetectCall();
    }

    @Test
    public void testFingerprintSensorProperties() throws RemoteException {
        mFingerprintAuthenticatorsRegisteredCallback.onAllAuthenticatorsRegistered(
                new ArrayList<>());

        assertThat(mKeyguardUpdateMonitor.isUnlockWithFingerprintPossible(
                mSelectedUserInteractor.getSelectedUserId())).isFalse();

        mFingerprintAuthenticatorsRegisteredCallback
                .onAllAuthenticatorsRegistered(mFingerprintSensorProperties);

        verifyFingerprintAuthenticateCall();
        assertThat(mKeyguardUpdateMonitor.isUnlockWithFingerprintPossible(
                mSelectedUserInteractor.getSelectedUserId())).isTrue();
    }

    @Test
    public void testFingerprintListeningStateWhenOccluded() {
        when(mAuthController.isUdfpsSupported()).thenReturn(true);

        mKeyguardUpdateMonitor.setKeyguardShowing(false, false);
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_BIOMETRIC);
        mKeyguardUpdateMonitor.setKeyguardShowing(false, true);

        verifyFingerprintAuthenticateNeverCalled();

        mKeyguardUpdateMonitor.setKeyguardShowing(true, true);
        mKeyguardUpdateMonitor.setAlternateBouncerShowing(true);

        verifyFingerprintAuthenticateCall();
    }

    @Test
    public void onTrustChangedCallbacksCalledBeforeOnTrustGrantedForCurrentUserCallback() {
        // GIVEN device is interactive
        deviceIsInteractive();

        // GIVEN callback is registered
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        // WHEN onTrustChanged enabled=true
        mKeyguardUpdateMonitor.onTrustChanged(
                true /* enabled */,
                true /* newlyUnlocked */,
                mSelectedUserInteractor.getSelectedUserId() /* userId */,
                TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD /* flags */,
                null /* trustGrantedMessages */);

        // THEN onTrustChanged is called FIRST
        final InOrder inOrder = Mockito.inOrder(callback);
        inOrder.verify(callback).onTrustChanged(eq(mSelectedUserInteractor.getSelectedUserId()));

        // AND THEN onTrustGrantedForCurrentUser callback called
        inOrder.verify(callback).onTrustGrantedForCurrentUser(
                eq(true) /* dismissKeyguard */,
                eq(true) /* newlyUnlocked */,
                eq(new TrustGrantFlags(TrustAgentService.FLAG_GRANT_TRUST_DISMISS_KEYGUARD)),
                eq(null) /* message */
        );
    }

    @Test
    public void testOnSimStateChanged_Unknown() {
        KeyguardUpdateMonitorCallback keyguardUpdateMonitorCallback = spy(
                KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback);
        mKeyguardUpdateMonitor.handleSimStateChange(-1, 0, TelephonyManager.SIM_STATE_UNKNOWN);
        verify(keyguardUpdateMonitorCallback).onSimStateChanged(-1, 0,
                TelephonyManager.SIM_STATE_UNKNOWN);
    }

    @Test
    public void testOnSimStateChanged_HandleSimStateNotReady() {
        KeyguardUpdateMonitorCallback keyguardUpdateMonitorCallback = spy(
                KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(keyguardUpdateMonitorCallback);
        mKeyguardUpdateMonitor.handleSimStateChange(-1, 0, TelephonyManager.SIM_STATE_NOT_READY);
        verify(keyguardUpdateMonitorCallback).onSimStateChanged(-1, 0,
                TelephonyManager.SIM_STATE_NOT_READY);
    }

    @Test
    public void onAuthEnrollmentChangesCallbacksAreNotified() {
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        ArgumentCaptor<AuthController.Callback> authCallback = ArgumentCaptor.forClass(
                AuthController.Callback.class);
        verify(mAuthController).addCallback(authCallback.capture());

        mKeyguardUpdateMonitor.registerCallback(callback);

        authCallback.getValue().onEnrollmentsChanged(TYPE_FINGERPRINT);
        mTestableLooper.processAllMessages();
        verify(callback).onBiometricEnrollmentStateChanged(BiometricSourceType.FINGERPRINT);

        authCallback.getValue().onEnrollmentsChanged(BiometricAuthenticator.TYPE_FACE);
        mTestableLooper.processAllMessages();
        verify(callback).onBiometricEnrollmentStateChanged(BiometricSourceType.FACE);

        clearInvocations(callback);
        mFaceAuthenticationListener.getValue().onAuthEnrollmentStateChanged(false);
        mTestableLooper.processAllMessages();
        verify(callback).onBiometricEnrollmentStateChanged(BiometricSourceType.FACE);
    }

    private void givenSelectedUserCanSkipBouncerFromTrustedState() {
        mKeyguardUpdateMonitor.onTrustChanged(true, true,
                mSelectedUserInteractor.getSelectedUserId(), 0, null);
    }

    private void verifyFingerprintAuthenticateNeverCalled() {
        verify(mFingerprintManager, never()).authenticate(any(), any(), any(), any(), any());
        verify(mFingerprintManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyInt(), anyInt());
    }

    private void verifyFingerprintAuthenticateCall() {
        verify(mFingerprintManager).authenticate(any(), any(), any(), any(), any());
    }

    private void verifyFingerprintDetectNeverCalled() {
        verify(mFingerprintManager, never()).detectFingerprint(any(), any(), any());
    }

    private void verifyFingerprintDetectCall() {
        verify(mFingerprintManager).detectFingerprint(any(), any(), any());
    }

    private void userDeviceLockDown() {
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        when(mStrongAuthTracker.getStrongAuthForUser(mCurrentUserId))
                .thenReturn(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);
    }

    private void lockscreenBypassIsAllowed() {
        mockCanBypassLockscreen(true);
    }

    private void mockCanBypassLockscreen(boolean canBypass) {
        mKeyguardUpdateMonitor.setKeyguardBypassController(mKeyguardBypassController);
        when(mKeyguardBypassController.canBypass()).thenReturn(canBypass);
    }

    private void lockscreenBypassIsNotAllowed() {
        mockCanBypassLockscreen(false);
    }

    private void cleanupKeyguardUpdateMonitor() {
        if (mKeyguardUpdateMonitor != null) {
            mKeyguardUpdateMonitor.removeCallback(mTestCallback);
            mKeyguardUpdateMonitor.destroy();
            mKeyguardUpdateMonitor = null;
        }
    }

    private void faceAuthLockOut() {
        when(mFaceAuthInteractor.isLockedOut()).thenReturn(true);
        mFaceAuthenticationListener.getValue().onAuthenticationStatusChanged(
                new ErrorFaceAuthenticationStatus(FaceManager.FACE_ERROR_LOCKOUT_PERMANENT, "",
                        0L));
    }

    private void statusBarShadeIsNotLocked() {
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);
    }

    private void keyguardIsVisible() {
        mKeyguardUpdateMonitor.setKeyguardShowing(true, false);
    }

    private void fingerprintErrorTemporaryLockOut() {
        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback
                .onAuthenticationError(FINGERPRINT_ERROR_LOCKOUT, "Fingerprint locked out");
    }

    private void deviceInPostureStateOpened() {
        mKeyguardUpdateMonitor.mPostureCallback.onPostureChanged(DEVICE_POSTURE_OPENED);
    }

    private void currentUserIsSystem() {
        when(mUserManager.isSystemUser()).thenReturn(true);
    }

    private void biometricsEnabledForCurrentUser() throws RemoteException {
        mBiometricEnabledOnKeyguardCallback.onChanged(true,
                mSelectedUserInteractor.getSelectedUserId());
    }

    private void primaryAuthNotRequiredByStrongAuthTracker() {
        when(mStrongAuthTracker.getStrongAuthForUser(mSelectedUserInteractor.getSelectedUserId()))
                .thenReturn(0);
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
    }

    private void keyguardNotGoingAway() {
        mKeyguardUpdateMonitor.setKeyguardGoingAway(false);
    }

    private void deviceIsInteractive() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
    }

    private void bouncerFullyVisible() {
        setKeyguardBouncerVisibility(true);
    }

    private void setKeyguardBouncerVisibility(boolean isVisible) {
        mKeyguardUpdateMonitor.sendPrimaryBouncerChanged(isVisible, isVisible);
        mTestableLooper.processAllMessages();
    }

    private void setBroadcastReceiverPendingResult(BroadcastReceiver receiver) {
        BroadcastReceiver.PendingResult pendingResult =
                new BroadcastReceiver.PendingResult(Activity.RESULT_OK,
                        "resultData",
                        /* resultExtras= */ null,
                        BroadcastReceiver.PendingResult.TYPE_UNREGISTERED,
                        /* ordered= */ true,
                        /* sticky= */ false,
                        /* token= */ null,
                        UserHandle.myUserId(),
                        /* flags= */ 0);
        receiver.setPendingResult(pendingResult);
    }

    private void givenDetectFingerprintWithClearingFingerprintManagerInvocations() {
        // Clear invocations, since previous setup (e.g. registering BiometricManager callbacks)
        // will trigger updateBiometricListeningState();
        clearInvocations(mFingerprintManager);
        mKeyguardUpdateMonitor.resetBiometricListeningState();

        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        mKeyguardUpdateMonitor.dispatchStartedGoingToSleep(0 /* why */);
        mTestableLooper.processAllMessages();
    }

    private Intent putPhoneInfo(Intent intent, Bundle data, Boolean simInited) {
        int subscription = simInited
                ? 1/* mock subid=1 */ : SubscriptionManager.PLACEHOLDER_SUBSCRIPTION_ID_BASE;
        if (data != null) intent.putExtras(data);

        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subscription);
        intent.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 0);
        return intent;
    }

    private BatteryStatus verifyRefreshBatteryInfo() {
        mTestableLooper.processAllMessages();
        ArgumentCaptor<BatteryStatus> captor = ArgumentCaptor.forClass(BatteryStatus.class);
        verify(mTestCallback, atLeastOnce()).onRefreshBatteryInfo(captor.capture());
        List<BatteryStatus> batteryStatusList = captor.getAllValues();
        return batteryStatusList.get(batteryStatusList.size() - 1);
    }

    private void setupIncompatibleCharging() {
        final List<UsbPort> usbPorts = new ArrayList<>();
        usbPorts.add(mUsbPort);
        when(mUsbManager.getPorts()).thenReturn(usbPorts);
        when(mUsbPort.getStatus()).thenReturn(mUsbPortStatus);
        when(mUsbPort.supportsComplianceWarnings()).thenReturn(true);
        when(mUsbPortStatus.isConnected()).thenReturn(true);
        when(mUsbPortStatus.getComplianceWarnings())
                .thenReturn(new int[]{UsbPortStatus.COMPLIANCE_WARNING_DEBUG_ACCESSORY});
    }

    private Context getSpyContext() {
        mContext.addMockSystemService(UsbManager.class, mUsbManager);
        Context contextSpy = spy(mContext);
        doReturn(getBatteryIntent()).when(contextSpy).registerReceiver(eq(null),
                any(IntentFilter.class));
        return contextSpy;
    }

    private Intent getBatteryIntent() {
        return new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(
                BatteryManager.EXTRA_CHARGING_STATUS,
                BatteryManager.CHARGING_POLICY_ADAPTIVE_LONGLIFE);
    }

    private void setTopStandardActivity(String pkgName) throws RemoteException {
        final ActivityTaskManager.RootTaskInfo taskInfo = new ActivityTaskManager.RootTaskInfo();
        taskInfo.visible = true;
        taskInfo.topActivity = TextUtils.isEmpty(pkgName)
                ? null : new ComponentName(pkgName, "testClass");
        when(mActivityTaskManager.getRootTaskInfo(anyInt(), eq(ACTIVITY_TYPE_STANDARD)))
                .thenReturn(taskInfo);
    }

    private void onTaskStackChanged() {
        ArgumentCaptor<TaskStackChangeListener> taskStackChangeListenerCaptor =
                ArgumentCaptor.forClass(TaskStackChangeListener.class);
        verify(mTaskStackChangeListeners).registerTaskStackListener(
                taskStackChangeListenerCaptor.capture());
        taskStackChangeListenerCaptor.getValue().onTaskStackChangedBackground();
    }

    private class TestableKeyguardUpdateMonitor extends KeyguardUpdateMonitor {
        AtomicBoolean mSimStateChanged = new AtomicBoolean(false);
        AtomicInteger mCachedSimState = new AtomicInteger(-1);

        protected TestableKeyguardUpdateMonitor(Context context) {
            super(context, mUserTracker,
                    TestableLooper.get(KeyguardUpdateMonitorTest.this).getLooper(),
                    mBroadcastDispatcher, mDumpManager,
                    mBackgroundExecutor, mMainExecutor,
                    mStatusBarStateController, mLockPatternUtils,
                    mAuthController, mTelephonyListenerManager,
                    mInteractionJankMonitor, mLatencyTracker, mActiveUnlockConfig,
                    mKeyguardUpdateMonitorLogger, mUiEventLogger, () -> mSessionTracker,
                    mTrustManager, mSubscriptionManager, mUserManager,
                    mDreamManager, mDevicePolicyManager, mSensorPrivacyManager, mTelephonyManager,
                    mPackageManager, mFingerprintManager, mBiometricManager,
                    mFaceWakeUpTriggersConfig, mDevicePostureController,
                    Optional.of(mInteractiveToAuthProvider),
                    mTaskStackChangeListeners, mSelectedUserInteractor, mActivityTaskManager);
            setStrongAuthTracker(KeyguardUpdateMonitorTest.this.mStrongAuthTracker);
            start();
        }

        public boolean hasSimStateJustChanged() {
            return mSimStateChanged.getAndSet(false);
        }

        public int getCachedSimState() {
            return mCachedSimState.getAndSet(-1);
        }

        @Override
        protected void handleSimStateChange(int subId, int slotId, int state) {
            mSimStateChanged.set(true);
            mCachedSimState.set(state);
            super.handleSimStateChange(subId, slotId, state);
        }

        @Override
        protected int getBiometricLockoutDelay() {
            return 0;
        }
    }
}
