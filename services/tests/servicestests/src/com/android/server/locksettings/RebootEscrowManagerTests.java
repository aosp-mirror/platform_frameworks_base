/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.locksettings;

import static android.content.pm.UserInfo.FLAG_FULL;
import static android.content.pm.UserInfo.FLAG_PRIMARY;
import static android.content.pm.UserInfo.FLAG_PROFILE;
import static android.os.UserHandle.USER_SYSTEM;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertNull;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.UserInfo;
import android.hardware.rebootescrow.IRebootEscrow;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.RebootEscrowListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.util.ArrayList;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class RebootEscrowManagerTests {
    protected static final int PRIMARY_USER_ID = 0;
    protected static final int WORK_PROFILE_USER_ID = 10;
    protected static final int NONSECURE_SECONDARY_USER_ID = 20;
    protected static final int SECURE_SECONDARY_USER_ID = 21;
    private static final byte FAKE_SP_VERSION = 1;
    private static final byte[] FAKE_AUTH_TOKEN = new byte[] {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
    };

    private Context mContext;
    private UserManager mUserManager;
    private RebootEscrowManager.Callbacks mCallbacks;
    private IRebootEscrow mRebootEscrow;

    LockSettingsStorageTestable mStorage;

    private MockableRebootEscrowInjected mInjected;
    private RebootEscrowManager mService;

    public interface MockableRebootEscrowInjected {
        int getBootCount();

        void reportMetric(boolean success);
    }

    static class MockInjector extends RebootEscrowManager.Injector {
        private final IRebootEscrow mRebootEscrow;
        private final UserManager mUserManager;
        private final MockableRebootEscrowInjected mInjected;

        MockInjector(Context context, UserManager userManager, IRebootEscrow rebootEscrow,
                MockableRebootEscrowInjected injected) {
            super(context);
            mRebootEscrow = rebootEscrow;
            mUserManager = userManager;
            mInjected = injected;
        }

        @Override
        public UserManager getUserManager() {
            return mUserManager;
        }

        @Override
        public IRebootEscrow getRebootEscrow() {
            return mRebootEscrow;
        }

        @Override
        public int getBootCount() {
            return mInjected.getBootCount();
        }

        @Override
        public void reportMetric(boolean success) {
            mInjected.reportMetric(success);
        }
    }

    @Before
    public void setUp_baseServices() throws Exception {
        mContext = new ContextWrapper(InstrumentationRegistry.getContext());
        mUserManager = mock(UserManager.class);
        mCallbacks = mock(RebootEscrowManager.Callbacks.class);
        mRebootEscrow = mock(IRebootEscrow.class);

        mStorage = new LockSettingsStorageTestable(mContext,
                new File(InstrumentationRegistry.getContext().getFilesDir(), "locksettings"));

        ArrayList<UserInfo> users = new ArrayList<>();
        users.add(new UserInfo(PRIMARY_USER_ID, "primary", FLAG_PRIMARY));
        users.add(new UserInfo(WORK_PROFILE_USER_ID, "work", FLAG_PROFILE));
        users.add(new UserInfo(NONSECURE_SECONDARY_USER_ID, "non-secure", FLAG_FULL));
        users.add(new UserInfo(SECURE_SECONDARY_USER_ID, "secure", FLAG_FULL));
        when(mUserManager.getUsers()).thenReturn(users);
        when(mCallbacks.isUserSecure(PRIMARY_USER_ID)).thenReturn(true);
        when(mCallbacks.isUserSecure(WORK_PROFILE_USER_ID)).thenReturn(true);
        when(mCallbacks.isUserSecure(NONSECURE_SECONDARY_USER_ID)).thenReturn(false);
        when(mCallbacks.isUserSecure(SECURE_SECONDARY_USER_ID)).thenReturn(true);
        mInjected = mock(MockableRebootEscrowInjected.class);
        mService = new RebootEscrowManager(new MockInjector(mContext, mUserManager, mRebootEscrow,
                mInjected), mCallbacks, mStorage);
    }

    @Test
    public void prepareRebootEscrow_Success() throws Exception {
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mRebootEscrow);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));
        verify(mRebootEscrow, never()).storeKey(any());
    }

    @Test
    public void prepareRebootEscrow_ClearCredentials_Success() throws Exception {
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));

        clearInvocations(mRebootEscrow);
        mService.clearRebootEscrow();
        verify(mockListener).onPreparedForReboot(eq(false));
        verify(mRebootEscrow).storeKey(eq(new byte[32]));
    }

    @Test
    public void clearCredentials_HalFailure_NonFatal() throws Exception {
        doThrow(ServiceSpecificException.class).when(mRebootEscrow).storeKey(any());
        mService.clearRebootEscrow();
        verify(mRebootEscrow).storeKey(eq(new byte[32]));
    }

    @Test
    public void armService_Success() throws Exception {
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mRebootEscrow);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));
        verify(mRebootEscrow, never()).storeKey(any());

        assertNull(
                mStorage.getString(RebootEscrowManager.REBOOT_ESCROW_ARMED_KEY, null, USER_SYSTEM));
        assertTrue(mService.armRebootEscrowIfNeeded());
        assertNotNull(
                mStorage.getString(RebootEscrowManager.REBOOT_ESCROW_ARMED_KEY, null, USER_SYSTEM));
        verify(mRebootEscrow).storeKey(any());

        assertTrue(mStorage.hasRebootEscrow(PRIMARY_USER_ID));
        assertFalse(mStorage.hasRebootEscrow(NONSECURE_SECONDARY_USER_ID));
    }

    @Test
    public void armService_HalFailure_NonFatal() throws Exception {
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mRebootEscrow);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));
        verify(mRebootEscrow, never()).storeKey(any());

        assertNull(
                mStorage.getString(RebootEscrowManager.REBOOT_ESCROW_ARMED_KEY, null, USER_SYSTEM));
        doThrow(ServiceSpecificException.class).when(mRebootEscrow).storeKey(any());
        assertFalse(mService.armRebootEscrowIfNeeded());
        verify(mRebootEscrow).storeKey(any());
    }

    @Test
    public void armService_MultipleUsers_Success() throws Exception {
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mRebootEscrow);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));
        mService.callToRebootEscrowIfNeeded(SECURE_SECONDARY_USER_ID, FAKE_SP_VERSION,
                FAKE_AUTH_TOKEN);
        verify(mRebootEscrow, never()).storeKey(any());

        assertTrue(mStorage.hasRebootEscrow(PRIMARY_USER_ID));
        assertTrue(mStorage.hasRebootEscrow(SECURE_SECONDARY_USER_ID));
        assertFalse(mStorage.hasRebootEscrow(NONSECURE_SECONDARY_USER_ID));

        assertNull(
                mStorage.getString(RebootEscrowManager.REBOOT_ESCROW_ARMED_KEY, null, USER_SYSTEM));
        assertTrue(mService.armRebootEscrowIfNeeded());
        assertNotNull(
                mStorage.getString(RebootEscrowManager.REBOOT_ESCROW_ARMED_KEY, null, USER_SYSTEM));
        verify(mRebootEscrow, times(1)).storeKey(any());

        assertTrue(mStorage.hasRebootEscrow(PRIMARY_USER_ID));
        assertTrue(mStorage.hasRebootEscrow(SECURE_SECONDARY_USER_ID));
        assertFalse(mStorage.hasRebootEscrow(NONSECURE_SECONDARY_USER_ID));
    }

    @Test
    public void armService_NoInitialization_Failure() throws Exception {
        assertFalse(mService.armRebootEscrowIfNeeded());
        verifyNoMoreInteractions(mRebootEscrow);
    }

    @Test
    public void armService_RebootEscrowServiceException_Failure() throws Exception {
        doThrow(RemoteException.class).when(mRebootEscrow).storeKey(any());
        assertFalse(mService.armRebootEscrowIfNeeded());
        verifyNoMoreInteractions(mRebootEscrow);
    }

    @Test
    public void loadRebootEscrowDataIfAvailable_NothingAvailable_Success() throws Exception {
        mService.loadRebootEscrowDataIfAvailable();
    }

    @Test
    public void loadRebootEscrowDataIfAvailable_Success() throws Exception {
        when(mInjected.getBootCount()).thenReturn(0);

        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mRebootEscrow);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));

        verify(mRebootEscrow, never()).storeKey(any());

        ArgumentCaptor<byte[]> keyByteCaptor = ArgumentCaptor.forClass(byte[].class);
        assertTrue(mService.armRebootEscrowIfNeeded());
        verify(mRebootEscrow).storeKey(keyByteCaptor.capture());

        assertTrue(mStorage.hasRebootEscrow(PRIMARY_USER_ID));
        assertFalse(mStorage.hasRebootEscrow(NONSECURE_SECONDARY_USER_ID));

        // pretend reboot happens here

        when(mInjected.getBootCount()).thenReturn(1);
        ArgumentCaptor<Boolean> metricsSuccessCaptor = ArgumentCaptor.forClass(Boolean.class);
        doNothing().when(mInjected).reportMetric(metricsSuccessCaptor.capture());
        when(mRebootEscrow.retrieveKey()).thenAnswer(invocation -> keyByteCaptor.getValue());

        mService.loadRebootEscrowDataIfAvailable();
        verify(mRebootEscrow).retrieveKey();
        assertTrue(metricsSuccessCaptor.getValue());
    }

    @Test
    public void loadRebootEscrowDataIfAvailable_TooManyBootsInBetween_NoMetrics() throws Exception {
        when(mInjected.getBootCount()).thenReturn(0);

        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mRebootEscrow);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));

        verify(mRebootEscrow, never()).storeKey(any());

        assertTrue(mService.armRebootEscrowIfNeeded());
        verify(mRebootEscrow).storeKey(any());

        assertTrue(mStorage.hasRebootEscrow(PRIMARY_USER_ID));
        assertFalse(mStorage.hasRebootEscrow(NONSECURE_SECONDARY_USER_ID));

        // pretend reboot happens here

        when(mInjected.getBootCount()).thenReturn(10);
        when(mRebootEscrow.retrieveKey()).thenReturn(new byte[32]);

        mService.loadRebootEscrowDataIfAvailable();
        verify(mRebootEscrow).retrieveKey();
        verify(mInjected, never()).reportMetric(anyBoolean());
    }

    @Test
    public void loadRebootEscrowDataIfAvailable_ManualReboot_Failure_NoMetrics() throws Exception {
        when(mInjected.getBootCount()).thenReturn(0);

        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mRebootEscrow);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));

        verify(mRebootEscrow, never()).storeKey(any());

        assertTrue(mStorage.hasRebootEscrow(PRIMARY_USER_ID));
        assertFalse(mStorage.hasRebootEscrow(NONSECURE_SECONDARY_USER_ID));

        // pretend reboot happens here

        when(mInjected.getBootCount()).thenReturn(10);
        when(mRebootEscrow.retrieveKey()).thenReturn(new byte[32]);

        mService.loadRebootEscrowDataIfAvailable();
        verify(mInjected, never()).reportMetric(anyBoolean());
    }

    @Test
    public void loadRebootEscrowDataIfAvailable_OTAFromBeforeArmedStatus_SuccessMetrics()
            throws Exception {
        when(mInjected.getBootCount()).thenReturn(0);

        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mRebootEscrow);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));

        verify(mRebootEscrow, never()).storeKey(any());

        ArgumentCaptor<byte[]> keyByteCaptor = ArgumentCaptor.forClass(byte[].class);
        assertTrue(mService.armRebootEscrowIfNeeded());
        verify(mRebootEscrow).storeKey(keyByteCaptor.capture());

        assertTrue(mStorage.hasRebootEscrow(PRIMARY_USER_ID));
        assertFalse(mStorage.hasRebootEscrow(NONSECURE_SECONDARY_USER_ID));

        // Delete key to simulate old version that didn't have it.
        mStorage.removeKey(RebootEscrowManager.REBOOT_ESCROW_ARMED_KEY, USER_SYSTEM);

        // pretend reboot happens here

        when(mInjected.getBootCount()).thenReturn(10);
        when(mRebootEscrow.retrieveKey()).thenAnswer(invocation -> keyByteCaptor.getValue());

        mService.loadRebootEscrowDataIfAvailable();
        verify(mInjected).reportMetric(eq(true));
    }

    @Test
    public void loadRebootEscrowDataIfAvailable_RestoreUnsuccessful_Failure() throws Exception {
        when(mInjected.getBootCount()).thenReturn(0);

        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mRebootEscrow);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));

        verify(mRebootEscrow, never()).storeKey(any());

        assertTrue(mService.armRebootEscrowIfNeeded());
        verify(mRebootEscrow).storeKey(any());

        assertTrue(mStorage.hasRebootEscrow(PRIMARY_USER_ID));
        assertFalse(mStorage.hasRebootEscrow(NONSECURE_SECONDARY_USER_ID));

        // pretend reboot happens here.

        when(mInjected.getBootCount()).thenReturn(1);
        ArgumentCaptor<Boolean> metricsSuccessCaptor = ArgumentCaptor.forClass(Boolean.class);
        doNothing().when(mInjected).reportMetric(metricsSuccessCaptor.capture());
        when(mRebootEscrow.retrieveKey()).thenAnswer(invocation -> new byte[32]);
        mService.loadRebootEscrowDataIfAvailable();
        verify(mRebootEscrow).retrieveKey();
        assertFalse(metricsSuccessCaptor.getValue());
    }
}
