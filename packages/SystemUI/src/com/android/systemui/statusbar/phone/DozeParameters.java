/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.MathUtils;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.doze.AlwaysOnDisplayPolicy;
import com.android.systemui.doze.DozeScreenState;
import com.android.systemui.tuner.TunerService;

import java.io.PrintWriter;

public class DozeParameters implements TunerService.Tunable {
    private static final int MAX_DURATION = 60 * 1000;
    public static final String DOZE_SENSORS_WAKE_UP_FULLY = "doze_sensors_wake_up_fully";
    public static final boolean FORCE_NO_BLANKING =
            SystemProperties.getBoolean("debug.force_no_blanking", false);

    private static IntInOutMatcher sPickupSubtypePerformsProxMatcher;
    private static DozeParameters sInstance;

    private final Context mContext;
    private final AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    private final PowerManager mPowerManager;

    private final AlwaysOnDisplayPolicy mAlwaysOnPolicy;

    private boolean mDozeAlwaysOn;
    private boolean mControlScreenOffAnimation;

    public static DozeParameters getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DozeParameters(context);
        }
        return sInstance;
    }

    @VisibleForTesting
    protected DozeParameters(Context context) {
        mContext = context.getApplicationContext();
        mAmbientDisplayConfiguration = new AmbientDisplayConfiguration(mContext);
        mAlwaysOnPolicy = new AlwaysOnDisplayPolicy(mContext);

        mControlScreenOffAnimation = !getDisplayNeedsBlanking();
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mPowerManager.setDozeAfterScreenOff(!mControlScreenOffAnimation);

        Dependency.get(TunerService.class).addTunable(this, Settings.Secure.DOZE_ALWAYS_ON,
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
    }

    public void dump(PrintWriter pw) {
        pw.println("  DozeParameters:");
        pw.print("    getDisplayStateSupported(): "); pw.println(getDisplayStateSupported());
        pw.print("    getPulseDuration(): "); pw.println(getPulseDuration());
        pw.print("    getPulseInDuration(): "); pw.println(getPulseInDuration());
        pw.print("    getPulseInVisibleDuration(): "); pw.println(getPulseVisibleDuration());
        pw.print("    getPulseOutDuration(): "); pw.println(getPulseOutDuration());
        pw.print("    getPulseOnSigMotion(): "); pw.println(getPulseOnSigMotion());
        pw.print("    getVibrateOnSigMotion(): "); pw.println(getVibrateOnSigMotion());
        pw.print("    getVibrateOnPickup(): "); pw.println(getVibrateOnPickup());
        pw.print("    getProxCheckBeforePulse(): "); pw.println(getProxCheckBeforePulse());
        pw.print("    getPickupVibrationThreshold(): "); pw.println(getPickupVibrationThreshold());
        pw.print("    getPickupSubtypePerformsProxCheck(): ");pw.println(
                dumpPickupSubtypePerformsProxCheck());
    }

    private String dumpPickupSubtypePerformsProxCheck() {
        // Refresh sPickupSubtypePerformsProxMatcher
        getPickupSubtypePerformsProxCheck(0);

        if (sPickupSubtypePerformsProxMatcher == null) {
            return "fallback: " + mContext.getResources().getBoolean(
                    R.bool.doze_pickup_performs_proximity_check);
        } else {
            return "spec: " + sPickupSubtypePerformsProxMatcher.mSpec;
        }
    }

    public boolean getDisplayStateSupported() {
        return getBoolean("doze.display.supported", R.bool.doze_display_state_supported);
    }

    public boolean getDozeSuspendDisplayStateSupported() {
        return mContext.getResources().getBoolean(R.bool.doze_suspend_display_state_supported);
    }

    public int getPulseDuration() {
        return getPulseInDuration() + getPulseVisibleDuration() + getPulseOutDuration();
    }

    public float getScreenBrightnessDoze() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessDoze) / 255f;
    }

    public int getPulseInDuration() {
        return getInt("doze.pulse.duration.in", R.integer.doze_pulse_duration_in);
    }

    public int getPulseVisibleDuration() {
        return getInt("doze.pulse.duration.visible", R.integer.doze_pulse_duration_visible);
    }

    public int getPulseOutDuration() {
        return getInt("doze.pulse.duration.out", R.integer.doze_pulse_duration_out);
    }

    public boolean getPulseOnSigMotion() {
        return getBoolean("doze.pulse.sigmotion", R.bool.doze_pulse_on_significant_motion);
    }

    public boolean getVibrateOnSigMotion() {
        return SystemProperties.getBoolean("doze.vibrate.sigmotion", false);
    }

    public boolean getVibrateOnPickup() {
        return SystemProperties.getBoolean("doze.vibrate.pickup", false);
    }

    public boolean getProxCheckBeforePulse() {
        return getBoolean("doze.pulse.proxcheck", R.bool.doze_proximity_check_before_pulse);
    }

    public int getPickupVibrationThreshold() {
        return getInt("doze.pickup.vibration.threshold", R.integer.doze_pickup_vibration_threshold);
    }

    /**
     * For how long a wallpaper can be visible in AoD before it fades aways.
     * @return duration in millis.
     */
    public long getWallpaperAodDuration() {
        return shouldControlScreenOff() ? DozeScreenState.ENTER_DOZE_HIDE_WALLPAPER_DELAY
                : mAlwaysOnPolicy.wallpaperVisibilityDuration;
    }

    /**
     * How long it takes for the wallpaper fade away (Animation duration.)
     * @return duration in millis.
     */
    public long getWallpaperFadeOutDuration() {
        return mAlwaysOnPolicy.wallpaperFadeOutDuration;
    }

    /**
     * Checks if always on is available and enabled for the current user.
     * @return {@code true} if enabled and available.
     */
    public boolean getAlwaysOn() {
        return mDozeAlwaysOn;
    }

    /**
     * Some screens need to be completely black before changing the display power mode,
     * unexpected behavior might happen if this parameter isn't respected.
     *
     * @return {@code true} if screen needs to be completely black before a power transition.
     */
    public boolean getDisplayNeedsBlanking() {
        return !FORCE_NO_BLANKING && mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_displayBlanksAfterDoze);
    }

    public boolean shouldControlScreenOff() {
        return mControlScreenOffAnimation;
    }

    public void setControlScreenOffAnimation(boolean controlScreenOffAnimation) {
        if (mControlScreenOffAnimation == controlScreenOffAnimation) {
            return;
        }
        mControlScreenOffAnimation = controlScreenOffAnimation;
        getPowerManager().setDozeAfterScreenOff(!controlScreenOffAnimation);
    }

    @VisibleForTesting
    protected PowerManager getPowerManager() {
        return mPowerManager;
    }

    private boolean getBoolean(String propName, int resId) {
        return SystemProperties.getBoolean(propName, mContext.getResources().getBoolean(resId));
    }

    private int getInt(String propName, int resId) {
        int value = SystemProperties.getInt(propName, mContext.getResources().getInteger(resId));
        return MathUtils.constrain(value, 0, MAX_DURATION);
    }

    private String getString(String propName, int resId) {
        return SystemProperties.get(propName, mContext.getString(resId));
    }

    public boolean getPickupSubtypePerformsProxCheck(int subType) {
        String spec = getString("doze.pickup.proxcheck",
                R.string.doze_pickup_subtype_performs_proximity_check);

        if (TextUtils.isEmpty(spec)) {
            // Fall back to non-subtype based property.
            return mContext.getResources().getBoolean(R.bool.doze_pickup_performs_proximity_check);
        }

        if (sPickupSubtypePerformsProxMatcher == null
                || !TextUtils.equals(spec, sPickupSubtypePerformsProxMatcher.mSpec)) {
            sPickupSubtypePerformsProxMatcher = new IntInOutMatcher(spec);
        }

        return sPickupSubtypePerformsProxMatcher.isIn(subType);
    }

    public int getPulseVisibleDurationExtended() {
        return 2 * getPulseVisibleDuration();
    }

    public boolean doubleTapReportsTouchCoordinates() {
        return mContext.getResources().getBoolean(R.bool.doze_double_tap_reports_touch_coordinates);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        mDozeAlwaysOn = mAmbientDisplayConfiguration.alwaysOnEnabled(UserHandle.USER_CURRENT);
    }

    public AlwaysOnDisplayPolicy getPolicy() {
        return mAlwaysOnPolicy;
    }

    /**
     * Parses a spec of the form `1,2,3,!5,*`. The resulting object will match numbers that are
     * listed, will not match numbers that are listed with a ! prefix, and will match / not match
     * unlisted numbers depending on whether * or !* is present.
     *
     * *  -> match any numbers that are not explicitly listed
     * !* -> don't match any numbers that are not explicitly listed
     * 2  -> match 2
     * !3 -> don't match 3
     *
     * It is illegal to specify:
     * - an empty spec
     * - a spec containing that are empty, or a lone !
     * - a spec for anything other than numbers or *
     * - multiple terms for the same number / multiple *s
     */
    public static class IntInOutMatcher {
        private static final String WILDCARD = "*";
        private static final char OUT_PREFIX = '!';

        private final SparseBooleanArray mIsIn;
        private final boolean mDefaultIsIn;
        final String mSpec;

        public IntInOutMatcher(String spec) {
            if (TextUtils.isEmpty(spec)) {
                throw new IllegalArgumentException("Spec must not be empty");
            }

            boolean defaultIsIn = false;
            boolean foundWildcard = false;

            mSpec = spec;
            mIsIn = new SparseBooleanArray();

            for (String itemPrefixed : spec.split(",", -1)) {
                if (itemPrefixed.length() == 0) {
                    throw new IllegalArgumentException(
                            "Illegal spec, must not have zero-length items: `" + spec + "`");
                }
                boolean isIn = itemPrefixed.charAt(0) != OUT_PREFIX;
                String item = isIn ? itemPrefixed : itemPrefixed.substring(1);

                if (itemPrefixed.length() == 0) {
                    throw new IllegalArgumentException(
                            "Illegal spec, must not have zero-length items: `" + spec + "`");
                }

                if (WILDCARD.equals(item)) {
                    if (foundWildcard) {
                        throw new IllegalArgumentException("Illegal spec, `" + WILDCARD +
                                "` must not appear multiple times in `" + spec + "`");
                    }
                    defaultIsIn = isIn;
                    foundWildcard = true;
                } else {
                    int key = Integer.parseInt(item);
                    if (mIsIn.indexOfKey(key) >= 0) {
                        throw new IllegalArgumentException("Illegal spec, `" + key +
                                "` must not appear multiple times in `" + spec + "`");
                    }
                    mIsIn.put(key, isIn);
                }
            }

            if (!foundWildcard) {
                throw new IllegalArgumentException("Illegal spec, must specify either * or !*");
            }

            mDefaultIsIn = defaultIsIn;
        }

        public boolean isIn(int value) {
            return (mIsIn.get(value, mDefaultIsIn));
        }
    }
}
