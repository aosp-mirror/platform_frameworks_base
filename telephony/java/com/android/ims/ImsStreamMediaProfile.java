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
    public static final int AUDIO_QUALITY_G711U = 11;
    public static final int AUDIO_QUALITY_G723 = 12;
    public static final int AUDIO_QUALITY_G711A = 13;
    public static final int AUDIO_QUALITY_G722 = 14;
    public static final int AUDIO_QUALITY_G711AB = 15;
    public static final int AUDIO_QUALITY_G729 = 16;
    public static final int AUDIO_QUALITY_EVS_NB = 17;
    public static final int AUDIO_QUALITY_EVS_WB = 18;
    public static final int AUDIO_QUALITY_EVS_SWB = 19;
    public static final int AUDIO_QUALITY_EVS_FB = 20;

   /**
     * Video information
     */
    public static final int VIDEO_QUALITY_NONE = 0;
    public static final int VIDEO_QUALITY_QCIF = (1 << 0);
    public static final int VIDEO_QUALITY_QVGA_LANDSCAPE = (1 << 1);
    public static final int VIDEO_QUALITY_QVGA_PORTRAIT = (1 << 2);
    public static final int VIDEO_QUALITY_VGA_LANDSCAPE = (1 << 3);
    public static final int VIDEO_QUALITY_VGA_PORTRAIT = (1 << 4);

    /**
     * RTT Modes
     */
    public static final int RTT_MODE_DISABLED = 0;
    public static final int RTT_MODE_FULL = 1;

    // Audio related information
    public int mAudioQuality;
    public int mAudioDirection;
    // Video related information
    public int mVideoQuality;
    public int mVideoDirection;
    // Rtt related information
    public int mRttMode;

    public ImsStreamMediaProfile(Parcel in) {
        readFromParcel(in);
    }

    public ImsStreamMediaProfile() {
        mAudioQuality = AUDIO_QUALITY_NONE;
        mAudioDirection = DIRECTION_SEND_RECEIVE;
        mVideoQuality = VIDEO_QUALITY_NONE;
        mVideoDirection = DIRECTION_INVALID;
        mRttMode = RTT_MODE_DISABLED;
    }

    public ImsStreamMediaProfile(int audioQuality, int audioDirection,
            int videoQuality, int videoDirection) {
        mAudioQuality = audioQuality;
        mAudioDirection = audioDirection;
        mVideoQuality = videoQuality;
        mVideoDirection = videoDirection;
    }

    public ImsStreamMediaProfile(int rttMode) {
        mRttMode = rttMode;
    }

    public void copyFrom(ImsStreamMediaProfile profile) {
        mAudioQuality = profile.mAudioQuality;
        mAudioDirection = profile.mAudioDirection;
        mVideoQuality = profile.mVideoQuality;
        mVideoDirection = profile.mVideoDirection;
        mRttMode = profile.mRttMode;
    }

    @Override
    public String toString() {
        return "{ audioQuality=" + mAudioQuality +
                ", audioDirection=" + mAudioDirection +
                ", videoQuality=" + mVideoQuality +
                ", videoDirection=" + mVideoDirection +
                ", rttMode=" + mRttMode + " }";
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
        out.writeInt(mRttMode);
    }

    private void readFromParcel(Parcel in) {
        mAudioQuality = in.readInt();
        mAudioDirection = in.readInt();
        mVideoQuality = in.readInt();
        mVideoDirection = in.readInt();
        mRttMode = in.readInt();
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

    /**
     * Determines if it's RTT call
     * @return true if RTT call, false otherwise.
     */
    public boolean isRttCall() {
        return (mRttMode == RTT_MODE_FULL);
    }

    /**
     * Updates the RttCall attribute
     */
    public void setRttMode(int rttMode) {
        mRttMode = rttMode;
    }

}
