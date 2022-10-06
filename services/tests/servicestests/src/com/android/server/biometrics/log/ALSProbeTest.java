/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.log;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputSensorInfo;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@Presubmit
@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class ALSProbeTest {

    private static final long TIMEOUT_MS = 1000;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private SensorManager mSensorManager;
    @Captor
    private ArgumentCaptor<SensorEventListener> mSensorEventListenerCaptor;

    private TestableLooper mLooper;
    private Sensor mLightSensor = new Sensor(
            new InputSensorInfo("", "", 0, 0, Sensor.TYPE_LIGHT, 0, 0, 0, 0, 0, 0,
                    "", "", 0, 0, 0));

    private ALSProbe mProbe;

    @Before
    public void setup() {
        mLooper = TestableLooper.get(this);
        when(mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)).thenReturn(mLightSensor);
        mProbe = new ALSProbe(mSensorManager, new Handler(mLooper.getLooper()), TIMEOUT_MS - 1);
        reset(mSensorManager);
    }

    @Test
    public void testEnable() {
        final float value = 2.0f;
        mProbe.enable();
        verify(mSensorManager).registerListener(
                mSensorEventListenerCaptor.capture(), any(), anyInt());

        mSensorEventListenerCaptor.getValue().onSensorChanged(
                new SensorEvent(mLightSensor, 1, 1, new float[]{4.0f}));
        mSensorEventListenerCaptor.getValue().onSensorChanged(
                new SensorEvent(mLightSensor, 1, 2, new float[]{value}));

        assertThat(mProbe.getCurrentLux()).isEqualTo(value);
    }

    @Test
    public void testEnableOnlyOnce() {
        mProbe.enable();
        mProbe.enable();

        verify(mSensorManager).registerListener(any(), any(), anyInt());
        verifyNoMoreInteractions(mSensorManager);
    }

    @Test
    public void testDisable() {
        mProbe.enable();
        verify(mSensorManager).registerListener(
                mSensorEventListenerCaptor.capture(), any(), anyInt());
        mProbe.disable();

        verify(mSensorManager).unregisterListener(eq(mSensorEventListenerCaptor.getValue()));
        verifyNoMoreInteractions(mSensorManager);
    }

    @Test
    public void testDestroy() {
        mProbe.destroy();
        mProbe.enable();

        verify(mSensorManager, never()).registerListener(any(), any(), anyInt());
        verifyNoMoreInteractions(mSensorManager);
    }

    @Test
    public void testDisabledReportsNegativeValue() {
        assertThat(mProbe.getCurrentLux()).isLessThan(0f);

        mProbe.enable();
        verify(mSensorManager).registerListener(
                mSensorEventListenerCaptor.capture(), any(), anyInt());
        mSensorEventListenerCaptor.getValue().onSensorChanged(
                new SensorEvent(mLightSensor, 1, 1, new float[]{4.0f}));
        mProbe.disable();

        assertThat(mProbe.getCurrentLux()).isLessThan(0f);
    }

    @Test
    public void testWatchDog() {
        mProbe.enable();
        verify(mSensorManager).registerListener(
                mSensorEventListenerCaptor.capture(), any(), anyInt());
        mSensorEventListenerCaptor.getValue().onSensorChanged(
                new SensorEvent(mLightSensor, 1, 1, new float[]{4.0f}));
        moveTimeBy(TIMEOUT_MS);

        verify(mSensorManager).unregisterListener(eq(mSensorEventListenerCaptor.getValue()));
        verifyNoMoreInteractions(mSensorManager);
        assertThat(mProbe.getCurrentLux()).isLessThan(0f);
    }

    @Test
    public void testEnableExtendsWatchDog() {
        mProbe.enable();
        verify(mSensorManager).registerListener(any(), any(), anyInt());

        moveTimeBy(TIMEOUT_MS / 2);
        verify(mSensorManager, never()).unregisterListener(any(SensorEventListener.class));

        mProbe.enable();
        moveTimeBy(TIMEOUT_MS);

        verify(mSensorManager).unregisterListener(any(SensorEventListener.class));
        verifyNoMoreInteractions(mSensorManager);
        assertThat(mProbe.getCurrentLux()).isLessThan(0f);
    }

    private void moveTimeBy(long millis) {
        mLooper.moveTimeForward(millis);
        mLooper.processAllMessages();
    }
}
