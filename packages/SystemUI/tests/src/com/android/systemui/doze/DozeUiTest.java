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

package com.android.systemui.doze;

import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSED;
import static com.android.systemui.doze.DozeMachine.State.INITIALIZED;
import static com.android.systemui.doze.DozeMachine.State.UNINITIALIZED;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.wakelock.WakeLockFake;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DozeUiTest extends SysuiTestCase {

    private AlarmManager mAlarmManager;
    private DozeMachine mMachine;
    private WakeLockFake mWakeLock;
    private DozeHostFake mHost;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private DozeUi mDozeUi;

    @Before
    public void setUp() throws Exception {
        mHandlerThread = new HandlerThread("DozeUiTest");
        mHandlerThread.start();
        mAlarmManager = mock(AlarmManager.class);
        mMachine = mock(DozeMachine.class);
        mWakeLock = new WakeLockFake();
        mHost = new DozeHostFake();
        mHandler = mHandlerThread.getThreadHandler();

        mDozeUi = new DozeUi(mContext, mAlarmManager, mMachine, mWakeLock, mHost, mHandler);
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandler = null;
        mHandlerThread = null;
    }

    @Test
    public void pausingAndUnpausingAod_registersTimeTickAfterUnpausing() throws Exception {
        mDozeUi.transitionTo(UNINITIALIZED, INITIALIZED);
        mDozeUi.transitionTo(INITIALIZED, DOZE_AOD);
        mDozeUi.transitionTo(DOZE_AOD, DOZE_AOD_PAUSED);

        clearInvocations(mAlarmManager);

        mDozeUi.transitionTo(DOZE_AOD_PAUSED, DOZE_AOD);

        verify(mAlarmManager).setExact(anyInt(), anyLong(), eq("doze_time_tick"), any(), any());
    }
}