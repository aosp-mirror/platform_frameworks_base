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

package com.android.systemui.qs.tiles.base.analytics

import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.QSEvent
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import javax.inject.Inject

/** Tracks QS tiles analytic events to [UiEventLogger]. */
@SysUISingleton
class QSTileAnalytics
@Inject
constructor(
    private val uiEventLogger: UiEventLogger,
) {

    fun trackUserAction(config: QSTileConfig, action: QSTileUserAction) {
        logAction(config, action)
    }

    private fun logAction(config: QSTileConfig, action: QSTileUserAction) {
        uiEventLogger.logWithInstanceId(
            action.getQSEvent(),
            0,
            config.metricsSpec,
            config.instanceId,
        )
    }

    private fun QSTileUserAction.getQSEvent(): QSEvent =
        when (this) {
            is QSTileUserAction.Click -> QSEvent.QS_ACTION_CLICK
            is QSTileUserAction.ToggleClick -> QSEvent.QS_ACTION_SECONDARY_CLICK
            is QSTileUserAction.LongClick -> QSEvent.QS_ACTION_LONG_PRESS
        }
}
