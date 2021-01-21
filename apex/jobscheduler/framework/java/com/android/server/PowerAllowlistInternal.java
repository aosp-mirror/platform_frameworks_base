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

package com.android.server;

import android.annotation.NonNull;

public interface PowerAllowlistInternal {
    /**
     * Listener to be notified when the temporary allowlist changes.
     */
    interface TempAllowlistChangeListener {
        void onAppAdded(int uid);
        void onAppRemoved(int uid);
    }

    /**
     * Registers a listener that will be notified when the temp allowlist changes.
     */
    void registerTempAllowlistChangeListener(@NonNull TempAllowlistChangeListener listener);

    /**
     * Unregisters a registered stationary listener from being notified when the temp allowlist
     * changes.
     */
    void unregisterTempAllowlistChangeListener(@NonNull TempAllowlistChangeListener listener);
}
