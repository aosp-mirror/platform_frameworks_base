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

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
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
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.test.TestLooper;
import android.util.FloatProperty;
import android.view.Display;
import android.view.DisplayInfo;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.LocalServices;
import com.android.server.am.BatteryStatsService;
import com.android.server.display.RampAnimator.DualRampAnimator;
import com.android.server.display.color.ColorDisplayService;
import com.android.server.display.whitebalance.DisplayWhiteBalanceController;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.testutils.OffsettableClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

import java.util.List;


@SmallTest
@RunWith(AndroidJUnit4.class)
public final class DisplayPowerControllerTest {
    private static final String UNIQUE_DISPLAY_ID = "unique_id_test123";
    private static final int DISPLAY_ID = Display.DEFAULT_DISPLAY;
    private static final int FOLLOWER_DISPLAY_ID = Display.DEFAULT_DISPLAY + 1;

    private MockitoSession mSession;
    private OffsettableClock mClock;
    private TestLooper mTestLooper;
    private Handler mHandler;
    private DisplayPowerController.Injector mInjector;
    private DisplayPowerController.Injector mFollowerInjector;
    private Context mContextSpy;
    private DisplayPowerController mDpc;
    private DisplayPowerController mFollowerDpc;
    private Sensor mProxSensor;

    @Mock
    private DisplayPowerCallbacks mDisplayPowerCallbacksMock;
    @Mock
    private SensorManager mSensorManagerMock;
    @Mock
    private DisplayBlanker mDisplayBlankerMock;
    @Mock
    private LogicalDisplay mLogicalDisplayMock;
    @Mock
    private LogicalDisplay mFollowerLogicalDisplayMock;
    @Mock
    private DisplayDevice mDisplayDeviceMock;
    @Mock
    private DisplayDevice mFollowerDisplayDeviceMock;
    @Mock
    private HighBrightnessModeMetadata mHighBrightnessModeMetadataMock;
    @Mock
    private HighBrightnessModeMetadata mFollowerHighBrightnessModeMetadataMock;
    @Mock
    private BrightnessTracker mBrightnessTrackerMock;
    @Mock
    private BrightnessSetting mBrightnessSettingMock;
    @Mock
    private BrightnessSetting mFollowerBrightnessSettingMock;
    @Mock
    private WindowManagerPolicy mWindowManagerPolicyMock;
    @Mock
    private PowerManager mPowerManagerMock;
    @Mock
    private Resources mResourcesMock;
    @Mock
    private DisplayDeviceConfig mDisplayDeviceConfigMock;
    @Mock
    private DisplayDeviceConfig mFollowerDisplayDeviceConfigMock;
    @Mock
    private DisplayPowerState mDisplayPowerStateMock;
    @Mock
    private DualRampAnimator<DisplayPowerState> mDualRampAnimatorMock;
    @Mock
    private DualRampAnimator<DisplayPowerState> mFollowerDualRampAnimatorMock;
    @Mock
    private AutomaticBrightnessController mAutomaticBrightnessControllerMock;
    @Mock
    private AutomaticBrightnessController mFollowerAutomaticBrightnessControllerMock;
    @Mock
    private BrightnessMappingStrategy mBrightnessMapperMock;
    @Mock
    private HysteresisLevels mHysteresisLevelsMock;
    @Mock
    private ColorDisplayService.ColorDisplayServiceInternal mCdsiMock;

    @Captor
    private ArgumentCaptor<SensorEventListener> mSensorEventListenerCaptor;

