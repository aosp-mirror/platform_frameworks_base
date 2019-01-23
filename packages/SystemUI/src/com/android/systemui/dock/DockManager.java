/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.dock;

/**
 * Allows an app to handle dock events
 */
public interface DockManager {

    /**
     * Uninitialized / undocking dock states
     */
    int STATE_NONE = 0;
    /**
     * The state for docking
     */
    int STATE_DOCKED = 1;
    /**
     * The state for docking without showing UI
     */
    int STATE_DOCKED_HIDE = 2;

    /**
     * Add a dock event listener into manager
     *
     * @param callback A {@link DockEventListener} which want to add
     */
    void addListener(DockEventListener callback);

    /**
     * Remove the added listener from dock manager
     *
     * @param callback A {@link DockEventListener} which want to remove
     */
    void removeListener(DockEventListener callback);

    /**
    * Returns true if the device is in docking state.
    */
    boolean isDocked();

    /** Callback for receiving dock events */
    interface DockEventListener {
        /**
         * Override to handle dock events
         */
        void onEvent(int event);
    }
}
