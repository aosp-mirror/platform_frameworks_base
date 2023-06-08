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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Position
import com.android.systemui.common.shared.model.Text

/** Models the UI state of a keyguard settings popup menu. */
data class KeyguardSettingsPopupMenuViewModel(
    val icon: Icon,
    val text: Text,
    /** Where the menu should be anchored, roughly in screen space. */
    val position: Position,
    /** Callback to invoke when the menu gets clicked by the user. */
    val onClicked: () -> Unit,
    /** Callback to invoke when the menu gets dismissed by the user. */
    val onDismissed: () -> Unit,
)
