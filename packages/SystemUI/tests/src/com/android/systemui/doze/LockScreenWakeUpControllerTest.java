/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.PowerManager;
import android.support.test.filters.SmallTest;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.SensorManagerPlugin;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateController;
import com.android.systemui.util.AsyncSensorManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
@SmallTest
public class LockScreenWakeUpControllerTest extends SysuiTestCase {

    @Mock
    private AsyncSensorManager mAsyncSensorManager;
    @Mock
    private SensorManagerPlugin.Sensor mSensor;
    @Mock
    private AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    @Mock
    private PowerManager mPowerManager;
    @Mock
    private DozeHost mDozeHost;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private Handler mHandler;

    private LockScreenWakeUpController mLockScreenWakeUpController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mHandler).post(any());

        mLockScreenWakeUpController = new LockScreenWakeUpController(mAsyncSensorManager, mSensor,
                mAmbientDisplayConfiguration, mPowerManager, mDozeHost, mStatusBarStateController,
                mHandler);
    }

    @Test
    public void testOnStateChanged_registersUnregistersListener() {
        when(mAmbientDisplayConfiguration.wakeLockScreenGestureEnabled(anyInt())).thenReturn(true);
        mLockScreenWakeUpController.onStateChanged(StatusBarState.KEYGUARD);
        mLockScreenWakeUpController.onStateChanged(StatusBarState.SHADE);

        verify(mAsyncSensorManager, times(1)).registerPluginListener(eq(mSensor),
                eq(mLockScreenWakeUpController));

        mLockScreenWakeUpController.onStateChanged(StatusBarState.SHADE);
        verify(mAsyncSensorManager).unregisterPluginListener(eq(mSensor),
                eq(mLockScreenWakeUpController));
    }

    @Test
    public void testOnStateChanged_disabledSensor() {
        when(mAmbientDisplayConfiguration.wakeLockScreenGestureEnabled(anyInt()))
                .thenReturn(false);
        mLockScreenWakeUpController.onStateChanged(StatusBarState.KEYGUARD);
        mLockScreenWakeUpController.onStateChanged(StatusBarState.SHADE);

        verify(mAsyncSensorManager, never()).registerPluginListener(eq(mSensor),
                eq(mLockScreenWakeUpController));
    }

    @Test
    public void testOnSensorChanged_postsToMainThread() {
        SensorManagerPlugin.SensorEvent event = new SensorManagerPlugin.SensorEvent(mSensor, 0);
        mLockScreenWakeUpController.onSensorChanged(event);

        verify(mHandler).post(any());
    }

    @Test
    public void testOnSensorChanged_wakeUpWhenDozing() {
        SensorManagerPlugin.SensorEvent event =
                new SensorManagerPlugin.SensorEvent(mSensor, 0, new float[] {1});
        mLockScreenWakeUpController.onSensorChanged(event);
        verify(mPowerManager, never()).wakeUp(anyLong(), any());

        mLockScreenWakeUpController.onDozingChanged(true);
        mLockScreenWakeUpController.onSensorChanged(event);
        verify(mPowerManager).wakeUp(anyLong(), any());
    }

    @Test
    public void testOnSensorChanged_sleepsWhenAwake() {
        boolean[] goToSleep = new boolean[] {false};
        doAnswer(invocation -> goToSleep[0] = true)
                .when(mPowerManager).goToSleep(anyLong(), anyInt(), anyInt());
        SensorManagerPlugin.SensorEvent event =
                new SensorManagerPlugin.SensorEvent(mSensor, 0, new float[] {0});
        mLockScreenWakeUpController.onDozingChanged(true);
        mLockScreenWakeUpController.onSensorChanged(event);
        Assert.assertFalse("goToSleep should have never been called.", goToSleep[0]);

        mLockScreenWakeUpController.onDozingChanged(false);
        mLockScreenWakeUpController.onSensorChanged(event);
        Assert.assertTrue("goToSleep should have been called.", goToSleep[0]);
    }
}
