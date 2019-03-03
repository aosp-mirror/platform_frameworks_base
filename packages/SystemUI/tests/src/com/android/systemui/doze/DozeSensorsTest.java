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

package com.android.systemui.doze;

import static com.android.systemui.plugins.SensorManagerPlugin.Sensor.TYPE_WAKE_LOCK_SCREEN;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.SensorManagerPlugin;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.AsyncSensorManager;
import com.android.systemui.util.wakelock.WakeLock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.function.Consumer;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class DozeSensorsTest extends SysuiTestCase {

    @Mock
    private AlarmManager mAlarmManager;
    @Mock
    private AsyncSensorManager mSensorManager;
    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    @Mock
    private WakeLock mWakeLock;
    @Mock
    private DozeSensors.Callback mCallback;
    @Mock
    private Consumer<Boolean> mProxCallback;
    @Mock
    private AlwaysOnDisplayPolicy mAlwaysOnDisplayPolicy;
    private SensorManagerPlugin.SensorEventListener mWakeLockScreenListener;
    private TestableLooper mTestableLooper;
    private DozeSensors mDozeSensors;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        when(mAmbientDisplayConfiguration.getWakeLockScreenDebounce()).thenReturn(5000L);
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mWakeLock).wrap(any(Runnable.class));
        mDozeSensors = new TestableDozeSensors();
    }

    @Test
    public void testSensorDebounce() {
        mDozeSensors.setListening(true);

        mWakeLockScreenListener.onSensorChanged(mock(SensorManagerPlugin.SensorEvent.class));
        mTestableLooper.processAllMessages();
        verify(mCallback).onSensorPulse(eq(DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN),
                anyBoolean(), anyFloat(), anyFloat(), eq(null));

        mDozeSensors.requestTemporaryDisable();
        reset(mCallback);
        mWakeLockScreenListener.onSensorChanged(mock(SensorManagerPlugin.SensorEvent.class));
        mTestableLooper.processAllMessages();
        verify(mCallback, never()).onSensorPulse(eq(DozeLog.PULSE_REASON_SENSOR_WAKE_LOCK_SCREEN),
                anyBoolean(), anyFloat(), anyFloat(), eq(null));
    }

    private class TestableDozeSensors extends DozeSensors {

        TestableDozeSensors() {
            super(getContext(), mAlarmManager, mSensorManager, mDozeParameters,
                    mAmbientDisplayConfiguration, mWakeLock, mCallback, mProxCallback,
                    mAlwaysOnDisplayPolicy);
            for (TriggerSensor sensor : mSensors) {
                if (sensor instanceof PluginSensor
                        && ((PluginSensor) sensor).mPluginSensor.getType()
                        == TYPE_WAKE_LOCK_SCREEN) {
                    mWakeLockScreenListener = (PluginSensor) sensor;
                }
            }
        }
    }
}
