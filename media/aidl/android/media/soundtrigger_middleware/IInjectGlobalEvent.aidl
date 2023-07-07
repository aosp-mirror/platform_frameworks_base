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
     * Trigger a fake STHAL restart.
     * This invalidates the {@link IInjectGlobalEvent}.
     */
    void triggerRestart();

    /**
     * Set global resource contention within the fake STHAL. Loads/startRecognition
     * will fail with {@code RESOURCE_CONTENTION} until unset.
     * @param isContended - true to enable resource contention. false to disable resource contention
     *                      and resume normal functionality.
     * @param callback - Call {@link IAcknowledgeEvent#eventReceived()} on this interface once
     * the contention status is successfully set.
     */
    void setResourceContention(boolean isContended, IAcknowledgeEvent callback);

    /**
     * Trigger an
     * {@link android.hardware.soundtrigger3.ISoundTriggerHwGlobalCallback#onResourcesAvailable}
     * callback from the fake STHAL. This callback is used to signal to the framework that
     * previous operations which failed may now succeed.
     */
    void triggerOnResourcesAvailable();
}
