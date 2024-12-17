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
import android.graphics.drawable.Drawable
import android.os.UserHandle
import com.android.app.tracing.coroutines.flow.flowName
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.modes.shared.ModesUi
import com.android.systemui.modes.shared.ModesUiIcons
import com.android.systemui.qs.tiles.ModesTile
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.statusbar.policy.domain.model.ActiveZenModes
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class ModesTileDataInteractor
@Inject
constructor(
    @ShadeDisplayAware val context: Context,
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
            .flowName("tileData")
            .flowOn(bgDispatcher)
            .distinctUntilChanged()

    suspend fun getCurrentTileModel() = buildTileData(zenModeInteractor.getActiveModes())

    private fun buildTileData(activeModes: ActiveZenModes): ModesTileModel {
        val drawable: Drawable
        val iconRes: Int?
        val activeMode = activeModes.mainMode

        if (ModesUiIcons.isEnabled && activeMode != null) {
            // ZenIconKey.resPackage is null if its resId is a system icon.
            iconRes =
                if (activeMode.icon.key.resPackage == null) {
                    activeMode.icon.key.resId
                } else {
                    null
                }
            drawable = activeMode.icon.drawable
        } else {
            iconRes = ModesTile.ICON_RES_ID
            drawable = context.getDrawable(iconRes)!!
        }

        return ModesTileModel(
            isActivated = activeModes.isAnyActive(),
            icon = Icon.Loaded(drawable, null, iconRes),
            activeModes = activeModes.modeNames,
        )
    }

    override fun availability(user: UserHandle): Flow<Boolean> = flowOf(ModesUi.isEnabled)
}
