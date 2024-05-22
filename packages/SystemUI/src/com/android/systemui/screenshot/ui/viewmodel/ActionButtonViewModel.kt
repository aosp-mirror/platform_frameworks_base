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

package com.android.systemui.screenshot.ui.viewmodel

data class ActionButtonViewModel(
    val appearance: ActionButtonAppearance,
    val id: Int,
    val visible: Boolean,
    val showDuringEntrance: Boolean,
    val onClicked: (() -> Unit)?,
) {
    companion object {
        private var nextId = 0

        private fun getId() = nextId.also { nextId += 1 }

        fun withNextId(
            appearance: ActionButtonAppearance,
            showDuringEntrance: Boolean,
            onClicked: (() -> Unit)?
        ): ActionButtonViewModel =
            ActionButtonViewModel(appearance, getId(), true, showDuringEntrance, onClicked)

        fun withNextId(
            appearance: ActionButtonAppearance,
            onClicked: (() -> Unit)?
        ): ActionButtonViewModel = withNextId(appearance, showDuringEntrance = true, onClicked)
    }
}
