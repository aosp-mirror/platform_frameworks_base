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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.DeviceConfig;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.feature.DeviceConfigParameterProvider;
import com.android.server.display.feature.DisplayManagerFlags;
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

    private final TestHandler mTestHandler = new TestHandler(null);

    @Rule
    public final TestableContext mMockContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());
    @Mock
    private BrightnessClamperController.ClamperChangeListener mMockExternalListener;

    @Mock
    private BrightnessClamperController.DisplayDeviceData mMockDisplayDeviceData;
    @Mock
    private DeviceConfigParameterProvider mMockDeviceConfigParameterProvider;
    @Mock
    private BrightnessClamper<BrightnessClamperController.DisplayDeviceData> mMockClamper;
    @Mock
    private DisplayManagerFlags mFlags;
    @Mock
    private BrightnessModifier mMockModifier;
    @Mock
    private DisplayManagerInternal.DisplayPowerRequest mMockRequest;
    @Mock
    private DeviceConfig.Properties mMockProperties;
    private BrightnessClamperController mClamperController;
    private TestInjector mTestInjector;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestInjector = new TestInjector(List.of(mMockClamper), List.of(mMockModifier));
        mClamperController = createBrightnessClamperController();
    }

    @Test
    public void testConstructor_AddsOnPropertiesChangeListener() {
        verify(mMockDeviceConfigParameterProvider).addOnPropertiesChangedListener(any(), any());
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
    public void testDelegatesPropertiesChangeToClamper() {
        ArgumentCaptor<DeviceConfig.OnPropertiesChangedListener> captor = ArgumentCaptor.forClass(
                DeviceConfig.OnPropertiesChangedListener.class);
        verify(mMockDeviceConfigParameterProvider)
                .addOnPropertiesChangedListener(any(), captor.capture());

        captor.getValue().onPropertiesChanged(mMockProperties);

        verify(mMockClamper).onDeviceConfigChanged();
    }

    @Test
    public void testMaxReasonIsNoneOnInit() {
        assertEquals(BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE,
                mClamperController.getBrightnessMaxReason());
    }

    @Test
    public void testOnDisplayChanged_DelegatesToClamper() {
        mClamperController.onDisplayChanged(mMockDisplayDeviceData);

        verify(mMockClamper).onDisplayChanged(mMockDisplayDeviceData);
    }

    @Test
    public void testClamp_AppliesModifier() {
        float initialBrightness = 0.2f;
        boolean initialSlowChange = true;
        mClamperController.clamp(mMockRequest, initialBrightness, initialSlowChange);

        verify(mMockModifier).apply(eq(mMockRequest), any());
    }

    @Test
    public void testClamp_inactiveClamperNotApplied() {
        float initialBrightness = 0.8f;
        boolean initialSlowChange = true;
        float clampedBrightness = 0.6f;
        float customAnimationRate = 0.01f;
        when(mMockClamper.getBrightnessCap()).thenReturn(clampedBrightness);
        when(mMockClamper.getType()).thenReturn(BrightnessClamper.Type.THERMAL);
        when(mMockClamper.getCustomAnimationRate()).thenReturn(customAnimationRate);
        when(mMockClamper.isActive()).thenReturn(false);
        mTestInjector.mCapturedChangeListener.onChanged();
        mTestHandler.flush();

        DisplayBrightnessState state = mClamperController.clamp(mMockRequest, initialBrightness,
                initialSlowChange);

        assertEquals(initialBrightness, state.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(PowerManager.BRIGHTNESS_MAX, state.getMaxBrightness(), FLOAT_TOLERANCE);
        assertEquals(0,
                state.getBrightnessReason().getModifier() & BrightnessReason.MODIFIER_THROTTLED);
        assertEquals(-1, state.getCustomAnimationRate(), FLOAT_TOLERANCE);
        assertEquals(initialSlowChange, state.isSlowChange());
    }

    @Test
    public void testClamp_activeClamperApplied_brightnessAboveMax() {
        float initialBrightness = 0.8f;
        boolean initialSlowChange = true;
        float clampedBrightness = 0.6f;
        float customAnimationRate = 0.01f;
        when(mMockClamper.getBrightnessCap()).thenReturn(clampedBrightness);
        when(mMockClamper.getType()).thenReturn(BrightnessClamper.Type.THERMAL);
        when(mMockClamper.getCustomAnimationRate()).thenReturn(customAnimationRate);
        when(mMockClamper.isActive()).thenReturn(true);
        mTestInjector.mCapturedChangeListener.onChanged();
        mTestHandler.flush();

        DisplayBrightnessState state = mClamperController.clamp(mMockRequest, initialBrightness,
                initialSlowChange);

        assertEquals(clampedBrightness, state.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(clampedBrightness, state.getMaxBrightness(), FLOAT_TOLERANCE);
        assertEquals(BrightnessReason.MODIFIER_THROTTLED,
                state.getBrightnessReason().getModifier() & BrightnessReason.MODIFIER_THROTTLED);
        assertEquals(customAnimationRate, state.getCustomAnimationRate(), FLOAT_TOLERANCE);
        assertFalse(state.isSlowChange());
    }

    @Test
    public void testClamp_activeClamperApplied_brightnessBelowMax() {
        float initialBrightness = 0.6f;
        boolean initialSlowChange = true;
        float clampedBrightness = 0.8f;
        float customAnimationRate = 0.01f;
        when(mMockClamper.getBrightnessCap()).thenReturn(clampedBrightness);
        when(mMockClamper.getType()).thenReturn(BrightnessClamper.Type.THERMAL);
        when(mMockClamper.getCustomAnimationRate()).thenReturn(customAnimationRate);
        when(mMockClamper.isActive()).thenReturn(true);
        mTestInjector.mCapturedChangeListener.onChanged();
        mTestHandler.flush();

        DisplayBrightnessState state = mClamperController.clamp(mMockRequest, initialBrightness,
                initialSlowChange);

        assertEquals(initialBrightness, state.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(clampedBrightness, state.getMaxBrightness(), FLOAT_TOLERANCE);
        assertEquals(BrightnessReason.MODIFIER_THROTTLED,
                state.getBrightnessReason().getModifier() & BrightnessReason.MODIFIER_THROTTLED);
        assertEquals(customAnimationRate, state.getCustomAnimationRate(), FLOAT_TOLERANCE);
        assertFalse(state.isSlowChange());
    }

    @Test
    public void testClamp_activeClamperAppliedTwoTimes_keepsSlowChange() {
        float initialBrightness = 0.8f;
        boolean initialSlowChange = true;
        float clampedBrightness = 0.6f;
        float customAnimationRate = 0.01f;
        when(mMockClamper.getBrightnessCap()).thenReturn(clampedBrightness);
        when(mMockClamper.getType()).thenReturn(BrightnessClamper.Type.THERMAL);
        when(mMockClamper.getCustomAnimationRate()).thenReturn(customAnimationRate);
        when(mMockClamper.isActive()).thenReturn(true);
        mTestInjector.mCapturedChangeListener.onChanged();
        mTestHandler.flush();
        // first call of clamp method
        mClamperController.clamp(mMockRequest, initialBrightness,
                initialSlowChange);
        // immediately second call of clamp method
        DisplayBrightnessState state = mClamperController.clamp(mMockRequest, initialBrightness,
                initialSlowChange);

        assertEquals(clampedBrightness, state.getBrightness(), FLOAT_TOLERANCE);
        assertEquals(clampedBrightness, state.getMaxBrightness(), FLOAT_TOLERANCE);
        assertEquals(BrightnessReason.MODIFIER_THROTTLED,
                state.getBrightnessReason().getModifier() & BrightnessReason.MODIFIER_THROTTLED);
        assertEquals(customAnimationRate, state.getCustomAnimationRate(), FLOAT_TOLERANCE);
        assertEquals(initialSlowChange, state.isSlowChange());
    }

    @Test
    public void testStop() {
        mClamperController.stop();
        verify(mMockModifier).stop();
        verify(mMockClamper).stop();
    }

    private BrightnessClamperController createBrightnessClamperController() {
        return new BrightnessClamperController(mTestInjector, mTestHandler, mMockExternalListener,
                mMockDisplayDeviceData, mMockContext, mFlags);
    }

    private class TestInjector extends BrightnessClamperController.Injector {

        private final List<BrightnessClamper<? super BrightnessClamperController.DisplayDeviceData>>
                mClampers;
        private final List<BrightnessStateModifier> mModifiers;

        private BrightnessClamperController.ClamperChangeListener mCapturedChangeListener;

        private TestInjector(
                List<BrightnessClamper<? super BrightnessClamperController.DisplayDeviceData>>
                        clampers,
                List<BrightnessStateModifier> modifiers) {
            mClampers = clampers;
            mModifiers = modifiers;
        }

        @Override
        DeviceConfigParameterProvider getDeviceConfigParameterProvider() {
            return mMockDeviceConfigParameterProvider;
        }

        @Override
        List<BrightnessClamper<? super BrightnessClamperController.DisplayDeviceData>> getClampers(
                Handler handler,
                BrightnessClamperController.ClamperChangeListener clamperChangeListener,
                BrightnessClamperController.DisplayDeviceData data,
                DisplayManagerFlags flags, Context context) {
            mCapturedChangeListener = clamperChangeListener;
            return mClampers;
        }

        @Override
        List<BrightnessStateModifier> getModifiers(DisplayManagerFlags flags, Context context,
                Handler handler, BrightnessClamperController.ClamperChangeListener listener,
                DisplayDeviceConfig displayDeviceConfig) {
            return mModifiers;
        }
    }
}
