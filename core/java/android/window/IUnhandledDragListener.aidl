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
import android.window.IUnhandledDragCallback;

/**
 * An interface to a handler for global drags that are not consumed (ie. not handled by any window).
 * {@hide}
 */
oneway interface IUnhandledDragListener {
    /**
     * Called when the user finishes the drag gesture but no windows have reported handling the
     * drop.  The DragEvent is populated with the drag surface for the listener to animate.  The
     * listener *MUST* call the provided callback exactly once when it has finished handling the
     * drop.  If the listener calls the callback with `true` then it is responsible for removing
     * and releasing the drag surface passed through the DragEvent.
     */
    void onUnhandledDrop(in DragEvent event, in IUnhandledDragCallback callback);
}
