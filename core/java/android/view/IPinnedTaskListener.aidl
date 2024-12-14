/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.pm.ParceledListSlice;
import android.graphics.Rect;
import android.view.DisplayInfo;

/**
 * Listener for changes to the pinned task made by the WindowManager.
 *
 * @hide
 */
oneway interface IPinnedTaskListener {

    /**
     * Called when the window manager has detected a change that would cause the movement bounds
     * to be changed (ie. after configuration change, aspect ratio change, etc).
     */
    void onMovementBoundsChanged(boolean fromImeAdjustment);

    /**
     * Called when window manager decides to adjust the pinned stack bounds because of the IME, or
     * when the listener is first registered to allow the listener to synchronized its state with
     * the controller.  This call will always be followed by a onMovementBoundsChanged() call
     * with fromImeAdjustement set to {@code true}.
     */
    void onImeVisibilityChanged(boolean imeVisible, int imeHeight);
}
