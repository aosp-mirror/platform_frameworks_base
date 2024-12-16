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

package android.service.notification;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.Flags;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Represents the set of device effects (affecting display and device behavior in general) that
 * are applied whenever an {@link android.app.AutomaticZenRule} is active.
 */
@FlaggedApi(Flags.FLAG_MODES_API)
public final class ZenDeviceEffects implements Parcelable {

    /**
     * Enum for the user-modifiable fields in this object.
     *
     * @hide
     */
    @IntDef(
            flag = true,
            prefix = {"FIELD_"},
            value = {
                FIELD_GRAYSCALE,
                FIELD_SUPPRESS_AMBIENT_DISPLAY,
                FIELD_DIM_WALLPAPER,
                FIELD_NIGHT_MODE,
                FIELD_DISABLE_AUTO_BRIGHTNESS,
                FIELD_DISABLE_TAP_TO_WAKE,
                FIELD_DISABLE_TILT_TO_WAKE,
                FIELD_DISABLE_TOUCH,
                FIELD_MINIMIZE_RADIO_USAGE,
                FIELD_MAXIMIZE_DOZE,
                FIELD_NIGHT_LIGHT,
                FIELD_EXTRA_EFFECTS
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModifiableField {}

    /**
     * @hide
     */
    public static final int FIELD_GRAYSCALE = 1 << 0;
    /**
     * @hide
     */
    public static final int FIELD_SUPPRESS_AMBIENT_DISPLAY = 1 << 1;
    /**
     * @hide
     */
    public static final int FIELD_DIM_WALLPAPER = 1 << 2;
    /**
     * @hide
     */
    public static final int FIELD_NIGHT_MODE = 1 << 3;
    /**
     * @hide
     */
    public static final int FIELD_DISABLE_AUTO_BRIGHTNESS = 1 << 4;
    /**
     * @hide
     */
    public static final int FIELD_DISABLE_TAP_TO_WAKE = 1 << 5;
    /**
     * @hide
     */
    public static final int FIELD_DISABLE_TILT_TO_WAKE = 1 << 6;
    /**
     * @hide
     */
    public static final int FIELD_DISABLE_TOUCH = 1 << 7;
    /**
     * @hide
     */
    public static final int FIELD_MINIMIZE_RADIO_USAGE = 1 << 8;
    /**
     * @hide
     */
    public static final int FIELD_MAXIMIZE_DOZE = 1 << 9;
    /**
     * @hide
     */
    public static final int FIELD_EXTRA_EFFECTS = 1 << 10;

    /** @hide */
    public static final int FIELD_NIGHT_LIGHT = 1 << 11;

    private static final int MAX_EFFECTS_LENGTH = 2_000; // characters

    private final boolean mGrayscale;
    private final boolean mSuppressAmbientDisplay;
    private final boolean mDimWallpaper;
    private final boolean mNightMode;

    private final boolean mDisableAutoBrightness;
    private final boolean mDisableTapToWake;
    private final boolean mDisableTiltToWake;
    private final boolean mDisableTouch;
    private final boolean mMinimizeRadioUsage;
    private final boolean mMaximizeDoze;
    private final boolean mNightLight;
    private final Set<String> mExtraEffects;

    private ZenDeviceEffects(
            boolean grayscale,
            boolean suppressAmbientDisplay,
            boolean dimWallpaper,
            boolean nightMode,
            boolean disableAutoBrightness,
            boolean disableTapToWake,
            boolean disableTiltToWake,
            boolean disableTouch,
            boolean minimizeRadioUsage,
            boolean maximizeDoze,
            boolean nightLight,
            Set<String> extraEffects) {
        mGrayscale = grayscale;
        mSuppressAmbientDisplay = suppressAmbientDisplay;
        mDimWallpaper = dimWallpaper;
        mNightMode = nightMode;
        mDisableAutoBrightness = disableAutoBrightness;
        mDisableTapToWake = disableTapToWake;
        mDisableTiltToWake = disableTiltToWake;
        mDisableTouch = disableTouch;
        mMinimizeRadioUsage = minimizeRadioUsage;
        mMaximizeDoze = maximizeDoze;
        mNightLight = nightLight;
        mExtraEffects = Collections.unmodifiableSet(extraEffects);
    }

    /** @hide */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public void validate() {
        int extraEffectsLength = 0;
        for (String extraEffect : mExtraEffects) {
            extraEffectsLength += extraEffect.length();
        }
        if (extraEffectsLength > MAX_EFFECTS_LENGTH) {
            throw new IllegalArgumentException(
                    "Total size of extra effects must be at most " + MAX_EFFECTS_LENGTH
                            + " characters");
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof final ZenDeviceEffects that)) return false;
        if (obj == this) return true;

        return this.mGrayscale == that.mGrayscale
                && this.mSuppressAmbientDisplay == that.mSuppressAmbientDisplay
                && this.mDimWallpaper == that.mDimWallpaper
                && this.mNightMode == that.mNightMode
                && this.mDisableAutoBrightness == that.mDisableAutoBrightness
                && this.mDisableTapToWake == that.mDisableTapToWake
                && this.mDisableTiltToWake == that.mDisableTiltToWake
                && this.mDisableTouch == that.mDisableTouch
                && this.mMinimizeRadioUsage == that.mMinimizeRadioUsage
                && this.mMaximizeDoze == that.mMaximizeDoze
                && this.mNightLight == that.mNightLight
                && Objects.equals(this.mExtraEffects, that.mExtraEffects);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mGrayscale,
                mSuppressAmbientDisplay,
                mDimWallpaper,
                mNightMode,
                mDisableAutoBrightness,
                mDisableTapToWake,
                mDisableTiltToWake,
                mDisableTouch,
                mMinimizeRadioUsage,
                mMaximizeDoze,
                mNightLight,
                mExtraEffects);
    }

