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

import android.content.Context
import android.os.UserHandle
import com.android.app.tracing.coroutines.flow.map
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.modes.shared.ModesUi
import com.android.systemui.modes.shared.ModesUiIcons
import com.android.systemui.qs.tiles.ModesTile
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.statusbar.policy.domain.model.ActiveZenModes
import com.android.systemui.statusbar.policy.domain.model.ZenModeInfo
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
        triggers: Flow<DataUpdateTrigger>,
    ): Flow<ModesTileModel> = tileData()

    /**
     * An adapted version of the base class' [tileData] method for use in an old-style tile.
     *
     * TODO(b/299909989): Remove after the transition.
     */
    fun tileData() =
        zenModeInteractor.activeModes
            .map { activeModes -> buildTileData(activeModes) }
            .flowOn(bgDispatcher)
            .distinctUntilChanged()

    suspend fun getCurrentTileModel() = buildTileData(zenModeInteractor.getActiveModes())

    private fun buildTileData(activeModes: ActiveZenModes): ModesTileModel {
        if (ModesUiIcons.isEnabled) {
            val tileIcon = getTileIcon(activeModes.mainMode)
            return ModesTileModel(
                isActivated = activeModes.isAnyActive(),
                icon = tileIcon.icon,
                iconResId = tileIcon.resId,
                activeModes = activeModes.modeNames,
            )
        } else {
            return ModesTileModel(
                isActivated = activeModes.isAnyActive(),
                icon = context.getDrawable(ModesTile.ICON_RES_ID)!!.asIcon(),
                iconResId = ModesTile.ICON_RES_ID,
                activeModes = activeModes.modeNames,
            )
        }
    }

    private data class TileIcon(val icon: Icon.Loaded, val resId: Int?)

    private fun getTileIcon(activeMode: ZenModeInfo?): TileIcon {
        return if (activeMode != null) {
            // ZenIconKey.resPackage is null if its resId is a system icon.
            if (activeMode.icon.key.resPackage == null) {
                TileIcon(activeMode.icon.drawable.asIcon(), activeMode.icon.key.resId)
            } else {
                TileIcon(activeMode.icon.drawable.asIcon(), null)
            }
        } else {
            TileIcon(context.getDrawable(ModesTile.ICON_RES_ID)!!.asIcon(), ModesTile.ICON_RES_ID)
        }
    }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(ModesUi.isEnabled)
}
