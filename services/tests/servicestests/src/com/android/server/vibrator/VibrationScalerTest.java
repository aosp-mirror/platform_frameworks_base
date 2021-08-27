/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vibrator;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.IExternalVibratorService;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.test.TestLooper;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;
import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link VibrationScaler}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibrationScalerTest
 */
@Presubmit
public class VibrationScalerTest {

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Mock private PowerManagerInternal mPowerManagerInternalMock;

    private TestLooper mTestLooper;
    private ContextWrapper mContextSpy;
    private FakeVibrator mFakeVibrator;
    private VibrationSettings mVibrationSettings;
    private VibrationScaler mVibrationScaler;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mFakeVibrator = new FakeVibrator();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        when(mContextSpy.getSystemService(eq(Context.VIBRATOR_SERVICE))).thenReturn(mFakeVibrator);

        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.addService(PowerManagerInternal.class, mPowerManagerInternalMock);

        mVibrationSettings = new VibrationSettings(
                mContextSpy, new Handler(mTestLooper.getLooper()));
        mVibrationScaler = new VibrationScaler(mContextSpy, mVibrationSettings);
        mVibrationSettings.onSystemReady();
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
    }

    @Test
    public void testGetExternalVibrationScale() {
        mFakeVibrator.setDefaultHapticFeedbackIntensity(Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        assertEquals(IExternalVibratorService.SCALE_VERY_HIGH,
                mVibrationScaler.getExternalVibrationScale(VibrationAttributes.USAGE_TOUCH));

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        assertEquals(IExternalVibratorService.SCALE_HIGH,
                mVibrationScaler.getExternalVibrationScale(VibrationAttributes.USAGE_TOUCH));

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        assertEquals(IExternalVibratorService.SCALE_NONE,
                mVibrationScaler.getExternalVibrationScale(VibrationAttributes.USAGE_TOUCH));

        mFakeVibrator.setDefaultHapticFeedbackIntensity(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        assertEquals(IExternalVibratorService.SCALE_LOW,
                mVibrationScaler.getExternalVibrationScale(VibrationAttributes.USAGE_TOUCH));

        mFakeVibrator.setDefaultHapticFeedbackIntensity(Vibrator.VIBRATION_INTENSITY_HIGH);
        assertEquals(IExternalVibratorService.SCALE_VERY_LOW,
                mVibrationScaler.getExternalVibrationScale(VibrationAttributes.USAGE_TOUCH));

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        // Unexpected vibration intensity will be treated as SCALE_NONE.
        assertEquals(IExternalVibratorService.SCALE_NONE,
                mVibrationScaler.getExternalVibrationScale(VibrationAttributes.USAGE_TOUCH));
    }

    @Test
    public void scale_withPrebakedSegment_setsEffectStrengthBasedOnSettings() {
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        PrebakedSegment effect = new PrebakedSegment(VibrationEffect.EFFECT_CLICK,
                /* shouldFallback= */ false, VibrationEffect.EFFECT_STRENGTH_MEDIUM);

        PrebakedSegment scaled = mVibrationScaler.scale(
                effect, VibrationAttributes.USAGE_NOTIFICATION);
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_STRONG);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        scaled = mVibrationScaler.scale(effect, VibrationAttributes.USAGE_NOTIFICATION);
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_MEDIUM);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        scaled = mVibrationScaler.scale(effect, VibrationAttributes.USAGE_NOTIFICATION);
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_LIGHT);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        scaled = mVibrationScaler.scale(effect, VibrationAttributes.USAGE_NOTIFICATION);
        // Unexpected intensity setting will be mapped to STRONG.
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_STRONG);
    }

    @Test
    public void scale_withPrebakedEffect_setsEffectStrengthBasedOnSettings() {
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);

        PrebakedSegment scaled = getFirstSegment(mVibrationScaler.scale(
                effect, VibrationAttributes.USAGE_NOTIFICATION));
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_STRONG);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);
        scaled = getFirstSegment(mVibrationScaler.scale(
                effect, VibrationAttributes.USAGE_NOTIFICATION));
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_MEDIUM);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        scaled = getFirstSegment(mVibrationScaler.scale(
                effect, VibrationAttributes.USAGE_NOTIFICATION));
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_LIGHT);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_OFF);
        scaled = getFirstSegment(mVibrationScaler.scale(
                effect, VibrationAttributes.USAGE_NOTIFICATION));
        // Unexpected intensity setting will be mapped to STRONG.
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_STRONG);
    }

    @Test
    public void scale_withOneShotAndWaveform_resolvesAmplitude() {
        // No scale, default amplitude still resolved
        mFakeVibrator.setDefaultRingVibrationIntensity(Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);

        StepSegment resolved = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE),
                VibrationAttributes.USAGE_RINGTONE));
        assertTrue(resolved.getAmplitude() > 0);

        resolved = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createWaveform(new long[]{10},
                        new int[]{VibrationEffect.DEFAULT_AMPLITUDE}, -1),
                VibrationAttributes.USAGE_RINGTONE));
        assertTrue(resolved.getAmplitude() > 0);
    }

    @Test
    public void scale_withOneShotAndWaveform_scalesAmplitude() {
        mFakeVibrator.setDefaultRingVibrationIntensity(Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        mFakeVibrator.setDefaultNotificationVibrationIntensity(Vibrator.VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        mFakeVibrator.setDefaultHapticFeedbackIntensity(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);

        StepSegment scaled = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createOneShot(128, 128), VibrationAttributes.USAGE_RINGTONE));
        // Ringtone scales up.
        assertTrue(scaled.getAmplitude() > 0.5);

        scaled = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createWaveform(new long[]{128}, new int[]{128}, -1),
                VibrationAttributes.USAGE_NOTIFICATION));
        // Notification scales down.
        assertTrue(scaled.getAmplitude() < 0.5);

        scaled = getFirstSegment(mVibrationScaler.scale(VibrationEffect.createOneShot(128, 128),
                VibrationAttributes.USAGE_TOUCH));
        // Haptic feedback does not scale.
        assertEquals(128f / 255, scaled.getAmplitude(), 1e-5);
    }

    @Test
    public void scale_withComposed_scalesPrimitives() {
        mFakeVibrator.setDefaultRingVibrationIntensity(Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_HIGH);
        mFakeVibrator.setDefaultNotificationVibrationIntensity(Vibrator.VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_LOW);
        mFakeVibrator.setDefaultHapticFeedbackIntensity(Vibrator.VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY,
                Vibrator.VIBRATION_INTENSITY_MEDIUM);

        VibrationEffect composed = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f).compose();

        PrimitiveSegment scaled = getFirstSegment(mVibrationScaler.scale(composed,
                VibrationAttributes.USAGE_RINGTONE));
        // Ringtone scales up.
        assertTrue(scaled.getScale() > 0.5f);

        scaled = getFirstSegment(mVibrationScaler.scale(composed,
                VibrationAttributes.USAGE_NOTIFICATION));
        // Notification scales down.
        assertTrue(scaled.getScale() < 0.5f);

        scaled = getFirstSegment(mVibrationScaler.scale(composed, VibrationAttributes.USAGE_TOUCH));
        // Haptic feedback does not scale.
        assertEquals(0.5, scaled.getScale(), 1e-5);
    }

    private <T extends VibrationEffectSegment> T getFirstSegment(VibrationEffect.Composed effect) {
        return (T) effect.getSegments().get(0);
    }

    private void setUserSetting(String settingName, int value) {
        Settings.System.putIntForUser(
                mContextSpy.getContentResolver(), settingName, value, UserHandle.USER_CURRENT);
        // FakeSettingsProvider don't support testing triggering ContentObserver yet.
        mVibrationSettings.updateSettings();
    }
}
