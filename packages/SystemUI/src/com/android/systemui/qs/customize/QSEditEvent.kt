/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.customize

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger

enum class QSEditEvent(private val _id: Int) : UiEventLogger.UiEventEnum {

    @UiEvent(doc = "Tile removed from current tiles")
    QS_EDIT_REMOVE(210),
    @UiEvent(doc = "Tile added to current tiles")
    QS_EDIT_ADD(211),
    @UiEvent(doc = "Tile moved")
    QS_EDIT_MOVE(212),
    @UiEvent(doc = "QS customizer open")
    QS_EDIT_OPEN(213),
    @UiEvent(doc = "QS customizer closed")
    QS_EDIT_CLOSED(214),
    @UiEvent(doc = "QS tiles reset")
    QS_EDIT_RESET(215);

    override fun getId() = _id
}