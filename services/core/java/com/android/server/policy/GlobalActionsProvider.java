/*
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.policy;

/** Used with LocalServices to add custom handling to global actions. */
public interface GlobalActionsProvider {
    /** @return {@code true} if the dialog is enabled. */
    boolean isGlobalActionsDisabled();
    /** Set the listener that will handle various global actions evetns. */
    void setGlobalActionsListener(GlobalActionsListener listener);
    /** Show the global actions UI to the user. */
    void showGlobalActions();

    /** Listener to pass global actions events back to system. */
    public interface GlobalActionsListener {
        /**
         * Called when sysui starts and connects its status bar, or when the status bar binder
         * dies indicating sysui is no longer alive.
         */
        void onGlobalActionsAvailableChanged(boolean available);

        /**
         * Callback from sysui to notify system that global actions has been successfully shown.
         */
        void onGlobalActionsShown();

        /**
         * Callback from sysui to notify system that the user has dismissed global actions and
         * it no longer needs to be displayed (even if sysui dies).
         */
        void onGlobalActionsDismissed();
    }
}
