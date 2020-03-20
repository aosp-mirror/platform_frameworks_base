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
import android.hardware.display.DisplayViewport;
import android.os.IBinder;
import android.view.InputEvent;

import java.util.List;

/**
 * Input manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class InputManagerInternal {
    /**
     * Inject an input event.
     *
     * @param event The InputEvent to inject
     * @param mode Synchronous or asynchronous mode
     * @return True if injection has succeeded
     */
    public abstract boolean injectInputEvent(InputEvent event, int mode);

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
}
