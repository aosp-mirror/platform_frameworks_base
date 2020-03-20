/*
 * Copyright 2015 The Android Open Source Project
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
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Structure for common playback params.
 *
 * Used by {@link AudioTrack} {@link AudioTrack#getPlaybackParams()} and
 * {@link AudioTrack#setPlaybackParams(PlaybackParams)}
 * to control playback behavior.
 * <p> <strong>audio fallback mode:</strong>
 * select out-of-range parameter handling.
 * <ul>
 * <li> {@link PlaybackParams#AUDIO_FALLBACK_MODE_DEFAULT}:
 *   System will determine best handling. </li>
 * <li> {@link PlaybackParams#AUDIO_FALLBACK_MODE_MUTE}:
 *   Play silence for params normally out of range.</li>
 * <li> {@link PlaybackParams#AUDIO_FALLBACK_MODE_FAIL}:
 *   Return {@link java.lang.IllegalArgumentException} from
 *   <code>AudioTrack.setPlaybackParams(PlaybackParams)</code>.</li>
 * </ul>
 * <p> <strong>pitch:</strong> increases or decreases the tonal frequency of the audio content.
 * It is expressed as a multiplicative factor, where normal pitch is 1.0f.
 * <p> <strong>speed:</strong> increases or decreases the time to
 * play back a set of audio or video frames.
 * It is expressed as a multiplicative factor, where normal speed is 1.0f.
 * <p> Different combinations of speed and pitch may be used for audio playback;
 * some common ones:
 * <ul>
 * <li> <em>Pitch equals 1.0f.</em> Speed change will be done with pitch preserved,
 * often called <em>timestretching</em>.</li>
 * <li> <em>Pitch equals speed.</em> Speed change will be done by <em>resampling</em>,
 * similar to {@link AudioTrack#setPlaybackRate(int)}.</li>
 * </ul>
 */
