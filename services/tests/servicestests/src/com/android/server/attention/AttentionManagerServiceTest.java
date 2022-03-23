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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.attention.AttentionManagerInternal.AttentionCallbackInternal;
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

import androidx.test.filters.SmallTest;

import com.android.server.attention.AttentionManagerService.AttentionCheck;
import com.android.server.attention.AttentionManagerService.AttentionCheckCache;
import com.android.server.attention.AttentionManagerService.AttentionCheckCacheBuffer;
import com.android.server.attention.AttentionManagerService.AttentionHandler;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link com.android.server.attention.AttentionManagerService}
 */
@SmallTest
public class AttentionManagerServiceTest {
    private AttentionManagerService mSpyAttentionManager;
    private final int mTimeout = 1000;
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

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        // setup context mock
        doReturn(true).when(mContext).bindServiceAsUser(any(), any(), anyInt(), any());
        // setup power manager mock
        PowerManager mPowerManager;
        doReturn(true).when(mMockIPowerManager).isInteractive();
        mPowerManager = new PowerManager(mContext, mMockIPowerManager, mMockIThermalService, null);

        Object mLock = new Object();
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
        doReturn(false).when(mMockIPowerManager).isInteractive();
        AttentionCallbackInternal callback = Mockito.mock(AttentionCallbackInternal.class);
        assertThat(mSpyAttentionManager.checkAttention(mTimeout, callback)).isFalse();
    }

    @Test
    public void testCheckAttention_callOnSuccess() throws RemoteException {
        mSpyAttentionManager.mIsServiceEnabled = true;
        doReturn(true).when(mSpyAttentionManager).isServiceAvailable();
        doReturn(true).when(mMockIPowerManager).isInteractive();
        doNothing().when(mSpyAttentionManager).freeIfInactiveLocked();
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

        public IBinder asBinder() {
            return null;
        }
    }
}
