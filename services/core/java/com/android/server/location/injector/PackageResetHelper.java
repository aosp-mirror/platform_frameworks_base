/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.CopyOnWriteArrayList;

/** Helpers for tracking queries and resets of package state. */
public abstract class PackageResetHelper {

    /** Interface for responding to reset events. */
    public interface Responder {

        /**
         * Called when a package's runtime state is being reset for whatever reason and any
         * components carrying runtime state on behalf of the package should clear that state.
         *
         * @param packageName The name of the package.
         */
        void onPackageReset(String packageName);

        /**
         * Called when the system queries whether this package has any active state for the given
         * package. Should return true if the component has some runtime state that is resetable of
         * behalf of the given package, and false otherwise.
         *
         * @param packageName The name of the package.
         * @return True if this component has resetable state for the given package.
         */
        boolean isResetableForPackage(String packageName);
    }

    private final CopyOnWriteArrayList<Responder> mResponders;

    public PackageResetHelper() {
        mResponders = new CopyOnWriteArrayList<>();
    }

    /** Begin listening for package reset events. */
    public synchronized void register(Responder responder) {
        boolean empty = mResponders.isEmpty();
        mResponders.add(responder);
        if (empty) {
            onRegister();
        }
    }

    /** Stop listening for package reset events. */
    public synchronized void unregister(Responder responder) {
        mResponders.remove(responder);
        if (mResponders.isEmpty()) {
            onUnregister();
        }
    }

    @GuardedBy("this")
    protected abstract void onRegister();

    @GuardedBy("this")
    protected abstract void onUnregister();

    protected final void notifyPackageReset(String packageName) {
        if (D) {
            Log.d(TAG, "package " + packageName + " reset");
        }

        for (Responder responder : mResponders) {
            responder.onPackageReset(packageName);
        }
    }

    protected final boolean queryResetableForPackage(String packageName) {
        for (Responder responder : mResponders) {
            if (responder.isResetableForPackage(packageName)) {
                return true;
            }
        }

        return false;
    }
}
