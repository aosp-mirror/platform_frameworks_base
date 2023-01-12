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

import android.annotation.Nullable;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.view.inputmethod.ImeTracker;

import com.android.internal.inputmethod.SoftInputShowHideReason;

/**
 * Interface for IME visibility operations like show/hide and update Z-ordering relative to the IME
 * targeted window.
 */
interface ImeVisibilityApplier {
    /**
     * Performs showing IME on top of the given window.
     *
     * @param windowToken    The token of a window that currently has focus.
     * @param statsToken     A token that tracks the progress of an IME request.
     * @param showFlags      Provides additional operating flags to show IME.
     * @param resultReceiver If non-null, this will be called back to the caller when
     *                       it has processed request to tell what it has done.
     * @param reason         The reason for requesting to show IME.
     */
    default void performShowIme(IBinder windowToken, @Nullable ImeTracker.Token statsToken,
            int showFlags, ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {}

    /**
     * Performs hiding IME to the given window
     *
     * @param windowToken    The token of a window that currently has focus.
     * @param statsToken     A token that tracks the progress of an IME request.
     * @param resultReceiver If non-null, this will be called back to the caller when
     *                       it has processed request to tell what it has done.
     * @param reason         The reason for requesting to hide IME.
     */
    default void performHideIme(IBinder windowToken, @Nullable ImeTracker.Token statsToken,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {}

    /**
     * Applies the IME visibility from {@link android.inputmethodservice.InputMethodService} with
     * according to the given visibility state.
     *
     * @param windowToken The token of a window for applying the IME visibility
     * @param statsToken  A token that tracks the progress of an IME request.
     * @param state       The new IME visibility state for the applier to handle
     */
    default void applyImeVisibility(IBinder windowToken, @Nullable ImeTracker.Token statsToken,
            @ImeVisibilityStateComputer.VisibilityState int state) {}

    /**
     * Updates the IME Z-ordering relative to the given window.
     *
     * This used to adjust the IME relative layer of the window during
     * {@link InputMethodManagerService} is in switching IME clients.
     *
     * @param windowToken The token of a window to update the Z-ordering relative to the IME.
     */
    default void updateImeLayeringByTarget(IBinder windowToken) {
        // TODO: add a method in WindowManagerInternal to call DC#updateImeInputAndControlTarget
        //  here to end up updating IME layering after IMMS#attachNewInputLocked called.
    }
}
