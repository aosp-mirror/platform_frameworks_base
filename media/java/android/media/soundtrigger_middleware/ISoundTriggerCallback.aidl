/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.media.soundtrigger_middleware.RecognitionEvent;
import android.media.soundtrigger_middleware.PhraseRecognitionEvent;

/**
 * Main interface for a client to get notifications of events coming from this module.
 *
 * {@hide}
 */
oneway interface ISoundTriggerCallback {
    /**
     * Invoked whenever a recognition event is triggered (typically, on recognition, but also in
     * case of external aborting of a recognition or a forced recognition event - see the status
     * code in the event for determining).
     */
    void onRecognition(int modelHandle, in RecognitionEvent event);
     /**
      * Invoked whenever a phrase recognition event is triggered (typically, on recognition, but
      * also in case of external aborting of a recognition or a forced recognition event - see the
      * status code in the event for determining).
      */
    void onPhraseRecognition(int modelHandle, in PhraseRecognitionEvent event);
    /**
     * Notifies the client the recognition has become available after previously having been
     * unavailable, or vice versa. This method will always be invoked once immediately after
     * attachment, and then every time there is a change in availability.
     * When availability changes from available to unavailable, all active recognitions are aborted,
     * and this event will be sent in addition to the abort event.
     */
    void onRecognitionAvailabilityChange(boolean available);
    /**
     * Notifies the client that the associated module has crashed and restarted. The module instance
     * is no longer usable and will throw a ServiceSpecificException with a Status.DEAD_OBJECT code
     * for every call. The client should detach, then re-attach to the module in order to get a new,
     * usable instance. All state for this module has been lost.
     */
     void onModuleDied();
}
