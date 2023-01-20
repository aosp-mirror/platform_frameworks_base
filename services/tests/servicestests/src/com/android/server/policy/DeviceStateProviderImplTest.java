/*
 * Copyright (C) 2020 The Android Open Source Project
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


import static android.content.Context.SENSOR_SERVICE;

import static com.android.server.policy.DeviceStateProviderImpl.DEFAULT_DEVICE_STATE;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.PowerManager;

import androidx.annotation.NonNull;

import com.android.server.LocalServices;
import com.android.server.devicestate.DeviceState;
import com.android.server.devicestate.DeviceStateProvider;
import com.android.server.input.InputManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.internal.util.reflection.FieldSetter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.List;

/**
 * Unit tests for {@link DeviceStateProviderImpl}.
 * <p/>
 * Run with <code>atest DeviceStateProviderImplTest</code>.
 */
public final class DeviceStateProviderImplTest {
    private final ArgumentCaptor<DeviceState[]> mDeviceStateArrayCaptor = ArgumentCaptor.forClass(
            DeviceState[].class);
    private final ArgumentCaptor<Integer> mIntegerCaptor = ArgumentCaptor.forClass(Integer.class);
    private static final int MAX_HINGE_ANGLE_EXCLUSIVE = 360;

    private Context mContext;
    private SensorManager mSensorManager;

    @Before
    public void setup() {
        LocalServices.addService(InputManagerInternal.class, mock(InputManagerInternal.class));
        mContext = mock(Context.class);
        mSensorManager = mock(SensorManager.class);
        when(mContext.getSystemServiceName(eq(SensorManager.class))).thenReturn(SENSOR_SERVICE);
        when(mContext.getSystemService(eq(SENSOR_SERVICE))).thenReturn(mSensorManager);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(InputManagerInternal.class);
    }

    @Test
    public void create_noConfig() {
        assertDefaultProviderValues(null);
    }

    @Test
    public void create_emptyFile() {
        String configString = "";
        DeviceStateProviderImpl.ReadableConfig config = new TestReadableConfig(configString);

        assertDefaultProviderValues(config);
    }

    @Test
    public void create_emptyConfig() {
        String configString = "<device-state-config></device-state-config>";
        DeviceStateProviderImpl.ReadableConfig config = new TestReadableConfig(configString);

        assertDefaultProviderValues(config);
    }

    @Test
    public void create_invalidConfig() {
        String configString = "<device-state-config>\n"
                + "    </device-state>\n"
                + "</device-state-config>\n";
        DeviceStateProviderImpl.ReadableConfig config = new TestReadableConfig(configString);

        assertDefaultProviderValues(config);
    }

