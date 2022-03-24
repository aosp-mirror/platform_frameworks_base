/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;

/**
 * Allow registering listeners for tracking changes in audio capture state (when recording starts /
 * stops). The client will be notified in a synchronized manner.
 */
interface ICaptureStateNotifier {
    interface Listener {
        void onCaptureStateChange(boolean state);
    }

    /**
     * Register a listener for state change notifications. Returns the current capture state and
     * any subsequent changes will be sent to the listener.
     * @param listener The listener.
     * @return The state at the time of registration.
     */
    boolean registerListener(@NonNull Listener listener);

    /**
     * Unregister a listener, previously registered with {@link #registerListener(Listener)}.
     * Once this call returns, no more invocations of the listener will be made.
     */
    void unregisterListener(@NonNull Listener listener);
}
