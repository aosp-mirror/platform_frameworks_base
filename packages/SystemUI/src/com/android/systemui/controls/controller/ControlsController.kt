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

package com.android.systemui.controls.controller

import android.content.ComponentName
import android.service.controls.Control
import android.service.controls.actions.ControlAction
import com.android.systemui.controls.ControlStatus
import com.android.systemui.controls.UserAwareController

interface ControlsController : UserAwareController {
    val available: Boolean

    fun getFavoriteControls(): List<ControlInfo>
    fun loadForComponent(
        componentName: ComponentName,
        callback: (List<ControlStatus>, List<String>) -> Unit
    )

    fun subscribeToFavorites()
    fun changeFavoriteStatus(controlInfo: ControlInfo, state: Boolean)
    fun replaceFavoritesForComponent(componentName: ComponentName, favorites: List<ControlInfo>)

    fun getFavoritesForComponent(componentName: ComponentName): List<ControlInfo>
    fun countFavoritesForComponent(componentName: ComponentName): Int

    fun unsubscribe()
    fun action(controlInfo: ControlInfo, action: ControlAction)
    fun refreshStatus(componentName: ComponentName, control: Control)
    fun onActionResponse(
        componentName: ComponentName,
        controlId: String,
        @ControlAction.ResponseResult response: Int
    )
    fun clearFavorites()
}
