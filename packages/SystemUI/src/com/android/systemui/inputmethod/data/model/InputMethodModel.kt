/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.inputmethod.data.model

/**
 * Models an input method editor (IME).
 *
 * @see android.view.inputmethod.InputMethodInfo
 */
data class InputMethodModel(
    /** A unique ID for the user associated with this input method. */
    val userId: Int,
    /** A unique ID for this input method. */
    val imeId: String,
    /** The subtypes of this IME (may be empty). */
    val subtypes: List<Subtype>,
) {
    /**
     * A Subtype can describe locale (e.g. en_US, fr_FR...) and mode (e.g. voice, keyboard), and is
     * used for IME switch and settings.
     *
     * @see android.view.inputmethod.InputMethodSubtype
     */
    data class Subtype(
        /** A unique ID for this IME subtype. */
        val subtypeId: Int,
        /**
         * Whether this subtype is auxiliary. An auxiliary subtype will not be shown in the list of
         * enabled IMEs for choosing the current IME in Settings, but it will be shown in the list
         * of IMEs in the IME switcher to allow the user to tentatively switch to this subtype while
         * an IME is shown.
         *
         * The intent of this flag is to allow for IMEs that are invoked in a one-shot way as
         * auxiliary input mode, and return to the previous IME once it is finished (e.g. voice
         * input).
         */
        val isAuxiliary: Boolean,
    )
}
