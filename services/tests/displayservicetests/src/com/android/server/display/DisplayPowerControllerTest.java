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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DEFAULT;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_DOZE;
import static com.android.server.display.AutomaticBrightnessController.AUTO_BRIGHTNESS_MODE_IDLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.DisplayManagerInternal.DisplayPowerCallbacks;
import android.hardware.display.DisplayManagerInternal.DisplayPowerRequest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.testing.TestableContext;
import android.util.FloatProperty;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.server.LocalServices;
import com.android.server.am.BatteryStatsService;
import com.android.server.display.RampAnimator.DualRampAnimator;
import com.android.server.display.brightness.BrightnessEvent;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.clamper.BrightnessClamperController;
import com.android.server.display.brightness.clamper.HdrClamper;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.display.config.HysteresisLevels;
import com.android.server.display.config.SensorData;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.feature.flags.Flags;
import com.android.server.display.layout.Layout;
import com.android.server.display.whitebalance.DisplayWhiteBalanceController;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.testutils.OffsettableClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.util.List;


@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DisplayPowerControllerTest {
    private static final int DISPLAY_ID = Display.DEFAULT_DISPLAY;
    private static final String UNIQUE_ID = "unique_id_test123";
    private static final int FOLLOWER_DISPLAY_ID = DISPLAY_ID + 1;
    private static final String FOLLOWER_UNIQUE_ID = "unique_id_456";
    private static final int SECOND_FOLLOWER_DISPLAY_ID = FOLLOWER_DISPLAY_ID + 1;
    private static final String SECOND_FOLLOWER_UNIQUE_DISPLAY_ID = "unique_id_789";
    private static final float PROX_SENSOR_MAX_RANGE = 5;
    private static final float DOZE_SCALE_FACTOR = 0.34f;

    private static final float BRIGHTNESS_RAMP_RATE_MINIMUM = 0.0f;
    private static final float BRIGHTNESS_RAMP_RATE_FAST_DECREASE = 0.3f;
    private static final float BRIGHTNESS_RAMP_RATE_FAST_INCREASE = 0.4f;
    private static final float BRIGHTNESS_RAMP_RATE_SLOW_DECREASE = 0.1f;
    private static final float BRIGHTNESS_RAMP_RATE_SLOW_INCREASE = 0.2f;
    private static final float BRIGHTNESS_RAMP_RATE_SLOW_INCREASE_IDLE = 0.5f;
    private static final float BRIGHTNESS_RAMP_RATE_SLOW_DECREASE_IDLE = 0.6f;

    private static final long BRIGHTNESS_RAMP_INCREASE_MAX = 1000;
    private static final long BRIGHTNESS_RAMP_DECREASE_MAX = 2000;
    private static final long BRIGHTNESS_RAMP_INCREASE_MAX_IDLE = 3000;
    private static final long BRIGHTNESS_RAMP_DECREASE_MAX_IDLE = 4000;

    private OffsettableClock mClock;
    private TestLooper mTestLooper;
    private Handler mHandler;
    private DisplayPowerControllerHolder mHolder;
    private Sensor mProxSensor;

    @Mock
    private DisplayPowerCallbacks mDisplayPowerCallbacksMock;
    @Mock
    private SensorManager mSensorManagerMock;
    @Mock
    private DisplayBlanker mDisplayBlankerMock;
    @Mock
    private BrightnessTracker mBrightnessTrackerMock;
    @Mock
    private WindowManagerPolicy mWindowManagerPolicyMock;
    @Mock
    private PowerManager mPowerManagerMock;
    @Mock
    private ColorDisplayService.ColorDisplayServiceInternal mCdsiMock;
    @Mock
    private DisplayWhiteBalanceController mDisplayWhiteBalanceControllerMock;
    @Mock
    private DisplayManagerFlags mDisplayManagerFlagsMock;
    @Mock
    private DisplayManagerInternal.DisplayOffloadSession mDisplayOffloadSession;
    @Captor
    private ArgumentCaptor<SensorEventListener> mSensorEventListenerCaptor;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule =
            new ExtendedMockitoRule.Builder(this)
                    .setStrictness(Strictness.LENIENT)
                    .spyStatic(SystemProperties.class)
                    .spyStatic(BatteryStatsService.class)
                    .spyStatic(ActivityManager.class)
                    .build();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() throws Exception {
        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);
        mHandler = new Handler(mTestLooper.getLooper());

        // Set some settings to minimize unexpected events and have a consistent starting state
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putFloatForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, 0, UserHandle.USER_CURRENT);

        addLocalServiceMock(WindowManagerPolicy.class, mWindowManagerPolicyMock);
        addLocalServiceMock(ColorDisplayService.ColorDisplayServiceInternal.class,
                mCdsiMock);

        mContext.addMockSystemService(PowerManager.class, mPowerManagerMock);

        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_displayColorFadeDisabled, false);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.fraction.config_screenAutoBrightnessDozeScaleFactor,
                DOZE_SCALE_FACTOR);

        doAnswer((Answer<Void>) invocationOnMock -> null).when(() ->
                SystemProperties.set(anyString(), any()));
        doAnswer((Answer<Void>) invocationOnMock -> null).when(BatteryStatsService::getService);
        doAnswer((Answer<Boolean>) invocationOnMock -> false)
                .when(ActivityManager::isLowRamDeviceStatic);

        setUpSensors();
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(WindowManagerPolicy.class);
        LocalServices.removeServiceForTest(ColorDisplayService.ColorDisplayServiceInternal.class);
    }

    @Test
    public void testReleaseProxSuspendBlockersOnExit() throws Exception {
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        // Send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        mHolder.dpc.requestPowerState(dpr, false /* waitForNegativeProximity */);

        // Run updatePowerState to start listener for the prox sensor
        advanceTime(1);

        SensorEventListener listener = getSensorEventListener(mProxSensor);
        assertNotNull(listener);

        listener.onSensorChanged(TestUtils.createSensorEvent(mProxSensor, /* value= */ 5));
        advanceTime(1);

        // two times, one for unfinished business and one for proximity
        verify(mHolder.wakelockController).acquireWakelock(
                WakelockController.WAKE_LOCK_UNFINISHED_BUSINESS);
        verify(mHolder.wakelockController).acquireWakelock(
                WakelockController.WAKE_LOCK_PROXIMITY_DEBOUNCE);

        mHolder.dpc.stop();
        advanceTime(1);
        verify(mHolder.wakelockController).releaseAll();
    }

    @Test
    public void testScreenOffBecauseOfProximity() throws Exception {
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        // Send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        mHolder.dpc.requestPowerState(dpr, false /* waitForNegativeProximity */);

        // Run updatePowerState to start listener for the prox sensor
        advanceTime(1);

        SensorEventListener listener = getSensorEventListener(mProxSensor);
        assertNotNull(listener);

        // Send a positive proximity event
        listener.onSensorChanged(TestUtils.createSensorEvent(mProxSensor, /* value= */ 1));
        advanceTime(1);

        // The display should have been turned off
        verify(mHolder.displayPowerState)
                .setScreenState(Display.STATE_OFF, Display.STATE_REASON_DEFAULT_POLICY);

        clearInvocations(mHolder.displayPowerState);
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_OFF);
        // Send a negative proximity event
        listener.onSensorChanged(TestUtils.createSensorEvent(mProxSensor,
                (int) PROX_SENSOR_MAX_RANGE + 1));
        // Advance time by less than PROXIMITY_SENSOR_NEGATIVE_DEBOUNCE_DELAY
        advanceTime(1);

        // The prox sensor is debounced so the display should not have been turned back on yet
        verify(mHolder.displayPowerState, never())
                .setScreenState(Display.STATE_ON, Display.STATE_REASON_DEFAULT_POLICY);

        // Advance time by more than PROXIMITY_SENSOR_NEGATIVE_DEBOUNCE_DELAY
        advanceTime(1000);

        // The display should have been turned back on
        verify(mHolder.displayPowerState)
                .setScreenState(Display.STATE_ON, Display.STATE_REASON_DEFAULT_POLICY);
    }

    @Test
    public void testScreenOffBecauseOfProximity_ProxSensorGone() throws Exception {
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        // Send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        mHolder.dpc.requestPowerState(dpr, false /* waitForNegativeProximity */);

        // Run updatePowerState to start listener for the prox sensor
        advanceTime(1);

        SensorEventListener listener = getSensorEventListener(mProxSensor);
        assertNotNull(listener);

        // Send a positive proximity event
        listener.onSensorChanged(TestUtils.createSensorEvent(mProxSensor, /* value= */ 1));
        advanceTime(1);

        // The display should have been turned off
        verify(mHolder.displayPowerState)
                .setScreenState(Display.STATE_OFF, Display.STATE_REASON_DEFAULT_POLICY);

        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_OFF);
        // The display device changes and we no longer have a prox sensor
        reset(mSensorManagerMock);
        setUpDisplay(DISPLAY_ID, "new_unique_id", mHolder.display, mock(DisplayDevice.class),
                mock(DisplayDeviceConfig.class), /* isEnabled= */ true);
        mHolder.dpc.onDisplayChanged(mHolder.hbmMetadata, Layout.NO_LEAD_DISPLAY);

        advanceTime(1); // Run updatePowerState

        // The display should have been turned back on and the listener should have been
        // unregistered
        verify(mHolder.displayPowerState)
                .setScreenState(Display.STATE_ON, Display.STATE_REASON_DEFAULT_POLICY);
        verify(mSensorManagerMock).unregisterListener(listener);
    }

    @Test
    public void testProximitySensorListenerNotRegisteredForNonDefaultDisplay() {
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        // send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        final DisplayPowerControllerHolder followerDpc =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_ID);
        followerDpc.dpc.requestPowerState(dpr, false /* waitForNegativeProximity */);

        // Run updatePowerState
        advanceTime(1);

        verify(mSensorManagerMock, never()).registerListener(any(SensorEventListener.class),
                eq(mProxSensor), anyInt(), any(Handler.class));
    }

    @Test
    public void testDisplayBrightnessFollowers_BothDpcsSupportNits() {
        DisplayPowerControllerHolder followerDpc =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_ID);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(followerDpc.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        followerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mHolder.brightnessSetting).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mHolder.dpc.addDisplayBrightnessFollower(followerDpc.dpc);

        // Test different float scale values
        float leadBrightness = 0.3f;
        float followerBrightness = 0.4f;
        float nits = 300;
        when(mHolder.automaticBrightnessController.convertToNits(leadBrightness)).thenReturn(nits);
        when(followerDpc.automaticBrightnessController.getBrightnessFromNits(nits))
                .thenReturn(followerBrightness);
        when(mHolder.brightnessSetting.getBrightness()).thenReturn(leadBrightness);
        listener.onBrightnessChanged(leadBrightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mHolder.animator).animateTo(eq(leadBrightness), anyFloat(), anyFloat(), eq(false));
        verify(followerDpc.animator).animateTo(eq(followerBrightness), anyFloat(),
                anyFloat(), eq(false));

        clearInvocations(mHolder.animator, followerDpc.animator);

        // Test the same float scale value
        float brightness = 0.6f;
        nits = 600;
        when(mHolder.automaticBrightnessController.convertToNits(brightness)).thenReturn(nits);
        when(followerDpc.automaticBrightnessController.getBrightnessFromNits(nits))
                .thenReturn(brightness);
        when(mHolder.brightnessSetting.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        verify(followerDpc.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
    }

    @Test
    public void testDisplayBrightnessFollowers_FollowerDoesNotSupportNits() {
        DisplayPowerControllerHolder followerDpc =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_ID);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(followerDpc.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        followerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mHolder.brightnessSetting).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mHolder.dpc.addDisplayBrightnessFollower(followerDpc.dpc);

        float brightness = 0.3f;
        when(mHolder.automaticBrightnessController.convertToNits(brightness)).thenReturn(300f);
        when(followerDpc.automaticBrightnessController.getBrightnessFromNits(anyFloat()))
                .thenReturn(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mHolder.brightnessSetting.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        verify(followerDpc.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
    }

    @Test
    public void testDisplayBrightnessFollowers_LeadDpcDoesNotSupportNits() {
        DisplayPowerControllerHolder followerDpc =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_ID);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(followerDpc.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        followerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mHolder.brightnessSetting).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mHolder.dpc.addDisplayBrightnessFollower(followerDpc.dpc);

        float brightness = 0.3f;
        when(mHolder.automaticBrightnessController.convertToNits(anyFloat())).thenReturn(-1f);
        when(mHolder.brightnessSetting.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        verify(followerDpc.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
    }

    @Test
    public void testDisplayBrightnessFollowers_NeitherDpcSupportsNits() {
        DisplayPowerControllerHolder followerDpc =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_ID);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(followerDpc.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        followerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mHolder.brightnessSetting).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mHolder.dpc.addDisplayBrightnessFollower(followerDpc.dpc);

        float brightness = 0.3f;
        when(mHolder.automaticBrightnessController.convertToNits(anyFloat())).thenReturn(-1f);
        when(followerDpc.automaticBrightnessController.getBrightnessFromNits(anyFloat()))
                .thenReturn(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mHolder.brightnessSetting.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        verify(followerDpc.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
    }

    @Test
    public void testDisplayBrightnessFollowers_AutomaticBrightness() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        DisplayPowerControllerHolder followerDpc =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_ID);
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(followerDpc.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        float leadBrightness = 0.1f;
        float rawLeadBrightness = 0.3f;
        float followerBrightness = 0.4f;
        float nits = 300;
        float ambientLux = 3000;
        when(mHolder.automaticBrightnessController.getRawAutomaticScreenBrightness())
                .thenReturn(rawLeadBrightness);
        when(mHolder.automaticBrightnessController
                .getAutomaticScreenBrightness(any(BrightnessEvent.class)))
                .thenReturn(leadBrightness);
        when(mHolder.automaticBrightnessController.convertToNits(rawLeadBrightness))
                .thenReturn(nits);
        when(mHolder.automaticBrightnessController.getAmbientLux()).thenReturn(ambientLux);
        when(followerDpc.automaticBrightnessController.getBrightnessFromNits(nits))
                .thenReturn(followerBrightness);

        mHolder.dpc.addDisplayBrightnessFollower(followerDpc.dpc);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        followerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator).animateTo(eq(leadBrightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        verify(followerDpc.hbmController).onAmbientLuxChange(ambientLux);
        verify(followerDpc.animator).animateTo(eq(followerBrightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));

        when(mHolder.displayPowerState.getScreenBrightness()).thenReturn(leadBrightness);
        when(followerDpc.displayPowerState.getScreenBrightness()).thenReturn(followerBrightness);
        clearInvocations(mHolder.animator, followerDpc.animator);

        leadBrightness = 0.05f;
        rawLeadBrightness = 0.2f;
        followerBrightness = 0.3f;
        nits = 200;
        ambientLux = 2000;
        when(mHolder.automaticBrightnessController.getRawAutomaticScreenBrightness())
                .thenReturn(rawLeadBrightness);
        when(mHolder.automaticBrightnessController
                .getAutomaticScreenBrightness(any(BrightnessEvent.class)))
                .thenReturn(leadBrightness);
        when(mHolder.automaticBrightnessController.convertToNits(rawLeadBrightness))
                .thenReturn(nits);
        when(mHolder.automaticBrightnessController.getAmbientLux()).thenReturn(ambientLux);
        when(followerDpc.automaticBrightnessController.getBrightnessFromNits(nits))
                .thenReturn(followerBrightness);

        mHolder.dpc.updateBrightness();
        advanceTime(1); // Run updatePowerState

        // The second time, the animation rate should be slow
        verify(mHolder.animator).animateTo(eq(leadBrightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_SLOW_DECREASE), eq(false));
        verify(followerDpc.hbmController).onAmbientLuxChange(ambientLux);
        verify(followerDpc.animator).animateTo(eq(followerBrightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_SLOW_DECREASE), eq(false));
    }

    @Test
    public void testDisplayBrightnessFollowersRemoval_RemoveSingleFollower() {
        DisplayPowerControllerHolder followerDpc = createDisplayPowerController(FOLLOWER_DISPLAY_ID,
                FOLLOWER_UNIQUE_ID);
        DisplayPowerControllerHolder secondFollowerDpc = createDisplayPowerController(
                SECOND_FOLLOWER_DISPLAY_ID, SECOND_FOLLOWER_UNIQUE_DISPLAY_ID);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(followerDpc.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(secondFollowerDpc.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        followerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        secondFollowerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mHolder.brightnessSetting).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        // Set the initial brightness on the DPC we're going to remove so we have a fixed value for
        // it to return to.
        listenerCaptor = ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(followerDpc.brightnessSetting).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener followerListener = listenerCaptor.getValue();
        final float initialFollowerBrightness = 0.3f;
        when(followerDpc.brightnessSetting.getBrightness()).thenReturn(initialFollowerBrightness);
        followerListener.onBrightnessChanged(initialFollowerBrightness);
        advanceTime(1);
        verify(followerDpc.animator).animateTo(eq(initialFollowerBrightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));

        when(followerDpc.displayPowerState.getScreenBrightness())
                .thenReturn(initialFollowerBrightness);

        mHolder.dpc.addDisplayBrightnessFollower(followerDpc.dpc);
        mHolder.dpc.addDisplayBrightnessFollower(secondFollowerDpc.dpc);
        clearInvocations(followerDpc.animator);

        // Validate both followers are correctly registered and receiving brightness updates
        float brightness = 0.6f;
        float nits = 600;
        when(mHolder.automaticBrightnessController.convertToNits(brightness)).thenReturn(nits);
        when(followerDpc.automaticBrightnessController.getBrightnessFromNits(nits))
                .thenReturn(brightness);
        when(secondFollowerDpc.automaticBrightnessController.getBrightnessFromNits(nits))
                .thenReturn(brightness);
        when(mHolder.brightnessSetting.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        verify(followerDpc.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        verify(secondFollowerDpc.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));

        when(mHolder.displayPowerState.getScreenBrightness()).thenReturn(brightness);
        when(followerDpc.displayPowerState.getScreenBrightness()).thenReturn(brightness);
        when(secondFollowerDpc.displayPowerState.getScreenBrightness()).thenReturn(brightness);
        clearInvocations(mHolder.animator, followerDpc.animator, secondFollowerDpc.animator);

        // Remove the first follower and validate it goes back to its original brightness.
        mHolder.dpc.removeDisplayBrightnessFollower(followerDpc.dpc);
        advanceTime(1);
        verify(followerDpc.animator).animateTo(eq(initialFollowerBrightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_DECREASE), eq(false));

        when(followerDpc.displayPowerState.getScreenBrightness())
                .thenReturn(initialFollowerBrightness);
        clearInvocations(followerDpc.animator);

        // Change the brightness of the lead display and validate only the second follower responds
        brightness = 0.7f;
        nits = 700;
        when(mHolder.automaticBrightnessController.convertToNits(brightness)).thenReturn(nits);
        when(followerDpc.automaticBrightnessController.getBrightnessFromNits(nits))
                .thenReturn(brightness);
        when(secondFollowerDpc.automaticBrightnessController.getBrightnessFromNits(nits))
                .thenReturn(brightness);
        when(mHolder.brightnessSetting.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        verify(followerDpc.animator, never()).animateTo(anyFloat(), anyFloat(), anyFloat(),
                anyBoolean());
        verify(secondFollowerDpc.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
    }

    @Test
    public void testDisplayBrightnessFollowersRemoval_RemoveAllFollowers() {
        DisplayPowerControllerHolder followerHolder =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_ID);
        DisplayPowerControllerHolder secondFollowerHolder =
                createDisplayPowerController(SECOND_FOLLOWER_DISPLAY_ID,
                        SECOND_FOLLOWER_UNIQUE_DISPLAY_ID);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(followerHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(secondFollowerHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        followerHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        secondFollowerHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mHolder.brightnessSetting).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        // Set the initial brightness on the DPCs we're going to remove so we have a fixed value for
        // it to return to.
        listenerCaptor = ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(followerHolder.brightnessSetting).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener followerListener = listenerCaptor.getValue();
        listenerCaptor = ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(secondFollowerHolder.brightnessSetting).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener secondFollowerListener =
                listenerCaptor.getValue();
        final float initialFollowerBrightness = 0.3f;
        when(followerHolder.brightnessSetting.getBrightness()).thenReturn(
                initialFollowerBrightness);
        when(secondFollowerHolder.brightnessSetting.getBrightness()).thenReturn(
                initialFollowerBrightness);
        followerListener.onBrightnessChanged(initialFollowerBrightness);
        secondFollowerListener.onBrightnessChanged(initialFollowerBrightness);
        advanceTime(1);
        verify(followerHolder.animator).animateTo(eq(initialFollowerBrightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        verify(secondFollowerHolder.animator).animateTo(eq(initialFollowerBrightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));

        when(followerHolder.displayPowerState.getScreenBrightness())
                .thenReturn(initialFollowerBrightness);
        when(secondFollowerHolder.displayPowerState.getScreenBrightness())
                .thenReturn(initialFollowerBrightness);

        mHolder.dpc.addDisplayBrightnessFollower(followerHolder.dpc);
        mHolder.dpc.addDisplayBrightnessFollower(secondFollowerHolder.dpc);
        clearInvocations(followerHolder.animator, secondFollowerHolder.animator);

        // Validate both followers are correctly registered and receiving brightness updates
        float brightness = 0.6f;
        float nits = 600;
        when(mHolder.automaticBrightnessController.convertToNits(brightness)).thenReturn(nits);
        when(followerHolder.automaticBrightnessController.getBrightnessFromNits(nits))
                .thenReturn(brightness);
        when(secondFollowerHolder.automaticBrightnessController.getBrightnessFromNits(nits))
                .thenReturn(brightness);
        when(mHolder.brightnessSetting.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        verify(followerHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        verify(secondFollowerHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));

        when(mHolder.displayPowerState.getScreenBrightness()).thenReturn(brightness);
        when(followerHolder.displayPowerState.getScreenBrightness()).thenReturn(brightness);
        when(secondFollowerHolder.displayPowerState.getScreenBrightness()).thenReturn(brightness);
        clearInvocations(mHolder.animator, followerHolder.animator, secondFollowerHolder.animator);

        // Stop the lead DPC and validate that the followers go back to their original brightness.
        mHolder.dpc.stop();
        advanceTime(1);
        verify(followerHolder.animator).animateTo(eq(initialFollowerBrightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_DECREASE), eq(false));
        verify(secondFollowerHolder.animator).animateTo(eq(initialFollowerBrightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_DECREASE), eq(false));
        clearInvocations(followerHolder.animator, secondFollowerHolder.animator);
    }

    @Test
    public void testDisplayBrightnessHdr_SkipAnimationOnHdrAppearance() {
        when(mDisplayManagerFlagsMock.isFastHdrTransitionsEnabled()).thenReturn(true);
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        final float sdrBrightness = 0.1f;
        final float hdrBrightness = 0.3f;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.automaticBrightnessController.getAutomaticScreenBrightness(
                any(BrightnessEvent.class))).thenReturn(sdrBrightness);
        when(mHolder.hdrClamper.getMaxBrightness()).thenReturn(1.0f);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator).animateTo(eq(sdrBrightness), eq(sdrBrightness),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));

        when(mHolder.displayPowerState.getScreenBrightness()).thenReturn(sdrBrightness);
        when(mHolder.displayPowerState.getSdrScreenBrightness()).thenReturn(sdrBrightness);

        when(mHolder.hbmController.getHighBrightnessMode()).thenReturn(
                BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR);
        when(mHolder.hbmController.getHdrBrightnessValue()).thenReturn(hdrBrightness);
        clearInvocations(mHolder.animator);

        mHolder.dpc.updateBrightness();
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator).animateTo(eq(hdrBrightness), eq(sdrBrightness),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
    }

    @Test
    public void testDisplayBrightnessHdr_SkipAnimationOnHdrRemoval() {
        when(mDisplayManagerFlagsMock.isFastHdrTransitionsEnabled()).thenReturn(true);
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        final float sdrBrightness = 0.1f;
        final float hdrBrightness = 0.3f;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.automaticBrightnessController.isInIdleMode()).thenReturn(true);
        when(mHolder.automaticBrightnessController.getAutomaticScreenBrightness(
                any(BrightnessEvent.class))).thenReturn(sdrBrightness);
        when(mHolder.hdrClamper.getMaxBrightness()).thenReturn(1.0f);

        when(mHolder.hbmController.getHighBrightnessMode()).thenReturn(
                BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR);
        when(mHolder.hbmController.getHdrBrightnessValue()).thenReturn(hdrBrightness);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator).animateTo(eq(hdrBrightness), eq(sdrBrightness),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));

        when(mHolder.displayPowerState.getScreenBrightness()).thenReturn(hdrBrightness);
        when(mHolder.displayPowerState.getSdrScreenBrightness()).thenReturn(sdrBrightness);
        when(mHolder.hbmController.getHighBrightnessMode()).thenReturn(
                BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF);

        clearInvocations(mHolder.animator);

        mHolder.dpc.updateBrightness();
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator).animateTo(eq(sdrBrightness), eq(sdrBrightness),
                eq(BRIGHTNESS_RAMP_RATE_FAST_DECREASE), eq(false));
    }

    @Test
    public void testDoesNotSetScreenStateForNonDefaultDisplayUntilBootCompleted() {
        // We should still set screen state for the default display
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState
        verify(mHolder.displayPowerState).setScreenState(anyInt(), anyInt());

        mHolder = createDisplayPowerController(42, UNIQUE_ID);

        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState
        verify(mHolder.displayPowerState, never()).setScreenState(anyInt(), anyInt());

        mHolder.dpc.onBootCompleted();
        advanceTime(1); // Run updatePowerState
        verify(mHolder.displayPowerState).setScreenState(anyInt(), anyInt());
    }

    @Test
    public void testSetScreenOffBrightnessSensorEnabled_DisplayIsOff() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);

        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_OFF);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_OFF;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.screenOffBrightnessSensorController, atLeastOnce())
                .setLightSensorEnabled(true);

        // The display turns on and we use the brightness value recommended by
        // ScreenOffBrightnessSensorController
        clearInvocations(mHolder.screenOffBrightnessSensorController);
        float brightness = 0.14f;
        when(mHolder.screenOffBrightnessSensorController.getAutomaticScreenBrightness())
                .thenReturn(brightness);
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        when(mHolder.automaticBrightnessController.getAutomaticScreenBrightness(
                any(BrightnessEvent.class))).thenReturn(PowerManager.BRIGHTNESS_INVALID_FLOAT);

        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.screenOffBrightnessSensorController, atLeastOnce())
                .getAutomaticScreenBrightness();
        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(), anyFloat(), eq(false));
    }

    @Test
    public void testSetScreenOffBrightnessSensorEnabled_DisplayIsInDoze() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_allowAutoBrightnessWhileDozing, false);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);

        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);

        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_DOZE);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.screenOffBrightnessSensorController, atLeastOnce())
                .setLightSensorEnabled(true);

        // The display turns on and we use the brightness value recommended by
        // ScreenOffBrightnessSensorController
        clearInvocations(mHolder.screenOffBrightnessSensorController);
        float brightness = 0.14f;
        when(mHolder.screenOffBrightnessSensorController.getAutomaticScreenBrightness())
                .thenReturn(brightness);
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        when(mHolder.automaticBrightnessController.getAutomaticScreenBrightness(
                any(BrightnessEvent.class))).thenReturn(PowerManager.BRIGHTNESS_INVALID_FLOAT);

        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.screenOffBrightnessSensorController, atLeastOnce())
                .getAutomaticScreenBrightness();
        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(), anyFloat(), eq(false));
    }

    @Test
    public void testSetScreenOffBrightnessSensorDisabled_AutoBrightnessIsDisabled() {
        // Tests are set up with manual brightness by default, so no need to set it here.
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_OFF;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.screenOffBrightnessSensorController, atLeastOnce())
                .setLightSensorEnabled(false);
    }

    @Test
    public void testSetScreenOffBrightnessSensorDisabled_DisplayIsDisabled() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID, /* isEnabled= */ false);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.screenOffBrightnessSensorController, atLeastOnce())
                .setLightSensorEnabled(false);
    }

    @Test
    public void testSetScreenOffBrightnessSensorDisabled_DisplayIsOn() {
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;

        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.screenOffBrightnessSensorController, atLeastOnce())
                .setLightSensorEnabled(false);
    }

    @Test
    public void testSetScreenOffBrightnessSensorDisabled_DisplayIsAFollower() {
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_OFF;

        mHolder.dpc.onDisplayChanged(mHolder.hbmMetadata, /* leadDisplayId= */ 42);
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.screenOffBrightnessSensorController, atLeastOnce())
                .setLightSensorEnabled(false);
    }

    @Test
    public void testStopScreenOffBrightnessSensorControllerWhenDisplayDeviceChanges() {
        // New display device
        setUpDisplay(DISPLAY_ID, "new_unique_id", mHolder.display, mock(DisplayDevice.class),
                mock(DisplayDeviceConfig.class), /* isEnabled= */ true);

        mHolder.dpc.onDisplayChanged(mHolder.hbmMetadata, Layout.NO_LEAD_DISPLAY);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.screenOffBrightnessSensorController).stop();
    }

    @Test
    public void testAutoBrightnessEnabled_DisplayIsOn() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.automaticBrightnessController).configure(
                AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED,
                /* configuration= */ null, PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChangedAutoBrightnessAdjustment= */ false, DisplayPowerRequest.POLICY_BRIGHT,
                Display.STATE_ON, /* shouldResetShortTermModel= */ false
        );
        verify(mHolder.hbmController)
                .setAutoBrightnessEnabled(AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED);
    }

    @Test
    public void testAutoBrightnessEnabled_DisplayIsInDoze() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_allowAutoBrightnessWhileDozing, true);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_DOZE);
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.automaticBrightnessController).configure(
                AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED,
                /* configuration= */ null, PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChangedAutoBrightnessAdjustment= */ false, DisplayPowerRequest.POLICY_DOZE,
                Display.STATE_DOZE, /* shouldResetShortTermModel= */ false
        );
        verify(mHolder.hbmController)
                .setAutoBrightnessEnabled(AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED);
    }

    @Test
    public void testAutoBrightnessEnabled_DisplayIsInDoze_OffloadAllows() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_allowAutoBrightnessWhileDozing, true);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        when(mDisplayManagerFlagsMock.isDisplayOffloadEnabled()).thenReturn(true);
        when(mDisplayManagerFlagsMock.offloadControlsDozeAutoBrightness()).thenReturn(true);
        when(mDisplayOffloadSession.allowAutoBrightnessInDoze()).thenReturn(true);
        mHolder.dpc.setDisplayOffloadSession(mDisplayOffloadSession);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_DOZE);
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.automaticBrightnessController).configure(
                AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED,
                /* configuration= */ null, PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChangedAutoBrightnessAdjustment= */ false, DisplayPowerRequest.POLICY_DOZE,
                Display.STATE_DOZE, /* shouldResetShortTermModel= */ false
        );
        verify(mHolder.hbmController)
                .setAutoBrightnessEnabled(AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED);
    }

    @Test
    public void testAutoBrightnessDisabled_ManualBrightnessMode() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.automaticBrightnessController).configure(
                AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED,
                /* configuration= */ null, PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChangedAutoBrightnessAdjustment= */ false, DisplayPowerRequest.POLICY_BRIGHT,
                Display.STATE_ON, /* shouldResetShortTermModel= */ false
        );
        verify(mHolder.hbmController)
                .setAutoBrightnessEnabled(AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED);
    }

    @Test
    public void testAutoBrightnessDisabled_DisplayIsOff() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_OFF;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_OFF);
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.automaticBrightnessController).configure(
                AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE,
                /* configuration= */ null, PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChangedAutoBrightnessAdjustment= */ false, DisplayPowerRequest.POLICY_OFF,
                Display.STATE_OFF, /* shouldResetShortTermModel= */ false
        );
        verify(mHolder.hbmController).setAutoBrightnessEnabled(
                AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE);
    }

    @Test
    public void testAutoBrightnessDisabled_DisplayIsInDoze_ConfigDoesNotAllow() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_allowAutoBrightnessWhileDozing, false);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_DOZE);
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.automaticBrightnessController).configure(
                AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE,
                /* configuration= */ null, PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChangedAutoBrightnessAdjustment= */ false, DisplayPowerRequest.POLICY_DOZE,
                Display.STATE_DOZE, /* shouldResetShortTermModel= */ false
        );
        verify(mHolder.hbmController).setAutoBrightnessEnabled(
                AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE);
    }

    @Test
    public void testAutoBrightnessDisabled_DisplayIsInDoze_OffloadDoesNotAllow() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_allowAutoBrightnessWhileDozing, true);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        when(mDisplayManagerFlagsMock.isDisplayOffloadEnabled()).thenReturn(true);
        when(mDisplayManagerFlagsMock.offloadControlsDozeAutoBrightness()).thenReturn(true);
        when(mDisplayOffloadSession.allowAutoBrightnessInDoze()).thenReturn(false);
        mHolder.dpc.setDisplayOffloadSession(mDisplayOffloadSession);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_DOZE);
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.automaticBrightnessController).configure(
                AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE,
                /* configuration= */ null, PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChangedAutoBrightnessAdjustment= */ false, DisplayPowerRequest.POLICY_DOZE,
                Display.STATE_DOZE, /* shouldResetShortTermModel= */ false
        );
        verify(mHolder.hbmController).setAutoBrightnessEnabled(
                AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE);
    }

    @Test
    public void testAutoBrightnessDisabled_FollowerDisplay() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mHolder.dpc.setBrightnessToFollow(0.3f, -1, 0, /* slowChange= */ false);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.automaticBrightnessController).configure(
                AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED,
                /* configuration= */ null, PowerManager.BRIGHTNESS_INVALID_FLOAT,
                /* userChangedBrightness= */ false, /* adjustment= */ 0,
                /* userChangedAutoBrightnessAdjustment= */ false, DisplayPowerRequest.POLICY_BRIGHT,
                Display.STATE_ON, /* shouldResetShortTermModel= */ false
        );

        // HBM should be allowed for the follower display
        verify(mHolder.hbmController)
                .setAutoBrightnessEnabled(AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED);
    }

    @Test
    public void testBrightnessNitsPersistWhenDisplayDeviceChanges() {
        float brightness = 0.3f;
        float nits = 500;
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_persistBrightnessNitsForDefaultDisplay,
                true);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        when(mHolder.automaticBrightnessController.convertToNits(brightness)).thenReturn(nits);

        mHolder.dpc.setBrightness(brightness);
        verify(mHolder.brightnessSetting).setBrightnessNitsForDefaultDisplay(nits);

        float newBrightness = 0.4f;
        when(mHolder.brightnessSetting.getBrightnessNitsForDefaultDisplay()).thenReturn(nits);
        when(mHolder.automaticBrightnessController.getBrightnessFromNits(nits))
                .thenReturn(newBrightness);
        // New display device
        setUpDisplay(DISPLAY_ID, "new_unique_id", mHolder.display, mock(DisplayDevice.class),
                mock(DisplayDeviceConfig.class), /* isEnabled= */ true);
        mHolder.dpc.onDisplayChanged(mHolder.hbmMetadata, Layout.NO_LEAD_DISPLAY);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState
        verify(mHolder.animator).animateTo(eq(newBrightness), anyFloat(), anyFloat(),
                eq(false));
    }

    @Test
    public void testShortTermModelPersistsWhenDisplayDeviceChanges() {
        float lux = 2000;
        float nits = 500;
        when(mHolder.automaticBrightnessController.getUserLux()).thenReturn(lux);
        when(mHolder.automaticBrightnessController.getUserNits()).thenReturn(nits);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1);
        clearInvocations(mHolder.injector);

        // New display device
        setUpDisplay(DISPLAY_ID, "new_unique_id", mHolder.display, mock(DisplayDevice.class),
                mock(DisplayDeviceConfig.class), /* isEnabled= */ true);
        mHolder.dpc.onDisplayChanged(mHolder.hbmMetadata, Layout.NO_LEAD_DISPLAY);
        advanceTime(1);

        verify(mHolder.injector).getAutomaticBrightnessController(
                any(AutomaticBrightnessController.Callbacks.class),
                any(Looper.class),
                eq(mSensorManagerMock),
                /* lightSensor= */ any(),
                /* brightnessMappingStrategyMap= */ any(SparseArray.class),
                /* lightSensorWarmUpTime= */ anyInt(),
                /* brightnessMin= */ anyFloat(),
                /* brightnessMax= */ anyFloat(),
                /* dozeScaleFactor */ anyFloat(),
                /* lightSensorRate= */ anyInt(),
                /* initialLightSensorRate= */ anyInt(),
                /* brighteningLightDebounceConfig */ anyLong(),
                /* darkeningLightDebounceConfig */ anyLong(),
                /* brighteningLightDebounceConfigIdle= */ anyLong(),
                /* darkeningLightDebounceConfigIdle= */ anyLong(),
                /* resetAmbientLuxAfterWarmUpConfig= */ anyBoolean(),
                any(HysteresisLevels.class),
                any(HysteresisLevels.class),
                any(HysteresisLevels.class),
                any(HysteresisLevels.class),
                eq(mContext),
                any(BrightnessRangeController.class),
                any(BrightnessThrottler.class),
                /* ambientLightHorizonShort= */ anyInt(),
                /* ambientLightHorizonLong= */ anyInt(),
                eq(lux),
                eq(nits),
                any(BrightnessClamperController.class),
                any(DisplayManagerFlags.class)
        );
    }

    @Test
    public void testUpdateBrightnessThrottlingDataId() {
        mHolder.display.getDisplayInfoLocked().thermalBrightnessThrottlingDataId =
                "throttling-data-id";
        clearInvocations(mHolder.display.getPrimaryDisplayDeviceLocked().getDisplayDeviceConfig());

        mHolder.dpc.onDisplayChanged(mHolder.hbmMetadata, Layout.NO_LEAD_DISPLAY);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.display.getPrimaryDisplayDeviceLocked().getDisplayDeviceConfig())
                .getThermalBrightnessThrottlingDataMapByThrottlingId();
    }

    @Test
    public void testSetBrightness_BrightnessShouldBeClamped() {
        float clampedBrightness = 0.6f;
        when(mHolder.hbmController.getCurrentBrightnessMax()).thenReturn(clampedBrightness);

        mHolder.dpc.setBrightness(PowerManager.BRIGHTNESS_MAX);
        mHolder.dpc.setBrightness(0.8f, /* userSerial= */ 123);

        verify(mHolder.brightnessSetting, times(2)).setBrightness(clampedBrightness);
    }

    @Test
    public void testDwbcCallsHappenOnHandler() {
        when(mDisplayManagerFlagsMock.isAdaptiveTone1Enabled()).thenReturn(true);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);

        // Send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        mHolder.dpc.requestPowerState(dpr, false /* waitForNegativeProximity */);

        // Run updatePowerState
        advanceTime(1);

        setUpDisplay(DISPLAY_ID, "new_unique_id", mHolder.display, mock(DisplayDevice.class),
                mHolder.config, /* isEnabled= */ true);

        // dispatch handler looper
        advanceTime(1);
        clearInvocations(mDisplayWhiteBalanceControllerMock, mHolder.automaticBrightnessController,
                mHolder.animator);
        mHolder.dpc.setAutomaticScreenBrightnessMode(AUTO_BRIGHTNESS_MODE_IDLE);
        verifyNoMoreInteractions(mDisplayWhiteBalanceControllerMock,
                mHolder.automaticBrightnessController,
                mHolder.animator);

        // dispatch handler looper
        advanceTime(1);
        verify(mHolder.automaticBrightnessController).switchMode(AUTO_BRIGHTNESS_MODE_IDLE,
                /* sendUpdate= */ true);
        verify(mHolder.animator).setAnimationTimeLimits(BRIGHTNESS_RAMP_INCREASE_MAX_IDLE,
                BRIGHTNESS_RAMP_DECREASE_MAX_IDLE);
        verify(mDisplayWhiteBalanceControllerMock).setStrongModeEnabled(true);
    }

    @Test
    public void testRampRatesIdle() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        float brightness = 0.6f;
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.automaticBrightnessController.isInIdleMode()).thenReturn(true);
        when(mHolder.automaticBrightnessController.getAutomaticScreenBrightness(
                any(BrightnessEvent.class))).thenReturn(brightness);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));

        when(mHolder.displayPowerState.getScreenBrightness()).thenReturn(brightness);
        brightness = 0.05f;
        when(mHolder.automaticBrightnessController.getAutomaticScreenBrightness(
                any(BrightnessEvent.class))).thenReturn(brightness);

        mHolder.dpc.updateBrightness();
        advanceTime(1); // Run updatePowerState

        // The second time, the animation rate should be slow
        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_SLOW_DECREASE_IDLE), eq(false));

        brightness = 0.9f;
        when(mHolder.automaticBrightnessController.getAutomaticScreenBrightness(
                any(BrightnessEvent.class))).thenReturn(brightness);

        mHolder.dpc.updateBrightness();
        advanceTime(1); // Run updatePowerState
        // The third time, the animation rate should be slow
        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_SLOW_INCREASE_IDLE), eq(false));
    }

    @Test
    public void testRampRateForHdrContent_HdrClamperOff() {
        float hdrBrightness = 0.8f;
        float clampedBrightness = 0.6f;
        float transitionRate = 1.5f;

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.displayPowerState.getScreenBrightness()).thenReturn(.2f);
        when(mHolder.displayPowerState.getSdrScreenBrightness()).thenReturn(.1f);
        when(mHolder.hbmController.getHighBrightnessMode()).thenReturn(
                BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR);
        when(mHolder.hbmController.getHdrBrightnessValue()).thenReturn(hdrBrightness);
        when(mHolder.hdrClamper.getMaxBrightness()).thenReturn(clampedBrightness);
        when(mHolder.hdrClamper.getTransitionRate()).thenReturn(transitionRate);

        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator, atLeastOnce()).animateTo(eq(hdrBrightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
    }

    @Test
    public void testRampRateForHdrContent_HdrClamperOn() {
        float clampedBrightness = 0.6f;
        float transitionRate = 1.5f;
        when(mDisplayManagerFlagsMock.isHdrClamperEnabled()).thenReturn(true);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID, /* isEnabled= */ true);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.displayPowerState.getScreenBrightness()).thenReturn(.2f);
        when(mHolder.displayPowerState.getSdrScreenBrightness()).thenReturn(.1f);
        when(mHolder.hbmController.getHighBrightnessMode()).thenReturn(
                BrightnessInfo.HIGH_BRIGHTNESS_MODE_HDR);
        when(mHolder.hbmController.getHdrBrightnessValue()).thenReturn(PowerManager.BRIGHTNESS_MAX);
        when(mHolder.hdrClamper.clamp(PowerManager.BRIGHTNESS_MAX)).thenReturn(clampedBrightness);
        when(mHolder.hdrClamper.getTransitionRate()).thenReturn(transitionRate);

        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator, atLeastOnce()).animateTo(eq(clampedBrightness), anyFloat(),
                eq(transitionRate), eq(true));
    }

    @Test
    public void testRampRateForClampersControllerApplied() {
        float transitionRate = 1.5f;
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.displayPowerState.getScreenBrightness()).thenReturn(.2f);
        when(mHolder.displayPowerState.getSdrScreenBrightness()).thenReturn(.1f);
        when(mHolder.clamperController.clamp(any(), anyFloat(), anyBoolean(), anyInt())).thenAnswer(
                invocation -> DisplayBrightnessState.builder()
                        .setIsSlowChange(invocation.getArgument(2))
                        .setBrightness(invocation.getArgument(1))
                        .setMaxBrightness(PowerManager.BRIGHTNESS_MAX)
                        .setCustomAnimationRate(transitionRate).build());

        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator, atLeastOnce()).animateTo(anyFloat(), anyFloat(),
                eq(transitionRate), anyBoolean());
    }

    @Test
    public void testRampRateForClampersControllerNotApplied_ifDoze() {
        float transitionRate = 1.5f;
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        dpr.dozeScreenState = Display.STATE_UNKNOWN;
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.displayPowerState.getScreenBrightness()).thenReturn(.2f);
        when(mHolder.displayPowerState.getSdrScreenBrightness()).thenReturn(.1f);
        when(mHolder.clamperController.clamp(any(), anyFloat(), anyBoolean(), anyInt())).thenAnswer(
                invocation -> DisplayBrightnessState.builder()
                        .setIsSlowChange(invocation.getArgument(2))
                        .setBrightness(invocation.getArgument(1))
                        .setMaxBrightness(PowerManager.BRIGHTNESS_MAX)
                        .setCustomAnimationRate(transitionRate).build());

        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator, atLeastOnce()).animateTo(anyFloat(), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_DECREASE), anyBoolean());
        verify(mHolder.animator, never()).animateTo(anyFloat(), anyFloat(),
                eq(transitionRate), anyBoolean());
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_ADAPTIVE_TONE_IMPROVEMENTS_1)
    public void testRampMaxTimeInteractiveThenIdle() {
        // Send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        mHolder.dpc.requestPowerState(dpr, false /* waitForNegativeProximity */);

        // Run updatePowerState
        advanceTime(1);

        setUpDisplay(DISPLAY_ID, "new_unique_id", mHolder.display, mock(DisplayDevice.class),
                mHolder.config, /* isEnabled= */ true);
        verify(mHolder.animator).setAnimationTimeLimits(BRIGHTNESS_RAMP_INCREASE_MAX,
                BRIGHTNESS_RAMP_DECREASE_MAX);

        // switch to idle mode
        mHolder.dpc.setAutomaticScreenBrightnessMode(AUTO_BRIGHTNESS_MODE_IDLE);
        advanceTime(1);

        // A second time, when switching to idle mode.
        verify(mHolder.animator, times(2)).setAnimationTimeLimits(BRIGHTNESS_RAMP_INCREASE_MAX,
                BRIGHTNESS_RAMP_DECREASE_MAX);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_ADAPTIVE_TONE_IMPROVEMENTS_1)
    public void testRampMaxTimeInteractiveThenIdle_DifferentValues() {
        when(mDisplayManagerFlagsMock.isAdaptiveTone1Enabled()).thenReturn(true);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID, /* isEnabled= */ true);

        // Send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        mHolder.dpc.requestPowerState(dpr, false /* waitForNegativeProximity */);

        // Run updatePowerState
        advanceTime(1);

        setUpDisplay(DISPLAY_ID, "new_unique_id", mHolder.display, mock(DisplayDevice.class),
                mHolder.config, /* isEnabled= */ true);
        verify(mHolder.animator).setAnimationTimeLimits(BRIGHTNESS_RAMP_INCREASE_MAX,
                BRIGHTNESS_RAMP_DECREASE_MAX);

        // switch to idle mode
        mHolder.dpc.setAutomaticScreenBrightnessMode(AUTO_BRIGHTNESS_MODE_IDLE);
        advanceTime(1);

        // A second time, when switching to idle mode.
        verify(mHolder.animator).setAnimationTimeLimits(BRIGHTNESS_RAMP_INCREASE_MAX_IDLE,
                BRIGHTNESS_RAMP_DECREASE_MAX_IDLE);
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_ENABLE_ADAPTIVE_TONE_IMPROVEMENTS_1)
    public void testRampMaxTimeIdle() {
        // Send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        mHolder.dpc.requestPowerState(dpr, false /* waitForNegativeProximity */);
        // Run updatePowerState
        advanceTime(1);
        // Once on setup
        verify(mHolder.animator).setAnimationTimeLimits(BRIGHTNESS_RAMP_INCREASE_MAX,
                BRIGHTNESS_RAMP_DECREASE_MAX);

        setUpDisplay(DISPLAY_ID, "new_unique_id", mHolder.display, mock(DisplayDevice.class),
                mHolder.config, /* isEnabled= */ true);

        // switch to idle mode
        mHolder.dpc.setAutomaticScreenBrightnessMode(AUTO_BRIGHTNESS_MODE_IDLE);

        // Process the MSG_SWITCH_AUTOBRIGHTNESS_MODE event
        advanceTime(1);

        // A second time when switching to idle mode.
        verify(mHolder.animator, times(2)).setAnimationTimeLimits(BRIGHTNESS_RAMP_INCREASE_MAX,
                BRIGHTNESS_RAMP_DECREASE_MAX);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_ADAPTIVE_TONE_IMPROVEMENTS_1)
    public void testRampMaxTimeIdle_DifferentValues() {
        when(mDisplayManagerFlagsMock.isAdaptiveTone1Enabled()).thenReturn(true);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID, /* isEnabled= */ true);

        // Send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        mHolder.dpc.requestPowerState(dpr, false /* waitForNegativeProximity */);

        // Run updatePowerState
        advanceTime(1);

        setUpDisplay(DISPLAY_ID, "new_unique_id", mHolder.display, mock(DisplayDevice.class),
                mHolder.config, /* isEnabled= */ true);

        // switch to idle mode
        mHolder.dpc.setAutomaticScreenBrightnessMode(AUTO_BRIGHTNESS_MODE_IDLE);

        // Process the MSG_SWITCH_AUTOBRIGHTNESS_MODE event
        advanceTime(1);
        verify(mHolder.animator).setAnimationTimeLimits(BRIGHTNESS_RAMP_INCREASE_MAX_IDLE,
                BRIGHTNESS_RAMP_DECREASE_MAX_IDLE);
    }

    @Test
    public void testDozeScreenStateOverride_toSupportedOffloadStateFromDoze_DisplayStateChanges() {
        when(mDisplayManagerFlagsMock.isOffloadDozeOverrideHoldsWakelockEnabled()).thenReturn(true);

        // set up.
        int initState = Display.STATE_DOZE;
        int supportedTargetState = Display.STATE_DOZE_SUSPEND;
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        doAnswer(invocation -> {
            when(mHolder.displayPowerState.getScreenState()).thenReturn(invocation.getArgument(0));
            return null;
        }).when(mHolder.displayPowerState).setScreenState(anyInt(), anyInt());
        mHolder.dpc.setDisplayOffloadSession(mDisplayOffloadSession);

        // start with DOZE.
        when(mHolder.displayPowerState.getScreenState()).thenReturn(initState);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        reset(mHolder.wakelockController);
        mHolder.dpc.overrideDozeScreenState(
                supportedTargetState, Display.STATE_REASON_DEFAULT_POLICY);
        advanceTime(1); // Run updatePowerState

        // Should get a wakelock to notify powermanager
        verify(mHolder.wakelockController, atLeastOnce()).acquireWakelock(
                eq(WakelockController.WAKE_LOCK_UNFINISHED_BUSINESS));

        verify(mHolder.displayPowerState)
                .setScreenState(supportedTargetState, Display.STATE_REASON_DEFAULT_POLICY);
    }

    @Test
    public void testDozeScreenStateOverride_toUnSupportedOffloadStateFromDoze_stateRemains() {
        // set up.
        int initState = Display.STATE_DOZE;
        int unSupportedTargetState = Display.STATE_ON;
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        doAnswer(invocation -> {
            when(mHolder.displayPowerState.getScreenState()).thenReturn(invocation.getArgument(0));
            return null;
        }).when(mHolder.displayPowerState).setScreenState(anyInt(), anyInt());
        mHolder.dpc.setDisplayOffloadSession(mDisplayOffloadSession);

        // start with DOZE.
        when(mHolder.displayPowerState.getScreenState()).thenReturn(initState);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        mHolder.dpc.overrideDozeScreenState(
                unSupportedTargetState, Display.STATE_REASON_DEFAULT_POLICY);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.displayPowerState, never())
                .setScreenState(anyInt(), eq(Display.STATE_REASON_DEFAULT_POLICY));
    }

    @Test
    public void testDozeScreenStateOverride_toSupportedOffloadStateFromOFF_stateRemains() {
        // set up.
        int initState = Display.STATE_OFF;
        int supportedTargetState = Display.STATE_DOZE_SUSPEND;
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        doAnswer(invocation -> {
            when(mHolder.displayPowerState.getScreenState()).thenReturn(invocation.getArgument(0));
            return null;
        }).when(mHolder.displayPowerState).setScreenState(anyInt(), anyInt());
        mHolder.dpc.setDisplayOffloadSession(mDisplayOffloadSession);

        // start with OFF.
        when(mHolder.displayPowerState.getScreenState()).thenReturn(initState);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_OFF;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        mHolder.dpc.overrideDozeScreenState(
                supportedTargetState, Display.STATE_REASON_DEFAULT_POLICY);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.displayPowerState, never()).setScreenState(anyInt(), anyInt());
    }

    @Test
    public void testOffloadBlocker_turnON_screenOnBlocked() {
        // set up.
        int initState = Display.STATE_OFF;
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        mHolder.dpc.setDisplayOffloadSession(mDisplayOffloadSession);
        // start with OFF.
        when(mHolder.displayPowerState.getScreenState()).thenReturn(initState);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_OFF;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        // go to ON.
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mDisplayOffloadSession).blockScreenOn(any(Runnable.class));
    }

    @Test
    public void testOffloadBlocker_turnOFF_screenOnNotBlocked() {
        // set up.
        int initState = Display.STATE_ON;
        mHolder.dpc.setDisplayOffloadSession(mDisplayOffloadSession);
        // start with ON.
        when(mHolder.displayPowerState.getScreenState()).thenReturn(initState);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        // go to OFF.
        dpr.policy = DisplayPowerRequest.POLICY_OFF;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mDisplayOffloadSession, never()).blockScreenOn(any(Runnable.class));
    }

    @Test
    public void testBrightnessFromOffload() {
        when(mDisplayManagerFlagsMock.isDisplayOffloadEnabled()).thenReturn(true);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        float brightness = 0.34f;
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        when(mHolder.automaticBrightnessController.getAutomaticScreenBrightness(
                any(BrightnessEvent.class))).thenReturn(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        mHolder.dpc.setDisplayOffloadSession(mDisplayOffloadSession);

        mHolder.dpc.setBrightnessFromOffload(brightness);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        assertEquals(BrightnessReason.REASON_OFFLOAD, mHolder.dpc.mBrightnessReason.getReason());
    }

    @Test
    public void testBrightness_AutomaticHigherPriorityThanOffload() {
        when(mDisplayManagerFlagsMock.isDisplayOffloadEnabled()).thenReturn(true);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        float brightness = 0.34f;
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        when(mHolder.automaticBrightnessController.getAutomaticScreenBrightness(
                any(BrightnessEvent.class))).thenReturn(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        mHolder.dpc.setDisplayOffloadSession(mDisplayOffloadSession);

        mHolder.dpc.setBrightnessFromOffload(brightness);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        assertEquals(BrightnessReason.REASON_OFFLOAD, mHolder.dpc.mBrightnessReason.getReason());

        // Now automatic brightness becomes available
        brightness = 0.22f;
        when(mHolder.automaticBrightnessController.getAutomaticScreenBrightness(
                any(BrightnessEvent.class))).thenReturn(brightness);

        mHolder.dpc.updateBrightness();
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator).animateTo(eq(brightness), anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
        assertEquals(BrightnessReason.REASON_AUTOMATIC, mHolder.dpc.mBrightnessReason.getReason());
    }

    @Test
    public void testSwitchToDozeAutoBrightnessMode() {
        when(mDisplayManagerFlagsMock.areAutoBrightnessModesEnabled()).thenReturn(true);
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_DOZE);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.automaticBrightnessController)
                .switchMode(AUTO_BRIGHTNESS_MODE_DOZE, /* sendUpdate= */ false);

        // Back to default mode
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.automaticBrightnessController)
                .switchMode(AUTO_BRIGHTNESS_MODE_DEFAULT, /* sendUpdate= */ false);
    }

    @Test
    public void testDoesNotSwitchFromIdleToDozeAutoBrightnessMode() {
        when(mDisplayManagerFlagsMock.areAutoBrightnessModesEnabled()).thenReturn(true);
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_DOZE);
        when(mHolder.automaticBrightnessController.isInIdleMode()).thenReturn(true);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.automaticBrightnessController, never())
                .switchMode(eq(AUTO_BRIGHTNESS_MODE_DOZE), /* sendUpdate= */ anyBoolean());
    }

    @Test
    public void testDoesNotSwitchDozeAutoBrightnessModeIfFeatureFlagOff() {
        when(mDisplayManagerFlagsMock.areAutoBrightnessModesEnabled()).thenReturn(false);
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_DOZE);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.automaticBrightnessController, never())
                .switchMode(eq(AUTO_BRIGHTNESS_MODE_DOZE), /* sendUpdate= */ anyBoolean());
    }

    @Test
    public void testOnSwitchUserUpdatesBrightness() {
        int userSerial = 12345;
        float brightness = 0.65f;

        mHolder.dpc.onSwitchUser(/* newUserId= */ 15, userSerial, brightness);
        advanceTime(1);

        verify(mHolder.brightnessSetting).setUserSerial(userSerial);
        verify(mHolder.brightnessSetting).setBrightness(brightness);
    }

    @Test
    public void testOnSwitchUserDoesNotAddUserDataPoint() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        int userSerial = 12345;
        float brightness = 0.65f;
        when(mHolder.automaticBrightnessController.hasValidAmbientLux()).thenReturn(true);
        when(mHolder.automaticBrightnessController.convertToAdjustedNits(brightness))
                .thenReturn(500f);
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_ON);
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState
        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mHolder.brightnessSetting).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mHolder.dpc.onSwitchUser(/* newUserId= */ 15, userSerial, brightness);
        when(mHolder.brightnessSetting.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState

        verify(mHolder.automaticBrightnessController, never()).configure(
                /* state= */ anyInt(),
                /* configuration= */ any(),
                eq(brightness),
                /* userChangedBrightness= */ eq(true),
                /* adjustment= */ anyFloat(),
                /* userChangedAutoBrightnessAdjustment= */ anyBoolean(),
                /* displayPolicy= */ anyInt(),
                /* displayState= */ anyInt(),
                /* shouldResetShortTermModel= */ anyBoolean());
        verify(mBrightnessTrackerMock, never()).notifyBrightnessChanged(
                /* brightness= */ anyFloat(),
                /* userInitiated= */ eq(true),
                /* powerBrightnessFactor= */ anyFloat(),
                /* wasShortTermModelActive= */ anyBoolean(),
                /* isDefaultBrightnessConfig= */ anyBoolean(),
                /* uniqueDisplayId= */ any(),
                /* luxValues */ any(),
                /* luxTimestamps= */ any());
    }

    @Test
    public void testDozeManualBrightness() {
        when(mDisplayManagerFlagsMock.isDisplayOffloadEnabled()).thenReturn(true);
        mHolder.dpc.setDisplayOffloadSession(mDisplayOffloadSession);
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        float brightness = 0.277f;
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.brightnessSetting.getBrightness()).thenReturn(brightness);
        when(mHolder.hbmController.getCurrentBrightnessMax())
                .thenReturn(PowerManager.BRIGHTNESS_MAX);
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_DOZE);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState, initialize

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mHolder.brightnessSetting).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState

        verify(mHolder.animator).animateTo(eq(brightness * DOZE_SCALE_FACTOR),
                /* linearSecondTarget= */ anyFloat(), /* rate= */ anyFloat(),
                /* ignoreAnimationLimits= */ anyBoolean());
        assertEquals(brightness * DOZE_SCALE_FACTOR, mHolder.dpc.getDozeBrightnessForOffload(),
                /* delta= */ 0);
    }

    @Test
    public void testDozeManualBrightness_AbcIsNull() {
        when(mDisplayManagerFlagsMock.isDisplayOffloadEnabled()).thenReturn(true);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID, /* isEnabled= */ true,
                /* isAutoBrightnessAvailable= */ false);
        mHolder.dpc.setDisplayOffloadSession(mDisplayOffloadSession);
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        float brightness = 0.277f;
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.brightnessSetting.getBrightness()).thenReturn(brightness);
        when(mHolder.hbmController.getCurrentBrightnessMax())
                .thenReturn(PowerManager.BRIGHTNESS_MAX);
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_DOZE);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState, initialize

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mHolder.brightnessSetting).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState

        verify(mHolder.animator).animateTo(eq(brightness * DOZE_SCALE_FACTOR),
                /* linearSecondTarget= */ anyFloat(), /* rate= */ anyFloat(),
                /* ignoreAnimationLimits= */ anyBoolean());
        assertEquals(brightness * DOZE_SCALE_FACTOR, mHolder.dpc.getDozeBrightnessForOffload(),
                /* delta= */ 0);
    }

    @Test
    public void testDefaultDozeBrightness() {
        float brightness = 0.121f;
        when(mPowerManagerMock.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DOZE)).thenReturn(brightness);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_allowAutoBrightnessWhileDozing, false);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.automaticBrightnessController.getAutomaticScreenBrightness(
                any(BrightnessEvent.class))).thenReturn(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mHolder.hbmController.getCurrentBrightnessMax())
                .thenReturn(PowerManager.BRIGHTNESS_MAX);
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_DOZE);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator).animateTo(eq(brightness), /* linearSecondTarget= */ anyFloat(),
                eq(BRIGHTNESS_RAMP_RATE_FAST_INCREASE), eq(false));
    }

    @Test
    public void testDefaultDozeBrightness_ShouldNotBeUsedIfAutoBrightnessAllowedInDoze() {
        float brightness = 0.121f;
        when(mPowerManagerMock.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_DOZE)).thenReturn(brightness);
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.bool.config_allowAutoBrightnessWhileDozing, true);
        mHolder = createDisplayPowerController(DISPLAY_ID, UNIQUE_ID);
        when(mHolder.displayPowerState.getColorFadeLevel()).thenReturn(1.0f);
        when(mHolder.automaticBrightnessController.getAutomaticScreenBrightness(
                any(BrightnessEvent.class))).thenReturn(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mHolder.hbmController.getCurrentBrightnessMax())
                .thenReturn(PowerManager.BRIGHTNESS_MAX);
        when(mHolder.displayPowerState.getScreenState()).thenReturn(Display.STATE_DOZE);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_DOZE;
        mHolder.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        verify(mHolder.animator, never()).animateTo(eq(brightness),
                /* linearSecondTarget= */ anyFloat(), /* rate= */ anyFloat(),
                /* ignoreAnimationLimits= */ anyBoolean());
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

    private void setUpSensors() throws Exception {
        mProxSensor = TestUtils.createSensor(Sensor.TYPE_PROXIMITY, Sensor.STRING_TYPE_PROXIMITY,
                PROX_SENSOR_MAX_RANGE);
        Sensor screenOffBrightnessSensor = TestUtils.createSensor(
                Sensor.TYPE_LIGHT, Sensor.STRING_TYPE_LIGHT);
        when(mSensorManagerMock.getSensorList(eq(Sensor.TYPE_ALL)))
                .thenReturn(List.of(mProxSensor, screenOffBrightnessSensor));
    }

    private SensorEventListener getSensorEventListener(Sensor sensor) {
        verify(mSensorManagerMock).registerListener(mSensorEventListenerCaptor.capture(),
                eq(sensor), eq(SensorManager.SENSOR_DELAY_NORMAL), isA(Handler.class));
        return mSensorEventListenerCaptor.getValue();
    }

    private void setUpDisplay(int displayId, String uniqueId, LogicalDisplay logicalDisplayMock,
            DisplayDevice displayDeviceMock, DisplayDeviceConfig displayDeviceConfigMock,
            boolean isEnabled) {
        DisplayInfo info = new DisplayInfo();
        DisplayDeviceInfo deviceInfo = new DisplayDeviceInfo();
        deviceInfo.uniqueId = uniqueId;

        when(logicalDisplayMock.getDisplayIdLocked()).thenReturn(displayId);
        when(logicalDisplayMock.getPrimaryDisplayDeviceLocked()).thenReturn(displayDeviceMock);
        when(logicalDisplayMock.getDisplayInfoLocked()).thenReturn(info);
        when(logicalDisplayMock.isEnabledLocked()).thenReturn(isEnabled);
        when(logicalDisplayMock.isInTransitionLocked()).thenReturn(false);
        when(displayDeviceMock.getDisplayDeviceInfoLocked()).thenReturn(deviceInfo);
        when(displayDeviceMock.getUniqueId()).thenReturn(uniqueId);
        when(displayDeviceMock.getDisplayDeviceConfig()).thenReturn(displayDeviceConfigMock);
        when(displayDeviceConfigMock.getProximitySensor()).thenReturn(
                new SensorData(Sensor.STRING_TYPE_PROXIMITY, null));
        when(displayDeviceConfigMock.getNits()).thenReturn(new float[]{2, 500});
        when(displayDeviceConfigMock.isAutoBrightnessAvailable()).thenReturn(true);
        when(displayDeviceConfigMock.getAmbientLightSensor()).thenReturn(
                new SensorData());
        when(displayDeviceConfigMock.getScreenOffBrightnessSensor()).thenReturn(
                new SensorData(Sensor.STRING_TYPE_LIGHT, null));
        when(displayDeviceConfigMock.getScreenOffBrightnessSensorValueToLux())
                .thenReturn(new int[0]);

        when(displayDeviceConfigMock.getBrightnessRampFastDecrease())
                .thenReturn(BRIGHTNESS_RAMP_RATE_FAST_DECREASE);
        when(displayDeviceConfigMock.getBrightnessRampFastIncrease())
                .thenReturn(BRIGHTNESS_RAMP_RATE_FAST_INCREASE);
        when(displayDeviceConfigMock.getBrightnessRampSlowDecrease())
                .thenReturn(BRIGHTNESS_RAMP_RATE_SLOW_DECREASE);
        when(displayDeviceConfigMock.getBrightnessRampSlowIncrease())
                .thenReturn(BRIGHTNESS_RAMP_RATE_SLOW_INCREASE);
        when(displayDeviceConfigMock.getBrightnessRampSlowIncreaseIdle())
                .thenReturn(BRIGHTNESS_RAMP_RATE_SLOW_INCREASE_IDLE);
        when(displayDeviceConfigMock.getBrightnessRampSlowDecreaseIdle())
                .thenReturn(BRIGHTNESS_RAMP_RATE_SLOW_DECREASE_IDLE);

        when(displayDeviceConfigMock.getBrightnessRampIncreaseMaxMillis())
                .thenReturn(BRIGHTNESS_RAMP_INCREASE_MAX);
        when(displayDeviceConfigMock.getBrightnessRampDecreaseMaxMillis())
                .thenReturn(BRIGHTNESS_RAMP_DECREASE_MAX);
        when(displayDeviceConfigMock.getBrightnessRampIncreaseMaxIdleMillis())
                .thenReturn(BRIGHTNESS_RAMP_INCREASE_MAX_IDLE);
        when(displayDeviceConfigMock.getBrightnessRampDecreaseMaxIdleMillis())
                .thenReturn(BRIGHTNESS_RAMP_DECREASE_MAX_IDLE);

        final HysteresisLevels hysteresisLevels = mock(HysteresisLevels.class);
        when(displayDeviceConfigMock.getAmbientBrightnessHysteresis()).thenReturn(hysteresisLevels);
        when(displayDeviceConfigMock.getAmbientBrightnessIdleHysteresis()).thenReturn(
                hysteresisLevels);
        when(displayDeviceConfigMock.getScreenBrightnessHysteresis()).thenReturn(hysteresisLevels);
        when(displayDeviceConfigMock.getScreenBrightnessIdleHysteresis()).thenReturn(
                hysteresisLevels);
    }

    private DisplayPowerControllerHolder createDisplayPowerController(int displayId,
            String uniqueId) {
        return createDisplayPowerController(displayId, uniqueId, /* isEnabled= */ true);
    }

    private DisplayPowerControllerHolder createDisplayPowerController(int displayId,
            String uniqueId, boolean isEnabled) {
        return createDisplayPowerController(displayId, uniqueId, isEnabled,
                /* isAutoBrightnessAvailable= */ true);
    }

    private DisplayPowerControllerHolder createDisplayPowerController(int displayId,
            String uniqueId, boolean isEnabled, boolean isAutoBrightnessAvailable) {
        final DisplayPowerState displayPowerState = mock(DisplayPowerState.class);
        final DualRampAnimator<DisplayPowerState> animator = mock(DualRampAnimator.class);
        final AutomaticBrightnessController automaticBrightnessController =
                mock(AutomaticBrightnessController.class);
        final WakelockController wakelockController = mock(WakelockController.class);
        final BrightnessMappingStrategy brightnessMappingStrategy =
                mock(BrightnessMappingStrategy.class);
        final HysteresisLevels hysteresisLevels = mock(HysteresisLevels.class);
        final ScreenOffBrightnessSensorController screenOffBrightnessSensorController =
                mock(ScreenOffBrightnessSensorController.class);
        final HighBrightnessModeController hbmController = mock(HighBrightnessModeController.class);
        final HdrClamper hdrClamper = mock(HdrClamper.class);
        final NormalBrightnessModeController normalBrightnessModeController = mock(
                NormalBrightnessModeController.class);
        BrightnessClamperController clamperController = mock(BrightnessClamperController.class);

        when(hbmController.getCurrentBrightnessMax()).thenReturn(PowerManager.BRIGHTNESS_MAX);
        when(clamperController.clamp(any(), anyFloat(), anyBoolean(), anyInt())).thenAnswer(
                invocation -> DisplayBrightnessState.builder()
                        .setIsSlowChange(invocation.getArgument(2))
                        .setBrightness(invocation.getArgument(1))
                        .setMaxBrightness(PowerManager.BRIGHTNESS_MAX)
                        .setCustomAnimationRate(-1).build());

        TestInjector injector = spy(new TestInjector(displayPowerState, animator,
                automaticBrightnessController, wakelockController, brightnessMappingStrategy,
                screenOffBrightnessSensorController,
                hbmController, normalBrightnessModeController, hdrClamper,
                clamperController, mDisplayManagerFlagsMock));

        final LogicalDisplay display = mock(LogicalDisplay.class);
        final DisplayDevice device = mock(DisplayDevice.class);
        final HighBrightnessModeMetadata hbmMetadata = mock(HighBrightnessModeMetadata.class);
        final BrightnessSetting brightnessSetting = mock(BrightnessSetting.class);
        final DisplayDeviceConfig config = mock(DisplayDeviceConfig.class);

        when(config.getAmbientBrightnessHysteresis()).thenReturn(hysteresisLevels);
        when(config.getAmbientBrightnessIdleHysteresis()).thenReturn(hysteresisLevels);
        when(config.getScreenBrightnessHysteresis()).thenReturn(hysteresisLevels);
        when(config.getScreenBrightnessIdleHysteresis()).thenReturn(hysteresisLevels);

        setUpDisplay(displayId, uniqueId, display, device, config, isEnabled);
        when(config.isAutoBrightnessAvailable()).thenReturn(isAutoBrightnessAvailable);

        final DisplayPowerController dpc = new DisplayPowerController(
                mContext, injector, mDisplayPowerCallbacksMock, mHandler,
                mSensorManagerMock, mDisplayBlankerMock, display,
                mBrightnessTrackerMock, brightnessSetting, () -> {
        },
                hbmMetadata, /* bootCompleted= */ false, mDisplayManagerFlagsMock);

        return new DisplayPowerControllerHolder(dpc, display, displayPowerState, brightnessSetting,
                animator, automaticBrightnessController, wakelockController,
                screenOffBrightnessSensorController, hbmController, hdrClamper, clamperController,
                hbmMetadata, brightnessMappingStrategy, injector, config);
    }

    /**
     * A class for holding a DisplayPowerController under test and all the mocks specifically
     * related to it.
     */
    private static class DisplayPowerControllerHolder {
        public final DisplayPowerController dpc;
        public final LogicalDisplay display;
        public final DisplayPowerState displayPowerState;
        public final BrightnessSetting brightnessSetting;
        public final DualRampAnimator<DisplayPowerState> animator;
        public final AutomaticBrightnessController automaticBrightnessController;
        public final WakelockController wakelockController;
        public final ScreenOffBrightnessSensorController screenOffBrightnessSensorController;
        public final HighBrightnessModeController hbmController;

        public final HdrClamper hdrClamper;
        public final BrightnessClamperController clamperController;
        public final HighBrightnessModeMetadata hbmMetadata;
        public final BrightnessMappingStrategy brightnessMappingStrategy;
        public final DisplayPowerController.Injector injector;
        public final DisplayDeviceConfig config;

        DisplayPowerControllerHolder(DisplayPowerController dpc, LogicalDisplay display,
                DisplayPowerState displayPowerState, BrightnessSetting brightnessSetting,
                DualRampAnimator<DisplayPowerState> animator,
                AutomaticBrightnessController automaticBrightnessController,
                WakelockController wakelockController,
                ScreenOffBrightnessSensorController screenOffBrightnessSensorController,
                HighBrightnessModeController hbmController,
                HdrClamper hdrClamper,
                BrightnessClamperController clamperController,
                HighBrightnessModeMetadata hbmMetadata,
                BrightnessMappingStrategy brightnessMappingStrategy,
                DisplayPowerController.Injector injector,
                DisplayDeviceConfig config) {
            this.dpc = dpc;
            this.display = display;
            this.displayPowerState = displayPowerState;
            this.brightnessSetting = brightnessSetting;
            this.animator = animator;
            this.automaticBrightnessController = automaticBrightnessController;
            this.wakelockController = wakelockController;
            this.screenOffBrightnessSensorController = screenOffBrightnessSensorController;
            this.hbmController = hbmController;
            this.hdrClamper = hdrClamper;
            this.clamperController = clamperController;
            this.hbmMetadata = hbmMetadata;
            this.brightnessMappingStrategy = brightnessMappingStrategy;
            this.injector = injector;
            this.config = config;
        }
    }

    private class TestInjector extends DisplayPowerController.Injector {
        private final DisplayPowerState mDisplayPowerState;
        private final DualRampAnimator<DisplayPowerState> mAnimator;
        private final AutomaticBrightnessController mAutomaticBrightnessController;
        private final WakelockController mWakelockController;
        private final BrightnessMappingStrategy mBrightnessMappingStrategy;
        private final ScreenOffBrightnessSensorController mScreenOffBrightnessSensorController;
        private final HighBrightnessModeController mHighBrightnessModeController;

        private final NormalBrightnessModeController mNormalBrightnessModeController;

        private final HdrClamper mHdrClamper;

        private final BrightnessClamperController mClamperController;

        private final DisplayManagerFlags mFlags;

        TestInjector(DisplayPowerState dps, DualRampAnimator<DisplayPowerState> animator,
                AutomaticBrightnessController automaticBrightnessController,
                WakelockController wakelockController,
                BrightnessMappingStrategy brightnessMappingStrategy,
                ScreenOffBrightnessSensorController screenOffBrightnessSensorController,
                HighBrightnessModeController highBrightnessModeController,
                NormalBrightnessModeController normalBrightnessModeController,
                HdrClamper hdrClamper,
                BrightnessClamperController clamperController,
                DisplayManagerFlags flags) {
            mDisplayPowerState = dps;
            mAnimator = animator;
            mAutomaticBrightnessController = automaticBrightnessController;
            mWakelockController = wakelockController;
            mBrightnessMappingStrategy = brightnessMappingStrategy;
            mScreenOffBrightnessSensorController = screenOffBrightnessSensorController;
            mHighBrightnessModeController = highBrightnessModeController;
            mNormalBrightnessModeController = normalBrightnessModeController;
            mHdrClamper = hdrClamper;
            mClamperController = clamperController;
            mFlags = flags;
        }

        @Override
        DisplayPowerController.Clock getClock() {
            return mClock::now;
        }

        @Override
        DisplayPowerState getDisplayPowerState(DisplayBlanker blanker, ColorFade colorFade,
                int displayId, int displayState) {
            return mDisplayPowerState;
        }

        @Override
        DualRampAnimator<DisplayPowerState> getDualRampAnimator(DisplayPowerState dps,
                FloatProperty<DisplayPowerState> firstProperty,
                FloatProperty<DisplayPowerState> secondProperty) {
            return mAnimator;
        }

        @Override
        WakelockController getWakelockController(int displayId,
                DisplayPowerCallbacks displayPowerCallbacks) {
            return mWakelockController;
        }

        @Override
        DisplayPowerProximityStateController getDisplayPowerProximityStateController(
                WakelockController wakelockController, DisplayDeviceConfig displayDeviceConfig,
                Looper looper, Runnable nudgeUpdatePowerState, int displayId,
                SensorManager sensorManager) {
            return new DisplayPowerProximityStateController(wakelockController,
                    displayDeviceConfig, looper, nudgeUpdatePowerState, displayId,
                    sensorManager,
                    new DisplayPowerProximityStateController.Injector() {
                        @Override
                        DisplayPowerProximityStateController.Clock createClock() {
                            return mClock::now;
                        }
                    });
        }

        @Override
        AutomaticBrightnessController getAutomaticBrightnessController(
                AutomaticBrightnessController.Callbacks callbacks, Looper looper,
                SensorManager sensorManager, Sensor lightSensor,
                SparseArray<BrightnessMappingStrategy> brightnessMappingStrategyMap,
                int lightSensorWarmUpTime, float brightnessMin, float brightnessMax,
                float dozeScaleFactor, int lightSensorRate, int initialLightSensorRate,
                long brighteningLightDebounceConfig, long darkeningLightDebounceConfig,
                long brighteningLightDebounceConfigIdle, long darkeningLightDebounceConfigIdle,
                boolean resetAmbientLuxAfterWarmUpConfig,
                HysteresisLevels ambientBrightnessThresholds,
                HysteresisLevels screenBrightnessThresholds,
                HysteresisLevels ambientBrightnessThresholdsIdle,
                HysteresisLevels screenBrightnessThresholdsIdle, Context context,
                BrightnessRangeController brightnessRangeController,
                BrightnessThrottler brightnessThrottler, int ambientLightHorizonShort,
                int ambientLightHorizonLong, float userLux, float userNits,
                BrightnessClamperController brightnessClamperController,
                DisplayManagerFlags displayManagerFlags) {
            return mAutomaticBrightnessController;
        }

        @Override
        BrightnessMappingStrategy getDefaultModeBrightnessMapper(Context context,
                DisplayDeviceConfig displayDeviceConfig,
                DisplayWhiteBalanceController displayWhiteBalanceController) {
            return mBrightnessMappingStrategy;
        }

        @Override
        ScreenOffBrightnessSensorController getScreenOffBrightnessSensorController(
                SensorManager sensorManager, Sensor lightSensor, Handler handler,
                ScreenOffBrightnessSensorController.Clock clock, int[] sensorValueToLux,
                BrightnessMappingStrategy brightnessMapper) {
            return mScreenOffBrightnessSensorController;
        }

        @Override
        HighBrightnessModeController getHighBrightnessModeController(Handler handler, int width,
                int height, IBinder displayToken, String displayUniqueId, float brightnessMin,
                float brightnessMax, DisplayDeviceConfig.HighBrightnessModeData hbmData,
                HighBrightnessModeController.HdrBrightnessDeviceConfig hdrBrightnessCfg,
                Runnable hbmChangeCallback, HighBrightnessModeMetadata hbmMetadata,
                Context context) {
            return mHighBrightnessModeController;
        }

        @Override
        BrightnessRangeController getBrightnessRangeController(
                HighBrightnessModeController hbmController, Runnable modeChangeCallback,
                DisplayDeviceConfig displayDeviceConfig, Handler handler,
                DisplayManagerFlags flags, IBinder displayToken, DisplayDeviceInfo info) {
            return new BrightnessRangeController(hbmController, modeChangeCallback,
                    displayDeviceConfig, mNormalBrightnessModeController, mHdrClamper,
                    mFlags, displayToken, info);
        }

        @Override
        BrightnessClamperController getBrightnessClamperController(Handler handler,
                BrightnessClamperController.ClamperChangeListener clamperChangeListener,
                BrightnessClamperController.DisplayDeviceData data, Context context,
                DisplayManagerFlags flags, SensorManager sensorManager) {
            return mClamperController;
        }

        @Override
        DisplayWhiteBalanceController getDisplayWhiteBalanceController(Handler handler,
                SensorManager sensorManager, Resources resources) {
            return mDisplayWhiteBalanceControllerMock;
        }
    }
}
