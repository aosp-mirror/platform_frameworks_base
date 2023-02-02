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

package com.android.server.policy;


import static com.android.server.policy.FoldableDeviceStateProvider.DeviceStateConfiguration.createConfig;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.hardware.input.InputSensorInfo;

import com.android.server.devicestate.DeviceState;
import com.android.server.devicestate.DeviceStateProvider.Listener;
import com.android.server.policy.FoldableDeviceStateProvider.DeviceStateConfiguration;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.internal.util.reflection.FieldSetter;

/**
 * Unit tests for {@link FoldableDeviceStateProvider}.
 * <p/>
 * Run with <code>atest FoldableDeviceStateProviderTest</code>.
 */
public final class FoldableDeviceStateProviderTest {

    private final ArgumentCaptor<DeviceState[]> mDeviceStateArrayCaptor = ArgumentCaptor.forClass(
            DeviceState[].class);
    private final ArgumentCaptor<Integer> mIntegerCaptor = ArgumentCaptor.forClass(Integer.class);

    private final SensorManager mSensorManager = mock(SensorManager.class);
    private final Sensor mHallSensor = new Sensor(mock(InputSensorInfo.class));
    private final Sensor mHingeAngleSensor = new Sensor(mock(InputSensorInfo.class));

    private FoldableDeviceStateProvider mProvider;

    @Test
    public void create_emptyConfiguration_throwsException() {
        assertThrows(IllegalArgumentException.class, this::createProvider);
    }

