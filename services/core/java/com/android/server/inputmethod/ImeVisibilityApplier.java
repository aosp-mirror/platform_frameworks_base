/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethod;

import com.android.internal.inputmethod.SoftInputShowHideReason;

/**
 * Interface for IME visibility operations like show/hide and update Z-ordering relative to the IME
 * targeted window.
 */
interface ImeVisibilityApplier {
    /**
     * Performs showing IME on top of the given window.
     *
     * @param showInputToken a token that represents the requester to show IME
     * @param statsToken     the token tracking the current IME request
     * @param resultReceiver if non-null, this will be called back to the caller when
     *                       it has processed request to tell what it has done
     * @param reason         yhe reason for requesting to show IME
     * @param userId         the target user when performing show IME
     */
    default void performShowIme(IBinder showInputToken, @NonNull ImeTracker.Token statsToken,
            @InputMethod.ShowFlags int showFlags, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason, @UserIdInt int userId) {
    }

    /**
     * Performs hiding IME to the given window
     *
     * @param hideInputToken a token that represents the requester to hide IME
     * @param statsToken     the token tracking the current IME request
     * @param resultReceiver if non-null, this will be called back to the caller when
     *                       it has processed request to tell what it has done
     * @param reason         the reason for requesting to hide IME
     * @param userId         the target user when performing hide IME
     */
    default void performHideIme(IBinder hideInputToken, @NonNull ImeTracker.Token statsToken,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason,
            @UserIdInt int userId) {
    }

    /**
     * Applies the IME visibility from {@link android.inputmethodservice.InputMethodService} with
     * according to the given visibility state.
     *
     * @param windowToken the token of a window for applying the IME visibility
     * @param statsToken  the token tracking the current IME request
     * @param state       the new IME visibility state for the applier to handle
     * @param reason      the reason why the input window is visible or hidden
     * @param userId      the target user when applying the IME visibility state
     */
    default void applyImeVisibility(IBinder windowToken, @NonNull ImeTracker.Token statsToken,
            @ImeVisibilityStateComputer.VisibilityState int state,
            @SoftInputShowHideReason int reason, @UserIdInt int userId) {
    }

    /**
     * Updates the IME Z-ordering relative to the given window.
     *
     * This used to adjust the IME relative layer of the window during
     * {@link InputMethodManagerService} is in switching IME clients.
     *
     * @param windowToken the token of a window to update the Z-ordering relative to the IME
     */
    default void updateImeLayeringByTarget(IBinder windowToken) {
        // TODO: add a method in WindowManagerInternal to call DC#updateImeInputAndControlTarget
        //  here to end up updating IME layering after IMMS#attachNewInputLocked called.
    }

    /**
     * Shows the IME screenshot and attach it to the given IME target window.
     *
     * @param windowToken the token of a window to show the IME screenshot
     * @param displayId   the unique id to identify the display
     * @param userId      the target user when when showing the IME screenshot
     * @return {@code true} if success, {@code false} otherwise
     */
    default boolean showImeScreenshot(@NonNull IBinder windowToken, int displayId,
            @UserIdInt int userId) {
        return false;
    }

    /**
     * Removes the IME screenshot on the given display.
     *
     * @param displayId the target display of showing IME screenshot
     * @param userId    the target user of showing IME screenshot
     * @return {@code true} if success, {@code false} otherwise
     */
    default boolean removeImeScreenshot(int displayId, @UserIdInt int userId) {
        return false;
    }
}
