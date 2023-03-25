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

/**
 * Opaque callback for acknowledging oneway events.
 * Since there is no return channel for oneway events,
 * passing this interface in a oneway method allows the service to call
 * back to the client to indicate the event was registered.
 * This essentially functions like a <code> Future<void> </code> without
 * an error channel.
 * {@hide}
 */
oneway interface IAcknowledgeEvent {
    /**
     * Acknowledge that the event has been received.
     */
    void eventReceived();

}
