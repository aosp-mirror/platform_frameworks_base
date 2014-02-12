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

import android.hardware.display.DisplayViewport;
import android.view.InputEvent;

/**
 * Input manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class InputManagerInternal {
    /**
     * Sets information about the displays as needed by the input system.
     * The input system should copy this information if required.
     */
    public abstract void setDisplayViewports(DisplayViewport defaultViewport,
            DisplayViewport externalTouchViewport);

    public abstract boolean injectInputEvent(InputEvent event, int displayId, int mode);
}
