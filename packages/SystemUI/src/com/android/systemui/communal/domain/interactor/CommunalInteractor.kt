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
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.communal.data.repository.CommunalMediaRepository
import com.android.systemui.communal.data.repository.CommunalPrefsRepository
import com.android.systemui.communal.data.repository.CommunalRepository
import com.android.systemui.communal.data.repository.CommunalWidgetRepository
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.domain.model.CommunalContentModel.WidgetContent
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.shared.model.CommunalContentSize.FULL
import com.android.systemui.communal.shared.model.CommunalContentSize.HALF
import com.android.systemui.communal.shared.model.CommunalContentSize.THIRD
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.communal.widgets.EditWidgetsActivityStarter
import com.android.systemui.communal.widgets.WidgetConfigurator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.log.dagger.CommunalTableLog
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.UserTracker
import com.android.systemui.smartspace.data.repository.SmartspaceRepository
import com.android.systemui.util.kotlin.BooleanFlowOperators.and
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import com.android.systemui.util.kotlin.BooleanFlowOperators.or
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn

/** Encapsulates business-logic related to communal mode. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalInteractor
@Inject
constructor(
    @Application val applicationScope: CoroutineScope,
    broadcastDispatcher: BroadcastDispatcher,
    private val communalRepository: CommunalRepository,
    private val widgetRepository: CommunalWidgetRepository,
    private val communalPrefsRepository: CommunalPrefsRepository,
    mediaRepository: CommunalMediaRepository,
    smartspaceRepository: SmartspaceRepository,
    keyguardInteractor: KeyguardInteractor,
    communalSettingsInteractor: CommunalSettingsInteractor,
    private val appWidgetHost: CommunalAppWidgetHost,
    private val editWidgetsActivityStarter: EditWidgetsActivityStarter,
    private val userTracker: UserTracker,
    private val activityStarter: ActivityStarter,
    private val userManager: UserManager,
    sceneInteractor: SceneInteractor,
    sceneContainerFlags: SceneContainerFlags,
    @CommunalLog logBuffer: LogBuffer,
    @CommunalTableLog tableLogBuffer: TableLogBuffer,
) {
    private val logger = Logger(logBuffer, "CommunalInteractor")

    private val _editModeOpen = MutableStateFlow(false)

    /** Whether edit mode is currently open. */
    val editModeOpen: StateFlow<Boolean> = _editModeOpen.asStateFlow()

    /** Whether communal features are enabled. */
    val isCommunalEnabled: StateFlow<Boolean> = communalSettingsInteractor.isCommunalEnabled

    /** Whether communal features are enabled and available. */
    val isCommunalAvailable: Flow<Boolean> =
        and(
                communalSettingsInteractor.isCommunalEnabled,
                not(keyguardInteractor.isEncryptedOrLockdown),
                or(keyguardInteractor.isKeyguardVisible, keyguardInteractor.isDreaming)
            )
            .distinctUntilChanged()
            .onEach { available ->
                logger.i({ "Communal is ${if (bool1) "" else "un"}available" }) {
                    bool1 = available
                }
            }
            .logDiffsForTable(
                tableLogBuffer = tableLogBuffer,
                columnPrefix = "",
                columnName = "isCommunalAvailable",
                initialValue = false,
            )
            .shareIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                replay = 1,
            )

    /**
     * Target scene as requested by the underlying [SceneTransitionLayout] or through
     * [onSceneChanged].
     *
     * If [isCommunalAvailable] is false, will return [CommunalScenes.Blank]
     */
    val desiredScene: Flow<SceneKey> =
        communalRepository.desiredScene.combine(isCommunalAvailable) { scene, available ->
            if (available) scene else CommunalScenes.Blank
        }

    /** Transition state of the hub mode. */
    val transitionState: StateFlow<ObservableTransitionState> = communalRepository.transitionState

    val _userActivity: MutableSharedFlow<Unit> =
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val userActivity: Flow<Unit> = _userActivity.asSharedFlow()

    fun signalUserInteraction() {
        _userActivity.tryEmit(Unit)
    }

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) {
        communalRepository.setTransitionState(transitionState)
    }

    /** Returns a flow that tracks the progress of transitions to the given scene from 0-1. */
    fun transitionProgressToScene(targetScene: SceneKey) =
        transitionState
            .flatMapLatest { state ->
                when (state) {
                    is ObservableTransitionState.Idle ->
                        flowOf(CommunalTransitionProgress.Idle(state.scene))
                    is ObservableTransitionState.Transition ->
                        if (state.toScene == targetScene) {
                            state.progress.map {
                                CommunalTransitionProgress.Transition(
                                    // Clamp the progress values between 0 and 1 as actual progress
                                    // values can be higher than 0 or lower than 1 due to a fling.
                                    progress = it.coerceIn(0.0f, 1.0f)
                                )
                            }
                        } else {
                            flowOf(CommunalTransitionProgress.OtherTransition)
                        }
                }
            }
            .distinctUntilChanged()

    /**
     * Flow that emits a boolean if the communal UI is the target scene, ie. the [desiredScene] is
     * the [CommunalScenes.Communal].
     *
     * This will be true as soon as the desired scene is set programmatically or at whatever point
     * during a fling that SceneTransitionLayout determines that the end state will be the communal
     * scene. The value also does not change while flinging away until the target scene is no longer
     * communal.
     *
     * If you need a flow that is only true when communal is fully showing and not in transition,
     * use [isIdleOnCommunal].
     */
    // TODO(b/323215860): rename to something more appropriate after cleaning up usages
    val isCommunalShowing: Flow<Boolean> =
        flow { emit(sceneContainerFlags.isEnabled()) }
            .flatMapLatest { sceneContainerEnabled ->
                if (sceneContainerEnabled) {
                    sceneInteractor.currentScene.map { it == Scenes.Communal }
                } else {
                    desiredScene.map { it == CommunalScenes.Communal }
                }
            }
            .distinctUntilChanged()
            .onEach { showing ->
                logger.i({ "Communal is ${if (bool1) "showing" else "gone"}" }) { bool1 = showing }
            }
            .logDiffsForTable(
                tableLogBuffer = tableLogBuffer,
                columnPrefix = "",
                columnName = "isCommunalShowing",
                initialValue = false,
            )
            .shareIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                replay = 1,
            )

    /**
     * Flow that emits a boolean if the communal UI is fully visible and not in transition.
     *
     * This will not be true while transitioning to the hub and will turn false immediately when a
     * swipe to exit the hub starts.
     */
    val isIdleOnCommunal: Flow<Boolean> =
        communalRepository.transitionState.map {
            it is ObservableTransitionState.Idle && it.scene == CommunalScenes.Communal
        }

    /**
     * Flow that emits a boolean if any portion of the communal UI is visible at all.
     *
     * This flow will be true during any transition and when idle on the communal scene.
     */
    val isCommunalVisible: Flow<Boolean> =
        communalRepository.transitionState.map {
            !(it is ObservableTransitionState.Idle && it.scene == CommunalScenes.Blank)
        }

    /** Callback received whenever the [SceneTransitionLayout] finishes a scene transition. */
    fun onSceneChanged(newScene: SceneKey) {
        communalRepository.setDesiredScene(newScene)
    }

    fun setEditModeOpen(isOpen: Boolean) {
        _editModeOpen.value = isOpen
    }

    /** Show the widget editor Activity. */
    fun showWidgetEditor(preselectedKey: String? = null) {
        editWidgetsActivityStarter.startActivity(preselectedKey)
    }

    /**
     * Navigates to communal widget setting after user has unlocked the device. Currently, this
     * setting resides within the Hub Mode settings screen.
     */
    fun navigateToCommunalWidgetSettings() {
        activityStarter.postStartActivityDismissingKeyguard(
            Intent(Settings.ACTION_COMMUNAL_SETTING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            /* delay= */ 0,
        )
    }

    /** Dismiss the CTA tile from the hub in view mode. */
    suspend fun dismissCtaTile() = communalPrefsRepository.setCtaDismissedForCurrentUser()

    /** Add a widget at the specified position. */
    fun addWidget(
        componentName: ComponentName,
        user: UserHandle,
        priority: Int,
        configurator: WidgetConfigurator?,
    ) = widgetRepository.addWidget(componentName, user, priority, configurator)

    /**
     * Delete a widget by id. Called when user deletes a widget from the hub or a widget is
     * uninstalled from App widget host.
     */
    fun deleteWidget(id: Int) = widgetRepository.deleteWidget(id)

    /**
     * Reorder the widgets.
     *
     * @param widgetIdToPriorityMap mapping of the widget ids to their new priorities.
     */
    fun updateWidgetOrder(widgetIdToPriorityMap: Map<Int, Int>) =
        widgetRepository.updateWidgetOrder(widgetIdToPriorityMap)

    /** Request to unpause work profile that is currently in quiet mode. */
    fun unpauseWorkProfile() {
        userTracker.userProfiles
            .find { it.isManagedProfile }
            ?.userHandle
            ?.let { userHandle ->
                userManager.requestQuietModeEnabled(/* enableQuietMode */ false, userHandle)
            }
    }

    /** Returns true if work profile is in quiet mode (disabled) for user handle. */
    private fun isQuietModeEnabled(userHandle: UserHandle): Boolean =
        userManager.isManagedProfile(userHandle.identifier) &&
            userManager.isQuietModeEnabled(userHandle)

    /** Emits whenever a work profile pause or unpause broadcast is received. */
    private val updateOnWorkProfileBroadcastReceived: Flow<Unit> =
        broadcastDispatcher
            .broadcastFlow(
                filter =
                    IntentFilter().apply {
                        addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                        addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                    },
            )
            .emitOnStart()

    /** All widgets present in db. */
    val communalWidgets: Flow<List<CommunalWidgetContentModel>> =
        isCommunalAvailable.flatMapLatest { available ->
            if (!available) emptyFlow() else widgetRepository.communalWidgets
        }

    /** A list of widget content to be displayed in the communal hub. */
    val widgetContent: Flow<List<WidgetContent>> =
        combine(
            widgetRepository.communalWidgets.map { filterWidgetsByExistingUsers(it) },
            communalSettingsInteractor.communalWidgetCategories,
            updateOnWorkProfileBroadcastReceived,
        ) { widgets, allowedCategories, _ ->
            widgets.map { widget ->
                if (widget.providerInfo.widgetCategory and allowedCategories != 0) {
                    // At least one category this widget specified is allowed, so show it
                    WidgetContent.Widget(
                        appWidgetId = widget.appWidgetId,
                        providerInfo = widget.providerInfo,
                        appWidgetHost = appWidgetHost,
                        inQuietMode = isQuietModeEnabled(widget.providerInfo.profile)
                    )
                } else {
                    WidgetContent.DisabledWidget(
                        appWidgetId = widget.appWidgetId,
                        providerInfo = widget.providerInfo,
                    )
                }
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

    /** CTA tile to be displayed in the glanceable hub (view mode). */
    val ctaTileContent: Flow<List<CommunalContentModel.CtaTileInViewMode>> =
        communalPrefsRepository.isCtaDismissed.map { isDismissed ->
            if (isDismissed) emptyList() else listOf(CommunalContentModel.CtaTileInViewMode())
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
            if (media.hasActiveMediaOrRecommendation) {
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

    /**
     * Filter and retain widgets associated with an existing user, safeguarding against displaying
     * stale data following user deletion.
     */
    private fun filterWidgetsByExistingUsers(
        list: List<CommunalWidgetContentModel>,
    ): List<CommunalWidgetContentModel> {
        val currentUserIds = userTracker.userProfiles.map { it.id }.toSet()
        return list.filter { widget ->
            currentUserIds.contains(widget.providerInfo.profile?.identifier)
        }
    }

    companion object {
        /**
         * The user activity timeout which should be used when the communal hub is opened. A value
         * of -1 means that the user's chosen screen timeout will be used instead.
         */
        const val AWAKE_INTERVAL_MS = -1

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

/** Simplified transition progress data class for tracking a single transition between scenes. */
sealed class CommunalTransitionProgress {
    /** No transition/animation is currently running. */
    data class Idle(val scene: SceneKey) : CommunalTransitionProgress()

    /** There is a transition animating to the expected scene. */
    data class Transition(
        val progress: Float,
    ) : CommunalTransitionProgress()

    /** There is a transition animating to a scene other than the expected scene. */
    data object OtherTransition : CommunalTransitionProgress()
}
