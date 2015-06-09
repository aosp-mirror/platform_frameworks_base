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
 */
public class VideoProfile implements Parcelable {
    /**
     * "Unknown" video quality.
     * @hide
     */
    public static final int QUALITY_UNKNOWN = 0;
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

    /**
     * Used when answering or dialing a call to indicate that the call does not have a video
     * component.
     * <p>
     * Should <b>not</b> be used in comparison checks to determine if a video state represents an
     * audio-only call.
     * <p>
     * The following, for example, is not the correct way to check if a call is audio-only:
     * <pre>
     * {@code
     * // This is the incorrect way to check for an audio-only call.
     * if (videoState == VideoProfile.STATE_AUDIO_ONLY) {
     *      // Handle audio-only call.
     * }
     * }
     * </pre>
     * <p>
     * Instead, use the {@link VideoProfile#isAudioOnly(int)} helper function to check if a
     * video state represents an audio-only call:
     * <pre>
     * {@code
     * // This is the correct way to check for an audio-only call.
     * if (VideoProfile.isAudioOnly(videoState)) {
     *      // Handle audio-only call.
     * }
     * }
     * </pre>
     */
    public static final int STATE_AUDIO_ONLY = 0x0;

    /**
     * Video transmission is enabled.
     */
    public static final int STATE_TX_ENABLED = 0x1;

    /**
     * Video reception is enabled.
     */
    public static final int STATE_RX_ENABLED = 0x2;

    /**
     * Video signal is bi-directional.
     */
    public static final int STATE_BIDIRECTIONAL = STATE_TX_ENABLED | STATE_RX_ENABLED;

