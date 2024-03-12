/*
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

package com.android.server.wm;

import android.annotation.Nullable;
import android.inputmethodservice.InputMethodService;
import android.view.WindowInsets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.inputmethod.ImeTracker;

/**
 * Generalization of an object that can control insets state.
 */
interface InsetsControlTarget {

    /**
     * Notifies the control target that the insets control has changed.
     *
     * @param displayId the display hosting the window of this target
     */
    default void notifyInsetsControlChanged(int displayId) {
    };

    /**
     * @return {@link WindowState} of this target, if any.
     */
    default WindowState getWindow() {
        return null;
    }

    /**
     * @return {@code true} if any of the {@link InsetsType} is requested visible by this target.
     */
    default boolean isRequestedVisible(@InsetsType int types) {
        return (WindowInsets.Type.defaultVisible() & types) != 0;
    }

    /**
     * @return {@link InsetsType}s which are requested visible by this target.
     */
    default @InsetsType int getRequestedVisibleTypes() {
        return WindowInsets.Type.defaultVisible();
    }

    /**
     * Instructs the control target to show inset sources.
     *
     * @param types to specify which types of insets source window should be shown.
     * @param fromIme {@code true} if the IME request originated from {@link InputMethodService}.
     * @param statsToken the token tracking the current IME request or {@code null} otherwise.
     */
    default void showInsets(@InsetsType int types, boolean fromIme,
            @Nullable ImeTracker.Token statsToken) {
    }

    /**
     * Instructs the control target to hide inset sources.
     *
     * @param types to specify which types of insets source window should be hidden.
     * @param fromIme {@code true} if the IME request originated from {@link InputMethodService}.
     * @param statsToken the token tracking the current IME request or {@code null} otherwise.
     */
    default void hideInsets(@InsetsType int types, boolean fromIme,
            @Nullable ImeTracker.Token statsToken) {
    }

    /**
     * Returns {@code true} if the control target allows the system to show transient windows.
     */
    default boolean canShowTransient() {
        return false;
    }

    /** Returns {@code target.getWindow()}, or null if {@code target} is {@code null}. */
    static WindowState asWindowOrNull(InsetsControlTarget target) {
        return target != null ? target.getWindow() : null;
    }
}
