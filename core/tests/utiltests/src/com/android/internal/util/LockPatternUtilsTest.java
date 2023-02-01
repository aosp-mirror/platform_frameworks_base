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

package com.android.internal.util;

import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_MANAGED;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.Settings;
import android.test.mock.MockContentResolver;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.IWeakEscrowTokenActivatedListener;
import com.android.internal.widget.IWeakEscrowTokenRemovedListener;
import com.android.internal.widget.LockPatternUtils;

import com.google.android.collect.Lists;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LockPatternUtilsTest {

    private static final int DEMO_USER_ID = 5;

    private LockPatternUtils mLockPatternUtils;

    private void configureTest(boolean isSecure, boolean isDemoUser, int deviceDemoMode)
            throws Exception {
        final Context context = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));

        final MockContentResolver cr = new MockContentResolver(context);
        cr.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(context.getContentResolver()).thenReturn(cr);
        Settings.Global.putInt(cr, Settings.Global.DEVICE_DEMO_MODE, deviceDemoMode);

        final ILockSettings ils = Mockito.mock(ILockSettings.class);
        when(ils.getCredentialType(DEMO_USER_ID)).thenReturn(
                isSecure ? LockPatternUtils.CREDENTIAL_TYPE_PASSWORD
                         : LockPatternUtils.CREDENTIAL_TYPE_NONE);
        when(ils.getLong("lockscreen.password_type", PASSWORD_QUALITY_UNSPECIFIED, DEMO_USER_ID))
                .thenReturn((long) PASSWORD_QUALITY_MANAGED);
        // TODO(b/63758238): stop spying the class under test
        mLockPatternUtils = spy(new LockPatternUtils(context));
        when(mLockPatternUtils.getLockSettings()).thenReturn(ils);
        doReturn(true).when(mLockPatternUtils).hasSecureLockScreen();

        final UserInfo userInfo = Mockito.mock(UserInfo.class);
        when(userInfo.isDemo()).thenReturn(isDemoUser);
        final UserManager um = Mockito.mock(UserManager.class);
        when(um.getUserInfo(DEMO_USER_ID)).thenReturn(userInfo);
        when(context.getSystemService(Context.USER_SERVICE)).thenReturn(um);
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

    private ILockSettings createTestLockSettings() {
        final Context context = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        mLockPatternUtils = spy(new LockPatternUtils(context));
        final ILockSettings ils = Mockito.mock(ILockSettings.class);
        when(mLockPatternUtils.getLockSettings()).thenReturn(ils);
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
}
