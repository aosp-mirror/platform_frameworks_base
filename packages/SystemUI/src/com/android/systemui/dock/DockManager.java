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
 * Allows an app to handle dock events.
 */
public interface DockManager {

    /**
     * Uninitialized / undocking dock states.
     */
    int STATE_NONE = 0;
    /**
     * The state for docking
     */
    int STATE_DOCKED = 1;
    /**
     * The state for docking without showing UI.
     */
    int STATE_DOCKED_HIDE = 2;

    /**
     * Indicates there's no alignment info. This could happen when the device is unable to decide
     * its alignment condition.
     */
    int ALIGN_STATE_UNKNOWN = -1;

    /**
     * Indicates there's no alignment issue.
     */
    int ALIGN_STATE_GOOD = 0;

    /**
     * Indicates it's slightly not aligned with dock.
     */
    int ALIGN_STATE_POOR = 1;

    /**
     * Indicates it's not aligned with dock.
     */
    int ALIGN_STATE_TERRIBLE = 2;

    /**
     * Adds a dock event listener into manager.
     *
     * @param callback A {@link DockEventListener} which want to add
     */
    void addListener(DockEventListener callback);

    /**
     * Removes the added listener from dock manager
     *
     * @param callback A {@link DockEventListener} which want to remove
     */
    void removeListener(DockEventListener callback);

    /**
     * Adds a alignment listener into manager.
     *
     * @param listener A {@link AlignmentStateListener} which want to add
     */
    void addAlignmentStateListener(AlignmentStateListener listener);

    /**
     * Removes the added alignment listener from dock manager.
     *
     * @param listener A {@link AlignmentStateListener} which want to remove
     */
    void removeAlignmentStateListener(AlignmentStateListener listener);

    /**
    * Returns true if the device is in docking state.
    */
    boolean isDocked();

    /**
     * Returns true if it is hiding docking UI.
     */
    boolean isHidden();

    /**
     * Listens to dock events.
     */
    interface DockEventListener {
        /**
         * Override to handle dock events.
         */
        void onEvent(int event);
    }

    /**
     * Listens to dock alignment state changed.
     */
    interface AlignmentStateListener {
        /**
         * Override to handle alignment state changes.
         */
        void onAlignmentStateChanged(int alignState);
    }
}
