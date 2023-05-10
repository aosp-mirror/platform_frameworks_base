/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media.soundtrigger_middleware;

import android.media.soundtrigger.PhraseRecognitionExtra;

/**
 * Interface for injecting recognition events into the ST Mock HAL.
 * {@hide}
 */
oneway interface IInjectRecognitionEvent {

    /**
     * Trigger a recognition event for the recognition session associated with
     * this object.
     * This invalidates the {@link IInjectRecognitionEvent}.
     * @param data the recognition data that the client of this model will receive
     * @param phraseExtras extra data only delivered for keyphrase models.
     */
    void triggerRecognitionEvent(in byte[] data,
            in @nullable PhraseRecognitionExtra[] phraseExtras);

    /**
     * Trigger an abort event for the recognition session associated with this object.
     * This invalidates the {@link IInjectRecognitionEvent}.
     */
    void triggerAbortRecognition();
}
