/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.bouncer.shared.model

/** Models the action button on the bouncer. */
data class BouncerActionButtonModel(
    /** The text to be shown on the button. */
    val label: String,

    /** The action to perform when the user clicks on the button. */
    val onClick: () -> Unit,

    /**
     * The action to perform when the user long-clicks on the button. When not provided, long-clicks
     * will be treated as regular clicks.
     */
    val onLongClick: (() -> Unit)? = null,
)
