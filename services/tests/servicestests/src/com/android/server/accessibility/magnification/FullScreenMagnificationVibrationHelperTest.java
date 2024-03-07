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

package com.android.server.accessibility.magnification;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.testing.TestableContext;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link FullScreenMagnificationVibrationHelper}.
 */
public class FullScreenMagnificationVibrationHelperTest {
    private static final long VIBRATION_DURATION_MS = 10L;
    private static final int VIBRATION_AMPLITUDE = VibrationEffect.MAX_AMPLITUDE / 2;


    @Rule
    public final TestableContext mContext = new TestableContext(getInstrumentation().getContext());
    @Mock
    Vibrator mMockVibrator;

    private FullScreenMagnificationVibrationHelper mFullScreenMagnificationVibrationHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(Vibrator.class, mMockVibrator);
        mFullScreenMagnificationVibrationHelper = new FullScreenMagnificationVibrationHelper(
                mContext);
        mFullScreenMagnificationVibrationHelper.mIsVibrationEffectSupportedProvider = () -> true;
    }

    @Test
    public void edgeHapticSettingEnabled_vibrate() {
        setEdgeHapticSettingEnabled(true);
        when(mMockVibrator.hasVibrator()).thenReturn(true);

        mFullScreenMagnificationVibrationHelper.vibrateIfSettingEnabled();

        verify(mMockVibrator).vibrate(any());
    }

    @Test
    public void edgeHapticSettingDisabled_doNotVibrate() {
        setEdgeHapticSettingEnabled(false);
        when(mMockVibrator.hasVibrator()).thenReturn(true);

        mFullScreenMagnificationVibrationHelper.vibrateIfSettingEnabled();

        verify(mMockVibrator, never()).vibrate(any());
    }

    @Test
    public void hasNoVibrator_doNotVibrate() {
        setEdgeHapticSettingEnabled(true);
        when(mMockVibrator.hasVibrator()).thenReturn(false);

        mFullScreenMagnificationVibrationHelper.vibrateIfSettingEnabled();

        verify(mMockVibrator, never()).vibrate(any());
    }

    @Test
    public void notSupportVibrationEffect_vibrateOneShotEffect() {
        setEdgeHapticSettingEnabled(true);
        when(mMockVibrator.hasVibrator()).thenReturn(true);
        mFullScreenMagnificationVibrationHelper.mIsVibrationEffectSupportedProvider = () -> false;

        mFullScreenMagnificationVibrationHelper.vibrateIfSettingEnabled();

        verify(mMockVibrator).vibrate(eq(VibrationEffect.createOneShot(VIBRATION_DURATION_MS,
                VIBRATION_AMPLITUDE)));
    }


    private boolean setEdgeHapticSettingEnabled(boolean enabled) {
        return Settings.Secure.putInt(
                mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_EDGE_HAPTIC_ENABLED,
                enabled ? 1 : 0);
    }
}
