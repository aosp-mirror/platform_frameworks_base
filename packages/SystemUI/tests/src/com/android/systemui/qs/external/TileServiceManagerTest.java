/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.qs.external;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.pipeline.data.repository.CustomTileAddedRepository;
import com.android.systemui.settings.UserTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TileServiceManagerTest extends SysuiTestCase {

    @Mock
    private TileServices mTileServices;
    @Mock
    private TileLifecycleManager mTileLifecycle;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private QSHost mQSHost;
    @Mock
    private Context mMockContext;
    @Mock
    private CustomTileAddedRepository mCustomTileAddedRepository;

    private HandlerThread mThread;
    private Handler mHandler;
    private TileServiceManager mTileServiceManager;
    private ComponentName mComponentName;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mThread = new HandlerThread("TestThread");
        mThread.start();
        mHandler = Handler.createAsync(mThread.getLooper());
        when(mUserTracker.getUserId()).thenReturn(UserHandle.USER_SYSTEM);
        when(mUserTracker.getUserHandle()).thenReturn(UserHandle.SYSTEM);

        when(mTileServices.getContext()).thenReturn(mMockContext);
        when(mTileServices.getHost()).thenReturn(mQSHost);
        when(mTileLifecycle.getUserId()).thenAnswer(invocation -> mUserTracker.getUserId());
        when(mTileLifecycle.isActiveTile()).thenReturn(false);

        mComponentName = new ComponentName(mContext, TileServiceManagerTest.class);
        when(mTileLifecycle.getComponent()).thenReturn(mComponentName);

        mTileServiceManager = new TileServiceManager(mTileServices, mHandler, mUserTracker,
                mCustomTileAddedRepository, mTileLifecycle);
    }

    @After
    public void tearDown() throws Exception {
        mThread.quit();
        mTileServiceManager.handleDestroy();
    }

    @Test
    public void testSetTileAddedIfNotAdded() {
        when(mCustomTileAddedRepository.isTileAdded(eq(mComponentName), anyInt()))
                .thenReturn(false);
        mTileServiceManager.startLifecycleManagerAndAddTile();

        verify(mCustomTileAddedRepository)
                .setTileAdded(mComponentName, mUserTracker.getUserId(), true);
    }

    @Test
    public void testNotSetTileAddedIfAdded() {
        when(mCustomTileAddedRepository.isTileAdded(eq(mComponentName), anyInt()))
                .thenReturn(true);
        mTileServiceManager.startLifecycleManagerAndAddTile();

        verify(mCustomTileAddedRepository, never())
                .setTileAdded(eq(mComponentName), anyInt(), eq(true));
    }

    @Test
    public void testSetTileAddedCorrectUser() {
        int user = 10;
        when(mUserTracker.getUserId()).thenReturn(user);
        when(mCustomTileAddedRepository.isTileAdded(eq(mComponentName), anyInt()))
                .thenReturn(false);
        mTileServiceManager.startLifecycleManagerAndAddTile();

        verify(mCustomTileAddedRepository)
                .setTileAdded(mComponentName, user, true);
    }

    @Test
    public void testUninstallReceiverExported() {
        mTileServiceManager.startLifecycleManagerAndAddTile();
        ArgumentCaptor<IntentFilter> intentFilterCaptor =
                ArgumentCaptor.forClass(IntentFilter.class);

        verify(mMockContext).registerReceiverAsUser(
                any(),
                any(),
                intentFilterCaptor.capture(),
                any(),
                any(),
                eq(Context.RECEIVER_EXPORTED)
        );
        IntentFilter filter = intentFilterCaptor.getValue();
        assertTrue(filter.hasAction(Intent.ACTION_PACKAGE_REMOVED));
        assertTrue(filter.hasDataScheme("package"));
    }

    @Test
    public void testSetBindRequested() {
        mTileServiceManager.startLifecycleManagerAndAddTile();
        // Request binding.
        mTileServiceManager.setBindRequested(true);
        mTileServiceManager.setLastUpdate(0);
        mTileServiceManager.calculateBindPriority(5);
        verify(mTileServices, times(2)).recalculateBindAllowance();
        assertEquals(5, mTileServiceManager.getBindPriority());

        // Verify same state doesn't trigger recalculating for no reason.
        mTileServiceManager.setBindRequested(true);
        verify(mTileServices, times(2)).recalculateBindAllowance();

        mTileServiceManager.setBindRequested(false);
        mTileServiceManager.calculateBindPriority(5);
        verify(mTileServices, times(3)).recalculateBindAllowance();
        assertEquals(Integer.MIN_VALUE, mTileServiceManager.getBindPriority());
    }

    @Test
    public void testPendingClickPriority() {
        mTileServiceManager.startLifecycleManagerAndAddTile();
        when(mTileLifecycle.hasPendingClick()).thenReturn(true);
        mTileServiceManager.calculateBindPriority(0);
        assertEquals(Integer.MAX_VALUE, mTileServiceManager.getBindPriority());
    }

    @Test
    public void testBind() {
        mTileServiceManager.startLifecycleManagerAndAddTile();
        // Trigger binding requested and allowed.
        mTileServiceManager.setBindRequested(true);
        mTileServiceManager.setBindAllowed(true);

        ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
        verify(mTileLifecycle, times(1)).executeSetBindService(captor.capture());
        assertTrue((boolean) captor.getValue());

        mTileServiceManager.setBindRequested(false);
        mTileServiceManager.calculateBindPriority(0);
        // Priority shouldn't disappear after the request goes away if we just bound, instead
        // it sticks around to avoid thrashing a bunch of processes.
        assertEquals(Integer.MAX_VALUE - 2, mTileServiceManager.getBindPriority());

        mTileServiceManager.setBindAllowed(false);
        captor = ArgumentCaptor.forClass(Boolean.class);
        verify(mTileLifecycle, times(2)).executeSetBindService(captor.capture());
        assertFalse((boolean) captor.getValue());
    }
}
