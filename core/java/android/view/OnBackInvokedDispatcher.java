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

package android.view;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.os.Build;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Dispatcher to register {@link OnBackInvokedCallback} instances for handling
 * back invocations.
 *
 * It also provides interfaces to update the attributes of {@link OnBackInvokedCallback}.
 * Attribute updates are proactively pushed to the window manager if they change the dispatch
 * target (a.k.a. the callback to be invoked next), or its behavior.
 */
public abstract class OnBackInvokedDispatcher {

    /** @hide */
    public static final String TAG = "OnBackInvokedDispatcher";

    /** @hide */
    public static final boolean DEBUG = Build.isDebuggable();

    /** @hide */
    @IntDef({
            PRIORITY_DEFAULT,
            PRIORITY_OVERLAY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Priority{}

    /**
     * Priority level of {@link OnBackInvokedCallback}s for overlays such as menus and
     * navigation drawers that should receive back dispatch before non-overlays.
     */
    public static final int PRIORITY_OVERLAY = 1000000;

    /**
     * Default priority level of {@link OnBackInvokedCallback}s.
     */
    public static final int PRIORITY_DEFAULT = 0;

    /**
     * Registers a {@link OnBackInvokedCallback}.
     *
     * Within the same priority level, callbacks are invoked in the reverse order in which
     * they are registered. Higher priority callbacks are invoked before lower priority ones.
     *
     * @param callback The callback to be registered. If the callback instance has been already
     *                 registered, the existing instance (no matter its priority) will be
     *                 unregistered and registered again.
     * @param priority The priority of the callback.
     */
    @SuppressLint("SamShouldBeLast")
    public abstract void registerOnBackInvokedCallback(
            @NonNull OnBackInvokedCallback callback, @Priority int priority);

    /**
     * Unregisters a {@link OnBackInvokedCallback}.
     *
     * @param callback The callback to be unregistered. Does nothing if the callback has not been
     *                 registered.
     */
    public abstract void unregisterOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback);
}
