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

import android.content.pm.ParceledListSlice;
import android.view.IPinnedStackController;

/**
 * Listener for changes to the pinned stack made by the WindowManager.
 *
 * @hide
 */
oneway interface IPinnedStackListener {

    /**
     * Called when the listener is registered and provides an interface to call back to the pinned
     * stack controller to update the controller of the pinned stack state.
     */
    void onListenerRegistered(IPinnedStackController controller);

    /**
     * Called when window manager decides to adjust the pinned stack bounds, or when the listener
     * is first registered to allow the listener to synchronized its state with the controller.
     */
    void onBoundsChanged(boolean adjustedForIme);

    /**
     * Called when window manager decides to adjust the minimized state, or when the listener
     * is first registered to allow the listener to synchronized its state with the controller.
     */
    void onMinimizedStateChanged(boolean isMinimized);

    /**
     * Called when window manager decides to adjust the snap-to-edge state, which determines whether
     * to snap only to the corners of the screen or to the closest edge.  It is called when the
     * listener is first registered to allow the listener to synchronized its state with the
     * controller.
     */
    void onSnapToEdgeStateChanged(boolean isSnapToEdge);

    /**
     * Called when the set of actions for the current PiP activity changes, or when the listener
     * is first registered to allow the listener to synchronized its state with the controller.
     */
    void onActionsChanged(in ParceledListSlice actions);
}