    private void assertDefaultProviderValues(
            @Nullable DeviceStateProviderImpl.ReadableConfig config) {
        DeviceStateProviderImpl provider = DeviceStateProviderImpl.createFromConfig(mContext,
                config);

        DeviceStateProvider.Listener listener = mock(DeviceStateProvider.Listener.class);
        provider.setListener(listener);

        verify(listener).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        assertArrayEquals(new DeviceState[]{DEFAULT_DEVICE_STATE},
                mDeviceStateArrayCaptor.getValue());

        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(DEFAULT_DEVICE_STATE.getIdentifier(), mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void create_multipleMatchingStatesDefaultsToLowestIdentifier() {
        String configString = "<device-state-config>\n"
                + "    <device-state>\n"
                + "        <identifier>1</identifier>\n"
                + "        <conditions/>\n"
                + "    </device-state>\n"
                + "    <device-state>\n"
                + "        <identifier>2</identifier>\n"
                + "        <conditions/>\n"
                + "    </device-state>\n"
                + "</device-state-config>\n";
        DeviceStateProviderImpl.ReadableConfig config = new TestReadableConfig(configString);
        DeviceStateProviderImpl provider = DeviceStateProviderImpl.createFromConfig(mContext,
                config);

        DeviceStateProvider.Listener listener = mock(DeviceStateProvider.Listener.class);
        provider.setListener(listener);

        verify(listener).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        final DeviceState[] expectedStates = new DeviceState[]{
                new DeviceState(1, "", 0 /* flags */),
                new DeviceState(2, "", 0 /* flags */) };
        assertArrayEquals(expectedStates, mDeviceStateArrayCaptor.getValue());

        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(1, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void create_stateWithCancelOverrideRequestFlag() {
        String configString = "<device-state-config>\n"
                + "    <device-state>\n"
                + "        <identifier>1</identifier>\n"
                + "        <flags>\n"
                + "            <flag>FLAG_CANCEL_OVERRIDE_REQUESTS</flag>\n"
                + "        </flags>\n"
                + "        <conditions/>\n"
                + "    </device-state>\n"
                + "    <device-state>\n"
                + "        <identifier>2</identifier>\n"
                + "        <conditions/>\n"
                + "    </device-state>\n"
                + "</device-state-config>\n";
        DeviceStateProviderImpl.ReadableConfig config = new TestReadableConfig(configString);
        DeviceStateProviderImpl provider = DeviceStateProviderImpl.createFromConfig(mContext,
                config);

        DeviceStateProvider.Listener listener = mock(DeviceStateProvider.Listener.class);
        provider.setListener(listener);

        verify(listener).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        final DeviceState[] expectedStates = new DeviceState[]{
                new DeviceState(1, "", DeviceState.FLAG_CANCEL_OVERRIDE_REQUESTS),
                new DeviceState(2, "", 0 /* flags */) };
        assertArrayEquals(expectedStates, mDeviceStateArrayCaptor.getValue());
    }

    @Test
    public void create_stateWithInvalidFlag() {
        String configString = "<device-state-config>\n"
                + "    <device-state>\n"
                + "        <identifier>1</identifier>\n"
                + "        <flags>\n"
                + "            <flag>INVALID_FLAG</flag>\n"
                + "        </flags>\n"
                + "        <conditions/>\n"
                + "    </device-state>\n"
                + "    <device-state>\n"
                + "        <identifier>2</identifier>\n"
                + "        <conditions/>\n"
                + "    </device-state>\n"
                + "</device-state-config>\n";
        DeviceStateProviderImpl.ReadableConfig config = new TestReadableConfig(configString);
        DeviceStateProviderImpl provider = DeviceStateProviderImpl.createFromConfig(mContext,
                config);

        DeviceStateProvider.Listener listener = mock(DeviceStateProvider.Listener.class);
        provider.setListener(listener);

        verify(listener).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        final DeviceState[] expectedStates = new DeviceState[]{
                new DeviceState(1, "", 0 /* flags */),
                new DeviceState(2, "", 0 /* flags */) };
        assertArrayEquals(expectedStates, mDeviceStateArrayCaptor.getValue());
    }

    @Test
    public void create_lidSwitch() {
        String configString = "<device-state-config>\n"
                + "    <device-state>\n"
                + "        <identifier>1</identifier>\n"
                + "        <conditions>\n"
                + "            <lid-switch>\n"
                + "                <open>true</open>\n"
                + "            </lid-switch>\n"
                + "        </conditions>\n"
                + "    </device-state>\n"
                + "    <device-state>\n"
                + "        <identifier>2</identifier>\n"
                + "        <name>CLOSED</name>\n"
                + "        <conditions>\n"
                + "            <lid-switch>\n"
                + "                <open>false</open>\n"
                + "            </lid-switch>\n"
                + "        </conditions>\n"
                + "    </device-state>\n"
                + "</device-state-config>\n";
        DeviceStateProviderImpl.ReadableConfig config = new TestReadableConfig(configString);
        DeviceStateProviderImpl provider = DeviceStateProviderImpl.createFromConfig(mContext,
                config);

        DeviceStateProvider.Listener listener = mock(DeviceStateProvider.Listener.class);
        provider.setListener(listener);

        verify(listener).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        final DeviceState[] expectedStates = new DeviceState[]{
                new DeviceState(1, "", 0 /* flags */),
                new DeviceState(2, "CLOSED", 0 /* flags */) };
        assertArrayEquals(expectedStates, mDeviceStateArrayCaptor.getValue());

        // onStateChanged() should not be called because the provider has not yet been notified of
        // the initial lid switch state.
        verify(listener, never()).onStateChanged(mIntegerCaptor.capture());

        provider.notifyLidSwitchChanged(0, false /* lidOpen */);

        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(2, mIntegerCaptor.getValue().intValue());

        Mockito.clearInvocations(listener);

        provider.notifyLidSwitchChanged(1, true /* lidOpen */);
        verify(listener, never()).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(1, mIntegerCaptor.getValue().intValue());
    }

    private DeviceStateProviderImpl create_sensorBasedProvider(Sensor sensor) {
        String configString = "<device-state-config>\n"
                + "    <device-state>\n"
                + "        <identifier>1</identifier>\n"
                + "        <name>CLOSED</name>\n"
                + "        <conditions>\n"
                + "            <sensor>\n"
                + "                <type>" + sensor.getStringType() + "</type>\n"
                + "                <name>" + sensor.getName() + "</name>\n"
                + "                <value>\n"
                + "                    <max>90</max>\n"
                + "                </value>\n"
                + "            </sensor>\n"
                + "        </conditions>\n"
                + "    </device-state>\n"
                + "    <device-state>\n"
                + "        <identifier>2</identifier>\n"
                + "        <name>HALF_OPENED</name>\n"
                + "        <conditions>\n"
                + "            <sensor>\n"
                + "                <type>" + sensor.getStringType() + "</type>\n"
                + "                <name>" + sensor.getName() + "</name>\n"
                + "                <value>\n"
                + "                    <min-inclusive>90</min-inclusive>\n"
                + "                    <max>180</max>\n"
                + "                </value>\n"
                + "            </sensor>\n"
                + "        </conditions>\n"
                + "    </device-state>\n"
                + "    <device-state>\n"
                + "        <identifier>3</identifier>\n"
                + "        <name>OPENED</name>\n"
                + "        <conditions>\n"
                + "            <sensor>\n"
                + "                <type>" + sensor.getStringType() + "</type>\n"
                + "                <name>" + sensor.getName() + "</name>\n"
                + "                <value>\n"
                + "                    <min-inclusive>180</min-inclusive>\n"
                + "                    <max>" + MAX_HINGE_ANGLE_EXCLUSIVE + "</max>\n"
                + "                </value>\n"
                + "            </sensor>\n"
                + "        </conditions>\n"
                + "    </device-state>\n"
                + "    <device-state>\n"
                + "        <identifier>4</identifier>\n"
                + "        <name>THERMAL_TEST</name>\n"
                + "        <flags>\n"
                + "            <flag>FLAG_EMULATED_ONLY</flag>\n"
                + "            <flag>FLAG_DISABLE_WHEN_THERMAL_STATUS_CRITICAL</flag>\n"
                + "        </flags>\n"
                + "    </device-state>\n"
                + "</device-state-config>\n";
        DeviceStateProviderImpl.ReadableConfig config = new TestReadableConfig(configString);
        return DeviceStateProviderImpl.createFromConfig(mContext,
                config);
    }

    @Test
    public void create_sensor() throws Exception {
        Sensor sensor = newSensor("sensor", Sensor.STRING_TYPE_HINGE_ANGLE);
        when(mSensorManager.getSensorList(anyInt())).thenReturn(List.of(sensor));
        DeviceStateProviderImpl provider = create_sensorBasedProvider(sensor);

        DeviceStateProvider.Listener listener = mock(DeviceStateProvider.Listener.class);
        provider.setListener(listener);

        verify(listener).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        assertArrayEquals(
                new DeviceState[]{
                        new DeviceState(1, "CLOSED", 0 /* flags */),
                        new DeviceState(2, "HALF_OPENED", 0 /* flags */),
                        new DeviceState(3, "OPENED", 0 /* flags */),
                        new DeviceState(4, "THERMAL_TEST",
                                DeviceState.FLAG_EMULATED_ONLY
                                        | DeviceState.FLAG_DISABLE_WHEN_THERMAL_STATUS_CRITICAL) },
                mDeviceStateArrayCaptor.getValue());
        // onStateChanged() should not be called because the provider has not yet been notified of
        // the initial sensor state.
        verify(listener, never()).onStateChanged(mIntegerCaptor.capture());

        Mockito.clearInvocations(listener);

        SensorEvent event0 = mock(SensorEvent.class);
        event0.sensor = sensor;
        FieldSetter.setField(event0, event0.getClass().getField("values"), new float[]{180});

        provider.onSensorChanged(event0);

        verify(listener, never()).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(3, mIntegerCaptor.getValue().intValue());

        Mockito.clearInvocations(listener);

        SensorEvent event1 = mock(SensorEvent.class);
        event1.sensor = sensor;
        FieldSetter.setField(event1, event1.getClass().getField("values"), new float[]{90});

        provider.onSensorChanged(event1);

        verify(listener, never()).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(2, mIntegerCaptor.getValue().intValue());

        Mockito.clearInvocations(listener);

        SensorEvent event2 = mock(SensorEvent.class);
        event2.sensor = sensor;
        FieldSetter.setField(event2, event2.getClass().getField("values"), new float[]{0});

        provider.onSensorChanged(event2);

        verify(listener, never()).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(1, mIntegerCaptor.getValue().intValue());
    }

    @Test
    public void test_flagDisableWhenThermalStatusCritical() throws Exception {
        Sensor sensor = newSensor("sensor", Sensor.STRING_TYPE_HINGE_ANGLE);
        when(mSensorManager.getSensorList(anyInt())).thenReturn(List.of(sensor));
        DeviceStateProviderImpl provider = create_sensorBasedProvider(sensor);

        provider.onThermalStatusChanged(PowerManager.THERMAL_STATUS_LIGHT);
        DeviceStateProvider.Listener listener = mock(DeviceStateProvider.Listener.class);
        provider.setListener(listener);

        verify(listener).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        assertArrayEquals(
                new DeviceState[]{
                        new DeviceState(1, "CLOSED", 0 /* flags */),
                        new DeviceState(2, "HALF_OPENED", 0 /* flags */),
                        new DeviceState(3, "OPENED", 0 /* flags */),
                        new DeviceState(4, "THERMAL_TEST",
                                DeviceState.FLAG_EMULATED_ONLY
                                        | DeviceState.FLAG_DISABLE_WHEN_THERMAL_STATUS_CRITICAL) },
                mDeviceStateArrayCaptor.getValue());
        Mockito.clearInvocations(listener);

        provider.onThermalStatusChanged(PowerManager.THERMAL_STATUS_MODERATE);
        verify(listener, never()).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        Mockito.clearInvocations(listener);

        // The THERMAL_TEST state should be disabled.
        provider.onThermalStatusChanged(PowerManager.THERMAL_STATUS_CRITICAL);
        verify(listener).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        assertArrayEquals(
                new DeviceState[]{
                        new DeviceState(1, "CLOSED", 0 /* flags */),
                        new DeviceState(2, "HALF_OPENED", 0 /* flags */),
                        new DeviceState(3, "OPENED", 0 /* flags */) },
                mDeviceStateArrayCaptor.getValue());
        Mockito.clearInvocations(listener);

        // The THERMAL_TEST state should be re-enabled.
        provider.onThermalStatusChanged(PowerManager.THERMAL_STATUS_LIGHT);
        verify(listener).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        assertArrayEquals(
                new DeviceState[]{
                        new DeviceState(1, "CLOSED", 0 /* flags */),
                        new DeviceState(2, "HALF_OPENED", 0 /* flags */),
                        new DeviceState(3, "OPENED", 0 /* flags */),
                        new DeviceState(4, "THERMAL_TEST",
                                DeviceState.FLAG_EMULATED_ONLY
                                        | DeviceState.FLAG_DISABLE_WHEN_THERMAL_STATUS_CRITICAL) },
                mDeviceStateArrayCaptor.getValue());
    }

    @Test
    public void test_invalidSensorValues() throws Exception {
        // onStateChanged() should not be triggered by invalid sensor values.

        Sensor sensor = newSensor("sensor", Sensor.STRING_TYPE_HINGE_ANGLE);
        when(mSensorManager.getSensorList(anyInt())).thenReturn(List.of(sensor));
        DeviceStateProviderImpl provider = create_sensorBasedProvider(sensor);

        DeviceStateProvider.Listener listener = mock(DeviceStateProvider.Listener.class);
        provider.setListener(listener);
        Mockito.clearInvocations(listener);

        // First, switch to a non-default state.
        SensorEvent event1 = mock(SensorEvent.class);
        event1.sensor = sensor;
        FieldSetter.setField(event1, event1.getClass().getField("values"), new float[]{90});
        provider.onSensorChanged(event1);
        verify(listener).onStateChanged(mIntegerCaptor.capture());
        assertEquals(2, mIntegerCaptor.getValue().intValue());

        Mockito.clearInvocations(listener);

        // Then, send an invalid sensor event, verify that onStateChanged() is not triggered.
        SensorEvent event2 = mock(SensorEvent.class);
        event2.sensor = sensor;
        FieldSetter.setField(event2, event2.getClass().getField("values"),
                new float[]{MAX_HINGE_ANGLE_EXCLUSIVE});

        provider.onSensorChanged(event2);

        verify(listener, never()).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        verify(listener, never()).onStateChanged(mIntegerCaptor.capture());
    }

    @Test
    public void create_invalidSensor() throws Exception {
        Sensor sensor = newSensor("sensor", Sensor.STRING_TYPE_HINGE_ANGLE);
        when(mSensorManager.getSensorList(anyInt())).thenReturn(List.of());

        String configString = "<device-state-config>\n"
                + "    <device-state>\n"
                + "        <identifier>1</identifier>\n"
                + "        <name>CLOSED</name>\n"
                + "        <conditions>\n"
                + "            <sensor>\n"
                + "                <type>" + sensor.getStringType() + "</type>\n"
                + "                <name>" + sensor.getName() + "</name>\n"
                + "                <value>\n"
                + "                    <max>90</max>\n"
                + "                </value>\n"
                + "            </sensor>\n"
                + "        </conditions>\n"
                + "    </device-state>\n"
                + "    <device-state>\n"
                + "        <identifier>2</identifier>\n"
                + "        <name>HALF_OPENED</name>\n"
                + "        <conditions>\n"
                + "            <sensor>\n"
                + "                <type>" + sensor.getStringType() + "</type>\n"
                + "                <name>" + sensor.getName() + "</name>\n"
                + "                <value>\n"
                + "                    <min-inclusive>90</min-inclusive>\n"
                + "                    <max>180</max>\n"
                + "                </value>\n"
                + "            </sensor>\n"
                + "        </conditions>\n"
                + "    </device-state>\n"
                + "</device-state-config>\n";
        DeviceStateProviderImpl.ReadableConfig config = new TestReadableConfig(configString);
        DeviceStateProviderImpl provider = DeviceStateProviderImpl.createFromConfig(mContext,
                config);

        DeviceStateProvider.Listener listener = mock(DeviceStateProvider.Listener.class);
        provider.setListener(listener);

        verify(listener).onSupportedDeviceStatesChanged(mDeviceStateArrayCaptor.capture());
        assertArrayEquals(
                new DeviceState[]{
                        new DeviceState(1, "CLOSED", 0 /* flags */),
                        new DeviceState(2, "HALF_OPENED", 0 /* flags */)
                }, mDeviceStateArrayCaptor.getValue());
        // onStateChanged() should not be called because the provider could not find the sensor.
        verify(listener, never()).onStateChanged(mIntegerCaptor.capture());
    }

    private static Sensor newSensor(String name, String type) throws Exception {
        Constructor<Sensor> constructor = Sensor.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        Sensor sensor = constructor.newInstance();
        FieldSetter.setField(sensor, Sensor.class.getDeclaredField("mName"), name);
        FieldSetter.setField(sensor, Sensor.class.getDeclaredField("mStringType"), type);
        return sensor;
    }

    private static final class TestReadableConfig implements
            DeviceStateProviderImpl.ReadableConfig {
        private final byte[] mData;

        TestReadableConfig(String configFileData) {
            mData = configFileData.getBytes();
        }

        @NonNull
        @Override
        public InputStream openRead() throws IOException {
            return new ByteArrayInputStream(mData);
        }
    }
}