    @Override
    public String toString() {
        ArrayList<String> effects = new ArrayList<>(11);
        if (mGrayscale) effects.add("grayscale");
        if (mSuppressAmbientDisplay) effects.add("suppressAmbientDisplay");
        if (mDimWallpaper) effects.add("dimWallpaper");
        if (mNightMode) effects.add("nightMode");
        if (mDisableAutoBrightness) effects.add("disableAutoBrightness");
        if (mDisableTapToWake) effects.add("disableTapToWake");
        if (mDisableTiltToWake) effects.add("disableTiltToWake");
        if (mDisableTouch) effects.add("disableTouch");
        if (mMinimizeRadioUsage) effects.add("minimizeRadioUsage");
        if (mMaximizeDoze) effects.add("maximizeDoze");
        if (mNightLight) effects.add("nightLight");
        if (mExtraEffects.size() > 0) {
            effects.add("extraEffects=[" + String.join(",", mExtraEffects) + "]");
        }
        return "[" + String.join(", ", effects) + "]";
    }

    /** @hide */
    public static String fieldsToString(@ModifiableField int bitmask) {
        ArrayList<String> modified = new ArrayList<>();
        if ((bitmask & FIELD_GRAYSCALE) != 0) {
            modified.add("FIELD_GRAYSCALE");
        }
        if ((bitmask & FIELD_SUPPRESS_AMBIENT_DISPLAY) != 0) {
            modified.add("FIELD_SUPPRESS_AMBIENT_DISPLAY");
        }
        if ((bitmask & FIELD_DIM_WALLPAPER) != 0) {
            modified.add("FIELD_DIM_WALLPAPER");
        }
        if ((bitmask & FIELD_NIGHT_MODE) != 0) {
            modified.add("FIELD_NIGHT_MODE");
        }
        if ((bitmask & FIELD_DISABLE_AUTO_BRIGHTNESS) != 0) {
            modified.add("FIELD_DISABLE_AUTO_BRIGHTNESS");
        }
        if ((bitmask & FIELD_DISABLE_TAP_TO_WAKE) != 0) {
            modified.add("FIELD_DISABLE_TAP_TO_WAKE");
        }
        if ((bitmask & FIELD_DISABLE_TILT_TO_WAKE) != 0) {
            modified.add("FIELD_DISABLE_TILT_TO_WAKE");
        }
        if ((bitmask & FIELD_DISABLE_TOUCH) != 0) {
            modified.add("FIELD_DISABLE_TOUCH");
        }
        if ((bitmask & FIELD_MINIMIZE_RADIO_USAGE) != 0) {
            modified.add("FIELD_MINIMIZE_RADIO_USAGE");
        }
        if ((bitmask & FIELD_MAXIMIZE_DOZE) != 0) {
            modified.add("FIELD_MAXIMIZE_DOZE");
        }
        if (((bitmask) & FIELD_NIGHT_LIGHT) != 0) {
            modified.add("FIELD_NIGHT_LIGHT");
        }
        if ((bitmask & FIELD_EXTRA_EFFECTS) != 0) {
            modified.add("FIELD_EXTRA_EFFECTS");
        }
        return "{" + String.join(",", modified) + "}";
    }

