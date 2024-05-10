/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.QuickAccessWalletTile
import com.android.systemui.statusbar.policy.WalletController
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [AutoAddable] for [QuickAccessWalletTile.TILE_SPEC].
 *
 * It will always try to add the tile if [WalletController.getWalletPosition] is non-null.
 */
@SysUISingleton
class WalletAutoAddable
@Inject
constructor(
    private val walletController: WalletController,
) : AutoAddable {

    private val spec = TileSpec.create(QuickAccessWalletTile.TILE_SPEC)

    override fun autoAddSignal(userId: Int): Flow<AutoAddSignal> {
        return flow {
            val position = walletController.getWalletPosition()
            if (position != null) {
                emit(AutoAddSignal.Add(spec, position))
            }
        }
    }

    override val autoAddTracking: AutoAddTracking
        get() = AutoAddTracking.IfNotAdded(spec)

    override val description = "WalletAutoAddable ($autoAddTracking)"
}
