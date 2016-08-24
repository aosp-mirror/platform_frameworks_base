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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManagerInternal;
import android.os.IBinder;
import android.view.animation.Animation;

import java.util.List;

/**
 * Window manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class WindowManagerInternal {

    /**
     * Interface to receive a callback when the windows reported for
     * accessibility changed.
     */
    public interface WindowsForAccessibilityCallback {

        /**
         * Called when the windows for accessibility changed.
         *
         * @param windows The windows for accessibility.
         */
        public void onWindowsForAccessibilityChanged(List<WindowInfo> windows);
    }

    /**
     * Callbacks for contextual changes that affect the screen magnification
     * feature.
     */
    public interface MagnificationCallbacks {

        /**
         * Called when the region where magnification operates changes. Note that this isn't the
         * entire screen. For example, IMEs are not magnified.
         *
         * @param magnificationRegion the current magnification region
         */
        public void onMagnificationRegionChanged(Region magnificationRegion);

        /**
         * Called when an application requests a rectangle on the screen to allow
         * the client to apply the appropriate pan and scale.
         *
         * @param left The rectangle left.
         * @param top The rectangle top.
         * @param right The rectangle right.
         * @param bottom The rectangle bottom.
         */
        public void onRectangleOnScreenRequested(int left, int top, int right, int bottom);

        /**
         * Notifies that the rotation changed.
         *
         * @param rotation The current rotation.
         */
        public void onRotationChanged(int rotation);

        /**
         * Notifies that the context of the user changed. For example, an application
         * was started.
         */
        public void onUserContextChanged();
    }

    /**
     * Abstract class to be notified about {@link com.android.server.wm.AppTransition} events. Held
     * as an abstract class so a listener only needs to implement the methods of its interest.
     */
    public static abstract class AppTransitionListener {

        /**
         * Called when an app transition is being setup and about to be executed.
         */
        public void onAppTransitionPendingLocked() {}

        /**
         * Called when a pending app transition gets cancelled.
         */
        public void onAppTransitionCancelledLocked() {}

        /**
         * Called when an app transition gets started
         *
         * @param openToken the token for the opening app
         * @param closeToken the token for the closing app
         * @param openAnimation the animation for the opening app
         * @param closeAnimation the animation for the closing app
         */
        public void onAppTransitionStartingLocked(IBinder openToken, IBinder closeToken,
                Animation openAnimation, Animation closeAnimation) {}

        /**
         * Called when an app transition is finished running.
         *
         * @param token the token for app whose transition has finished
         */
        public void onAppTransitionFinishedLocked(IBinder token) {}
    }

    /**
      * An interface to be notified about hardware keyboard status.
      */
    public interface OnHardKeyboardStatusChangeListener {
        public void onHardKeyboardStatusChange(boolean available);
    }

    /**
     * Request that the window manager call
     * {@link DisplayManagerInternal#performTraversalInTransactionFromWindowManager}
     * within a surface transaction at a later time.
     */
    public abstract void requestTraversalFromDisplayManager();

    /**
     * Set by the accessibility layer to observe changes in the magnified region,
     * rotation, and other window transformations related to display magnification
     * as the window manager is responsible for doing the actual magnification
     * and has access to the raw window data while the accessibility layer serves
     * as a controller.
     *
     * @param callbacks The callbacks to invoke.
     */
    public abstract void setMagnificationCallbacks(@Nullable MagnificationCallbacks callbacks);

    /**
     * Set by the accessibility layer to specify the magnification and panning to
     * be applied to all windows that should be magnified.
     *
     * @param spec The MagnficationSpec to set.
     *
     * @see #setMagnificationCallbacks(MagnificationCallbacks)
     */
    public abstract void setMagnificationSpec(MagnificationSpec spec);

    /**
     * Obtains the magnification regions.
     *
     * @param magnificationRegion the current magnification region
     */
    public abstract void getMagnificationRegion(@NonNull Region magnificationRegion);

    /**
     * Gets the magnification and translation applied to a window given its token.
     * Not all windows are magnified and the window manager policy determines which
     * windows are magnified. The returned result also takes into account the compat
     * scale if necessary.
     *
     * @param windowToken The window's token.
     *
     * @return The magnification spec for the window.
     *
     * @see #setMagnificationCallbacks(MagnificationCallbacks)
     */
    public abstract MagnificationSpec getCompatibleMagnificationSpecForWindow(
            IBinder windowToken);

    /**
     * Sets a callback for observing which windows are touchable for the purposes
     * of accessibility.
     *
     * @param callback The callback.
     */
    public abstract void setWindowsForAccessibilityCallback(
            WindowsForAccessibilityCallback callback);

    /**
     * Sets a filter for manipulating the input event stream.
     *
     * @param filter The filter implementation.
     */
    public abstract void setInputFilter(IInputFilter filter);

    /**
     * Gets the token of the window that has input focus.
     *
     * @return The token.
     */
    public abstract IBinder getFocusedWindowToken();

    /**
     * @return Whether the keyguard is engaged.
     */
    public abstract boolean isKeyguardLocked();

    /**
     * Gets the frame of a window given its token.
     *
     * @param token The token.
     * @param outBounds The frame to populate.
     */
    public abstract void getWindowFrame(IBinder token, Rect outBounds);

    /**
     * Opens the global actions dialog.
     */
    public abstract void showGlobalActions();

    /**
     * Invalidate all visible windows. Then report back on the callback once all windows have
     * redrawn.
     */
    public abstract void waitForAllWindowsDrawn(Runnable callback, long timeout);

    /**
     * Adds a window token for a given window type.
     *
     * @param token The token to add.
     * @param type The window type.
     */
    public abstract void addWindowToken(android.os.IBinder token, int type);

    /**
     * Removes a window token.
     *
     * @param token The toke to remove.
     * @param removeWindows Whether to also remove the windows associated with the token.
     */
    public abstract void removeWindowToken(android.os.IBinder token, boolean removeWindows);

    /**
     * Registers a listener to be notified about app transition events.
     *
     * @param listener The listener to register.
     */
    public abstract void registerAppTransitionListener(AppTransitionListener listener);

    /**
     * Retrieves a height of input method window.
     */
    public abstract int getInputMethodWindowVisibleHeight();

    /**
      * Saves last input method window for transition.
      *
      * Note that it is assumed that this method is called only by InputMethodManagerService.
      */
    public abstract void saveLastInputMethodWindowForTransition();

    /**
     * Clears last input method window for transition.
     *
     * Note that it is assumed that this method is called only by InputMethodManagerService.
     */
    public abstract void clearLastInputMethodWindowForTransition();

    /**
      * Returns true when the hardware keyboard is available.
      */
    public abstract boolean isHardKeyboardAvailable();

    /**
      * Sets the callback listener for hardware keyboard status changes.
      *
      * @param listener The listener to set.
      */
    public abstract void setOnHardKeyboardStatusChangeListener(
        OnHardKeyboardStatusChangeListener listener);

    /** Returns true if the stack with the input Id is currently visible. */
    public abstract boolean isStackVisible(int stackId);

    /**
     * @return True if and only if the docked divider is currently in resize mode.
     */
    public abstract boolean isDockedDividerResizing();
}
