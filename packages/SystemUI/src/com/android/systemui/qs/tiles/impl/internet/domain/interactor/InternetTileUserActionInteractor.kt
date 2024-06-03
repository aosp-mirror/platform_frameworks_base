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

package com.android.systemui.qs.tiles.impl.internet.domain.interactor

import android.content.Intent
import android.provider.Settings
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.qs.tiles.impl.internet.domain.model.InternetTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.statusbar.connectivity.AccessPointController
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/** Handles internet tile clicks. */
class InternetTileUserActionInteractor
@Inject
constructor(
    @Main private val mainContext: CoroutineContext,
    private val internetDialogManager: InternetDialogManager,
    private val accessPointController: AccessPointController,
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
) : QSTileUserActionInteractor<InternetTileModel> {

    override suspend fun handleInput(input: QSTileInput<InternetTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    withContext(mainContext) {
                        internetDialogManager.create(
                            aboveStatusBar = true,
                            accessPointController.canConfigMobileData(),
                            accessPointController.canConfigWifi(),
                            action.expandable,
                        )
                    }
                }
                is QSTileUserAction.LongClick -> {
                    qsTileIntentUserActionHandler.handle(
                        action.expandable,
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                    )
                }
            }
        }
}
