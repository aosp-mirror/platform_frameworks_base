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

import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.PhraseRecognitionEvent;

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
     * In case of abortion, the caller may retry after the next onRecognitionAvailabilityChange()
     * callback.
     */
    void onRecognition(int modelHandle, in RecognitionEvent event);
     /**
      * Invoked whenever a phrase recognition event is triggered (typically, on recognition, but
      * also in case of external aborting of a recognition or a forced recognition event - see the
      * status code in the event for determining).
      * In case of abortion, the caller may retry after the next onRecognitionAvailabilityChange()
      * callback.
      */
    void onPhraseRecognition(int modelHandle, in PhraseRecognitionEvent event);
    /**
     * Notifies the client that some start/load operations that have previously failed for resource
     * reasons (threw a ServiceSpecificException(RESOURCE_CONTENTION) or have been preempted) may
     * now succeed. This is not a guarantee, but a hint for the client to retry.
     */
    void onResourcesAvailable();
    /**
     * Notifies the client that a model had been preemptively unloaded by the service.
     * The caller may retry after the next onRecognitionAvailabilityChange() callback.
     */
    void onModelUnloaded(int modelHandle);
    /**
     * Notifies the client that the associated module has crashed and restarted. The module instance
     * is no longer usable and will throw a ServiceSpecificException with a Status.DEAD_OBJECT code
     * for every call. The client should detach, then re-attach to the module in order to get a new,
     * usable instance. All state for this module has been lost.
     */
     void onModuleDied();
}
