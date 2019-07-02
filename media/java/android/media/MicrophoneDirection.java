/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.FloatRange;
import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface defining mechanism for controlling the directionality and field width of
 * audio capture.
 */
public interface MicrophoneDirection {
    /**
     * Don't do any directionality processing of the activated microphone(s).
     */
    int MIC_DIRECTION_UNSPECIFIED = 0;
    /**
     * Optimize capture for audio coming from the side of the device facing the user.
     * In the typical case, a device with a single screen, screen-side camera/microphone and
     * non-screen-side camera/microphone, this will be the screen side (as in a "selfie").
     * For a different device geometry, it is the side for which the expectation is to be
     * facing the user.
     */
    int MIC_DIRECTION_TOWARDS_USER = 1;
    /**
     * Optimize capture for audio coming from the side of the device pointing away from the user.
     * In the typical case, a device with a single screen, screen-side camera/microphone and
     * non-screen-side camera/microphone, this will be the non-screen side.
     * For a different device geometry, it is the side for which the expectation is to be
     * facing away from the user. This is the "taking a video of something else" case.
     */
    int MIC_DIRECTION_AWAY_FROM_USER = 2;
    /**
     * Optimize capture for audio coming from an off-device microphone.
     */
    int MIC_DIRECTION_EXTERNAL = 3;

    /** @hide */
    /*public*/ @IntDef({
            MIC_DIRECTION_UNSPECIFIED,
            MIC_DIRECTION_TOWARDS_USER,
            MIC_DIRECTION_AWAY_FROM_USER,
            MIC_DIRECTION_EXTERNAL
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface DirectionMode{};
    /**
     * Specifies the logical microphone (for processing). Applications can use this to specify
     * which side of the device to optimize capture from. Typically used in conjunction with
     * the camera capturing video.
     *
     * Usage would include specifying the audio capture to follow camera being used to capture
     * video.
     * @param direction Direction constant.
     * @return true if sucessful.
     */
    boolean setPreferredMicrophoneDirection(@DirectionMode int direction);

    /**
     * Specifies the zoom factor (i.e. the field dimension) for the selected microphone
     * (for processing). The selected microphone is determined by the use-case for the stream.
     *
     * Usage would include specifying the audio focus to follow the zoom specified for the camera
     * being used to capture video.
     *
     * @param zoom the desired field dimension of microphone capture. Range is from -1 (wide angle),
     * though 0 (no zoom) to 1 (maximum zoom).
     * @return true if sucessful.
     */
    boolean setPreferredMicrophoneFieldDimension(@FloatRange(from = -1.0, to = 1.0) float zoom);
}
