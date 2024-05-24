/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.view.DragEvent;

/**
 * A callback for notifying the system when the unhandled drop is complete.
 * {@hide}
 */
oneway interface IUnhandledDragCallback {
    /**
     * Called when the IUnhandledDropListener has fully handled the drop, and the drag can be
     * cleaned up.  If handled is `true`, then cleanup of the drag and drag surface will be
     * immediate, otherwise, the system will treat the drag as a cancel back to the start of the
     * drag.
     */
    void notifyUnhandledDropComplete(boolean handled);
}
