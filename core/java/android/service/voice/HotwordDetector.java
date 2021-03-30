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

package android.service.voice;

import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.RECORD_AUDIO;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.media.AudioFormat;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.service.voice.HotwordDetectionService.InitializationStatus;

/**
 * Basic functionality for hotword detectors.
 *
 * @hide
 */
@SystemApi
public interface HotwordDetector {

    /** No confidence in hotword detector result. */
    int CONFIDENCE_LEVEL_NONE = 0;

    /** Small confidence in hotword detector result. */
    int CONFIDENCE_LEVEL_LOW = 1;

    /** Medium confidence in hotword detector result. */
    int CONFIDENCE_LEVEL_MEDIUM = 2;

    /** High confidence in hotword detector result. */
    int CONFIDENCE_LEVEL_HIGH = 3;

    /** @hide */
    @IntDef(prefix = {"CONFIDENCE_LEVEL_"}, value = {
            CONFIDENCE_LEVEL_NONE,
            CONFIDENCE_LEVEL_LOW,
            CONFIDENCE_LEVEL_MEDIUM,
            CONFIDENCE_LEVEL_HIGH
    })
    @interface HotwordConfidenceLevelValue {
    }

    /**
     * Starts hotword recognition.
     * <p>
     * On calling this, the system streams audio from the device microphone to this application's
     * {@link HotwordDetectionService}. Audio is streamed until {@link #stopRecognition()} is
     * called.
     * <p>
     * On detection of a hotword,
     * {@link AlwaysOnHotwordDetector.Callback#onDetected(AlwaysOnHotwordDetector.EventPayload)}
     * is called on the callback provided when creating this {@link HotwordDetector}.
     * <p>
     * There is a noticeable impact on battery while recognition is active, so make sure to call
     * {@link #stopRecognition()} when detection isn't needed.
     * <p>
     * Calling this again while recognition is active does nothing.
     *
     * @return true if the request to start recognition succeeded
     */
    @RequiresPermission(allOf = {RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD})
    boolean startRecognition();

    /**
     * Stops hotword recognition.
     *
     * @return true if the request to stop recognition succeeded
     */
    boolean stopRecognition();

    /**
     * Starts hotword recognition on audio coming from an external connected microphone.
     * <p>
     * {@link #stopRecognition()} must be called before {@code audioStream} is closed.
     *
     * @param audioStream stream containing the audio bytes to run detection on
     * @param audioFormat format of the encoded audio
     * @param options options supporting detection, such as configuration specific to the
     *         source of the audio. This will be provided to the {@link HotwordDetectionService}.
     *         PersistableBundle does not allow any remotable objects or other contents that can be
     *         used to communicate with other processes.
     * @return true if the request to start recognition succeeded
     */
    boolean startRecognition(
            @NonNull ParcelFileDescriptor audioStream,
            @NonNull AudioFormat audioFormat,
            @Nullable PersistableBundle options);

    /**
     * The callback to notify of detection events.
     */
    interface Callback {

        /**
         * Called when the keyphrase is spoken.
         *
         * @param eventPayload Payload data for the detection event.
         */
        // TODO: Consider creating a new EventPayload that the AOHD one subclasses.
        void onDetected(@NonNull AlwaysOnHotwordDetector.EventPayload eventPayload);

        /**
         * Called when the detection fails due to an error.
         */
        void onError();

        /**
         * Called when the recognition is paused temporarily for some reason.
         * This is an informational callback, and the clients shouldn't be doing anything here
         * except showing an indication on their UI if they have to.
         */
        void onRecognitionPaused();

        /**
         * Called when the recognition is resumed after it was temporarily paused.
         * This is an informational callback, and the clients shouldn't be doing anything here
         * except showing an indication on their UI if they have to.
         */
        void onRecognitionResumed();

        /**
         * Called when the {@link HotwordDetectionService second stage detection} did not detect the
         * keyphrase.
         *
         * @param result Info about the second stage detection result, provided by the
         *         {@link HotwordDetectionService}.
         */
        void onRejected(@Nullable HotwordRejectedResult result);

        /**
         * Called when the {@link HotwordDetectionService} is created by the system and given a
         * short amount of time to report it's initialization state.
         *
         * @param status Info about initialization state of {@link HotwordDetectionService}.
         */
        void onHotwordDetectionServiceInitialized(@InitializationStatus int status);
    }
}
