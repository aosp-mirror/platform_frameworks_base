/*
 * Copyright 2024 The Android Open Source Project
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

package android.hardware.input;

import android.hardware.input.AidlKeyGestureEvent;
import android.os.IBinder;

/** @hide */
interface IKeyGestureHandler {

    /**
     * Called when a key gesture starts, ends, or is cancelled. If a handler returns {@code true},
     * it means they intend to handle the full gesture and should handle all the events pertaining
     * to that gesture.
     */
    boolean handleKeyGesture(in AidlKeyGestureEvent event, in IBinder focusedToken);

    /**
     * Called to know if a particular gesture type is supported by the handler.
     *
     * TODO(b/358569822): Remove this call to reduce the binder calls to single call for
     *  handleKeyGesture. For this we need to remove dependency of multi-key gestures to identify if
     *  a key gesture is supported on first relevant key down.
     *  Also, for now we prioritize handlers in the system server process above external handlers to
     *  reduce IPC binder calls.
     */
    boolean isKeyGestureSupported(int gestureType);
}
