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

package android.hardware.input;

import android.annotation.NonNull;
import android.graphics.PointF;
import android.hardware.display.DisplayViewport;
import android.os.IBinder;
import android.view.InputChannel;

import java.util.List;

/**
 * Input manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class InputManagerInternal {

    /**
     * Called by the display manager to set information about the displays as needed
     * by the input system.  The input system must copy this information to retain it.
     */
    public abstract void setDisplayViewports(List<DisplayViewport> viewports);

    /**
     * Called by the power manager to tell the input manager whether it should start
     * watching for wake events.
     */
    public abstract void setInteractive(boolean interactive);

    /**
     * Toggles Caps Lock state for input device with specific id.
     *
     * @param deviceId The id of input device.
     */
    public abstract void toggleCapsLock(int deviceId);

    /**
     * Set whether the input stack should deliver pulse gesture events when the device is asleep.
     */
    public abstract void setPulseGestureEnabled(boolean enabled);

    /**
     * Atomically transfers touch focus from one window to another as identified by
     * their input channels.  It is possible for multiple windows to have
     * touch focus if they support split touch dispatch
     * {@link android.view.WindowManager.LayoutParams#FLAG_SPLIT_TOUCH} but this
     * method only transfers touch focus of the specified window without affecting
     * other windows that may also have touch focus at the same time.
     *
     * @param fromChannelToken The channel token of a window that currently has touch focus.
     * @param toChannelToken The channel token of the window that should receive touch focus in
     * place of the first.
     * @return {@code true} if the transfer was successful. {@code false} if the window with the
     * specified channel did not actually have touch focus at the time of the request.
     */
    public abstract boolean transferTouchFocus(@NonNull IBinder fromChannelToken,
            @NonNull IBinder toChannelToken);

    /**
     * Sets the display id that the MouseCursorController will be forced to target. Pass
     * {@link android.view.Display#INVALID_DISPLAY} to clear the override.
     */
    public abstract void setVirtualMousePointerDisplayId(int pointerDisplayId);

    /**
     * Gets the display id that the MouseCursorController is being forced to target. Returns
     * {@link android.view.Display#INVALID_DISPLAY} if there is no override
     */
    public abstract int getVirtualMousePointerDisplayId();

    /** Gets the current position of the mouse cursor. */
    public abstract PointF getCursorPosition();

    /**
     * Sets the pointer acceleration.
     * See {@code frameworks/native/include/input/VelocityControl.h#VelocityControlParameters}.
     */
    public abstract void setPointerAcceleration(float acceleration, int displayId);

    /**
     * Sets the eligibility of windows on a given display for pointer capture. If a display is
     * marked ineligible, requests to enable pointer capture for windows on that display will be
     * ignored.
     */
    public abstract void setDisplayEligibilityForPointerCapture(int displayId, boolean isEligible);

    /** Sets the visibility of the cursor. */
    public abstract void setPointerIconVisible(boolean visible, int displayId);

    /** Registers the {@link LidSwitchCallback} to begin receiving notifications. */
    public abstract void registerLidSwitchCallback(@NonNull LidSwitchCallback callbacks);

    /**
     * Unregisters a {@link LidSwitchCallback callback} previously registered with
     * {@link #registerLidSwitchCallback(LidSwitchCallback)}.
     */
    public abstract void unregisterLidSwitchCallback(@NonNull LidSwitchCallback callbacks);

    /** Callback interface for notifications relating to the lid switch. */
    public interface LidSwitchCallback {
        /**
         * This callback is invoked when the lid switch changes state. Will be triggered once on
         * registration of the callback with a {@code whenNanos} of 0 and then on every subsequent
         * change in lid switch state.
         *
         * @param whenNanos the time when the change occurred
         * @param lidOpen true if the lid is open
         */
        void notifyLidSwitchChanged(long whenNanos, boolean lidOpen);
    }

    /** Create an {@link InputChannel} that is registered to InputDispatcher. */
    public abstract InputChannel createInputChannel(String inputChannelName);

    /**
     * Pilfer pointers from the input channel with the given token so that ongoing gestures are
     * canceled for all other channels.
     */
    public abstract void pilferPointers(IBinder token);
}
