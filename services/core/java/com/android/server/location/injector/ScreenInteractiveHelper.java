/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.injector;

import static com.android.server.location.LocationManagerService.D;
import static com.android.server.location.LocationManagerService.TAG;

import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides accessors and listeners for screen interactive state (screen on/off).
 */
public abstract class ScreenInteractiveHelper {

    /**
     * Listener for screen interactive changes.
     */
    public interface ScreenInteractiveChangedListener {
        /**
         * Called when the screen interative state changes.
         */
        void onScreenInteractiveChanged(boolean isInteractive);
    }

    private final CopyOnWriteArrayList<ScreenInteractiveChangedListener> mListeners;

    public ScreenInteractiveHelper() {
        mListeners = new CopyOnWriteArrayList<>();
    }

    /**
     * Add a listener for changes to screen interactive state. Callbacks occur on an unspecified
     * thread.
     */
    public final void addListener(ScreenInteractiveChangedListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener for changes to screen interactive state.
     */
    public final void removeListener(ScreenInteractiveChangedListener listener) {
        mListeners.remove(listener);
    }

    protected final void notifyScreenInteractiveChanged(boolean interactive) {
        if (D) {
            Log.d(TAG, "screen interactive is now " + interactive);
        }

        for (ScreenInteractiveChangedListener listener : mListeners) {
            listener.onScreenInteractiveChanged(interactive);
        }
    }

    /**
     * Returns true if the screen is currently interactive, and false otherwise.
     */
    public abstract boolean isInteractive();
}
