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

import android.content.ComponentName;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.inputmethod.ImeTracker;

/**
 * Singular controller of insets to use when there isn't another obvious controller available.
 * Specifically, this will take over insets control in multi-window.
 * @hide
 */
oneway interface IDisplayWindowInsetsController {

    /**
     * Called when top focused window changes to determine whether or not to take over insets
     * control. Won't be called if config_remoteInsetsControllerControlsSystemBars is false.
     * @param component: Passes the top application component in the focused window.
     * @param requestedVisibleTypes The insets types requested visible by the focused window.
     */
    void topFocusedWindowChanged(in ComponentName component, int requestedVisibleTypes);

    /**
     * @see IWindow#insetsChanged
     */
    void insetsChanged(in InsetsState insetsState);

    /**
     * @see IWindow#insetsControlChanged
     */
    void insetsControlChanged(in InsetsState insetsState, in InsetsSourceControl[] activeControls);

    /**
     * @see IWindow#showInsets
     */
    void showInsets(int types, boolean fromIme, in @nullable ImeTracker.Token statsToken);

    /**
     * @see IWindow#hideInsets
     */
    void hideInsets(int types, boolean fromIme, in @nullable ImeTracker.Token statsToken);

    /**
     * Reports the requested IME visibility of the IME input target to
     * the IDisplayWindowInsetsController
     */
    void setImeInputTargetRequestedVisibility(boolean visible);
}
