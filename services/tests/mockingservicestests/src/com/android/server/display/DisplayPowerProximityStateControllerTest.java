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

package com.android.server.display;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.test.TestLooper;
import android.view.Display;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.testutils.OffsettableClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DisplayPowerProximityStateControllerTest {
    @Mock
    WakelockController mWakelockController;

    @Mock
    DisplayDeviceConfig mDisplayDeviceConfig;

    @Mock
    Runnable mNudgeUpdatePowerState;

    @Mock
    SensorManager mSensorManager;

    private Sensor mProximitySensor;
    private OffsettableClock mClock;
    private TestLooper mTestLooper;
    private SensorEventListener mSensorEventListener;
    private DisplayPowerProximityStateController mDisplayPowerProximityStateController;

    @Before
    public void before() throws Exception {
        MockitoAnnotations.initMocks(this);
        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);
        when(mDisplayDeviceConfig.getProximitySensor()).thenReturn(
                new DisplayDeviceConfig.SensorData() {
                    {
                        type = Sensor.STRING_TYPE_PROXIMITY;
                        // This is kept null because currently there is no way to define a sensor
                        // name in TestUtils
                        name = null;
                    }
                });
        setUpProxSensor();
        DisplayPowerProximityStateController.Injector injector =
                new DisplayPowerProximityStateController.Injector() {
                    @Override
                    DisplayPowerProximityStateController.Clock createClock() {
                        return mClock::now;
                    }
                };
        mDisplayPowerProximityStateController = new DisplayPowerProximityStateController(
                mWakelockController, mDisplayDeviceConfig, mTestLooper.getLooper(),
                mNudgeUpdatePowerState, 0,
                mSensorManager, injector);
        mSensorEventListener = mDisplayPowerProximityStateController.getProximitySensorListener();
    }

    @Test
    public void updatePendingProximityRequestsWorksAsExpectedWhenPending() {
        // Set the system to pending wait for proximity
        assertTrue(mDisplayPowerProximityStateController.setPendingWaitForNegativeProximityLocked(
                true));
        assertTrue(
                mDisplayPowerProximityStateController.getPendingWaitForNegativeProximityLocked());

        // Update the pending proximity wait request
        mDisplayPowerProximityStateController.updatePendingProximityRequestsLocked();
        assertTrue(mDisplayPowerProximityStateController.getWaitingForNegativeProximity());
        assertFalse(
                mDisplayPowerProximityStateController.getPendingWaitForNegativeProximityLocked());
    }

    @Test
    public void updatePendingProximityRequestsWorksAsExpectedWhenNotPending() {
        // Will not wait or be in the pending wait state of not already pending
        mDisplayPowerProximityStateController.updatePendingProximityRequestsLocked();
        assertFalse(mDisplayPowerProximityStateController.getWaitingForNegativeProximity());
        assertFalse(
                mDisplayPowerProximityStateController.getPendingWaitForNegativeProximityLocked());
    }

    @Test
    public void updatePendingProximityRequestsWorksAsExpectedWhenPendingAndProximityIgnored()
            throws Exception {
        // Set the system to the state where it will ignore proximity unless changed
        enableProximitySensor();
        emitAndValidatePositiveProximityEvent();
        mDisplayPowerProximityStateController.ignoreProximitySensorUntilChangedInternal();
        advanceTime(1);
        assertTrue(mDisplayPowerProximityStateController.shouldIgnoreProximityUntilChanged());
        verify(mNudgeUpdatePowerState, times(2)).run();

        // Do not set the system to pending wait for proximity
        mDisplayPowerProximityStateController.updatePendingProximityRequestsLocked();
        assertFalse(mDisplayPowerProximityStateController.getWaitingForNegativeProximity());
        assertFalse(
                mDisplayPowerProximityStateController.getPendingWaitForNegativeProximityLocked());

        // Set the system to pending wait for proximity. But because the proximity is being
        // ignored, it will not wait or not set the pending wait
        assertTrue(mDisplayPowerProximityStateController.setPendingWaitForNegativeProximityLocked(
                true));
        mDisplayPowerProximityStateController.updatePendingProximityRequestsLocked();
        assertFalse(mDisplayPowerProximityStateController.getWaitingForNegativeProximity());
        assertFalse(
                mDisplayPowerProximityStateController.getPendingWaitForNegativeProximityLocked());
    }

    @Test
    public void cleanupDisablesTheProximitySensor() {
        enableProximitySensor();
        mDisplayPowerProximityStateController.cleanup();
        verify(mSensorManager).unregisterListener(
                mSensorEventListener);
        assertFalse(mDisplayPowerProximityStateController.isProximitySensorEnabled());
        assertFalse(mDisplayPowerProximityStateController.getWaitingForNegativeProximity());
        assertFalse(mDisplayPowerProximityStateController.shouldIgnoreProximityUntilChanged());
        assertEquals(mDisplayPowerProximityStateController.getProximity(),
                DisplayPowerProximityStateController.PROXIMITY_UNKNOWN);
        when(mWakelockController.releaseWakelock(
                WakelockController.WAKE_LOCK_PROXIMITY_DEBOUNCE)).thenReturn(true);
        assertEquals(mDisplayPowerProximityStateController.getPendingProximityDebounceTime(), -1);
    }

    @Test
    public void isProximitySensorAvailableReturnsTrueWhenAvailable() {
        assertTrue(mDisplayPowerProximityStateController.isProximitySensorAvailable());
    }

    @Test
    public void isProximitySensorAvailableReturnsFalseWhenNotAvailable() {
        when(mDisplayDeviceConfig.getProximitySensor()).thenReturn(
                new DisplayDeviceConfig.SensorData() {
                    {
                        type = null;
                        name = null;
                    }
                });
        mDisplayPowerProximityStateController = new DisplayPowerProximityStateController(
                mWakelockController, mDisplayDeviceConfig, mTestLooper.getLooper(),
                mNudgeUpdatePowerState, 1,
                mSensorManager, null);
        assertFalse(mDisplayPowerProximityStateController.isProximitySensorAvailable());
    }

    @Test
    public void notifyDisplayDeviceChangedReloadsTheProximitySensor() throws Exception {
        DisplayDeviceConfig updatedDisplayDeviceConfig = mock(DisplayDeviceConfig.class);
        when(updatedDisplayDeviceConfig.getProximitySensor()).thenReturn(
                new DisplayDeviceConfig.SensorData() {
                    {
                        type = Sensor.STRING_TYPE_PROXIMITY;
                        name = null;
                    }
                });
        Sensor newProxSensor = TestUtils.createSensor(
                Sensor.TYPE_PROXIMITY, Sensor.STRING_TYPE_PROXIMITY, 4.0f);
        when(mSensorManager.getSensorList(eq(Sensor.TYPE_ALL)))
                .thenReturn(List.of(newProxSensor));
        mDisplayPowerProximityStateController.notifyDisplayDeviceChanged(
                updatedDisplayDeviceConfig);
        assertTrue(mDisplayPowerProximityStateController.isProximitySensorAvailable());
    }

    @Test
    public void setPendingWaitForNegativeProximityLockedWorksAsExpected() {
        // Doesn't do anything not asked to wait
        assertFalse(mDisplayPowerProximityStateController.setPendingWaitForNegativeProximityLocked(
                false));
        assertFalse(
                mDisplayPowerProximityStateController.getPendingWaitForNegativeProximityLocked());

        // Sets pending wait negative proximity if not already waiting
        assertTrue(mDisplayPowerProximityStateController.setPendingWaitForNegativeProximityLocked(
                true));
        assertTrue(
                mDisplayPowerProximityStateController.getPendingWaitForNegativeProximityLocked());

        // Will not set pending wait negative proximity if already waiting
        assertFalse(mDisplayPowerProximityStateController.setPendingWaitForNegativeProximityLocked(
                true));
        assertTrue(
                mDisplayPowerProximityStateController.getPendingWaitForNegativeProximityLocked());

    }

    @Test
    public void evaluateProximityStateWhenRequestedUseOfProximitySensor() throws Exception {
        // Enable the proximity sensor
        enableProximitySensor();

        // Emit a positive proximity event to move the system to a state to mimic a scenario
        // where the system is in positive proximity
        emitAndValidatePositiveProximityEvent();

        // Again evaluate the proximity state, with system having positive proximity
        setScreenOffBecauseOfPositiveProximityState();
    }

    @Test
    public void evaluateProximityStateWhenScreenOffBecauseOfPositiveProximity() throws Exception {
        // Enable the proximity sensor
        enableProximitySensor();

        // Emit a positive proximity event to move the system to a state to mimic a scenario
        // where the system is in positive proximity
        emitAndValidatePositiveProximityEvent();

        // Again evaluate the proximity state, with system having positive proximity
        setScreenOffBecauseOfPositiveProximityState();

        // Set the system to pending wait for proximity
        mDisplayPowerProximityStateController.setPendingWaitForNegativeProximityLocked(true);
        // Update the pending proximity wait request
        mDisplayPowerProximityStateController.updatePendingProximityRequestsLocked();

        // Start ignoring proximity sensor
        mDisplayPowerProximityStateController.ignoreProximitySensorUntilChangedInternal();
        // Re-evaluate the proximity state, such that the system is detecting the positive
        // proximity, and screen is off because of that
        when(mWakelockController.getOnProximityNegativeRunnable()).thenReturn(mock(Runnable.class));
        mDisplayPowerProximityStateController.updateProximityState(mock(
                DisplayManagerInternal.DisplayPowerRequest.class), Display.STATE_ON);
        assertTrue(mDisplayPowerProximityStateController.isProximitySensorEnabled());
        assertFalse(mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity());
        assertTrue(
                mDisplayPowerProximityStateController
                        .shouldSkipRampBecauseOfProximityChangeToNegative());
        verify(mWakelockController).acquireWakelock(
                WakelockController.WAKE_LOCK_PROXIMITY_NEGATIVE);
    }

    @Test
    public void evaluateProximityStateWhenDisplayIsTurningOff() throws Exception {
        // Enable the proximity sensor
        enableProximitySensor();

        // Emit a positive proximity event to move the system to a state to mimic a scenario
        // where the system is in positive proximity
        emitAndValidatePositiveProximityEvent();

        // Again evaluate the proximity state, with system having positive proximity
        setScreenOffBecauseOfPositiveProximityState();

        // Re-evaluate the proximity state, such that the system is detecting the positive
        // proximity, and screen is off because of that
        mDisplayPowerProximityStateController.updateProximityState(mock(
                DisplayManagerInternal.DisplayPowerRequest.class), Display.STATE_OFF);
        verify(mSensorManager).unregisterListener(
                mSensorEventListener);
        assertFalse(mDisplayPowerProximityStateController.isProximitySensorEnabled());
        assertFalse(mDisplayPowerProximityStateController.getWaitingForNegativeProximity());
        assertFalse(mDisplayPowerProximityStateController.shouldIgnoreProximityUntilChanged());
        assertEquals(mDisplayPowerProximityStateController.getProximity(),
                DisplayPowerProximityStateController.PROXIMITY_UNKNOWN);
        when(mWakelockController.releaseWakelock(
                WakelockController.WAKE_LOCK_PROXIMITY_DEBOUNCE)).thenReturn(true);
        assertEquals(mDisplayPowerProximityStateController.getPendingProximityDebounceTime(), -1);
    }

    @Test
    public void evaluateProximityStateNotWaitingForNegativeProximityAndNotUsingProxSensor()
            throws Exception {
        // Enable the proximity sensor
        enableProximitySensor();

        // Emit a positive proximity event to move the system to a state to mimic a scenario
        // where the system is in positive proximity
        emitAndValidatePositiveProximityEvent();

        // Re-evaluate the proximity state, such that the system is detecting the positive
        // proximity, and screen is off because of that
        mDisplayPowerProximityStateController.updateProximityState(mock(
                DisplayManagerInternal.DisplayPowerRequest.class), Display.STATE_ON);
        verify(mSensorManager).unregisterListener(
                mSensorEventListener);
        assertFalse(mDisplayPowerProximityStateController.isProximitySensorEnabled());
        assertFalse(mDisplayPowerProximityStateController.getWaitingForNegativeProximity());
        assertFalse(mDisplayPowerProximityStateController.shouldIgnoreProximityUntilChanged());
        assertEquals(mDisplayPowerProximityStateController.getProximity(),
                DisplayPowerProximityStateController.PROXIMITY_UNKNOWN);
        when(mWakelockController.releaseWakelock(
                WakelockController.WAKE_LOCK_PROXIMITY_DEBOUNCE)).thenReturn(true);
        assertEquals(mDisplayPowerProximityStateController.getPendingProximityDebounceTime(), -1);
    }

    private void advanceTime(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
    }

    private void setUpProxSensor() throws Exception {
        mProximitySensor = TestUtils.createSensor(
                Sensor.TYPE_PROXIMITY, Sensor.STRING_TYPE_PROXIMITY, 5.0f);
        when(mSensorManager.getSensorList(eq(Sensor.TYPE_ALL)))
                .thenReturn(List.of(mProximitySensor));
    }

    private void emitAndValidatePositiveProximityEvent() throws Exception {
        // Emit a positive proximity event to move the system to a state to mimic a scenario
        // where the system is in positive proximity
        when(mWakelockController.releaseWakelock(
                WakelockController.WAKE_LOCK_PROXIMITY_DEBOUNCE)).thenReturn(true);
        mSensorEventListener.onSensorChanged(TestUtils.createSensorEvent(mProximitySensor, 4));
        verify(mSensorManager).registerListener(mSensorEventListener,
                mProximitySensor, SensorManager.SENSOR_DELAY_NORMAL,
                mDisplayPowerProximityStateController.getHandler());
        verify(mWakelockController).acquireWakelock(
                WakelockController.WAKE_LOCK_PROXIMITY_DEBOUNCE);
        assertEquals(mDisplayPowerProximityStateController.getPendingProximity(),
                DisplayPowerProximityStateController.PROXIMITY_POSITIVE);
        assertFalse(mDisplayPowerProximityStateController.shouldIgnoreProximityUntilChanged());
        assertEquals(mDisplayPowerProximityStateController.getProximity(),
                DisplayPowerProximityStateController.PROXIMITY_POSITIVE);
        verify(mNudgeUpdatePowerState).run();
        assertEquals(mDisplayPowerProximityStateController.getPendingProximityDebounceTime(), -1);
    }

    // Call evaluateProximityState with the request for using the proximity sensor. This will
    // register the proximity sensor listener, which will be needed for mocking positive
    // proximity scenarios.
    private void enableProximitySensor() {
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.useProximitySensor = true;
        mDisplayPowerProximityStateController.updateProximityState(displayPowerRequest,
                Display.STATE_ON);
        verify(mSensorManager).registerListener(
                mSensorEventListener,
                mProximitySensor, SensorManager.SENSOR_DELAY_NORMAL,
                mDisplayPowerProximityStateController.getHandler());
        assertTrue(mDisplayPowerProximityStateController.isProximitySensorEnabled());
        assertFalse(mDisplayPowerProximityStateController.shouldIgnoreProximityUntilChanged());
        assertFalse(mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity());
        verifyZeroInteractions(mWakelockController);
    }

    private void setScreenOffBecauseOfPositiveProximityState() {
        // Prepare a request to indicate that the proximity sensor is to be used
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = mock(
                DisplayManagerInternal.DisplayPowerRequest.class);
        displayPowerRequest.useProximitySensor = true;

        Runnable onProximityPositiveRunnable = mock(Runnable.class);
        when(mWakelockController.getOnProximityPositiveRunnable()).thenReturn(
                onProximityPositiveRunnable);

        mDisplayPowerProximityStateController.updateProximityState(displayPowerRequest,
                Display.STATE_ON);
        verify(mSensorManager).registerListener(
                mSensorEventListener,
                mProximitySensor, SensorManager.SENSOR_DELAY_NORMAL,
                mDisplayPowerProximityStateController.getHandler());
        assertTrue(mDisplayPowerProximityStateController.isProximitySensorEnabled());
        assertFalse(mDisplayPowerProximityStateController.shouldIgnoreProximityUntilChanged());
        assertTrue(mDisplayPowerProximityStateController.isScreenOffBecauseOfProximity());
        verify(mWakelockController).acquireWakelock(
                WakelockController.WAKE_LOCK_PROXIMITY_POSITIVE);
    }
}
