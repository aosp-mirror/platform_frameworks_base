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

package com.android.systemui.communal.shared.log

import android.util.StatsEvent
import com.android.systemui.communal.dagger.CommunalModule.Companion.LOGGABLE_PREFIXES
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.system.SysUiStatsLog
import javax.inject.Inject
import javax.inject.Named

@SysUISingleton
class CommunalMetricsLogger
@Inject
constructor(
    @Named(LOGGABLE_PREFIXES) private val loggablePrefixes: List<String>,
    private val statsLogProxy: StatsLogProxy,
) {
    /** Logs an add widget event for metrics. No-op if widget is not loggable. */
    fun logAddWidget(componentName: String, rank: Int?) {
        if (!componentName.isLoggable()) {
            return
        }

        statsLogProxy.writeCommunalHubWidgetEventReported(
            SysUiStatsLog.COMMUNAL_HUB_WIDGET_EVENT_REPORTED__ACTION__ADD,
            componentName,
            rank ?: -1,
        )
    }

    /** Logs a remove widget event for metrics. No-op if widget is not loggable. */
    fun logRemoveWidget(componentName: String, rank: Int) {
        if (!componentName.isLoggable()) {
            return
        }

        statsLogProxy.writeCommunalHubWidgetEventReported(
            SysUiStatsLog.COMMUNAL_HUB_WIDGET_EVENT_REPORTED__ACTION__REMOVE,
            componentName,
            rank,
        )
    }

    /** Logs a tap widget event for metrics. No-op if widget is not loggable. */
    fun logTapWidget(componentName: String, rank: Int) {
        if (!componentName.isLoggable()) {
            return
        }

        statsLogProxy.writeCommunalHubWidgetEventReported(
            SysUiStatsLog.COMMUNAL_HUB_WIDGET_EVENT_REPORTED__ACTION__TAP,
            componentName,
            rank,
        )
    }

    /** Logs loggable widgets and the total widget count as a [StatsEvent]. */
    fun logWidgetsSnapshot(
        statsEvents: MutableList<StatsEvent>,
        componentNames: List<String>,
    ) {
        val loggableComponentNames = componentNames.filter { it.isLoggable() }.toTypedArray()
        statsEvents.add(
            statsLogProxy.buildCommunalHubSnapshotStatsEvent(
                componentNames = loggableComponentNames,
                widgetCount = componentNames.size,
            )
        )
    }

    /** Whether the component name matches any of the loggable prefixes. */
    private fun String.isLoggable(): Boolean {
        return loggablePrefixes.any { loggablePrefix -> startsWith(loggablePrefix) }
    }

    /** Proxy of [SysUiStatsLog] for testing purpose. */
    interface StatsLogProxy {
        /** Logs a [SysUiStatsLog.COMMUNAL_HUB_WIDGET_EVENT_REPORTED] stats event. */
        fun writeCommunalHubWidgetEventReported(
            action: Int,
            componentName: String,
            rank: Int,
        )

        /** Builds a [SysUiStatsLog.COMMUNAL_HUB_SNAPSHOT] stats event. */
        fun buildCommunalHubSnapshotStatsEvent(
            componentNames: Array<String>,
            widgetCount: Int,
        ): StatsEvent
    }
}

/** Redirects calls to [SysUiStatsLog]. */
@SysUISingleton
class CommunalStatsLogProxyImpl @Inject constructor() : CommunalMetricsLogger.StatsLogProxy {
    override fun writeCommunalHubWidgetEventReported(
        action: Int,
        componentName: String,
        rank: Int,
    ) {
        SysUiStatsLog.write(
            SysUiStatsLog.COMMUNAL_HUB_WIDGET_EVENT_REPORTED,
            action,
            componentName,
            rank,
        )
    }

    override fun buildCommunalHubSnapshotStatsEvent(
        componentNames: Array<String>,
        widgetCount: Int,
    ): StatsEvent {
        return SysUiStatsLog.buildStatsEvent(
            SysUiStatsLog.COMMUNAL_HUB_SNAPSHOT,
            componentNames,
            widgetCount,
        )
    }
}
