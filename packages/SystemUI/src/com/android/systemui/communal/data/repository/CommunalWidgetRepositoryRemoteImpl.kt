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

package com.android.systemui.communal.data.repository

import android.content.ComponentName
import android.os.UserHandle
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelper
import com.android.systemui.communal.widgets.GlanceableHubWidgetManager
import com.android.systemui.communal.widgets.WidgetConfigurator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The remote implementation of the [CommunalWidgetRepository] that should be injected in a headless
 * system user process. This implementation receives widget data from and routes requests to the
 * remote service in the foreground user.
 */
@SysUISingleton
class CommunalWidgetRepositoryRemoteImpl
@Inject
constructor(
    @Background private val bgScope: CoroutineScope,
    private val glanceableHubWidgetManager: GlanceableHubWidgetManager,
    glanceableHubMultiUserHelper: GlanceableHubMultiUserHelper,
) : CommunalWidgetRepository {

    init {
        // This is the implementation for the headless system user. For the foreground user
        // implementation see [CommunalWidgetRepositoryLocalImpl].
        glanceableHubMultiUserHelper.assertInHeadlessSystemUser()
    }

    override val communalWidgets: Flow<List<CommunalWidgetContentModel>> =
        glanceableHubWidgetManager.widgets

    override fun addWidget(
        provider: ComponentName,
        user: UserHandle,
        rank: Int?,
        configurator: WidgetConfigurator?,
    ) {
        bgScope.launch { glanceableHubWidgetManager.addWidget(provider, user, rank, configurator) }
    }

    override fun deleteWidget(widgetId: Int) {
        bgScope.launch { glanceableHubWidgetManager.deleteWidget(widgetId) }
    }

    override fun updateWidgetOrder(widgetIdToRankMap: Map<Int, Int>) {
        bgScope.launch { glanceableHubWidgetManager.updateWidgetOrder(widgetIdToRankMap) }
    }

    override fun resizeWidget(appWidgetId: Int, spanY: Int, widgetIdToRankMap: Map<Int, Int>) {
        bgScope.launch {
            glanceableHubWidgetManager.resizeWidget(appWidgetId, spanY, widgetIdToRankMap)
        }
    }

    override fun restoreWidgets(oldToNewWidgetIdMap: Map<Int, Int>) {
        throw IllegalStateException("Restore widgets should be performed on a foreground user")
    }

    override fun abortRestoreWidgets() {
        throw IllegalStateException("Restore widgets should be performed on a foreground user")
    }
}
