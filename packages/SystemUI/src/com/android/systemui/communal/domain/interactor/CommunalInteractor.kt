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

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.UserInfo
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import com.android.app.tracing.coroutines.launch
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TransitionKey
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.communal.data.repository.CommunalMediaRepository
import com.android.systemui.communal.data.repository.CommunalSmartspaceRepository
import com.android.systemui.communal.data.repository.CommunalWidgetRepository
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.domain.model.CommunalContentModel.WidgetContent
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.shared.model.CommunalContentSize.FULL
import com.android.systemui.communal.shared.model.CommunalContentSize.HALF
import com.android.systemui.communal.shared.model.CommunalContentSize.THIRD
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.shared.model.EditModeState
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.communal.widgets.EditWidgetsActivityStarter
import com.android.systemui.communal.widgets.WidgetConfigurator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.log.dagger.CommunalTableLog
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.ManagedProfileController
import com.android.systemui.util.kotlin.BooleanFlowOperators.allOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/** Encapsulates business-logic related to communal mode. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalInteractor
@Inject
constructor(
    @Application val applicationScope: CoroutineScope,
    @Background private val bgScope: CoroutineScope,
    @Background val bgDispatcher: CoroutineDispatcher,
    broadcastDispatcher: BroadcastDispatcher,
    private val widgetRepository: CommunalWidgetRepository,
    private val communalPrefsInteractor: CommunalPrefsInteractor,
    private val mediaRepository: CommunalMediaRepository,
    private val smartspaceRepository: CommunalSmartspaceRepository,
    keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    communalSettingsInteractor: CommunalSettingsInteractor,
    private val appWidgetHost: CommunalAppWidgetHost,
    private val editWidgetsActivityStarter: EditWidgetsActivityStarter,
    private val userTracker: UserTracker,
    private val activityStarter: ActivityStarter,
    private val userManager: UserManager,
    private val communalSceneInteractor: CommunalSceneInteractor,
    sceneInteractor: SceneInteractor,
    @CommunalLog logBuffer: LogBuffer,
    @CommunalTableLog tableLogBuffer: TableLogBuffer,
    private val managedProfileController: ManagedProfileController,
) {
    private val logger = Logger(logBuffer, "CommunalInteractor")

    private val _editModeOpen = MutableStateFlow(false)

    /**
     * Whether edit mode is currently open. This will be true from onCreate to onDestroy in
     * [EditWidgetsActivity] and thus does not correspond to whether or not the activity is visible.
     *
     * Note that since this is called in onDestroy, it's not guaranteed to ever be set to false when
     * edit mode is closed, such as in the case that a user exits edit mode manually with a back
     * gesture or navigation gesture.
     */
    val editModeOpen: StateFlow<Boolean> = _editModeOpen.asStateFlow()

    private val _editActivityShowing = MutableStateFlow(false)

    /**
     * Whether the edit mode activity is currently showing. This is true from onStart to onStop in
     * [EditWidgetsActivity] so may be false even when the user is in edit mode, such as when a
     * widget's individual configuration activity has launched.
     */
    val editActivityShowing: StateFlow<Boolean> = _editActivityShowing.asStateFlow()

    private val _selectedKey: MutableStateFlow<String?> = MutableStateFlow(null)

    val selectedKey: StateFlow<String?> = _selectedKey.asStateFlow()

    /** Whether communal features are enabled. */
    val isCommunalEnabled: StateFlow<Boolean> = communalSettingsInteractor.isCommunalEnabled

    /** Whether communal features are enabled and available. */
    val isCommunalAvailable: Flow<Boolean> =
        allOf(
                communalSettingsInteractor.isCommunalEnabled,
                not(keyguardInteractor.isEncryptedOrLockdown),
                keyguardInteractor.isKeyguardShowing,
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

    private val _isDisclaimerDismissed = MutableStateFlow(false)
    val isDisclaimerDismissed: Flow<Boolean> = _isDisclaimerDismissed.asStateFlow()

    fun setDisclaimerDismissed() {
        bgScope.launch("$TAG#setDisclaimerDismissed") {
            _isDisclaimerDismissed.value = true
            delay(DISCLAIMER_RESET_MILLIS)
            _isDisclaimerDismissed.value = false
        }
    }

    fun setSelectedKey(key: String?) {
        _selectedKey.value = key
    }

    /** Whether to show communal when exiting the occluded state. */
    val showCommunalFromOccluded: Flow<Boolean> =
        keyguardTransitionInteractor.startedKeyguardTransitionStep
            .filter { step -> step.to == KeyguardState.OCCLUDED }
            .combine(isCommunalAvailable, ::Pair)
            .map { (step, available) ->
                available &&
                    (step.from == KeyguardState.GLANCEABLE_HUB ||
                        step.from == KeyguardState.DREAMING)
            }
            .flowOn(bgDispatcher)
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** Whether to start dreaming when returning from occluded */
    val dreamFromOccluded: Flow<Boolean> =
        keyguardTransitionInteractor
            .transition(Edge.create(to = KeyguardState.OCCLUDED))
            .map { it.from == KeyguardState.DREAMING }
            .stateIn(scope = applicationScope, SharingStarted.Eagerly, false)

    /**
     * Target scene as requested by the underlying [SceneTransitionLayout] or through [changeScene].
     *
     * If [isCommunalAvailable] is false, will return [CommunalScenes.Blank]
     */
    @Deprecated(
        "Use com.android.systemui.communal.domain.interactor.CommunalSceneInteractor instead"
    )
    val desiredScene: Flow<SceneKey> = communalSceneInteractor.currentScene

    /** Transition state of the hub mode. */
    @Deprecated(
        "Use com.android.systemui.communal.domain.interactor.CommunalSceneInteractor instead"
    )
    val transitionState: StateFlow<ObservableTransitionState> =
        communalSceneInteractor.transitionState

    private val _userActivity: MutableSharedFlow<Unit> =
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val userActivity: Flow<Unit> = _userActivity.asSharedFlow()

    fun signalUserInteraction() {
        _userActivity.tryEmit(Unit)
    }

    /**
     * Repopulates the communal widgets database by first reading a backed-up state from disk and
     * updating the widget ids indicated by [oldToNewWidgetIdMap]. The backed-up state is removed
     * from disk afterwards.
     */
    fun restoreWidgets(oldToNewWidgetIdMap: Map<Int, Int>) {
        widgetRepository.restoreWidgets(oldToNewWidgetIdMap)
    }

    /**
     * Aborts the task of restoring widgets from a backup. The backed up state stored on disk is
     * removed.
     */
    fun abortRestoreWidgets() {
        widgetRepository.abortRestoreWidgets()
    }

    /**
     * Updates the transition state of the hub [SceneTransitionLayout].
     *
     * Note that you must call is with `null` when the UI is done or risk a memory leak.
     */
    @Deprecated(
        "Use com.android.systemui.communal.domain.interactor.CommunalSceneInteractor instead"
    )
    fun setTransitionState(transitionState: Flow<ObservableTransitionState>?) =
        communalSceneInteractor.setTransitionState(transitionState)

    /** Returns a flow that tracks the progress of transitions to the given scene from 0-1. */
    @Deprecated(
        "Use com.android.systemui.communal.domain.interactor.CommunalSceneInteractor instead"
    )
    fun transitionProgressToScene(targetScene: SceneKey) =
        communalSceneInteractor.transitionProgressToScene(targetScene)

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
        flow { emit(SceneContainerFlag.isEnabled) }
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
    @Deprecated(
        "Use com.android.systemui.communal.domain.interactor.CommunalSceneInteractor instead"
    )
    val isIdleOnCommunal: StateFlow<Boolean> = communalSceneInteractor.isIdleOnCommunal

    /**
     * Flow that emits a boolean if any portion of the communal UI is visible at all.
     *
     * This flow will be true during any transition and when idle on the communal scene.
     */
    @Deprecated(
        "Use com.android.systemui.communal.domain.interactor.CommunalSceneInteractor instead"
    )
    val isCommunalVisible: Flow<Boolean> = communalSceneInteractor.isCommunalVisible

    /**
     * Asks for an asynchronous scene witch to [newScene], which will use the corresponding
     * installed transition or the one specified by [transitionKey], if provided.
     */
    @Deprecated(
        "Use com.android.systemui.communal.domain.interactor.CommunalSceneInteractor instead"
    )
    fun changeScene(
        newScene: SceneKey,
        loggingReason: String,
        transitionKey: TransitionKey? = null,
    ) = communalSceneInteractor.changeScene(newScene, loggingReason, transitionKey)

    fun setEditModeOpen(isOpen: Boolean) {
        _editModeOpen.value = isOpen
    }

    fun setEditActivityShowing(isOpen: Boolean) {
        _editActivityShowing.value = isOpen
    }

    /** Show the widget editor Activity. */
    fun showWidgetEditor(shouldOpenWidgetPickerOnStart: Boolean = false) {
        communalSceneInteractor.setEditModeState(EditModeState.STARTING)
        editWidgetsActivityStarter.startActivity(shouldOpenWidgetPickerOnStart)
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
    suspend fun dismissCtaTile() = communalPrefsInteractor.setCtaDismissed()

    /**
     * Add a widget at the specified rank. If rank is not provided, the widget will be added at the
     * end.
     */
    fun addWidget(
        componentName: ComponentName,
        user: UserHandle,
        rank: Int? = null,
        configurator: WidgetConfigurator?,
    ) = widgetRepository.addWidget(componentName, user, rank, configurator)

    /**
     * Delete a widget by id. Called when user deletes a widget from the hub or a widget is
     * uninstalled from App widget host.
     */
    fun deleteWidget(id: Int) = widgetRepository.deleteWidget(id)

    /**
     * Reorder the widgets.
     *
     * @param widgetIdToRankMap mapping of the widget ids to their new priorities.
     */
    fun updateWidgetOrder(widgetIdToRankMap: Map<Int, Int>) =
        widgetRepository.updateWidgetOrder(widgetIdToRankMap)

    /** Request to unpause work profile that is currently in quiet mode. */
    fun unpauseWorkProfile() {
        managedProfileController.setWorkModeEnabled(true)
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
                    }
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
            widgetRepository.communalWidgets
                .map { filterWidgetsByExistingUsers(it) }
                .combine(communalSettingsInteractor.workProfileUserDisallowedByDevicePolicy) {
                    // exclude widgets under work profile if not allowed by device policy
                    widgets,
                    disallowedByPolicyUser ->
                    filterWidgetsAllowedByDevicePolicy(widgets, disallowedByPolicyUser)
                },
            updateOnWorkProfileBroadcastReceived,
        ) { widgets, _ ->
            widgets.map { widget ->
                when (widget) {
                    is CommunalWidgetContentModel.Available -> {
                        WidgetContent.Widget(
                            appWidgetId = widget.appWidgetId,
                            rank = widget.rank,
                            providerInfo = widget.providerInfo,
                            appWidgetHost = appWidgetHost,
                            inQuietMode = isQuietModeEnabled(widget.providerInfo.profile),
                        )
                    }
                    is CommunalWidgetContentModel.Pending -> {
                        WidgetContent.PendingWidget(
                            appWidgetId = widget.appWidgetId,
                            rank = widget.rank,
                            componentName = widget.componentName,
                            icon = widget.icon,
                        )
                    }
                }
            }
        }

    /** Filter widgets based on whether their associated profile is allowed by device policy. */
    private fun filterWidgetsAllowedByDevicePolicy(
        list: List<CommunalWidgetContentModel>,
        disallowedByDevicePolicyUser: UserInfo?,
    ): List<CommunalWidgetContentModel> =
        if (disallowedByDevicePolicyUser == null) {
            list
        } else {
            list.filter { model ->
                val uid =
                    when (model) {
                        is CommunalWidgetContentModel.Available ->
                            model.providerInfo.profile.identifier
                        is CommunalWidgetContentModel.Pending -> model.user.identifier
                    }
                uid != disallowedByDevicePolicyUser.id
            }
        }

    /** CTA tile to be displayed in the glanceable hub (view mode). */
    val ctaTileContent: Flow<List<CommunalContentModel.CtaTileInViewMode>> =
        communalPrefsInteractor.isCtaDismissed.map { isDismissed ->
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
    fun ongoingContent(isMediaHostVisible: Boolean): Flow<List<CommunalContentModel.Ongoing>> =
        combine(smartspaceRepository.timers, mediaRepository.mediaModel) { timers, media ->
                val ongoingContent = mutableListOf<CommunalContentModel.Ongoing>()

                // Add smartspace timers
                ongoingContent.addAll(
                    timers.map { timer ->
                        CommunalContentModel.Smartspace(
                            smartspaceTargetId = timer.smartspaceTargetId,
                            remoteViews = timer.remoteViews,
                            createdTimestampMillis = timer.createdTimestampMillis,
                        )
                    }
                )

                // Add UMO
                if (isMediaHostVisible && media.hasAnyMediaOrRecommendation) {
                    ongoingContent.add(
                        CommunalContentModel.Umo(
                            createdTimestampMillis = media.createdTimestampMillis
                        )
                    )
                }

                // Order by creation time descending.
                ongoingContent.sortByDescending { it.createdTimestampMillis }
                // Resize the items.
                ongoingContent.resizeItems()

                // Return the sorted and resized items.
                ongoingContent
            }
            .flowOn(bgDispatcher)

    /**
     * Filter and retain widgets associated with an existing user, safeguarding against displaying
     * stale data following user deletion.
     */
    private fun filterWidgetsByExistingUsers(
        list: List<CommunalWidgetContentModel>
    ): List<CommunalWidgetContentModel> {
        val currentUserIds = userTracker.userProfiles.map { it.id }.toSet()
        return list.filter { widget ->
            when (widget) {
                is CommunalWidgetContentModel.Available ->
                    currentUserIds.contains(widget.providerInfo.profile?.identifier)
                is CommunalWidgetContentModel.Pending -> true
            }
        }
    }

    // Dynamically resizes the height of items in the list of ongoing items such that they fit in
    // columns in as compact a space as possible.
    //
    // Currently there are three possible sizes. When the total number is 1, size for that  content
    // is [FULL], when the total number is 2, size for each is [HALF], and 3, size for  each is
    // [THIRD].
    //
    // This algorithm also respects each item's minimum size. All items in a column will have the
    // same size, and all items in a column will be no smaller than any item's minimum size.
    private fun List<CommunalContentModel.Ongoing>.resizeItems() {
        fun resizeColumn(c: List<CommunalContentModel.Ongoing>) {
            if (c.isEmpty()) return
            val newSize = CommunalContentSize.toSize(span = FULL.span / c.size)
            c.forEach { item -> item.size = newSize }
        }

        val column = mutableListOf<CommunalContentModel.Ongoing>()
        var available = FULL.span

        forEach { item ->
            if (available < item.minSize.span) {
                resizeColumn(column)
                column.clear()
                available = FULL.span
            }

            column.add(item)
            available -= item.minSize.span
        }

        // Make sure to resize the final column.
        resizeColumn(column)
    }

    companion object {
        const val TAG = "CommunalInteractor"

        /**
         * The amount of time between showing the widget disclaimer to the user as measured from the
         * moment the disclaimer is dimsissed.
         */
        val DISCLAIMER_RESET_MILLIS = 30.minutes

        /**
         * The user activity timeout which should be used when the communal hub is opened. A value
         * of -1 means that the user's chosen screen timeout will be used instead.
         */
        const val AWAKE_INTERVAL_MS = -1
    }

    /**
     * {@link #setScrollPosition} persists the current communal grid scroll position (to volatile
     * memory) so that the next presentation of the grid (either as glanceable hub or edit mode) can
     * restore position.
     */
    fun setScrollPosition(firstVisibleItemIndex: Int, firstVisibleItemOffset: Int) {
        _firstVisibleItemIndex = firstVisibleItemIndex
        _firstVisibleItemOffset = firstVisibleItemOffset
    }

    val firstVisibleItemIndex: Int
        get() = _firstVisibleItemIndex

    private var _firstVisibleItemIndex: Int = 0

    val firstVisibleItemOffset: Int
        get() = _firstVisibleItemOffset

    private var _firstVisibleItemOffset: Int = 0
}
