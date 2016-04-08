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

import android.content.ComponentName;
import android.os.Handler;
import android.os.HandlerThread;
import android.test.suitebuilder.annotation.SmallTest;
import com.android.systemui.SysuiTestCase;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@SmallTest
public class TileServiceManagerTests extends SysuiTestCase {

    private TileServices mTileServices;
    private TileLifecycleManager mTileLifecycle;
    private HandlerThread mThread;
    private Handler mHandler;
    private TileServiceManager mTileServiceManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mThread = new HandlerThread("TestThread");
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mTileServices = Mockito.mock(TileServices.class);
        Mockito.when(mTileServices.getContext()).thenReturn(mContext);
        mTileLifecycle = Mockito.mock(TileLifecycleManager.class);
        Mockito.when(mTileLifecycle.isActiveTile()).thenReturn(false);
        ComponentName componentName = new ComponentName(mContext,
                TileServiceManagerTests.class);
        Mockito.when(mTileLifecycle.getComponent()).thenReturn(componentName);
        mTileServiceManager = new TileServiceManager(mTileServices, mHandler, mTileLifecycle);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mThread.quit();
    }

    public void testSetBindRequested() {
        // Request binding.
        mTileServiceManager.setBindRequested(true);
        mTileServiceManager.setLastUpdate(0);
        mTileServiceManager.calculateBindPriority(5);
        Mockito.verify(mTileServices, Mockito.times(2)).recalculateBindAllowance();
        assertEquals(5, mTileServiceManager.getBindPriority());

        // Verify same state doesn't trigger recalculating for no reason.
        mTileServiceManager.setBindRequested(true);
        Mockito.verify(mTileServices, Mockito.times(2)).recalculateBindAllowance();

        mTileServiceManager.setBindRequested(false);
        mTileServiceManager.calculateBindPriority(5);
        Mockito.verify(mTileServices, Mockito.times(3)).recalculateBindAllowance();
        assertEquals(Integer.MIN_VALUE, mTileServiceManager.getBindPriority());
    }

    public void testPendingClickPriority() {
        Mockito.when(mTileLifecycle.hasPendingClick()).thenReturn(true);
        mTileServiceManager.calculateBindPriority(0);
        assertEquals(Integer.MAX_VALUE, mTileServiceManager.getBindPriority());
    }

    public void testBind() {
        // Trigger binding requested and allowed.
        mTileServiceManager.setBindRequested(true);
        mTileServiceManager.setBindAllowed(true);

        ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
        Mockito.verify(mTileLifecycle, Mockito.times(1)).setBindService(captor.capture());
        assertTrue((boolean) captor.getValue());

        mTileServiceManager.setBindRequested(false);
        mTileServiceManager.calculateBindPriority(0);
        // Priority shouldn't disappear after the request goes away if we just bound, instead
        // it sticks around to avoid thrashing a bunch of processes.
        assertEquals(Integer.MAX_VALUE - 2, mTileServiceManager.getBindPriority());

        mTileServiceManager.setBindAllowed(false);
        captor = ArgumentCaptor.forClass(Boolean.class);
        Mockito.verify(mTileLifecycle, Mockito.times(2)).setBindService(captor.capture());
        assertFalse((boolean) captor.getValue());
    }
}
