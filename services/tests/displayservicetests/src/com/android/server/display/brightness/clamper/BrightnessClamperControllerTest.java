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

package com.android.server.display.brightness.clamper;

import static android.view.Display.STATE_OFF;
import static android.view.Display.STATE_ON;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.provider.DeviceConfig;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.clamper.BrightnessClamperController.ModifiersAggregatedState;
import com.android.server.display.config.SensorData;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@SmallTest
public class BrightnessClamperControllerTest {
    private static final float FLOAT_TOLERANCE = 0.001f;
    private static final int DISPLAY_ID = 2;

    private final OffsettableClock mClock = new OffsettableClock();
    private final TestHandler mTestHandler = new TestHandler(null, mClock);

    @Rule
    public final TestableContext mMockContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());
    @Mock
    private BrightnessClamperController.ClamperChangeListener mMockExternalListener;
    @Mock
    private SensorManager mSensorManager;

    @Mock
    private BrightnessClamperController.DisplayDeviceData mMockDisplayDeviceData;
    @Mock
    private SensorData mMockSensorData;
    @Mock
    private DeviceConfigParameterProvider mMockDeviceConfigParameterProvider;
    @Mock
    private LightSensorController mMockLightSensorController;
    @Mock
    private DisplayManagerFlags mFlags;
    @Mock
    private BrightnessModifier mMockModifier;
    @Mock
    private TestStatefulModifier mMockStatefulModifier;
    @Mock
    private TestDisplayListenerModifier mMockDisplayListenerModifier;
    @Mock
    private TestDeviceConfigListenerModifier mMockDeviceConfigListenerModifier;
    @Mock
    private DisplayManagerInternal.DisplayPowerRequest mMockRequest;

    @Mock
    private DeviceConfig.Properties mMockProperties;
    private BrightnessClamperController mClamperController;
    private DisplayBrightnessState mDisplayBrightnessState;
    private TestInjector mTestInjector;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestInjector = new TestInjector(
                List.of(mMockModifier, mMockStatefulModifier,
                        mMockDisplayListenerModifier, mMockDeviceConfigListenerModifier));
        when(mMockDisplayDeviceData.getDisplayId()).thenReturn(DISPLAY_ID);
        when(mMockDisplayDeviceData.getAmbientLightSensor()).thenReturn(mMockSensorData);

        mClamperController = createBrightnessClamperController();
        mDisplayBrightnessState = DisplayBrightnessState.builder().build();
    }

    @Test
    public void testConstructor_AddsOnPropertiesChangeListener() {
        verify(mMockDeviceConfigParameterProvider).addOnPropertiesChangedListener(any(), any());
    }

    @Test
    public void testConstructor_ConfiguresLightSensorController() {
        verify(mMockLightSensorController).configure(mMockSensorData, DISPLAY_ID);
    }

    @Test
    public void testConstructor_doesNotStartsLightSensorController() {
        when(mMockModifier.shouldListenToLightSensor()).thenReturn(true);

        mClamperController = createBrightnessClamperController();

        verify(mMockLightSensorController, never()).restart();
    }

    @Test
    public void testStop_RemovesOnPropertiesChangeListener() {
        ArgumentCaptor<DeviceConfig.OnPropertiesChangedListener> captor = ArgumentCaptor.forClass(
                DeviceConfig.OnPropertiesChangedListener.class);
        verify(mMockDeviceConfigParameterProvider)
                .addOnPropertiesChangedListener(any(), captor.capture());
        mClamperController.stop();

        verify(mMockDeviceConfigParameterProvider)
                .removeOnPropertiesChangedListener(captor.getValue());
    }

    @Test
    public void testDelegatesPropertiesChangeToDeviceConfigLisener() {
        ArgumentCaptor<DeviceConfig.OnPropertiesChangedListener> captor = ArgumentCaptor.forClass(
                DeviceConfig.OnPropertiesChangedListener.class);
        verify(mMockDeviceConfigParameterProvider)
                .addOnPropertiesChangedListener(any(), captor.capture());

        captor.getValue().onPropertiesChanged(mMockProperties);

        verify(mMockDeviceConfigListenerModifier).onDeviceConfigChanged();
    }

    @Test
    public void testOnDisplayChanged_DelegatesToDisplayListeners() {
        mClamperController.onDisplayChanged(mMockDisplayDeviceData);

        verify(mMockDisplayListenerModifier).onDisplayChanged(mMockDisplayDeviceData);
    }

    @Test
    public void testOnDisplayChanged_doesNotRestartLightSensor() {
        mClamperController.clamp(mDisplayBrightnessState, mMockRequest, 0.1f,
                false, STATE_ON);
        reset(mMockLightSensorController);

        mClamperController.onDisplayChanged(mMockDisplayDeviceData);

        verify(mMockLightSensorController, never()).restart();
        verify(mMockLightSensorController).stop();
    }

    @Test
    public void testOnDisplayChanged_restartsLightSensor() {
        when(mMockModifier.shouldListenToLightSensor()).thenReturn(true);
        mClamperController.clamp(mDisplayBrightnessState, mMockRequest, 0.1f,
                false, STATE_ON);
        reset(mMockLightSensorController);

        mClamperController.onDisplayChanged(mMockDisplayDeviceData);

        verify(mMockLightSensorController, never()).stop();
        verify(mMockLightSensorController).restart();
    }

    @Test
    public void testOnDisplayChanged_doesNotRestartLightSensor_screenOff() {
        when(mMockModifier.shouldListenToLightSensor()).thenReturn(true);
        mClamperController.clamp(mDisplayBrightnessState, mMockRequest, 0.1f,
                false, STATE_OFF);
        reset(mMockLightSensorController);

        mClamperController.onDisplayChanged(mMockDisplayDeviceData);

        verify(mMockLightSensorController, never()).restart();
        verify(mMockLightSensorController).stop();
    }

    @Test
    public void testClamp_AppliesModifier() {
        float initialBrightness = 0.2f;
        boolean initialSlowChange = true;
        mClamperController.clamp(mDisplayBrightnessState, mMockRequest, initialBrightness,
                initialSlowChange, STATE_ON);

        verify(mMockModifier).apply(eq(mMockRequest), any());
        verify(mMockDisplayListenerModifier).apply(eq(mMockRequest), any());
        verify(mMockStatefulModifier).apply(eq(mMockRequest), any());
    }

    @Test
    public void testClamp_restartsLightSensor() {
        float initialBrightness = 0.2f;
        boolean initialSlowChange = true;
        when(mMockModifier.shouldListenToLightSensor()).thenReturn(true);
        mClamperController.clamp(mDisplayBrightnessState, mMockRequest, initialBrightness,
                initialSlowChange, STATE_ON);

        verify(mMockLightSensorController).restart();
    }

    @Test
    public void testClamp_stopsLightSensor() {
        float initialBrightness = 0.2f;
        boolean initialSlowChange = true;
        clearInvocations(mMockLightSensorController);
        mClamperController.clamp(mDisplayBrightnessState, mMockRequest, initialBrightness,
                initialSlowChange, STATE_OFF);

        verify(mMockLightSensorController).stop();
    }

    @Test
    public void testClamp_activeClamperApplied_confirmBrightnessOverrideStateReturned() {
        float initialBrightness = 0.8f;
        boolean initialSlowChange = false;
        mTestInjector.mCapturedChangeListener.onChanged();
        mTestHandler.flush();

        mDisplayBrightnessState = DisplayBrightnessState.builder().setBrightnessReason(
                BrightnessReason.REASON_OVERRIDE).build();

        DisplayBrightnessState state = mClamperController.clamp(mDisplayBrightnessState,
                mMockRequest, initialBrightness, initialSlowChange, STATE_ON);

        assertEquals(BrightnessReason.REASON_OVERRIDE, state.getBrightnessReason().getReason());
    }

    @Test
    public void testAmbientLuxChanges() {
        mTestInjector.mCapturedLightSensorListener.onAmbientLuxChange(50);

        verify(mMockModifier).setAmbientLux(50);
    }

    @Test
    public void testStop() {
        clearInvocations(mMockLightSensorController);
        mClamperController.stop();
        verify(mMockLightSensorController).stop();
        verify(mMockModifier).stop();
    }

    @Test
    public void test_doesNotNotifyExternalListener_aggregatedStateNotChanged() {
        mTestInjector.mCapturedChangeListener.onChanged();
        mTestHandler.flush();

        verify(mMockExternalListener, never()).onChanged();
    }

    @Test
    public void test_notifiesExternalListener_aggregatedStateChanged() {
        doAnswer((invocation) -> {
            ModifiersAggregatedState argument = invocation.getArgument(0);
            // we need to do changes in AggregatedState to trigger onChange
            argument.mMaxHdrBrightness = 0.5f;
            return null;
        }).when(mMockStatefulModifier).applyStateChange(any());
        mTestInjector.mCapturedChangeListener.onChanged();
        mTestHandler.flush();

        verify(mMockExternalListener).onChanged();
    }

    @Test
    public void test_doesNotScheduleRecalculateBeforeStart() {
        mTestInjector = new TestInjector(List.of()) {
            @Override
            List<BrightnessStateModifier> getModifiers(DisplayManagerFlags flags, Context context,
                    Handler handler, BrightnessClamperController.ClamperChangeListener listener,
                    BrightnessClamperController.DisplayDeviceData displayDeviceData,
                    float currentBrightness) {
                listener.onChanged();
                return super.getModifiers(flags, context, handler, listener, displayDeviceData,
                        currentBrightness);
            }
        };
        mClamperController = createBrightnessClamperController();

        assertThat(mTestHandler.getPendingMessages()).isEmpty();
    }

    private BrightnessClamperController createBrightnessClamperController() {
        return new BrightnessClamperController(mTestInjector, mTestHandler, mMockExternalListener,
                mMockDisplayDeviceData, mMockContext, mFlags, mSensorManager, 0);
    }

    interface TestDisplayListenerModifier extends BrightnessStateModifier,
            BrightnessClamperController.DisplayDeviceDataListener {
    }

    interface TestStatefulModifier extends BrightnessStateModifier,
            BrightnessClamperController.StatefulModifier {
    }

    interface TestDeviceConfigListenerModifier extends  BrightnessStateModifier,
            BrightnessClamperController.DeviceConfigListener {

    }

    private class TestInjector extends BrightnessClamperController.Injector {

        private final List<BrightnessStateModifier> mModifiers;
        private BrightnessClamperController.ClamperChangeListener mCapturedChangeListener;
        private LightSensorController.LightSensorListener mCapturedLightSensorListener;

        private TestInjector(List<BrightnessStateModifier> modifiers) {
            mModifiers = modifiers;
        }

        @Override
        DeviceConfigParameterProvider getDeviceConfigParameterProvider() {
            return mMockDeviceConfigParameterProvider;
        }

        @Override
        List<BrightnessStateModifier> getModifiers(DisplayManagerFlags flags, Context context,
                Handler handler, BrightnessClamperController.ClamperChangeListener listener,
                BrightnessClamperController.DisplayDeviceData displayDeviceData,
                float currentBrightness) {
            mCapturedChangeListener = listener;
            return mModifiers;
        }

        @Override
        LightSensorController getLightSensorController(SensorManager sensorManager, Context context,
                LightSensorController.LightSensorListener listener, Handler handler) {
            mCapturedLightSensorListener = listener;
            return mMockLightSensorController;
        }
    }
}
