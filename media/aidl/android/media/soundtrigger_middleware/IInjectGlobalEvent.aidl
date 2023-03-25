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

import android.media.soundtrigger_middleware.IAcknowledgeEvent;

/**
 * Interface for injecting global events to the fake STHAL.
 * {@hide}
 */
oneway interface IInjectGlobalEvent {

    /**
     * Request a fake STHAL restart.
     * This invalidates the {@link IInjectGlobalEvent}.
     */
    void triggerRestart();

    /**
     * Triggers global resource contention into the fake STHAL. Loads/startRecognition
     * will fail with RESOURCE_CONTENTION.
     * @param isContended - true to enable resource contention. false to disable resource contention
     *                      and resume normal functionality.
     * @param callback - Call {@link IAcknowledgeEvent#eventReceived()} on this interface once
     * the contention status is successfully set.
     */
    void setResourceContention(boolean isContended, IAcknowledgeEvent callback);

}
