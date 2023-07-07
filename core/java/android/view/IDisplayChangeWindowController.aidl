/**
 * Copyright (C) 2019 The Android Open Source Project
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

import android.view.IDisplayChangeWindowCallback;
import android.window.DisplayAreaInfo;

/**
 * Singular controller of a "remote" display change. When a display rotation or change is started,
 * WM freezes the screen. It will then call into this controller and wait for a response via the
 * callback.
 *
 * This needs to provide configuration changes because those changes need to be applied in sync
 * with the actual display rotation to prevent relayouts with mismatched information.
 *
 * The flow is like this:
 *  1. DisplayContent/Rotation freezes the screen
 *  2. This controller is notified of a rotation and provided a callback.
 *  3. This controller is responsible for collecting a set of configuration changes to go along with
 *     the rotation.
 *  4. The callback is fired which tells DisplayContent/Rotation to apply the provided configuration
 *     changes and continue the rotation.
 *
 * @hide
 */
oneway interface IDisplayChangeWindowController {

    /**
     * Called when WM needs to know how to update tasks in response to a display change.
     * If this isn't called, a timeout will continue the change in WM.
     *
     * @param fromRotation the old rotation
     * @param newRotation the new rotation
     * @param newDisplayAreaInfo the new display area info after the change
     * @param callback A callback to be called when this has calculated updated configs.
     */
    void onDisplayChange(int displayId, int fromRotation, int toRotation,
            in DisplayAreaInfo newDisplayAreaInfo, in IDisplayChangeWindowCallback callback);

}
