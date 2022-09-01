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

package com.android.server.display;

import android.os.IBinder;

import java.util.Objects;

/**
 * Calls into SurfaceFlinger for Display creation and deletion.
 */
public class DisplayControl {
    private static native IBinder nativeCreateDisplay(String name, boolean secure);
    private static native void nativeDestroyDisplay(IBinder displayToken);

    /**
     * Create a display in SurfaceFlinger.
     *
     * @param name The name of the display
     * @param secure Whether this display is secure.
     * @return The token reference for the display in SurfaceFlinger.
     */
    public static IBinder createDisplay(String name, boolean secure) {
        Objects.requireNonNull(name, "name must not be null");
        return nativeCreateDisplay(name, secure);
    }

    /**
     * Destroy a display in SurfaceFlinger.
     *
     * @param displayToken The display token for the display to be destroyed.
     */
    public static void destroyDisplay(IBinder displayToken) {
        if (displayToken == null) {
            throw new IllegalArgumentException("displayToken must not be null");
        }

        nativeDestroyDisplay(displayToken);
    }

}
