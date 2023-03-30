/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.attention;

import static android.provider.DeviceConfig.NAMESPACE_ATTENTION_MANAGER_SERVICE;

import static com.android.server.attention.AttentionManagerService.ATTENTION_CACHE_BUFFER_SIZE;
import static com.android.server.attention.AttentionManagerService.DEFAULT_STALE_AFTER_MILLIS;
import static com.android.server.attention.AttentionManagerService.KEY_STALE_AFTER_MILLIS;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.attention.AttentionManagerInternal.AttentionCallbackInternal;
import android.attention.AttentionManagerInternal.ProximityUpdateCallbackInternal;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.DeviceConfig;
import android.service.attention.IAttentionCallback;
import android.service.attention.IAttentionService;
import android.service.attention.IProximityUpdateCallback;

import androidx.test.filters.SmallTest;

import com.android.server.attention.AttentionManagerService.AttentionCheck;
import com.android.server.attention.AttentionManagerService.AttentionCheckCache;
import com.android.server.attention.AttentionManagerService.AttentionCheckCacheBuffer;
import com.android.server.attention.AttentionManagerService.AttentionHandler;
import com.android.server.attention.AttentionManagerService.ProximityUpdate;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link com.android.server.attention.AttentionManagerService}
 */
@SuppressWarnings("GuardedBy")
@SmallTest
public class AttentionManagerServiceTest {
    private static final double PROXIMITY_SUCCESS_STATE = 1.0;

    private AttentionManagerService mSpyAttentionManager;
    private final int mTimeout = 1000;
    private final Object mLock = new Object();
    @Mock
    private AttentionCallbackInternal mMockAttentionCallbackInternal;
    @Mock
    private AttentionHandler mMockHandler;
    @Mock
    private IPowerManager mMockIPowerManager;
    @Mock
    private IThermalService mMockIThermalService;
    @Mock
    Context mContext;
    @Mock
    private ProximityUpdateCallbackInternal mMockProximityUpdateCallbackInternal;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        // setup context mock
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        // setup power manager mock
        PowerManager mPowerManager;
        doReturn(true).when(mMockIPowerManager).isInteractive();
        mPowerManager = new PowerManager(mContext, mMockIPowerManager, mMockIThermalService, null);

        // setup a spy on attention manager
        AttentionManagerService attentionManager = new AttentionManagerService(
                mContext,
                mPowerManager,
                mLock,
                mMockHandler);
        mSpyAttentionManager = Mockito.spy(attentionManager);
        // setup a spy on user state
        ComponentName componentName = new ComponentName("a", "b");
        mSpyAttentionManager.mComponentName = componentName;

