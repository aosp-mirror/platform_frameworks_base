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

package com.android.systemui.qs

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.UiEventLoggerImpl
import com.android.internal.logging.testing.UiEventLoggerFake

object QSEvents {

    var qsUiEventsLogger: UiEventLogger = UiEventLoggerImpl()
        private set

    fun setLoggerForTesting(): UiEventLoggerFake {
        return UiEventLoggerFake().also {
            qsUiEventsLogger = it
        }
    }

    fun resetLogger() {
        qsUiEventsLogger = UiEventLoggerImpl()
    }
}

enum class QSEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "Tile clicked. It has an instance id and a spec (or packageName)")
    QS_ACTION_CLICK(387),

    @UiEvent(doc = "Tile secondary button clicked. " +
            "It has an instance id and a spec (or packageName)")
    QS_ACTION_SECONDARY_CLICK(388),

    @UiEvent(doc = "Tile long clicked. It has an instance id and a spec (or packageName)")
    QS_ACTION_LONG_PRESS(389),

    @UiEvent(doc = "Quick Settings panel expanded")
    QS_PANEL_EXPANDED(390),

    @UiEvent(doc = "Quick Settings panel collapsed")
    QS_PANEL_COLLAPSED(391),

    @UiEvent(doc = "Tile visible in Quick Settings panel. The tile may be in a different page. " +
            "It has an instance id and a spec (or packageName)")
    QS_TILE_VISIBLE(392),

    @UiEvent(doc = "Quick Quick Settings panel expanded")
    QQS_PANEL_EXPANDED(393),

    @UiEvent(doc = "Quick Quick Settings panel collapsed")
    QQS_PANEL_COLLAPSED(394),

    @UiEvent(doc = "Tile visible in Quick Quick Settings panel. " +
            "It has an instance id and a spec (or packageName)")
    QQS_TILE_VISIBLE(395);

    override fun getId() = _id
}

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

enum class QSDndEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "TODO(beverlyt)")
    QS_DND_CONDITION_SELECT(420),

    @UiEvent(doc = "TODO(beverlyt)")
    QS_DND_TIME_UP(422),

    @UiEvent(doc = "TODO(beverlyt)")
    QS_DND_TIME_DOWN(423);

    override fun getId() = _id
}

enum class QSUserSwitcherEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "The current user has been switched in the detail panel")
    QS_USER_SWITCH(424),

    @UiEvent(doc = "User switcher QS detail panel open")
    QS_USER_DETAIL_OPEN(425),

    @UiEvent(doc = "User switcher QS detail panel closed")
    QS_USER_DETAIL_CLOSE(426),

    @UiEvent(doc = "User switcher QS detail panel more settings pressed")
    QS_USER_MORE_SETTINGS(427),

    @UiEvent(doc = "The user has added a guest in the detail panel")
    QS_USER_GUEST_ADD(754),

    @UiEvent(doc = "The user selected 'Start over' after switching to the existing Guest user")
    QS_USER_GUEST_WIPE(755),

    @UiEvent(doc = "The user selected 'Yes, continue' after switching to the existing Guest user")
    QS_USER_GUEST_CONTINUE(756),

    @UiEvent(doc = "The user has pressed 'Remove guest' in the detail panel")
    QS_USER_GUEST_REMOVE(757);

    override fun getId() = _id
}