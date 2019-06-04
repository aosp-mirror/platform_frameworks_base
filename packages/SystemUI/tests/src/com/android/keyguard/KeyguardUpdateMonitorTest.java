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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.os.UserManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.systemui.SysuiTestCase;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.atomic.AtomicBoolean;

@SmallTest
@RunWith(AndroidTestingRunner.class)
// We must run on the main looper because KeyguardUpdateMonitor#mHandler is initialized with
// new Handler(Looper.getMainLooper()).
//
// Using the main looper should be avoided whenever possible, please don't copy this over to
// new tests.
@RunWithLooper(setAsMainLooper = true)
public class KeyguardUpdateMonitorTest extends SysuiTestCase {

    @Mock
    private KeyguardUpdateMonitor.StrongAuthTracker mStrongAuthTracker;
    @Mock
    private TrustManager mTrustManager;
    @Mock
    private FingerprintManager mFingerprintManager;
    @Mock
    private FaceManager mFaceManager;
    @Mock
    private BiometricManager mBiometricManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private DevicePolicyManager mDevicePolicyManager;
    private TestableLooper mTestableLooper;
    private TestableKeyguardUpdateMonitor mKeyguardUpdateMonitor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        TestableContext context = spy(mContext);
        when(mPackageManager.hasSystemFeature(anyString())).thenReturn(true);
        when(context.getPackageManager()).thenReturn(mPackageManager);
        doAnswer(invocation -> {
            IBiometricEnabledOnKeyguardCallback callback = invocation.getArgument(0);
            callback.onChanged(BiometricSourceType.FACE, true /* enabled */);
            return null;
        }).when(mBiometricManager).registerEnabledOnKeyguardCallback(any());
        when(mFaceManager.isHardwareDetected()).thenReturn(true);
        when(mFaceManager.hasEnrolledTemplates()).thenReturn(true);
        when(mFaceManager.hasEnrolledTemplates(anyInt())).thenReturn(true);
        when(mUserManager.isUserUnlocked(anyInt())).thenReturn(true);
        when(mUserManager.isPrimaryUser()).thenReturn(true);
        when(mStrongAuthTracker.isUnlockingWithBiometricAllowed()).thenReturn(true);
        context.addMockSystemService(TrustManager.class, mTrustManager);
        context.addMockSystemService(FingerprintManager.class, mFingerprintManager);
        context.addMockSystemService(BiometricManager.class, mBiometricManager);
        context.addMockSystemService(FaceManager.class, mFaceManager);
        context.addMockSystemService(UserManager.class, mUserManager);
        context.addMockSystemService(DevicePolicyManager.class, mDevicePolicyManager);

