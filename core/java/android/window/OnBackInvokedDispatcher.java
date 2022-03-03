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
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
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
public interface OnBackInvokedDispatcher {
    /**
     * Enables dispatching the "back" action via {@link OnBackInvokedDispatcher}.
     *
     * When enabled, the following APIs are no longer invoked:
     * <ul>
     * <li> {@link android.app.Activity#onBackPressed}
     * <li> {@link android.app.Dialog#onBackPressed}
     * <li> {@link android.view.KeyEvent#KEYCODE_BACK} is no longer dispatched.
     * </ul>
     *
     * @hide
     */
    @TestApi
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    long DISPATCH_BACK_INVOCATION_AHEAD_OF_TIME = 195946584L;

    /** @hide */
    String TAG = "OnBackInvokedDispatcher";

    /** @hide */
    boolean DEBUG = Build.isDebuggable();

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
     * @param callback The callback to be registered. If the callback instance has been already
     *                 registered, the existing instance (no matter its priority) will be
     *                 unregistered and registered again.
     * @param priority The priority of the callback.
     * @throws {@link IllegalArgumentException} if the priority is negative.
     */
    @SuppressLint({"SamShouldBeLast", "ExecutorRegistration"})
    void registerOnBackInvokedCallback(
            @NonNull OnBackInvokedCallback callback, @Priority @IntRange(from = 0) int priority);

    /**
     * Unregisters a {@link OnBackInvokedCallback}.
     *
     * @param callback The callback to be unregistered. Does nothing if the callback has not been
     *                 registered.
     */
    void unregisterOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback);

    /**
     * Returns the most prioritized callback to receive back dispatch next.
     * @hide
     */
    @Nullable
    default OnBackInvokedCallback getTopCallback() {
        return null;
    }

    /**
     * Registers a {@link OnBackInvokedCallback} with system priority.
     * @hide
     */
    default void registerSystemOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback) { }
}
