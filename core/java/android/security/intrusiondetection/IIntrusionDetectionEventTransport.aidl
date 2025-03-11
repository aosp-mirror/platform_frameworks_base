/*
 * Copyright 2024 The Android Open Source Project
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

package android.security.intrusiondetection;

import android.security.intrusiondetection.IntrusionDetectionEvent;

import com.android.internal.infra.AndroidFuture;

/** {@hide} */
oneway interface IIntrusionDetectionEventTransport {
    /**
     * Initialize the server side.
     */
    void initialize(in AndroidFuture<boolean> resultFuture);

    /**
     * Send intrusiondetection logging data to the transport destination.
     * The data is a list of IntrusionDetectionEvent.
     * The IntrusionDetectionEvent is an abstract class that represents
     * different types of events.
     */
    void addData(
        in List<IntrusionDetectionEvent> events,
        in AndroidFuture<boolean> resultFuture);

    /**
     * Release the binder to the server.
     */
    void release(in AndroidFuture<boolean> resultFuture);
}