        mTestableLooper = TestableLooper.get(this);
        mKeyguardUpdateMonitor = new TestableKeyguardUpdateMonitor(context);
    }

    @Test
    public void testIgnoresSimStateCallback_rebroadcast() {
        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);

        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(), intent);
        mTestableLooper.processAllMessages();
        Assert.assertTrue("onSimStateChanged not called",
                mKeyguardUpdateMonitor.hasSimStateJustChanged());

        intent.putExtra(TelephonyIntents.EXTRA_REBROADCAST_ON_UNLOCK, true);
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
        intent.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE,
                IccCardConstants.INTENT_VALUE_ICC_ABSENT);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, false));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isTrue();
    }

    @Test
    public void testTelephonyCapable_SimState_CardIOError() {
        Intent intent = new Intent(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        intent.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE,
                IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext(),
                putPhoneInfo(intent, null, false));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isTrue();
    }

    @Test
    public void testTelephonyCapable_SimInvalid_ServiceState_InService() {
        // SERVICE_STATE - IN_SERVICE, but SIM_STATE is invalid TelephonyCapable should be False
        Intent intent = new Intent(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
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
        Intent intent = new Intent(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
        intent.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE
                , IccCardConstants.INTENT_VALUE_ICC_LOADED);
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
        Intent intent = new Intent(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
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
        intent.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE
                , IccCardConstants.INTENT_VALUE_ICC_NOT_READY);
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
        intent.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE
                , IccCardConstants.INTENT_VALUE_ICC_READY);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext()
                , putPhoneInfo(intent, data, false));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isFalse();
    }

    @Test
    public void testTelephonyCapable_BootInitState_ServiceState_PowerOff() {
        Intent intent = new Intent(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
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
        Intent intent = new Intent(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
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
        intentSimState.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE
                , IccCardConstants.INTENT_VALUE_ICC_LOADED);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext()
                , putPhoneInfo(intentSimState, data, true));
        mTestableLooper.processAllMessages();
        // Even SimState Loaded, still need ACTION_SERVICE_STATE_CHANGED turn on mTelephonyCapable
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isFalse();

        Intent intentServiceState =  new Intent(TelephonyIntents.ACTION_SERVICE_STATE_CHANGED);
        intentSimState.putExtra(IccCardConstants.INTENT_KEY_ICC_STATE
                , IccCardConstants.INTENT_VALUE_ICC_LOADED);
        mKeyguardUpdateMonitor.mBroadcastReceiver.onReceive(getContext()
                , putPhoneInfo(intentServiceState, data, true));
        mTestableLooper.processAllMessages();
        assertThat(mKeyguardUpdateMonitor.mTelephonyCapable).isTrue();
    }

    @Test
    public void testTriesToAuthenticate_whenBouncer() {
        mKeyguardUpdateMonitor.sendKeyguardBouncerChanged(true);
        mTestableLooper.processAllMessages();

        verify(mFaceManager).authenticate(any(), any(), anyInt(), any(), any(), anyInt());
        verify(mFaceManager).isHardwareDetected();
        verify(mFaceManager).hasEnrolledTemplates(anyInt());
    }

    @Test
    public void testTriesToAuthenticate_whenKeyguard() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager).authenticate(any(), any(), anyInt(), any(), any(), anyInt());
    }

    @Test
    public void skipsAuthentication_whenEncryptedKeyguard() {
        reset(mUserManager);
        when(mUserManager.isUserUnlocked(anyInt())).thenReturn(false);

        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager, never()).authenticate(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void testTriesToAuthenticate_whenAssistant() {
        mKeyguardUpdateMonitor.setKeyguardOccluded(true);
        mKeyguardUpdateMonitor.setAssistantVisible(true);

        verify(mFaceManager).authenticate(any(), any(), anyInt(), any(), any(), anyInt());
    }

    @Test
    public void testOnFaceAuthenticated_skipsFaceWhenAuthenticated() {
        mKeyguardUpdateMonitor.onFaceAuthenticated(KeyguardUpdateMonitor.getCurrentUser());
        mKeyguardUpdateMonitor.sendKeyguardBouncerChanged(true);
        mTestableLooper.processAllMessages();

        verify(mFaceManager, never()).authenticate(any(), any(), anyInt(), any(), any());
    }

    @Test
    public void testGetUserCanSkipBouncer_whenFace() {
        int user = KeyguardUpdateMonitor.getCurrentUser();
        mKeyguardUpdateMonitor.onFaceAuthenticated(user);
        assertThat(mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)).isTrue();
    }

    @Test
    public void testGetUserCanSkipBouncer_whenFingerprint() {
        int user = KeyguardUpdateMonitor.getCurrentUser();
        mKeyguardUpdateMonitor.onFingerprintAuthenticated(user);
        assertThat(mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)).isTrue();
    }

    @Test
    public void testGetUserCanSkipBouncer_whenTrust() {
        int user = KeyguardUpdateMonitor.getCurrentUser();
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */, user, 0 /* flags */);
        assertThat(mKeyguardUpdateMonitor.getUserCanSkipBouncer(user)).isTrue();
    }

    private Intent putPhoneInfo(Intent intent, Bundle data, Boolean simInited) {
        int subscription = simInited
                ? 1/* mock subid=1 */ : SubscriptionManager.DUMMY_SUBSCRIPTION_ID_BASE;
        if (data != null) intent.putExtras(data);
        intent.putExtra(PhoneConstants.PHONE_NAME_KEY, "Phone");
        intent.putExtra("subscription", subscription);
        intent.putExtra("slot", 0/* SLOT 1 */);
        return intent;
    }

    private class TestableKeyguardUpdateMonitor extends KeyguardUpdateMonitor {
        AtomicBoolean mSimStateChanged = new AtomicBoolean(false);

        protected TestableKeyguardUpdateMonitor(Context context) {
            super(context);
            context.unregisterReceiver(mBroadcastReceiver);
            context.unregisterReceiver(mBroadcastAllReceiver);
            mStrongAuthTracker = KeyguardUpdateMonitorTest.this.mStrongAuthTracker;
        }

        public boolean hasSimStateJustChanged() {
            return mSimStateChanged.getAndSet(false);
        }

        @Override
        protected void handleSimStateChange(int subId, int slotId,
                IccCardConstants.State state) {
            mSimStateChanged.set(true);
            super.handleSimStateChange(subId, slotId, state);
        }
    }
}
