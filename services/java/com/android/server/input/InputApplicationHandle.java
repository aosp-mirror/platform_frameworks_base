/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.input;

/**
 * Functions as a handle for an application that can receive input.
 * Enables the native input dispatcher to refer indirectly to the window manager's
 * application window token.
 * @hide
 */
public final class InputApplicationHandle {
    // Pointer to the native input application handle.
    // This field is lazily initialized via JNI.
    @SuppressWarnings("unused")
    private int ptr;

    // The window manager's application window token.
    public final Object appWindowToken;

    // Application name.
    public String name;

    // Dispatching timeout.
    public long dispatchingTimeoutNanos;

    private native void nativeDispose();

    public InputApplicationHandle(Object appWindowToken) {
        this.appWindowToken = appWindowToken;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            nativeDispose();
        } finally {
            super.finalize();
        }
    }
}
