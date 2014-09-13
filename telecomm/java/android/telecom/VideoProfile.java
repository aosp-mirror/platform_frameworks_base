/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.telecom;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Represents attributes of video calls.
 *
 * {@hide}
 */
public class VideoProfile implements Parcelable {
    /**
     * "High" video quality.
     */
    public static final int QUALITY_HIGH = 1;

    /**
     * "Medium" video quality.
     */
    public static final int QUALITY_MEDIUM = 2;

    /**
     * "Low" video quality.
     */
    public static final int QUALITY_LOW = 3;

    /**
     * Use default video quality.
     */
    public static final int QUALITY_DEFAULT = 4;

    private final int mVideoState;

    private final int mQuality;

    /**
     * Creates an instance of the VideoProfile
     *
     * @param videoState The video state.
     */
    public VideoProfile(int videoState) {
        this(videoState, QUALITY_DEFAULT);
    }

    /**
     * Creates an instance of the VideoProfile
     *
     * @param videoState The video state.
     * @param quality The video quality.
     */
    public VideoProfile(int videoState, int quality) {
        mVideoState = videoState;
        mQuality = quality;
    }

    /**
     * The video state of the call.
     * Valid values: {@link VideoProfile.VideoState#AUDIO_ONLY},
     * {@link VideoProfile.VideoState#BIDIRECTIONAL},
     * {@link VideoProfile.VideoState#TX_ENABLED},
     * {@link VideoProfile.VideoState#RX_ENABLED},
     * {@link VideoProfile.VideoState#PAUSED}.
     */
    public int getVideoState() {
        return mVideoState;
    }

    /**
     * The desired video quality for the call.
     * Valid values: {@link VideoProfile#QUALITY_HIGH}, {@link VideoProfile#QUALITY_MEDIUM},
     * {@link VideoProfile#QUALITY_LOW}, {@link VideoProfile#QUALITY_DEFAULT}.
     */
    public int getQuality() {
        return mQuality;
    }

    /**
     * Responsible for creating VideoProfile objects from deserialized Parcels.
     **/
    public static final Parcelable.Creator<VideoProfile> CREATOR =
            new Parcelable.Creator<VideoProfile> () {
                /**
                 * Creates a MediaProfile instances from a parcel.
                 *
                 * @param source The parcel.
                 * @return The MediaProfile.
                 */
                @Override
                public VideoProfile createFromParcel(Parcel source) {
                    int state = source.readInt();
                    int quality = source.readInt();

                    ClassLoader classLoader = VideoProfile.class.getClassLoader();
                    return new VideoProfile(state, quality);
                }

                @Override
                public VideoProfile[] newArray(int size) {
                    return new VideoProfile[size];
                }
            };

    /**
     * Describe the kinds of special objects contained in this Parcelable's
     * marshalled representation.
     *
     * @return a bitmask indicating the set of special object types marshalled
     * by the Parcelable.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Flatten this object in to a Parcel.
     *
     * @param dest  The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     *              May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mVideoState);
        dest.writeInt(mQuality);
    }

    /**
    * The video state of the call, stored as a bit-field describing whether video transmission and
    * receipt it enabled, as well as whether the video is currently muted.
    */
    public static class VideoState {
        /**
         * Call is currently in an audio-only mode with no video transmission or receipt.
         */
        public static final int AUDIO_ONLY = 0x0;

        /**
         * Video transmission is enabled.
         */
        public static final int TX_ENABLED = 0x1;

        /**
         * Video reception is enabled.
         */
        public static final int RX_ENABLED = 0x2;

        /**
         * Video signal is bi-directional.
         */
        public static final int BIDIRECTIONAL = TX_ENABLED | RX_ENABLED;

        /**
         * Video is paused.
         */
        public static final int PAUSED = 0x4;

        /**
         * Whether the video state is audio only.
         * @param videoState The video state.
         * @return Returns true if the video state is audio only.
         */
        public static boolean isAudioOnly(int videoState) {
            return !hasState(videoState, TX_ENABLED) && !hasState(videoState, RX_ENABLED);
        }

        /**
         * Whether the video transmission is enabled.
         * @param videoState The video state.
         * @return Returns true if the video transmission is enabled.
         */
        public static boolean isTransmissionEnabled(int videoState) {
            return hasState(videoState, TX_ENABLED);
        }

        /**
         * Whether the video reception is enabled.
         * @param videoState The video state.
         * @return Returns true if the video transmission is enabled.
         */
        public static boolean isReceptionEnabled(int videoState) {
            return hasState(videoState, RX_ENABLED);
        }

        /**
         * Whether the video signal is bi-directional.
         * @param videoState
         * @return Returns true if the video signal is bi-directional.
         */
        public static boolean isBidirectional(int videoState) {
            return hasState(videoState, BIDIRECTIONAL);
        }

        /**
         * Whether the video is paused.
         * @param videoState The video state.
         * @return Returns true if the video is paused.
         */
        public static boolean isPaused(int videoState) {
            return hasState(videoState, PAUSED);
        }

        /**
         * Determines if a specified state is set in a videoState bit-mask.
         *
         * @param videoState The video state bit-mask.
         * @param state The state to check.
         * @return {@code True} if the state is set.
         * {@hide}
         */
        private static boolean hasState(int videoState, int state) {
            return (videoState & state) == state;
        }
    }
}
