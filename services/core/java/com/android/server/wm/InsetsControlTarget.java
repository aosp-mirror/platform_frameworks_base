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
import android.view.WindowInsets.Type.InsetType;

/**
 * Generalization of an object that can control insets state.
 */
interface InsetsControlTarget {
    void notifyInsetsControlChanged();

    /**
     * Instructs the control target to show inset sources.
     *
     * @param types to specify which types of insets source window should be shown.
     * @param fromIme {@code true} if IME show request originated from {@link InputMethodService}.
     */
    default void showInsets(@InsetType int types, boolean fromIme) {
    }
}
