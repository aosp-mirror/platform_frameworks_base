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
 */

package com.android.systemui.qs.tiles.base.interactor

/** Event that triggers data update */
sealed interface DataUpdateTrigger {
    /**
     * State update is requested in a response to a user action.
     * - [action] is the action that happened
     * - [tileData] is the data state of the tile when that action took place
     */
    class UserInput<T>(val input: QSTileInput<T>) : DataUpdateTrigger

    /** Force update current state. This is passed when the view needs a new state to show */
    data object ForceUpdate : DataUpdateTrigger

    /** The data is requested loaded for the first time */
    data object InitialRequest : DataUpdateTrigger
}