    @Before
    public void setUp() throws Exception {
        mSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(SystemProperties.class)
                .spyStatic(LocalServices.class)
                .spyStatic(BatteryStatsService.class)
                .startMocking();
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

            @Override
            AutomaticBrightnessController getAutomaticBrightnessController(
                    AutomaticBrightnessController.Callbacks callbacks, Looper looper,
                    SensorManager sensorManager, Sensor lightSensor,
                    BrightnessMappingStrategy interactiveModeBrightnessMapper,
                    int lightSensorWarmUpTime, float brightnessMin, float brightnessMax,
                    float dozeScaleFactor, int lightSensorRate, int initialLightSensorRate,
                    long brighteningLightDebounceConfig, long darkeningLightDebounceConfig,
                    boolean resetAmbientLuxAfterWarmUpConfig,
                    HysteresisLevels ambientBrightnessThresholds,
                    HysteresisLevels screenBrightnessThresholds,
                    HysteresisLevels ambientBrightnessThresholdsIdle,
                    HysteresisLevels screenBrightnessThresholdsIdle, Context context,
                    HighBrightnessModeController hbmController,
                    BrightnessThrottler brightnessThrottler,
                    BrightnessMappingStrategy idleModeBrightnessMapper,
                    int ambientLightHorizonShort, int ambientLightHorizonLong, float userLux,
                    float userBrightness) {
                return mAutomaticBrightnessControllerMock;
            }

            @Override
            BrightnessMappingStrategy getInteractiveModeBrightnessMapper(Resources resources,
                    DisplayDeviceConfig displayDeviceConfig,
                    DisplayWhiteBalanceController displayWhiteBalanceController) {
                return mBrightnessMapperMock;
            }

            @Override
            HysteresisLevels getHysteresisLevels(float[] brighteningThresholdsPercentages,
                    float[] darkeningThresholdsPercentages, float[] brighteningThresholdLevels,
                    float[] darkeningThresholdLevels, float minDarkeningThreshold,
                    float minBrighteningThreshold) {
                return mHysteresisLevelsMock;
            }

            @Override
            HysteresisLevels getHysteresisLevels(float[] brighteningThresholdsPercentages,
                    float[] darkeningThresholdsPercentages, float[] brighteningThresholdLevels,
                    float[] darkeningThresholdLevels, float minDarkeningThreshold,
                    float minBrighteningThreshold, boolean potentialOldBrightnessRange) {
                return mHysteresisLevelsMock;
            }
        };
        mFollowerInjector = new DisplayPowerController.Injector() {
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
                return mFollowerDualRampAnimatorMock;
            }

            @Override
            AutomaticBrightnessController getAutomaticBrightnessController(
                    AutomaticBrightnessController.Callbacks callbacks, Looper looper,
                    SensorManager sensorManager, Sensor lightSensor,
                    BrightnessMappingStrategy interactiveModeBrightnessMapper,
                    int lightSensorWarmUpTime, float brightnessMin, float brightnessMax,
                    float dozeScaleFactor, int lightSensorRate, int initialLightSensorRate,
                    long brighteningLightDebounceConfig, long darkeningLightDebounceConfig,
                    boolean resetAmbientLuxAfterWarmUpConfig,
                    HysteresisLevels ambientBrightnessThresholds,
                    HysteresisLevels screenBrightnessThresholds,
                    HysteresisLevels ambientBrightnessThresholdsIdle,
                    HysteresisLevels screenBrightnessThresholdsIdle, Context context,
                    HighBrightnessModeController hbmController,
                    BrightnessThrottler brightnessThrottler,
                    BrightnessMappingStrategy idleModeBrightnessMapper,
                    int ambientLightHorizonShort, int ambientLightHorizonLong, float userLux,
                    float userBrightness) {
                return mFollowerAutomaticBrightnessControllerMock;
            }

            @Override
            BrightnessMappingStrategy getInteractiveModeBrightnessMapper(Resources resources,
                    DisplayDeviceConfig displayDeviceConfig,
                    DisplayWhiteBalanceController displayWhiteBalanceController) {
                return mBrightnessMapperMock;
            }

            @Override
            HysteresisLevels getHysteresisLevels(float[] brighteningThresholdsPercentages,
                    float[] darkeningThresholdsPercentages, float[] brighteningThresholdLevels,
                    float[] darkeningThresholdLevels, float minDarkeningThreshold,
                    float minBrighteningThreshold) {
                return mHysteresisLevelsMock;
            }

