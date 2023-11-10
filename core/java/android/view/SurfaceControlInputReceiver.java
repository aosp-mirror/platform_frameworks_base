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

package android.view;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;

import com.android.window.flags.Flags;

/**
 * Provides a mechanism for a SurfaceControl to receive input events.
 */
@FlaggedApi(Flags.FLAG_SURFACE_CONTROL_INPUT_RECEIVER)
public interface SurfaceControlInputReceiver {
    /**
     * When input events are batched, this is called at most once per frame. When non batched, this
     * is called immediately for the input event.
     *
     * @param event The input event that was received. This input event object will become invalid
     *              and recycled after this method is invoked. If there is need to persist this
     *              object beyond the scope of this method, the overriding code should make a copy
     *              of this object. For example, using
     *              {@link MotionEvent#obtain(MotionEvent other)} or
     *              {@link KeyEvent#KeyEvent(KeyEvent)} }
     * @return true if the event was handled, false otherwise.
     */
    boolean onInputEvent(@NonNull InputEvent event);

}
