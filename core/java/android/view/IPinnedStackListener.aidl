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

import android.content.ComponentName;
import android.content.pm.ParceledListSlice;
import android.graphics.Rect;
import android.view.DisplayInfo;
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
     * Called when the window manager has detected a change that would cause the movement bounds
     * to be changed (ie. after configuration change, aspect ratio change, etc). It then provides
     * the components that allow the listener to calculate the movement bounds itself.
     * The {@param animatingBounds} are provided to indicate the current target bounds of the
     * pinned stack (the final bounds if animating, the current bounds if not),
     * which may be helpful in calculating dependent animation bounds.
     */
    void onMovementBoundsChanged(in Rect animatingBounds, boolean fromImeAdjustment,
            boolean fromShelfAdjustment);

    /**
     * Called when window manager decides to adjust the pinned stack bounds because of the IME, or
     * when the listener is first registered to allow the listener to synchronized its state with
     * the controller.  This call will always be followed by a onMovementBoundsChanged() call
     * with fromImeAdjustement set to {@code true}.
     */
    void onImeVisibilityChanged(boolean imeVisible, int imeHeight);

    /**
     * Called when window manager decides to adjust the pinned stack bounds because of the shelf, or
     * when the listener is first registered to allow the listener to synchronized its state with
     * the controller.  This call will always be followed by a onMovementBoundsChanged() call
     * with fromShelfAdjustment set to {@code true}.
     */
    void onShelfVisibilityChanged(boolean shelfVisible, int shelfHeight);

    /**
     * Called when window manager decides to adjust the minimized state, or when the listener
     * is first registered to allow the listener to synchronized its state with the controller.
     */
    void onMinimizedStateChanged(boolean isMinimized);

    /**
     * Called when the set of actions for the current PiP activity changes, or when the listener
     * is first registered to allow the listener to synchronized its state with the controller.
     */
    void onActionsChanged(in ParceledListSlice actions);

    /**
     * Called by the window manager to notify the listener to save the reentry fraction,
     * typically when an Activity leaves PiP (picture-in-picture) mode to fullscreen.
     * {@param componentName} represents the application component of PiP window
     * while {@param bounds} is the current PiP bounds used to calculate the
     * reentry snap fraction.
     */
    void onSaveReentrySnapFraction(in ComponentName componentName, in Rect bounds);

    /**
     * Called by the window manager to notify the listener to reset saved reentry fraction,
     * typically when an Activity enters PiP (picture-in-picture) mode from fullscreen.
     * {@param componentName} represents the application component of PiP window.
     */
    void onResetReentrySnapFraction(in ComponentName componentName);

    /**
     * Called when the window manager has detected change on DisplayInfo,  or
     * when the listener is first registered to allow the listener to synchronized its state with
     * the controller.
     */
    void onDisplayInfoChanged(in DisplayInfo displayInfo);

    /**
     * Called by the window manager at the beginning of a configuration update cascade
     * since the metrics from these resources are used for bounds calculations.
     */
    void onConfigurationChanged();

    /**
     * Called by the window manager when the aspect ratio is reset.
     */
    void onAspectRatioChanged(float aspectRatio);

    /**
     * Called by the window manager to notify the listener to prepare for PiP animation.
     * Internally, the target bounds would be calculated from the given {@param aspectRatio}
     * and {@param bounds}, the saved reentry snap fraction also contributes.
     * Caller would wait for a IPinnedStackController#startAnimation callback to actually
     * start the animation, see details in IPinnedStackController.
     */
    void onPrepareAnimation(in Rect sourceRectHint, float aspectRatio, in Rect bounds);
}
