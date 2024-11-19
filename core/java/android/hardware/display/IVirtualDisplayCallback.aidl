/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.hardware.display;

/** @hide */
oneway interface IVirtualDisplayCallback {
    /**
     * Called when the virtual display video projection has been
     * paused by the system or when the surface has been detached
     * by the application by calling setSurface(null).
     * The surface will not receive any more buffers while paused.
     */
    void onPaused();

    /**
     * Called when the virtual display video projection has been
     * resumed after having been paused.
     */
    void onResumed();

    /**
     * Called when the virtual display video projection has been
     * stopped by the system.  It will no longer receive frames
     * and it will never be resumed.  It is still the responsibility
     * of the application to release() the virtual display.
     */
    void onStopped();
}
