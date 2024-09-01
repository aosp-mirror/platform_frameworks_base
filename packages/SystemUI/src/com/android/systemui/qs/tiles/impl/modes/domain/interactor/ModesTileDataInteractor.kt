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

package com.android.systemui.qs.tiles.impl.modes.domain.interactor

import android.app.Flags
import android.content.Context
import android.os.UserHandle
import com.android.app.tracing.coroutines.flow.map
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

class ModesTileDataInteractor
@Inject
constructor(
    val context: Context,
    val zenModeInteractor: ZenModeInteractor,
    @Background val bgDispatcher: CoroutineDispatcher,
) : QSTileDataInteractor<ModesTileModel> {

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<ModesTileModel> = tileData()

    /**
     * An adapted version of the base class' [tileData] method for use in an old-style tile.
     *
     * TODO(b/299909989): Remove after the transition.
     */
    fun tileData() =
        zenModeInteractor.activeModes
            .map { activeModes ->
                val modesIconResId = com.android.internal.R.drawable.ic_zen_priority_modes

                if (usesModeIcons()) {
                    val mainModeDrawable = activeModes.mainMode?.icon?.drawable
                    val iconResId = if (mainModeDrawable == null) modesIconResId else null

                    ModesTileModel(
                        isActivated = activeModes.isAnyActive(),
                        icon = (mainModeDrawable ?: context.getDrawable(modesIconResId)!!).asIcon(),
                        iconResId = iconResId,
                        activeModes = activeModes.modeNames
                    )
                } else {
                    ModesTileModel(
                        isActivated = activeModes.isAnyActive(),
                        icon = context.getDrawable(modesIconResId)!!.asIcon(),
                        iconResId = modesIconResId,
                        activeModes = activeModes.modeNames
                    )
                }
            }
            .flowOn(bgDispatcher)
            .distinctUntilChanged()

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(Flags.modesUi())

    private fun usesModeIcons() = Flags.modesApi() && Flags.modesUi() && Flags.modesUiIcons()
}
