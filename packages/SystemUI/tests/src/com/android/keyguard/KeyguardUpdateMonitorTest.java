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
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ERROR_LOCKOUT_PERMANENT;
import static android.hardware.fingerprint.FingerprintSensorProperties.TYPE_POWER_BUTTON;
import static android.telephony.SubscriptionManager.DATA_ROAMING_DISABLE;
import static android.telephony.SubscriptionManager.NAME_SOURCE_CARRIER_ID;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;
import static com.android.keyguard.FaceAuthApiRequestReason.NOTIFICATION_PANEL_CLICKED;
import static com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_STATE_CANCELLING_RESTARTING;
import static com.android.keyguard.KeyguardUpdateMonitor.DEFAULT_CANCEL_SIGNAL_TIMEOUT;
import static com.android.keyguard.KeyguardUpdateMonitor.HAL_POWER_PRESS_TIMEOUT;
import static com.android.keyguard.KeyguardUpdateMonitor.getCurrentUser;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_CLOSED;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_OPENED;
import static com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_UNKNOWN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.IActivityManager;
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
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.biometrics.SensorProperties;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorProperties;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.service.trust.TrustAgentService;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.annotation.NonNull;

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
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.FingerprintInteractiveToAuthProvider;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.telephony.TelephonyListenerManager;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.settings.SecureSettings;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.internal.util.reflection.FieldSetter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class KeyguardUpdateMonitorTest extends SysuiTestCase {
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
    private static final int FACE_SENSOR_ID = 0;
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
    private FaceManager mFaceManager;
    @Mock
    private List<FaceSensorPropertiesInternal> mFaceSensorProperties;
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
    private SecureSettings mSecureSettings;
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
    private IActivityManager mActivityService;
    @Mock
    private SessionTracker mSessionTracker;
    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private GlobalSettings mGlobalSettings;
    private FaceWakeUpTriggersConfig mFaceWakeUpTriggersConfig;
    @Mock
    private FingerprintInteractiveToAuthProvider mInteractiveToAuthProvider;


    private final int mCurrentUserId = 100;
    private final UserInfo mCurrentUserInfo = new UserInfo(mCurrentUserId, "Test user", 0);

    @Captor
    private ArgumentCaptor<IBiometricEnabledOnKeyguardCallback>
            mBiometricEnabledCallbackArgCaptor;
    @Captor
    private ArgumentCaptor<FaceManager.AuthenticationCallback> mAuthenticationCallbackCaptor;

    @Mock
    private Uri mURI;

    // Direct executor
    private final Executor mBackgroundExecutor = Runnable::run;
    private final Executor mMainExecutor = Runnable::run;
    private TestableLooper mTestableLooper;
    private Handler mHandler;
    private TestableKeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private MockitoSession mMockitoSession;
    private StatusBarStateController.StateListener mStatusBarStateListener;
    private IBiometricEnabledOnKeyguardCallback mBiometricEnabledOnKeyguardCallback;
    private final InstanceId mKeyguardInstanceId = InstanceId.fakeInstanceId(999);

    @Before
    public void setup() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        when(mActivityService.getCurrentUser()).thenReturn(mCurrentUserInfo);
        when(mActivityService.getCurrentUserId()).thenReturn(mCurrentUserId);
        when(mFaceManager.isHardwareDetected()).thenReturn(true);
        when(mAuthController.isFaceAuthEnrolled(anyInt())).thenReturn(true);
        when(mFaceManager.getSensorPropertiesInternal()).thenReturn(mFaceSensorProperties);
        when(mSessionTracker.getSessionId(SESSION_KEYGUARD)).thenReturn(mKeyguardInstanceId);

        // IBiometricsFace@1.0 does not support detection, only authentication.
        when(mFaceSensorProperties.isEmpty()).thenReturn(false);
        when(mFaceSensorProperties.get(anyInt())).thenReturn(
                createFaceSensorProperties(/* supportsFaceDetection = */ false));

        when(mFingerprintManager.isHardwareDetected()).thenReturn(true);
        when(mFingerprintManager.hasEnrolledTemplates(anyInt())).thenReturn(true);
        when(mFingerprintManager.getSensorPropertiesInternal()).thenReturn(List.of(
                new FingerprintSensorPropertiesInternal(1 /* sensorId */,
                        FingerprintSensorProperties.STRENGTH_STRONG,
                        1 /* maxEnrollmentsPerUser */,
                        List.of(new ComponentInfoInternal("fingerprintSensor" /* componentId */,
                                "vendor/model/revision" /* hardwareVersion */,
                                "1.01" /* firmwareVersion */,
                                "00000001" /* serialNumber */, "" /* softwareVersion */)),
                        FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                        false /* resetLockoutRequiresHAT */)));
        when(mUserManager.isUserUnlocked(anyInt())).thenReturn(true);
        when(mUserManager.isPrimaryUser()).thenReturn(true);
        when(mStrongAuthTracker.getStub()).thenReturn(mock(IStrongAuthTracker.Stub.class));
        when(mStrongAuthTracker
                .isUnlockingWithBiometricAllowed(anyBoolean() /* isStrongBiometric */))
                .thenReturn(true);
        when(mTelephonyManager.getServiceStateForSubscriber(anyInt()))
                .thenReturn(new ServiceState());
        when(mLockPatternUtils.getLockSettings()).thenReturn(mLockSettings);
        when(mAuthController.isUdfpsEnrolled(anyInt())).thenReturn(false);
        when(mDevicePostureController.getDevicePosture()).thenReturn(DEVICE_POSTURE_UNKNOWN);

        mMockitoSession = ExtendedMockito.mockitoSession()
                .spyStatic(SubscriptionManager.class)
                .spyStatic(ActivityManager.class)
                .startMocking();
        ExtendedMockito.doReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                .when(SubscriptionManager::getDefaultSubscriptionId);
        KeyguardUpdateMonitor.setCurrentUser(mCurrentUserId);
        when(mUserTracker.getUserId()).thenReturn(mCurrentUserId);
        ExtendedMockito.doReturn(mActivityService).when(ActivityManager::getService);

        mContext.getOrCreateTestableResources().addOverride(
                com.android.systemui.R.integer.config_face_auth_supported_posture,
                DEVICE_POSTURE_UNKNOWN);
        mFaceWakeUpTriggersConfig = new FaceWakeUpTriggersConfig(
                mContext.getResources(),
                mGlobalSettings,
                mDumpManager
        );

        mTestableLooper = TestableLooper.get(this);
        allowTestableLooperAsMainThread();

        when(mSecureSettings.getUriFor(anyString())).thenReturn(mURI);

        final ContentResolver contentResolver = mContext.getContentResolver();
        ExtendedMockito.spyOn(contentResolver);
        doNothing().when(contentResolver)
                .registerContentObserver(any(Uri.class), anyBoolean(), any(ContentObserver.class),
                        anyInt());

        mKeyguardUpdateMonitor = new TestableKeyguardUpdateMonitor(mContext);

        verify(mBiometricManager)
                .registerEnabledOnKeyguardCallback(mBiometricEnabledCallbackArgCaptor.capture());
        mBiometricEnabledOnKeyguardCallback = mBiometricEnabledCallbackArgCaptor.getValue();
        biometricsEnabledForCurrentUser();

        mHandler = spy(mKeyguardUpdateMonitor.getHandler());
        try {
            FieldSetter.setField(mKeyguardUpdateMonitor,
                    KeyguardUpdateMonitor.class.getDeclaredField("mHandler"), mHandler);
        } catch (NoSuchFieldException e) {

        }
        verify(mStatusBarStateController).addCallback(mStatusBarStateListenerCaptor.capture());
        mStatusBarStateListener = mStatusBarStateListenerCaptor.getValue();
        mKeyguardUpdateMonitor.registerCallback(mTestCallback);

        mTestableLooper.processAllMessages();
        when(mAuthController.areAllFingerprintAuthenticatorsRegistered()).thenReturn(true);
    }

    @NonNull
    private FaceSensorPropertiesInternal createFaceSensorProperties(boolean supportsFaceDetection) {
        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        componentInfo.add(new ComponentInfoInternal("faceSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */, "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */, "" /* softwareVersion */));
        componentInfo.add(new ComponentInfoInternal("matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */, "" /* firmwareVersion */, "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */));


        return new FaceSensorPropertiesInternal(
                0 /* id */,
                FaceSensorProperties.STRENGTH_STRONG,
                1 /* maxTemplatesAllowed */,
                componentInfo,
                FaceSensorProperties.TYPE_UNKNOWN,
                supportsFaceDetection /* supportsFaceDetection */,
                true /* supportsSelfIllumination */,
                false /* resetLockoutRequiresChallenge */);
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
    public void testTriesToAuthenticateFingerprint_whenKeyguard() {
        mKeyguardUpdateMonitor.dispatchStartedGoingToSleep(0 /* why */);
        mTestableLooper.processAllMessages();

        verify(mFingerprintManager).authenticate(any(), any(), any(), any(), anyInt(), anyInt(),
                anyInt());
        verify(mFingerprintManager, never()).detectFingerprint(any(), any(), anyInt());
    }

    @Test
    public void test_doesNotTryToAuthenticateFingerprint_whenAuthenticatorsNotRegistered() {
        when(mAuthController.areAllFingerprintAuthenticatorsRegistered()).thenReturn(false);

        mKeyguardUpdateMonitor.dispatchStartedGoingToSleep(0 /* why */);
        mTestableLooper.processAllMessages();

        verify(mFingerprintManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyInt(), anyInt());
        verify(mFingerprintManager, never()).detectFingerprint(any(), any(), anyInt());
    }

    @Test
    public void testOnlyDetectFingerprint_whenFingerprintUnlockNotAllowed() {
        // Clear invocations, since previous setup (e.g. registering BiometricManager callbacks)
        // will trigger updateBiometricListeningState();
        clearInvocations(mFingerprintManager);
        mKeyguardUpdateMonitor.resetBiometricListeningState();

        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        mKeyguardUpdateMonitor.dispatchStartedGoingToSleep(0 /* why */);
        mTestableLooper.processAllMessages();

        verify(mFingerprintManager, never()).authenticate(any(), any(), any(), any(), anyInt());
        verify(mFingerprintManager).detectFingerprint(any(), any(), anyInt());
    }

    @Test
    public void testUnlockingWithFaceAllowed_strongAuthTrackerUnlockingWithBiometricAllowed() {
        // GIVEN unlocking with biometric is allowed
        strongAuthNotRequired();

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
    public void testUnlockingWithFaceAllowed_fingerprintLockout() {
        // GIVEN unlocking with biometric is allowed
        strongAuthNotRequired();

        // WHEN fingerprint is locked out
        fingerprintErrorLockedOut();

        // THEN unlocking with face is not allowed
        Assert.assertFalse(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                BiometricSourceType.FACE));
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
        strongAuthNotRequired();

        // WHEN fingerprint is locked out
        fingerprintErrorLockedOut();

        // THEN unlocking with fingeprint is not allowed
        Assert.assertFalse(mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                BiometricSourceType.FINGERPRINT));
    }

    @Test
    public void testTriesToAuthenticate_whenBouncer() {
        setKeyguardBouncerVisibility(true);

        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
        verify(mFaceManager).isHardwareDetected();
        verify(mFaceManager, never()).hasEnrolledTemplates(anyInt());
    }

    @Test
    public void testNoStartAuthenticate_whenAboutToShowBouncer() {
        mKeyguardUpdateMonitor.sendPrimaryBouncerChanged(
                /* bouncerIsOrWillBeShowing */ true, /* bouncerFullyShown */ false);

        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void testTriesToAuthenticate_whenKeyguard() {
        keyguardIsVisible();
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
        verify(mUiEventLogger).logWithInstanceIdAndPosition(
                eq(FaceAuthUiEvent.FACE_AUTH_UPDATED_STARTED_WAKING_UP),
                eq(0),
                eq(null),
                any(),
                eq(PowerManager.WAKE_REASON_POWER_BUTTON));
    }

    @Test
    public void skipsAuthentication_whenStatusBarShadeLocked() {
        mStatusBarStateListener.onStateChanged(StatusBarState.SHADE_LOCKED);
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();

        keyguardIsVisible();
        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void skipsAuthentication_whenStrongAuthRequired_nonBypass() {
        lockscreenBypassIsNotAllowed();
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);

        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        keyguardIsVisible();
        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void faceDetect_whenStrongAuthRequiredAndBypass() {
        // GIVEN bypass is enabled, face detection is supported and strong auth is required
        lockscreenBypassIsAllowed();
        supportsFaceDetection();
        strongAuthRequiredEncrypted();
        keyguardIsVisible();

        // WHEN the device wakes up
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();

        // FACE detect is triggered, not authenticate
        verify(mFaceManager).detectFace(any(), any(), anyInt());
        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());

        // WHEN bouncer becomes visible
        setKeyguardBouncerVisibility(true);
        clearInvocations(mFaceManager);

        // THEN face scanning is not run
        mKeyguardUpdateMonitor.requestFaceAuth(FaceAuthApiRequestReason.UDFPS_POINTER_DOWN);
        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
        verify(mFaceManager, never()).detectFace(any(), any(), anyInt());
    }

    @Test
    public void noFaceDetect_whenStrongAuthRequiredAndBypass_faceDetectionUnsupported() {
        // GIVEN bypass is enabled, face detection is NOT supported and strong auth is required
        lockscreenBypassIsAllowed();
        strongAuthRequiredEncrypted();
        keyguardIsVisible();

        // WHEN the device wakes up
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();

        // FACE detect and authenticate are NOT triggered
        verify(mFaceManager, never()).detectFace(any(), any(), anyInt());
        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void requestFaceAuth_whenFaceAuthWasStarted_returnsTrue() throws RemoteException {
        // This satisfies all the preconditions to run face auth.
        keyguardNotGoingAway();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        bouncerFullyVisibleAndNotGoingToSleep();
        mTestableLooper.processAllMessages();

        boolean didFaceAuthRun = mKeyguardUpdateMonitor.requestFaceAuth(
                NOTIFICATION_PANEL_CLICKED);

        assertThat(didFaceAuthRun).isTrue();
    }

    @Test
    public void requestFaceAuth_whenFaceAuthWasNotStarted_returnsFalse() throws RemoteException {
        // This ensures face auth won't run.
        biometricsDisabledForCurrentUser();
        mTestableLooper.processAllMessages();

        boolean didFaceAuthRun = mKeyguardUpdateMonitor.requestFaceAuth(
                NOTIFICATION_PANEL_CLICKED);

        assertThat(didFaceAuthRun).isFalse();
    }

    @Test
    public void testTriesToAuthenticate_whenAssistant() {
        mKeyguardUpdateMonitor.setKeyguardShowing(true, true);
        mKeyguardUpdateMonitor.setAssistantVisible(true);

        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    public void testTriesToAuthenticate_whenTrustOnAgentKeyguard_ifBypass() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        lockscreenBypassIsAllowed();
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */, true /* newlyUnlocked */,
                KeyguardUpdateMonitor.getCurrentUser(), 0 /* flags */,
                new ArrayList<>());
        keyguardIsVisible();
        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    public void testIgnoresAuth_whenTrustAgentOnKeyguard_withoutBypass() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */, true /* newlyUnlocked */,
                KeyguardUpdateMonitor.getCurrentUser(), 0 /* flags */, new ArrayList<>());
        keyguardIsVisible();
        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void testNoFaceAuth_whenLockDown() {
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        userDeviceLockDown();

        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        keyguardIsVisible();
        mTestableLooper.processAllMessages();

        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
        verify(mFaceManager, never()).detectFace(any(), any(), anyInt());
    }

    @Test
    public void testFingerprintPowerPressed_restartsFingerprintListeningStateWithDelay() {
        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback
                .onAuthenticationError(FingerprintManager.BIOMETRIC_ERROR_POWER_PRESSED, "");

        // THEN doesn't authenticate immediately
        verify(mFingerprintManager, never()).authenticate(any(),
                any(), any(), any(), anyInt(), anyInt(), anyInt());

        // WHEN all messages (with delays) are processed
        mTestableLooper.moveTimeForward(HAL_POWER_PRESS_TIMEOUT);
        mTestableLooper.processAllMessages();

        // THEN fingerprint manager attempts to authenticate again
        verify(mFingerprintManager).authenticate(any(),
                any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    public void testOnFaceAuthenticated_skipsFaceWhenAuthenticated() {
        // test whether face will be skipped if authenticated, so the value of isStrongBiometric
        // doesn't matter here
        mKeyguardUpdateMonitor.onFaceAuthenticated(KeyguardUpdateMonitor.getCurrentUser(),
                true /* isStrongBiometric */);
        setKeyguardBouncerVisibility(true);
        mTestableLooper.processAllMessages();

        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void testFaceAndFingerprintLockout_onlyFace() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        keyguardIsVisible();

        faceAuthLockedOut();

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

        faceAuthLockedOut();
        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback
                .onAuthenticationError(FINGERPRINT_ERROR_LOCKOUT_PERMANENT, "");

        verify(mLockPatternUtils).requireStrongAuth(anyInt(), anyInt());
    }

    @Test
    public void testGetUserCanSkipBouncer_whenFace() {
        int user = KeyguardUpdateMonitor.getCurrentUser();
        mKeyguardUpdateMonitor.onFaceAuthenticated(user, true /* isStrongBiometric */);
        assertThat(mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)).isTrue();
    }

    @Test
    public void testGetUserCanSkipBouncer_whenFace_nonStrongAndDisallowed() {
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(false /* isStrongBiometric */))
                .thenReturn(false);
        int user = KeyguardUpdateMonitor.getCurrentUser();
        mKeyguardUpdateMonitor.onFaceAuthenticated(user, false /* isStrongBiometric */);
        assertThat(mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)).isFalse();
    }

    @Test
    public void testGetUserCanSkipBouncer_whenFingerprint() {
        int user = KeyguardUpdateMonitor.getCurrentUser();
        mKeyguardUpdateMonitor.onFingerprintAuthenticated(user, true /* isStrongBiometric */);
        assertThat(mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)).isTrue();
    }

    @Test
    public void testGetUserCanSkipBouncer_whenFingerprint_nonStrongAndDisallowed() {
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(false /* isStrongBiometric */))
                .thenReturn(false);
        int user = KeyguardUpdateMonitor.getCurrentUser();
        mKeyguardUpdateMonitor.onFingerprintAuthenticated(user, false /* isStrongBiometric */);
        assertThat(mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)).isFalse();
    }

    @Test
    public void testBiometricsCleared_whenUserSwitches() throws Exception {
        final IRemoteCallback reply = new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle data) {
            } // do nothing
        };
        final BiometricAuthenticated dummyAuthentication =
                new BiometricAuthenticated(true /* authenticated */, true /* strong */);
        mKeyguardUpdateMonitor.mUserFaceAuthenticated.put(0 /* user */, dummyAuthentication);
        mKeyguardUpdateMonitor.mUserFingerprintAuthenticated.put(0 /* user */, dummyAuthentication);
        assertThat(mKeyguardUpdateMonitor.mUserFingerprintAuthenticated.size()).isEqualTo(1);
        assertThat(mKeyguardUpdateMonitor.mUserFaceAuthenticated.size()).isEqualTo(1);

        mKeyguardUpdateMonitor.handleUserSwitching(10 /* user */, reply);
        assertThat(mKeyguardUpdateMonitor.mUserFingerprintAuthenticated.size()).isEqualTo(0);
        assertThat(mKeyguardUpdateMonitor.mUserFaceAuthenticated.size()).isEqualTo(0);
    }

    @Test
    public void testMultiUserJankMonitor_whenUserSwitches() throws Exception {
        final IRemoteCallback reply = new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle data) {
            } // do nothing
        };
        mKeyguardUpdateMonitor.handleUserSwitchComplete(10 /* user */);
        verify(mInteractionJankMonitor).end(InteractionJankMonitor.CUJ_USER_SWITCH);
        verify(mLatencyTracker).onActionEnd(LatencyTracker.ACTION_USER_SWITCH);
    }

    @Test
    public void testMultiUserLockoutChanged_whenUserSwitches() {
        testMultiUserLockout_whenUserSwitches(BiometricConstants.BIOMETRIC_LOCKOUT_PERMANENT,
                BiometricConstants.BIOMETRIC_LOCKOUT_PERMANENT);
    }

    @Test
    public void testMultiUserLockoutNotChanged_whenUserSwitches() {
        testMultiUserLockout_whenUserSwitches(BiometricConstants.BIOMETRIC_LOCKOUT_NONE,
                BiometricConstants.BIOMETRIC_LOCKOUT_NONE);
    }

    private void testMultiUserLockout_whenUserSwitches(
            @BiometricConstants.LockoutMode int fingerprintLockoutMode,
            @BiometricConstants.LockoutMode int faceLockoutMode) {
        final int newUser = 12;
        final boolean faceLocked =
                faceLockoutMode != BiometricConstants.BIOMETRIC_LOCKOUT_NONE;
        final boolean fpLocked =
                fingerprintLockoutMode != BiometricConstants.BIOMETRIC_LOCKOUT_NONE;

        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        keyguardIsVisible();

        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
        verify(mFingerprintManager).authenticate(any(), any(), any(), any(), anyInt(), anyInt(),
                anyInt());

        when(mFingerprintManager.getLockoutModeForUser(eq(FINGERPRINT_SENSOR_ID), eq(newUser)))
                .thenReturn(fingerprintLockoutMode);
        when(mFaceManager.getLockoutModeForUser(eq(FACE_SENSOR_ID), eq(newUser)))
                .thenReturn(faceLockoutMode);
        final CancellationSignal faceCancel = spy(mKeyguardUpdateMonitor.mFaceCancelSignal);
        final CancellationSignal fpCancel = spy(mKeyguardUpdateMonitor.mFingerprintCancelSignal);
        mKeyguardUpdateMonitor.mFaceCancelSignal = faceCancel;
        mKeyguardUpdateMonitor.mFingerprintCancelSignal = fpCancel;
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        mKeyguardUpdateMonitor.handleUserSwitchComplete(newUser);
        mTestableLooper.processAllMessages();

        // THEN face and fingerprint listening are always cancelled immediately
        verify(faceCancel).cancel();
        verify(callback).onBiometricRunningStateChanged(
                eq(false), eq(BiometricSourceType.FACE));
        verify(fpCancel).cancel();
        verify(callback).onBiometricRunningStateChanged(
                eq(false), eq(BiometricSourceType.FINGERPRINT));

        // THEN locked out states are updated
        assertThat(mKeyguardUpdateMonitor.isFingerprintLockedOut()).isEqualTo(fpLocked);
        assertThat(mKeyguardUpdateMonitor.isFaceLockedOut()).isEqualTo(faceLocked);

        // Fingerprint should be restarted once its cancelled bc on lockout, the device
        // can still detectFingerprint (and if it's not locked out, fingerprint can listen)
        assertThat(mKeyguardUpdateMonitor.mFingerprintRunningState)
                .isEqualTo(BIOMETRIC_STATE_CANCELLING_RESTARTING);
    }

    @Test
    public void testGetUserCanSkipBouncer_whenTrust() {
        int user = KeyguardUpdateMonitor.getCurrentUser();
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
        assertThat(mKeyguardUpdateMonitor.isUserUnlocked(KeyguardUpdateMonitor.getCurrentUser()))
                .isTrue();
        // Invalid user should not be unlocked.
        int randomUser = 99;
        assertThat(mKeyguardUpdateMonitor.isUserUnlocked(randomUser)).isFalse();
    }

    @Test
    public void testTrustUsuallyManaged_whenTrustChanges() {
        int user = KeyguardUpdateMonitor.getCurrentUser();
        when(mTrustManager.isTrustUsuallyManaged(eq(user))).thenReturn(true);
        mKeyguardUpdateMonitor.onTrustManagedChanged(false /* managed */, user);
        assertThat(mKeyguardUpdateMonitor.isTrustUsuallyManaged(user)).isTrue();
    }

    @Test
    public void testTrustUsuallyManaged_resetWhenUserIsRemoved() {
        int user = KeyguardUpdateMonitor.getCurrentUser();
        when(mTrustManager.isTrustUsuallyManaged(eq(user))).thenReturn(true);
        mKeyguardUpdateMonitor.onTrustManagedChanged(false /* managed */, user);
        assertThat(mKeyguardUpdateMonitor.isTrustUsuallyManaged(user)).isTrue();

        mKeyguardUpdateMonitor.handleUserRemoved(user);
        assertThat(mKeyguardUpdateMonitor.isTrustUsuallyManaged(user)).isFalse();
    }

    @Test
    public void testSecondaryLockscreenRequirement() {
        KeyguardUpdateMonitor.setCurrentUser(UserHandle.myUserId());
        when(mUserTracker.getUserId()).thenReturn(UserHandle.myUserId());
        int user = KeyguardUpdateMonitor.getCurrentUser();
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
        when(mStrongAuthTracker.getStrongAuthForUser(KeyguardUpdateMonitor.getCurrentUser()))
                .thenReturn(SOME_AUTH_REQUIRED_AFTER_USER_REQUEST);

        // THEN we should still listen for udfps
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(true);
    }

    @Test
    public void startsListeningForSfps_whenKeyguardIsVisible_ifRequireInteractiveToAuthEnabled()
            throws RemoteException {
        // SFPS supported and enrolled
        final ArrayList<FingerprintSensorPropertiesInternal> props = new ArrayList<>();
        props.add(newFingerprintSensorPropertiesInternal(TYPE_POWER_BUTTON));
        when(mAuthController.getSfpsProps()).thenReturn(props);
        when(mAuthController.isSfpsEnrolled(anyInt())).thenReturn(true);

        // WHEN require interactive to auth is disabled, and keyguard is not awake
        when(mInteractiveToAuthProvider.isEnabled(anyInt())).thenReturn(false);

        // Preconditions for sfps auth to run
        keyguardNotGoingAway();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();

        statusBarShadeIsLocked();
        mTestableLooper.processAllMessages();

        // THEN we should listen for sfps when screen off, because require screen on is disabled
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isTrue();

        // WHEN require interactive to auth is enabled, and keyguard is not awake
        when(mInteractiveToAuthProvider.isEnabled(anyInt())).thenReturn(true);

        // THEN we shouldn't listen for sfps when screen off, because require screen on is enabled
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isFalse();

        // Device now awake & keyguard is now interactive
        deviceNotGoingToSleep();
        deviceIsInteractive();
        keyguardIsVisible();

        // THEN we should listen for sfps when screen on, and require screen on is enabled
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isTrue();
    }

    @Test
    public void notListeningForSfps_whenGoingToSleep_ifRequireInteractiveToAuthEnabled()
            throws RemoteException {
        // GIVEN SFPS supported and enrolled
        final ArrayList<FingerprintSensorPropertiesInternal> props = new ArrayList<>();
        props.add(newFingerprintSensorPropertiesInternal(TYPE_POWER_BUTTON));
        when(mAuthController.getSfpsProps()).thenReturn(props);
        when(mAuthController.isSfpsEnrolled(anyInt())).thenReturn(true);

        // GIVEN Preconditions for sfps auth to run
        keyguardNotGoingAway();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        statusBarShadeIsLocked();

        // WHEN require interactive to auth is enabled & keyguard is going to sleep
        when(mInteractiveToAuthProvider.isEnabled(anyInt())).thenReturn(true);
        deviceGoingToSleep();

        mTestableLooper.processAllMessages();

        // THEN we should NOT listen for sfps because device is going to sleep
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isFalse();
    }

    @Test
    public void listeningForSfps_whenGoingToSleep_ifRequireInteractiveToAuthDisabled()
            throws RemoteException {
        // GIVEN SFPS supported and enrolled
        final ArrayList<FingerprintSensorPropertiesInternal> props = new ArrayList<>();
        props.add(newFingerprintSensorPropertiesInternal(TYPE_POWER_BUTTON));
        when(mAuthController.getSfpsProps()).thenReturn(props);
        when(mAuthController.isSfpsEnrolled(anyInt())).thenReturn(true);

        // GIVEN Preconditions for sfps auth to run
        keyguardNotGoingAway();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        statusBarShadeIsLocked();

        // WHEN require interactive to auth is disabled & keyguard is going to sleep
        when(mInteractiveToAuthProvider.isEnabled(anyInt())).thenReturn(false);
        deviceGoingToSleep();

        mTestableLooper.processAllMessages();

        // THEN we should listen for sfps because screen on to auth is  disabled
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(false)).isTrue();
    }

    private FingerprintSensorPropertiesInternal newFingerprintSensorPropertiesInternal(
            @FingerprintSensorProperties.SensorType int sensorType) {
        return new FingerprintSensorPropertiesInternal(
                0 /* sensorId */,
                SensorProperties.STRENGTH_STRONG,
                1 /* maxEnrollmentsPerUser */,
                new ArrayList<ComponentInfoInternal>(),
                sensorType,
                true /* resetLockoutRequiresHardwareAuthToken */);
    }

    @Test
    public void testShouldNotListenForUdfps_whenTrustEnabled() {
        // GIVEN a "we should listen for udfps" state
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);
        when(mStrongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);

        // WHEN trust is enabled (ie: via smartlock)
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */, true /* newlyUnlocked */,
                KeyguardUpdateMonitor.getCurrentUser(), 0 /* flags */, new ArrayList<>());

        // THEN we shouldn't listen for udfps
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(false);
    }

    @Test
    public void testShouldNotListenForUdfps_whenFaceAuthenticated() {
        // GIVEN a "we should listen for udfps" state
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);
        when(mStrongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);

        // WHEN face authenticated
        mKeyguardUpdateMonitor.onFaceAuthenticated(
                KeyguardUpdateMonitor.getCurrentUser(), false);

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
    public void testShouldNotUpdateBiometricListeningStateOnStatusBarStateChange() {
        // GIVEN state for face auth should run aside from StatusBarState
        biometricsNotDisabledThroughDevicePolicyManager();
        mStatusBarStateListener.onStateChanged(StatusBarState.SHADE_LOCKED);
        setKeyguardBouncerVisibility(false /* isVisible */);
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        lockscreenBypassIsAllowed();
        keyguardIsVisible();

        // WHEN status bar state reports a change to the keyguard that would normally indicate to
        // start running face auth
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isEqualTo(true);

        // THEN face unlock is not running b/c status bar state changes don't cause biometric
        // listening state to update
        assertThat(mKeyguardUpdateMonitor.isFaceDetectionRunning()).isEqualTo(false);

        // WHEN biometric listening state is updated when showing state changes from false => true
        mKeyguardUpdateMonitor.setKeyguardShowing(false, false);
        mKeyguardUpdateMonitor.setKeyguardShowing(true, false);

        // THEN face unlock is running
        assertThat(mKeyguardUpdateMonitor.isFaceDetectionRunning()).isEqualTo(true);
    }

    @Test
    public void testRequestFaceAuthFromOccludingApp_whenInvoked_startsFaceAuth() {
        mKeyguardUpdateMonitor.requestFaceAuthOnOccludingApp(true);

        assertThat(mKeyguardUpdateMonitor.isFaceDetectionRunning()).isTrue();
    }

    @Test
    public void testRequestFaceAuthFromOccludingApp_whenInvoked_stopsFaceAuth() {
        mKeyguardUpdateMonitor.requestFaceAuthOnOccludingApp(true);

        assertThat(mKeyguardUpdateMonitor.isFaceDetectionRunning()).isTrue();

        mKeyguardUpdateMonitor.requestFaceAuthOnOccludingApp(false);

        assertThat(mKeyguardUpdateMonitor.isFaceDetectionRunning()).isFalse();
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
    public void testFaceDoesNotAuth_afterPinAttempt() {
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.setCredentialAttempted();
        verify(mFingerprintManager, never()).authenticate(any(), any(), any(),
                any(), anyInt());
        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void testShowTrustGrantedMessage_onTrustGranted() {
        // WHEN trust is enabled (ie: via some trust agent) with a trustGranted string
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */, true /* newlyUnlocked */,
                KeyguardUpdateMonitor.getCurrentUser(), 0 /* flags */,
                Arrays.asList("Unlocked by wearable"));

        // THEN the showTrustGrantedMessage should be called with the first message
        verify(mTestCallback).onTrustGrantedForCurrentUser(
                anyBoolean() /* dismissKeyguard */,
                eq(true) /* newlyUnlocked */,
                eq(new TrustGrantFlags(0)),
                eq("Unlocked by wearable"));
    }

    @Test
    public void testShouldListenForFace_whenFaceManagerNotAvailable_returnsFalse() {
        cleanupKeyguardUpdateMonitor();
        mFaceManager = null;

        mKeyguardUpdateMonitor = new TestableKeyguardUpdateMonitor(mContext);

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
    }

    @Test
    public void testShouldListenForFace_whenFpIsLockedOut_returnsFalse() throws RemoteException {
        // Face auth should run when the following is true.
        keyguardNotGoingAway();
        occludingAppRequestsFaceAuth();
        currentUserIsPrimary();
        strongAuthNotRequired();
        biometricsEnabledForCurrentUser();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        userNotCurrentlySwitching();
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();

        // Fingerprint is locked out.
        fingerprintErrorLockedOut();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
    }

    @Test
    public void testShouldListenForFace_whenFaceIsAlreadyAuthenticated_returnsFalse()
            throws RemoteException {
        // Face auth should run when the following is true.
        bouncerFullyVisibleAndNotGoingToSleep();
        keyguardNotGoingAway();
        currentUserIsPrimary();
        strongAuthNotRequired();
        biometricsEnabledForCurrentUser();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        userNotCurrentlySwitching();

        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();

        triggerSuccessfulFaceAuth();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
    }

    @Test
    public void testShouldListenForFace_whenFpIsAlreadyAuthenticated_returnsFalse()
            throws RemoteException {
        // Face auth should run when the following is true.
        bouncerFullyVisibleAndNotGoingToSleep();
        keyguardNotGoingAway();
        currentUserIsPrimary();
        strongAuthNotRequired();
        biometricsEnabledForCurrentUser();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        userNotCurrentlySwitching();

        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();

        successfulFingerprintAuth();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
    }

    @Test
    public void testShouldListenForFace_whenUserIsNotPrimary_returnsFalse() throws RemoteException {
        cleanupKeyguardUpdateMonitor();
        // This disables face auth
        when(mUserManager.isPrimaryUser()).thenReturn(false);
        mKeyguardUpdateMonitor =
                new TestableKeyguardUpdateMonitor(mContext);

        // Face auth should run when the following is true.
        keyguardNotGoingAway();
        bouncerFullyVisibleAndNotGoingToSleep();
        strongAuthNotRequired();
        biometricsEnabledForCurrentUser();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        userNotCurrentlySwitching();
        mTestableLooper.processAllMessages();


        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
    }

    @Test
    public void testShouldListenForFace_whenStrongAuthDoesNotAllowScanning_returnsFalse()
            throws RemoteException {
        // Face auth should run when the following is true.
        keyguardNotGoingAway();
        bouncerFullyVisibleAndNotGoingToSleep();
        currentUserIsPrimary();
        biometricsEnabledForCurrentUser();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        userNotCurrentlySwitching();

        // This disables face auth
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
    }

    @Test
    public void testShouldListenForFace_whenBiometricsDisabledForUser_returnsFalse()
            throws RemoteException {
        keyguardNotGoingAway();
        bouncerFullyVisibleAndNotGoingToSleep();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();

        // This disables face auth
        biometricsDisabledForCurrentUser();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
    }

    @Test
    public void testShouldListenForFace_whenUserCurrentlySwitching_returnsFalse()
            throws RemoteException {
        // Face auth should run when the following is true.
        keyguardNotGoingAway();
        bouncerFullyVisibleAndNotGoingToSleep();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();

        userCurrentlySwitching();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
    }

    @Test
    public void testShouldListenForFace_whenSecureCameraLaunched_returnsFalse()
            throws RemoteException {
        keyguardNotGoingAway();
        bouncerFullyVisibleAndNotGoingToSleep();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();

        secureCameraLaunched();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
    }

    @Test
    public void testShouldListenForFace_whenOccludingAppRequestsFaceAuth_returnsTrue()
            throws RemoteException {
        // Face auth should run when the following is true.
        keyguardNotGoingAway();
        bouncerFullyVisibleAndNotGoingToSleep();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        mTestableLooper.processAllMessages();

        secureCameraLaunched();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();

        occludingAppRequestsFaceAuth();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();
    }

    @Test
    public void testShouldListenForFace_whenBouncerShowingAndDeviceIsAwake_returnsTrue()
            throws RemoteException {
        // Face auth should run when the following is true.
        keyguardNotGoingAway();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();

        bouncerFullyVisibleAndNotGoingToSleep();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();
    }

    @Test
    public void testShouldListenForFace_whenAuthInterruptIsActive_returnsTrue()
            throws RemoteException {
        // Face auth should run when the following is true.
        keyguardNotGoingAway();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();

        triggerAuthInterrupt();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();
    }

    @Test
    public void testShouldListenForFace_whenKeyguardIsAwake_returnsTrue() throws RemoteException {
        // Preconditions for face auth to run
        keyguardNotGoingAway();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();

        statusBarShadeIsLocked();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();

        deviceNotGoingToSleep();
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
        deviceIsInteractive();
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
        keyguardIsVisible();
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
        statusBarShadeIsNotLocked();
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();
    }

    @Test
    public void testShouldListenForFace_whenUdfpsFingerDown_returnsTrue() throws RemoteException {
        // Preconditions for face auth to run
        keyguardNotGoingAway();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        when(mAuthController.isUdfpsFingerDown()).thenReturn(false);
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();

        when(mAuthController.isUdfpsFingerDown()).thenReturn(true);
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();
    }

    @Test
    public void testShouldListenForFace_whenUdfpsBouncerIsShowing_returnsTrue()
            throws RemoteException {
        // Preconditions for face auth to run
        keyguardNotGoingAway();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();

        mKeyguardUpdateMonitor.setUdfpsBouncerShowing(true);

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();
    }

    @Test
    public void testShouldListenForFace_udfpsBouncerIsShowingButDeviceGoingToSleep_returnsFalse()
            throws RemoteException {
        // Preconditions for face auth to run
        keyguardNotGoingAway();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        deviceNotGoingToSleep();
        mKeyguardUpdateMonitor.setUdfpsBouncerShowing(true);
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();

        deviceGoingToSleep();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
    }

    @Test
    public void testShouldListenForFace_whenFaceIsLockedOut_returnsTrue()
            throws RemoteException {
        // Preconditions for face auth to run
        keyguardNotGoingAway();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        mKeyguardUpdateMonitor.setUdfpsBouncerShowing(true);
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();

        // Face is locked out.
        faceAuthLockedOut();
        mTestableLooper.processAllMessages();

        // This is needed beccause we want to show face locked out error message whenever face auth
        // is supposed to run.
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();
    }

    @Test
    public void testFingerprintCanAuth_whenCancellationNotReceivedAndAuthFailed() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        keyguardIsVisible();

        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
        verify(mFingerprintManager).authenticate(any(), any(), any(), any(), anyInt(), anyInt(),
                anyInt());

        mKeyguardUpdateMonitor.onFaceAuthenticated(0, false);
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
    public void testFingerAcquired_wakesUpPowerManager() {
        cleanupKeyguardUpdateMonitor();
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.kg_wake_on_acquire_start, true);
        mKeyguardUpdateMonitor = new TestableKeyguardUpdateMonitor(mContext);
        fingerprintAcquireStart();

        verify(mPowerManager).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    public void testFingerAcquired_doesNotWakeUpPowerManager() {
        cleanupKeyguardUpdateMonitor();
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.kg_wake_on_acquire_start, false);
        mKeyguardUpdateMonitor = new TestableKeyguardUpdateMonitor(mContext);
        fingerprintAcquireStart();

        verify(mPowerManager, never()).wakeUp(anyLong(), anyInt(), anyString());
    }

    @Test
    public void testDreamingStopped_faceDoesNotRun() {
        mKeyguardUpdateMonitor.dispatchDreamingStopped();
        mTestableLooper.processAllMessages();

        verify(mFaceManager, never()).authenticate(
                any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    public void testFaceWakeupTrigger_runFaceAuth_onlyOnConfiguredTriggers() {
        // keyguard is visible
        keyguardIsVisible();

        // WHEN device wakes up from an application
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_APPLICATION);
        mTestableLooper.processAllMessages();

        // THEN face auth isn't triggered
        verify(mFaceManager, never()).authenticate(
                any(), any(), any(), any(), anyInt(), anyBoolean());

        // WHEN device wakes up from the power button
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();

        // THEN face auth is triggered
        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
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
                getCurrentUser() /* userId */,
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
                getCurrentUser() /* userId */,
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
                getCurrentUser() /* userId */,
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
                getCurrentUser() /* userId, not the current userId */,
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
                getCurrentUser() /* userId, not the current userId */,
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
    public void testStrongAuthChange_lockDown_stopsFpAndFaceListeningState() {
        // GIVEN device is listening for face and fingerprint
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        keyguardIsVisible();

        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
        verify(mFingerprintManager).authenticate(any(), any(), any(), any(), anyInt(), anyInt(),
                anyInt());

        final CancellationSignal faceCancel = spy(mKeyguardUpdateMonitor.mFaceCancelSignal);
        final CancellationSignal fpCancel = spy(mKeyguardUpdateMonitor.mFingerprintCancelSignal);
        mKeyguardUpdateMonitor.mFaceCancelSignal = faceCancel;
        mKeyguardUpdateMonitor.mFingerprintCancelSignal = fpCancel;
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        // WHEN strong auth changes and device is in user lockdown
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        userDeviceLockDown();
        mKeyguardUpdateMonitor.notifyStrongAuthAllowedChanged(getCurrentUser());
        mTestableLooper.processAllMessages();

        // THEN face and fingerprint listening are cancelled
        verify(faceCancel).cancel();
        verify(callback).onBiometricRunningStateChanged(
                eq(false), eq(BiometricSourceType.FACE));
        verify(fpCancel).cancel();
        verify(callback).onBiometricRunningStateChanged(
                eq(false), eq(BiometricSourceType.FINGERPRINT));
    }

    @Test
    public void testNonStrongBiometricAllowedChanged_stopsFaceListeningState() {
        // GIVEN device is listening for face and fingerprint
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
        mTestableLooper.processAllMessages();
        keyguardIsVisible();

        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());

        final CancellationSignal faceCancel = spy(mKeyguardUpdateMonitor.mFaceCancelSignal);
        mKeyguardUpdateMonitor.mFaceCancelSignal = faceCancel;
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        // WHEN non-strong biometric allowed changes
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        mKeyguardUpdateMonitor.notifyNonStrongBiometricAllowedChanged(getCurrentUser());
        mTestableLooper.processAllMessages();

        // THEN face and fingerprint listening are cancelled
        verify(faceCancel).cancel();
        verify(callback).onBiometricRunningStateChanged(
                eq(false), eq(BiometricSourceType.FACE));
    }

    @Test
    public void testShouldListenForFace_withLockedDown_returnsFalse()
            throws RemoteException {
        keyguardNotGoingAway();
        bouncerFullyVisibleAndNotGoingToSleep();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        supportsFaceDetection();
        mTestableLooper.processAllMessages();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();

        userDeviceLockDown();

        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();
    }

    @Test
    public void fingerprintFailure_requestActiveUnlock_dismissKeyguard()
            throws RemoteException {
        // GIVEN shouldTriggerActiveUnlock
        bouncerFullyVisible();
        when(mLockPatternUtils.isSecure(KeyguardUpdateMonitor.getCurrentUser())).thenReturn(true);

        // GIVEN active unlock triggers on biometric failures
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.BIOMETRIC_FAIL))
                .thenReturn(true);

        // WHEN fingerprint fails
        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback.onAuthenticationFailed();

        // ALWAYS request unlock with a keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(eq(KeyguardUpdateMonitor.getCurrentUser()),
                eq(true));
    }

    @Test
    public void faceNonBypassFailure_requestActiveUnlock_doesNotDismissKeyguard()
            throws RemoteException {
        // GIVEN shouldTriggerActiveUnlock
        when(mAuthController.isUdfpsFingerDown()).thenReturn(false);
        keyguardIsVisible();
        keyguardNotGoingAway();
        statusBarShadeIsNotLocked();
        when(mLockPatternUtils.isSecure(KeyguardUpdateMonitor.getCurrentUser())).thenReturn(true);

        // GIVEN active unlock triggers on biometric failures
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.BIOMETRIC_FAIL))
                .thenReturn(true);

        // WHEN face fails & bypass is not allowed
        lockscreenBypassIsNotAllowed();
        mKeyguardUpdateMonitor.mFaceAuthenticationCallback.onAuthenticationFailed();

        // THEN request unlock with NO keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(eq(KeyguardUpdateMonitor.getCurrentUser()),
                eq(false));
    }

    @Test
    public void faceBypassFailure_requestActiveUnlock_dismissKeyguard()
            throws RemoteException {
        // GIVEN shouldTriggerActiveUnlock
        when(mAuthController.isUdfpsFingerDown()).thenReturn(false);
        keyguardIsVisible();
        keyguardNotGoingAway();
        statusBarShadeIsNotLocked();
        when(mLockPatternUtils.isSecure(KeyguardUpdateMonitor.getCurrentUser())).thenReturn(true);

        // GIVEN active unlock triggers on biometric failures
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.BIOMETRIC_FAIL))
                .thenReturn(true);

        // WHEN face fails & bypass is not allowed
        lockscreenBypassIsAllowed();
        mKeyguardUpdateMonitor.mFaceAuthenticationCallback.onAuthenticationFailed();

        // THEN request unlock with a keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(eq(KeyguardUpdateMonitor.getCurrentUser()),
                eq(true));
    }

    @Test
    public void faceNonBypassFailure_requestActiveUnlock_dismissKeyguard()
            throws RemoteException {
        // GIVEN shouldTriggerActiveUnlock
        when(mAuthController.isUdfpsFingerDown()).thenReturn(false);
        lockscreenBypassIsNotAllowed();
        when(mLockPatternUtils.isSecure(KeyguardUpdateMonitor.getCurrentUser())).thenReturn(true);

        // GIVEN active unlock triggers on biometric failures
        when(mActiveUnlockConfig.shouldAllowActiveUnlockFromOrigin(
                ActiveUnlockConfig.ACTIVE_UNLOCK_REQUEST_ORIGIN.BIOMETRIC_FAIL))
                .thenReturn(true);

        // WHEN face fails & on the bouncer
        bouncerFullyVisible();
        mKeyguardUpdateMonitor.mFaceAuthenticationCallback.onAuthenticationFailed();

        // THEN request unlock with a keyguard dismissal
        verify(mTrustManager).reportUserRequestedUnlock(eq(KeyguardUpdateMonitor.getCurrentUser()),
                eq(true));
    }

    @Test
    public void testShouldListenForFace_withAuthSupportPostureConfig_returnsTrue()
            throws RemoteException {
        mKeyguardUpdateMonitor.mConfigFaceAuthSupportedPosture = DEVICE_POSTURE_CLOSED;
        keyguardNotGoingAway();
        bouncerFullyVisibleAndNotGoingToSleep();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        supportsFaceDetection();

        deviceInPostureStateOpened();
        mTestableLooper.processAllMessages();
        // Should not listen for face when posture state in DEVICE_POSTURE_OPENED
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isFalse();

        deviceInPostureStateClosed();
        mTestableLooper.processAllMessages();
        // Should listen for face when posture state in DEVICE_POSTURE_CLOSED
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();
    }

    @Test
    public void testShouldListenForFace_withoutAuthSupportPostureConfig_returnsTrue()
            throws RemoteException {
        mKeyguardUpdateMonitor.mConfigFaceAuthSupportedPosture = DEVICE_POSTURE_UNKNOWN;
        keyguardNotGoingAway();
        bouncerFullyVisibleAndNotGoingToSleep();
        currentUserIsPrimary();
        currentUserDoesNotHaveTrust();
        biometricsNotDisabledThroughDevicePolicyManager();
        biometricsEnabledForCurrentUser();
        userNotCurrentlySwitching();
        supportsFaceDetection();

        deviceInPostureStateClosed();
        mTestableLooper.processAllMessages();
        // Whether device in any posture state, always listen for face
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();

        deviceInPostureStateOpened();
        mTestableLooper.processAllMessages();
        // Whether device in any posture state, always listen for face
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isTrue();
    }

    private void userDeviceLockDown() {
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
        when(mStrongAuthTracker.getStrongAuthForUser(mCurrentUserId))
                .thenReturn(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);
    }

    private void supportsFaceDetection() {
        when(mFaceSensorProperties.get(anyInt()))
                .thenReturn(createFaceSensorProperties(
                        /* supportsFaceDetection = */ true));
    }

    private void lockscreenBypassIsAllowed() {
        mockCanBypassLockscreen(true);
    }

    private void mockCanBypassLockscreen(boolean canBypass) {
        // force update the isFaceEnrolled cache:
        mKeyguardUpdateMonitor.isFaceAuthEnabledForUser(getCurrentUser());

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

    private void faceAuthLockedOut() {
        mKeyguardUpdateMonitor.mFaceAuthenticationCallback
                .onAuthenticationError(FaceManager.FACE_ERROR_LOCKOUT_PERMANENT, "");
    }

    private void statusBarShadeIsNotLocked() {
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);
    }

    private void statusBarShadeIsLocked() {
        mStatusBarStateListener.onStateChanged(StatusBarState.SHADE_LOCKED);
    }

    private void keyguardIsVisible() {
        mKeyguardUpdateMonitor.setKeyguardShowing(true, false);
    }

    private void triggerAuthInterrupt() {
        mKeyguardUpdateMonitor.onAuthInterruptDetected(true);
    }

    private void occludingAppRequestsFaceAuth() {
        mKeyguardUpdateMonitor.requestFaceAuthOnOccludingApp(true);
    }

    private void secureCameraLaunched() {
        mKeyguardUpdateMonitor.onCameraLaunched();
    }

    private void userCurrentlySwitching() {
        mKeyguardUpdateMonitor.setSwitchingUser(true);
    }

    private void fingerprintErrorLockedOut() {
        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback
                .onAuthenticationError(FINGERPRINT_ERROR_LOCKOUT, "Fingerprint locked out");
    }

    private void fingerprintAcquireStart() {
        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback
                .onAuthenticationAcquired(FINGERPRINT_ACQUIRED_START);
    }

    private void deviceInPostureStateOpened() {
        mKeyguardUpdateMonitor.mPostureCallback.onPostureChanged(DEVICE_POSTURE_OPENED);
    }

    private void deviceInPostureStateClosed() {
        mKeyguardUpdateMonitor.mPostureCallback.onPostureChanged(DEVICE_POSTURE_CLOSED);
    }

    private void successfulFingerprintAuth() {
        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback
                .onAuthenticationSucceeded(
                        new FingerprintManager.AuthenticationResult(null,
                                null,
                                mCurrentUserId,
                                true));
    }

    private void triggerSuccessfulFaceAuth() {
        mKeyguardUpdateMonitor.requestFaceAuth(FaceAuthApiRequestReason.UDFPS_POINTER_DOWN);
        verify(mFaceManager).authenticate(any(),
                any(),
                mAuthenticationCallbackCaptor.capture(),
                any(),
                anyInt(),
                anyBoolean());
        mAuthenticationCallbackCaptor.getValue()
                .onAuthenticationSucceeded(
                        new FaceManager.AuthenticationResult(null, null, mCurrentUserId, false));
    }

    private void currentUserIsPrimary() {
        when(mUserManager.isPrimaryUser()).thenReturn(true);
    }

    private void biometricsNotDisabledThroughDevicePolicyManager() {
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null,
                KeyguardUpdateMonitor.getCurrentUser())).thenReturn(0);
    }

    private void biometricsEnabledForCurrentUser() throws RemoteException {
        mBiometricEnabledOnKeyguardCallback.onChanged(true, KeyguardUpdateMonitor.getCurrentUser());
    }

    private void biometricsDisabledForCurrentUser() throws RemoteException {
        mBiometricEnabledOnKeyguardCallback.onChanged(
                false,
                KeyguardUpdateMonitor.getCurrentUser()
        );
    }

    private void strongAuthRequiredEncrypted() {
        when(mStrongAuthTracker.getStrongAuthForUser(KeyguardUpdateMonitor.getCurrentUser()))
                .thenReturn(STRONG_AUTH_REQUIRED_AFTER_BOOT);
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(false);
    }

    private void strongAuthNotRequired() {
        when(mStrongAuthTracker.getStrongAuthForUser(KeyguardUpdateMonitor.getCurrentUser()))
                .thenReturn(0);
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed(anyBoolean())).thenReturn(true);
    }

    private void currentUserDoesNotHaveTrust() {
        mKeyguardUpdateMonitor.onTrustChanged(
                false,
                false,
                KeyguardUpdateMonitor.getCurrentUser(),
                -1,
                new ArrayList<>()
        );
    }

    private void userNotCurrentlySwitching() {
        mKeyguardUpdateMonitor.setSwitchingUser(false);
    }

    private void keyguardNotGoingAway() {
        mKeyguardUpdateMonitor.setKeyguardGoingAway(false);
    }

    private void bouncerFullyVisibleAndNotGoingToSleep() {
        bouncerFullyVisible();
        deviceNotGoingToSleep();
    }

    private void deviceNotGoingToSleep() {
        mKeyguardUpdateMonitor.dispatchFinishedGoingToSleep(/* value doesn't matter */1);
    }

    private void deviceGoingToSleep() {
        mKeyguardUpdateMonitor.dispatchStartedGoingToSleep(/* value doesn't matter */1);
    }

    private void deviceIsInteractive() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp(PowerManager.WAKE_REASON_POWER_BUTTON);
    }

    private void bouncerFullyVisible() {
        setKeyguardBouncerVisibility(true);
    }

    private void bouncerNotVisible() {
        setKeyguardBouncerVisibility(false);
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

    private Intent putPhoneInfo(Intent intent, Bundle data, Boolean simInited) {
        int subscription = simInited
                ? 1/* mock subid=1 */ : SubscriptionManager.PLACEHOLDER_SUBSCRIPTION_ID_BASE;
        if (data != null) intent.putExtras(data);

        intent.putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, subscription);
        intent.putExtra(SubscriptionManager.EXTRA_SLOT_INDEX, 0);
        return intent;
    }

    private class TestableKeyguardUpdateMonitor extends KeyguardUpdateMonitor {
        AtomicBoolean mSimStateChanged = new AtomicBoolean(false);

        protected TestableKeyguardUpdateMonitor(Context context) {
            super(context, mUserTracker,
                    TestableLooper.get(KeyguardUpdateMonitorTest.this).getLooper(),
                    mBroadcastDispatcher, mSecureSettings, mDumpManager,
                    mBackgroundExecutor, mMainExecutor,
                    mStatusBarStateController, mLockPatternUtils,
                    mAuthController, mTelephonyListenerManager,
                    mInteractionJankMonitor, mLatencyTracker, mActiveUnlockConfig,
                    mKeyguardUpdateMonitorLogger, mUiEventLogger, () -> mSessionTracker,
                    mPowerManager, mTrustManager, mSubscriptionManager, mUserManager,
                    mDreamManager, mDevicePolicyManager, mSensorPrivacyManager, mTelephonyManager,
                    mPackageManager, mFaceManager, mFingerprintManager, mBiometricManager,
                    mFaceWakeUpTriggersConfig, mDevicePostureController,
                    Optional.of(mInteractiveToAuthProvider));
            setStrongAuthTracker(KeyguardUpdateMonitorTest.this.mStrongAuthTracker);
        }

        public boolean hasSimStateJustChanged() {
            return mSimStateChanged.getAndSet(false);
        }

        @Override
        protected void handleSimStateChange(int subId, int slotId, int state) {
            mSimStateChanged.set(true);
            super.handleSimStateChange(subId, slotId, state);
        }

        @Override
        protected int getBiometricLockoutDelay() {
            return 0;
        }
    }
}
