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

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.annotation.WorkerThread
import com.android.systemui.communal.data.db.CommunalItemRank
import com.android.systemui.communal.data.db.CommunalWidgetDao
import com.android.systemui.communal.data.db.CommunalWidgetItem
import com.android.systemui.communal.shared.CommunalWidgetHost
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import java.util.Optional
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Encapsulates the state of widgets for communal mode. */
interface CommunalWidgetRepository {
    /** A flow of information about active communal widgets stored in database. */
    val communalWidgets: Flow<List<CommunalWidgetContentModel>>

    /** Add a widget at the specified position in the app widget service and the database. */
    fun addWidget(
        provider: ComponentName,
        priority: Int,
        configureWidget: suspend (id: Int) -> Boolean
    ) {}

    /** Delete a widget by id from app widget service and the database. */
    fun deleteWidget(widgetId: Int) {}

    /**
     * Update the order of widgets in the database.
     *
     * @param widgetIdToPriorityMap mapping of the widget ids to the priority of the widget.
     */
    fun updateWidgetOrder(widgetIdToPriorityMap: Map<Int, Int>) {}

    /** Update whether the app widget host should be active. */
    fun updateAppWidgetHostActive(active: Boolean)
}

@SysUISingleton
class CommunalWidgetRepositoryImpl
@Inject
constructor(
    private val appWidgetManager: Optional<AppWidgetManager>,
    private val appWidgetHost: CommunalAppWidgetHost,
    @Application private val applicationScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val communalWidgetHost: CommunalWidgetHost,
    private val communalWidgetDao: CommunalWidgetDao,
    @CommunalLog logBuffer: LogBuffer,
) : CommunalWidgetRepository {
    companion object {
        const val TAG = "CommunalWidgetRepository"
    }

    private val logger = Logger(logBuffer, TAG)

    override fun updateAppWidgetHostActive(active: Boolean) {
        if (active == isHostActive.value) {
            return
        }

        if (active) {
            appWidgetHost.startListening()
        } else {
            appWidgetHost.stopListening()
        }
        isHostActive.value = active
    }

    private val isHostActive = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    override val communalWidgets: Flow<List<CommunalWidgetContentModel>> =
        isHostActive.flatMapLatest { isHostActive ->
            if (!isHostActive || !appWidgetManager.isPresent) {
                return@flatMapLatest flowOf(emptyList())
            }
            communalWidgetDao
                .getWidgets()
                .map { it.map(::mapToContentModel) }
                // As this reads from a database and triggers IPCs to AppWidgetManager,
                // it should be executed in the background.
                .flowOn(bgDispatcher)
        }

    override fun addWidget(
        provider: ComponentName,
        priority: Int,
        configureWidget: suspend (id: Int) -> Boolean
    ) {
        applicationScope.launch(bgDispatcher) {
            val id = communalWidgetHost.allocateIdAndBindWidget(provider)
            if (id != null) {
                val configured =
                    if (communalWidgetHost.requiresConfiguration(id)) {
                        logger.i("Widget ${provider.flattenToString()} requires configuration.")
                        try {
                            configureWidget.invoke(id)
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
                } else {
                    appWidgetHost.deleteAppWidgetId(id)
                }
            }
            logger.i("Added widget ${provider.flattenToString()} at position $priority.")
        }
    }

    override fun deleteWidget(widgetId: Int) {
        applicationScope.launch(bgDispatcher) {
            communalWidgetDao.deleteWidgetById(widgetId)
            appWidgetHost.deleteAppWidgetId(widgetId)
            logger.i("Deleted widget with id $widgetId.")
        }
    }

    override fun updateWidgetOrder(widgetIdToPriorityMap: Map<Int, Int>) {
        applicationScope.launch(bgDispatcher) {
            communalWidgetDao.updateWidgetOrder(widgetIdToPriorityMap)
            logger.i({ "Updated the order of widget list with ids: $str1." }) {
                str1 = widgetIdToPriorityMap.toString()
            }
        }
    }

    @WorkerThread
    private fun mapToContentModel(
        entry: Map.Entry<CommunalItemRank, CommunalWidgetItem>
    ): CommunalWidgetContentModel {
        val (_, widgetId) = entry.value
        return CommunalWidgetContentModel(
            appWidgetId = widgetId,
            providerInfo = appWidgetManager.get().getAppWidgetInfo(widgetId),
            priority = entry.key.rank,
        )
    }
}
