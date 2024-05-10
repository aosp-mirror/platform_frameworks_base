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
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Defines the audio HAL version.
 *
 * @hide
 */
@TestApi
public final class AudioHalVersionInfo implements Parcelable, Comparable<AudioHalVersionInfo> {
    /**
     * Indicate the audio HAL is implemented with HIDL (HAL interface definition language).
     *
     * @see <a href="https://source.android.com/docs/core/architecture/hidl/">HIDL</a>
     *     <p>The value of AUDIO_HAL_TYPE_HIDL should match the value of {@link
     *     android.media.AudioHalVersion.Type#HIDL}.
     */
    public static final int AUDIO_HAL_TYPE_HIDL = 0;

    /**
     * Indicate the audio HAL is implemented with AIDL (Android Interface Definition Language).
     *
     * @see <a href="https://source.android.com/docs/core/architecture/aidl/">AIDL</a>
     *     <p>The value of AUDIO_HAL_TYPE_AIDL should match the value of {@link
     *     android.media.AudioHalVersion.Type#AIDL}.
     */
    public static final int AUDIO_HAL_TYPE_AIDL = 1;

    /** @hide */
    @IntDef(
            flag = false,
            prefix = "AUDIO_HAL_TYPE_",
            value = {AUDIO_HAL_TYPE_HIDL, AUDIO_HAL_TYPE_AIDL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioHalType {}

    /** AudioHalVersionInfo object of all valid Audio HAL versions. */
    public static final @NonNull AudioHalVersionInfo AIDL_1_0 =
            new AudioHalVersionInfo(AUDIO_HAL_TYPE_AIDL, 1 /* major */, 0 /* minor */);

    public static final @NonNull AudioHalVersionInfo HIDL_7_1 =
            new AudioHalVersionInfo(AUDIO_HAL_TYPE_HIDL, 7 /* major */, 1 /* minor */);
    public static final @NonNull AudioHalVersionInfo HIDL_7_0 =
            new AudioHalVersionInfo(AUDIO_HAL_TYPE_HIDL, 7 /* major */, 0 /* minor */);
    public static final @NonNull AudioHalVersionInfo HIDL_6_0 =
            new AudioHalVersionInfo(AUDIO_HAL_TYPE_HIDL, 6 /* major */, 0 /* minor */);
    public static final @NonNull AudioHalVersionInfo HIDL_5_0 =
            new AudioHalVersionInfo(AUDIO_HAL_TYPE_HIDL, 5 /* major */, 0 /* minor */);
    public static final @NonNull AudioHalVersionInfo HIDL_4_0 =
            new AudioHalVersionInfo(AUDIO_HAL_TYPE_HIDL, 4 /* major */, 0 /* minor */);
    public static final @NonNull AudioHalVersionInfo HIDL_2_0 =
            new AudioHalVersionInfo(AUDIO_HAL_TYPE_HIDL, 2 /* major */, 0 /* minor */);

    /**
     * List of all valid Audio HAL versions. This list need to be in sync with sAudioHALVersions
     * defined in frameworks/av/media/libaudiohal/FactoryHal.cpp.
     *
     * Note: update {@link android.media.audio.cts.AudioHalVersionInfoTest} CTS accordingly if
     * there is a change to supported versions.
     */
    public static final @NonNull List<AudioHalVersionInfo> VERSIONS =
            List.of(AIDL_1_0, HIDL_7_1, HIDL_7_0, HIDL_6_0, HIDL_5_0);

    private static final String TAG = "AudioHalVersionInfo";
    private AudioHalVersion mHalVersion = new AudioHalVersion();

    public @AudioHalType int getHalType() {
        return mHalVersion.type;
    }

    public int getMajorVersion() {
        return mHalVersion.major;
    }

    public int getMinorVersion() {
        return mHalVersion.minor;
    }

    /** String representative of AudioHalVersion.Type */
    private static @NonNull String typeToString(@AudioHalType int type) {
        if (type == AudioHalVersion.Type.HIDL) {
            return "HIDL";
        } else if (type == AudioHalVersion.Type.AIDL) {
            return "AIDL";
        } else {
            return "INVALID";
        }
    }

    /** String representative of type, major and minor */
    private static @NonNull String toString(@AudioHalType int type, int major, int minor) {
        return typeToString(type) + ":" + Integer.toString(major) + "." + Integer.toString(minor);
    }

    private AudioHalVersionInfo(@AudioHalType int type, int major, int minor) {
        mHalVersion.type = type;
        mHalVersion.major = major;
        mHalVersion.minor = minor;
    }

    private AudioHalVersionInfo(Parcel in) {
        mHalVersion = in.readTypedObject(AudioHalVersion.CREATOR);
    }

    /** String representative of this (AudioHalVersionInfo) object */
    @Override
    public String toString() {
        return toString(mHalVersion.type, mHalVersion.major, mHalVersion.minor);
    }

    /**
     * Compare two HAL versions by comparing their index in VERSIONS.
     *
     * <p>Normally all AudioHalVersionInfo object to compare should exist in the VERSIONS list. If
     * both candidates exist in the VERSIONS list, smaller index means newer. Any candidate not
     * exist in the VERSIONS list will be considered to be oldest version.
     *
     * @return 0 if the HAL version is the same as the other HAL version. Positive if the HAL
     *     version is newer than the other HAL version. Negative if the HAL version is older than
     *     the other version.
     */
    @Override
    public int compareTo(@NonNull AudioHalVersionInfo other) {
        int indexOther = VERSIONS.indexOf(other);
        int indexThis = VERSIONS.indexOf(this);
        if (indexThis < 0 || indexOther < 0) {
            return indexThis - indexOther;
        }
        return indexOther - indexThis;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel out, int flag) {
        out.writeTypedObject(mHalVersion, flag);
    }

    public static final @NonNull Parcelable.Creator<AudioHalVersionInfo> CREATOR =
            new Parcelable.Creator<AudioHalVersionInfo>() {
                @Override
                public AudioHalVersionInfo createFromParcel(@NonNull Parcel in) {
                    return new AudioHalVersionInfo(in);
                }

                @Override
                public AudioHalVersionInfo[] newArray(int size) {
                    return new AudioHalVersionInfo[size];
                }
            };
}
