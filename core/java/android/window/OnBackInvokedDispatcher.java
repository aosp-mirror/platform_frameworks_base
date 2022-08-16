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

package android.window;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SuppressLint;

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
public interface OnBackInvokedDispatcher {
    /** @hide */
    String TAG = "OnBackInvokedDispatcher";

    /** @hide */
    boolean DEBUG = false;

    /** @hide */
    @IntDef({
            PRIORITY_DEFAULT,
            PRIORITY_OVERLAY,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Priority{}

    /**
     * Priority level of {@link OnBackInvokedCallback}s for overlays such as menus and
     * navigation drawers that should receive back dispatch before non-overlays.
     */
    int PRIORITY_OVERLAY = 1000000;

    /**
     * Default priority level of {@link OnBackInvokedCallback}s.
     */
    int PRIORITY_DEFAULT = 0;

    /**
     * Priority level of {@link OnBackInvokedCallback}s registered by the system.
     *
     * System back animation will play when the callback to receive dispatch has this priority.
     * @hide
     */
    int PRIORITY_SYSTEM = -1;

    /**
     * Registers a {@link OnBackInvokedCallback}.
     *
     * Within the same priority level, callbacks are invoked in the reverse order in which
     * they are registered. Higher priority callbacks are invoked before lower priority ones.
     *
     * @param priority The priority of the callback.
     * @param callback The callback to be registered. If the callback instance has been already
     *                 registered, the existing instance (no matter its priority) will be
     *                 unregistered and registered again.
     * @throws {@link IllegalArgumentException} if the priority is negative.
     */
    @SuppressLint({"ExecutorRegistration"})
    void registerOnBackInvokedCallback(
            @Priority @IntRange(from = 0) int priority, @NonNull OnBackInvokedCallback callback);

    /**
     * Unregisters a {@link OnBackInvokedCallback}.
     *
     * @param callback The callback to be unregistered. Does nothing if the callback has not been
     *                 registered.
     */
    void unregisterOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback);

    /**
     * Registers a {@link OnBackInvokedCallback} with system priority.
     * @hide
     */
    default void registerSystemOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback) { }


    /**
     * Sets an {@link ImeOnBackInvokedDispatcher} to forward {@link OnBackInvokedCallback}s
     * from IME to the app process to be registered on the app window.
     *
     * Only call this on the IME window. Create the {@link ImeOnBackInvokedDispatcher} from
     * the application process and override
     * {@link ImeOnBackInvokedDispatcher#getReceivingDispatcher()} to point to the app
     * window's {@link WindowOnBackInvokedDispatcher}.
     *
     * @hide
     */
    default void setImeOnBackInvokedDispatcher(
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher) { }
}
