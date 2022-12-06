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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Class to represent the attributes of the audio mixer: its format, which represents by an
 * {@link AudioFormat} object and mixer behavior.
 */
public final class AudioMixerAttributes implements Parcelable {

    /**
     * Constant indicating the audio mixer behavior will follow the default platform behavior, which
     * is mixing all audio sources in the mixer.
     */
    public static final int MIXER_BEHAVIOR_DEFAULT = 0;

    /**
     * Constant indicating the audio mixer behavior is bit-perfect, which indicates there will
     * not be mixing happen, the audio data will be sent as is down to the HAL.
     */
    public static final int MIXER_BEHAVIOR_BIT_PERFECT = 1;

    /** @hide */
    @IntDef(flag = false, prefix = "MIXER_BEHAVIOR_", value = {
            MIXER_BEHAVIOR_DEFAULT,
            MIXER_BEHAVIOR_BIT_PERFECT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MixerBehavior {}

    private final AudioFormat mFormat;
    private final @MixerBehavior int mMixerBehavior;

    /**
     * Constructor from {@link AudioFormat} and mixer behavior
     */
    AudioMixerAttributes(AudioFormat format, @MixerBehavior int mixerBehavior) {
        mFormat = format;
        mMixerBehavior = mixerBehavior;
    }

    /**
     * Return the format of the audio mixer. The format is an {@link AudioFormat} object, which
     * includes encoding format, sample rate and channel mask or channel index mask.
     * @return the format of the audio mixer.
     */
    @NonNull
    public AudioFormat getFormat() {
        return mFormat;
    }

    /**
     * Returns the mixer behavior for this set of mixer attributes.
     *
     * @return the mixer behavior
     */
    public @MixerBehavior int getMixerBehavior() {
        return mMixerBehavior;
    }

    /**
     * Builder class for {@link AudioMixerAttributes} objects.
     */
    public static final class Builder {
        private final AudioFormat mFormat;
        private int mMixerBehavior = MIXER_BEHAVIOR_DEFAULT;

        /**
         * Constructs a new Builder with the defaults.
         *
         * @param format the {@link AudioFormat} for the audio mixer.
         */
        public Builder(@NonNull AudioFormat format) {
            Objects.requireNonNull(format);
            mFormat = format;
        }

        /**
         * Combines all attributes that have been set and returns a new {@link AudioMixerAttributes}
         * object.
         * @return a new {@link AudioMixerAttributes} object
         */
        public @NonNull AudioMixerAttributes build() {
            AudioMixerAttributes ama = new AudioMixerAttributes(mFormat, mMixerBehavior);
            return ama;
        }

        /**
         * Sets the mixer behavior for the audio mixer
         * @param mixerBehavior must be {@link #MIXER_BEHAVIOR_DEFAULT} or
         *                      {@link #MIXER_BEHAVIOR_BIT_PERFECT}.
         * @return the same Builder instance.
         */
        public @NonNull Builder setMixerBehavior(@MixerBehavior int mixerBehavior) {
            switch (mixerBehavior) {
                case MIXER_BEHAVIOR_DEFAULT:
                case MIXER_BEHAVIOR_BIT_PERFECT:
                    mMixerBehavior = mixerBehavior;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid mixer behavior " + mixerBehavior);
            }
            return this;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFormat, mMixerBehavior);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioMixerAttributes that = (AudioMixerAttributes) o;
        return (mFormat.equals(that.mFormat)
                && (mMixerBehavior == that.mMixerBehavior));
    }

    private String mixerBehaviorToString(@MixerBehavior int mixerBehavior) {
        switch (mixerBehavior) {
            case MIXER_BEHAVIOR_DEFAULT:
                return "default";
            case MIXER_BEHAVIOR_BIT_PERFECT:
                return "bit-perfect";
            default:
                return "unknown";
        }
    }

    @Override
    public String toString() {
        return new String("AudioMixerAttributes:"
                + " format:" + mFormat.toString()
                + " mixer behavior:" + mixerBehaviorToString(mMixerBehavior));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mFormat, flags);
        dest.writeInt(mMixerBehavior);
    }

    private AudioMixerAttributes(@NonNull Parcel in) {
        mFormat = in.readParcelable(AudioFormat.class.getClassLoader(), AudioFormat.class);
        mMixerBehavior = in.readInt();
    }

    public static final @NonNull Parcelable.Creator<AudioMixerAttributes> CREATOR =
            new Parcelable.Creator<AudioMixerAttributes>() {
                /**
                 * Rebuilds an AudioMixerAttributes previously stored with writeToParcel().
                 * @param p Parcel object to read the AudioMixerAttributes from
                 * @return a new AudioMixerAttributes created from the data in the parcel
                 */
                public AudioMixerAttributes createFromParcel(Parcel p) {
                    return new AudioMixerAttributes(p);
                }

                public AudioMixerAttributes[] newArray(int size) {
                    return new AudioMixerAttributes[size];
                }
            };
}
