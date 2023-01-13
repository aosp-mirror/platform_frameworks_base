/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm;


import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;

import android.os.IBinder;
import android.view.WindowManager;

/**
 * A class for {@link com.android.server.inputmethod.InputMethodManagerService} to
 * control IME visibility operations in {@link WindowManagerService}.
 */
public abstract class ImeTargetVisibilityPolicy {

    /**
     * Shows the IME screenshot and attach it to the given IME target window.
     *
     * @param imeTarget The target window to show the IME screenshot.
     * @param displayId A unique id to identify the display.
     * @return {@code true} if success, {@code false} otherwise.
     */
    public abstract boolean showImeScreenShot(IBinder imeTarget, int displayId);

    /**
     * Updates the IME parent for target window.
     *
     * @param imeTarget The target window to update the IME parent.
     * @param displayId A unique id to identify the display.
     * @return {@code true} if success, {@code false} otherwise.
     */
    public abstract boolean updateImeParent(IBinder imeTarget, int displayId);

    /**
     * Called when {@link DisplayContent#computeImeParent()} to check if it's valid to keep
     * computing the ime parent.
     *
     * @return {@code true} to keep computing the ime parent, {@code false} to defer this operation
     */
    public static boolean isReadyToComputeImeParent(WindowState imeLayeringTarget,
            InputTarget imeInputTarget) {
        if (imeLayeringTarget == null) {
            return false;
        }
        // Ensure changing the IME parent when the layering target that may use IME has
        // became to the input target for preventing IME flickers.
        // Note that:
        // 1) For the imeLayeringTarget that may not use IME but requires IME on top
        // of it (e.g. an overlay window with NOT_FOCUSABLE|ALT_FOCUSABLE_IM flags), we allow
        // it to re-parent the IME on top the display to keep the legacy behavior.
        // 2) Even though the starting window won't use IME, the associated activity
        // behind the starting window may request the input. If so, then we should still hold
        // the IME parent change until the activity started the input.
        boolean imeLayeringTargetMayUseIme =
                WindowManager.LayoutParams.mayUseInputMethod(imeLayeringTarget.mAttrs.flags)
                        || imeLayeringTarget.mAttrs.type == TYPE_APPLICATION_STARTING;

        // Do not change parent if the window hasn't requested IME.
        var inputAndLayeringTargetsDisagree = (imeInputTarget == null
                || imeLayeringTarget.mActivityRecord != imeInputTarget.getActivityRecord());
        var inputTargetStale = imeLayeringTargetMayUseIme && inputAndLayeringTargetsDisagree;

        return !inputTargetStale;
    }
}
