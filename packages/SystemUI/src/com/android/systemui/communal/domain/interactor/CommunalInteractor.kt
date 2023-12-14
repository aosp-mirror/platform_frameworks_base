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

import android.app.smartspace.SmartspaceTarget
import android.appwidget.AppWidgetHost
import android.content.ComponentName
import com.android.systemui.communal.data.repository.CommunalMediaRepository
import com.android.systemui.communal.data.repository.CommunalRepository
import com.android.systemui.communal.data.repository.CommunalWidgetRepository
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.communal.widgets.EditWidgetsActivityStarter
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.smartspace.data.repository.SmartspaceRepository
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Encapsulates business-logic related to communal mode. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalInteractor
@Inject
constructor(
    private val communalRepository: CommunalRepository,
    private val widgetRepository: CommunalWidgetRepository,
    mediaRepository: CommunalMediaRepository,
    smartspaceRepository: SmartspaceRepository,
    keyguardInteractor: KeyguardInteractor,
    private val appWidgetHost: AppWidgetHost,
    private val editWidgetsActivityStarter: EditWidgetsActivityStarter
) {

    /** Whether communal features are enabled. */
    val isCommunalEnabled: Boolean
        get() = communalRepository.isCommunalEnabled

    /**
     * Target scene as requested by the underlying [SceneTransitionLayout] or through
     * [onSceneChanged].
     */
    val desiredScene: StateFlow<CommunalSceneKey> = communalRepository.desiredScene

    /** Transition state of the hub mode. */
    val transitionState: StateFlow<ObservableCommunalTransitionState> =
        communalRepository.transitionState

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableCommunalTransitionState>?) {
        communalRepository.setTransitionState(transitionState)
    }

    /**
     * Flow that emits a boolean if the communal UI is showing, ie. the [desiredScene] is the
     * [CommunalSceneKey.Communal].
     */
    val isCommunalShowing: Flow<Boolean> =
        communalRepository.desiredScene.map { it == CommunalSceneKey.Communal }

    val isKeyguardVisible: Flow<Boolean> = keyguardInteractor.isKeyguardVisible

    /** Callback received whenever the [SceneTransitionLayout] finishes a scene transition. */
    fun onSceneChanged(newScene: CommunalSceneKey) {
        communalRepository.setDesiredScene(newScene)
    }

    /** Show the widget editor Activity. */
    fun showWidgetEditor() {
        editWidgetsActivityStarter.startActivity()
    }

    /** Add a widget at the specified position. */
    fun addWidget(componentName: ComponentName, priority: Int) =
        widgetRepository.addWidget(componentName, priority)

    /** Delete a widget by id. */
    fun deleteWidget(id: Int) = widgetRepository.deleteWidget(id)

    /** Reorder widgets. The order in the list will be their display order in the hub. */
    fun updateWidgetOrder(ids: List<Int>) = widgetRepository.updateWidgetOrder(ids)

    /** A list of widget content to be displayed in the communal hub. */
    val widgetContent: Flow<List<CommunalContentModel.Widget>> =
        widgetRepository.communalWidgets.map { widgets ->
            widgets.map Widget@{ widget ->
                return@Widget CommunalContentModel.Widget(
                    appWidgetId = widget.appWidgetId,
                    providerInfo = widget.providerInfo,
                    appWidgetHost = appWidgetHost,
                )
            }
        }

    /** A flow of available smartspace content. Currently only showing timer targets. */
    val smartspaceContent: Flow<List<CommunalContentModel.Smartspace>> =
        if (!smartspaceRepository.isSmartspaceRemoteViewsEnabled) {
            flowOf(emptyList())
        } else {
            smartspaceRepository.communalSmartspaceTargets.map { targets ->
                targets
                    .filter { target ->
                        target.featureType == SmartspaceTarget.FEATURE_TIMER &&
                            target.remoteViews != null
                    }
                    .map Target@{ target ->
                        return@Target CommunalContentModel.Smartspace(
                            smartspaceTargetId = target.smartspaceTargetId,
                            remoteViews = target.remoteViews!!,
                            // Smartspace always as HALF for now.
                            size = CommunalContentSize.HALF,
                        )
                    }
            }
        }

    /** A list of tutorial content to be displayed in the communal hub in tutorial mode. */
    val tutorialContent: List<CommunalContentModel.Tutorial> =
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

    val umoContent: Flow<List<CommunalContentModel.Umo>> =
        mediaRepository.mediaPlaying.flatMapLatest { mediaPlaying ->
            if (mediaPlaying) {
                // TODO(b/310254801): support HALF and FULL layouts
                flowOf(listOf(CommunalContentModel.Umo(CommunalContentSize.THIRD)))
            } else {
                flowOf(emptyList())
            }
        }
}
