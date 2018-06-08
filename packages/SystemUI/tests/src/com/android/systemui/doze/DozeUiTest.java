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

import static com.android.systemui.doze.DozeMachine.State.DOZE;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.wakelock.WakeLockFake;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DozeUiTest extends SysuiTestCase {

    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private DozeMachine mMachine;
    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    private DozeHost mHost;
    private WakeLockFake mWakeLock;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private DozeUi mDozeUi;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread("DozeUiTest");
        mHandlerThread.start();
        mWakeLock = new WakeLockFake();
        mHandler = mHandlerThread.getThreadHandler();

        mDozeUi = new DozeUi(mContext, mAlarmManager, mMachine, mWakeLock, mHost, mHandler,
                mDozeParameters, mKeyguardUpdateMonitor);
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

    @Test
    public void propagatesAnimateScreenOff_noAlwaysOn() {
        reset(mHost);
        when(mDozeParameters.getAlwaysOn()).thenReturn(false);
        when(mDozeParameters.getDisplayNeedsBlanking()).thenReturn(false);

        mDozeUi.getKeyguardCallback().onKeyguardVisibilityChanged(false);
        verify(mHost).setAnimateScreenOff(eq(false));
    }

    @Test
    public void propagatesAnimateScreenOff_alwaysOn() {
        reset(mHost);
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);
        when(mDozeParameters.getDisplayNeedsBlanking()).thenReturn(false);

        // Take over when the keyguard is visible.
        mDozeUi.getKeyguardCallback().onKeyguardVisibilityChanged(true);
        verify(mHost).setAnimateScreenOff(eq(true));

        // Do not animate screen-off when keyguard isn't visible - PowerManager will do it.
        mDozeUi.getKeyguardCallback().onKeyguardVisibilityChanged(false);
        verify(mHost).setAnimateScreenOff(eq(false));
    }

    @Test
    public void neverAnimateScreenOff_whenNotSupported() {
        // Re-initialize DozeParameters saying that the display requires blanking.
        reset(mDozeParameters);
        reset(mHost);
        when(mDozeParameters.getDisplayNeedsBlanking()).thenReturn(true);
        mDozeUi = new DozeUi(mContext, mAlarmManager, mMachine, mWakeLock, mHost, mHandler,
                mDozeParameters, mKeyguardUpdateMonitor);

        // Never animate if display doesn't support it.
        mDozeUi.getKeyguardCallback().onKeyguardVisibilityChanged(true);
        mDozeUi.getKeyguardCallback().onKeyguardVisibilityChanged(false);
        verify(mHost, never()).setAnimateScreenOff(eq(false));
    }

    @Test
    public void transitionSetsAnimateWakeup_alwaysOn() {
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);
        when(mDozeParameters.getDisplayNeedsBlanking()).thenReturn(false);
        mDozeUi.transitionTo(UNINITIALIZED, DOZE);
        verify(mHost).setAnimateWakeup(eq(true));
    }

    @Test
    public void keyguardVisibility_changesControlScreenOffAnimation() {
        // Pre-condition
        reset(mDozeParameters);
        when(mDozeParameters.getAlwaysOn()).thenReturn(true);
        when(mDozeParameters.getDisplayNeedsBlanking()).thenReturn(false);

        mDozeUi.getKeyguardCallback().onKeyguardVisibilityChanged(false);
        verify(mDozeParameters).setControlScreenOffAnimation(eq(false));
        mDozeUi.getKeyguardCallback().onKeyguardVisibilityChanged(true);
        verify(mDozeParameters).setControlScreenOffAnimation(eq(true));
    }

    @Test
    public void transitionSetsAnimateWakeup_noAlwaysOn() {
        mDozeUi.transitionTo(UNINITIALIZED, DOZE);
        verify(mHost).setAnimateWakeup(eq(false));
    }
}