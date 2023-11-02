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

package com.android.systemui.communal.domain.interactor

import android.appwidget.AppWidgetHost
import android.content.ComponentName
import com.android.systemui.communal.data.repository.CommunalRepository
import com.android.systemui.communal.data.repository.CommunalWidgetRepository
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalAppWidgetInfo
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Encapsulates business-logic related to communal mode. */
@SysUISingleton
class CommunalInteractor
@Inject
constructor(
    private val communalRepository: CommunalRepository,
    private val widgetRepository: CommunalWidgetRepository,
    tutorialInteractor: CommunalTutorialInteractor,
    private val appWidgetHost: AppWidgetHost,
) {

    /** Whether communal features are enabled. */
    val isCommunalEnabled: Boolean
        get() = communalRepository.isCommunalEnabled

    /** A flow of info about the widget to be displayed, or null if widget is unavailable. */
    val appWidgetInfo: Flow<CommunalAppWidgetInfo?> = widgetRepository.stopwatchAppWidgetInfo

    /**
     * Target scene as requested by the underlying [SceneTransitionLayout] or through
     * [onSceneChanged].
     */
    val desiredScene: StateFlow<CommunalSceneKey> = communalRepository.desiredScene

    /**
     * Flow that emits a boolean if the communal UI is showing, ie. the [desiredScene] is the
     * [CommunalSceneKey.Communal].
     */
    val isCommunalShowing: Flow<Boolean> =
        communalRepository.desiredScene.map { it == CommunalSceneKey.Communal }

    /** Callback received whenever the [SceneTransitionLayout] finishes a scene transition. */
    fun onSceneChanged(newScene: CommunalSceneKey) {
        communalRepository.setDesiredScene(newScene)
    }

    /** Add a widget at the specified position. */
    fun addWidget(componentName: ComponentName, priority: Int) =
        widgetRepository.addWidget(componentName, priority)

    /** Delete a widget by id. */
    fun deleteWidget(id: Int) = widgetRepository.deleteWidget(id)

    /** A list of all the communal content to be displayed in the communal hub. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val communalContent: Flow<List<CommunalContentModel>> =
        tutorialInteractor.isTutorialAvailable.flatMapLatest { isTutorialMode ->
            if (isTutorialMode) {
                return@flatMapLatest flowOf(tutorialContent)
            }
            widgetContent
        }

    /** A list of widget content to be displayed in the communal hub. */
    private val widgetContent: Flow<List<CommunalContentModel.Widget>> =
        widgetRepository.communalWidgets.map { widgets ->
            widgets.map Widget@{ widget ->
                return@Widget CommunalContentModel.Widget(
                    appWidgetId = widget.appWidgetId,
                    providerInfo = widget.providerInfo,
                    appWidgetHost = appWidgetHost,
                )
            }
        }

    /** A list of tutorial content to be displayed in the communal hub in tutorial mode. */
    private val tutorialContent: List<CommunalContentModel.Tutorial> =
        listOf(
            CommunalContentModel.Tutorial(id = 0, CommunalContentSize.FULL),
            CommunalContentModel.Tutorial(id = 1, CommunalContentSize.THIRD),
            CommunalContentModel.Tutorial(id = 2, CommunalContentSize.THIRD),
            CommunalContentModel.Tutorial(id = 3, CommunalContentSize.THIRD),
            CommunalContentModel.Tutorial(id = 4, CommunalContentSize.HALF),
            CommunalContentModel.Tutorial(id = 5, CommunalContentSize.HALF),
            CommunalContentModel.Tutorial(id = 6, CommunalContentSize.HALF),
            CommunalContentModel.Tutorial(id = 7, CommunalContentSize.HALF),
        )
}
