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

package com.android.systemui.communal.shared.log

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger.UiEventEnum

/** UI events for the Communal Hub. */
enum class CommunalUiEvent(private val id: Int) : UiEventEnum {
    @UiEvent(doc = "Communal Hub is fully shown") COMMUNAL_HUB_SHOWN(1566),
    @UiEvent(doc = "Communal Hub is fully gone") COMMUNAL_HUB_GONE(1577),
    @UiEvent(doc = "Communal Hub times out") COMMUNAL_HUB_TIMEOUT(1578),
    @UiEvent(doc = "The visible content in the Communal Hub is fully loaded and rendered")
    COMMUNAL_HUB_LOADED(1579),
    @UiEvent(doc = "User starts the swipe gesture to enter the Communal Hub")
    COMMUNAL_HUB_SWIPE_TO_ENTER_START(1580),
    @UiEvent(doc = "User finishes the swipe gesture to enter the Communal Hub")
    COMMUNAL_HUB_SWIPE_TO_ENTER_FINISH(1581),
    @UiEvent(doc = "User cancels the swipe gesture to enter the Communal Hub")
    COMMUNAL_HUB_SWIPE_TO_ENTER_CANCEL(1582),
    @UiEvent(doc = "User starts the swipe gesture to exit the Communal Hub")
    COMMUNAL_HUB_SWIPE_TO_EXIT_START(1583),
    @UiEvent(doc = "User finishes the swipe gesture to exit the Communal Hub")
    COMMUNAL_HUB_SWIPE_TO_EXIT_FINISH(1584),
    @UiEvent(doc = "User cancels the swipe gesture to exit the Communal Hub")
    COMMUNAL_HUB_SWIPE_TO_EXIT_CANCEL(1585),
    @UiEvent(doc = "User starts the drag gesture to reorder a widget")
    COMMUNAL_HUB_REORDER_WIDGET_START(1586),
    @UiEvent(doc = "User finishes the drag gesture to reorder a widget")
    COMMUNAL_HUB_REORDER_WIDGET_FINISH(1587),
    @UiEvent(doc = "User cancels the drag gesture to reorder a widget")
    COMMUNAL_HUB_REORDER_WIDGET_CANCEL(1588),
    @UiEvent(doc = "Edit mode for the Communal Hub is shown") COMMUNAL_HUB_EDIT_MODE_SHOWN(1569),
    @UiEvent(doc = "Edit mode for the Communal Hub is gone") COMMUNAL_HUB_EDIT_MODE_GONE(1589),
    @UiEvent(doc = "Widget picker for the Communal Hub is shown")
    COMMUNAL_HUB_WIDGET_PICKER_SHOWN(1590),
    @UiEvent(doc = "Widget picker for the Communal Hub is gone")
    COMMUNAL_HUB_WIDGET_PICKER_GONE(1591),
    @UiEvent(doc = "User performs a swipe up gesture from bottom to enter bouncer")
    COMMUNAL_HUB_SWIPE_UP_TO_BOUNCER(1573),
    @UiEvent(doc = "User performs a swipe down gesture from top to enter shade")
    COMMUNAL_HUB_SWIPE_DOWN_TO_SHADE(1574);

    override fun getId(): Int {
        return id
    }
}
