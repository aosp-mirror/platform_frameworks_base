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
import android.os.ExternalVibrationScale;
import android.os.Handler;
import android.os.PersistableBundle;
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
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
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
    private static final float TOLERANCE = 1e-2f;
    private static final int TEST_DEFAULT_AMPLITUDE = 255;
    private static final float TEST_DEFAULT_SCALE_LEVEL_GAIN = 1.4f;

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Rule public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

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
        when(mVibrationConfigMock.getDefaultVibrationAmplitude())
                .thenReturn(TEST_DEFAULT_AMPLITUDE);
        when(mVibrationConfigMock.getDefaultVibrationScaleLevelGain())
                .thenReturn(TEST_DEFAULT_SCALE_LEVEL_GAIN);

        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternalMock);
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
        LocalServices.addService(PowerManagerInternal.class, mPowerManagerInternalMock);

        Settings.System.putInt(contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1);
        Settings.System.putInt(contentResolver, Settings.System.VIBRATE_WHEN_RINGING, 1);

        mVibrationSettings = new VibrationSettings(
                mContextSpy, new Handler(mTestLooper.getLooper()), mVibrationConfigMock);
        mVibrationScaler = new VibrationScaler(mVibrationConfigMock, mVibrationSettings);

        mVibrationSettings.onSystemReady();
    }

    @After
    public void tearDown() throws Exception {
        LocalServices.removeServiceForTest(PowerManagerInternal.class);
    }

    @Test
    public void testGetScaleLevel() {
        setDefaultIntensity(USAGE_TOUCH, Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_HIGH);
        assertEquals(ExternalVibrationScale.ScaleLevel.SCALE_VERY_HIGH,
                mVibrationScaler.getScaleLevel(USAGE_TOUCH));

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_MEDIUM);
        assertEquals(ExternalVibrationScale.ScaleLevel.SCALE_HIGH,
                mVibrationScaler.getScaleLevel(USAGE_TOUCH));

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_LOW);
        assertEquals(ExternalVibrationScale.ScaleLevel.SCALE_NONE,
                mVibrationScaler.getScaleLevel(USAGE_TOUCH));

        setDefaultIntensity(USAGE_TOUCH, VIBRATION_INTENSITY_MEDIUM);
        assertEquals(ExternalVibrationScale.ScaleLevel.SCALE_LOW,
                mVibrationScaler.getScaleLevel(USAGE_TOUCH));

        setDefaultIntensity(USAGE_TOUCH, VIBRATION_INTENSITY_HIGH);
        assertEquals(ExternalVibrationScale.ScaleLevel.SCALE_VERY_LOW,
                mVibrationScaler.getScaleLevel(USAGE_TOUCH));

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        // Vibration setting being bypassed will use default setting and not scale.
        assertEquals(ExternalVibrationScale.ScaleLevel.SCALE_NONE,
                mVibrationScaler.getScaleLevel(USAGE_TOUCH));
    }

    @Test
    @DisableFlags(Flags.FLAG_HAPTICS_SCALE_V2_ENABLED)
    public void testGetScaleFactor_withLegacyScaling() {
        // Default scale gain will be ignored.
        when(mVibrationConfigMock.getDefaultVibrationScaleLevelGain()).thenReturn(1.4f);
        mVibrationScaler = new VibrationScaler(mVibrationConfigMock, mVibrationSettings);

        setDefaultIntensity(USAGE_TOUCH, Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_HIGH);
        assertEquals(1.4f, mVibrationScaler.getScaleFactor(USAGE_TOUCH), TOLERANCE); // VERY_HIGH

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_MEDIUM);
        assertEquals(1.2f, mVibrationScaler.getScaleFactor(USAGE_TOUCH), TOLERANCE); // HIGH

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_LOW);
        assertEquals(1f, mVibrationScaler.getScaleFactor(USAGE_TOUCH), TOLERANCE); // NONE

        setDefaultIntensity(USAGE_TOUCH, VIBRATION_INTENSITY_MEDIUM);
        assertEquals(0.8f, mVibrationScaler.getScaleFactor(USAGE_TOUCH), TOLERANCE); // LOW

        setDefaultIntensity(USAGE_TOUCH, VIBRATION_INTENSITY_HIGH);
        assertEquals(0.6f, mVibrationScaler.getScaleFactor(USAGE_TOUCH), TOLERANCE); // VERY_LOW

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        // Vibration setting being bypassed will use default setting and not scale.
        assertEquals(1f, mVibrationScaler.getScaleFactor(USAGE_TOUCH), TOLERANCE); // NONE
    }

    @Test
    @EnableFlags(Flags.FLAG_HAPTICS_SCALE_V2_ENABLED)
    public void testGetScaleFactor_withScalingV2() {
        // Test scale factors for a default gain of 1.4
        when(mVibrationConfigMock.getDefaultVibrationScaleLevelGain()).thenReturn(1.4f);
        mVibrationScaler = new VibrationScaler(mVibrationConfigMock, mVibrationSettings);

        setDefaultIntensity(USAGE_TOUCH, Vibrator.VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_HIGH);
        assertEquals(1.95f, mVibrationScaler.getScaleFactor(USAGE_TOUCH), TOLERANCE); // VERY_HIGH

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_MEDIUM);
        assertEquals(1.4f, mVibrationScaler.getScaleFactor(USAGE_TOUCH), TOLERANCE); // HIGH

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_LOW);
        assertEquals(1f, mVibrationScaler.getScaleFactor(USAGE_TOUCH), TOLERANCE); // NONE

        setDefaultIntensity(USAGE_TOUCH, VIBRATION_INTENSITY_MEDIUM);
        assertEquals(0.71f, mVibrationScaler.getScaleFactor(USAGE_TOUCH), TOLERANCE); // LOW

        setDefaultIntensity(USAGE_TOUCH, VIBRATION_INTENSITY_HIGH);
        assertEquals(0.51f, mVibrationScaler.getScaleFactor(USAGE_TOUCH), TOLERANCE); // VERY_LOW

        setUserSetting(Settings.System.HAPTIC_FEEDBACK_INTENSITY, VIBRATION_INTENSITY_OFF);
        // Vibration setting being bypassed will use default setting and not scale.
        assertEquals(1f, mVibrationScaler.getScaleFactor(USAGE_TOUCH), TOLERANCE); // NONE
    }

    @Test
    @EnableFlags(Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED)
    public void testAdaptiveHapticsScale_withAdaptiveHapticsAvailable() {
        mVibrationScaler.updateAdaptiveHapticsScale(USAGE_TOUCH, 0.5f);
        mVibrationScaler.updateAdaptiveHapticsScale(USAGE_RINGTONE, 0.2f);

        assertEquals(0.5f, mVibrationScaler.getAdaptiveHapticsScale(USAGE_TOUCH));
        assertEquals(0.2f, mVibrationScaler.getAdaptiveHapticsScale(USAGE_RINGTONE));
        assertEquals(1f, mVibrationScaler.getAdaptiveHapticsScale(USAGE_NOTIFICATION));
        assertEquals(0.2f, mVibrationScaler.getAdaptiveHapticsScale(USAGE_RINGTONE));
    }

    @Test
    @DisableFlags(Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED)
    public void testAdaptiveHapticsScale_flagDisabled_adaptiveHapticScaleAlwaysNone() {
        mVibrationScaler.updateAdaptiveHapticsScale(USAGE_TOUCH, 0.5f);
        mVibrationScaler.updateAdaptiveHapticsScale(USAGE_RINGTONE, 0.2f);

        assertEquals(1f, mVibrationScaler.getAdaptiveHapticsScale(USAGE_TOUCH));
        assertEquals(1f, mVibrationScaler.getAdaptiveHapticsScale(USAGE_RINGTONE));
        assertEquals(1f, mVibrationScaler.getAdaptiveHapticsScale(USAGE_NOTIFICATION));
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
    @EnableFlags(android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS)
    public void scale_withVendorEffect_setsEffectStrengthAndScaleBasedOnSettings() {
        setDefaultIntensity(USAGE_NOTIFICATION, VIBRATION_INTENSITY_MEDIUM);
        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_HIGH);
        PersistableBundle vendorData = new PersistableBundle();
        vendorData.putString("key", "value");
        VibrationEffect effect = VibrationEffect.createVendorEffect(vendorData);

        VibrationEffect.VendorEffect scaled =
                (VibrationEffect.VendorEffect) mVibrationScaler.scale(effect, USAGE_NOTIFICATION);
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_STRONG);
        // Notification scales up.
        assertTrue(scaled.getScale() > 1);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY,
                VIBRATION_INTENSITY_MEDIUM);
        scaled = (VibrationEffect.VendorEffect) mVibrationScaler.scale(effect, USAGE_NOTIFICATION);
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_MEDIUM);
        // Notification does not scale.
        assertEquals(1, scaled.getScale(), TOLERANCE);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW);
        scaled = (VibrationEffect.VendorEffect) mVibrationScaler.scale(effect, USAGE_NOTIFICATION);
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_LIGHT);
        // Notification scales down.
        assertTrue(scaled.getScale() < 1);

        setUserSetting(Settings.System.NOTIFICATION_VIBRATION_INTENSITY, VIBRATION_INTENSITY_OFF);
        scaled = (VibrationEffect.VendorEffect) mVibrationScaler.scale(effect, USAGE_NOTIFICATION);
        // Vibration setting being bypassed will use default setting.
        assertEquals(scaled.getEffectStrength(), VibrationEffect.EFFECT_STRENGTH_MEDIUM);
        assertEquals(1, scaled.getScale(), TOLERANCE);
    }

    @Test
    public void scale_withOneShotAndWaveform_resolvesAmplitude() {
        // No scale, default amplitude still resolved
        setDefaultIntensity(USAGE_RINGTONE, VIBRATION_INTENSITY_LOW);
        setUserSetting(Settings.System.RING_VIBRATION_INTENSITY, VIBRATION_INTENSITY_LOW);

        StepSegment resolved = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE),
                USAGE_RINGTONE));
        assertEquals(TEST_DEFAULT_AMPLITUDE / 255f, resolved.getAmplitude(), TOLERANCE);

        resolved = getFirstSegment(mVibrationScaler.scale(
                VibrationEffect.createWaveform(new long[]{10},
                        new int[]{VibrationEffect.DEFAULT_AMPLITUDE}, -1),
                USAGE_RINGTONE));
        assertEquals(TEST_DEFAULT_AMPLITUDE / 255f, resolved.getAmplitude(), TOLERANCE);
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
        assertEquals(128f / 255, scaled.getAmplitude(), TOLERANCE);
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
        assertEquals(0.5, scaled.getScale(), TOLERANCE);
    }

    @Test
    @EnableFlags(Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED)
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
    @EnableFlags(Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED)
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
    @EnableFlags(Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED)
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

    @Test
    @EnableFlags({
            android.os.vibrator.Flags.FLAG_ADAPTIVE_HAPTICS_ENABLED,
            android.os.vibrator.Flags.FLAG_VENDOR_VIBRATION_EFFECTS,
    })
    public void scale_adaptiveHapticsOnVendorEffect_setsAdaptiveScaleParameter() {
        setDefaultIntensity(USAGE_RINGTONE, VIBRATION_INTENSITY_HIGH);

        mVibrationScaler.updateAdaptiveHapticsScale(USAGE_RINGTONE, 0.5f);

        PersistableBundle vendorData = new PersistableBundle();
        vendorData.putInt("key", 1);
        VibrationEffect effect = VibrationEffect.createVendorEffect(vendorData);

        VibrationEffect.VendorEffect scaled =
                (VibrationEffect.VendorEffect) mVibrationScaler.scale(effect, USAGE_RINGTONE);
        assertEquals(scaled.getAdaptiveScale(), 0.5f);

        mVibrationScaler.removeAdaptiveHapticsScale(USAGE_RINGTONE);

        scaled = (VibrationEffect.VendorEffect) mVibrationScaler.scale(effect, USAGE_RINGTONE);
        assertEquals(scaled.getAdaptiveScale(), 1.0f);
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