            @Override
            HysteresisLevels getHysteresisLevels(float[] brighteningThresholdsPercentages,
                    float[] darkeningThresholdsPercentages, float[] brighteningThresholdLevels,
                    float[] darkeningThresholdLevels, float minDarkeningThreshold,
                    float minBrighteningThreshold, boolean potentialOldBrightnessRange) {
                return mHysteresisLevelsMock;
            }
        };

        addLocalServiceMock(WindowManagerPolicy.class, mWindowManagerPolicyMock);

        when(mContextSpy.getSystemService(eq(PowerManager.class))).thenReturn(mPowerManagerMock);
        when(mContextSpy.getResources()).thenReturn(mResourcesMock);

        doAnswer((Answer<Void>) invocationOnMock -> null).when(() ->
                SystemProperties.set(anyString(), any()));
        doAnswer((Answer<ColorDisplayService.ColorDisplayServiceInternal>) invocationOnMock ->
                mCdsiMock).when(() -> LocalServices.getService(
                ColorDisplayService.ColorDisplayServiceInternal.class));
        doAnswer((Answer<Void>) invocationOnMock -> null).when(BatteryStatsService::getService);

        setUpDisplay(DISPLAY_ID, UNIQUE_DISPLAY_ID, mLogicalDisplayMock, mDisplayDeviceMock,
                mDisplayDeviceConfigMock);
        setUpDisplay(FOLLOWER_DISPLAY_ID, UNIQUE_DISPLAY_ID, mFollowerLogicalDisplayMock,
                mFollowerDisplayDeviceMock, mFollowerDisplayDeviceConfigMock);

        mProxSensor = setUpProxSensor();

        mDpc = new DisplayPowerController(
                mContextSpy, mInjector, mDisplayPowerCallbacksMock, mHandler,
                mSensorManagerMock, mDisplayBlankerMock, mLogicalDisplayMock,
                mBrightnessTrackerMock, mBrightnessSettingMock, () -> {
        }, mHighBrightnessModeMetadataMock);
        mFollowerDpc = new DisplayPowerController(
                mContextSpy, mFollowerInjector, mDisplayPowerCallbacksMock, mHandler,
                mSensorManagerMock, mDisplayBlankerMock, mFollowerLogicalDisplayMock,
                mBrightnessTrackerMock, mFollowerBrightnessSettingMock, () -> {
        }, mFollowerHighBrightnessModeMetadataMock);
    }

    @After
    public void tearDown() {
        mSession.finishMocking();
        LocalServices.removeServiceForTest(WindowManagerPolicy.class);
    }

    @Test
    public void testReleaseProxSuspendBlockersOnExit() throws Exception {
        when(mDisplayPowerStateMock.getScreenState()).thenReturn(Display.STATE_ON);
        // send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        mDpc.requestPowerState(dpr, false /* waitForNegativeProximity */);

        // Run updatePowerState to start listener for the prox sensor
        advanceTime(1);

        SensorEventListener listener = getSensorEventListener(mProxSensor);
        assertNotNull(listener);

        listener.onSensorChanged(TestUtils.createSensorEvent(mProxSensor, 5 /* lux */));
        advanceTime(1);

        // two times, one for unfinished business and one for proximity
        verify(mDisplayPowerCallbacksMock).acquireSuspendBlocker(
                mDpc.getSuspendBlockerUnfinishedBusinessId(DISPLAY_ID));
        verify(mDisplayPowerCallbacksMock).acquireSuspendBlocker(
                mDpc.getSuspendBlockerProxDebounceId(DISPLAY_ID));

        mDpc.stop();
        advanceTime(1);

        // two times, one for unfinished business and one for proximity
        verify(mDisplayPowerCallbacksMock).releaseSuspendBlocker(
                mDpc.getSuspendBlockerUnfinishedBusinessId(DISPLAY_ID));
        verify(mDisplayPowerCallbacksMock).releaseSuspendBlocker(
                mDpc.getSuspendBlockerProxDebounceId(DISPLAY_ID));
    }

    @Test
    public void testProximitySensorListenerNotRegisteredForNonDefaultDisplay() {
        when(mDisplayPowerStateMock.getScreenState()).thenReturn(Display.STATE_ON);
        // send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        mFollowerDpc.requestPowerState(dpr, false /* waitForNegativeProximity */);

        // Run updatePowerState
        advanceTime(1);

        verify(mSensorManagerMock, never()).registerListener(any(SensorEventListener.class),
                eq(mProxSensor), anyInt(), any(Handler.class));
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

    private void setUpDisplay(int displayId, String uniqueId, LogicalDisplay logicalDisplayMock,
            DisplayDevice displayDeviceMock, DisplayDeviceConfig displayDeviceConfigMock) {
        DisplayInfo info = new DisplayInfo();
        DisplayDeviceInfo deviceInfo = new DisplayDeviceInfo();

        when(logicalDisplayMock.getDisplayIdLocked()).thenReturn(displayId);
        when(logicalDisplayMock.getPrimaryDisplayDeviceLocked()).thenReturn(displayDeviceMock);
        when(logicalDisplayMock.getDisplayInfoLocked()).thenReturn(info);
        when(logicalDisplayMock.isEnabledLocked()).thenReturn(true);
        when(logicalDisplayMock.isInTransitionLocked()).thenReturn(false);
        when(displayDeviceMock.getDisplayDeviceInfoLocked()).thenReturn(deviceInfo);
        when(displayDeviceMock.getUniqueId()).thenReturn(uniqueId);
        when(displayDeviceMock.getDisplayDeviceConfig()).thenReturn(displayDeviceConfigMock);
        when(displayDeviceConfigMock.getProximitySensor()).thenReturn(
                new DisplayDeviceConfig.SensorData() {
                    {
                        type = Sensor.STRING_TYPE_PROXIMITY;
                        name = null;
                    }
                });
        when(displayDeviceConfigMock.getNits()).thenReturn(new float[]{2, 500});
        when(displayDeviceConfigMock.isAutoBrightnessAvailable()).thenReturn(true);
        when(displayDeviceConfigMock.getAmbientLightSensor()).thenReturn(
                new DisplayDeviceConfig.SensorData());
        when(displayDeviceConfigMock.getScreenOffBrightnessSensor()).thenReturn(
                new DisplayDeviceConfig.SensorData());
    }

    @Test
    public void testDisplayBrightnessFollowers_BothDpcsSupportNits() {
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        mFollowerDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mBrightnessSettingMock).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mDpc.addDisplayBrightnessFollower(mFollowerDpc);

        // Test different float scale values
        float leadBrightness = 0.3f;
        float followerBrightness = 0.4f;
        float nits = 300;
        when(mAutomaticBrightnessControllerMock.convertToNits(leadBrightness)).thenReturn(nits);
        when(mFollowerAutomaticBrightnessControllerMock.convertToFloatScale(nits))
                .thenReturn(followerBrightness);
        when(mBrightnessSettingMock.getBrightness()).thenReturn(leadBrightness);
        listener.onBrightnessChanged(leadBrightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(leadBrightness), anyFloat(), anyFloat());
        verify(mFollowerDualRampAnimatorMock).animateTo(eq(followerBrightness), anyFloat(),
                anyFloat());

        clearInvocations(mDualRampAnimatorMock, mFollowerDualRampAnimatorMock);

        // Test the same float scale value
        float brightness = 0.6f;
        nits = 600;
        when(mAutomaticBrightnessControllerMock.convertToNits(brightness)).thenReturn(nits);
        when(mFollowerAutomaticBrightnessControllerMock.convertToFloatScale(nits))
                .thenReturn(brightness);
        when(mBrightnessSettingMock.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
        verify(mFollowerDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());

        clearInvocations(mDualRampAnimatorMock, mFollowerDualRampAnimatorMock);

        // Test clear followers
        mDpc.clearDisplayBrightnessFollowers();
        when(mBrightnessSettingMock.getBrightness()).thenReturn(leadBrightness);
        listener.onBrightnessChanged(leadBrightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(leadBrightness), anyFloat(), anyFloat());
        verify(mFollowerDualRampAnimatorMock, never()).animateTo(eq(followerBrightness), anyFloat(),
                anyFloat());
    }

    @Test
    public void testDisplayBrightnessFollowers_FollowerDoesNotSupportNits() {
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        mFollowerDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mBrightnessSettingMock).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mDpc.addDisplayBrightnessFollower(mFollowerDpc);

        float brightness = 0.3f;
        when(mAutomaticBrightnessControllerMock.convertToNits(brightness)).thenReturn(300f);
        when(mFollowerAutomaticBrightnessControllerMock.convertToFloatScale(anyFloat()))
                .thenReturn(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mBrightnessSettingMock.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
        verify(mFollowerDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
    }

    @Test
    public void testDisplayBrightnessFollowers_LeadDpcDoesNotSupportNits() {
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        mFollowerDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mBrightnessSettingMock).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mDpc.addDisplayBrightnessFollower(mFollowerDpc);

        float brightness = 0.3f;
        when(mAutomaticBrightnessControllerMock.convertToNits(anyFloat())).thenReturn(-1f);
        when(mBrightnessSettingMock.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
        verify(mFollowerDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
    }

    @Test
    public void testDisplayBrightnessFollowers_NeitherDpcSupportsNits() {
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        mFollowerDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mBrightnessSettingMock).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mDpc.addDisplayBrightnessFollower(mFollowerDpc);

        float brightness = 0.3f;
        when(mAutomaticBrightnessControllerMock.convertToNits(anyFloat())).thenReturn(-1f);
        when(mFollowerAutomaticBrightnessControllerMock.convertToFloatScale(anyFloat()))
                .thenReturn(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mBrightnessSettingMock.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
        verify(mFollowerDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
    }
}
