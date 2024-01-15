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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.STATE_OFF;
import static android.view.Display.STATE_ON;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Instrumentation;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputSensorInfo;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.view.Display;
import android.view.Surface;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.devicestate.DeviceStateProvider;
import com.android.server.devicestate.DeviceStateProvider.Listener;
import com.android.server.policy.feature.flags.FakeFeatureFlagsImpl;
import com.android.server.policy.feature.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.util.reflection.FieldSetter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link BookStyleDeviceStatePolicy.Provider}.
 * <p/>
 * Run with <code>atest BookStyleDeviceStatePolicyTest</code>.
 */
@RunWith(AndroidTestingRunner.class)
public final class BookStyleDeviceStatePolicyTest {

    private static final int DEVICE_STATE_CLOSED = 0;
    private static final int DEVICE_STATE_HALF_OPENED = 1;
    private static final int DEVICE_STATE_OPENED = 2;

    @Captor
    private ArgumentCaptor<Integer> mDeviceStateCaptor;
    @Captor
    private ArgumentCaptor<DisplayManager.DisplayListener> mDisplayListenerCaptor;
    @Mock
    private SensorManager mSensorManager;
    @Mock
    private InputSensorInfo mInputSensorInfo;
    @Mock
    private Listener mListener;
    @Mock
    DisplayManager mDisplayManager;
    @Mock
    private Display mDisplay;

    private final FakeFeatureFlagsImpl mFakeFeatureFlags = new FakeFeatureFlagsImpl();

    private final Configuration mConfiguration = new Configuration();

    private final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();

    @Rule
    public final TestableContext mContext = new TestableContext(
            mInstrumentation.getTargetContext());

    private Sensor mHallSensor;
    private Sensor mOrientationSensor;
    private Sensor mHingeAngleSensor;
    private Sensor mLeftAccelerometer;
    private Sensor mRightAccelerometer;

    private Map<Sensor, List<SensorEventListener>> mSensorEventListeners = new HashMap<>();
    private DeviceStateProvider mProvider;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mFakeFeatureFlags.setFlag(Flags.FLAG_ENABLE_FOLDABLES_POSTURE_BASED_CLOSED_STATE, true);
        mFakeFeatureFlags.setFlag(Flags.FLAG_ENABLE_DUAL_DISPLAY_BLOCKING, true);

        when(mInputSensorInfo.getName()).thenReturn("hall-effect");
        mHallSensor = new Sensor(mInputSensorInfo);
        when(mInputSensorInfo.getName()).thenReturn("hinge-angle");
        mHingeAngleSensor = new Sensor(mInputSensorInfo);
        when(mInputSensorInfo.getName()).thenReturn("left-accelerometer");
        mLeftAccelerometer = new Sensor(mInputSensorInfo);
        when(mInputSensorInfo.getName()).thenReturn("right-accelerometer");
        mRightAccelerometer = new Sensor(mInputSensorInfo);
        when(mInputSensorInfo.getName()).thenReturn("orientation");
        mOrientationSensor = new Sensor(mInputSensorInfo);

        mContext.addMockSystemService(SensorManager.class, mSensorManager);

        when(mSensorManager.getDefaultSensor(eq(Sensor.TYPE_HINGE_ANGLE), eq(true)))
                .thenReturn(mHingeAngleSensor);
        when(mSensorManager.getDefaultSensor(eq(Sensor.TYPE_DEVICE_ORIENTATION)))
                .thenReturn(mOrientationSensor);

        when(mDisplayManager.getDisplay(eq(DEFAULT_DISPLAY))).thenReturn(mDisplay);
        mContext.addMockSystemService(DisplayManager.class, mDisplayManager);

        mContext.ensureTestableResources();
        when(mContext.getResources().getConfiguration()).thenReturn(mConfiguration);

        final List<Sensor> sensors = new ArrayList<>();
        sensors.add(mHallSensor);
        sensors.add(mHingeAngleSensor);
        sensors.add(mOrientationSensor);
        sensors.add(mLeftAccelerometer);
        sensors.add(mRightAccelerometer);

        when(mSensorManager.registerListener(any(), any(), anyInt(), any())).thenAnswer(
                invocation -> {
                    final SensorEventListener listener = invocation.getArgument(0);
                    final Sensor sensor = invocation.getArgument(1);
                    addSensorListener(sensor, listener);
                    return true;
                });
        when(mSensorManager.registerListener(any(), any(), anyInt())).thenAnswer(
                invocation -> {
                    final SensorEventListener listener = invocation.getArgument(0);
                    final Sensor sensor = invocation.getArgument(1);
                    addSensorListener(sensor, listener);
                    return true;
                });

