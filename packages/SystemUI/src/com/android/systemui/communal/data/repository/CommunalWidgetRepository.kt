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

package com.android.systemui.communal.data.repository

import android.app.backup.BackupManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.UserHandle
import com.android.systemui.communal.data.backup.CommunalBackupUtils
import com.android.systemui.communal.data.db.CommunalItemRank
import com.android.systemui.communal.data.db.CommunalWidgetDao
import com.android.systemui.communal.data.db.CommunalWidgetItem
import com.android.systemui.communal.nano.CommunalHubState
import com.android.systemui.communal.proto.toCommunalHubState
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.communal.widgets.CommunalWidgetHost
import com.android.systemui.communal.widgets.WidgetConfigurator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** Encapsulates the state of widgets for communal mode. */
interface CommunalWidgetRepository {
    /** A flow of information about active communal widgets stored in database. */
    val communalWidgets: Flow<List<CommunalWidgetContentModel>>

    /** Add a widget at the specified position in the app widget service and the database. */
    fun addWidget(
        provider: ComponentName,
        user: UserHandle,
        priority: Int,
        configurator: WidgetConfigurator? = null
    ) {}

    /**
     * Delete a widget by id from the database and app widget host.
     *
     * @param widgetId id of the widget to remove.
     */
    fun deleteWidget(widgetId: Int) {}

    /**
     * Update the order of widgets in the database.
     *
     * @param widgetIdToPriorityMap mapping of the widget ids to the priority of the widget.
     */
    fun updateWidgetOrder(widgetIdToPriorityMap: Map<Int, Int>) {}

    /**
     * Restores the database by reading a state file from disk and updating the widget ids according
     * to [oldToNewWidgetIdMap].
     */
    fun restoreWidgets(oldToNewWidgetIdMap: Map<Int, Int>)

    /** Aborts the restore process and removes files from disk if necessary. */
    fun abortRestoreWidgets()
}

