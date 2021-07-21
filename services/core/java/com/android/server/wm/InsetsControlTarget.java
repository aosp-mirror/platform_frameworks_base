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

import android.inputmethodservice.InputMethodService;
import android.view.InsetsState;
import android.view.InsetsState.InternalInsetsType;
import android.view.WindowInsets.Type.InsetsType;

/**
 * Generalization of an object that can control insets state.
 */
interface InsetsControlTarget {

    /**
     * Notifies the control target that the insets control has changed.
     */
    default void notifyInsetsControlChanged() {
    };

    /**
     * @return {@link WindowState} of this target, if any.
     */
    default WindowState getWindow() {
        return null;
    }

    /**
     * @return The requested visibility of this target.
     */
    default boolean getRequestedVisibility(@InternalInsetsType int type) {
        return InsetsState.getDefaultVisibility(type);
    }

    /**
     * Instructs the control target to show inset sources.
     *
     * @param types to specify which types of insets source window should be shown.
     * @param fromIme {@code true} if IME show request originated from {@link InputMethodService}.
     */
    default void showInsets(@InsetsType int types, boolean fromIme) {
    }

    /**
     * Instructs the control target to hide inset sources.
     *
     * @param types to specify which types of insets source window should be hidden.
     * @param fromIme {@code true} if IME hide request originated from {@link InputMethodService}.
     */
    default void hideInsets(@InsetsType int types, boolean fromIme) {
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
