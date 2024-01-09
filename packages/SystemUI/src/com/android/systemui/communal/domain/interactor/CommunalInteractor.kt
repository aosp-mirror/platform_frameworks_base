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
import com.android.systemui.communal.shared.model.CommunalContentSize.FULL
import com.android.systemui.communal.shared.model.CommunalContentSize.HALF
import com.android.systemui.communal.shared.model.CommunalContentSize.THIRD
import com.android.systemui.communal.shared.model.CommunalSceneKey
import com.android.systemui.communal.shared.model.ObservableCommunalTransitionState
import com.android.systemui.communal.widgets.EditWidgetsActivityStarter
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.smartspace.data.repository.SmartspaceRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Encapsulates business-logic related to communal mode. */
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

    /**
     * Reorder the widgets.
     *
     * @param widgetIdToPriorityMap mapping of the widget ids to their new priorities.
     */
    fun updateWidgetOrder(widgetIdToPriorityMap: Map<Int, Int>) =
        widgetRepository.updateWidgetOrder(widgetIdToPriorityMap)

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

    /** A flow of available smartspace targets. Currently only showing timers. */
    private val smartspaceTargets: Flow<List<SmartspaceTarget>> =
        if (!smartspaceRepository.isSmartspaceRemoteViewsEnabled) {
            flowOf(emptyList())
        } else {
            smartspaceRepository.communalSmartspaceTargets.map { targets ->
                targets.filter { target ->
                    target.featureType == SmartspaceTarget.FEATURE_TIMER &&
                        target.remoteViews != null
                }
            }
        }

    /** A list of tutorial content to be displayed in the communal hub in tutorial mode. */
    val tutorialContent: List<CommunalContentModel.Tutorial> =
        listOf(
            CommunalContentModel.Tutorial(id = 0, FULL),
            CommunalContentModel.Tutorial(id = 1, THIRD),
            CommunalContentModel.Tutorial(id = 2, THIRD),
            CommunalContentModel.Tutorial(id = 3, THIRD),
            CommunalContentModel.Tutorial(id = 4, HALF),
            CommunalContentModel.Tutorial(id = 5, HALF),
            CommunalContentModel.Tutorial(id = 6, HALF),
            CommunalContentModel.Tutorial(id = 7, HALF),
        )

    /**
     * A flow of ongoing content, including smartspace timers and umo, ordered by creation time and
     * sized dynamically.
     */
    val ongoingContent: Flow<List<CommunalContentModel.Ongoing>> =
        combine(smartspaceTargets, mediaRepository.mediaModel) { smartspace, media ->
            val ongoingContent = mutableListOf<CommunalContentModel.Ongoing>()

            // Add smartspace
            ongoingContent.addAll(
                smartspace.map { target ->
                    CommunalContentModel.Smartspace(
                        smartspaceTargetId = target.smartspaceTargetId,
                        remoteViews = target.remoteViews!!,
                        createdTimestampMillis = target.creationTimeMillis,
                    )
                }
            )

            // Add UMO
            if (media.hasAnyMediaOrRecommendation) {
                ongoingContent.add(
                    CommunalContentModel.Umo(
                        createdTimestampMillis = media.createdTimestampMillis,
                    )
                )
            }

            // Order by creation time descending
            ongoingContent.sortByDescending { it.createdTimestampMillis }

            // Dynamic sizing
            ongoingContent.forEachIndexed { index, model ->
                model.size = dynamicContentSize(ongoingContent.size, index)
            }

            return@combine ongoingContent
        }

    companion object {
        /**
         * Calculates the content size dynamically based on the total number of contents of that
         * type.
         *
         * Contents with the same type are expected to fill each column evenly. Currently there are
         * three possible sizes. When the total number is 1, size for that content is [FULL], when
         * the total number is 2, size for each is [HALF], and 3, size for each is [THIRD].
         *
         * When dynamic contents fill in multiple columns, the first column follows the algorithm
         * above, and the remaining contents are packed in [THIRD]s. For example, when the total
         * number if 4, the first one is [FULL], filling the column, and the remaining 3 are
         * [THIRD].
         *
         * @param size The total number of contents of this type.
         * @param index The index of the current content of this type.
         */
        private fun dynamicContentSize(size: Int, index: Int): CommunalContentSize {
            val remainder = size % CommunalContentSize.entries.size
            return CommunalContentSize.toSize(
                span =
                    FULL.span /
                        if (index > remainder - 1) CommunalContentSize.entries.size else remainder
            )
        }
    }
}
