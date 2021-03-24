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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.RebootEscrowListener;
import com.android.server.locksettings.ResumeOnRebootServiceProvider.ResumeOnRebootServiceConnection;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

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

    // Hex encoding of a randomly generated AES key for test.
    private static final byte[] TEST_AES_KEY = new byte[] {
            0x48, 0x19, 0x12, 0x54, 0x13, 0x13, 0x52, 0x31,
            0x44, 0x74, 0x61, 0x54, 0x29, 0x74, 0x37, 0x61,
            0x70, 0x70, 0x75, 0x25, 0x27, 0x31, 0x49, 0x09,
            0x26, 0x52, 0x72, 0x63, 0x63, 0x61, 0x78, 0x23,
    };

    private Context mContext;
    private UserManager mUserManager;
    private RebootEscrowManager.Callbacks mCallbacks;
    private IRebootEscrow mRebootEscrow;
    private ResumeOnRebootServiceConnection mServiceConnection;
    private RebootEscrowKeyStoreManager mKeyStoreManager;

    LockSettingsStorageTestable mStorage;

    private MockableRebootEscrowInjected mInjected;
    private RebootEscrowManager mService;
    private SecretKey mAesKey;

    public interface MockableRebootEscrowInjected {
        int getBootCount();

        long getCurrentTimeMillis();

        boolean forceServerBased();

        void reportMetric(boolean success, int errorCode, int serviceType, int attemptCount,
                int escrowDurationInSeconds, int vbmetaDigestStatus, int durationSinceBootComplete);
    }

    static class MockInjector extends RebootEscrowManager.Injector {
        private final IRebootEscrow mRebootEscrow;
        private final ResumeOnRebootServiceConnection mServiceConnection;
        private final RebootEscrowProviderInterface mRebootEscrowProvider;
        private final UserManager mUserManager;
        private final MockableRebootEscrowInjected mInjected;
        private final RebootEscrowKeyStoreManager mKeyStoreManager;
        private final boolean mServerBased;

        MockInjector(Context context, UserManager userManager,
                IRebootEscrow rebootEscrow,
                RebootEscrowKeyStoreManager keyStoreManager,
                LockSettingsStorageTestable storage,
                MockableRebootEscrowInjected injected) {
            super(context, storage);
            mRebootEscrow = rebootEscrow;
            mServiceConnection = null;
            mServerBased = false;
            RebootEscrowProviderHalImpl.Injector halInjector =
                    new RebootEscrowProviderHalImpl.Injector() {
                        @Override
                        public IRebootEscrow getRebootEscrow() {
                            return mRebootEscrow;
                        }
                    };
            mRebootEscrowProvider = new RebootEscrowProviderHalImpl(halInjector);
            mUserManager = userManager;
            mKeyStoreManager = keyStoreManager;
            mInjected = injected;
        }

        MockInjector(Context context, UserManager userManager,
                ResumeOnRebootServiceConnection serviceConnection,
                RebootEscrowKeyStoreManager keyStoreManager,
                LockSettingsStorageTestable storage,
                MockableRebootEscrowInjected injected) {
            super(context, storage);
            mServiceConnection = serviceConnection;
            mRebootEscrow = null;
            mServerBased = true;
            RebootEscrowProviderServerBasedImpl.Injector injector =
                    new RebootEscrowProviderServerBasedImpl.Injector(serviceConnection);
            mRebootEscrowProvider = new RebootEscrowProviderServerBasedImpl(storage, injector);
            mUserManager = userManager;
            mKeyStoreManager = keyStoreManager;
            mInjected = injected;
        }

        @Override
        void post(Handler handler, Runnable runnable) {
            runnable.run();
        }

        @Override
        public UserManager getUserManager() {
            return mUserManager;
        }

        @Override
        public boolean serverBasedResumeOnReboot() {
            if (mInjected.forceServerBased()) {
                return true;
            }
            return mServerBased;
        }

        @Override
        public RebootEscrowProviderInterface getRebootEscrowProvider() {
            return mRebootEscrowProvider;
        }

        @Override
        public RebootEscrowKeyStoreManager getKeyStoreManager() {
            return mKeyStoreManager;
        }

        @Override
        public int getBootCount() {
            return mInjected.getBootCount();
        }

        @Override
        public int getLoadEscrowDataRetryLimit() {
            // Try two times
            return 2;
        }

        @Override
        public int getLoadEscrowDataRetryIntervalSeconds() {
            // Retry in 1 seconds
            return 1;
        }

        @Override
        public String getVbmetaDigest(boolean other) {
            return other ? "" : "fake digest";
        }

        @Override
        public long getCurrentTimeMillis() {
            return mInjected.getCurrentTimeMillis();
        }

        @Override
        public void reportMetric(boolean success, int errorCode, int serviceType, int attemptCount,
                int escrowDurationInSeconds, int vbmetaDigestStatus,
                int durationSinceBootComplete) {

            mInjected.reportMetric(success, errorCode, serviceType, attemptCount,
                    escrowDurationInSeconds, vbmetaDigestStatus, durationSinceBootComplete);
        }
    }

    @Before
    public void setUp_baseServices() throws Exception {
        mContext = new ContextWrapper(InstrumentationRegistry.getContext());
        mUserManager = mock(UserManager.class);
        mCallbacks = mock(RebootEscrowManager.Callbacks.class);
        mRebootEscrow = mock(IRebootEscrow.class);
        mServiceConnection = mock(ResumeOnRebootServiceConnection.class);
        mKeyStoreManager = mock(RebootEscrowKeyStoreManager.class);
        mAesKey = new SecretKeySpec(TEST_AES_KEY, "AES");

        when(mKeyStoreManager.getKeyStoreEncryptionKey()).thenReturn(mAesKey);
        when(mKeyStoreManager.generateKeyStoreEncryptionKeyIfNeeded()).thenReturn(mAesKey);

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
                mKeyStoreManager, mStorage, mInjected), mCallbacks, mStorage);
    }

    private void setServerBasedRebootEscrowProvider() throws Exception {
        mService = new RebootEscrowManager(new MockInjector(mContext, mUserManager,
                mServiceConnection, mKeyStoreManager, mStorage, mInjected), mCallbacks, mStorage);
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
    public void prepareRebootEscrowServerBased_Success() throws Exception {
        setServerBasedRebootEscrowProvider();
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));
        verify(mServiceConnection, never()).wrapBlob(any(), anyLong(), anyLong());
        assertFalse(mStorage.hasRebootEscrowServerBlob());
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
        verify(mKeyStoreManager).getKeyStoreEncryptionKey();

        assertTrue(mStorage.hasRebootEscrow(PRIMARY_USER_ID));
        assertFalse(mStorage.hasRebootEscrow(NONSECURE_SECONDARY_USER_ID));
    }

    @Test
    public void armServiceServerBased_Success() throws Exception {
        setServerBasedRebootEscrowProvider();
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mServiceConnection);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));
        verify(mServiceConnection, never()).wrapBlob(any(), anyLong(), anyLong());

        when(mServiceConnection.wrapBlob(any(), anyLong(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        assertTrue(mService.armRebootEscrowIfNeeded());
        verify(mServiceConnection).wrapBlob(any(), anyLong(), anyLong());

        assertTrue(mStorage.hasRebootEscrow(PRIMARY_USER_ID));
        assertFalse(mStorage.hasRebootEscrow(NONSECURE_SECONDARY_USER_ID));
        assertTrue(mStorage.hasRebootEscrowServerBlob());
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
        mService.loadRebootEscrowDataIfAvailable(null);
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
        verify(mKeyStoreManager).getKeyStoreEncryptionKey();

        assertTrue(mStorage.hasRebootEscrow(PRIMARY_USER_ID));
        assertFalse(mStorage.hasRebootEscrow(NONSECURE_SECONDARY_USER_ID));

        // pretend reboot happens here

        when(mInjected.getBootCount()).thenReturn(1);
        when(mInjected.getCurrentTimeMillis()).thenReturn(30000L);
        mStorage.setLong(RebootEscrowManager.REBOOT_ESCROW_KEY_ARMED_TIMESTAMP, 10000L,
                USER_SYSTEM);
        ArgumentCaptor<Boolean> metricsSuccessCaptor = ArgumentCaptor.forClass(Boolean.class);
        doNothing().when(mInjected).reportMetric(metricsSuccessCaptor.capture(),
                eq(0) /* error code */, eq(1) /* HAL based */, eq(1) /* attempt count */,
                eq(20), eq(0) /* vbmeta status */, anyInt());
        when(mRebootEscrow.retrieveKey()).thenAnswer(invocation -> keyByteCaptor.getValue());

        mService.loadRebootEscrowDataIfAvailable(null);
        verify(mRebootEscrow).retrieveKey();
        assertTrue(metricsSuccessCaptor.getValue());
        verify(mKeyStoreManager).clearKeyStoreEncryptionKey();
        assertEquals(mStorage.getLong(RebootEscrowManager.REBOOT_ESCROW_KEY_ARMED_TIMESTAMP,
                -1, USER_SYSTEM), -1);
    }

    @Test
    public void loadRebootEscrowDataIfAvailable_ServerBased_Success() throws Exception {
        setServerBasedRebootEscrowProvider();

        when(mInjected.getBootCount()).thenReturn(0);
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mServiceConnection);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));
        verify(mServiceConnection, never()).wrapBlob(any(), anyLong(), anyLong());

        // Use x -> x for both wrap & unwrap functions.
        when(mServiceConnection.wrapBlob(any(), anyLong(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        assertTrue(mService.armRebootEscrowIfNeeded());
        verify(mServiceConnection).wrapBlob(any(), anyLong(), anyLong());
        assertTrue(mStorage.hasRebootEscrowServerBlob());

        // pretend reboot happens here
        when(mInjected.getBootCount()).thenReturn(1);
        ArgumentCaptor<Boolean> metricsSuccessCaptor = ArgumentCaptor.forClass(Boolean.class);
        doNothing().when(mInjected).reportMetric(metricsSuccessCaptor.capture(),
                eq(0) /* error code */, eq(2) /* Server based */, eq(1) /* attempt count */,
                anyInt(), eq(0) /* vbmeta status */, anyInt());

        when(mServiceConnection.unwrap(any(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        mService.loadRebootEscrowDataIfAvailable(null);
        verify(mServiceConnection).unwrap(any(), anyLong());
        assertTrue(metricsSuccessCaptor.getValue());
        verify(mKeyStoreManager).clearKeyStoreEncryptionKey();
    }

    @Test
    public void loadRebootEscrowDataIfAvailable_ServerBasedRemoteException_Failure()
            throws Exception {
        setServerBasedRebootEscrowProvider();

        when(mInjected.getBootCount()).thenReturn(0);
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mServiceConnection);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));
        verify(mServiceConnection, never()).wrapBlob(any(), anyLong(), anyLong());

        // Use x -> x for both wrap & unwrap functions.
        when(mServiceConnection.wrapBlob(any(), anyLong(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        assertTrue(mService.armRebootEscrowIfNeeded());
        verify(mServiceConnection).wrapBlob(any(), anyLong(), anyLong());
        assertTrue(mStorage.hasRebootEscrowServerBlob());

        // pretend reboot happens here
        when(mInjected.getBootCount()).thenReturn(1);
        ArgumentCaptor<Boolean> metricsSuccessCaptor = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> metricsErrorCodeCaptor = ArgumentCaptor.forClass(Integer.class);
        doNothing().when(mInjected).reportMetric(metricsSuccessCaptor.capture(),
                metricsErrorCodeCaptor.capture(), eq(2) /* Server based */,
                eq(1) /* attempt count */, anyInt(), eq(0) /* vbmeta status */, anyInt());

        when(mServiceConnection.unwrap(any(), anyLong())).thenThrow(RemoteException.class);
        mService.loadRebootEscrowDataIfAvailable(null);
        verify(mServiceConnection).unwrap(any(), anyLong());
        assertFalse(metricsSuccessCaptor.getValue());
        assertEquals(Integer.valueOf(RebootEscrowManager.ERROR_LOAD_ESCROW_KEY),
                metricsErrorCodeCaptor.getValue());
    }

    @Test
    public void loadRebootEscrowDataIfAvailable_ServerBasedIoError_RetryFailure() throws Exception {
        setServerBasedRebootEscrowProvider();

        when(mInjected.getBootCount()).thenReturn(0);
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mServiceConnection);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));
        verify(mServiceConnection, never()).wrapBlob(any(), anyLong(), anyLong());

        // Use x -> x for both wrap & unwrap functions.
        when(mServiceConnection.wrapBlob(any(), anyLong(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        assertTrue(mService.armRebootEscrowIfNeeded());
        verify(mServiceConnection).wrapBlob(any(), anyLong(), anyLong());
        assertTrue(mStorage.hasRebootEscrowServerBlob());

        // pretend reboot happens here
        when(mInjected.getBootCount()).thenReturn(1);
        ArgumentCaptor<Boolean> metricsSuccessCaptor = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Integer> metricsErrorCodeCaptor = ArgumentCaptor.forClass(Integer.class);
        doNothing().when(mInjected).reportMetric(metricsSuccessCaptor.capture(),
                metricsErrorCodeCaptor.capture(), eq(2) /* Server based */,
                eq(2) /* attempt count */, anyInt(), eq(0) /* vbmeta status */, anyInt());
        when(mServiceConnection.unwrap(any(), anyLong())).thenThrow(IOException.class);

        HandlerThread thread = new HandlerThread("RebootEscrowManagerTest");
        thread.start();
        mService.loadRebootEscrowDataIfAvailable(new Handler(thread.getLooper()));
        // Sleep 5s for the retry to complete
        Thread.sleep(5 * 1000);
        assertFalse(metricsSuccessCaptor.getValue());
        assertEquals(Integer.valueOf(RebootEscrowManager.ERROR_RETRY_COUNT_EXHAUSTED),
                metricsErrorCodeCaptor.getValue());
    }

    @Test
    public void loadRebootEscrowDataIfAvailable_ServerBased_RetrySuccess() throws Exception {
        setServerBasedRebootEscrowProvider();

        when(mInjected.getBootCount()).thenReturn(0);
        RebootEscrowListener mockListener = mock(RebootEscrowListener.class);
        mService.setRebootEscrowListener(mockListener);
        mService.prepareRebootEscrow();

        clearInvocations(mServiceConnection);
        mService.callToRebootEscrowIfNeeded(PRIMARY_USER_ID, FAKE_SP_VERSION, FAKE_AUTH_TOKEN);
        verify(mockListener).onPreparedForReboot(eq(true));
        verify(mServiceConnection, never()).wrapBlob(any(), anyLong(), anyLong());

        // Use x -> x for both wrap & unwrap functions.
        when(mServiceConnection.wrapBlob(any(), anyLong(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        assertTrue(mService.armRebootEscrowIfNeeded());
        verify(mServiceConnection).wrapBlob(any(), anyLong(), anyLong());
        assertTrue(mStorage.hasRebootEscrowServerBlob());

        // pretend reboot happens here
        when(mInjected.getBootCount()).thenReturn(1);
        ArgumentCaptor<Boolean> metricsSuccessCaptor = ArgumentCaptor.forClass(Boolean.class);
        doNothing().when(mInjected).reportMetric(metricsSuccessCaptor.capture(),
                anyInt(), anyInt(), eq(2) /* attempt count */, anyInt(), anyInt(), anyInt());

        when(mServiceConnection.unwrap(any(), anyLong()))
                .thenThrow(new IOException())
                .thenAnswer(invocation -> invocation.getArgument(0));

        HandlerThread thread = new HandlerThread("RebootEscrowManagerTest");
        thread.start();
        mService.loadRebootEscrowDataIfAvailable(new Handler(thread.getLooper()));
        // Sleep 5s for the retry to complete
        Thread.sleep(5 * 1000);
        verify(mServiceConnection, times(2)).unwrap(any(), anyLong());
        assertTrue(metricsSuccessCaptor.getValue());
        verify(mKeyStoreManager).clearKeyStoreEncryptionKey();
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

        mService.loadRebootEscrowDataIfAvailable(null);
        verify(mRebootEscrow).retrieveKey();
        verify(mInjected, never()).reportMetric(anyBoolean(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt());
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

        mService.loadRebootEscrowDataIfAvailable(null);
        verify(mInjected, never()).reportMetric(anyBoolean(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), anyInt());
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

        // Trigger a vbmeta digest mismatch
        mStorage.setString(RebootEscrowManager.REBOOT_ESCROW_KEY_VBMETA_DIGEST,
                "non sense value", USER_SYSTEM);
        mService.loadRebootEscrowDataIfAvailable(null);
        verify(mInjected).reportMetric(eq(true), eq(0) /* error code */, eq(1) /* HAL based */,
                eq(1) /* attempt count */, anyInt(), eq(2) /* vbmeta status */, anyInt());
        assertEquals(mStorage.getString(RebootEscrowManager.REBOOT_ESCROW_KEY_VBMETA_DIGEST,
                "", USER_SYSTEM), "");
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
        ArgumentCaptor<Integer> metricsErrorCodeCaptor = ArgumentCaptor.forClass(Integer.class);
        // Return a null escrow key
        doNothing().when(mInjected).reportMetric(metricsSuccessCaptor.capture(),
                metricsErrorCodeCaptor.capture(), eq(1) /* HAL based */,
                eq(1) /* attempt count */, anyInt(), anyInt(), anyInt());

        when(mRebootEscrow.retrieveKey()).thenAnswer(invocation -> null);
        mService.loadRebootEscrowDataIfAvailable(null);
        verify(mRebootEscrow).retrieveKey();
        assertFalse(metricsSuccessCaptor.getValue());
        assertEquals(Integer.valueOf(RebootEscrowManager.ERROR_LOAD_ESCROW_KEY),
                metricsErrorCodeCaptor.getValue());
    }
}
