/*
 * Copyright (c) 2013 The Android Open Source Project
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

package com.android.ims;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Parcelable object to handle IMS stream media profile.
 * It provides the media direction, quality of audio and/or video.
 *
 * @hide
 */
public class ImsStreamMediaProfile implements Parcelable {
    private static final String TAG = "ImsStreamMediaProfile";

    /**
     * Media directions
     */
    public static final int DIRECTION_INVALID = (-1);
    public static final int DIRECTION_INACTIVE = 0;
    public static final int DIRECTION_RECEIVE = 1;
    public static final int DIRECTION_SEND = 2;
    public static final int DIRECTION_SEND_RECEIVE = 3;

    /**
     * Audio information
     */
    public static final int AUDIO_QUALITY_NONE = 0;
    public static final int AUDIO_QUALITY_AMR = 1;
    public static final int AUDIO_QUALITY_AMR_WB = 2;
    public static final int AUDIO_QUALITY_QCELP13K = 3;
    public static final int AUDIO_QUALITY_EVRC = 4;
    public static final int AUDIO_QUALITY_EVRC_B = 5;
    public static final int AUDIO_QUALITY_EVRC_WB = 6;
    public static final int AUDIO_QUALITY_EVRC_NW = 7;
    public static final int AUDIO_QUALITY_GSM_EFR = 8;
    public static final int AUDIO_QUALITY_GSM_FR = 9;
    public static final int AUDIO_QUALITY_GSM_HR = 10;

   /**
     * Video information
     */
    public static final int VIDEO_QUALITY_NONE = 0;
    public static final int VIDEO_QUALITY_QCIF = (1 << 0);
    public static final int VIDEO_QUALITY_QVGA_LANDSCAPE = (1 << 1);
    public static final int VIDEO_QUALITY_QVGA_PORTRAIT = (1 << 2);
    public static final int VIDEO_QUALITY_VGA_LANDSCAPE = (1 << 3);
    public static final int VIDEO_QUALITY_VGA_PORTRAIT = (1 << 4);

    // Audio related information
    public int mAudioQuality;
    public int mAudioDirection;
    // Video related information
    public int mVideoQuality;
    public int mVideoDirection;



    public ImsStreamMediaProfile(Parcel in) {
        readFromParcel(in);
    }

    public ImsStreamMediaProfile() {
        mAudioQuality = AUDIO_QUALITY_NONE;
        mAudioDirection = DIRECTION_SEND_RECEIVE;
        mVideoQuality = VIDEO_QUALITY_NONE;
        mVideoDirection = DIRECTION_INVALID;
    }

    public ImsStreamMediaProfile(int audioQuality, int audioDirection,
            int videoQuality, int videoDirection) {
        mAudioQuality = audioQuality;
        mAudioDirection = audioDirection;
        mVideoQuality = videoQuality;
        mVideoDirection = videoDirection;
    }

    public void copyFrom(ImsStreamMediaProfile profile) {
        mAudioQuality = profile.mAudioQuality;
        mAudioDirection = profile.mAudioDirection;
        mVideoQuality = profile.mVideoQuality;
        mVideoDirection = profile.mVideoDirection;
    }

    @Override
    public String toString() {
        return "{ audioQuality=" + mAudioQuality +
                ", audioDirection=" + mAudioDirection +
                ", videoQuality=" + mVideoQuality +
                ", videoDirection=" + mVideoDirection + " }";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mAudioQuality);
        out.writeInt(mAudioDirection);
        out.writeInt(mVideoQuality);
        out.writeInt(mVideoDirection);
    }

    private void readFromParcel(Parcel in) {
        mAudioQuality = in.readInt();
        mAudioDirection = in.readInt();
        mVideoQuality = in.readInt();
        mVideoDirection = in.readInt();
    }

    public static final Creator<ImsStreamMediaProfile> CREATOR =
            new Creator<ImsStreamMediaProfile>() {
        @Override
        public ImsStreamMediaProfile createFromParcel(Parcel in) {
            return new ImsStreamMediaProfile(in);
        }

        @Override
        public ImsStreamMediaProfile[] newArray(int size) {
            return new ImsStreamMediaProfile[size];
        }
    };
}
