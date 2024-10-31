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

package android.security.forensic;
import android.security.forensic.ForensicEvent;

import com.android.internal.infra.AndroidFuture;

/** {@hide} */
oneway interface IBackupTransport {
    /**
     * Initialize the server side.
     */
    void initialize(in AndroidFuture<int> resultFuture);

    /**
     * Send forensic logging data to the backup destination.
     * The data is a list of ForensicEvent.
     * The ForensicEvent is an abstract class that represents
     * different type of events.
     */
    void addData(in List<ForensicEvent> events, in AndroidFuture<int> resultFuture);

    /**
     * Release the binder to the server.
     */
    void release(in AndroidFuture<int> resultFuture);
}
