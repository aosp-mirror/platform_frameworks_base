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


import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal.DisplayPowerCallbacks;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;
import android.os.PowerManager;
import android.os.test.TestLooper;
import android.util.FloatProperty;
import android.view.Display;
import android.view.DisplayInfo;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.display.RampAnimator.DualRampAnimator;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.testutils.OffsettableClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;


@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DisplayPowerControllerTest {
    private static final String UNIQUE_DISPLAY_ID = "unique_id_test123";
    private static final int DISPLAY_ID = 42;

    private OffsettableClock mClock;
    private TestLooper mTestLooper;
    private Handler mHandler;
    private DisplayPowerController.Injector mInjector;
    private Context mContextSpy;

    @Mock
    private DisplayPowerCallbacks mDisplayPowerCallbacksMock;
    @Mock
    private SensorManager mSensorManagerMock;
    @Mock
    private DisplayBlanker mDisplayBlankerMock;
    @Mock
    private LogicalDisplay mLogicalDisplayMock;
    @Mock
    private DisplayDevice mDisplayDeviceMock;
    @Mock
    private HighBrightnessModeMetadata mHighBrightnessModeMetadataMock;
    @Mock
    private BrightnessTracker mBrightnessTrackerMock;
    @Mock
    private BrightnessSetting mBrightnessSettingMock;
    @Mock
    private WindowManagerPolicy mWindowManagerPolicyMock;
    @Mock
    private PowerManager mPowerManagerMock;
    @Mock
    private Resources mResourcesMock;
    @Mock
    private DisplayDeviceConfig mDisplayDeviceConfigMock;
    @Mock
    private DisplayPowerState mDisplayPowerStateMock;
    @Mock
    private DualRampAnimator<DisplayPowerState> mDualRampAnimatorMock;

    @Captor
    private ArgumentCaptor<SensorEventListener> mSensorEventListenerCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContextSpy = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);
        mHandler = new Handler(mTestLooper.getLooper());
        mInjector = new DisplayPowerController.Injector() {
            @Override
            DisplayPowerController.Clock getClock() {
                return mClock::now;
            }

            @Override
            DisplayPowerState getDisplayPowerState(DisplayBlanker blanker, ColorFade colorFade,
                    int displayId, int displayState) {
                return mDisplayPowerStateMock;
            }

            @Override
            DualRampAnimator<DisplayPowerState> getDualRampAnimator(DisplayPowerState dps,
                    FloatProperty<DisplayPowerState> firstProperty,
                    FloatProperty<DisplayPowerState> secondProperty) {
                return mDualRampAnimatorMock;
            }
        };

        addLocalServiceMock(WindowManagerPolicy.class, mWindowManagerPolicyMock);

        when(mContextSpy.getSystemService(eq(PowerManager.class))).thenReturn(mPowerManagerMock);
        when(mContextSpy.getResources()).thenReturn(mResourcesMock);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(WindowManagerPolicy.class);
    }

    @Test
    public void testReleaseProxSuspendBlockersOnExit() throws Exception {
        setUpDisplay(DISPLAY_ID, UNIQUE_DISPLAY_ID);

        Sensor proxSensor = setUpProxSensor();

        DisplayPowerController dpc = new DisplayPowerController(
                mContextSpy, mInjector, mDisplayPowerCallbacksMock, mHandler,
                mSensorManagerMock, mDisplayBlankerMock, mLogicalDisplayMock,
                mBrightnessTrackerMock, mBrightnessSettingMock, () -> {
        }, mHighBrightnessModeMetadataMock);

        when(mDisplayPowerStateMock.getScreenState()).thenReturn(Display.STATE_ON);
        // send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        dpc.requestPowerState(dpr, false /* waitForNegativeProximity */);

        // Run updatePowerState to start listener for the prox sensor
        advanceTime(1);

        SensorEventListener listener = getSensorEventListener(proxSensor);
        assertNotNull(listener);

        listener.onSensorChanged(TestUtils.createSensorEvent(proxSensor, 5 /* lux */));
        advanceTime(1);

        // two times, one for unfinished business and one for proximity
        verify(mDisplayPowerCallbacksMock).acquireSuspendBlocker(
                dpc.getSuspendBlockerUnfinishedBusinessId(DISPLAY_ID));
        verify(mDisplayPowerCallbacksMock).acquireSuspendBlocker(
                dpc.getSuspendBlockerProxDebounceId(DISPLAY_ID));

        dpc.stop();
        advanceTime(1);

        // two times, one for unfinished business and one for proximity
        verify(mDisplayPowerCallbacksMock).releaseSuspendBlocker(
                dpc.getSuspendBlockerUnfinishedBusinessId(DISPLAY_ID));
        verify(mDisplayPowerCallbacksMock).releaseSuspendBlocker(
                dpc.getSuspendBlockerProxDebounceId(DISPLAY_ID));
    }

    /**
     * Creates a mock and registers it to {@link LocalServices}.
     */
    private static <T> void addLocalServiceMock(Class<T> clazz, T mock) {
        LocalServices.removeServiceForTest(clazz);
        LocalServices.addService(clazz, mock);
    }

    private void advanceTime(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
    }

    private Sensor setUpProxSensor() throws Exception {
        Sensor proxSensor = TestUtils.createSensor(
                Sensor.TYPE_PROXIMITY, Sensor.STRING_TYPE_PROXIMITY);
        when(mSensorManagerMock.getSensorList(eq(Sensor.TYPE_ALL)))
                .thenReturn(List.of(proxSensor));
        return proxSensor;
    }

    private SensorEventListener getSensorEventListener(Sensor sensor) {
        verify(mSensorManagerMock).registerListener(mSensorEventListenerCaptor.capture(),
                eq(sensor), eq(SensorManager.SENSOR_DELAY_NORMAL), isA(Handler.class));
        return mSensorEventListenerCaptor.getValue();
    }

    private void setUpDisplay(int displayId, String uniqueId) {
        DisplayInfo info = new DisplayInfo();
        DisplayDeviceInfo deviceInfo = new DisplayDeviceInfo();

        when(mLogicalDisplayMock.getDisplayIdLocked()).thenReturn(displayId);
        when(mLogicalDisplayMock.getPrimaryDisplayDeviceLocked()).thenReturn(mDisplayDeviceMock);
        when(mLogicalDisplayMock.getDisplayInfoLocked()).thenReturn(info);
        when(mLogicalDisplayMock.isEnabledLocked()).thenReturn(true);
        when(mLogicalDisplayMock.isInTransitionLocked()).thenReturn(false);
        when(mDisplayDeviceMock.getDisplayDeviceInfoLocked()).thenReturn(deviceInfo);
        when(mDisplayDeviceMock.getUniqueId()).thenReturn(uniqueId);
        when(mDisplayDeviceMock.getDisplayDeviceConfig()).thenReturn(mDisplayDeviceConfigMock);
        when(mDisplayDeviceConfigMock.getProximitySensor()).thenReturn(
                new DisplayDeviceConfig.SensorData() {
                    {
                        type = Sensor.STRING_TYPE_PROXIMITY;
                        name = null;
                    }
                });
        when(mDisplayDeviceConfigMock.getNits()).thenReturn(new float[]{2, 500});
    }
}
