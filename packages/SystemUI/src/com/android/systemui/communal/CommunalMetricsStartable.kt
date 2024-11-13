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

package com.android.systemui.communal

import android.app.StatsManager
import android.util.StatsEvent
import com.android.systemui.CoreStartable
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.shared.log.CommunalMetricsLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.shared.system.SysUiStatsLog
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@SysUISingleton
class CommunalMetricsStartable
@Inject
constructor(
    @Background private val bgExecutor: Executor,
    private val communalSettingsInteractor: CommunalSettingsInteractor,
    private val communalInteractor: CommunalInteractor,
    private val statsManager: StatsManager,
    private val metricsLogger: CommunalMetricsLogger,
) : CoreStartable, StatsManager.StatsPullAtomCallback {
    override fun start() {
        if (!communalSettingsInteractor.isCommunalFlagEnabled()) {
            return
        }

        statsManager.setPullAtomCallback(
            /* atomTag = */ SysUiStatsLog.COMMUNAL_HUB_SNAPSHOT,
            /* metadata = */ null,
            /* executor = */ bgExecutor,
            /* callback = */ this,
        )
    }

    override fun onPullAtom(atomTag: Int, statsEvents: MutableList<StatsEvent>): Int {
        if (atomTag != SysUiStatsLog.COMMUNAL_HUB_SNAPSHOT) {
            return StatsManager.PULL_SKIP
        }

        metricsLogger.logWidgetsSnapshot(
            statsEvents,
            componentNames =
                runBlocking {
                    communalInteractor.widgetContent.first().map {
                        it.componentName.flattenToString()
                    }
                },
        )
        return StatsManager.PULL_SUCCESS
    }
}
