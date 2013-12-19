/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.power;

/**
 * Low-level screen on blocker mechanism which is used to keep the screen off
 * or the contents of the screen hidden until the window manager is ready to show new content.
 */
interface ScreenOnBlocker {
    /**
     * Acquires the screen on blocker.
     * Prevents the screen from turning on.
     *
     * Calls to acquire() nest and must be matched by the same number
     * of calls to release().
     */
    void acquire();

    /**
     * Releases the screen on blocker.
     * Allows the screen to turn on.
     *
     * It is an error to call release() if the screen on blocker has not been acquired.
     * The system may crash.
     */
    void release();
}