        AttentionCheck attentionCheck = new AttentionCheck(mMockAttentionCallbackInternal,
                mSpyAttentionManager);
        mSpyAttentionManager.mCurrentAttentionCheck = attentionCheck;
        mSpyAttentionManager.mService = new MockIAttentionService();
        doNothing().when(mSpyAttentionManager).freeIfInactiveLocked();
    }

    @Test
    public void testRegisterProximityUpdates_returnFalseWhenServiceDisabled() {
        mSpyAttentionManager.mIsServiceEnabled = false;

        assertThat(mSpyAttentionManager.onStartProximityUpdates(
                mMockProximityUpdateCallbackInternal))
                .isFalse();
    }

    @Test
    public void testRegisterProximityUpdates_returnFalseWhenProximityDisabled() {
        mSpyAttentionManager.mIsServiceEnabled = true;
        mSpyAttentionManager.mIsProximityEnabled = false;

        assertThat(mSpyAttentionManager.onStartProximityUpdates(
                mMockProximityUpdateCallbackInternal))
                .isFalse();
    }

    @Test
    public void testRegisterProximityUpdates_returnFalseWhenServiceUnavailable() {
        mSpyAttentionManager.mIsServiceEnabled = true;
        mSpyAttentionManager.mIsProximityEnabled = true;
        doReturn(false).when(mSpyAttentionManager).isServiceAvailable();

        assertThat(mSpyAttentionManager.onStartProximityUpdates(
                mMockProximityUpdateCallbackInternal))
                .isFalse();
    }

    @Test
    public void testRegisterProximityUpdates_returnFalseWhenPowerManagerNotInteract()
            throws RemoteException {
        mSpyAttentionManager.mIsServiceEnabled = true;
        mSpyAttentionManager.mIsProximityEnabled = true;
        doReturn(true).when(mSpyAttentionManager).isServiceAvailable();
        doReturn(false).when(mMockIPowerManager).isInteractive();

        assertThat(mSpyAttentionManager.onStartProximityUpdates(
                mMockProximityUpdateCallbackInternal))
                .isFalse();
    }

    @Test
    public void testRegisterProximityUpdates_callOnSuccess() throws RemoteException {
        mSpyAttentionManager.mIsServiceEnabled = true;
        mSpyAttentionManager.mIsProximityEnabled = true;
        doReturn(true).when(mSpyAttentionManager).isServiceAvailable();
        doReturn(true).when(mMockIPowerManager).isInteractive();

        assertThat(mSpyAttentionManager.onStartProximityUpdates(
                mMockProximityUpdateCallbackInternal))
                .isTrue();
        verify(mMockProximityUpdateCallbackInternal, times(1))
                .onProximityUpdate(PROXIMITY_SUCCESS_STATE);
    }

    @Test
    public void testRegisterProximityUpdates_callOnSuccessTwiceInARow() throws RemoteException {
        mSpyAttentionManager.mIsServiceEnabled = true;
        mSpyAttentionManager.mIsProximityEnabled = true;
        doReturn(true).when(mSpyAttentionManager).isServiceAvailable();
        doReturn(true).when(mMockIPowerManager).isInteractive();

        assertThat(mSpyAttentionManager.onStartProximityUpdates(
                mMockProximityUpdateCallbackInternal))
                .isTrue();

        ProximityUpdate prevProximityUpdate = mSpyAttentionManager.mCurrentProximityUpdate;
        assertThat(mSpyAttentionManager.onStartProximityUpdates(
                mMockProximityUpdateCallbackInternal))
                .isTrue();
        assertThat(mSpyAttentionManager.mCurrentProximityUpdate).isEqualTo(prevProximityUpdate);
        verify(mMockProximityUpdateCallbackInternal, times(1))
                .onProximityUpdate(PROXIMITY_SUCCESS_STATE);
    }

    @Test
    public void testUnregisterProximityUpdates_noCrashWhenNoCallbackIsRegistered() {
        mSpyAttentionManager.onStopProximityUpdates(mMockProximityUpdateCallbackInternal);
        verifyZeroInteractions(mMockProximityUpdateCallbackInternal);
    }

    @Test
    public void testUnregisterProximityUpdates_noCrashWhenCallbackMismatched()
            throws RemoteException {
        mSpyAttentionManager.mIsServiceEnabled = true;
        mSpyAttentionManager.mIsProximityEnabled = true;
        doReturn(true).when(mSpyAttentionManager).isServiceAvailable();
        doReturn(true).when(mMockIPowerManager).isInteractive();
        mSpyAttentionManager.onStartProximityUpdates(mMockProximityUpdateCallbackInternal);
        verify(mMockProximityUpdateCallbackInternal, times(1))
                .onProximityUpdate(PROXIMITY_SUCCESS_STATE);

        ProximityUpdateCallbackInternal mismatchedCallback = new ProximityUpdateCallbackInternal() {
            @Override
            public void onProximityUpdate(double distance) {
                fail("Callback shouldn't have responded.");
            }
        };
        mSpyAttentionManager.onStopProximityUpdates(mismatchedCallback);

        verifyNoMoreInteractions(mMockProximityUpdateCallbackInternal);
    }

    @Test
    public void testUnregisterProximityUpdates_cancelRegistrationWhenMatched()
            throws RemoteException {
        mSpyAttentionManager.mIsServiceEnabled = true;
        mSpyAttentionManager.mIsProximityEnabled = true;
        doReturn(true).when(mSpyAttentionManager).isServiceAvailable();
        doReturn(true).when(mMockIPowerManager).isInteractive();
        mSpyAttentionManager.onStartProximityUpdates(mMockProximityUpdateCallbackInternal);
        mSpyAttentionManager.onStopProximityUpdates(mMockProximityUpdateCallbackInternal);

        assertThat(mSpyAttentionManager.mCurrentProximityUpdate).isNull();
    }

    @Test
    public void testUnregisterProximityUpdates_noCrashWhenTwiceInARow() throws RemoteException {
        // Attention Service registers proximity updates.
        mSpyAttentionManager.mIsServiceEnabled = true;
        mSpyAttentionManager.mIsProximityEnabled = true;
        doReturn(true).when(mSpyAttentionManager).isServiceAvailable();
        doReturn(true).when(mMockIPowerManager).isInteractive();
        mSpyAttentionManager.onStartProximityUpdates(mMockProximityUpdateCallbackInternal);
        verify(mMockProximityUpdateCallbackInternal, times(1))
                .onProximityUpdate(PROXIMITY_SUCCESS_STATE);

        // Attention Service unregisters the proximity update twice in a row.
        mSpyAttentionManager.onStopProximityUpdates(mMockProximityUpdateCallbackInternal);
        mSpyAttentionManager.onStopProximityUpdates(mMockProximityUpdateCallbackInternal);
        verifyNoMoreInteractions(mMockProximityUpdateCallbackInternal);
    }

    @Test
    public void testCancelAttentionCheck_noCrashWhenCallbackMismatched() {
        assertThat(mMockAttentionCallbackInternal).isNotNull();
        mSpyAttentionManager.cancelAttentionCheck(null);
    }

    @Test
    public void testCancelAttentionCheck_cancelCallbackWhenMatched() {
        mSpyAttentionManager.cancelAttentionCheck(mMockAttentionCallbackInternal);
        verify(mSpyAttentionManager).cancel();
    }

    @Test
    public void testCheckAttention_returnFalseWhenPowerManagerNotInteract() throws RemoteException {
        mSpyAttentionManager.mIsServiceEnabled = true;
        mSpyAttentionManager.mIsProximityEnabled = true;
        doReturn(false).when(mMockIPowerManager).isInteractive();
        AttentionCallbackInternal callback = Mockito.mock(AttentionCallbackInternal.class);
        assertThat(mSpyAttentionManager.checkAttention(mTimeout, callback)).isFalse();
    }

    @Test
    public void testCheckAttention_callOnSuccess() throws RemoteException {
        mSpyAttentionManager.mIsServiceEnabled = true;
        mSpyAttentionManager.mIsProximityEnabled = true;
        doReturn(true).when(mSpyAttentionManager).isServiceAvailable();
        doReturn(true).when(mMockIPowerManager).isInteractive();
        mSpyAttentionManager.mCurrentAttentionCheck = null;

        AttentionCallbackInternal callback = Mockito.mock(AttentionCallbackInternal.class);
        mSpyAttentionManager.checkAttention(mTimeout, callback);
        verify(callback).onSuccess(anyInt(), anyLong());
    }

    @Test
    public void testAttentionCheckCacheBuffer_getLast_returnTheLastElement() {
        AttentionCheckCacheBuffer buffer = new AttentionCheckCacheBuffer();
        buffer.add(new AttentionCheckCache(0, 0, 1L));
        AttentionCheckCache cache = new AttentionCheckCache(0, 0, 2L);
        buffer.add(cache);
        assertThat(buffer.getLast()).isEqualTo(cache);
    }

    @Test
    public void testAttentionCheckCacheBuffer_get_returnNullWhenOutOfBoundary() {
        AttentionCheckCacheBuffer buffer = new AttentionCheckCacheBuffer();
        assertThat(buffer.get(1)).isNull();
    }

    @Test
    public void testAttentionCheckCacheBuffer_get_handleCircularIndexing() {
        AttentionCheckCacheBuffer buffer = new AttentionCheckCacheBuffer();
        AttentionCheckCache cache = new AttentionCheckCache(0L, 0, 1L);
        // Insert SIZE+1 elements.
        for (int i = 0; i <= ATTENTION_CACHE_BUFFER_SIZE; i++) {
            if (i == 1) {
                buffer.add(cache);
            } else {
                buffer.add(new AttentionCheckCache(0L, 0, i));
            }
        }
        // The element that was at index 1 should be at index 0 after inserting SIZE + 1 elements.
        assertThat(buffer.get(0)).isEqualTo(cache);
    }

    @Test
    public void testGetStaleAfterMillis_handlesGoodFlagValue() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_STALE_AFTER_MILLIS, "123", false);
        assertThat(mSpyAttentionManager.getStaleAfterMillis()).isEqualTo(123);
    }

    @Test
    public void testGetStaleAfterMillis_handlesBadFlagValue_1() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_STALE_AFTER_MILLIS, "-123", false);
        assertThat(mSpyAttentionManager.getStaleAfterMillis()).isEqualTo(
                DEFAULT_STALE_AFTER_MILLIS);
    }

    @Test
    public void testGetStaleAfterMillis_handlesBadFlagValue_2() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_STALE_AFTER_MILLIS, "15000", false);
        assertThat(mSpyAttentionManager.getStaleAfterMillis()).isEqualTo(
                DEFAULT_STALE_AFTER_MILLIS);
    }

    @Test
    public void testGetStaleAfterMillis_handlesBadFlagValue_3() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_STALE_AFTER_MILLIS, "abracadabra", false);
        assertThat(mSpyAttentionManager.getStaleAfterMillis()).isEqualTo(
                DEFAULT_STALE_AFTER_MILLIS);
    }

    @Test
    public void testGetStaleAfterMillis_handlesBadFlagValue_4() {
        DeviceConfig.setProperty(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_STALE_AFTER_MILLIS, "15_000L", false);
        assertThat(mSpyAttentionManager.getStaleAfterMillis()).isEqualTo(
                DEFAULT_STALE_AFTER_MILLIS);
    }

    private class MockIAttentionService implements IAttentionService {
        public void checkAttention(IAttentionCallback callback) throws RemoteException {
            callback.onSuccess(0, 0);
        }

        public void cancelAttentionCheck(IAttentionCallback callback) {
        }

        public void onStartProximityUpdates(IProximityUpdateCallback callback)
                throws RemoteException {
            callback.onProximityUpdate(PROXIMITY_SUCCESS_STATE);
        }

        public void onStopProximityUpdates() throws RemoteException {
        }

        public IBinder asBinder() {
            return null;
        }
    }
}
