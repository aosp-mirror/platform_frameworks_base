/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.app;

import android.hardware.soundtrigger.SoundTrigger;
import android.service.voice.HotwordDetectedResult;
import android.service.voice.HotwordDetectionServiceFailure;
import android.service.voice.HotwordRejectedResult;
import android.service.voice.SoundTriggerFailure;
import android.service.voice.VisualQueryDetectionServiceFailure;
import com.android.internal.infra.AndroidFuture;

/**
 * @hide
 */
oneway interface IHotwordRecognitionStatusCallback {
    /**
     * Called when the keyphrase is spoken.
     *
     * @param recognitionEvent Object containing data relating to the
     *                         keyphrase recognition event such as keyphrase
     *                         extras.
     * @param result Successful detection result payload.
     */
    void onKeyphraseDetected(
            in SoundTrigger.KeyphraseRecognitionEvent recognitionEvent,
            in HotwordDetectedResult result);

    /**
     * Called when the keyphrase is detected from audio coming from an external source.
     *
     * @param result Successful detection result payload.
     */
    void onKeyphraseDetectedFromExternalSource(in HotwordDetectedResult result);

   /**
     * Called when a generic sound trigger event is witnessed.
     *
     * @param recognitionEvent Object containing data relating to the
     *                         recognition event such as trigger audio data (if
     *                         requested).
     */
    void onGenericSoundTriggerDetected(in SoundTrigger.GenericRecognitionEvent recognitionEvent);

    /**
     * Called when the {@link HotwordDetectionService second stage detection} did not detect the
     * keyphrase.
     *
     * @param result Info about the second stage detection result, provided by the
     *         {@link HotwordDetectionService}.
     */
    void onRejected(in HotwordRejectedResult result);

    /**
     * Called when the detection fails due to an error occurs in the
     * {@link HotwordDetectionService}.
     *
     * @param hotwordDetectionServiceFailure It provides the error code, error message and
     *                                       suggested action.
     */
    void onHotwordDetectionServiceFailure(
        in HotwordDetectionServiceFailure hotwordDetectionServiceFailure);

    /**
     * Called when the detection fails due to an error occurs in the
     * {@link VisualQueryDetectionService}.
     *
     * @param visualQueryDetectionServiceFailure It provides the error code, error message and
     *                                           suggested action.
     */
    void onVisualQueryDetectionServiceFailure(
        in VisualQueryDetectionServiceFailure visualQueryDetectionServiceFailure);

    /**
     * Called when the detection fails due to an error occurs in the
     * {@link com.android.server.soundtrigger.SoundTriggerService}.
     *
     * @param soundTriggerFailure It provides the error code, error message and
     *                                           suggested action.
     */
    void onSoundTriggerFailure(in SoundTriggerFailure soundTriggerFailure);

    /**
     * Called when the detection fails due to an unknown error occurs.
     *
     * @param errorMessage It provides the error message.
     */
    void onUnknownFailure(in String errorMessage);

    /**
     * Called when the recognition is paused temporarily for some reason.
     */
    void onRecognitionPaused();

    /**
     * Called when the recognition is resumed after it was temporarily paused.
     */
    void onRecognitionResumed();

    /**
     * Called when the {@link HotwordDetectionService} reported the result for requesting update
     * state action.
     *
     * @param status The status about the result of requesting update state action.
     */
    void onStatusReported(int status);

    /** Called when the hotword detection process is restarted */
    void onProcessRestarted();

    /**
     * Called when a file open request is sent.
     */
   void onOpenFile(in String filename, in AndroidFuture future);
}
