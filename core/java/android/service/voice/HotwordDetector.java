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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.media.AudioFormat;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.SharedMemory;
import android.util.AndroidException;

/**
 * Basic functionality for hotword detectors.
 *
 * @hide
 */
@SystemApi
public interface HotwordDetector {

    /**
     * Prior to API level 33, API calls of {@link android.service.voice.HotwordDetector} could
     * return both {@link java.lang.IllegalStateException} or
     * {@link java.lang.UnsupportedOperationException} depending on the detector's underlying state.
     * This lead to confusing behavior as the underlying state of the detector can be modified
     * without the knowledge of the caller via system service layer updates.
     *
     * This change ID, when enabled, changes the API calls to only throw checked exception
     * {@link android.service.voice.HotwordDetector.IllegalDetectorStateException} when checking
     * against state information modified by both the caller and the system services.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    long HOTWORD_DETECTOR_THROW_CHECKED_EXCEPTION = 226355112L;

    /**
     * Indicates that it is a non-trusted hotword detector.
     *
     * @hide
     */
    int DETECTOR_TYPE_NORMAL = 0;

    /**
     * Indicates that it is a DSP trusted hotword detector.
     *
     * @hide
     */
    int DETECTOR_TYPE_TRUSTED_HOTWORD_DSP = 1;

    /**
     * Indicates that it is a software trusted hotword detector.
     *
     * @hide
     */
    int DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE = 2;

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
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         or above and attempts to start a recognition when the detector is not able based on
     *         the state. This can be thrown even if the state has been checked before calling this
     *         method because the caller receives updates via an asynchronous callback, and the
     *         state of the detector can change concurrently to the caller calling this method.
     */
    @RequiresPermission(allOf = {RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD})
    boolean startRecognition() throws IllegalDetectorStateException;

    /**
     * Stops hotword recognition.
     *
     * @return true if the request to stop recognition succeeded
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         or above and attempts to stop a recognition when the detector is not able based on
     *         the state. This can be thrown even if the state has been checked before calling this
     *         method because the caller receives updates via an asynchronous callback, and the
     *         state of the detector can change concurrently to the caller calling this method.
     */
    boolean stopRecognition() throws IllegalDetectorStateException;

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
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         or above and attempts to start a recognition when the detector is not able based on
     *         the state. This can be thrown even if the state has been checked before calling this
     *         method because the caller receives updates via an asynchronous callback, and the
     *         state of the detector can change concurrently to the caller calling this method.
     */
    boolean startRecognition(
            @NonNull ParcelFileDescriptor audioStream,
            @NonNull AudioFormat audioFormat,
            @Nullable PersistableBundle options) throws IllegalDetectorStateException;

    /**
     * Set configuration and pass read-only data to hotword detection service.
     *
     * @param options Application configuration data to provide to the
     *         {@link HotwordDetectionService}. PersistableBundle does not allow any remotable
     *         objects or other contents that can be used to communicate with other processes.
     * @param sharedMemory The unrestricted data blob to provide to the
     *         {@link HotwordDetectionService}. Use this to provide the hotword models data or other
     *         such data to the trusted process.
     * @throws IllegalDetectorStateException Thrown when a caller has a target SDK of API level 33
     *         or above and the detector is not able to perform the operation based on the
     *         underlying state. This can be thrown even if the state has been checked before
     *         calling this method because the caller receives updates via an asynchronous callback,
     *         and the state of the detector can change concurrently to the caller calling this
     *         method.
     * @throws IllegalStateException if this HotwordDetector wasn't specified to use a
     *         {@link HotwordDetectionService} when it was created.
     */
    void updateState(@Nullable PersistableBundle options, @Nullable SharedMemory sharedMemory)
            throws IllegalDetectorStateException;

    /**
     * Invalidates this hotword detector so that any future calls to this result
     * in an {@link IllegalStateException}.
     *
     * <p>If there are no other {@link HotwordDetector} instances linked to the
     * {@link HotwordDetectionService}, the service will be shutdown.
     */
    default void destroy() {
        throw new UnsupportedOperationException("Not implemented. Must override in a subclass.");
    }

    /**
     * @hide
     */
    static String detectorTypeToString(int detectorType) {
        switch (detectorType) {
            case DETECTOR_TYPE_NORMAL:
                return "normal";
            case DETECTOR_TYPE_TRUSTED_HOTWORD_DSP:
                return "trusted_hotword_dsp";
            case DETECTOR_TYPE_TRUSTED_HOTWORD_SOFTWARE:
                return "trusted_hotword_software";
            default:
                return Integer.toString(detectorType);
        }
    }

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
        void onRejected(@NonNull HotwordRejectedResult result);

        /**
         * Called when the {@link HotwordDetectionService} is created by the system and given a
         * short amount of time to report it's initialization state.
         *
         * @param status Info about initialization state of {@link HotwordDetectionService}; the
         * allowed values are {@link HotwordDetectionService#INITIALIZATION_STATUS_SUCCESS},
         * 1<->{@link HotwordDetectionService#getMaxCustomInitializationStatus()},
         * {@link HotwordDetectionService#INITIALIZATION_STATUS_UNKNOWN}.
         */
        void onHotwordDetectionServiceInitialized(int status);

        /**
         * Called with the {@link HotwordDetectionService} is restarted.
         *
         * Clients are expected to call {@link HotwordDetector#updateState} to share the state with
         * the newly created service.
         */
        void onHotwordDetectionServiceRestarted();
    }

    /**
     * {@link HotwordDetector} specific exception thrown when the underlying state of the detector
     * is invalid for the given action.
     */
    class IllegalDetectorStateException extends AndroidException {
        IllegalDetectorStateException(String message) {
            super(message);
        }
    }
}