    @Test
    public void create_duplicatedDeviceStateIdentifiers_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> createProvider(
                        createConfig(
                                /* identifier= */ 0, /* name= */ "ONE", (c) -> true),
                        createConfig(
                                /* identifier= */ 0, /* name= */ "TWO", (c) -> true)
                ));
    }

    @Test
    public void create_allMatchingStatesDefaultsToTheFirstIdentifier() {
        createProvider(
                createConfig(
                        /* identifier= */ 1, /* name= */ "ONE", (c) -> true),
                createConfig(
                        /* identifier= */ 2, /* name= */ "TWO", (c) -> true),
                createConfig(
                        /* identifier= */ 3, /* name= */ "THREE", (c) -> true)
        );

        Listener listener = mock(Listener.class);
        mProvider.setListener(listener);

        verify(listener).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        final DeviceState[] expectedStates = new DeviceState[]{
                new DeviceState(1, "ONE", /* flags= */ 0),
                new DeviceState(2, "TWO", /* flags= */ 0),
                new DeviceState(3, "THREE", /* flags= */ 0),
        };
        assertArrayEquals(expectedStates, mDeviceStateArrayCaptor.getValue());

        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(1, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void create_multipleMatchingStatesDefaultsToTheLowestIdentifier() {
        createProvider(
                createConfig(
                        /* identifier= */ 1, /* name= */ "ONE", (c) -> false),
                createConfig(
                        /* identifier= */ 3, /* name= */ "THREE", (c) -> false),
                createConfig(
                        /* identifier= */ 4, /* name= */ "FOUR", (c) -> true),
                createConfig(
                        /* identifier= */ 2, /* name= */ "TWO", (c) -> true)
        );

        Listener listener = mock(Listener.class);
        mProvider.setListener(listener);

        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(2, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_hingeAngleUpdatedFirstTime_switchesToMatchingState() throws Exception {
        createProvider(createConfig(/* identifier= */ 1, /* name= */ "ONE",
                        (c) -> c.getHingeAngle() < 90f),
                createConfig(/* identifier= */ 2, /* name= */ "TWO",
                        (c) -> c.getHingeAngle() >= 90f));
        Listener listener = mock(Listener.class);
        mProvider.setListener(listener);
        verify(listener, never()).onStateChanged(anyInt());
        clearInvocations(listener);

        sendSensorEvent(mHingeAngleSensor, /* value= */ 100f);

        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(2, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_hallSensorUpdatedFirstTime_switchesToMatchingState() throws Exception {
        createProvider(createConfig(/* identifier= */ 1, /* name= */ "ONE",
                        (c) -> !c.isHallSensorClosed()),
                createConfig(/* identifier= */ 2, /* name= */ "TWO",
                        FoldableDeviceStateProvider::isHallSensorClosed));
        Listener listener = mock(Listener.class);
        mProvider.setListener(listener);
        verify(listener, never()).onStateChanged(anyInt());
        clearInvocations(listener);

        // Hall sensor value '1f' is for the closed state
        sendSensorEvent(mHallSensor, /* value= */ 1f);

        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(2, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_hingeAngleUpdatedSecondTime_switchesToMatchingState() throws Exception {
        createProvider(createConfig(/* identifier= */ 1, /* name= */ "ONE",
                        (c) -> c.getHingeAngle() < 90f),
                createConfig(/* identifier= */ 2, /* name= */ "TWO",
                        (c) -> c.getHingeAngle() >= 90f));
        sendSensorEvent(mHingeAngleSensor, /* value= */ 30f);
        Listener listener = mock(Listener.class);
        mProvider.setListener(listener);
        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(1, mIntegerCaptor.getValue().intValue());
        clearInvocations(listener);

        sendSensorEvent(mHingeAngleSensor, /* value= */ 100f);

        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(2, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_hallSensorUpdatedSecondTime_switchesToMatchingState() throws Exception {
        createProvider(createConfig(/* identifier= */ 1, /* name= */ "ONE",
                        (c) -> !c.isHallSensorClosed()),
                createConfig(/* identifier= */ 2, /* name= */ "TWO",
                        FoldableDeviceStateProvider::isHallSensorClosed));
        sendSensorEvent(mHallSensor, /* value= */ 0f);
        Listener listener = mock(Listener.class);
        mProvider.setListener(listener);
        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(1, mIntegerCaptor.getValue().intValue());
        clearInvocations(listener);

        // Hall sensor value '1f' is for the closed state
        sendSensorEvent(mHallSensor, /* value= */ 1f);

        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(2, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_invalidSensorValues_onStateChangedIsNotTriggered() throws Exception {
        createProvider(createConfig(/* identifier= */ 1, /* name= */ "ONE",
                        (c) -> c.getHingeAngle() < 90f),
                createConfig(/* identifier= */ 2, /* name= */ "TWO",
                        (c) -> c.getHingeAngle() >= 90f));
        Listener listener = mock(Listener.class);
        mProvider.setListener(listener);
        clearInvocations(listener);

        // First, switch to a non-default state.
        sendSensorEvent(mHingeAngleSensor, /* value= */ 100f);
        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(2, mIntegerCaptor.getValue().intValue());

        clearInvocations(listener);

        // Then, send an invalid sensor event, verify that onStateChanged() is not triggered.
        sendInvalidSensorEvent(mHingeAngleSensor);

        verify(listener, never()).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        verify(listener, never()).onStateChanged(mIntegerCaptor.capture());
    }

    @Test
    public void test_previousStateBasedPredicate() {
        // Create a configuration where state TWO could be matched only if
        // the previous state was 'THREE'
        createProvider(
                createConfig(
                        /* identifier= */ 1, /* name= */ "ONE", (c) -> c.getHingeAngle() < 30f),
                createConfig(
                        /* identifier= */ 2, /* name= */ "TWO",
                        (c) -> c.getLastReportedDeviceState() == 3 && c.getHingeAngle() > 120f),
                createConfig(
                        /* identifier= */ 3, /* name= */ "THREE",
                        (c) -> c.getHingeAngle() > 90f)
        );
        sendSensorEvent(mHingeAngleSensor, /* value= */ 0f);
        Listener listener = mock(Listener.class);
        mProvider.setListener(listener);

        // Check that the initial state is 'ONE'
        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(1, mIntegerCaptor.getValue().intValue());
        clearInvocations(listener);

        // Should not match state 'TWO', it should match only state 'THREE'
        // (because the previous state is not 'THREE', it is 'ONE')
        sendSensorEvent(mHingeAngleSensor, /* value= */ 180f);
        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(3, mIntegerCaptor.getValue().intValue());
        clearInvocations(listener);

        // Now it should match state 'TWO'
        // (because the previous state is 'THREE' now)
        sendSensorEvent(mHingeAngleSensor, /* value= */ 180f);
        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(2, mIntegerCaptor.getValue().intValue());
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

        mProvider.onSensorChanged(event);
    }

    private void sendInvalidSensorEvent(Sensor sensor) {
        SensorEvent event = mock(SensorEvent.class);
        event.sensor = sensor;
        try {
            // Set empty values array to make the event invalid
            FieldSetter.setField(event, event.getClass().getField("values"),
                    new float[]{});
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
        mProvider.onSensorChanged(event);
    }

    private void createProvider(DeviceStateConfiguration... configurations) {
        mProvider = new FoldableDeviceStateProvider(mSensorManager, mHingeAngleSensor, mHallSensor,
                configurations);
    }
}
