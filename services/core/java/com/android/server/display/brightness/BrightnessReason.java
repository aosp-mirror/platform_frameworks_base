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

package com.android.server.display.brightness;

import android.util.Slog;

import java.util.Objects;

/**
 * Stores data about why the brightness was changed. Made up of one main
 * {@code BrightnessReason.REASON_*} reason and various {@code BrightnessReason.MODIFIER_*}
 * modifiers.
 */
public final class BrightnessReason {
    private static final String TAG = "BrightnessReason";

    public static final int REASON_UNKNOWN = 0;
    public static final int REASON_MANUAL = 1;
    public static final int REASON_DOZE = 2;
    public static final int REASON_DOZE_DEFAULT = 3;
    public static final int REASON_AUTOMATIC = 4;
    public static final int REASON_SCREEN_OFF = 5;
    public static final int REASON_OVERRIDE = 6;
    public static final int REASON_TEMPORARY = 7;
    public static final int REASON_BOOST = 8;
    public static final int REASON_SCREEN_OFF_BRIGHTNESS_SENSOR = 9;
    public static final int REASON_FOLLOWER = 10;
    public static final int REASON_OFFLOAD = 11;
    public static final int REASON_DOZE_INITIAL = 12;
    public static final int REASON_MAX = REASON_DOZE_INITIAL;

    public static final int MODIFIER_DIMMED = 0x1;
    public static final int MODIFIER_LOW_POWER = 0x2;
    public static final int MODIFIER_HDR = 0x4;
    public static final int MODIFIER_THROTTLED = 0x8;
    public static final int MODIFIER_MIN_LUX = 0x10;
    public static final int MODIFIER_MIN_USER_SET_LOWER_BOUND = 0x20;
    public static final int MODIFIER_MASK = MODIFIER_DIMMED | MODIFIER_LOW_POWER | MODIFIER_HDR
            | MODIFIER_THROTTLED | MODIFIER_MIN_LUX | MODIFIER_MIN_USER_SET_LOWER_BOUND;

    // ADJUSTMENT_*
    // These things can happen at any point, even if the main brightness reason doesn't
    // fundamentally change, so they're not stored.

    // Auto-brightness adjustment factor changed
    public static final int ADJUSTMENT_AUTO_TEMP = 0x1;
    // Temporary adjustment to the auto-brightness adjustment factor.
    public static final int ADJUSTMENT_AUTO = 0x2;

    // One of REASON_*
    private int mReason;
    // Any number of MODIFIER_*
    private int mModifier;

    /**
     * A utility to clone a BrightnessReason from another BrightnessReason event
     *
     * @param other The BrightnessReason object which is to be cloned
     */
    public void set(BrightnessReason other) {
        setReason(other == null ? REASON_UNKNOWN : other.mReason);
        setModifier(other == null ? 0 : other.mModifier);
    }

    /**
     * A utility to add a modifier to the BrightnessReason object
     *
     * @param modifier The modifier which is to be added
     */
    public void addModifier(int modifier) {
        setModifier(modifier | this.mModifier);
    }


    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof BrightnessReason)) {
            return false;
        }
        BrightnessReason other = (BrightnessReason) obj;
        return other.mReason == mReason && other.mModifier == mModifier;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mReason, mModifier);
    }

    @Override
    public String toString() {
        return toString(0);
    }

    /**
     * A utility to stringify a BrightnessReason
     *
     * @param adjustments Indicates if the adjustments field is to be added in the stringify version
     *                    of the BrightnessReason
     * @return A stringified BrightnessReason
     */
    public String toString(int adjustments) {
        final StringBuilder sb = new StringBuilder();
        sb.append(reasonToString(mReason));
        sb.append(" [");
        if ((adjustments & ADJUSTMENT_AUTO_TEMP) != 0) {
            sb.append(" temp_adj");
        }
        if ((adjustments & ADJUSTMENT_AUTO) != 0) {
            sb.append(" auto_adj");
        }
        if ((mModifier & MODIFIER_LOW_POWER) != 0) {
            sb.append(" low_pwr");
        }
        if ((mModifier & MODIFIER_DIMMED) != 0) {
            sb.append(" dim");
        }
        if ((mModifier & MODIFIER_HDR) != 0) {
            sb.append(" hdr");
        }
        if ((mModifier & MODIFIER_THROTTLED) != 0) {
            sb.append(" throttled");
        }
        if ((mModifier & MODIFIER_MIN_LUX) != 0) {
            sb.append(" lux_lower_bound");
        }
        if ((mModifier & MODIFIER_MIN_USER_SET_LOWER_BOUND) != 0) {
            sb.append(" user_min_pref");
        }
        int strlen = sb.length();
        if (sb.charAt(strlen - 1) == '[') {
            sb.setLength(strlen - 2);
        } else {
            sb.append(" ]");
        }
        return sb.toString();
    }

    /**
     * A utility to set the reason of the BrightnessReason object
     *
     * @param reason The value to which the reason is to be updated.
     */
    public void setReason(int reason) {
        if (reason < REASON_UNKNOWN || reason > REASON_MAX) {
            Slog.w(TAG, "brightness reason out of bounds: " + reason);
        } else {
            this.mReason = reason;
        }
    }

    public int getReason() {
        return mReason;
    }

    public int getModifier() {
        return mModifier;
    }

    /**
     * A utility to set the modified of the current BrightnessReason object
     *
     * @param modifier The value to which the modifier is to be updated
     */
    public void setModifier(int modifier) {
        if ((modifier & ~MODIFIER_MASK) != 0) {
            Slog.w(TAG, "brightness modifier out of bounds: 0x"
                    + Integer.toHexString(modifier));
        } else {
            this.mModifier = modifier;
        }
    }

    private String reasonToString(int reason) {
        switch (reason) {
            case REASON_MANUAL:
                return "manual";
            case REASON_DOZE:
                return "doze";
            case REASON_DOZE_DEFAULT:
                return "doze_default";
            case REASON_AUTOMATIC:
                return "automatic";
            case REASON_SCREEN_OFF:
                return "screen_off";
            case REASON_OVERRIDE:
                return "override";
            case REASON_TEMPORARY:
                return "temporary";
            case REASON_BOOST:
                return "boost";
            case REASON_SCREEN_OFF_BRIGHTNESS_SENSOR:
                return "screen_off_brightness_sensor";
            case REASON_FOLLOWER:
                return "follower";
            case REASON_OFFLOAD:
                return "offload";
            case REASON_DOZE_INITIAL:
                return "doze_initial";
            default:
                return Integer.toString(reason);
        }
    }
}
