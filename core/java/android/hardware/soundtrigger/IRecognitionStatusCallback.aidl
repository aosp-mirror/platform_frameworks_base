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
 * limitations under the License.
 */

package android.hardware.soundtrigger;

import android.hardware.soundtrigger.SoundTrigger;

/**
 * @hide
 */
oneway interface IRecognitionStatusCallback {
    /**
     * Called when the keyphrase is spoken.
     *
     * @param recognitionEvent Object containing data relating to the
     *                         keyphrase recognition event such as keyphrase
     *                         extras.
     */
    void onKeyphraseDetected(in SoundTrigger.KeyphraseRecognitionEvent recognitionEvent);

   /**
     * Called when a generic sound trigger event is witnessed.
     *
     * @param recognitionEvent Object containing data relating to the
     *                         recognition event such as trigger audio data (if
     *                         requested).
     */

    void onGenericSoundTriggerDetected(in SoundTrigger.GenericRecognitionEvent recognitionEvent);

    /**
     * Called when the recognition is paused temporarily for some reason.
     */
    void onRecognitionPaused();
    /**
     * Called when the recognition is resumed after it was temporarily paused.
     */
    void onRecognitionResumed();

    // Error callbacks to follow
    /**
     * Called when this recognition has been preempted by another.
     */
    void onPreempted();

    /**
     * Called when the underlying ST module service has died.
     */
    void onModuleDied();

    /**
     * Called when the service failed to gracefully resume recognition following a pause.
     * @param status - The received error code.
     */
    void onResumeFailed(int status);

    /**
     * Called when the service failed to pause recognition when required.
     * TODO(b/276507281) Remove. This should never happen, so we should abort instead.
     * @param status - The received error code.
     */
    void onPauseFailed(int status);
}
