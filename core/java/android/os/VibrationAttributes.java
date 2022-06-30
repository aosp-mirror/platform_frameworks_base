/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.media.AudioAttributes;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Encapsulates a collection of attributes describing information about a vibration.
 */
public final class VibrationAttributes implements Parcelable {
    private static final String TAG = "VibrationAttributes";

    /** @hide */
    @IntDef(prefix = { "USAGE_CLASS_" }, value = {
            USAGE_CLASS_UNKNOWN,
            USAGE_CLASS_ALARM,
            USAGE_CLASS_FEEDBACK,
            USAGE_CLASS_MEDIA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UsageClass {}

    /** @hide */
    @IntDef(prefix = { "USAGE_" }, value = {
            USAGE_UNKNOWN,
            USAGE_ACCESSIBILITY,
            USAGE_ALARM,
            USAGE_COMMUNICATION_REQUEST,
            USAGE_HARDWARE_FEEDBACK,
            USAGE_MEDIA,
            USAGE_NOTIFICATION,
            USAGE_PHYSICAL_EMULATION,
            USAGE_RINGTONE,
            USAGE_TOUCH,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Usage {}

    /**
     * Vibration usage filter value to match all usages.
     * @hide
     */
    public static final int USAGE_FILTER_MATCH_ALL = -1;
    /**
     * Vibration usage class value to use when the vibration usage class is unknown.
     */
    public static final int USAGE_CLASS_UNKNOWN = 0x0;
    /**
     * Vibration usage class value to use when the vibration is initiated to catch user's
     * attention, such as alarm, ringtone, and notification vibrations.
     */
    public static final int USAGE_CLASS_ALARM = 0x1;
    /**
     * Vibration usage class value to use when the vibration is initiated as a response to user's
     * actions, such as emulation of physical effects, and texting feedback vibration.
     */
    public static final int USAGE_CLASS_FEEDBACK = 0x2;
    /**
     * Vibration usage class value to use when the vibration is part of media, such as music, movie,
     * soundtrack, game or animations.
     */
    public static final int USAGE_CLASS_MEDIA = 0x3;

    /**
     * Mask for vibration usage class value.
     */
    public static final int USAGE_CLASS_MASK = 0xF;

    /**
     * Usage value to use when usage is unknown.
     */
    public static final int USAGE_UNKNOWN = 0x0 | USAGE_CLASS_UNKNOWN;
    /**
     * Usage value to use for alarm vibrations.
     */
    public static final int USAGE_ALARM = 0x10 | USAGE_CLASS_ALARM;
    /**
     * Usage value to use for ringtone vibrations.
     */
    public static final int USAGE_RINGTONE = 0x20 | USAGE_CLASS_ALARM;
    /**
     * Usage value to use for notification vibrations.
     */
    public static final int USAGE_NOTIFICATION = 0x30 | USAGE_CLASS_ALARM;
    /**
     * Usage value to use for vibrations which mean a request to enter/end a
     * communication with the user, such as a voice prompt.
     */
    public static final int USAGE_COMMUNICATION_REQUEST = 0x40 | USAGE_CLASS_ALARM;
    /**
     * Usage value to use for touch vibrations.
     *
     * <p>Most typical haptic feedback should be classed as <em>touch</em> feedback. Examples
     * include vibrations for tap, long press, drag and scroll.
     */
    public static final int USAGE_TOUCH = 0x10 | USAGE_CLASS_FEEDBACK;
    /**
     * Usage value to use for vibrations which emulate physical hardware reactions,
     * such as edge squeeze.
     *
     * <p>Note that normal screen-touch feedback "click" effects would typically be
     * classed as {@link #USAGE_TOUCH}, and that on-screen "physical" animations
     * like bouncing would be {@link #USAGE_MEDIA}.
     */
    public static final int USAGE_PHYSICAL_EMULATION = 0x20 | USAGE_CLASS_FEEDBACK;
    /**
     * Usage value to use for vibrations which provide a feedback for hardware
     * component interaction, such as a fingerprint sensor.
     */
    public static final int USAGE_HARDWARE_FEEDBACK = 0x30 | USAGE_CLASS_FEEDBACK;
    /**
     * Usage value to use for accessibility vibrations, such as with a screen reader.
     */
    public static final int USAGE_ACCESSIBILITY = 0x40 | USAGE_CLASS_FEEDBACK;
    /**
     * Usage value to use for media vibrations, such as music, movie, soundtrack, animations, games,
     * or any interactive media that isn't for touch feedback specifically.
     */
    public static final int USAGE_MEDIA = 0x10 | USAGE_CLASS_MEDIA;

    /**
     * @hide
     */
    @IntDef(prefix = { "FLAG_" }, flag = true, value = {
            FLAG_BYPASS_INTERRUPTION_POLICY,
            FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flag{}

    /**
     * Flag requesting vibration effect to be played even under limited interruptions.
     */
    public static final int FLAG_BYPASS_INTERRUPTION_POLICY = 0x1;

    /**
     * Flag requesting vibration effect to be played even when user settings are disabling it.
     *
     * <p>Flag introduced to represent
     * {@link android.view.HapticFeedbackConstants#FLAG_IGNORE_GLOBAL_SETTING} and
     * {@link AudioAttributes#FLAG_BYPASS_MUTE}.
     *
     * @hide
     */
    public static final int FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF = 0x2;

    /**
     * Flag requesting vibration effect to be played with fresh user settings values.
     *
     * <p>This flag is not protected by any permission, but vibrations that use it require an extra
     * query of user vibration intensity settings, ringer mode and other controls that affect the
     * vibration effect playback, which can increase the latency for the overall request.
     *
     * <p>This is intended to be used on scenarios where the user settings might have changed
     * recently, and needs to be applied to this vibration, like settings controllers that preview
     * newly set intensities to the user.
     *
     * @hide
     */
    public static final int FLAG_INVALIDATE_SETTINGS_CACHE = 0x3;

    /**
     * All flags supported by vibrator service, update it when adding new flag.
     * @hide
     */
    public static final int FLAG_ALL_SUPPORTED =
            FLAG_BYPASS_INTERRUPTION_POLICY | FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF
                    | FLAG_INVALIDATE_SETTINGS_CACHE;

    /** Creates a new {@link VibrationAttributes} instance with given usage. */
    public static @NonNull VibrationAttributes createForUsage(@Usage int usage) {
        return new VibrationAttributes.Builder().setUsage(usage).build();
    }

    private final int mUsage;
    private final int mFlags;
    private final int mOriginalAudioUsage;

    private VibrationAttributes(@Usage int usage, @AudioAttributes.AttributeUsage int audioUsage,
            @Flag int flags) {
        mUsage = usage;
        mOriginalAudioUsage = audioUsage;
        mFlags = flags & FLAG_ALL_SUPPORTED;
    }

    /**
     * Return the vibration usage class.
     */
    @UsageClass
    public int getUsageClass() {
        return mUsage & USAGE_CLASS_MASK;
    }

    /**
     * Return the vibration usage.
     */
    @Usage
    public int getUsage() {
        return mUsage;
    }

    /**
     * Return the flags.
     * @return a combined mask of all flags
     */
    @Flag
    public int getFlags() {
        return mFlags;
    }

    /**
     * Check whether a flag is set
     * @return true if a flag is set and false otherwise
     */
    public boolean isFlagSet(@Flag int flag) {
        return (mFlags & flag) > 0;
    }

    /**
     * Return {@link AudioAttributes} usage equivalent to {@link #getUsage()}.
     * @return one of {@link AudioAttributes#SDK_USAGES} that represents {@link #getUsage()}
     * @hide
     */
    @TestApi
    @AudioAttributes.AttributeUsage
    public int getAudioUsage() {
        if (mOriginalAudioUsage != AudioAttributes.USAGE_UNKNOWN) {
            // Return same audio usage set in the Builder.
            return mOriginalAudioUsage;
        }
        // Return correct audio usage based on the vibration usage set in the Builder.
        switch (mUsage) {
            case USAGE_NOTIFICATION:
                return AudioAttributes.USAGE_NOTIFICATION;
            case USAGE_COMMUNICATION_REQUEST:
                return AudioAttributes.USAGE_VOICE_COMMUNICATION;
            case USAGE_RINGTONE:
                return AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
            case USAGE_TOUCH:
                return AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
            case USAGE_ALARM:
                return AudioAttributes.USAGE_ALARM;
            case USAGE_ACCESSIBILITY:
                return AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY;
            case USAGE_MEDIA:
                return AudioAttributes.USAGE_MEDIA;
            default:
                return AudioAttributes.USAGE_UNKNOWN;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mUsage);
        dest.writeInt(mOriginalAudioUsage);
        dest.writeInt(mFlags);
    }

    private VibrationAttributes(Parcel src) {
        mUsage = src.readInt();
        mOriginalAudioUsage = src.readInt();
        mFlags = src.readInt();
    }

    public static final @NonNull Parcelable.Creator<VibrationAttributes>
            CREATOR = new Parcelable.Creator<VibrationAttributes>() {
                public VibrationAttributes createFromParcel(Parcel p) {
                    return new VibrationAttributes(p);
                }
                public VibrationAttributes[] newArray(int size) {
                    return new VibrationAttributes[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        VibrationAttributes rhs = (VibrationAttributes) o;
        return mUsage == rhs.mUsage && mOriginalAudioUsage == rhs.mOriginalAudioUsage
                && mFlags == rhs.mFlags;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUsage, mOriginalAudioUsage, mFlags);
    }

    @Override
    public String toString() {
        return "VibrationAttributes:"
                + " Usage=" + usageToString()
                + " Audio Usage= " + AudioAttributes.usageToString(mOriginalAudioUsage)
                + " Flags=" + mFlags;
    }

    /** @hide */
    public String usageToString() {
        return usageToString(mUsage);
    }

    /** @hide */
    public static String usageToString(@Usage int usage) {
        switch (usage) {
            case USAGE_UNKNOWN:
                return "UNKNOWN";
            case USAGE_ALARM:
                return "ALARM";
            case USAGE_ACCESSIBILITY:
                return "ACCESSIBILITY";
            case USAGE_RINGTONE:
                return "RINGTONE";
            case USAGE_NOTIFICATION:
                return "NOTIFICATION";
            case USAGE_COMMUNICATION_REQUEST:
                return "COMMUNICATION_REQUEST";
            case USAGE_MEDIA:
                return "MEDIA";
            case USAGE_TOUCH:
                return "TOUCH";
            case USAGE_PHYSICAL_EMULATION:
                return "PHYSICAL_EMULATION";
            case USAGE_HARDWARE_FEEDBACK:
                return "HARDWARE_FEEDBACK";
            default:
                return "unknown usage " + usage;
        }
    }

    /**
     * Builder class for {@link VibrationAttributes} objects.
     * By default, all information is set to UNKNOWN.
     */
    public static final class Builder {
        private int mUsage = USAGE_UNKNOWN;
        private int mOriginalAudioUsage = AudioAttributes.USAGE_UNKNOWN;
        private int mFlags = 0x0;

        /**
         * Constructs a new Builder with the defaults.
         */
        public Builder() {
        }

        /**
         * Constructs a new Builder from a given VibrationAttributes.
         */
        public Builder(@Nullable VibrationAttributes vib) {
            if (vib != null) {
                mUsage = vib.mUsage;
                mOriginalAudioUsage = vib.mOriginalAudioUsage;
                mFlags = vib.mFlags;
            }
        }

        /**
         * Constructs a new Builder from AudioAttributes.
         */
        public Builder(@NonNull AudioAttributes audio) {
            setUsage(audio);
            setFlags(audio);
        }

        private void setUsage(@NonNull AudioAttributes audio) {
            mOriginalAudioUsage = audio.getUsage();
            switch (audio.getUsage()) {
                case AudioAttributes.USAGE_NOTIFICATION:
                case AudioAttributes.USAGE_NOTIFICATION_EVENT:
                case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
                case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
                case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
                    mUsage = USAGE_NOTIFICATION;
                    break;
                case AudioAttributes.USAGE_VOICE_COMMUNICATION:
                case AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING:
                case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
                case AudioAttributes.USAGE_ASSISTANT:
                    mUsage = USAGE_COMMUNICATION_REQUEST;
                    break;
                case AudioAttributes.USAGE_NOTIFICATION_RINGTONE:
                    mUsage = USAGE_RINGTONE;
                    break;
                case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY:
                    mUsage = USAGE_ACCESSIBILITY;
                    break;
                case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION:
                    mUsage = USAGE_TOUCH;
                    break;
                case AudioAttributes.USAGE_ALARM:
                    mUsage = USAGE_ALARM;
                    break;
                case AudioAttributes.USAGE_MEDIA:
                case AudioAttributes.USAGE_GAME:
                    mUsage = USAGE_MEDIA;
                    break;
                default:
                    mUsage = USAGE_UNKNOWN;
            }
        }

        private void setFlags(@NonNull AudioAttributes audio) {
            if ((audio.getAllFlags() & AudioAttributes.FLAG_BYPASS_INTERRUPTION_POLICY) != 0) {
                mFlags |= FLAG_BYPASS_INTERRUPTION_POLICY;
            }
            if ((audio.getAllFlags() & AudioAttributes.FLAG_BYPASS_MUTE) != 0) {
                // Muted audio stream translates to vibration usage having the value
                // Vibrator.VIBRATION_INTENSITY_OFF set in the user setting.
                mFlags |= FLAG_BYPASS_USER_VIBRATION_INTENSITY_OFF;
            }
        }

        /**
         * Combines all of the attributes that have been set and returns a new
         * {@link VibrationAttributes} object.
         * @return a new {@link VibrationAttributes} object
         */
        public @NonNull VibrationAttributes build() {
            VibrationAttributes ans = new VibrationAttributes(mUsage, mOriginalAudioUsage, mFlags);
            return ans;
        }

        /**
         * Sets the attribute describing the type of the corresponding vibration.
         * @param usage The type of usage for the vibration
         * @return the same Builder instance.
         */
        public @NonNull Builder setUsage(@Usage int usage) {
            mOriginalAudioUsage = AudioAttributes.USAGE_UNKNOWN;
            mUsage = usage;
            return this;
        }

        /**
         * Sets only the flags specified in the bitmask, leaving the other supported flag values
         * unchanged in the builder.
         *
         * @param flags Combination of flags to be set.
         * @param mask Bit range that should be changed.
         * @return the same Builder instance.
         */
        public @NonNull Builder setFlags(@Flag int flags, int mask) {
            mask &= FLAG_ALL_SUPPORTED;
            mFlags = (mFlags & ~mask) | (flags & mask);
            return this;
        }

        /**
         * Set all supported flags with given combination of flags, overriding any previous values
         * set to this builder.
         *
         * @param flags combination of flags to be set.
         * @return the same Builder instance.
         *
         * @hide
         */
        public @NonNull Builder setFlags(@Flag int flags) {
            return setFlags(flags, FLAG_ALL_SUPPORTED);
        }
    }
}
