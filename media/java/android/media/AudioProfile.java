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

package android.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An AudioProfile is specific to an audio format and lists supported sampling rates and
 * channel masks for that format.  An {@link AudioDeviceInfo} has a list of supported AudioProfiles.
 * There can be multiple profiles whose encoding format is the same. This usually happens when
 * an encoding format is only supported when it is encapsulated by some particular encapsulation
 * types. If there are multiple encapsulation types that can carry this encoding format, they will
 * be reported in different audio profiles. The application can choose any of the encapsulation
 * types.
 */
public class AudioProfile implements Parcelable {
    /**
     * No encapsulation type is specified.
     */
    public static final int AUDIO_ENCAPSULATION_TYPE_NONE = 0;
    /**
     * Encapsulation format is defined in standard IEC 61937.
     */
    public static final int AUDIO_ENCAPSULATION_TYPE_IEC61937 = 1;

    /** @hide */
    @IntDef({
            AUDIO_ENCAPSULATION_TYPE_NONE,
            AUDIO_ENCAPSULATION_TYPE_IEC61937,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EncapsulationType {}

    private final int mFormat;
    private final int[] mSamplingRates;
    private final int[] mChannelMasks;
    private final int[] mChannelIndexMasks;
    private final int mEncapsulationType;

    /**
     * @hide
     * Constructor from format, sampling rates, channel masks, channel index masks and
     * encapsulation type.
     * @param format the audio format
     * @param samplingRates the supported sampling rates
     * @param channelMasks the supported channel masks
     * @param channelIndexMasks the supported channel index masks
     * @param encapsulationType the encapsulation type of the encoding format
     */
    @SystemApi
    public AudioProfile(int format, @NonNull int[] samplingRates, @NonNull int[] channelMasks,
                 @NonNull int[] channelIndexMasks, int encapsulationType) {
        mFormat = format;
        mSamplingRates = samplingRates;
        mChannelMasks = channelMasks;
        mChannelIndexMasks = channelIndexMasks;
        mEncapsulationType = encapsulationType;
    }

    /**
     * @return the encoding format for this AudioProfile.
     */
    public @AudioFormat.Encoding int getFormat() {
        return mFormat;
    }

    /**
     * @return an array of channel position masks that are associated with the encoding format.
     */
    public @NonNull int[] getChannelMasks() {
        return mChannelMasks;
    }

    /**
     * @return an array of channel index masks that are associated with the encoding format.
     */
    public @NonNull int[] getChannelIndexMasks() {
        return mChannelIndexMasks;
    }

    /**
     * @return an array of sample rates that are associated with the encoding format.
     */
    public @NonNull int[] getSampleRates() {
        return mSamplingRates;
    }

    /**
     * The encapsulation type indicates what encapsulation type is required when the framework is
     * using this format when playing to a device exposing this audio profile.
     * When encapsulation is required, only playback with {@link android.media.AudioTrack} API is
     * supported. But playback with {@link android.media.MediaPlayer} is not.
     * When an encapsulation type is required, the {@link AudioFormat} encoding selected when
     * creating the {@link AudioTrack} must match the encapsulation type, e.g
     * AudioFormat.ENCODING_IEC61937 for AUDIO_ENCAPSULATION_TYPE_IEC61937.
     *
     * @return an integer representing the encapsulation type
     *
     * @see #AUDIO_ENCAPSULATION_TYPE_NONE
     * @see #AUDIO_ENCAPSULATION_TYPE_IEC61937
     */
    public @EncapsulationType int getEncapsulationType() {
        return mEncapsulationType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFormat, Arrays.hashCode(mSamplingRates),
                Arrays.hashCode(mChannelMasks), Arrays.hashCode(mChannelIndexMasks),
                mEncapsulationType);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioProfile that = (AudioProfile) o;
        return ((mFormat == that.mFormat)
                && (hasIdenticalElements(mSamplingRates, that.mSamplingRates))
                && (hasIdenticalElements(mChannelMasks, that.mChannelMasks))
                && (hasIdenticalElements(mChannelIndexMasks, that.mChannelIndexMasks))
                && (mEncapsulationType == that.mEncapsulationType));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        sb.append(AudioFormat.toLogFriendlyEncoding(mFormat));
        if (mSamplingRates != null && mSamplingRates.length > 0) {
            sb.append(", sampling rates=").append(Arrays.toString(mSamplingRates));
        }
        if (mChannelMasks != null && mChannelMasks.length > 0) {
            sb.append(", channel masks=").append(toHexString(mChannelMasks));
        }
        if (mChannelIndexMasks != null && mChannelIndexMasks.length > 0) {
            sb.append(", channel index masks=").append(Arrays.toString(mChannelIndexMasks));
        }
        sb.append(", encapsulation type=" + mEncapsulationType);
        sb.append("}");
        return sb.toString();
    }

    private static String toHexString(int[] ints) {
        if (ints == null || ints.length == 0) {
            return "";
        }
        return Arrays.stream(ints).mapToObj(anInt -> String.format("0x%02X", anInt))
                .collect(Collectors.joining(", "));
    }

    private static boolean hasIdenticalElements(int[] array1, int[] array2) {
        int[] sortedArray1 = Arrays.copyOf(array1, array1.length);
        Arrays.sort(sortedArray1);
        int[] sortedArray2 = Arrays.copyOf(array2, array2.length);
        Arrays.sort(sortedArray2);
        return Arrays.equals(sortedArray1, sortedArray2);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mFormat);
        dest.writeIntArray(mSamplingRates);
        dest.writeIntArray(mChannelMasks);
        dest.writeIntArray(mChannelIndexMasks);
        dest.writeInt(mEncapsulationType);
    }

    private AudioProfile(@NonNull Parcel in) {
        mFormat = in.readInt();
        mSamplingRates = in.createIntArray();
        mChannelMasks = in.createIntArray();
        mChannelIndexMasks = in.createIntArray();
        mEncapsulationType = in.readInt();
    }

    public static final @NonNull Parcelable.Creator<AudioProfile> CREATOR =
            new Parcelable.Creator<AudioProfile>() {
                /**
                 * Rebuilds an AudioProfile previously stored with writeToParcel().
                 * @param p Parcel object to read the AudioProfile from
                 * @return a new AudioProfile created from the data in the parcel
                 */
                public AudioProfile createFromParcel(Parcel p) {
                    return new AudioProfile(p);
                }

                public AudioProfile[] newArray(int size) {
                    return new AudioProfile[size];
                }
            };
}
