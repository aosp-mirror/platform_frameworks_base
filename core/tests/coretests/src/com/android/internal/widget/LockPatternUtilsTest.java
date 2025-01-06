/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.widget;

import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_MANAGED;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.trust.TrustManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.UserInfo;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManagerGlobal;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.flag.junit.SetFlagsRule;
import android.platform.test.ravenwood.RavenwoodRule;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.view.InputDevice;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.widget.flags.Flags;

import com.google.android.collect.Lists;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RunWith(AndroidJUnit4.class)
@SmallTest
@IgnoreUnderRavenwood(blockedBy = LockPatternUtils.class)
public class LockPatternUtilsTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private ILockSettings mLockSettings;
    private static final int USER_ID = 1;
    private static final int DEMO_USER_ID = 5;

    private LockPatternUtils mLockPatternUtils;

    private void configureTest(boolean isSecure, boolean isDemoUser, int deviceDemoMode)
            throws Exception {
        mLockSettings = mock(ILockSettings.class);
        final Context context = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        final MockContentResolver cr = new MockContentResolver(context);
        cr.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(context.getContentResolver()).thenReturn(cr);
        Settings.Global.putInt(cr, Settings.Global.DEVICE_DEMO_MODE, deviceDemoMode);

        when(mLockSettings.getCredentialType(DEMO_USER_ID)).thenReturn(
                isSecure ? LockPatternUtils.CREDENTIAL_TYPE_PASSWORD
                         : LockPatternUtils.CREDENTIAL_TYPE_NONE);
        when(mLockSettings.getLong("lockscreen.password_type", PASSWORD_QUALITY_UNSPECIFIED,
                DEMO_USER_ID)).thenReturn((long) PASSWORD_QUALITY_MANAGED);
        when(mLockSettings.hasSecureLockScreen()).thenReturn(true);
        mLockPatternUtils = new LockPatternUtils(context, mLockSettings);

        final UserInfo userInfo = mock(UserInfo.class);
        when(userInfo.isDemo()).thenReturn(isDemoUser);
        final UserManager um = mock(UserManager.class);
        when(um.getUserInfo(DEMO_USER_ID)).thenReturn(userInfo);
        when(context.getSystemService(Context.USER_SERVICE)).thenReturn(um);
    }

    @Test
    public void isUserInLockDown() throws Exception {
        configureTest(true, false, 2);

        // GIVEN strong auth not required
        when(mLockSettings.getStrongAuthForUser(USER_ID)).thenReturn(STRONG_AUTH_NOT_REQUIRED);

        // THEN user isn't in lockdown
        assertFalse(mLockPatternUtils.isUserInLockdown(USER_ID));

        // GIVEN lockdown
        when(mLockSettings.getStrongAuthForUser(USER_ID)).thenReturn(
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN);

        // THEN user is in lockdown
        assertTrue(mLockPatternUtils.isUserInLockdown(USER_ID));

        // GIVEN lockdown and lockout
        when(mLockSettings.getStrongAuthForUser(USER_ID)).thenReturn(
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN | STRONG_AUTH_REQUIRED_AFTER_LOCKOUT);

        // THEN user is in lockdown
        assertTrue(mLockPatternUtils.isUserInLockdown(USER_ID));
    }

    @Test
    public void isLockScreenDisabled_isDemoUser_true() throws Exception {
        configureTest(false, true, 2);
        assertTrue(mLockPatternUtils.isLockScreenDisabled(DEMO_USER_ID));
    }

    @Test
    public void isLockScreenDisabled_isSecureAndDemoUser_false() throws Exception {
        configureTest(true, true, 2);
        assertFalse(mLockPatternUtils.isLockScreenDisabled(DEMO_USER_ID));
    }

    @Test
    public void isLockScreenDisabled_isNotDemoUser_false() throws Exception {
        configureTest(false, false, 2);
        assertFalse(mLockPatternUtils.isLockScreenDisabled(DEMO_USER_ID));
    }

    @Test
    public void isLockScreenDisabled_isNotInDemoMode_false() throws Exception {
        configureTest(false, true, 0);
        assertFalse(mLockPatternUtils.isLockScreenDisabled(DEMO_USER_ID));
    }

    @Test
    public void testAddWeakEscrowToken() throws RemoteException {
        ILockSettings ils = createTestLockSettings();
        byte[] testToken = "test_token".getBytes(StandardCharsets.UTF_8);
        int testUserId = 10;
        IWeakEscrowTokenActivatedListener listener = createWeakEscrowTokenListener();
        mLockPatternUtils.addWeakEscrowToken(testToken, testUserId, listener);
        verify(ils).addWeakEscrowToken(eq(testToken), eq(testUserId), eq(listener));
    }

    @Test
    public void testRegisterWeakEscrowTokenRemovedListener() throws RemoteException {
        ILockSettings ils = createTestLockSettings();
        IWeakEscrowTokenRemovedListener testListener = createTestAutoEscrowTokenRemovedListener();
        mLockPatternUtils.registerWeakEscrowTokenRemovedListener(testListener);
        verify(ils).registerWeakEscrowTokenRemovedListener(eq(testListener));
    }

    @Test
    public void testUnregisterWeakEscrowTokenRemovedListener() throws RemoteException {
        ILockSettings ils = createTestLockSettings();
        IWeakEscrowTokenRemovedListener testListener = createTestAutoEscrowTokenRemovedListener();
        mLockPatternUtils.unregisterWeakEscrowTokenRemovedListener(testListener);
        verify(ils).unregisterWeakEscrowTokenRemovedListener(eq(testListener));
    }

    @Test
    public void testRemoveAutoEscrowToken() throws RemoteException {
        ILockSettings ils = createTestLockSettings();
        int testUserId = 10;
        long testHandle = 100L;
        mLockPatternUtils.removeWeakEscrowToken(testHandle, testUserId);
        verify(ils).removeWeakEscrowToken(eq(testHandle), eq(testUserId));
    }

    @Test
    public void testIsAutoEscrowTokenActive() throws RemoteException {
        ILockSettings ils = createTestLockSettings();
        int testUserId = 10;
        long testHandle = 100L;
        mLockPatternUtils.isWeakEscrowTokenActive(testHandle, testUserId);
        verify(ils).isWeakEscrowTokenActive(eq(testHandle), eq(testUserId));
    }

    @Test
    public void testIsAutoEscrowTokenValid() throws RemoteException {
        ILockSettings ils = createTestLockSettings();
        int testUserId = 10;
        byte[] testToken = "test_token".getBytes(StandardCharsets.UTF_8);
        long testHandle = 100L;
        mLockPatternUtils.isWeakEscrowTokenValid(testHandle, testToken, testUserId);
        verify(ils).isWeakEscrowTokenValid(eq(testHandle), eq(testToken), eq(testUserId));
    }

    @Test
    public void testSetEnabledTrustAgents() throws RemoteException {
        int testUserId = 10;
        ILockSettings ils = createTestLockSettings();
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        doNothing().when(ils).setString(anyString(), valueCaptor.capture(), anyInt());
        List<ComponentName> enabledTrustAgents = Lists.newArrayList(
                ComponentName.unflattenFromString("com.android/.TrustAgent"),
                ComponentName.unflattenFromString("com.test/.TestAgent"));

        mLockPatternUtils.setEnabledTrustAgents(enabledTrustAgents, testUserId);

        assertThat(valueCaptor.getValue()).isEqualTo("com.android/.TrustAgent,com.test/.TestAgent");
    }

    @Test
    public void testGetEnabledTrustAgents() throws RemoteException {
        int testUserId = 10;
        ILockSettings ils = createTestLockSettings();
        when(ils.getString(anyString(), any(), anyInt())).thenReturn(
                "com.android/.TrustAgent,com.test/.TestAgent");

        List<ComponentName> trustAgents = mLockPatternUtils.getEnabledTrustAgents(testUserId);

        assertThat(trustAgents).containsExactly(
                ComponentName.unflattenFromString("com.android/.TrustAgent"),
                ComponentName.unflattenFromString("com.test/.TestAgent"));
    }

    @Test
    public void testSetKnownTrustAgents() throws RemoteException {
        int testUserId = 10;
        ILockSettings ils = createTestLockSettings();
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        doNothing().when(ils).setString(anyString(), valueCaptor.capture(), anyInt());
        List<ComponentName> knownTrustAgents = Lists.newArrayList(
                ComponentName.unflattenFromString("com.android/.TrustAgent"),
                ComponentName.unflattenFromString("com.test/.TestAgent"));

        mLockPatternUtils.setKnownTrustAgents(knownTrustAgents, testUserId);

        assertThat(valueCaptor.getValue()).isEqualTo("com.android/.TrustAgent,com.test/.TestAgent");
    }

    @Test
    public void testGetKnownTrustAgents() throws RemoteException {
        int testUserId = 10;
        ILockSettings ils = createTestLockSettings();
        when(ils.getString(anyString(), any(), anyInt())).thenReturn(
                "com.android/.TrustAgent,com.test/.TestAgent");

        List<ComponentName> trustAgents = mLockPatternUtils.getKnownTrustAgents(testUserId);

        assertThat(trustAgents).containsExactly(
                ComponentName.unflattenFromString("com.android/.TrustAgent"),
                ComponentName.unflattenFromString("com.test/.TestAgent"));
    }

    @Test
    public void isBiometricAllowedForUser_afterTrustagentExpired_returnsTrue()
            throws RemoteException {
        TestStrongAuthTracker tracker = createStrongAuthTracker();
        tracker.changeStrongAuth(SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED);

        assertTrue(tracker.isBiometricAllowedForUser(
                /* isStrongBiometric = */ true,
                DEMO_USER_ID));
    }

    @Test
    public void isBiometricAllowedForUser_afterLockout_returnsFalse()
            throws RemoteException {
        TestStrongAuthTracker tracker = createStrongAuthTracker();
        tracker.changeStrongAuth(STRONG_AUTH_REQUIRED_AFTER_LOCKOUT);

        assertFalse(tracker.isBiometricAllowedForUser(
                /* isStrongBiometric = */ true,
                DEMO_USER_ID));
    }

    @Test
    public void testUserFrp_isNotRegularUser() throws Exception {
        assertTrue(LockPatternUtils.USER_FRP < 0);
    }

    @Test
    public void testUserRepairMode_isNotRegularUser() {
        assertTrue(LockPatternUtils.USER_REPAIR_MODE < 0);
    }

    @Test
    public void testUserFrp_isNotAReservedSpecialUser() throws Exception {
        assertNotEquals(UserHandle.USER_NULL, LockPatternUtils.USER_FRP);
        assertNotEquals(UserHandle.USER_ALL, LockPatternUtils.USER_FRP);
        assertNotEquals(UserHandle.USER_CURRENT, LockPatternUtils.USER_FRP);
        assertNotEquals(UserHandle.USER_CURRENT_OR_SELF, LockPatternUtils.USER_FRP);
    }

    @Test
    public void testUserRepairMode_isNotAReservedSpecialUser() throws Exception {
        assertNotEquals(UserHandle.USER_NULL, LockPatternUtils.USER_REPAIR_MODE);
        assertNotEquals(UserHandle.USER_ALL, LockPatternUtils.USER_REPAIR_MODE);
        assertNotEquals(UserHandle.USER_CURRENT, LockPatternUtils.USER_REPAIR_MODE);
        assertNotEquals(UserHandle.USER_CURRENT_OR_SELF, LockPatternUtils.USER_REPAIR_MODE);
    }

    @Test
    public void testWriteRepairModeCredential_mainThread() {
        createTestLockSettings();
        var context = InstrumentationRegistry.getTargetContext();

        var future = new CompletableFuture<Exception>();
        context.getMainThreadHandler().post(() -> {
            try {
                mLockPatternUtils.writeRepairModeCredential(USER_ID);
                future.complete(null);
            } catch (Exception e) {
                future.complete(e);
            }
        });

        var e = future.join();
        assertThat(e).isNotNull();
        assertThat(e.getMessage()).contains("should not be called from the main thread");
    }

    @Test
    public void testWriteRepairModeCredential() throws Exception {
        var ils = createTestLockSettings();

        when(ils.writeRepairModeCredential(USER_ID)).thenReturn(false);
        assertThat(mLockPatternUtils.writeRepairModeCredential(USER_ID)).isFalse();

        when(ils.writeRepairModeCredential(USER_ID)).thenReturn(true);
        assertThat(mLockPatternUtils.writeRepairModeCredential(USER_ID)).isTrue();

        when(ils.writeRepairModeCredential(USER_ID)).thenThrow(new RemoteException());
        assertThat(mLockPatternUtils.writeRepairModeCredential(USER_ID)).isFalse();
    }

    private TestStrongAuthTracker createStrongAuthTracker() {
        final Context context = new ContextWrapper(InstrumentationRegistry.getTargetContext());
        return new TestStrongAuthTracker(context, Looper.getMainLooper());
    }

    private static class TestStrongAuthTracker extends LockPatternUtils.StrongAuthTracker {

        TestStrongAuthTracker(Context context, Looper looper) {
            super(context, looper);
        }

        public void changeStrongAuth(@StrongAuthFlags int strongAuthFlags) {
            handleStrongAuthRequiredChanged(strongAuthFlags, DEMO_USER_ID);
        }
    }

    private ILockSettings createTestLockSettings() {
        final Context context = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        final TrustManager trustManager = mock(TrustManager.class);
        when(context.getSystemService(Context.TRUST_SERVICE)).thenReturn(trustManager);

        final ILockSettings ils = mock(ILockSettings.class);
        mLockPatternUtils = new LockPatternUtils(context, ils);
        return ils;
    }

    private IWeakEscrowTokenActivatedListener createWeakEscrowTokenListener() {
        return new IWeakEscrowTokenActivatedListener.Stub() {
            @Override
            public void onWeakEscrowTokenActivated(long handle, int userId) {
                // Do nothing.
            }
        };
    }

    private IWeakEscrowTokenRemovedListener createTestAutoEscrowTokenRemovedListener() {
        return new IWeakEscrowTokenRemovedListener.Stub() {
            @Override
            public void onWeakEscrowTokenRemoved(long handle, int userId) {
                // Do nothing.
            }
        };
    }

    private InputManagerGlobal.TestSession configureExternalHardwareTest(InputDevice[] devices)
            throws RemoteException {
        final Context context = new ContextWrapper(InstrumentationRegistry.getTargetContext());
        final ILockSettings ils = mock(ILockSettings.class);
        when(ils.getBoolean(anyString(), anyBoolean(), anyInt())).thenThrow(RemoteException.class);
        mLockPatternUtils = new LockPatternUtils(context, ils);

        IInputManager inputManagerMock = mock(IInputManager.class);

        int[] deviceIds = new int[devices.length];

        for (int i = 0; i < devices.length; i++) {
            when(inputManagerMock.getInputDevice(i)).thenReturn(devices[i]);
        }

        when(inputManagerMock.getInputDeviceIds()).thenReturn(deviceIds);
        InputManagerGlobal.TestSession session =
                InputManagerGlobal.createTestSession(inputManagerMock);

        return session;
    }

    @Test
    @EnableFlags(Flags.FLAG_HIDE_LAST_CHAR_WITH_PHYSICAL_INPUT)
    public void isPinEnhancedPrivacyEnabled_noDevicesAttached() throws RemoteException {
        InputManagerGlobal.TestSession session = configureExternalHardwareTest(new InputDevice[0]);
        assertFalse(mLockPatternUtils.isPinEnhancedPrivacyEnabled(USER_ID));
        session.close();
    }

    @Test
    @EnableFlags(Flags.FLAG_HIDE_LAST_CHAR_WITH_PHYSICAL_INPUT)
    public void isPinEnhancedPrivacyEnabled_noEnabledDeviceAttached() throws RemoteException {
        InputDevice.Builder builder = new InputDevice.Builder();
        builder.setEnabled(false);
        InputManagerGlobal.TestSession session =
                configureExternalHardwareTest(new InputDevice[]{builder.build()});
        assertFalse(mLockPatternUtils.isPinEnhancedPrivacyEnabled(USER_ID));
        session.close();
    }

    @Test
    @EnableFlags(Flags.FLAG_HIDE_LAST_CHAR_WITH_PHYSICAL_INPUT)
    public void isPinEnhancedPrivacyEnabled_withoutHwKeyboard() throws RemoteException {
        InputDevice.Builder builder = new InputDevice.Builder();
        builder.setEnabled(true).setSources(InputDevice.SOURCE_TOUCHSCREEN);
        InputManagerGlobal.TestSession session =
                configureExternalHardwareTest(new InputDevice[]{builder.build()});
        assertFalse(mLockPatternUtils.isPinEnhancedPrivacyEnabled(USER_ID));
        session.close();
    }

    @Test
    @EnableFlags(Flags.FLAG_HIDE_LAST_CHAR_WITH_PHYSICAL_INPUT)
    public void isPinEnhancedPrivacyEnabled_withoutFullHwKeyboard() throws RemoteException {
        InputDevice.Builder builder = new InputDevice.Builder();
        builder
                .setEnabled(true)
                .setSources(InputDevice.SOURCE_KEYBOARD)
                .setKeyboardType(InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC);
        InputManagerGlobal.TestSession session =
                configureExternalHardwareTest(new InputDevice[]{builder.build()});
        assertFalse(mLockPatternUtils.isPinEnhancedPrivacyEnabled(USER_ID));
        session.close();
    }

    @Test
    @DisableFlags(Flags.FLAG_HIDE_LAST_CHAR_WITH_PHYSICAL_INPUT)
    public void isPinEnhancedPrivacyEnabled_withHwKeyboardOldDefault() throws RemoteException {
        InputDevice.Builder builder = new InputDevice.Builder();
        builder
                .setEnabled(true)
                .setSources(InputDevice.SOURCE_KEYBOARD)
                .setKeyboardType(InputDevice.KEYBOARD_TYPE_ALPHABETIC);
        InputManagerGlobal.TestSession session =
                configureExternalHardwareTest(new InputDevice[]{builder.build()});
        assertFalse(mLockPatternUtils.isPinEnhancedPrivacyEnabled(USER_ID));
        session.close();
    }

    @Test
    @EnableFlags(Flags.FLAG_HIDE_LAST_CHAR_WITH_PHYSICAL_INPUT)
    public void isPinEnhancedPrivacyEnabled_withHwKeyboard() throws RemoteException {
        InputDevice.Builder builder = new InputDevice.Builder();
        builder
                .setEnabled(true)
                .setSources(InputDevice.SOURCE_KEYBOARD)
                .setKeyboardType(InputDevice.KEYBOARD_TYPE_ALPHABETIC);
        InputManagerGlobal.TestSession session =
                configureExternalHardwareTest(new InputDevice[]{builder.build()});
        assertTrue(mLockPatternUtils.isPinEnhancedPrivacyEnabled(USER_ID));
        session.close();
    }

    @Test
    @EnableFlags(Flags.FLAG_HIDE_LAST_CHAR_WITH_PHYSICAL_INPUT)
    public void isVisiblePatternEnabled_noDevices() throws RemoteException {
        InputManagerGlobal.TestSession session = configureExternalHardwareTest(new InputDevice[0]);
        assertTrue(mLockPatternUtils.isVisiblePatternEnabled(USER_ID));
        session.close();
    }

    @Test
    @EnableFlags(Flags.FLAG_HIDE_LAST_CHAR_WITH_PHYSICAL_INPUT)
    public void isVisiblePatternEnabled_noEnabledDevices() throws RemoteException {
        InputDevice.Builder builder = new InputDevice.Builder();
        builder.setEnabled(false);
        InputManagerGlobal.TestSession session =
                configureExternalHardwareTest(new InputDevice[]{builder.build()});
        assertTrue(mLockPatternUtils.isVisiblePatternEnabled(USER_ID));
        session.close();
    }

    @Test
    @EnableFlags(Flags.FLAG_HIDE_LAST_CHAR_WITH_PHYSICAL_INPUT)
    public void isVisiblePatternEnabled_noPointingDevices() throws RemoteException {
        InputDevice.Builder builder = new InputDevice.Builder();
        builder
                .setEnabled(true)
                .setSources(InputDevice.SOURCE_TOUCHSCREEN);
        InputManagerGlobal.TestSession session =
                configureExternalHardwareTest(new InputDevice[]{builder.build()});
        assertTrue(mLockPatternUtils.isVisiblePatternEnabled(USER_ID));
        session.close();
    }

    @Test
    @EnableFlags(Flags.FLAG_HIDE_LAST_CHAR_WITH_PHYSICAL_INPUT)
    public void isVisiblePatternEnabled_externalPointingDevice() throws RemoteException {
        InputDevice.Builder builder = new InputDevice.Builder();
        builder
                .setEnabled(true)
                .setSources(InputDevice.SOURCE_CLASS_POINTER);
        InputManagerGlobal.TestSession session =
                configureExternalHardwareTest(new InputDevice[]{builder.build()});
        assertFalse(mLockPatternUtils.isVisiblePatternEnabled(USER_ID));
        session.close();
    }

    @Test
    @DisableFlags(Flags.FLAG_HIDE_LAST_CHAR_WITH_PHYSICAL_INPUT)
    public void isVisiblePatternEnabled_externalPointingDeviceOldDefault() throws RemoteException {
        InputDevice.Builder builder = new InputDevice.Builder();
        builder
                .setEnabled(true)
                .setSources(InputDevice.SOURCE_CLASS_POINTER);
        InputManagerGlobal.TestSession session =
                configureExternalHardwareTest(new InputDevice[]{builder.build()});
        assertTrue(mLockPatternUtils.isVisiblePatternEnabled(USER_ID));
        session.close();
    }
}
