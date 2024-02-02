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

package com.android.systemui.communal.widgets

import com.android.systemui.CoreStartable
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.kotlin.BooleanFlowOperators.or
import com.android.systemui.util.kotlin.pairwise
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

@SysUISingleton
class CommunalAppWidgetHostStartable
@Inject
constructor(
    private val appWidgetHost: CommunalAppWidgetHost,
    private val communalInteractor: CommunalInteractor,
    @Background private val bgScope: CoroutineScope,
    @Main private val uiDispatcher: CoroutineDispatcher
) : CoreStartable {

    override fun start() {
        or(communalInteractor.isCommunalAvailable, communalInteractor.editModeOpen)
            // Only trigger updates on state changes, ignoring the initial false value.
            .pairwise(false)
            .filter { (previous, new) -> previous != new }
            .onEach { (_, shouldListen) -> updateAppWidgetHostActive(shouldListen) }
            .launchIn(bgScope)

        appWidgetHost.appWidgetIdToRemove
            .onEach { appWidgetId -> communalInteractor.deleteWidgetFromDb(appWidgetId) }
            .launchIn(bgScope)
    }

    private suspend fun updateAppWidgetHostActive(active: Boolean) =
        // Always ensure this is called on the main/ui thread.
        withContext(uiDispatcher) {
            if (active) {
                appWidgetHost.startListening()
            } else {
                appWidgetHost.stopListening()
            }
        }
}
