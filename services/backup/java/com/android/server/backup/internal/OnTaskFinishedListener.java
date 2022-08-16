/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.internal;

import com.android.server.backup.transport.TransportConnection;
import com.android.server.backup.transport.TransportConnectionListener;

/** Listener to be called when a task finishes, successfully or not. */
public interface OnTaskFinishedListener {
    OnTaskFinishedListener NOP = caller -> {};

    /**
     * Called when a task finishes, successfully or not.
     *
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportConnection#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     */
    void onFinished(String caller);
}
