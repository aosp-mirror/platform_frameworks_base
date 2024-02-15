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

package android.os.vibrator;

import static android.os.VibrationAttributes.USAGE_ACCESSIBILITY;
import static android.os.VibrationAttributes.USAGE_ALARM;
import static android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
import static android.os.VibrationAttributes.USAGE_HARDWARE_FEEDBACK;
import static android.os.VibrationAttributes.USAGE_MEDIA;
import static android.os.VibrationAttributes.USAGE_NOTIFICATION;
import static android.os.VibrationAttributes.USAGE_PHYSICAL_EMULATION;
import static android.os.VibrationAttributes.USAGE_RINGTONE;
import static android.os.VibrationAttributes.USAGE_TOUCH;
import static android.os.VibrationAttributes.USAGE_UNKNOWN;

import android.annotation.Nullable;
import android.content.res.Resources;
import android.os.VibrationAttributes;
import android.os.Vibrator;
import android.os.Vibrator.VibrationIntensity;
import android.util.IndentingPrintWriter;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * List of device-specific internal vibration configuration loaded from platform config.xml.
 *
 * <p>This should not be public, but some individual values are exposed by {@link Vibrator} by
 * hidden methods, made available to Settings, SysUI and other platform client code. They can also
 * be individually exposed with the necessary permissions by the {@link Vibrator} service.
 *
 * @hide
 */
public class VibrationConfig {

    // TODO(b/191150049): move these to vibrator static config file
    private final float mHapticChannelMaxVibrationAmplitude;
    private final int mRampStepDurationMs;
    private final int mRampDownDurationMs;
    private final int mRequestVibrationParamsTimeoutMs;
    private final int[] mRequestVibrationParamsForUsages;

    private final boolean mIgnoreVibrationsOnWirelessCharger;

    @VibrationIntensity
    private final int mDefaultAlarmVibrationIntensity;
    @VibrationIntensity
    private final int mDefaultHapticFeedbackIntensity;
    @VibrationIntensity
    private final int mDefaultMediaVibrationIntensity;
    @VibrationIntensity
    private final int mDefaultNotificationVibrationIntensity;
    @VibrationIntensity
    private final int mDefaultRingVibrationIntensity;

    private final boolean mDefaultKeyboardVibrationEnabled;

    /** @hide */
    public VibrationConfig(@Nullable Resources resources) {
        mHapticChannelMaxVibrationAmplitude = loadFloat(resources,
                com.android.internal.R.dimen.config_hapticChannelMaxVibrationAmplitude, 0);
        mRampDownDurationMs = loadInteger(resources,
                com.android.internal.R.integer.config_vibrationWaveformRampDownDuration, 0);
        mRampStepDurationMs = loadInteger(resources,
                com.android.internal.R.integer.config_vibrationWaveformRampStepDuration, 0);
        mRequestVibrationParamsTimeoutMs = loadInteger(resources,
                com.android.internal.R.integer.config_requestVibrationParamsTimeout, 0);
        mRequestVibrationParamsForUsages = loadIntArray(resources,
                com.android.internal.R.array.config_requestVibrationParamsForUsages);

        mIgnoreVibrationsOnWirelessCharger = loadBoolean(resources,
                com.android.internal.R.bool.config_ignoreVibrationsOnWirelessCharger, false);
        mDefaultKeyboardVibrationEnabled = loadBoolean(resources,
                com.android.internal.R.bool.config_defaultKeyboardVibrationEnabled, true);

        mDefaultAlarmVibrationIntensity = loadDefaultIntensity(resources,
                com.android.internal.R.integer.config_defaultAlarmVibrationIntensity);
        mDefaultHapticFeedbackIntensity = loadDefaultIntensity(resources,
                com.android.internal.R.integer.config_defaultHapticFeedbackIntensity);
        mDefaultMediaVibrationIntensity = loadDefaultIntensity(resources,
                com.android.internal.R.integer.config_defaultMediaVibrationIntensity);
        mDefaultNotificationVibrationIntensity = loadDefaultIntensity(resources,
                com.android.internal.R.integer.config_defaultNotificationVibrationIntensity);
        mDefaultRingVibrationIntensity = loadDefaultIntensity(resources,
                com.android.internal.R.integer.config_defaultRingVibrationIntensity);
    }

    @VibrationIntensity
    private static int loadDefaultIntensity(@Nullable Resources res, int resId) {
        int defaultIntensity = Vibrator.VIBRATION_INTENSITY_MEDIUM;
        int value = loadInteger(res, resId, defaultIntensity);
        if (value < Vibrator.VIBRATION_INTENSITY_OFF || value > Vibrator.VIBRATION_INTENSITY_HIGH) {
            return defaultIntensity;
        }
        return value;
    }

    private static float loadFloat(@Nullable Resources res, int resId, float defaultValue) {
        return res != null ? res.getFloat(resId) : defaultValue;
    }

    private static int loadInteger(@Nullable Resources res, int resId, int defaultValue) {
        return res != null ? res.getInteger(resId) : defaultValue;
    }

    private static boolean loadBoolean(@Nullable Resources res, int resId, boolean defaultValue) {
        return res != null ? res.getBoolean(resId) : defaultValue;
    }

