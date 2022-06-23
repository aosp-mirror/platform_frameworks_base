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
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A class to encapsulate a collection of attributes describing information about a vibration
 */
public final class VibrationAttributes implements Parcelable {
    private static final String TAG = "VibrationAttributes";

    /**
     * @hide
     */
    @IntDef(prefix = { "USAGE_CLASS_" }, value = {
            USAGE_CLASS_UNKNOWN,
            USAGE_CLASS_ALARM,
            USAGE_CLASS_FEEDBACK,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UsageClass{}

    /**
     * @hide
     */
    @IntDef(prefix = { "USAGE_" }, value = {
            USAGE_UNKNOWN,
            USAGE_ALARM,
            USAGE_RINGTONE,
            USAGE_NOTIFICATION,
            USAGE_COMMUNICATION_REQUEST,
            USAGE_TOUCH,
            USAGE_PHYSICAL_EMULATION,
            USAGE_HARDWARE_FEEDBACK,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Usage{}

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
     * communication, such as a VoIP communication or video-conference.
     */
    public static final int USAGE_COMMUNICATION_REQUEST = 0x40 | USAGE_CLASS_ALARM;
    /**
     * Usage value to use for touch vibrations.
     */
    public static final int USAGE_TOUCH = 0x10 | USAGE_CLASS_FEEDBACK;
    /**
     * Usage value to use for vibrations which emulate physical effects, such as edge squeeze.
     */
    public static final int USAGE_PHYSICAL_EMULATION = 0x20 | USAGE_CLASS_FEEDBACK;
    /**
     * Usage value to use for vibrations which provide a feedback for hardware interaction,
     * such as a fingerprint sensor.
     */
    public static final int USAGE_HARDWARE_FEEDBACK = 0x30 | USAGE_CLASS_FEEDBACK;

    /**
     * @hide
     */
    @IntDef(prefix = { "FLAG_" }, value = {
            FLAG_BYPASS_INTERRUPTION_POLICY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flag{}

    /**
     * Flag requesting vibration effect to be played even under limited interruptions.
     *
     * <p>Only privileged apps can ignore user settings that limit interruptions, and this
     * flag will be ignored otherwise.
     */
    public static final int FLAG_BYPASS_INTERRUPTION_POLICY = 0x1;

    /**
     * All flags supported by vibrator service, update it when adding new flag.
     * @hide
     */
    public static final int FLAG_ALL_SUPPORTED = FLAG_BYPASS_INTERRUPTION_POLICY;

    // If a vibration is playing for longer than 5s, it's probably not haptic feedback
    private static final long MAX_HAPTIC_FEEDBACK_DURATION = 5000;

    private final int mUsage;
    private final int mFlags;
    private final int mOriginalAudioUsage;

    private VibrationAttributes(int usage, int audioUsage, int flags) {
        mUsage = usage;
        mOriginalAudioUsage = audioUsage;
        mFlags = flags & FLAG_ALL_SUPPORTED;
    }

    /**
     * Return the vibration usage class.
     * @return USAGE_CLASS_ALARM, USAGE_CLASS_FEEDBACK or USAGE_CLASS_UNKNOWN
     */
    public int getUsageClass() {
        return mUsage & USAGE_CLASS_MASK;
    }

    /**
     * Return the vibration usage.
     * @return one of the values that can be set in {@link Builder#setUsage(int)}
     */
    public int getUsage() {
        return mUsage;
    }

    /**
     * Return the flags.
     * @return a combined mask of all flags
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Check whether a flag is set
     * @return true if a flag is set and false otherwise
     */
    public boolean isFlagSet(int flag) {
        return (mFlags & flag) > 0;
    }

    /**
     * Return {@link AudioAttributes} usage equivalent to {@link #getUsage()}.
     * @return one of {@link AudioAttributes#SDK_USAGES} that represents {@link #getUsage()}
     * @hide
     */
    @TestApi
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
                return AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST;
            case USAGE_RINGTONE:
                return AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
            case USAGE_TOUCH:
                return AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
            case USAGE_ALARM:
                return AudioAttributes.USAGE_ALARM;
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
    public static String usageToString(int usage) {
        switch (usage) {
            case USAGE_UNKNOWN:
                return "UNKNOWN";
            case USAGE_ALARM:
                return "ALARM";
            case USAGE_RINGTONE:
                return "RIGNTONE";
            case USAGE_NOTIFICATION:
                return "NOTIFICATION";
            case USAGE_COMMUNICATION_REQUEST:
                return "COMMUNICATION_REQUEST";
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
         * @hide
         */
        @TestApi
        public Builder(@NonNull AudioAttributes audio, @Nullable VibrationEffect effect) {
            setUsage(audio);
            setFlags(audio);
            applyHapticFeedbackHeuristics(effect);
        }

        private void applyHapticFeedbackHeuristics(@Nullable VibrationEffect effect) {
            if (effect != null) {
                PrebakedSegment prebaked = extractPrebakedSegment(effect);
                if (mUsage == USAGE_UNKNOWN && prebaked != null) {
                    switch (prebaked.getEffectId()) {
                        case VibrationEffect.EFFECT_CLICK:
                        case VibrationEffect.EFFECT_DOUBLE_CLICK:
                        case VibrationEffect.EFFECT_HEAVY_CLICK:
                        case VibrationEffect.EFFECT_TEXTURE_TICK:
                        case VibrationEffect.EFFECT_TICK:
                        case VibrationEffect.EFFECT_POP:
                        case VibrationEffect.EFFECT_THUD:
                            mUsage = USAGE_TOUCH;
                            break;
                        default:
                            Slog.w(TAG, "Unknown prebaked vibration effect, assuming it isn't "
                                    + "haptic feedback");
                    }
                }
                final long duration = effect.getDuration();
                if (mUsage == USAGE_UNKNOWN && duration >= 0
                        && duration < MAX_HAPTIC_FEEDBACK_DURATION) {
                    mUsage = USAGE_TOUCH;
                }
            }
        }

        @Nullable
        private PrebakedSegment extractPrebakedSegment(VibrationEffect effect) {
            if (effect instanceof VibrationEffect.Composed) {
                VibrationEffect.Composed composed = (VibrationEffect.Composed) effect;
                if (composed.getSegments().size() == 1) {
                    VibrationEffectSegment segment = composed.getSegments().get(0);
                    if (segment instanceof PrebakedSegment) {
                        return (PrebakedSegment) segment;
                    }
                }
            }
            return null;
        }

        private void setUsage(@NonNull AudioAttributes audio) {
            mOriginalAudioUsage = audio.getUsage();
            switch (audio.getUsage()) {
                case AudioAttributes.USAGE_NOTIFICATION:
                case AudioAttributes.USAGE_NOTIFICATION_EVENT:
                case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
                case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
                    mUsage = USAGE_NOTIFICATION;
                    break;
                case AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
                case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY:
                    mUsage = USAGE_COMMUNICATION_REQUEST;
                    break;
                case AudioAttributes.USAGE_NOTIFICATION_RINGTONE:
                    mUsage = USAGE_RINGTONE;
                    break;
                case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION:
                    mUsage = USAGE_TOUCH;
                    break;
                case AudioAttributes.USAGE_ALARM:
                    mUsage = USAGE_ALARM;
                    break;
                default:
                    mUsage = USAGE_UNKNOWN;
            }
        }

        private void setFlags(@NonNull AudioAttributes audio) {
            if ((audio.getAllFlags() & AudioAttributes.FLAG_BYPASS_INTERRUPTION_POLICY) != 0) {
                mFlags |= FLAG_BYPASS_INTERRUPTION_POLICY;
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
         * Sets the attribute describing the type of corresponding vibration.
         * @param usage one of {@link VibrationAttributes#USAGE_ALARM},
         * {@link VibrationAttributes#USAGE_RINGTONE},
         * {@link VibrationAttributes#USAGE_NOTIFICATION},
         * {@link VibrationAttributes#USAGE_COMMUNICATION_REQUEST},
         * {@link VibrationAttributes#USAGE_TOUCH},
         * {@link VibrationAttributes#USAGE_PHYSICAL_EMULATION},
         * {@link VibrationAttributes#USAGE_HARDWARE_FEEDBACK}.
         * @return the same Builder instance.
         */
        public @NonNull Builder setUsage(int usage) {
            mOriginalAudioUsage = AudioAttributes.USAGE_UNKNOWN;
            mUsage = usage;
            return this;
        }

        /**
         * Set flags
         * @param flags combination of flags to be set.
         * @param mask Bit range that should be changed.
         * @return the same Builder instance.
         */
        public @NonNull Builder setFlags(int flags, int mask) {
            mask &= FLAG_ALL_SUPPORTED;
            mFlags = (mFlags & ~mask) | (flags & mask);
            return this;
        }
    }
}

