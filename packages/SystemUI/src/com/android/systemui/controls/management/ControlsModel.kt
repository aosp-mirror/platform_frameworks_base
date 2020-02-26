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

package com.android.systemui.controls.management

import com.android.systemui.controls.ControlStatus
import com.android.systemui.controls.controller.ControlInfo

/**
 * Model for using with [ControlAdapter].
 *
 * Implementations of this interface provide different views of the controls to show.
 */
interface ControlsModel {

    /**
     * List of favorites (builders) in order.
     *
     * This should be obtained prior to storing the favorites using
     * [ControlsController.replaceFavoritesForComponent].
     */
    val favorites: List<ControlInfo.Builder>

    /**
     * List of all the elements to display by the corresponding [RecyclerView].
     */
    val elements: List<ElementWrapper>

    /**
     * Change the favorite status of a particular control.
     */
    fun changeFavoriteStatus(controlId: String, favorite: Boolean) {}

    /**
     * Move an item (in elements) from one position to another.
     */
    fun onMoveItem(from: Int, to: Int) {}
}

/**
 * Wrapper classes for the different types of elements shown in the [RecyclerView]s in
 * [ControlAdapter].
 */
sealed class ElementWrapper
data class ZoneNameWrapper(val zoneName: CharSequence) : ElementWrapper()
data class ControlWrapper(val controlStatus: ControlStatus) : ElementWrapper()