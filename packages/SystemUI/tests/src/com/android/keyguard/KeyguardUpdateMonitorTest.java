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

import static android.telephony.SubscriptionManager.DATA_ROAMING_DISABLE;
import static android.telephony.SubscriptionManager.NAME_SOURCE_CARRIER_ID;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.IStrongAuthTracker;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.hardware.biometrics.BiometricConstants;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.ComponentInfoInternal;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.face.FaceManager;
import android.hardware.face.FaceSensorProperties;
import android.hardware.face.FaceSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor.BiometricAuthenticated;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.telephony.TelephonyListenerManager;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private SubscriptionManager mSubscriptionManager;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private TelephonyManager mTelephonyManager;
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
    // Direct executor
    private Executor mBackgroundExecutor = Runnable::run;
    private Executor mMainExecutor = Runnable::run;
    private TestableLooper mTestableLooper;
    private TestableKeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private TestableContext mSpiedContext;
    private MockitoSession mMockitoSession;
    private StatusBarStateController.StateListener mStatusBarStateListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mSpiedContext = spy(mContext);
        when(mPackageManager.hasSystemFeature(anyString())).thenReturn(true);
        when(mSpiedContext.getPackageManager()).thenReturn(mPackageManager);
        doAnswer(invocation -> {
            IBiometricEnabledOnKeyguardCallback callback = invocation.getArgument(0);
            callback.onChanged(true /* enabled */, KeyguardUpdateMonitor.getCurrentUser());
            return null;
        }).when(mBiometricManager).registerEnabledOnKeyguardCallback(any());
        when(mFaceManager.isHardwareDetected()).thenReturn(true);
        when(mFaceManager.hasEnrolledTemplates()).thenReturn(true);
        when(mFaceManager.hasEnrolledTemplates(anyInt())).thenReturn(true);
        when(mFaceManager.getSensorPropertiesInternal()).thenReturn(mFaceSensorProperties);

        // IBiometricsFace@1.0 does not support detection, only authentication.
        when(mFaceSensorProperties.isEmpty()).thenReturn(false);

        final List<ComponentInfoInternal> componentInfo = new ArrayList<>();
        componentInfo.add(new ComponentInfoInternal("faceSensor" /* componentId */,
                "vendor/model/revision" /* hardwareVersion */, "1.01" /* firmwareVersion */,
                "00000001" /* serialNumber */, "" /* softwareVersion */));
        componentInfo.add(new ComponentInfoInternal("matchingAlgorithm" /* componentId */,
                "" /* hardwareVersion */, "" /* firmwareVersion */, "" /* serialNumber */,
                "vendor/version/revision" /* softwareVersion */));

        when(mFaceSensorProperties.get(anyInt())).thenReturn(new FaceSensorPropertiesInternal(
                0 /* id */,
                FaceSensorProperties.STRENGTH_STRONG, 1 /* maxTemplatesAllowed */,
                componentInfo, FaceSensorProperties.TYPE_UNKNOWN,
                false /* supportsFaceDetection */, true /* supportsSelfIllumination */,
                false /* resetLockoutRequiresChallenge */));

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
        mSpiedContext.addMockSystemService(TrustManager.class, mTrustManager);
        mSpiedContext.addMockSystemService(FingerprintManager.class, mFingerprintManager);
        mSpiedContext.addMockSystemService(BiometricManager.class, mBiometricManager);
        mSpiedContext.addMockSystemService(FaceManager.class, mFaceManager);
        mSpiedContext.addMockSystemService(UserManager.class, mUserManager);
        mSpiedContext.addMockSystemService(DevicePolicyManager.class, mDevicePolicyManager);
        mSpiedContext.addMockSystemService(SubscriptionManager.class, mSubscriptionManager);
        mSpiedContext.addMockSystemService(TelephonyManager.class, mTelephonyManager);

        mMockitoSession = ExtendedMockito.mockitoSession()
                .spyStatic(SubscriptionManager.class)
                .spyStatic(ActivityManager.class)
                .startMocking();
        ExtendedMockito.doReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                .when(SubscriptionManager::getDefaultSubscriptionId);
        ExtendedMockito.doReturn(KeyguardUpdateMonitor.getCurrentUser())
                .when(ActivityManager::getCurrentUser);

        mTestableLooper = TestableLooper.get(this);
        allowTestableLooperAsMainThread();
        mKeyguardUpdateMonitor = new TestableKeyguardUpdateMonitor(mSpiedContext);

        verify(mStatusBarStateController).addCallback(mStatusBarStateListenerCaptor.capture());
        mStatusBarStateListener = mStatusBarStateListenerCaptor.getValue();
        mKeyguardUpdateMonitor.registerCallback(mTestCallback);

        mTestableLooper.processAllMessages();
        when(mAuthController.areAllAuthenticatorsRegistered()).thenReturn(true);
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
        mKeyguardUpdateMonitor.removeCallback(mTestCallback);
        mKeyguardUpdateMonitor.destroy();
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
        final int subId = 3;
        final int state = TelephonyManager.SIM_STATE_ABSENT;

        when(mTelephonyManager.getActiveModemCount()).thenReturn(1);
        when(mTelephonyManager.getSimState(anyInt())).thenReturn(state);
        when(mSubscriptionManager.getSubscriptionIds(anyInt())).thenReturn(new int[] { subId });

        KeyguardUpdateMonitor testKUM = new TestableKeyguardUpdateMonitor(mSpiedContext);

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

        Intent intentServiceState =  new Intent(Intent.ACTION_SERVICE_STATE);
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
        when(mAuthController.areAllAuthenticatorsRegistered()).thenReturn(false);

        mKeyguardUpdateMonitor.dispatchStartedGoingToSleep(0 /* why */);
        mTestableLooper.processAllMessages();

        verify(mFingerprintManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyInt(), anyInt());
        verify(mFingerprintManager, never()).detectFingerprint(any(), any(), anyInt());
    }

    @Test
    public void testFingerprintDoesNotAuth_whenEncrypted() {
        testFingerprintWhenStrongAuth(
                STRONG_AUTH_REQUIRED_AFTER_BOOT);
    }

    @Test
    public void testFingerprintDoesNotAuth_whenDpmLocked() {
        testFingerprintWhenStrongAuth(
                KeyguardUpdateMonitor.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW);
    }

    @Test
    public void testFingerprintDoesNotAuth_whenUserLockdown() {
        testFingerprintWhenStrongAuth(
                KeyguardUpdateMonitor.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);
    }

    private void testFingerprintWhenStrongAuth(int strongAuth) {
        // Clear invocations, since previous setup (e.g. registering BiometricManager callbacks)
        // will trigger updateBiometricListeningState();
        clearInvocations(mFingerprintManager);
        mKeyguardUpdateMonitor.resetBiometricListeningState();

        when(mStrongAuthTracker.getStrongAuthForUser(anyInt())).thenReturn(strongAuth);
        mKeyguardUpdateMonitor.dispatchStartedGoingToSleep(0 /* why */);
        mTestableLooper.processAllMessages();

        verify(mFingerprintManager, never()).authenticate(any(), any(), any(), any(), anyInt());
        verify(mFingerprintManager).detectFingerprint(any(), any(), anyInt());
    }

    @Test
    public void testTriesToAuthenticate_whenBouncer() {
        setKeyguardBouncerVisibility(true);

        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
        verify(mFaceManager).isHardwareDetected();
        verify(mFaceManager).hasEnrolledTemplates(anyInt());
    }

    @Test
    public void testTriesToAuthenticate_whenKeyguard() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    public void skipsAuthentication_whenStatusBarShadeLocked() {
        mStatusBarStateListener.onStateChanged(StatusBarState.SHADE_LOCKED);
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();

        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void skipsAuthentication_whenEncryptedKeyguard() {
        when(mStrongAuthTracker.getStrongAuthForUser(anyInt())).thenReturn(
                STRONG_AUTH_REQUIRED_AFTER_BOOT);
        mKeyguardUpdateMonitor.setKeyguardBypassController(mKeyguardBypassController);

        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void requiresAuthentication_whenEncryptedKeyguard_andBypass() {
        testStrongAuthExceptOnBouncer(
                STRONG_AUTH_REQUIRED_AFTER_BOOT);
    }

    @Test
    public void requiresAuthentication_whenTimeoutKeyguard_andBypass() {
        testStrongAuthExceptOnBouncer(
                KeyguardUpdateMonitor.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT);
    }

    private void testStrongAuthExceptOnBouncer(int strongAuth) {
        when(mKeyguardBypassController.canBypass()).thenReturn(true);
        mKeyguardUpdateMonitor.setKeyguardBypassController(mKeyguardBypassController);
        when(mStrongAuthTracker.getStrongAuthForUser(anyInt())).thenReturn(strongAuth);

        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());

        // Stop scanning when bouncer becomes visible
        setKeyguardBouncerVisibility(true);
        clearInvocations(mFaceManager);
        mKeyguardUpdateMonitor.requestFaceAuth(true);
        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void testTriesToAuthenticate_whenAssistant() {
        mKeyguardUpdateMonitor.setKeyguardOccluded(true);
        mKeyguardUpdateMonitor.setAssistantVisible(true);

        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    public void testTriesToAuthenticate_whenTrustOnAgentKeyguard_ifBypass() {
        mKeyguardUpdateMonitor.setKeyguardBypassController(mKeyguardBypassController);
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        when(mKeyguardBypassController.canBypass()).thenReturn(true);
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */,
                KeyguardUpdateMonitor.getCurrentUser(), 0 /* flags */,
                new ArrayList<>());
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    public void testIgnoresAuth_whenTrustAgentOnKeyguard_withoutBypass() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */,
                KeyguardUpdateMonitor.getCurrentUser(), 0 /* flags */, new ArrayList<>());
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void testIgnoresAuth_whenLockdown() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        when(mStrongAuthTracker.getStrongAuthForUser(anyInt())).thenReturn(
                KeyguardUpdateMonitor.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);

        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void testTriesToAuthenticate_whenLockout() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        when(mStrongAuthTracker.getStrongAuthForUser(anyInt())).thenReturn(
                KeyguardUpdateMonitor.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT);

        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    public void testOnFaceAuthenticated_skipsFaceWhenAuthenticated() {
        // test whether face will be skipped if authenticated, so the value of isStrongBiometric
        // doesn't matter here
        mKeyguardUpdateMonitor.onFaceAuthenticated(KeyguardUpdateMonitor.getCurrentUser(),
                true /* isStrongBiometric */);
        mKeyguardUpdateMonitor.sendKeyguardBouncerChanged(true);
        mTestableLooper.processAllMessages();

        verify(mFaceManager, never()).authenticate(any(), any(), any(), any(), anyInt(),
                anyBoolean());
    }

    @Test
    public void testFaceAndFingerprintLockout_onlyFace() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);

        mKeyguardUpdateMonitor.mFaceAuthenticationCallback
                .onAuthenticationError(FaceManager.FACE_ERROR_LOCKOUT_PERMANENT, "");

        verify(mLockPatternUtils, never()).requireStrongAuth(anyInt(), anyInt());
    }

    @Test
    public void testFaceAndFingerprintLockout_onlyFingerprint() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);

        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback
                .onAuthenticationError(FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT, "");

        verify(mLockPatternUtils).requireStrongAuth(anyInt(), anyInt());
    }


    @Test
    public void testFaceAndFingerprintLockout() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);

        mKeyguardUpdateMonitor.mFaceAuthenticationCallback
                .onAuthenticationError(FaceManager.FACE_ERROR_LOCKOUT_PERMANENT, "");
        mKeyguardUpdateMonitor.mFingerprintAuthenticationCallback
                .onAuthenticationError(FingerprintManager.FINGERPRINT_ERROR_LOCKOUT_PERMANENT, "");

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
            public void sendResult(Bundle data) {} // do nothing
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
            public void sendResult(Bundle data) {} // do nothing
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
        when(mFingerprintManager.getLockoutModeForUser(eq(FINGERPRINT_SENSOR_ID), eq(newUser)))
                .thenReturn(fingerprintLockoutMode);
        when(mFaceManager.getLockoutModeForUser(eq(FACE_SENSOR_ID), eq(newUser)))
                .thenReturn(faceLockoutMode);

        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);

        verify(mFaceManager).authenticate(any(), any(), any(), any(), anyInt(), anyBoolean());
        verify(mFingerprintManager).authenticate(any(), any(), any(), any(), anyInt(), anyInt(),
                anyInt());

        final CancellationSignal faceCancel = spy(mKeyguardUpdateMonitor.mFaceCancelSignal);
        final CancellationSignal fpCancel = spy(mKeyguardUpdateMonitor.mFingerprintCancelSignal);
        mKeyguardUpdateMonitor.mFaceCancelSignal = faceCancel;
        mKeyguardUpdateMonitor.mFingerprintCancelSignal = fpCancel;
        KeyguardUpdateMonitorCallback callback = mock(KeyguardUpdateMonitorCallback.class);
        mKeyguardUpdateMonitor.registerCallback(callback);

        mKeyguardUpdateMonitor.handleUserSwitchComplete(newUser);
        mTestableLooper.processAllMessages();

        verify(faceCancel, faceLocked ? times(1) : never()).cancel();
        verify(fpCancel, fpLocked ? times(1) : never()).cancel();
        verify(callback, faceLocked ? times(1) : never()).onBiometricRunningStateChanged(
                eq(false), eq(BiometricSourceType.FACE));
        verify(callback, fpLocked ? times(1) : never()).onBiometricRunningStateChanged(
                eq(false), eq(BiometricSourceType.FINGERPRINT));
        assertThat(mKeyguardUpdateMonitor.isFingerprintLockedOut()).isEqualTo(fpLocked);
        assertThat(mKeyguardUpdateMonitor.isFaceLockedOut()).isEqualTo(faceLocked);
    }

    @Test
    public void testGetUserCanSkipBouncer_whenTrust() {
        int user = KeyguardUpdateMonitor.getCurrentUser();
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */, user, 0 /* flags */,
                new ArrayList<>());
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
                .getFilteredSubscriptionInfo(false);
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
        callback.onEnrollmentsChanged();

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
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mKeyguardUpdateMonitor.setKeyguardOccluded(true);
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(false);
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
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mKeyguardUpdateMonitor.setKeyguardOccluded(true);
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(false);

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

        // WHEN user hasn't authenticated since last boot
        when(mStrongAuthTracker.getStrongAuthForUser(KeyguardUpdateMonitor.getCurrentUser()))
                .thenReturn(STRONG_AUTH_REQUIRED_AFTER_BOOT);

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
    public void testShouldNotListenForUdfps_whenTrustEnabled() {
        // GIVEN a "we should listen for udfps" state
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);
        when(mStrongAuthTracker.hasUserAuthenticatedSinceBoot()).thenReturn(true);

        // WHEN trust is enabled (ie: via smartlock)
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */,
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
        when(mStrongAuthTracker.getStrongAuthForUser(anyInt())).thenReturn(
                KeyguardUpdateMonitor.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);

        // THEN we shouldn't listen for udfps
        assertThat(mKeyguardUpdateMonitor.shouldListenForFingerprint(true)).isEqualTo(false);
    }

    @Test
    public void testShouldNotUpdateBiometricListeningStateOnStatusBarStateChange() {
        // GIVEN state for face auth should run aside from StatusBarState
        when(mDevicePolicyManager.getKeyguardDisabledFeatures(null,
                KeyguardUpdateMonitor.getCurrentUser())).thenReturn(0);
        mStatusBarStateListener.onStateChanged(StatusBarState.SHADE_LOCKED);
        setKeyguardBouncerVisibility(false /* isVisible */);
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        when(mKeyguardBypassController.canBypass()).thenReturn(true);
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);

        // WHEN status bar state reports a change to the keyguard that would normally indicate to
        // start running face auth
        mStatusBarStateListener.onStateChanged(StatusBarState.KEYGUARD);
        assertThat(mKeyguardUpdateMonitor.shouldListenForFace()).isEqualTo(true);

        // THEN face unlock is not running b/c status bar state changes don't cause biometric
        // listening state to update
        assertThat(mKeyguardUpdateMonitor.isFaceUnlockRunning(
                KeyguardUpdateMonitor.getCurrentUser())).isEqualTo(false);

        // WHEN biometric listening state is updated
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);

        // THEN face unlock is running
        assertThat(mKeyguardUpdateMonitor.isFaceDetectionRunning()).isEqualTo(true);
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
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */,
                KeyguardUpdateMonitor.getCurrentUser(), 0 /* flags */,
                Arrays.asList("Unlocked by wearable"));

        // THEN the showTrustGrantedMessage should be called with the first message
        verify(mTestCallback).showTrustGrantedMessage("Unlocked by wearable");
    }

    private void setKeyguardBouncerVisibility(boolean isVisible) {
        mKeyguardUpdateMonitor.sendKeyguardBouncerChanged(isVisible);
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
            super(context,
                    TestableLooper.get(KeyguardUpdateMonitorTest.this).getLooper(),
                    mBroadcastDispatcher, mDumpManager,
                    mBackgroundExecutor, mMainExecutor,
                    mStatusBarStateController, mLockPatternUtils,
                    mAuthController, mTelephonyListenerManager,
                    mInteractionJankMonitor, mLatencyTracker);
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
