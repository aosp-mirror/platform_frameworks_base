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
import static org.mockito.Mockito.mock;
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
    private static final int DISPLAY_ID = Display.DEFAULT_DISPLAY;
    private static final String UNIQUE_DISPLAY_ID = "unique_id_test123";
    private static final int FOLLOWER_DISPLAY_ID = DISPLAY_ID + 1;
    private static final String FOLLOWER_UNIQUE_DISPLAY_ID = "unique_id_456";
    private static final int SECOND_FOLLOWER_DISPLAY_ID = FOLLOWER_DISPLAY_ID + 1;
    private static final String SECOND_FOLLOWER_UNIQUE_DISPLAY_ID = "unique_id_789";

    private MockitoSession mSession;
    private OffsettableClock mClock;
    private TestLooper mTestLooper;
    private Handler mHandler;
    private Context mContextSpy;
    private DisplayPowerController mDpc;
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
    @Mock
    private AutomaticBrightnessController mAutomaticBrightnessControllerMock;
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

        mProxSensor = setUpProxSensor();

        TestInjector injector = new TestInjector(mDisplayPowerStateMock, mDualRampAnimatorMock,
                mAutomaticBrightnessControllerMock, mBrightnessMapperMock, mHysteresisLevelsMock);

        mDpc = new DisplayPowerController(
                mContextSpy, injector, mDisplayPowerCallbacksMock, mHandler,
                mSensorManagerMock, mDisplayBlankerMock, mLogicalDisplayMock,
                mBrightnessTrackerMock, mBrightnessSettingMock, () -> {
        }, mHighBrightnessModeMetadataMock);
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
        DisplayPowerControllerHolder followerDpc =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_DISPLAY_ID);

        when(mDisplayPowerStateMock.getScreenState()).thenReturn(Display.STATE_ON);
        // send a display power request
        DisplayPowerRequest dpr = new DisplayPowerRequest();
        dpr.policy = DisplayPowerRequest.POLICY_BRIGHT;
        dpr.useProximitySensor = true;
        followerDpc.dpc.requestPowerState(dpr, false /* waitForNegativeProximity */);

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
        DisplayPowerControllerHolder followerDpc =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_DISPLAY_ID);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        followerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mBrightnessSettingMock).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mDpc.addDisplayBrightnessFollower(followerDpc.dpc);

        // Test different float scale values
        float leadBrightness = 0.3f;
        float followerBrightness = 0.4f;
        float nits = 300;
        when(mAutomaticBrightnessControllerMock.convertToNits(leadBrightness)).thenReturn(nits);
        when(followerDpc.automaticBrightnessController.convertToFloatScale(nits))
                .thenReturn(followerBrightness);
        when(mBrightnessSettingMock.getBrightness()).thenReturn(leadBrightness);
        listener.onBrightnessChanged(leadBrightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(leadBrightness), anyFloat(), anyFloat());
        verify(followerDpc.animator).animateTo(eq(followerBrightness), anyFloat(),
                anyFloat());

        clearInvocations(mDualRampAnimatorMock, followerDpc.animator);

        // Test the same float scale value
        float brightness = 0.6f;
        nits = 600;
        when(mAutomaticBrightnessControllerMock.convertToNits(brightness)).thenReturn(nits);
        when(followerDpc.automaticBrightnessController.convertToFloatScale(nits))
                .thenReturn(brightness);
        when(mBrightnessSettingMock.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
        verify(followerDpc.animator).animateTo(eq(brightness), anyFloat(), anyFloat());
    }

    @Test
    public void testDisplayBrightnessFollowers_FollowerDoesNotSupportNits() {
        DisplayPowerControllerHolder followerDpc =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_DISPLAY_ID);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        followerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mBrightnessSettingMock).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mDpc.addDisplayBrightnessFollower(followerDpc.dpc);

        float brightness = 0.3f;
        when(mAutomaticBrightnessControllerMock.convertToNits(brightness)).thenReturn(300f);
        when(followerDpc.automaticBrightnessController.convertToFloatScale(anyFloat()))
                .thenReturn(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mBrightnessSettingMock.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
        verify(followerDpc.animator).animateTo(eq(brightness), anyFloat(), anyFloat());
    }

    @Test
    public void testDisplayBrightnessFollowers_LeadDpcDoesNotSupportNits() {
        DisplayPowerControllerHolder followerDpc =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_DISPLAY_ID);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        followerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mBrightnessSettingMock).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mDpc.addDisplayBrightnessFollower(followerDpc.dpc);

        float brightness = 0.3f;
        when(mAutomaticBrightnessControllerMock.convertToNits(anyFloat())).thenReturn(-1f);
        when(mBrightnessSettingMock.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
        verify(followerDpc.animator).animateTo(eq(brightness), anyFloat(), anyFloat());
    }

    @Test
    public void testDisplayBrightnessFollowers_NeitherDpcSupportsNits() {
        DisplayPowerControllerHolder followerDpc =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_DISPLAY_ID);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        followerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mBrightnessSettingMock).registerListener(listenerCaptor.capture());
        BrightnessSetting.BrightnessSettingListener listener = listenerCaptor.getValue();

        mDpc.addDisplayBrightnessFollower(followerDpc.dpc);

        float brightness = 0.3f;
        when(mAutomaticBrightnessControllerMock.convertToNits(anyFloat())).thenReturn(-1f);
        when(followerDpc.automaticBrightnessController.convertToFloatScale(anyFloat()))
                .thenReturn(PowerManager.BRIGHTNESS_INVALID_FLOAT);
        when(mBrightnessSettingMock.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
        verify(followerDpc.animator).animateTo(eq(brightness), anyFloat(), anyFloat());
    }

    @Test
    public void testDisplayBrightnessFollowersRemoval() {
        DisplayPowerControllerHolder followerDpc =
                createDisplayPowerController(FOLLOWER_DISPLAY_ID, FOLLOWER_UNIQUE_DISPLAY_ID);
        DisplayPowerControllerHolder secondFollowerDpc =
                createDisplayPowerController(SECOND_FOLLOWER_DISPLAY_ID,
                        SECOND_FOLLOWER_UNIQUE_DISPLAY_ID);

        DisplayPowerRequest dpr = new DisplayPowerRequest();
        mDpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        followerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        secondFollowerDpc.dpc.requestPowerState(dpr, /* waitForNegativeProximity= */ false);
        advanceTime(1); // Run updatePowerState

        ArgumentCaptor<BrightnessSetting.BrightnessSettingListener> listenerCaptor =
                ArgumentCaptor.forClass(BrightnessSetting.BrightnessSettingListener.class);
        verify(mBrightnessSettingMock).registerListener(listenerCaptor.capture());
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
        verify(followerDpc.animator).animateTo(eq(initialFollowerBrightness),
                anyFloat(), anyFloat());


        mDpc.addDisplayBrightnessFollower(followerDpc.dpc);
        mDpc.addDisplayBrightnessFollower(secondFollowerDpc.dpc);
        clearInvocations(followerDpc.animator);

        // Validate both followers are correctly registered and receiving brightness updates
        float brightness = 0.6f;
        float nits = 600;
        when(mAutomaticBrightnessControllerMock.convertToNits(brightness)).thenReturn(nits);
        when(followerDpc.automaticBrightnessController.convertToFloatScale(nits))
                .thenReturn(brightness);
        when(secondFollowerDpc.automaticBrightnessController.convertToFloatScale(nits))
                .thenReturn(brightness);
        when(mBrightnessSettingMock.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
        verify(followerDpc.animator).animateTo(eq(brightness), anyFloat(), anyFloat());
        verify(secondFollowerDpc.animator).animateTo(eq(brightness), anyFloat(), anyFloat());

        clearInvocations(mDualRampAnimatorMock, followerDpc.animator, secondFollowerDpc.animator);

        // Remove the first follower and validate it goes back to its original brightness.
        mDpc.removeDisplayBrightnessFollower(followerDpc.dpc);
        advanceTime(1);
        verify(followerDpc.animator).animateTo(eq(initialFollowerBrightness),
                anyFloat(), anyFloat());
        clearInvocations(followerDpc.animator);

        // Change the brightness of the lead display and validate only the second follower responds
        brightness = 0.7f;
        nits = 700;
        when(mAutomaticBrightnessControllerMock.convertToNits(brightness)).thenReturn(nits);
        when(followerDpc.automaticBrightnessController.convertToFloatScale(nits))
                .thenReturn(brightness);
        when(secondFollowerDpc.automaticBrightnessController.convertToFloatScale(nits))
                .thenReturn(brightness);
        when(mBrightnessSettingMock.getBrightness()).thenReturn(brightness);
        listener.onBrightnessChanged(brightness);
        advanceTime(1); // Send messages, run updatePowerState
        verify(mDualRampAnimatorMock).animateTo(eq(brightness), anyFloat(), anyFloat());
        verify(followerDpc.animator, never()).animateTo(anyFloat(), anyFloat(), anyFloat());
        verify(secondFollowerDpc.animator).animateTo(eq(brightness), anyFloat(), anyFloat());
    }

    private DisplayPowerControllerHolder createDisplayPowerController(int displayId,
            String uniqueId) {
        final DisplayPowerState displayPowerState = mock(DisplayPowerState.class);
        final DualRampAnimator<DisplayPowerState> animator = mock(DualRampAnimator.class);
        final AutomaticBrightnessController automaticBrightnessController =
                mock(AutomaticBrightnessController.class);
        final BrightnessMappingStrategy brightnessMappingStrategy =
                mock(BrightnessMappingStrategy.class);
        final HysteresisLevels hysteresisLevels = mock(HysteresisLevels.class);

        DisplayPowerController.Injector injector = new TestInjector(displayPowerState, animator,
                automaticBrightnessController, brightnessMappingStrategy, hysteresisLevels);

        final LogicalDisplay display = mock(LogicalDisplay.class);
        final DisplayDevice device = mock(DisplayDevice.class);
        final HighBrightnessModeMetadata hbmMetadata = mock(HighBrightnessModeMetadata.class);
        final BrightnessSetting brightnessSetting = mock(BrightnessSetting.class);
        final DisplayDeviceConfig config = mock(DisplayDeviceConfig.class);

        setUpDisplay(displayId, uniqueId, display, device, config);

        final DisplayPowerController dpc = new DisplayPowerController(
                mContextSpy, injector, mDisplayPowerCallbacksMock, mHandler,
                mSensorManagerMock, mDisplayBlankerMock, display,
                mBrightnessTrackerMock, brightnessSetting, () -> {},
                hbmMetadata);

        return new DisplayPowerControllerHolder(dpc, brightnessSetting, animator,
                automaticBrightnessController);
    }

    /**
     * A class for holding a DisplayPowerController under test and all the mocks specifically
     * related to it.
     */
    private static class DisplayPowerControllerHolder {
        public final DisplayPowerController dpc;
        public final BrightnessSetting brightnessSetting;
        public final DualRampAnimator<DisplayPowerState> animator;
        public final AutomaticBrightnessController automaticBrightnessController;

        DisplayPowerControllerHolder(DisplayPowerController dpc,
                BrightnessSetting brightnessSetting, DualRampAnimator<DisplayPowerState> animator,
                AutomaticBrightnessController automaticBrightnessController) {
            this.dpc = dpc;
            this.brightnessSetting = brightnessSetting;
            this.animator = animator;
            this.automaticBrightnessController = automaticBrightnessController;
        }
    }

    private class TestInjector extends DisplayPowerController.Injector {
        private final DisplayPowerState mDisplayPowerState;
        private final DualRampAnimator<DisplayPowerState> mAnimator;
        private final AutomaticBrightnessController mAutomaticBrightnessController;
        private final BrightnessMappingStrategy mBrightnessMappingStrategy;
        private final HysteresisLevels mHysteresisLevels;

        TestInjector(DisplayPowerState dps, DualRampAnimator<DisplayPowerState> animator,
                AutomaticBrightnessController automaticBrightnessController,
                BrightnessMappingStrategy brightnessMappingStrategy,
                HysteresisLevels hysteresisLevels) {
            mDisplayPowerState = dps;
            mAnimator = animator;
            mAutomaticBrightnessController = automaticBrightnessController;
            mBrightnessMappingStrategy = brightnessMappingStrategy;
            mHysteresisLevels = hysteresisLevels;
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
            return mAutomaticBrightnessController;
        }

        @Override
        BrightnessMappingStrategy getInteractiveModeBrightnessMapper(Resources resources,
                DisplayDeviceConfig displayDeviceConfig,
                DisplayWhiteBalanceController displayWhiteBalanceController) {
            return mBrightnessMappingStrategy;
        }

        @Override
        HysteresisLevels getHysteresisLevels(float[] brighteningThresholdsPercentages,
                float[] darkeningThresholdsPercentages, float[] brighteningThresholdLevels,
                float[] darkeningThresholdLevels, float minDarkeningThreshold,
                float minBrighteningThreshold) {
            return mHysteresisLevels;
        }

        @Override
        HysteresisLevels getHysteresisLevels(float[] brighteningThresholdsPercentages,
                float[] darkeningThresholdsPercentages, float[] brighteningThresholdLevels,
                float[] darkeningThresholdLevels, float minDarkeningThreshold,
                float minBrighteningThreshold, boolean potentialOldBrightnessRange) {
            return mHysteresisLevels;
        }
    }
}