        doAnswer(invocation -> {
            final SensorEventListener listener = invocation.getArgument(0);
            final boolean[] removed = {false};
            mSensorEventListeners.forEach((sensor, sensorEventListeners) ->
                    removed[0] |= sensorEventListeners.remove(listener));

            if (!removed[0]) {
                throw new IllegalArgumentException(
                        "Trying to unregister listener " + listener + " that was not registered");
            }

            return null;
        }).when(mSensorManager).unregisterListener(any(SensorEventListener.class));

        doAnswer(invocation -> {
            final SensorEventListener listener = invocation.getArgument(0);
            final Sensor sensor = invocation.getArgument(1);

            boolean removed = mSensorEventListeners.get(sensor).remove(listener);
            if (!removed) {
                throw new IllegalArgumentException(
                        "Trying to unregister listener " + listener
                                + " that was not registered for sensor " + sensor);
            }

            return null;
        }).when(mSensorManager).unregisterListener(any(SensorEventListener.class),
                any(Sensor.class));

        try {
            FieldSetter.setField(mHallSensor, mHallSensor.getClass()
                    .getDeclaredField("mStringType"), "com.google.sensor.hall_effect");
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }

        when(mSensorManager.getSensorList(eq(Sensor.TYPE_ALL)))
                .thenReturn(sensors);

        mInstrumentation.runOnMainSync(() -> mProvider = createProvider());

