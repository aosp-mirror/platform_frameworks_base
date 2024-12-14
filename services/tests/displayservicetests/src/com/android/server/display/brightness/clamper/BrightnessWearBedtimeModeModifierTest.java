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

import static com.android.server.display.brightness.clamper.BrightnessWearBedtimeModeModifier.BEDTIME_MODE_OFF;
import static com.android.server.display.brightness.clamper.BrightnessWearBedtimeModeModifier.BEDTIME_MODE_ON;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManagerInternal;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.testing.TestableContext;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.display.BrightnessSynchronizer;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.DisplayDeviceConfig;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.clamper.BrightnessClamperController.ModifiersAggregatedState;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class BrightnessWearBedtimeModeModifierTest {
    private static final int NO_MODIFIER = 0;
    private static final float BRIGHTNESS_CAP = 0.3f;
    private static final String DISPLAY_ID = "displayId";

    @Mock
    private BrightnessClamperController.ClamperChangeListener mMockClamperChangeListener;
    @Mock
    private DisplayManagerInternal.DisplayPowerRequest mMockRequest;
    @Mock
    private DisplayDeviceConfig mMockDisplayDeviceConfig;
    @Mock
    private IBinder mMockBinder;

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());

    private final TestHandler mTestHandler = new TestHandler(null);
    private final TestInjector mInjector = new TestInjector();
    private BrightnessWearBedtimeModeModifier mModifier;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mModifier = new BrightnessWearBedtimeModeModifier(mInjector, mTestHandler, mContext,
                mMockClamperChangeListener, () -> BRIGHTNESS_CAP);
        mTestHandler.flush();
    }

    @Test
    public void testBedtimeModeOff() {
        setBedtimeModeEnabled(false);
        assertModifierState(
                0.5f, true,
                PowerManager.BRIGHTNESS_MAX, 0.5f,
                false, true);
        verify(mMockClamperChangeListener).onChanged();
    }

    @Test
    public void testBedtimeModeOn() {
        setBedtimeModeEnabled(true);
        assertModifierState(
                0.5f, true,
                BRIGHTNESS_CAP, BRIGHTNESS_CAP,
                true, false);
        verify(mMockClamperChangeListener).onChanged();
    }

    @Test
    public void testOnDisplayChanged() {
        setBedtimeModeEnabled(true);
        clearInvocations(mMockClamperChangeListener);
        float newBrightnessCap = 0.61f;
        onDisplayChange(newBrightnessCap);
        mTestHandler.flush();

        assertModifierState(
                0.5f, true,
                newBrightnessCap, 0.5f,
                true, false);
        verify(mMockClamperChangeListener).onChanged();
    }

    private void setBedtimeModeEnabled(boolean enabled) {
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.Wearable.BEDTIME_MODE,
                enabled ? BEDTIME_MODE_ON : BEDTIME_MODE_OFF);
        mInjector.notifyBedtimeModeChanged();
        mTestHandler.flush();
    }

    private void onDisplayChange(float brightnessCap) {
        when(mMockDisplayDeviceConfig.getBrightnessCapForWearBedtimeMode())
                .thenReturn(brightnessCap);
        mModifier.onDisplayChanged(ClamperTestUtilsKt.createDisplayDeviceData(
                mMockDisplayDeviceConfig, mMockBinder, DISPLAY_ID, DisplayDeviceConfig.DEFAULT_ID));
    }

    private void assertModifierState(
            float currentBrightness,
            boolean currentSlowChange,
            float maxBrightness, float brightness,
            boolean isActive,
            boolean isSlowChange) {
        ModifiersAggregatedState modifierState = new ModifiersAggregatedState();
        DisplayBrightnessState.Builder stateBuilder = DisplayBrightnessState.builder();
        stateBuilder.setBrightness(currentBrightness);
        stateBuilder.setIsSlowChange(currentSlowChange);

        int maxBrightnessReason = isActive ? BrightnessInfo.BRIGHTNESS_MAX_REASON_WEAR_BEDTIME_MODE
                : BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE;
        int modifier = isActive ? BrightnessReason.MODIFIER_THROTTLED : NO_MODIFIER;

        mModifier.applyStateChange(modifierState);
        assertThat(modifierState.mMaxBrightness).isEqualTo(maxBrightness);
        assertThat(modifierState.mMaxBrightnessReason).isEqualTo(maxBrightnessReason);

        mModifier.apply(mMockRequest, stateBuilder);

        assertThat(stateBuilder.getMaxBrightness())
                .isWithin(BrightnessSynchronizer.EPSILON).of(maxBrightness);
        assertThat(stateBuilder.getBrightness())
                .isWithin(BrightnessSynchronizer.EPSILON).of(brightness);
        assertThat(stateBuilder.getBrightnessMaxReason()).isEqualTo(maxBrightnessReason);
        assertThat(stateBuilder.getBrightnessReason().getModifier()).isEqualTo(modifier);
        assertThat(stateBuilder.isSlowChange()).isEqualTo(isSlowChange);
    }


    private static class TestInjector extends BrightnessWearBedtimeModeModifier.Injector {

        private ContentObserver mObserver;

        @Override
        void registerBedtimeModeObserver(@NonNull ContentResolver cr,
                @NonNull ContentObserver observer) {
            mObserver = observer;
        }

        private void notifyBedtimeModeChanged() {
            if (mObserver != null) {
                mObserver.dispatchChange(/* selfChange= */ false,
                        Settings.Global.getUriFor(Settings.Global.Wearable.BEDTIME_MODE));
            }
        }
    }
}