    /**
     * Whether the level of color saturation of the display should be set to minimum, effectively
     * switching it to grayscale, while the rule is active.
     */
    public boolean shouldDisplayGrayscale() {
        return mGrayscale;
    }

    /**
     * Whether the ambient (always-on) display feature should be disabled while the rule is active.
     * This will have no effect if the device doesn't support always-on display or if it's not
     * generally enabled.
     */
    public boolean shouldSuppressAmbientDisplay() {
        return mSuppressAmbientDisplay;
    }

    /** Whether the wallpaper should be dimmed while the rule is active. */
    public boolean shouldDimWallpaper() {
        return mDimWallpaper;
    }

    /** Whether night mode (aka dark theme) should be applied while the rule is active. */
    public boolean shouldUseNightMode() {
        return mNightMode;
    }

    /**
     * Whether the display's automatic brightness adjustment should be disabled while the rule is
     * active.
     * @hide
     */
    public boolean shouldDisableAutoBrightness() {
        return mDisableAutoBrightness;
    }

    /**
     * Whether "tap to wake" should be disabled while the rule is active.
     * @hide
     */
    public boolean shouldDisableTapToWake() {
        return mDisableTapToWake;
    }

    /**
     * Whether "tilt to wake" should be disabled while the rule is active.
     * @hide
     */
    public boolean shouldDisableTiltToWake() {
        return mDisableTiltToWake;
    }

    /**
     * Whether touch interactions should be disabled while the rule is active.
     * @hide
     */
    public boolean shouldDisableTouch() {
        return mDisableTouch;
    }

    /**
     * Whether radio (wi-fi, LTE, etc) traffic, and its attendant battery consumption, should be
     * minimized while the rule is active.
     * @hide
     */
    public boolean shouldMinimizeRadioUsage() {
        return mMinimizeRadioUsage;
    }

    /**
     * Whether Doze should be enhanced (e.g. with more aggressive activation, or less frequent
     * maintenance windows) while the rule is active.
     * @hide
     */
    public boolean shouldMaximizeDoze() {
        return mMaximizeDoze;
    }

    /**
     * Whether the night display transformation should be activated while the rule is active.
     *
     * @hide
     */
    public boolean shouldUseNightLight() {
        return mNightLight;
    }

    /**
     * (Immutable) set of extra effects to be applied while the rule is active. Extra effects are
     * not used in AOSP, but OEMs may add support for them by providing a custom
     * {@link DeviceEffectsApplier}.
     * @hide
     */
    @TestApi
    @NonNull
    public Set<String> getExtraEffects() {
        return mExtraEffects;
    }

    /**
     * Whether any of the effects are set up.
     * @hide
     */
    public boolean hasEffects() {
        return mGrayscale
                || mSuppressAmbientDisplay
                || mDimWallpaper
                || mNightMode
                || mDisableAutoBrightness
                || mDisableTapToWake
                || mDisableTiltToWake
                || mDisableTouch
                || mMinimizeRadioUsage
                || mMaximizeDoze
                || mNightLight
                || mExtraEffects.size() > 0;
    }