    /**
     * Video is paused.
     */
    public static final int STATE_PAUSED = 0x4;

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
     * Valid values: {@link VideoProfile#STATE_AUDIO_ONLY},
     * {@link VideoProfile#STATE_BIDIRECTIONAL},
     * {@link VideoProfile#STATE_TX_ENABLED},
     * {@link VideoProfile#STATE_RX_ENABLED},
     * {@link VideoProfile#STATE_PAUSED}.
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

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[VideoProfile videoState = ");
        sb.append(videoStateToString(mVideoState));
        sb.append(" videoQuality = ");
        sb.append(mQuality);
        sb.append("]");
        return sb.toString();
    }

    /**
     * Generates a string representation of a video state.
     *
     * @param videoState The video state.
     * @return String representation of the video state.
     */
    public static String videoStateToString(int videoState) {
        StringBuilder sb = new StringBuilder();
        sb.append("Audio");

        if (isAudioOnly(videoState)) {
            sb.append(" Only");
        } else {
            if (isTransmissionEnabled(videoState)) {
                sb.append(" Tx");
            }

            if (isReceptionEnabled(videoState)) {
                sb.append(" Rx");
            }

            if (isPaused(videoState)) {
                sb.append(" Pause");
            }
        }

        return sb.toString();
    }

    /**
     * Indicates whether the video state is audio only.
     *
     * @param videoState The video state.
     * @return {@code True} if the video state is audio only, {@code false} otherwise.
     */
    public static boolean isAudioOnly(int videoState) {
        return !hasState(videoState, VideoProfile.STATE_TX_ENABLED)
                && !hasState(videoState, VideoProfile.STATE_RX_ENABLED);
    }

    /**
     * Indicates whether video transmission or reception is enabled for a video state.
     *
     * @param videoState The video state.
     * @return {@code True} if video transmission or reception is enabled, {@code false} otherwise.
     */
    public static boolean isVideo(int videoState) {
        return hasState(videoState, VideoProfile.STATE_TX_ENABLED)
                || hasState(videoState, VideoProfile.STATE_RX_ENABLED)
                || hasState(videoState, VideoProfile.STATE_BIDIRECTIONAL);
    }

    /**
     * Indicates whether the video state has video transmission enabled.
     *
     * @param videoState The video state.
     * @return {@code True} if video transmission is enabled, {@code false} otherwise.
     */
    public static boolean isTransmissionEnabled(int videoState) {
        return hasState(videoState, VideoProfile.STATE_TX_ENABLED);
    }

    /**
     * Indicates whether the video state has video reception enabled.
     *
     * @param videoState The video state.
     * @return {@code True} if video reception is enabled, {@code false} otherwise.
     */
    public static boolean isReceptionEnabled(int videoState) {
        return hasState(videoState, VideoProfile.STATE_RX_ENABLED);
    }

    /**
     * Indicates whether the video state is bi-directional.
     *
     * @param videoState The video state.
     * @return {@code True} if the video is bi-directional, {@code false} otherwise.
     */
    public static boolean isBidirectional(int videoState) {
        return hasState(videoState, VideoProfile.STATE_BIDIRECTIONAL);
    }

    /**
     * Indicates whether the video state is paused.
     *
     * @param videoState The video state.
     * @return {@code True} if the video is paused, {@code false} otherwise.
     */
    public static boolean isPaused(int videoState) {
        return hasState(videoState, VideoProfile.STATE_PAUSED);
    }

    /**
     * Indicates if a specified state is set in a videoState bit-mask.
     *
     * @param videoState The video state bit-mask.
     * @param state The state to check.
     * @return {@code True} if the state is set.
     */
    private static boolean hasState(int videoState, int state) {
        return (videoState & state) == state;
    }

    /**
     * Represents the camera capabilities important to a Video Telephony provider.
     */
    public static final class CameraCapabilities implements Parcelable {

        /**
         * The width of the camera video in pixels.
         */
        private final int mWidth;

        /**
         * The height of the camera video in pixels.
         */
        private final int mHeight;

        /**
         * Whether the camera supports zoom.
         */
        private final boolean mZoomSupported;

        /**
         * The maximum zoom supported by the camera.
         */
        private final float mMaxZoom;

        /**
         * Create a call camera capabilities instance.
         *
         * @param width The width of the camera video (in pixels).
         * @param height The height of the camera video (in pixels).
         */
        public CameraCapabilities(int width, int height) {
            this(width, height, false, 1.0f);
        }

        /**
         * Create a call camera capabilities instance that optionally
         * supports zoom.
         *
         * @param width The width of the camera video (in pixels).
         * @param height The height of the camera video (in pixels).
         * @param zoomSupported True when camera supports zoom.
         * @param maxZoom Maximum zoom supported by camera.
         * @hide
         */
        public CameraCapabilities(int width, int height, boolean zoomSupported, float maxZoom) {
            mWidth = width;
            mHeight = height;
            mZoomSupported = zoomSupported;
            mMaxZoom = maxZoom;
        }

        /**
         * Responsible for creating CallCameraCapabilities objects from deserialized Parcels.
         **/
        public static final Parcelable.Creator<CameraCapabilities> CREATOR =
                new Parcelable.Creator<CameraCapabilities> () {
                    /**
                     * Creates a CallCameraCapabilities instances from a parcel.
                     *
                     * @param source The parcel.
                     * @return The CallCameraCapabilities.
                     */
                    @Override
                    public CameraCapabilities createFromParcel(Parcel source) {
                        int width = source.readInt();
                        int height = source.readInt();
                        boolean supportsZoom = source.readByte() != 0;
                        float maxZoom = source.readFloat();

                        return new CameraCapabilities(width, height, supportsZoom, maxZoom);
                    }

                    @Override
                    public CameraCapabilities[] newArray(int size) {
                        return new CameraCapabilities[size];
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
            dest.writeInt(getWidth());
            dest.writeInt(getHeight());
            dest.writeByte((byte) (isZoomSupported() ? 1 : 0));
            dest.writeFloat(getMaxZoom());
        }

        /**
         * The width of the camera video in pixels.
         */
        public int getWidth() {
            return mWidth;
        }

        /**
         * The height of the camera video in pixels.
         */
        public int getHeight() {
            return mHeight;
        }

        /**
         * Whether the camera supports zoom.
         * @hide
         */
        public boolean isZoomSupported() {
            return mZoomSupported;
        }

        /**
         * The maximum zoom supported by the camera.
         * @hide
         */
        public float getMaxZoom() {
            return mMaxZoom;
        }
    }

}
