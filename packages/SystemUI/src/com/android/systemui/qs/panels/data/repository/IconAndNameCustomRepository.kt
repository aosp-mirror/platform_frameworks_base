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

package com.android.systemui.qs.panels.data.repository

import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.panels.shared.model.EditTileData
import com.android.systemui.qs.pipeline.data.repository.InstalledTilesComponentRepository
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

@SysUISingleton
class IconAndNameCustomRepository
@Inject
constructor(
    private val installedTilesComponentRepository: InstalledTilesComponentRepository,
    private val userTracker: UserTracker,
    @Background private val backgroundContext: CoroutineContext,
) {
    /**
     * Returns a list of the icon/labels for all available (installed and enabled) tile services.
     *
     * No order is guaranteed.
     */
    suspend fun getCustomTileData(): List<EditTileData> {
        return withContext(backgroundContext) {
            val installedTiles =
                installedTilesComponentRepository.getInstalledTilesServiceInfos(userTracker.userId)
            val packageManager = userTracker.userContext.packageManager
            installedTiles
                .map {
                    val tileSpec = TileSpec.create(it.componentName)
                    val label = it.loadLabel(packageManager)
                    val icon = it.loadIcon(packageManager)
                    val appName = it.applicationInfo.loadLabel(packageManager)
                    if (icon != null) {
                        EditTileData(
                            tileSpec,
                            Icon.Loaded(icon, ContentDescription.Loaded(label.toString())),
                            Text.Loaded(label.toString()),
                            Text.Loaded(appName.toString()),
                            TileCategory.PROVIDED_BY_APP,
                        )
                    } else {
                        null
                    }
                }
                .filterNotNull()
        }
    }
}
