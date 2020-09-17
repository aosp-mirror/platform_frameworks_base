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

package com.android.test

class SurfaceProxy {
    init {
        System.loadLibrary("surface_jni")
    }

    external fun setSurface(surface: Any)
    external fun waitUntilBufferDisplayed(frameNumber: Int, timeoutSec: Int)
    external fun draw()

    // android/native_window.h functions
    external fun ANativeWindowLock()
    external fun ANativeWindowUnlockAndPost()
    external fun ANativeWindowSetBuffersGeometry(surface: Any, width: Int, height: Int, format: Int)
}
