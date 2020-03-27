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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricSourceType;
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;

import com.android.internal.telephony.TelephonyIntents;
import com.android.keyguard.KeyguardUpdateMonitor.BiometricAuthenticated;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.phone.KeyguardBypassController;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
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
    @Mock
    private DumpManager mDumpManager;
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
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private SubscriptionManager mSubscriptionManager;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private Executor mBackgroundExecutor;
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
            callback.onChanged(BiometricSourceType.FACE, true /* enabled */,
                    KeyguardUpdateMonitor.getCurrentUser());
            return null;
        }).when(mBiometricManager).registerEnabledOnKeyguardCallback(any());
        when(mFaceManager.isHardwareDetected()).thenReturn(true);
        when(mFaceManager.hasEnrolledTemplates()).thenReturn(true);
        when(mFaceManager.hasEnrolledTemplates(anyInt())).thenReturn(true);
        when(mUserManager.isUserUnlocked(anyInt())).thenReturn(true);
        when(mUserManager.isPrimaryUser()).thenReturn(true);
        when(mStrongAuthTracker
                .isUnlockingWithBiometricAllowed(anyBoolean() /* isStrongBiometric */))
                .thenReturn(true);
        context.addMockSystemService(TrustManager.class, mTrustManager);
        context.addMockSystemService(FingerprintManager.class, mFingerprintManager);
        context.addMockSystemService(BiometricManager.class, mBiometricManager);
        context.addMockSystemService(FaceManager.class, mFaceManager);
        context.addMockSystemService(UserManager.class, mUserManager);
        context.addMockSystemService(DevicePolicyManager.class, mDevicePolicyManager);
        context.addMockSystemService(SubscriptionManager.class, mSubscriptionManager);

        mTestableLooper = TestableLooper.get(this);
        allowTestableLooperAsMainThread();
        mKeyguardUpdateMonitor = new TestableKeyguardUpdateMonitor(context);
    }

    @After
    public void tearDown() {
        mKeyguardUpdateMonitor.destroy();
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
        when(mStrongAuthTracker.getStrongAuthForUser(anyInt())).thenReturn(
                KeyguardUpdateMonitor.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT);
        mKeyguardUpdateMonitor.setKeyguardBypassController(mKeyguardBypassController);

        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager, never()).authenticate(any(), any(), anyInt(), any(), any(), anyInt());
    }

    @Test
    public void requiresAuthentication_whenEncryptedKeyguard_andBypass() {
        testStrongAuthExceptOnBouncer(
                KeyguardUpdateMonitor.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT);
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
        verify(mFaceManager).authenticate(any(), any(), anyInt(), any(), any(), anyInt());

        // Stop scanning when bouncer becomes visible
        mKeyguardUpdateMonitor.sendKeyguardBouncerChanged(true /* showingBouncer */);
        mTestableLooper.processAllMessages();
        clearInvocations(mFaceManager);
        mKeyguardUpdateMonitor.requestFaceAuth();
        verify(mFaceManager, never()).authenticate(any(), any(), anyInt(), any(), any(), anyInt());
    }

    @Test
    public void testTriesToAuthenticate_whenAssistant() {
        mKeyguardUpdateMonitor.setKeyguardOccluded(true);
        mKeyguardUpdateMonitor.setAssistantVisible(true);

        verify(mFaceManager).authenticate(any(), any(), anyInt(), any(), any(), anyInt());
    }

    @Test
    public void testTriesToAuthenticate_whenTrustOnAgentKeyguard_ifBypass() {
        mKeyguardUpdateMonitor.setKeyguardBypassController(mKeyguardBypassController);
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        when(mKeyguardBypassController.canBypass()).thenReturn(true);
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */,
                KeyguardUpdateMonitor.getCurrentUser(), 0 /* flags */);
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager).authenticate(any(), any(), anyInt(), any(), any(), anyInt());
    }

    @Test
    public void testIgnoresAuth_whenTrustAgentOnKeyguard_withoutBypass() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */,
                KeyguardUpdateMonitor.getCurrentUser(), 0 /* flags */);
        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager, never()).authenticate(any(), any(), anyInt(), any(), any(), anyInt());
    }

    @Test
    public void testIgnoresAuth_whenLockdown() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        when(mStrongAuthTracker.getStrongAuthForUser(anyInt())).thenReturn(
                KeyguardUpdateMonitor.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);

        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager, never()).authenticate(any(), any(), anyInt(), any(), any(), anyInt());
    }

    @Test
    public void testTriesToAuthenticate_whenLockout() {
        mKeyguardUpdateMonitor.dispatchStartedWakingUp();
        mTestableLooper.processAllMessages();
        when(mStrongAuthTracker.getStrongAuthForUser(anyInt())).thenReturn(
                KeyguardUpdateMonitor.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT);

        mKeyguardUpdateMonitor.onKeyguardVisibilityChanged(true);
        verify(mFaceManager).authenticate(any(), any(), anyInt(), any(), any(), anyInt());
    }

    @Test
    public void testOnFaceAuthenticated_skipsFaceWhenAuthenticated() {
        // test whether face will be skipped if authenticated, so the value of isStrongBiometric
        // doesn't matter here
        mKeyguardUpdateMonitor.onFaceAuthenticated(KeyguardUpdateMonitor.getCurrentUser(),
                true /* isStrongBiometric */);
        mKeyguardUpdateMonitor.sendKeyguardBouncerChanged(true);
        mTestableLooper.processAllMessages();

        verify(mFaceManager, never()).authenticate(any(), any(), anyInt(), any(), any());
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
    public void testGetUserCanSkipBouncer_whenTrust() {
        int user = KeyguardUpdateMonitor.getCurrentUser();
        mKeyguardUpdateMonitor.onTrustChanged(true /* enabled */, user, 0 /* flags */);
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
        when(mDevicePolicyManager.getProfileOwnerAsUser(user))
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
                ? 1/* mock subid=1 */ : SubscriptionManager.DUMMY_SUBSCRIPTION_ID_BASE;
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
                    mBroadcastDispatcher, mDumpManager, mBackgroundExecutor);
            mStrongAuthTracker = KeyguardUpdateMonitorTest.this.mStrongAuthTracker;
        }

        public boolean hasSimStateJustChanged() {
            return mSimStateChanged.getAndSet(false);
        }

        @Override
        protected void handleSimStateChange(int subId, int slotId, int state) {
            mSimStateChanged.set(true);
            super.handleSimStateChange(subId, slotId, state);
        }
    }
}
