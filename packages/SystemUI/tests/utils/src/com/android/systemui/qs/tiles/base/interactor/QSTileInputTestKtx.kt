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

import android.os.UserHandle
import com.android.systemui.animation.Expandable
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction

object QSTileInputTestKtx {

    fun <T> click(
        data: T,
        user: UserHandle = UserHandle.CURRENT,
        expandable: Expandable? = null,
    ): QSTileInput<T> = QSTileInput(user, QSTileUserAction.Click(expandable), data)

    fun <T> toggleClick(
        data: T,
        user: UserHandle = UserHandle.CURRENT,
        expandable: Expandable? = null,
    ): QSTileInput<T> = QSTileInput(user, QSTileUserAction.ToggleClick(expandable), data)

    fun <T> longClick(
        data: T,
        user: UserHandle = UserHandle.CURRENT,
        expandable: Expandable? = null,
    ): QSTileInput<T> = QSTileInput(user, QSTileUserAction.LongClick(expandable), data)
}
