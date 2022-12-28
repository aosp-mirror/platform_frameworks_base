/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.policy;


import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputSensorInfo;

import com.android.server.devicestate.DeviceStateProvider;
import com.android.server.devicestate.DeviceStateProvider.Listener;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.FieldSetter;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link TentModeDeviceStatePolicy.Provider}.
 * <p/>
 * Run with <code>atest TentModeDeviceStateProviderTest</code>.
 */
public final class TentModeDeviceStateProviderTest {

    private static final int DEVICE_STATE_CLOSED = 0;
    private static final int DEVICE_STATE_HALF_OPENED = 1;
    private static final int DEVICE_STATE_OPENED = 2;

    private final ArgumentCaptor<Integer> mIntegerCaptor = ArgumentCaptor.forClass(Integer.class);

    private final Context mContext = mock(Context.class);
    private final SensorManager mSensorManager = mock(SensorManager.class);
    private final Sensor mHallSensor = new Sensor(mock(InputSensorInfo.class));
    private final Sensor mHingeAngleSensor = new Sensor(mock(InputSensorInfo.class));
    private final Listener mListener = mock(Listener.class);

    private SensorEventListener mSensorEventListener;
    private DeviceStateProvider mProvider;

    @Before
    public void setup() {
        when(mContext.getSystemServiceName(SensorManager.class)).thenReturn(Context.SENSOR_SERVICE);
        when(mContext.getSystemService(Context.SENSOR_SERVICE)).thenReturn(mSensorManager);

        when(mSensorManager.getDefaultSensor(eq(Sensor.TYPE_HINGE_ANGLE), eq(true)))
                .thenReturn(mHingeAngleSensor);

        final List<Sensor> sensors = new ArrayList<>();
        sensors.add(mHallSensor);
        sensors.add(mHingeAngleSensor);

        when(mSensorManager.registerListener(any(), any(), anyInt())).thenAnswer(invocation -> {
            mSensorEventListener = invocation.getArgument(0);
            return true;
        });

        try {
            FieldSetter.setField(mHallSensor, mHallSensor.getClass()
                    .getDeclaredField("mStringType"), "com.google.sensor.hall_effect");
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }

        when(mSensorManager.getSensorList(eq(Sensor.TYPE_ALL)))
                .thenReturn(sensors);

        mProvider = new TentModeDeviceStatePolicy.Provider().instantiate(mContext)
                .getDeviceStateProvider();
    }

