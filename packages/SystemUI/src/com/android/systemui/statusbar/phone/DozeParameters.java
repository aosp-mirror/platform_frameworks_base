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
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.util.MathUtils;
import android.util.SparseBooleanArray;

import com.android.systemui.R;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DozeParameters {
    private static final String TAG = "DozeParameters";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int MAX_DURATION = 60 * 1000;

    private final Context mContext;

    private static IntInOutMatcher sPickupSubtypePerformsProxMatcher;

    public DozeParameters(Context context) {
        mContext = context;
    }

    public void dump(PrintWriter pw) {
        pw.println("  DozeParameters:");
        pw.print("    getDisplayStateSupported(): "); pw.println(getDisplayStateSupported());
        pw.print("    getPulseDuration(pickup=false): "); pw.println(getPulseDuration(false));
        pw.print("    getPulseDuration(pickup=true): "); pw.println(getPulseDuration(true));
        pw.print("    getPulseInDuration(pickup=false): "); pw.println(getPulseInDuration(false));
        pw.print("    getPulseInDuration(pickup=true): "); pw.println(getPulseInDuration(true));
        pw.print("    getPulseInVisibleDuration(): "); pw.println(getPulseVisibleDuration());
        pw.print("    getPulseOutDuration(): "); pw.println(getPulseOutDuration());
        pw.print("    getPulseOnSigMotion(): "); pw.println(getPulseOnSigMotion());
        pw.print("    getVibrateOnSigMotion(): "); pw.println(getVibrateOnSigMotion());
        pw.print("    getPulseOnPickup(): "); pw.println(getPulseOnPickup());
        pw.print("    getVibrateOnPickup(): "); pw.println(getVibrateOnPickup());
        pw.print("    getProxCheckBeforePulse(): "); pw.println(getProxCheckBeforePulse());
        pw.print("    getPulseOnNotifications(): "); pw.println(getPulseOnNotifications());
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

    public int getPulseDuration(boolean pickup) {
        return getPulseInDuration(pickup) + getPulseVisibleDuration() + getPulseOutDuration();
    }

    public int getPulseInDuration(boolean pickup) {
        return pickup
                ? getInt("doze.pulse.duration.in.pickup", R.integer.doze_pulse_duration_in_pickup)
                : getInt("doze.pulse.duration.in", R.integer.doze_pulse_duration_in);
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

    public boolean getPulseOnPickup() {
        return getBoolean("doze.pulse.pickup", R.bool.doze_pulse_on_pick_up);
    }

    public boolean getVibrateOnPickup() {
        return SystemProperties.getBoolean("doze.vibrate.pickup", false);
    }

    public boolean getProxCheckBeforePulse() {
        return getBoolean("doze.pulse.proxcheck", R.bool.doze_proximity_check_before_pulse);
    }

    public boolean getPulseOnNotifications() {
        return getBoolean("doze.pulse.notifications", R.bool.doze_pulse_on_notifications);
    }

    public int getPickupVibrationThreshold() {
        return getInt("doze.pickup.vibration.threshold", R.integer.doze_pickup_vibration_threshold);
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