        verify(mDisplayManager, atLeastOnce()).registerDisplayListener(
                mDisplayListenerCaptor.capture(), nullable(Handler.class));
        setScreenOn(true);
    }

    @Test
    public void test_noSensorEventsYet_reportOpenedState() {
        mProvider.setListener(mListener);
        verify(mListener).onStateChanged(mDeviceStateCaptor.capture());
        assertEquals(DEVICE_STATE_OPENED, mDeviceStateCaptor.getValue().intValue());
    }

    @Test
    public void test_deviceClosedSensorEventsBecameAvailable_reportsClosedState() {
        mProvider.setListener(mListener);
        clearInvocations(mListener);

        sendHingeAngle(0f);

        verify(mListener).onStateChanged(mDeviceStateCaptor.capture());
        assertEquals(DEVICE_STATE_CLOSED, mDeviceStateCaptor.getValue().intValue());
    }

    @Test
    public void test_hingeAngleClosed_reportsClosedState() {
        sendHingeAngle(0f);

        mProvider.setListener(mListener);
        verify(mListener).onStateChanged(mDeviceStateCaptor.capture());
        assertEquals(DEVICE_STATE_CLOSED, mDeviceStateCaptor.getValue().intValue());
    }

    @Test
    public void test_hingeAngleFullyOpened_reportsOpenedState() {
        sendHingeAngle(180f);

        mProvider.setListener(mListener);
        verify(mListener).onStateChanged(mDeviceStateCaptor.capture());
        assertEquals(DEVICE_STATE_OPENED, mDeviceStateCaptor.getValue().intValue());
    }

    @Test
    public void test_unfoldingFromClosedToFullyOpened_reportsOpenedEvent() {
        sendHingeAngle(0f);
        mProvider.setListener(mListener);
        clearInvocations(mListener);

        sendHingeAngle(180f);

        verify(mListener).onStateChanged(mDeviceStateCaptor.capture());
        assertEquals(DEVICE_STATE_OPENED, mDeviceStateCaptor.getValue().intValue());
    }

    @Test
    public void test_foldingFromFullyOpenToFullyClosed_movesToClosedState() {
        sendHingeAngle(180f);

        sendHingeAngle(0f);

        mProvider.setListener(mListener);
        verify(mListener).onStateChanged(mDeviceStateCaptor.capture());
        assertEquals(DEVICE_STATE_CLOSED, mDeviceStateCaptor.getValue().intValue());
    }

    @Test
    public void test_slowUnfolding_reportsEventsInOrder() {
        sendHingeAngle(0f);
        mProvider.setListener(mListener);

        sendHingeAngle(5f);
        sendHingeAngle(10f);
        sendHingeAngle(60f);
        sendHingeAngle(100f);
        sendHingeAngle(180f);

        verify(mListener, atLeastOnce()).onStateChanged(mDeviceStateCaptor.capture());
        assertThat(mDeviceStateCaptor.getAllValues()).containsExactly(
                DEVICE_STATE_CLOSED,
                DEVICE_STATE_HALF_OPENED,
                DEVICE_STATE_OPENED
        );
    }

    @Test
    public void test_slowFolding_reportsEventsInOrder() {
        sendHingeAngle(180f);
        mProvider.setListener(mListener);

        sendHingeAngle(180f);
        sendHingeAngle(100f);
        sendHingeAngle(60f);
        sendHingeAngle(10f);
        sendHingeAngle(5f);

        verify(mListener, atLeastOnce()).onStateChanged(mDeviceStateCaptor.capture());
        assertThat(mDeviceStateCaptor.getAllValues()).containsExactly(
                DEVICE_STATE_OPENED,
                DEVICE_STATE_HALF_OPENED,
                DEVICE_STATE_CLOSED
        );
    }

    @Test
    public void test_hingeAngleOpen_screenOff_reportsHalfFolded() {
        sendHingeAngle(0f);
        setScreenOn(false);
        mProvider.setListener(mListener);

        sendHingeAngle(10f);

        verify(mListener, atLeastOnce()).onStateChanged(mDeviceStateCaptor.capture());
        assertThat(mDeviceStateCaptor.getAllValues()).containsExactly(
                DEVICE_STATE_CLOSED,
                DEVICE_STATE_HALF_OPENED
        );
    }

    @Test
    public void test_slowUnfoldingWithScreenOff_reportsEventsInOrder() {
        sendHingeAngle(0f);
        setScreenOn(false);
        mProvider.setListener(mListener);

        sendHingeAngle(5f);
        assertLatestReportedState(DEVICE_STATE_HALF_OPENED);
        sendHingeAngle(10f);
        assertLatestReportedState(DEVICE_STATE_HALF_OPENED);
        sendHingeAngle(60f);
        assertLatestReportedState(DEVICE_STATE_HALF_OPENED);
        sendHingeAngle(100f);
        sendHingeAngle(180f);
        assertLatestReportedState(DEVICE_STATE_OPENED);

        verify(mListener, atLeastOnce()).onStateChanged(mDeviceStateCaptor.capture());
        assertThat(mDeviceStateCaptor.getAllValues()).containsExactly(
                DEVICE_STATE_CLOSED,
                DEVICE_STATE_HALF_OPENED,
                DEVICE_STATE_OPENED
        );
    }

    @Test
    public void test_unfoldWithScreenOff_reportsHalfOpened() {
        sendHingeAngle(0f);
        setScreenOn(false);
        mProvider.setListener(mListener);

        sendHingeAngle(5f);
        sendHingeAngle(10f);

        verify(mListener, atLeastOnce()).onStateChanged(mDeviceStateCaptor.capture());
        assertThat(mDeviceStateCaptor.getAllValues()).containsExactly(
                DEVICE_STATE_CLOSED,
                DEVICE_STATE_HALF_OPENED
        );
    }

    @Test
    public void test_slowUnfoldingAndFolding_reportsEventsInOrder() {
        sendHingeAngle(0f);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_CLOSED);

        // Started unfolding
        sendHingeAngle(5f);
        sendHingeAngle(30f);
        assertLatestReportedState(DEVICE_STATE_HALF_OPENED);
        sendHingeAngle(60f);
        assertLatestReportedState(DEVICE_STATE_HALF_OPENED);
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
        assertLatestReportedState(DEVICE_STATE_CLOSED);
        sendHingeAngle(5f);
        assertLatestReportedState(DEVICE_STATE_CLOSED);

        verify(mListener, atLeastOnce()).onStateChanged(mDeviceStateCaptor.capture());
        assertThat(mDeviceStateCaptor.getAllValues()).containsExactly(
                DEVICE_STATE_CLOSED,
                DEVICE_STATE_HALF_OPENED,
                DEVICE_STATE_OPENED,
                DEVICE_STATE_HALF_OPENED,
                DEVICE_STATE_CLOSED
        );
    }

    @Test
    public void test_unfoldTo30Degrees_screenOnRightSideMostlyFlat_keepsClosedState() {
        sendHingeAngle(0f);
        sendRightSideFlatSensorEvent(true);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_CLOSED);
        clearInvocations(mListener);

        sendHingeAngle(30f);

        verify(mListener, never()).onStateChanged(mDeviceStateCaptor.capture());
    }

    @Test
    public void test_unfoldTo30Degrees_seascapeDeviceOrientation_keepsClosedState() {
        sendHingeAngle(0f);
        sendRightSideFlatSensorEvent(false);
        sendDeviceOrientation(Surface.ROTATION_270);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_CLOSED);
        clearInvocations(mListener);

        sendHingeAngle(30f);

        verify(mListener, never()).onStateChanged(mDeviceStateCaptor.capture());
    }

    @Test
    public void test_unfoldTo30Degrees_landscapeScreenRotation_keepsClosedState() {
        sendHingeAngle(0f);
        sendRightSideFlatSensorEvent(false);
        sendScreenRotation(Surface.ROTATION_90);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_CLOSED);
        clearInvocations(mListener);

        sendHingeAngle(30f);

        verify(mListener, never()).onStateChanged(mDeviceStateCaptor.capture());
    }

    @Test
    public void test_unfoldTo30Degrees_seascapeScreenRotation_keepsClosedState() {
        sendHingeAngle(0f);
        sendRightSideFlatSensorEvent(false);
        sendScreenRotation(Surface.ROTATION_270);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_CLOSED);
        clearInvocations(mListener);

        sendHingeAngle(30f);

        verify(mListener, never()).onStateChanged(mDeviceStateCaptor.capture());
    }

    @Test
    public void test_unfoldTo30Degrees_screenOnRightSideNotFlat_switchesToHalfOpenState() {
        sendHingeAngle(0f);
        sendRightSideFlatSensorEvent(false);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_CLOSED);
        clearInvocations(mListener);

        sendHingeAngle(30f);

        verify(mListener).onStateChanged(DEVICE_STATE_HALF_OPENED);
    }

    @Test
    public void test_unfoldTo30Degrees_screenOffRightSideFlat_switchesToHalfOpenState() {
        sendHingeAngle(0f);
        setScreenOn(false);
        // This sensor event should be ignored as screen is off
        sendRightSideFlatSensorEvent(true);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_CLOSED);
        clearInvocations(mListener);

        sendHingeAngle(30f);

        verify(mListener).onStateChanged(DEVICE_STATE_HALF_OPENED);
    }

    @Test
    public void test_unfoldTo60Degrees_andFoldTo10_switchesToClosedState() {
        sendHingeAngle(0f);
        sendRightSideFlatSensorEvent(false);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_CLOSED);
        sendHingeAngle(60f);
        assertLatestReportedState(DEVICE_STATE_HALF_OPENED);
        clearInvocations(mListener);

        sendHingeAngle(10f);

        verify(mListener).onStateChanged(DEVICE_STATE_CLOSED);
    }

    @Test
    public void test_foldTo10AndUnfoldTo85Degrees_keepsClosedState() {
        sendHingeAngle(0f);
        sendRightSideFlatSensorEvent(false);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_CLOSED);
        sendHingeAngle(180f);
        assertLatestReportedState(DEVICE_STATE_OPENED);
        sendHingeAngle(10f);
        assertLatestReportedState(DEVICE_STATE_CLOSED);

        sendHingeAngle(85f);

        // Keeps 'tent'/'wedge' mode even when right side is not flat
        // as user manually folded the device not all the way
        assertLatestReportedState(DEVICE_STATE_CLOSED);
    }

    @Test
    public void test_foldTo0AndUnfoldTo85Degrees_doesNotKeepClosedState() {
        sendHingeAngle(0f);
        sendRightSideFlatSensorEvent(false);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_CLOSED);
        sendHingeAngle(180f);
        assertLatestReportedState(DEVICE_STATE_OPENED);
        sendHingeAngle(0f);
        assertLatestReportedState(DEVICE_STATE_CLOSED);

        sendHingeAngle(85f);

        // Do not enter 'tent'/'wedge' mode when right side is not flat
        // as user fully folded the device before that
        assertLatestReportedState(DEVICE_STATE_HALF_OPENED);
    }

    @Test
    public void test_foldTo10_leftSideIsFlat_keepsInnerScreenForReverseWedge() {
        sendHingeAngle(180f);
        sendLeftSideFlatSensorEvent(true);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_OPENED);

        sendHingeAngle(10f);

        // Keep the inner screen for reverse wedge mode (e.g. for astrophotography use case)
        assertLatestReportedState(DEVICE_STATE_HALF_OPENED);
    }

    @Test
    public void test_foldTo10_leftSideIsNotFlat_switchesToOuterScreen() {
        sendHingeAngle(180f);
        sendLeftSideFlatSensorEvent(false);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_OPENED);

        sendHingeAngle(10f);

        // Do not keep the inner screen as it is not reverse wedge mode
        assertLatestReportedState(DEVICE_STATE_CLOSED);
    }

    @Test
    public void test_foldTo10_noAccelerometerEvents_switchesToOuterScreen() {
        sendHingeAngle(180f);
        mProvider.setListener(mListener);
        assertLatestReportedState(DEVICE_STATE_OPENED);

        sendHingeAngle(10f);

        // Do not keep the inner screen as it is not reverse wedge mode
        assertLatestReportedState(DEVICE_STATE_CLOSED);
    }

    @Test
    public void test_deviceClosed_screenIsOff_noSensorListeners() {
        mProvider.setListener(mListener);

        sendHingeAngle(0f);
        setScreenOn(false);

        assertNoListenersForSensor(mLeftAccelerometer);
        assertNoListenersForSensor(mRightAccelerometer);
        assertNoListenersForSensor(mOrientationSensor);
    }

    @Test
    public void test_deviceClosed_screenIsOn_doesNotListenForOneAccelerometer() {
        mProvider.setListener(mListener);

        sendHingeAngle(0f);
        setScreenOn(true);

        assertNoListenersForSensor(mLeftAccelerometer);
        assertListensForSensor(mRightAccelerometer);
        assertListensForSensor(mOrientationSensor);
    }

    @Test
    public void test_deviceOpened_screenIsOn_listensToSensors() {
        mProvider.setListener(mListener);

        sendHingeAngle(180f);
        setScreenOn(true);

        assertListensForSensor(mLeftAccelerometer);
        assertListensForSensor(mRightAccelerometer);
        assertListensForSensor(mOrientationSensor);
    }

    private void assertLatestReportedState(int state) {
        final ArgumentCaptor<Integer> integerCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mListener, atLeastOnce()).onStateChanged(integerCaptor.capture());
        assertEquals(state, integerCaptor.getValue().intValue());
    }

    private void sendHingeAngle(float angle) {
        sendSensorEvent(mHingeAngleSensor, new float[]{angle});
    }

    private void sendDeviceOrientation(int orientation) {
        sendSensorEvent(mOrientationSensor, new float[]{orientation});
    }

    private void sendScreenRotation(int rotation) {
        when(mDisplay.getRotation()).thenReturn(rotation);
        mDisplayListenerCaptor.getAllValues().forEach((l) -> l.onDisplayChanged(DEFAULT_DISPLAY));
    }

    private void sendRightSideFlatSensorEvent(boolean flat) {
        sendAccelerometerFlatEvents(mRightAccelerometer, flat);
    }

    private void sendLeftSideFlatSensorEvent(boolean flat) {
        sendAccelerometerFlatEvents(mLeftAccelerometer, flat);
    }

    private static final int ACCELEROMETER_EVENTS = 10;

    private void sendAccelerometerFlatEvents(Sensor sensor, boolean flat) {
        final float[] values = flat ? new float[]{0.00021f, -0.00013f, 9.7899f} :
                new float[]{6.124f, 4.411f, -1.7899f};
        // Send the same values multiple times to bypass noise filter
        for (int i = 0; i < ACCELEROMETER_EVENTS; i++) {
            sendSensorEvent(sensor, values);
        }
    }

    private void setScreenOn(boolean isOn) {
        int state = isOn ? STATE_ON : STATE_OFF;
        when(mDisplay.getState()).thenReturn(state);
        mDisplayListenerCaptor.getAllValues().forEach((l) -> l.onDisplayChanged(DEFAULT_DISPLAY));
    }

    private void sendSensorEvent(Sensor sensor, float[] values) {
        SensorEvent event = mock(SensorEvent.class);
        event.sensor = sensor;
        try {
            FieldSetter.setField(event, event.getClass().getField("values"),
                    values);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }

        List<SensorEventListener> listeners = mSensorEventListeners.get(sensor);
        if (listeners != null) {
            listeners.forEach(sensorEventListener -> sensorEventListener.onSensorChanged(event));
        }
    }

    private void assertNoListenersForSensor(Sensor sensor) {
        final List<SensorEventListener> listeners = mSensorEventListeners.getOrDefault(sensor,
                new ArrayList<>());
        assertWithMessage("Expected no listeners for sensor " + sensor + " but found some").that(
                listeners).isEmpty();
    }

    private void assertListensForSensor(Sensor sensor) {
        final List<SensorEventListener> listeners = mSensorEventListeners.getOrDefault(sensor,
                new ArrayList<>());
        assertWithMessage(
                "Expected at least one listener for sensor " + sensor).that(
                listeners).isNotEmpty();
    }

    private void addSensorListener(Sensor sensor, SensorEventListener listener) {
        List<SensorEventListener> listeners = mSensorEventListeners.computeIfAbsent(
                sensor, k -> new ArrayList<>());
        listeners.add(listener);
    }

    private DeviceStateProvider createProvider() {
        return new BookStyleDeviceStatePolicy(mFakeFeatureFlags, mContext, mHingeAngleSensor,
                mHallSensor, mLeftAccelerometer, mRightAccelerometer,
                /* closeAngleDegrees= */ null).getDeviceStateProvider();
    }
}