    @Test
    public void test_noSensorEventsYet_reportOpenedState() {
        mProvider.setListener(mListener);
        verify(mListener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(DEVICE_STATE_OPENED, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_deviceClosedSensorEventsBecameAvailable_reportsClosedState() {
        mProvider.setListener(mListener);
        clearInvocations(mListener);

        sendClosedHallSensorEvent();
        sendHingeAngle(0f);

        verify(mListener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(DEVICE_STATE_CLOSED, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_hallSensorClosedAndHingeAngleClosed_reportsClosedState() {
        sendClosedHallSensorEvent();
        sendHingeAngle(0f);

        mProvider.setListener(mListener);
        verify(mListener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(DEVICE_STATE_CLOSED, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_hallSensorClosedAndHingeAngleFullyOpened_reportsOpenedState() {
        sendClosedHallSensorEvent();
        sendHingeAngle(180f);

        mProvider.setListener(mListener);
        verify(mListener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(DEVICE_STATE_OPENED, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_hallSensorOpenedAndHingeAngleClosed_reportsClosedState() {
        sendOpenedHallSensorEvent();
        sendHingeAngle(0f);

        mProvider.setListener(mListener);
        verify(mListener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(DEVICE_STATE_CLOSED, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_unfoldingFromClosedToFullyOpened_reportsOpenedEvent() {
        sendClosedHallSensorEvent();
        sendHingeAngle(0f);
        mProvider.setListener(mListener);
        clearInvocations(mListener);

        sendOpenedHallSensorEvent();
        sendHingeAngle(180f);

        verify(mListener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(DEVICE_STATE_OPENED, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_unfoldingFromClosedToTentMode_keepsClosedState() {
        sendClosedHallSensorEvent();
        sendHingeAngle(0f);
        mProvider.setListener(mListener);
        verify(mListener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(DEVICE_STATE_CLOSED, mIntegerCaptor.getValue().intValue());
        clearInvocations(mListener);

        sendOpenedHallSensorEvent();
        sendHingeAngle(60f);

        verify(mListener, never()).onStateChanged(mIntegerCaptor.capture());
    }

    @Test
    public void test_foldingFromFullyOpenToAlmostClosed_movesToHalfOpenedState() {
        sendOpenedHallSensorEvent();
        sendHingeAngle(180f);
        mProvider.setListener(mListener);
        clearInvocations(mListener);

        sendHingeAngle(15f);

        verify(mListener).onStateChanged(mIntegerCaptor.capture());
        // Assert that we don't go into tent mode (OPENED state) and switch to HALF_OPENED state
        assertEquals(DEVICE_STATE_HALF_OPENED, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_foldingFromFullyOpenToFullyClosed_movesToClosedState() {
        sendOpenedHallSensorEvent();
        sendHingeAngle(180f);

        sendClosedHallSensorEvent();
        sendHingeAngle(0f);

        mProvider.setListener(mListener);
        verify(mListener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(DEVICE_STATE_CLOSED, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_slowUnfolding_reportsEventsInOrder() {
        sendClosedHallSensorEvent();
        sendHingeAngle(0f);
        mProvider.setListener(mListener);

        sendHingeAngle(2f);
        sendOpenedHallSensorEvent();
        sendHingeAngle(10f);
        sendHingeAngle(60f);
        sendHingeAngle(100f);
        sendHingeAngle(180f);

        verify(mListener, atLeastOnce()).onStateChanged(mIntegerCaptor.capture());
        assertThat(mIntegerCaptor.getAllValues()).containsExactly(
                DEVICE_STATE_CLOSED,
                DEVICE_STATE_HALF_OPENED,
                DEVICE_STATE_OPENED
        );
    }

    @Test
    public void test_slowFolding_reportsEventsInOrder() {
        sendOpenedHallSensorEvent();
        sendHingeAngle(180f);
        mProvider.setListener(mListener);

        sendHingeAngle(180f);
        sendHingeAngle(100f);
        sendHingeAngle(60f);
        sendHingeAngle(10f);
        sendClosedHallSensorEvent();
        sendHingeAngle(2f);

        verify(mListener, atLeastOnce()).onStateChanged(mIntegerCaptor.capture());
        assertThat(mIntegerCaptor.getAllValues()).containsExactly(
                DEVICE_STATE_OPENED,
                DEVICE_STATE_HALF_OPENED,
                DEVICE_STATE_CLOSED
        );
    }

    @Test
    public void test_slowUnfoldingAndFolding_reportsEventsInOrder() {
        sendClosedHallSensorEvent();
        sendHingeAngle(0f);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_CLOSED);

        // Started unfolding
        sendHingeAngle(2f);
        sendOpenedHallSensorEvent();
        sendHingeAngle(30f);
        assertLatestReportedState(DEVICE_STATE_CLOSED);
        sendHingeAngle(60f);
        assertLatestReportedState(DEVICE_STATE_CLOSED);
        sendHingeAngle(100f);
        assertLatestReportedState(DEVICE_STATE_HALF_OPENED);
        sendHingeAngle(180f);
        assertLatestReportedState(DEVICE_STATE_OPENED);

        // Started folding
        sendHingeAngle(100f);
        assertLatestReportedState(DEVICE_STATE_HALF_OPENED);
        sendHingeAngle(60f);
        assertLatestReportedState(DEVICE_STATE_HALF_OPENED);
        sendHingeAngle(30f);
        assertLatestReportedState(DEVICE_STATE_HALF_OPENED);
        sendClosedHallSensorEvent();
        sendHingeAngle(2f);
        assertLatestReportedState(DEVICE_STATE_CLOSED);

        verify(mListener, atLeastOnce()).onStateChanged(mIntegerCaptor.capture());
        assertThat(mIntegerCaptor.getAllValues()).containsExactly(
                DEVICE_STATE_CLOSED,
                DEVICE_STATE_HALF_OPENED,
                DEVICE_STATE_OPENED,
                DEVICE_STATE_HALF_OPENED,
                DEVICE_STATE_CLOSED
        );
    }

    private void assertLatestReportedState(int state) {
        final ArgumentCaptor<Integer> integerCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mListener, atLeastOnce()).onStateChanged(integerCaptor.capture());
        assertEquals(state, integerCaptor.getValue().intValue());
    }

    private void sendSensorEvent(Sensor sensor, float value) {
        SensorEvent event = mock(SensorEvent.class);
        event.sensor = sensor;
        try {
            FieldSetter.setField(event, event.getClass().getField("values"),
                    new float[]{value});
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }

        mSensorEventListener.onSensorChanged(event);
    }

    private void sendClosedHallSensorEvent() {
        // Hall sensor value '1f' is for the closed state
        sendSensorEvent(mHallSensor, /* value= */ 1f);
    }

    private void sendOpenedHallSensorEvent() {
        // Hall sensor value '0f' is for the opened state
        sendSensorEvent(mHallSensor, /* value= */ 0f);
    }

    private void sendHingeAngle(float angle) {
        sendSensorEvent(mHingeAngleSensor, /* value= */ angle);
    }
}