    /** {@link Parcelable.Creator} that instantiates {@link ZenDeviceEffects} objects. */
    @NonNull
    public static final Creator<ZenDeviceEffects> CREATOR =
            new Creator<ZenDeviceEffects>() {
                @Override
                public ZenDeviceEffects createFromParcel(Parcel in) {
                    return new ZenDeviceEffects(
                            in.readBoolean(),
                            in.readBoolean(),
                            in.readBoolean(),
                            in.readBoolean(),
                            in.readBoolean(),
                            in.readBoolean(),
                            in.readBoolean(),
                            in.readBoolean(),
                            in.readBoolean(),
                            in.readBoolean(),
                            in.readBoolean(),
                            Set.of(in.readArray(String.class.getClassLoader(), String.class)));
                }

                @Override
                public ZenDeviceEffects[] newArray(int size) {
                    return new ZenDeviceEffects[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mGrayscale);
        dest.writeBoolean(mSuppressAmbientDisplay);
        dest.writeBoolean(mDimWallpaper);
        dest.writeBoolean(mNightMode);
        dest.writeBoolean(mDisableAutoBrightness);
        dest.writeBoolean(mDisableTapToWake);
        dest.writeBoolean(mDisableTiltToWake);
        dest.writeBoolean(mDisableTouch);
        dest.writeBoolean(mMinimizeRadioUsage);
        dest.writeBoolean(mMaximizeDoze);
        dest.writeBoolean(mNightLight);
        dest.writeArray(mExtraEffects.toArray(new String[0]));
    }

    /** Builder class for {@link ZenDeviceEffects} objects. */
    @FlaggedApi(Flags.FLAG_MODES_API)
    public static final class Builder {

        private boolean mGrayscale;
        private boolean mSuppressAmbientDisplay;
        private boolean mDimWallpaper;
        private boolean mNightMode;
        private boolean mDisableAutoBrightness;
        private boolean mDisableTapToWake;
        private boolean mDisableTiltToWake;
        private boolean mDisableTouch;
        private boolean mMinimizeRadioUsage;
        private boolean mMaximizeDoze;
        private boolean mNightLight;
        private final HashSet<String> mExtraEffects = new HashSet<>();

        /**
         * Instantiates a new {@link ZenPolicy.Builder} with all effects set to default (disabled).
         */
        public Builder() {
        }

        /**
         * Instantiates a new {@link ZenPolicy.Builder} with all effects set to their corresponding
         * values in the supplied {@link ZenDeviceEffects}.
         */
        public Builder(@NonNull ZenDeviceEffects zenDeviceEffects) {
            mGrayscale = zenDeviceEffects.shouldDisplayGrayscale();
            mSuppressAmbientDisplay = zenDeviceEffects.shouldSuppressAmbientDisplay();
            mDimWallpaper = zenDeviceEffects.shouldDimWallpaper();
            mNightMode = zenDeviceEffects.shouldUseNightMode();
            mDisableAutoBrightness = zenDeviceEffects.shouldDisableAutoBrightness();
            mDisableTapToWake = zenDeviceEffects.shouldDisableTapToWake();
            mDisableTiltToWake = zenDeviceEffects.shouldDisableTiltToWake();
            mDisableTouch = zenDeviceEffects.shouldDisableTouch();
            mMinimizeRadioUsage = zenDeviceEffects.shouldMinimizeRadioUsage();
            mMaximizeDoze = zenDeviceEffects.shouldMaximizeDoze();
            mNightLight = zenDeviceEffects.shouldUseNightLight();
            mExtraEffects.addAll(zenDeviceEffects.getExtraEffects());
        }

        /**
         * Sets whether the level of color saturation of the display should be set to minimum,
         * effectively switching it to grayscale, while the rule is active.
         */
        @NonNull
        public Builder setShouldDisplayGrayscale(boolean grayscale) {
            mGrayscale = grayscale;
            return this;
        }

        /**
         * Sets whether the ambient (always-on) display feature should be disabled while the rule
         * is active. This will have no effect if the device doesn't support always-on display or if
         * it's not generally enabled.
         */
        @NonNull
        public Builder setShouldSuppressAmbientDisplay(boolean suppressAmbientDisplay) {
            mSuppressAmbientDisplay = suppressAmbientDisplay;
            return this;
        }

        /** Sets whether the wallpaper should be dimmed while the rule is active. */
        @NonNull
        public Builder setShouldDimWallpaper(boolean dimWallpaper) {
            mDimWallpaper = dimWallpaper;
            return this;
        }

        /** Sets whether night mode (aka dark theme) should be applied while the rule is active. */
        @NonNull
        public Builder setShouldUseNightMode(boolean nightMode) {
            mNightMode = nightMode;
            return this;
        }

        /**
         * Sets whether the display's automatic brightness adjustment should be disabled while the
         * rule is active.
         * @hide
         */
        @NonNull
        public Builder setShouldDisableAutoBrightness(boolean disableAutoBrightness) {
            mDisableAutoBrightness = disableAutoBrightness;
            return this;
        }

        /**
         * Sets whether "tap to wake" should be disabled while the rule is active.
         * @hide
         */
        @NonNull
        public Builder setShouldDisableTapToWake(boolean disableTapToWake) {
            mDisableTapToWake = disableTapToWake;
            return this;
        }

        /**
         * Sets whether "tilt to wake" should be disabled while the rule is active.
         * @hide
         */
        @NonNull
        public Builder setShouldDisableTiltToWake(boolean disableTiltToWake) {
            mDisableTiltToWake = disableTiltToWake;
            return this;
        }

        /**
         * Sets whether touch interactions should be disabled while the rule is active.
         * @hide
         */
        @NonNull
        public Builder setShouldDisableTouch(boolean disableTouch) {
            mDisableTouch = disableTouch;
            return this;
        }

        /**
         * Sets whether radio (wi-fi, LTE, etc) traffic, and its attendant battery consumption,
         * should be minimized while the rule is active.
         * @hide
         */
        @NonNull
        public Builder setShouldMinimizeRadioUsage(boolean minimizeRadioUsage) {
            mMinimizeRadioUsage = minimizeRadioUsage;
            return this;
        }

        /**
         * Sets whether Doze should be enhanced (e.g. with more aggressive activation, or less
         * frequent maintenance windows) while the rule is active.
         * @hide
         */
        @NonNull
        public Builder setShouldMaximizeDoze(boolean maximizeDoze) {
            mMaximizeDoze = maximizeDoze;
            return this;
        }

        /**
         * Sets whether the night display transformation should be activated while the rule is
         * active.
         *
         * @hide
         */
        @NonNull
        public Builder setShouldUseNightLight(boolean nightLight) {
            mNightLight = nightLight;
            return this;
        }

        /**
         * Sets the extra effects to be applied while the rule is active. Extra effects are not
         * used in AOSP, but OEMs may add support for them by providing a custom
         * {@link DeviceEffectsApplier}.
         *
         * @apiNote The total size of the extra effects (concatenation of strings) is limited.
         *
         * @hide
         */
        @TestApi
        @NonNull
        public Builder setExtraEffects(@NonNull Set<String> extraEffects) {
            Objects.requireNonNull(extraEffects);
            mExtraEffects.clear();
            mExtraEffects.addAll(extraEffects);
            return this;
        }

        /**
         * Adds the supplied extra effects to the set to be applied while the rule is active.
         * Extra effects are not used in AOSP, but OEMs may add support for them by providing a
         * custom {@link DeviceEffectsApplier}.
         *
         * @apiNote The total size of the extra effects (concatenation of strings) is limited.
         *
         * @hide
         */
        @NonNull
        public Builder addExtraEffects(@NonNull Set<String> extraEffects) {
            mExtraEffects.addAll(Objects.requireNonNull(extraEffects));
            return this;
        }

        /**
         * Adds the supplied extra effect to the set to be applied while the rule is active.
         * Extra effects are not used in AOSP, but OEMs may add support for them by providing a
         * custom {@link DeviceEffectsApplier}.
         *
         * @apiNote The total size of the extra effects (concatenation of strings) is limited.
         *
         * @hide
         */
        @NonNull
        public Builder addExtraEffect(@NonNull String extraEffect) {
            mExtraEffects.add(Objects.requireNonNull(extraEffect));
            return this;
        }

        /**
         * Applies the effects that are {@code true} on the supplied {@link ZenDeviceEffects} to
         * this builder (essentially logically-ORing the effect set).
         * @hide
         */
        @NonNull
        public Builder add(@Nullable ZenDeviceEffects effects) {
            if (effects == null) return this;
            if (effects.shouldDisplayGrayscale()) setShouldDisplayGrayscale(true);
            if (effects.shouldSuppressAmbientDisplay()) setShouldSuppressAmbientDisplay(true);
            if (effects.shouldDimWallpaper()) setShouldDimWallpaper(true);
            if (effects.shouldUseNightMode()) setShouldUseNightMode(true);
            if (effects.shouldDisableAutoBrightness()) setShouldDisableAutoBrightness(true);
            if (effects.shouldDisableTapToWake()) setShouldDisableTapToWake(true);
            if (effects.shouldDisableTiltToWake()) setShouldDisableTiltToWake(true);
            if (effects.shouldDisableTouch()) setShouldDisableTouch(true);
            if (effects.shouldMinimizeRadioUsage()) setShouldMinimizeRadioUsage(true);
            if (effects.shouldMaximizeDoze()) setShouldMaximizeDoze(true);
            if (effects.shouldUseNightLight()) setShouldUseNightLight(true);
            addExtraEffects(effects.getExtraEffects());
            return this;
        }

        /** Builds a {@link ZenDeviceEffects} object based on the builder's state. */
        @NonNull
        public ZenDeviceEffects build() {
            return new ZenDeviceEffects(
                    mGrayscale,
                    mSuppressAmbientDisplay,
                    mDimWallpaper,
                    mNightMode,
                    mDisableAutoBrightness,
                    mDisableTapToWake,
                    mDisableTiltToWake,
                    mDisableTouch,
                    mMinimizeRadioUsage,
                    mMaximizeDoze,
                    mNightLight,
                    mExtraEffects);
        }
    }
}