    private static int[] loadIntArray(@Nullable Resources res, int resId) {
        return res != null ? res.getIntArray(resId) : new int[0];
    }

    /**
     * Return the maximum amplitude the vibrator can play using the audio haptic channels.
     *
     * @return a positive value representing the maximum absolute value the device can play signals
     * from audio haptic channels, or {@link Float#NaN NaN} if it's unknown.
     */
    public float getHapticChannelMaximumAmplitude() {
        if (mHapticChannelMaxVibrationAmplitude <= 0) {
            return Float.NaN;
        }
        return mHapticChannelMaxVibrationAmplitude;
    }

    /**
     * The duration, in milliseconds, that should be applied to the ramp to turn off the vibrator
     * when a vibration is cancelled or finished at non-zero amplitude.
     */
    public int getRampDownDurationMs() {
        if (mRampDownDurationMs < 0) {
            return 0;
        }
        return mRampDownDurationMs;
    }

    /**
     * The duration, in milliseconds, that the vibrator control service will wait for new
     * vibration params.
     */
    public int getRequestVibrationParamsTimeoutMs() {
        return Math.max(mRequestVibrationParamsTimeoutMs, 0);
    }

    /**
     * The list of usages that should request vibration params before they are played. These
     * usages don't have strong latency requirements, e.g. ringtone and notification, and can be
     * slightly delayed.
     */
    public int[] getRequestVibrationParamsForUsages() {
        return mRequestVibrationParamsForUsages;
    }

    /**
     * The duration, in milliseconds, that should be applied to convert vibration effect's
     * {@link android.os.vibrator.RampSegment} to a {@link android.os.vibrator.StepSegment} on
     * devices without PWLE support.
     */
    public int getRampStepDurationMs() {
        if (mRampStepDurationMs < 0) {
            return 0;
        }
        return mRampStepDurationMs;
    }

    /**
     * Whether or not vibrations are ignored if the device is on a wireless charger.
     *
     * <p>This may be the case if vibration during wireless charging causes unwanted results, like
     * moving the device out of alignment with the charging pad.
     */
    public boolean ignoreVibrationsOnWirelessCharger() {
        return mIgnoreVibrationsOnWirelessCharger;
    }

    /**
     * Whether keyboard vibration settings is enabled by default.
     * @hide
     */
    public boolean isDefaultKeyboardVibrationEnabled() {
        return mDefaultKeyboardVibrationEnabled;
    }

    /** Get the default vibration intensity for given usage. */
    @VibrationIntensity
    public int getDefaultVibrationIntensity(@VibrationAttributes.Usage int usage) {
        switch (usage) {
            case USAGE_ALARM:
                return mDefaultAlarmVibrationIntensity;
            case USAGE_NOTIFICATION:
            case USAGE_COMMUNICATION_REQUEST:
                return mDefaultNotificationVibrationIntensity;
            case USAGE_RINGTONE:
                return mDefaultRingVibrationIntensity;
            case USAGE_TOUCH:
            case USAGE_HARDWARE_FEEDBACK:
            case USAGE_PHYSICAL_EMULATION:
            case USAGE_ACCESSIBILITY:
                return mDefaultHapticFeedbackIntensity;
            case USAGE_MEDIA:
            case USAGE_UNKNOWN:
                // fall through
            default:
                return mDefaultMediaVibrationIntensity;
        }
    }

    @Override
    public String toString() {
        return "VibrationConfig{"
                + "mHapticChannelMaxVibrationAmplitude=" + mHapticChannelMaxVibrationAmplitude
                + ", mRampStepDurationMs=" + mRampStepDurationMs
                + ", mRampDownDurationMs=" + mRampDownDurationMs
                + ", mDefaultAlarmIntensity=" + mDefaultAlarmVibrationIntensity
                + ", mDefaultHapticFeedbackIntensity=" + mDefaultHapticFeedbackIntensity
                + ", mDefaultMediaIntensity=" + mDefaultMediaVibrationIntensity
                + ", mDefaultNotificationIntensity=" + mDefaultNotificationVibrationIntensity
                + ", mDefaultRingIntensity=" + mDefaultRingVibrationIntensity
                + ", mRequestVibrationParamsTimeoutMs=" + mRequestVibrationParamsTimeoutMs
                + ", mRequestVibrationParamsForUsages=" + Arrays.toString(
                getRequestVibrationParamsForUsagesNames())
                + "}";
    }

    /**
     * Write current settings into given {@link PrintWriter}, skipping the default settings.
     *
     * @hide
     */
    public void dumpWithoutDefaultSettings(IndentingPrintWriter pw) {
        pw.println("VibrationConfig:");
        pw.increaseIndent();
        pw.println("hapticChannelMaxAmplitude = " + mHapticChannelMaxVibrationAmplitude);
        pw.println("rampStepDurationMs = " + mRampStepDurationMs);
        pw.println("rampDownDurationMs = " + mRampDownDurationMs);
        pw.decreaseIndent();
    }

    private String[] getRequestVibrationParamsForUsagesNames() {
        int usagesCount = mRequestVibrationParamsForUsages.length;
        String[] names = new String[usagesCount];
        for (int i = 0; i < usagesCount; i++) {
            names[i] = VibrationAttributes.usageToString(mRequestVibrationParamsForUsages[i]);
        }

        return names;
    }
}
