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

package android.window;

import android.annotation.NonNull;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.OnBackInvokedCallback;
import android.view.OnBackInvokedDispatcher;

/**
 * Provides window based implementation of {@link OnBackInvokedDispatcher}.
 *
 * Callbacks with higher priorities receive back dispatching first.
 * Within the same priority, callbacks receive back dispatching in the reverse order
 * in which they are added.
 *
 * When the top priority callback is updated, the new callback is propagated to the Window Manager
 * if the window the instance is associated with has been attached. It is allowed to register /
 * unregister {@link OnBackInvokedCallback}s before the window is attached, although callbacks
 * will not receive dispatches until window attachment.
 *
 * @hide
 */
public class WindowOnBackInvokedDispatcher extends OnBackInvokedDispatcher {
    private IWindowSession mWindowSession;
    private IWindow mWindow;

    /**
     * Sends the pending top callback (if one exists) to WM when the view root
     * is attached a window.
     */
    public void attachToWindow(@NonNull IWindowSession windowSession, @NonNull IWindow window) {
        mWindowSession = windowSession;
        mWindow = window;
        // TODO(b/209867448): Send the top callback to WM (if one exists).
    }

    /** Detaches the dispatcher instance from its window. */
    public void detachFromWindow() {
        mWindow = null;
        mWindowSession = null;
    }

    @Override
    public void registerOnBackInvokedCallback(
            @NonNull OnBackInvokedCallback callback, @Priority int priority) {
        // TODO(b/209867448): To be implemented.
    }

    @Override
    public void unregisterOnBackInvokedCallback(
            @NonNull OnBackInvokedCallback callback) {
        // TODO(b/209867448): To be implemented.
    }

    /** Clears all registered callbacks on the instance. */
    public void clear() {
        // TODO(b/209867448): To be implemented.
    }
}
