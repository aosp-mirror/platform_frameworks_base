/*
 * Copyright (C) 2021 The Android Open Source Project
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

/**
 * The AudioDescriptor contains the information to describe the audio playback/capture
 * capabilities. The capabilities are described by a byte array, which is defined by a
 * particular standard. This is used when the format is unrecognized to the platform.
 */
public class AudioDescriptor implements Parcelable {
    /**
     * The audio standard is not specified.
     */
    public static final int STANDARD_NONE = 0;
    /**
     * The Extended Display Identification Data (EDID) standard for a short audio descriptor.
     */
    public static final int STANDARD_EDID = 1;

    /** @hide */
    @IntDef({
            STANDARD_NONE,
            STANDARD_EDID,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioDescriptorStandard {}

    private final int mStandard;
    private final byte[] mDescriptor;
    private final int mEncapsulationType;

    /**
     * @hide
     * Constructor from standard, encapsulation type and descriptor
     * @param standard the standard of the audio descriptor
     * @param encapsulationType the encapsulation type of the audio descriptor
     * @param descriptor the audio descriptor
     */
    @SystemApi
    public AudioDescriptor(int standard, int encapsulationType, @NonNull byte[] descriptor) {
        mStandard = standard;
        mEncapsulationType = encapsulationType;
        mDescriptor = descriptor;
    }

    /**
     * @return the standard that defines audio playback/capture capabilities.
     */
    public @AudioDescriptorStandard int getStandard() {
        return mStandard;
    }

    /**
     * @return a byte array that describes audio playback/capture capabilities as encoded by the
     * standard for this AudioDescriptor.
     */
    public @NonNull byte[] getDescriptor() {
        return mDescriptor;
    }

    /**
     * The encapsulation type indicates what encapsulation type is required when the framework is
     * using this extra audio descriptor for playing to a device exposing this audio profile.
     * When encapsulation is required, only playback with {@link android.media.AudioTrack} API is
     * supported. But playback with {@link android.media.MediaPlayer} is not.
     * When an encapsulation type is required, the {@link AudioFormat} encoding selected when
     * creating the {@link AudioTrack} must match the encapsulation type, e.g
     * AudioFormat#ENCODING_IEC61937 for AudioProfile.AUDIO_ENCAPSULATION_TYPE_IEC61937.
     *
     * @return an integer representing the encapsulation type
     *
     * @see AudioProfile#AUDIO_ENCAPSULATION_TYPE_NONE
     * @see AudioProfile#AUDIO_ENCAPSULATION_TYPE_IEC61937
     */
    public @AudioProfile.EncapsulationType int getEncapsulationType() {
        return mEncapsulationType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStandard, mEncapsulationType, Arrays.hashCode(mDescriptor));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioDescriptor that = (AudioDescriptor) o;
        return ((mStandard == that.mStandard)
                && (mEncapsulationType == that.mEncapsulationType)
                && (Arrays.equals(mDescriptor, that.mDescriptor)));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("standard=" + mStandard);
        sb.append(", encapsulation type=" + mEncapsulationType);
        if (mDescriptor != null && mDescriptor.length > 0) {
            sb.append(", descriptor=").append(Arrays.toString(mDescriptor));
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStandard);
        dest.writeInt(mEncapsulationType);
        dest.writeByteArray(mDescriptor);
    }

    private AudioDescriptor(@NonNull Parcel in) {
        mStandard = in.readInt();
        mEncapsulationType = in.readInt();
        mDescriptor = in.createByteArray();
    }

    public static final @NonNull Parcelable.Creator<AudioDescriptor> CREATOR =
            new Parcelable.Creator<AudioDescriptor>() {
                /**
                 * Rebuilds an AudioDescriptor previously stored with writeToParcel().
                 * @param p Parcel object to read the AudioDescriptor from
                 * @return a new AudioDescriptor created from the data in the parcel
                 */
                public AudioDescriptor createFromParcel(Parcel p) {
                    return new AudioDescriptor(p);
                }

                public AudioDescriptor[] newArray(int size) {
                    return new AudioDescriptor[size];
                }
            };
}
