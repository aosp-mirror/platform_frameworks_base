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

import android.annotation.NonNull;
import android.annotation.Nullable;
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
    public abstract boolean showImeScreenshot(@NonNull IBinder imeTarget, int displayId);

    /**
     * Removes the IME screenshot on the given display.
     *
     * @param displayId The target display of showing IME screenshot.
     * @return {@code true} if success, {@code false} otherwise.
     */
    public abstract boolean removeImeScreenshot(int displayId);

    /**
     * Called when {@link DisplayContent#computeImeParent()} to check if it's valid to keep
     * computing the ime parent.
     *
     * @param imeLayeringTarget The window which IME target to layer on top of it.
     * @param imeInputTarget The window which start the input connection, receive input from IME.
     * @return {@code true} to keep computing the ime parent, {@code false} to defer this operation
     */
    public static boolean canComputeImeParent(@Nullable WindowState imeLayeringTarget,
            @Nullable InputTarget imeInputTarget) {
        if (imeLayeringTarget == null) {
            return false;
        }
        if (shouldComputeImeParentForEmbeddedActivity(imeLayeringTarget, imeInputTarget)) {
            return true;
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


    /**
     * Called from {@link DisplayContent#computeImeParent()} to check the given IME targets if the
     * IME surface parent should be updated in ActivityEmbeddings.
     *
     * As the IME layering target is calculated according to the window hierarchy by
     * {@link DisplayContent#computeImeTarget}, the layering target and input target may be
     * different when the window hasn't started input connection, WindowManagerService hasn't yet
     * received the input target which reported from InputMethodManagerService. To make the IME
     * surface will be shown on the best fit IME layering target, we basically won't update IME
     * parent until both IME input and layering target updated for better IME transition.
     *
     * However, in activity embedding, tapping a window won't update it to the top window so the
     * calculated IME layering target may higher than input target. Update IME parent for this case.
     *
     * @return {@code true} means the layer of IME layering target is higher than the input target
     * and {@link DisplayContent#computeImeParent()} should keep progressing to update the IME
     * surface parent on the display in case the IME surface left behind.
     */
    private static boolean shouldComputeImeParentForEmbeddedActivity(
            @Nullable WindowState imeLayeringTarget, @Nullable InputTarget imeInputTarget) {
        if (imeInputTarget == null || imeLayeringTarget == null) {
            return false;
        }
        final WindowState inputTargetWindow = imeInputTarget.getWindowState();
        if (inputTargetWindow == null || !imeLayeringTarget.isAttached()
                || !inputTargetWindow.isAttached()) {
            return false;
        }

        final ActivityRecord inputTargetRecord = imeInputTarget.getActivityRecord();
        final ActivityRecord layeringTargetRecord = imeLayeringTarget.getActivityRecord();
        if (inputTargetRecord == null || layeringTargetRecord == null
                || inputTargetRecord == layeringTargetRecord
                || (inputTargetRecord.getTask() != layeringTargetRecord.getTask())
                || !inputTargetRecord.isEmbedded() || !layeringTargetRecord.isEmbedded()) {
            // Check whether the input target and layering target are embedded in the same Task.
            return false;
        }
        return imeLayeringTarget.compareTo(inputTargetWindow) > 0;
    }
}
