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

import static android.os.VibrationAttributes.USAGE_NOTIFICATION;
import static android.os.VibrationAttributes.USAGE_RINGTONE;
import static android.os.VibrationAttributes.USAGE_TOUCH;
import static android.os.Vibrator.VIBRATION_INTENSITY_HIGH;
import static android.os.Vibrator.VIBRATION_INTENSITY_LOW;
import static android.os.Vibrator.VIBRATION_INTENSITY_MEDIUM;
import static android.os.Vibrator.VIBRATION_INTENSITY_OFF;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContextWrapper;
import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.IExternalVibratorService;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.test.TestLooper;
import android.os.vibrator.Flags;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationConfig;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
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

public class VibrationScalerTest {

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock private PowerManagerInternal mPowerManagerInternalMock;
    @Mock private PackageManagerInternal mPackageManagerInternalMock;
    @Mock private VibrationConfig mVibrationConfigMock;

    private TestLooper mTestLooper;
    private ContextWrapper mContextSpy;
    private VibrationSettings mVibrationSettings;
    private VibrationScaler mVibrationScaler;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        when(mPackageManagerInternalMock.getSystemUiServiceComponent())
                .thenReturn(new ComponentName("", ""));

        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternalMock);
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.addService(PowerManagerInternal.class, mPowerManagerInternalMock);

        Settings.System.putInt(contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1);
        Settings.System.putInt(contentResolver, Settings.System.VIBRATE_WHEN_RINGING, 1);

        mVibrationSettings = new VibrationSettings(
                mContextSpy, new Handler(mTestLooper.getLooper()), mVibrationConfigMock);
        mVibrationScaler = new VibrationScaler(mContextSpy, mVibrationSettings);

        mVibrationSettings.onSystemReady();
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
    }

    @Test
    public void testGetExternalVibrationScale() {
        setDefaultIntensity(USAGE_TOUCH, Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_HIGH);
        assertEquals(IExternalVibratorService.SCALE_VERY_HIGH,
                mVibrationScaler.getExternalVibrationScale(USAGE_TOUCH));

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_MEDIUM);
        assertEquals(IExternalVibratorService.SCALE_HIGH,
                mVibrationScaler.getExternalVibrationScale(USAGE_TOUCH));

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_LOW);
        assertEquals(IExternalVibratorService.SCALE_NONE,
                mVibrationScaler.getExternalVibrationScale(USAGE_TOUCH));

        setDefaultIntensity(USAGE_TOUCH, VIBRATION_INTENSITY_MEDIUM);
        assertEquals(IExternalVibratorService.SCALE_LOW,
                mVibrationScaler.getExternalVibrationScale(USAGE_TOUCH));

        setDefaultIntensity(USAGE_TOUCH, VIBRATION_INTENSITY_HIGH);
        assertEquals(IExternalVibratorService.SCALE_VERY_LOW,
                mVibrationScaler.getExternalVibrationScale(USAGE_TOUCH));

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        // Vibration setting being bypassed will use default setting and not scale.
        assertEquals(IExternalVibratorService.SCALE_NONE,
                mVibrationScaler.getExternalVibrationScale(USAGE_TOUCH));
    }

    @Test
    public void scale_withPrebakedSegment_setsEffectStrengthBasedOnSettings() {
        setDefaultIntensity(USAGE_NOTIFICATION, VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_HIGH);
        PrebakedSegment effect = new PrebakedSegment(VibrationEffect.EFFECT_CLICK,
                /* shouldFallback= */ false, VibrationEffect.EFFECT_STRENGTH_MEDIUM);

        PrebakedSegment scaled = mVibrationScaler.scale(effect, USAGE_NOTIFICATION);
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_STRONG);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                VIBRATION_INTENSITY_MEDIUM);
        scaled = mVibrationScaler.scale(effect, USAGE_NOTIFICATION);
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_MEDIUM);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW);
        scaled = mVibrationScaler.scale(effect, USAGE_NOTIFICATION);
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_LIGHT);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        scaled = mVibrationScaler.scale(effect, USAGE_NOTIFICATION);
        // Vibration setting being bypassed will use default setting.
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_MEDIUM);
    }

    @Test
    public void scale_withPrebakedEffect_setsEffectStrengthBasedOnSettings() {
        setDefaultIntensity(USAGE_NOTIFICATION, VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_HIGH);
        VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);

        PrebakedSegment scaled =
                getFirstSegment(mVibrationScaler.scale(effect, USAGE_NOTIFICATION));
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_STRONG);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                VIBRATION_INTENSITY_MEDIUM);
        scaled = getFirstSegment(mVibrationScaler.scale(effect, USAGE_NOTIFICATION));
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_MEDIUM);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW);
        scaled = getFirstSegment(mVibrationScaler.scale(effect, USAGE_NOTIFICATION));
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_LIGHT);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        scaled = getFirstSegment(mVibrationScaler.scale(effect, USAGE_NOTIFICATION));
        // Vibration setting being bypassed will use default setting.
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_LIGHT);
    }

    @Test
    public void scale_withOneShotAndWaveform_resolvesAmplitude() {
        // No scale, default amplitude still resolved
        setDefaultIntensity(USAGE_RINGTONE, VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW);

        StepSegment resolved = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE),
                USAGE_RINGTONE));
        assertTrue(resolved.getAmplitude() > 0);

        resolved = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createWaveform(new long[]{10},
                        new int[]{VibrationEffect.DEFAULT_AMPLITUDE}, -1),
                USAGE_RINGTONE));
        assertTrue(resolved.getAmplitude() > 0);
    }

    @Test
    public void scale_withOneShotAndWaveform_scalesAmplitude() {
        setDefaultIntensity(USAGE_RINGTONE, VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_HIGH);
        setDefaultIntensity(USAGE_NOTIFICATION, VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW);
        setDefaultIntensity(USAGE_TOUCH, VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_MEDIUM);

        StepSegment scaled = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createOneShot(128, 128), USAGE_RINGTONE));
        // Ringtone scales up.
        assertTrue(scaled.getAmplitude() > 0.5);

        scaled = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createWaveform(new long[]{128}, new int[]{128}, -1),
                USAGE_NOTIFICATION));
        // Notification scales down.
        assertTrue(scaled.getAmplitude() < 0.5);

        scaled = getFirstSegment(mVibrationScaler.scale(VibrationEffect.createOneShot(128, 128),
                USAGE_TOUCH));
        // Haptic feedback does not scale.
        assertEquals(128f / 255, scaled.getAmplitude(), 1e-5);
    }

    @Test
    public void scale_withComposed_scalesPrimitives() {
        setDefaultIntensity(USAGE_RINGTONE, VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_HIGH);
        setDefaultIntensity(USAGE_NOTIFICATION, VIBRATION_INTENSITY_HIGH);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW);
        setDefaultIntensity(USAGE_TOUCH, VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_MEDIUM);

        VibrationEffect composed = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f).compose();

        PrimitiveSegment scaled = getFirstSegment(mVibrationScaler.scale(composed, USAGE_RINGTONE));
        // Ringtone scales up.
        assertTrue(scaled.getScale() > 0.5f);

        scaled = getFirstSegment(mVibrationScaler.scale(composed, USAGE_NOTIFICATION));
        // Notification scales down.
        assertTrue(scaled.getScale() < 0.5f);

        scaled = getFirstSegment(mVibrationScaler.scale(composed, USAGE_TOUCH));
        // Haptic feedback does not scale.
        assertEquals(0.5, scaled.getScale(), 1e-5);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED)
    public void scale_withAdaptiveHaptics_scalesVibrationsCorrectly() {
        setDefaultIntensity(USAGE_RINGTONE, VIBRATION_INTENSITY_HIGH);
        setDefaultIntensity(USAGE_NOTIFICATION, VIBRATION_INTENSITY_HIGH);

        mVibrationScaler.updateAdaptiveHapticsScale(USAGE_RINGTONE, 0.5f);
        mVibrationScaler.updateAdaptiveHapticsScale(USAGE_NOTIFICATION, 0.5f);

        StepSegment scaled = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createOneShot(128, 128), USAGE_RINGTONE));
        // Ringtone scales down.
        assertTrue(scaled.getAmplitude() < 0.5);

        scaled = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createWaveform(new long[]{128}, new int[]{128}, -1),
                USAGE_NOTIFICATION));
        // Notification scales down.
        assertTrue(scaled.getAmplitude() < 0.5);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED)
    public void scale_clearAdaptiveHapticsScales_clearsAllCachedScales() {
        setDefaultIntensity(USAGE_RINGTONE, VIBRATION_INTENSITY_HIGH);
        setDefaultIntensity(USAGE_NOTIFICATION, VIBRATION_INTENSITY_HIGH);

        mVibrationScaler.updateAdaptiveHapticsScale(USAGE_RINGTONE, 0.5f);
        mVibrationScaler.updateAdaptiveHapticsScale(USAGE_NOTIFICATION, 0.5f);
        mVibrationScaler.clearAdaptiveHapticsScales();

        StepSegment scaled = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createOneShot(128, 128), USAGE_RINGTONE));
        // Ringtone scales up.
        assertTrue(scaled.getAmplitude() > 0.5);

        scaled = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createWaveform(new long[]{128}, new int[]{128}, -1),
                USAGE_NOTIFICATION));
        // Notification scales up.
        assertTrue(scaled.getAmplitude() > 0.5);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED)
    public void scale_removeAdaptiveHapticsScale_removesCachedScale() {
        setDefaultIntensity(USAGE_RINGTONE, VIBRATION_INTENSITY_HIGH);
        setDefaultIntensity(USAGE_NOTIFICATION, VIBRATION_INTENSITY_HIGH);

        mVibrationScaler.updateAdaptiveHapticsScale(USAGE_RINGTONE, 0.5f);
        mVibrationScaler.updateAdaptiveHapticsScale(USAGE_NOTIFICATION, 0.5f);
        mVibrationScaler.removeAdaptiveHapticsScale(USAGE_NOTIFICATION);

        StepSegment scaled = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createOneShot(128, 128), USAGE_RINGTONE));
        // Ringtone scales down.
        assertTrue(scaled.getAmplitude() < 0.5);

        scaled = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createWaveform(new long[]{128}, new int[]{128}, -1),
                USAGE_NOTIFICATION));
        // Notification scales up.
        assertTrue(scaled.getAmplitude() > 0.5);
    }

    private void setDefaultIntensity(@VibrationAttributes.Usage int usage,
            @Vibrator.VibrationIntensity int intensity) {
        when(mVibrationConfigMock.getDefaultVibrationIntensity(eq(usage))).thenReturn(intensity);
    }

    private <T extends VibrationEffectSegment> T getFirstSegment(VibrationEffect effect) {
        assertTrue(effect instanceof VibrationEffect.Composed);
        return (T) ((VibrationEffect.Composed) effect).getSegments().get(0);
    }

    private void setUserSetting(String settingName, int value) {
        Settings.System.putIntForUser(
                mContextSpy.getContentResolver(), settingName, value, UserHandle.USER_CURRENT);
        // FakeSettingsProvider don't support testing triggering ContentObserver yet.
        mVibrationSettings.mSettingObserver.onChange(false);
    }
}
