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

package com.android.systemui.shade.shared.model

/** Enumerates all known modes of operation of the shade. */
sealed interface ShadeMode {

    /**
     * The single or "accordion" shade where the QS and notification parts are in two vertically
     * stacked panels and the user can swipe up and down to expand or collapse between the two
     * parts.
     */
    data object Single : ShadeMode

    /**
     * The split shade where, on large screens and unfolded foldables, the QS and notification parts
     * are placed side-by-side and expand/collapse as a single panel.
     */
    data object Split : ShadeMode

    /**
     * The dual shade where the QS and notification parts each have their own independently
     * expandable/collapsible panel on either side of the large screen / unfolded device or sharing
     * a space on a small screen or folded device.
     */
    data object Dual : ShadeMode
}