public final class PlaybackParams implements Parcelable {
    /** @hide */
    @IntDef(
        value = {
                AUDIO_FALLBACK_MODE_DEFAULT,
                AUDIO_FALLBACK_MODE_MUTE,
                AUDIO_FALLBACK_MODE_FAIL,
        }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioFallbackMode {}
    public static final int AUDIO_FALLBACK_MODE_DEFAULT = 0;
    public static final int AUDIO_FALLBACK_MODE_MUTE = 1;
    public static final int AUDIO_FALLBACK_MODE_FAIL = 2;

    /** @hide */
    @IntDef(
        value = {
                AUDIO_STRETCH_MODE_DEFAULT,
                AUDIO_STRETCH_MODE_VOICE,
        }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface AudioStretchMode {}
    /** @hide */
    public static final int AUDIO_STRETCH_MODE_DEFAULT = 0;
    /** @hide */
    public static final int AUDIO_STRETCH_MODE_VOICE = 1;

    // flags to indicate which params are actually set
    @UnsupportedAppUsage
    private static final int SET_SPEED               = 1 << 0;
    @UnsupportedAppUsage
    private static final int SET_PITCH               = 1 << 1;
    @UnsupportedAppUsage
    private static final int SET_AUDIO_FALLBACK_MODE = 1 << 2;
    @UnsupportedAppUsage
    private static final int SET_AUDIO_STRETCH_MODE  = 1 << 3;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mSet = 0;

    // params
    @UnsupportedAppUsage
    private int mAudioFallbackMode = AUDIO_FALLBACK_MODE_DEFAULT;
    @UnsupportedAppUsage
    private int mAudioStretchMode = AUDIO_STRETCH_MODE_DEFAULT;
    @UnsupportedAppUsage
    private float mPitch = 1.0f;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private float mSpeed = 1.0f;

    public PlaybackParams() {
    }

    private PlaybackParams(Parcel in) {
        mSet = in.readInt();
        mAudioFallbackMode = in.readInt();
        mAudioStretchMode = in.readInt();
        mPitch = in.readFloat();
        if (mPitch < 0.f) {
            mPitch = 0.f;
        }
        mSpeed = in.readFloat();
    }

    /**
     * Allows defaults to be returned for properties not set.
     * Otherwise a {@link java.lang.IllegalArgumentException} exception
     * is raised when getting those properties
     * which have defaults but have never been set.
     * @return this <code>PlaybackParams</code> instance.
     */
    public PlaybackParams allowDefaults() {
        mSet |= SET_AUDIO_FALLBACK_MODE | SET_AUDIO_STRETCH_MODE | SET_PITCH | SET_SPEED;
        return this;
    }

    /**
     * Sets the audio fallback mode.
     * @param audioFallbackMode
     * @return this <code>PlaybackParams</code> instance.
     */
    public PlaybackParams setAudioFallbackMode(@AudioFallbackMode int audioFallbackMode) {
        mAudioFallbackMode = audioFallbackMode;
        mSet |= SET_AUDIO_FALLBACK_MODE;
        return this;
    }

    /**
     * Retrieves the audio fallback mode.
     * @return audio fallback mode
     * @throws IllegalStateException if the audio fallback mode is not set.
     */
    public @AudioFallbackMode int getAudioFallbackMode() {
        if ((mSet & SET_AUDIO_FALLBACK_MODE) == 0) {
            throw new IllegalStateException("audio fallback mode not set");
        }
        return mAudioFallbackMode;
    }

    /**
     * @hide
     * Sets the audio stretch mode.
     * @param audioStretchMode
     * @return this <code>PlaybackParams</code> instance.
     */
    @TestApi
    public PlaybackParams setAudioStretchMode(@AudioStretchMode int audioStretchMode) {
        mAudioStretchMode = audioStretchMode;
        mSet |= SET_AUDIO_STRETCH_MODE;
        return this;
    }

    /**
     * @hide
     * Retrieves the audio stretch mode.
     * @return audio stretch mode
     * @throws IllegalStateException if the audio stretch mode is not set.
     */
    @TestApi
    public @AudioStretchMode int getAudioStretchMode() {
        if ((mSet & SET_AUDIO_STRETCH_MODE) == 0) {
            throw new IllegalStateException("audio stretch mode not set");
        }
        return mAudioStretchMode;
    }

    /**
     * Sets the pitch factor.
     * @param pitch
     * @return this <code>PlaybackParams</code> instance.
     * @throws IllegalArgumentException if the pitch is negative.
     */
    public PlaybackParams setPitch(float pitch) {
        if (pitch < 0.f) {
            throw new IllegalArgumentException("pitch must not be negative");
        }
        mPitch = pitch;
        mSet |= SET_PITCH;
        return this;
    }

    /**
     * Retrieves the pitch factor.
     * @return pitch
     * @throws IllegalStateException if pitch is not set.
     */
    public float getPitch() {
        if ((mSet & SET_PITCH) == 0) {
            throw new IllegalStateException("pitch not set");
        }
        return mPitch;
    }

    /**
     * Sets the speed factor.
     * @param speed
     * @return this <code>PlaybackParams</code> instance.
     */
    public PlaybackParams setSpeed(float speed) {
        mSpeed = speed;
        mSet |= SET_SPEED;
        return this;
    }

    /**
     * Retrieves the speed factor.
     * @return speed
     * @throws IllegalStateException if speed is not set.
     */
    public float getSpeed() {
        if ((mSet & SET_SPEED) == 0) {
            throw new IllegalStateException("speed not set");
        }
        return mSpeed;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<PlaybackParams> CREATOR =
            new Parcelable.Creator<PlaybackParams>() {
                @Override
                public PlaybackParams createFromParcel(Parcel in) {
                    return new PlaybackParams(in);
                }

                @Override
                public PlaybackParams[] newArray(int size) {
                    return new PlaybackParams[size];
                }
            };


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mSet);
        dest.writeInt(mAudioFallbackMode);
        dest.writeInt(mAudioStretchMode);
        dest.writeFloat(mPitch);
        dest.writeFloat(mSpeed);
    }
}
