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

import android.annotation.Nullable;
import android.hardware.display.DisplayViewport;
import android.view.InputEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

/**
 * Input manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class InputManagerInternal {
    public abstract boolean injectInputEvent(InputEvent event, int displayId, int mode);

    /**
     * Called by the display manager to set information about the displays as needed
     * by the input system.  The input system must copy this information to retain it.
     */
    public abstract void setDisplayViewports(DisplayViewport defaultViewport,
            DisplayViewport externalTouchViewport);

    /**
     * Called by the power manager to tell the input manager whether it should start
     * watching for wake events.
     */
    public abstract void setInteractive(boolean interactive);

    /**
     * Notifies that InputMethodManagerService switched the current input method subtype.
     *
     * @param userId user id that indicates who is using the specified input method and subtype.
     * @param inputMethodInfo {@code null} when no input method is selected.
     * @param subtype {@code null} when {@code inputMethodInfo} does has no subtype.
     */
    public abstract void onInputMethodSubtypeChanged(int userId,
            @Nullable InputMethodInfo inputMethodInfo, @Nullable InputMethodSubtype subtype);

    /**
     * Toggles Caps Lock state for input device with specific id.
     *
     * @param deviceId The id of input device.
     */
    public abstract void toggleCapsLock(int deviceId);
}
