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

package com.android.systemui.qs

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger

/** Events of user interactions with modes from the QS Modes dialog. {@see ModesDialogViewModel} */
enum class QSModesEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "User turned manual Do Not Disturb on via modes dialog") QS_MODES_DND_ON(1870),
    @UiEvent(doc = "User turned manual Do Not Disturb off via modes dialog") QS_MODES_DND_OFF(1871),
    @UiEvent(doc = "User opened mode settings from the Do Not Disturb tile in the modes dialog")
    QS_MODES_DND_SETTINGS(1872),
    @UiEvent(doc = "User turned automatic mode on via modes dialog") QS_MODES_MODE_ON(1873),
    @UiEvent(doc = "User turned automatic mode off via modes dialog") QS_MODES_MODE_OFF(1874),
    @UiEvent(doc = "User opened mode settings from a mode tile in the modes dialog")
    QS_MODES_MODE_SETTINGS(1875),
    @UiEvent(doc = "User clicked on Settings from the modes dialog") QS_MODES_SETTINGS(1876),
    @UiEvent(doc = "User clicked on Do Not Disturb tile, opening the time selection dialog")
    QS_MODES_DURATION_DIALOG(1879);

    override fun getId() = _id
}
