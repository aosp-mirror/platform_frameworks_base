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

import android.location.util.identity.CallerIdentity;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides helpers and listeners for appops.
 */
public abstract class AppOpsHelper {

    /**
     * Listener for current user changes.
     */
    public interface LocationAppOpListener {

        /**
         * Called when something has changed about a location appop for the given package.
         */
        void onAppOpsChanged(String packageName);
    }

    private final CopyOnWriteArrayList<LocationAppOpListener> mListeners;

    public AppOpsHelper() {
        mListeners = new CopyOnWriteArrayList<>();
    }

    protected final void notifyAppOpChanged(String packageName) {
        for (LocationAppOpListener listener : mListeners) {
            listener.onAppOpsChanged(packageName);
        }
    }

    /**
     * Adds a listener for app ops events. Callbacks occur on an unspecified thread.
     */
    public final void addListener(LocationAppOpListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener for app ops events.
     */
    public final void removeListener(LocationAppOpListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Starts the given appop.
     */
    public abstract boolean startOpNoThrow(int appOp, CallerIdentity callerIdentity);

    /**
     * Finishes the given appop.
     */
    public abstract void finishOp(int appOp, CallerIdentity callerIdentity);

    /**
     * Checks the given appop.
     */
    public abstract boolean checkOpNoThrow(int appOp, CallerIdentity callerIdentity);

    /**
     * Notes the given appop (and may throw a security exception).
     */
    public abstract boolean noteOp(int appOp, CallerIdentity callerIdentity);

    /**
     * Notes the given appop.
     */
    public abstract boolean noteOpNoThrow(int appOp, CallerIdentity callerIdentity);
}