@SysUISingleton
class CommunalWidgetRepositoryImpl
@Inject
constructor(
    private val appWidgetHost: CommunalAppWidgetHost,
    @Background private val bgScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val communalWidgetHost: CommunalWidgetHost,
    private val communalWidgetDao: CommunalWidgetDao,
    @CommunalLog logBuffer: LogBuffer,
    private val backupManager: BackupManager,
    private val backupUtils: CommunalBackupUtils,
) : CommunalWidgetRepository {
    companion object {
        const val TAG = "CommunalWidgetRepository"
    }

    private val logger = Logger(logBuffer, TAG)

    override val communalWidgets: Flow<List<CommunalWidgetContentModel>> =
        combine(
                communalWidgetDao.getWidgets(),
                communalWidgetHost.appWidgetProviders,
            ) { entries, providers ->
                entries.mapNotNull { entry -> mapToContentModel(entry, providers) }
            }
            // As this reads from a database and triggers IPCs to AppWidgetManager,
            // it should be executed in the background.
            .flowOn(bgDispatcher)

    override fun addWidget(
        provider: ComponentName,
        user: UserHandle,
        priority: Int,
        configurator: WidgetConfigurator?
    ) {
        bgScope.launch {
            val id = communalWidgetHost.allocateIdAndBindWidget(provider, user)
            if (id == null) {
                logger.e("Failed to allocate widget id to ${provider.flattenToString()}")
                return@launch
            }
            val info = communalWidgetHost.getAppWidgetInfo(id)
            val configured =
                if (
                    configurator != null &&
                        info != null &&
                        CommunalWidgetHost.requiresConfiguration(info)
                ) {
                    logger.i("Widget ${provider.flattenToString()} requires configuration.")
                    try {
                        configurator.configureWidget(id)
                    } catch (ex: Exception) {
                        // Cleanup the app widget id if an error happens during configuration.
                        logger.e("Error during widget configuration, cleaning up id $id", ex)
                        if (ex is CancellationException) {
                            appWidgetHost.deleteAppWidgetId(id)
                            // Re-throw cancellation to ensure the parent coroutine also gets
                            // cancelled.
                            throw ex
                        } else {
                            false
                        }
                    }
                } else {
                    logger.i("Skipping configuration for ${provider.flattenToString()}")
                    true
                }
            if (configured) {
                communalWidgetDao.addWidget(
                    widgetId = id,
                    provider = provider,
                    priority = priority,
                )
                backupManager.dataChanged()
            } else {
                appWidgetHost.deleteAppWidgetId(id)
            }
            logger.i("Added widget ${provider.flattenToString()} at position $priority.")
        }
    }

    override fun deleteWidget(widgetId: Int) {
        bgScope.launch {
            if (communalWidgetDao.deleteWidgetById(widgetId)) {
                appWidgetHost.deleteAppWidgetId(widgetId)
                logger.i("Deleted widget with id $widgetId.")
                backupManager.dataChanged()
            }
        }
    }

    override fun updateWidgetOrder(widgetIdToPriorityMap: Map<Int, Int>) {
        bgScope.launch {
            communalWidgetDao.updateWidgetOrder(widgetIdToPriorityMap)
            logger.i({ "Updated the order of widget list with ids: $str1." }) {
                str1 = widgetIdToPriorityMap.toString()
            }
            backupManager.dataChanged()
        }
    }

    override fun restoreWidgets(oldToNewWidgetIdMap: Map<Int, Int>) {
        bgScope.launch {
            // Read restored state file from disk
            val state: CommunalHubState
            try {
                state = backupUtils.readBytesFromDisk().toCommunalHubState()
            } catch (e: Exception) {
                logger.e({ "Failed reading restore data from disk: $str1" }) {
                    str1 = e.localizedMessage
                }
                abortRestoreWidgets()
                return@launch
            }

            val widgetsWithHost = appWidgetHost.appWidgetIds.toList()
            val widgetsToRemove = widgetsWithHost.toMutableList()

            // Produce a new state to be restored, skipping invalid widgets
            val newWidgets =
                state.widgets.mapNotNull { restoredWidget ->
                    val newWidgetId =
                        oldToNewWidgetIdMap[restoredWidget.widgetId] ?: restoredWidget.widgetId

                    // Skip if widget id is not registered with the host
                    if (!widgetsWithHost.contains(newWidgetId)) {
                        logger.d({
                            "Skipped restoring widget (old:$int1 new:$int2) " +
                                "because it is not registered with host"
                        }) {
                            int1 = restoredWidget.widgetId
                            int2 = newWidgetId
                        }
                        return@mapNotNull null
                    }

                    widgetsToRemove.remove(newWidgetId)

                    CommunalHubState.CommunalWidgetItem().apply {
                        widgetId = newWidgetId
                        componentName = restoredWidget.componentName
                        rank = restoredWidget.rank
                    }
                }
            val newState = CommunalHubState().apply { widgets = newWidgets.toTypedArray() }

            // Restore database
            logger.i("Restoring communal database $newState")
            communalWidgetDao.restoreCommunalHubState(newState)

            // Delete restored state file from disk
            backupUtils.clear()

            // Remove widgets from host that have not been restored
            widgetsToRemove.forEach { widgetId ->
                logger.i({ "Deleting widget $int1 from host since it has not been restored" }) {
                    int1 = widgetId
                }
                appWidgetHost.deleteAppWidgetId(widgetId)
            }

            // Providers may have changed
            communalWidgetHost.refreshProviders()
        }
    }

    override fun abortRestoreWidgets() {
        bgScope.launch {
            logger.i("Restore widgets aborted")
            backupUtils.clear()
        }
    }

    private fun mapToContentModel(
        entry: Map.Entry<CommunalItemRank, CommunalWidgetItem>,
        providers: Map<Int, AppWidgetProviderInfo?>,
    ): CommunalWidgetContentModel? {
        val (_, widgetId) = entry.value
        val providerInfo = providers[widgetId] ?: return null
        return CommunalWidgetContentModel(
            appWidgetId = widgetId,
            providerInfo = providerInfo,
            priority = entry.key.rank,
        )
    }
}
