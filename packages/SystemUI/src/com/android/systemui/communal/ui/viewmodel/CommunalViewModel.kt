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

package com.android.systemui.communal.ui.viewmodel

import android.content.ComponentName
import android.content.res.Resources
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.domain.interactor.CommunalSettingsInteractor
import com.android.systemui.communal.domain.interactor.CommunalTutorialInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.shared.log.CommunalMetricsLogger
import com.android.systemui.communal.shared.model.CommunalBackgroundType
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.dagger.MediaModule
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.KeyguardIndicationController
import com.android.systemui.util.kotlin.BooleanFlowOperators.allOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The default view model used for showing the communal hub. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalViewModel
@Inject
constructor(
    @Main val mainDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    @Background private val bgScope: CoroutineScope,
    @Main private val resources: Resources,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    keyguardInteractor: KeyguardInteractor,
    private val keyguardIndicationController: KeyguardIndicationController,
    communalSceneInteractor: CommunalSceneInteractor,
    private val communalInteractor: CommunalInteractor,
    communalSettingsInteractor: CommunalSettingsInteractor,
    tutorialInteractor: CommunalTutorialInteractor,
    private val shadeInteractor: ShadeInteractor,
    @Named(MediaModule.COMMUNAL_HUB) mediaHost: MediaHost,
    @CommunalLog logBuffer: LogBuffer,
    private val metricsLogger: CommunalMetricsLogger,
) : BaseCommunalViewModel(communalSceneInteractor, communalInteractor, mediaHost) {

    private val logger = Logger(logBuffer, "CommunalViewModel")

    private val isMediaHostVisible =
        conflatedCallbackFlow {
                val callback = { visible: Boolean ->
                    trySend(visible)
                    Unit
                }
                mediaHost.addVisibilityChangeListener(callback)
                awaitClose { mediaHost.removeVisibilityChangeListener(callback) }
            }
            .onStart {
                // Ensure the visibility state is correct when the hub is opened and this flow is
                // started so that the UMO is shown when needed. The visibility state in MediaHost
                // is not updated once its view has been detached, aka the hub is closed, which can
                // result in this getting stuck as False and never being updated as the UMO is not
                // shown.
                mediaHost.updateViewVisibility()
                emit(mediaHost.visible)
            }
            .distinctUntilChanged()
            .onEach { logger.d({ "_isMediaHostVisible: $bool1" }) { bool1 = it } }
            .flowOn(mainDispatcher)

    /** Communal content saved from the previous emission when the flow is active (not "frozen"). */
    private var frozenCommunalContent: List<CommunalContentModel>? = null

    private val ongoingContent =
        isMediaHostVisible.flatMapLatest { isMediaHostVisible ->
            communalInteractor.ongoingContent(isMediaHostVisible).onEach {
                mediaHost.updateViewVisibility()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val latestCommunalContent: Flow<List<CommunalContentModel>> =
        tutorialInteractor.isTutorialAvailable
            .flatMapLatest { isTutorialMode ->
                if (isTutorialMode) {
                    return@flatMapLatest flowOf(communalInteractor.tutorialContent)
                }
                combine(
                    ongoingContent,
                    communalInteractor.widgetContent,
                    communalInteractor.ctaTileContent,
                ) { ongoing, widgets, ctaTile ->
                    ongoing + widgets + ctaTile
                }
            }
            .onEach { models ->
                frozenCommunalContent = models
                logger.d({ "Content updated: $str1" }) { str1 = models.joinToString { it.key } }
            }

    override val isCommunalContentVisible: Flow<Boolean> = MutableStateFlow(true)

    /**
     * Freeze the content flow, when an activity is about to show, like starting a timer via voice:
     * 1) in handheld mode, use the keyguard occluded state;
     * 2) in dreaming mode, where keyguard is already occluded by dream, use the dream wakeup
     *    signal. Since in this case the shell transition info does not include
     *    KEYGUARD_VISIBILITY_TRANSIT_FLAGS, KeyguardTransitionHandler will not run the
     *    occludeAnimation on KeyguardViewMediator.
     */
    override val isCommunalContentFlowFrozen: Flow<Boolean> =
        allOf(
                keyguardTransitionInteractor.isFinishedIn(
                    scene = Scenes.Communal,
                    stateWithoutSceneContainer = KeyguardState.GLANCEABLE_HUB,
                ),
                keyguardInteractor.isKeyguardOccluded,
                not(keyguardInteractor.isAbleToDream),
            )
            .distinctUntilChanged()
            .onEach { logger.d("isCommunalContentFlowFrozen: $it") }

    override val communalContent: Flow<List<CommunalContentModel>> =
        isCommunalContentFlowFrozen
            .flatMapLatestConflated { isFrozen ->
                if (isFrozen) {
                    flowOf(frozenCommunalContent ?: emptyList())
                } else {
                    latestCommunalContent
                }
            }
            .onEach { models ->
                logger.d({ "CommunalContent: $str1" }) { str1 = models.joinToString { it.key } }
            }

    override val isEmptyState: Flow<Boolean> =
        communalInteractor.widgetContent
            .map { it.isEmpty() }
            .distinctUntilChanged()
            .onEach { logger.d("isEmptyState: $it") }

    private val _currentPopup: MutableStateFlow<PopupType?> = MutableStateFlow(null)
    override val currentPopup: Flow<PopupType?> = _currentPopup.asStateFlow()

    // The widget is focusable for accessibility when the hub is fully visible and shade is not
    // opened.
    override val isFocusable: Flow<Boolean> =
        combine(
                keyguardTransitionInteractor.isFinishedIn(
                    scene = Scenes.Communal,
                    stateWithoutSceneContainer = KeyguardState.GLANCEABLE_HUB,
                ),
                communalInteractor.isIdleOnCommunal,
                shadeInteractor.isAnyFullyExpanded,
            ) { transitionedToGlanceableHub, isIdleOnCommunal, isAnyFullyExpanded ->
                transitionedToGlanceableHub && isIdleOnCommunal && !isAnyFullyExpanded
            }
            .distinctUntilChanged()

    override val widgetAccessibilityDelegate =
        object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfo,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                // Hint user to long press in order to enter edit mode
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id,
                        resources
                            .getString(R.string.accessibility_action_label_edit_widgets)
                            .lowercase(),
                    )
                )
            }

            override fun performAccessibilityAction(
                host: View,
                action: Int,
                args: Bundle?,
            ): Boolean {
                when (action) {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id -> {
                        onOpenWidgetEditor()
                        return true
                    }
                }
                return super.performAccessibilityAction(host, action, args)
            }
        }

    private val _isEnableWidgetDialogShowing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isEnableWidgetDialogShowing: Flow<Boolean> = _isEnableWidgetDialogShowing.asStateFlow()

    private val _isEnableWorkProfileDialogShowing: MutableStateFlow<Boolean> =
        MutableStateFlow(false)
    val isEnableWorkProfileDialogShowing: Flow<Boolean> =
        _isEnableWorkProfileDialogShowing.asStateFlow()

    init {
        // Initialize our media host for the UMO. This only needs to happen once and must be done
        // before the MediaHierarchyManager attempts to move the UMO to the hub.
        with(mediaHost) {
            expansion = MediaHostState.EXPANDED
            expandedMatchesParentHeight = true
            showsOnlyActiveMedia = false
            falsingProtectionNeeded = false
            disablePagination = true
            init(MediaHierarchyManager.LOCATION_COMMUNAL_HUB)
        }
    }

    override fun onOpenWidgetEditor(shouldOpenWidgetPickerOnStart: Boolean) {
        persistScrollPosition()
        communalInteractor.showWidgetEditor(shouldOpenWidgetPickerOnStart)
    }

    override fun onDismissCtaTile() {
        scope.launch {
            communalInteractor.dismissCtaTile()
            setCurrentPopupType(PopupType.CtaTile)
        }
    }

    override fun onTapWidget(componentName: ComponentName, rank: Int) {
        metricsLogger.logTapWidget(componentName.flattenToString(), rank)
    }

    fun onClick() {
        keyguardIndicationController.showActionToUnlock()
    }

    override fun onLongClick() {
        setCurrentPopupType(PopupType.CustomizeWidgetButton)
    }

    override fun onHidePopup() {
        setCurrentPopupType(null)
    }

    override fun onOpenEnableWidgetDialog() {
        setIsEnableWidgetDialogShowing(true)
    }

    fun onEnableWidgetDialogConfirm() {
        communalInteractor.navigateToCommunalWidgetSettings()
        setIsEnableWidgetDialogShowing(false)
    }

    fun onEnableWidgetDialogCancel() {
        setIsEnableWidgetDialogShowing(false)
    }

    override fun onOpenEnableWorkProfileDialog() {
        setIsEnableWorkProfileDialogShowing(true)
    }

    fun onEnableWorkProfileDialogConfirm() {
        communalInteractor.unpauseWorkProfile()
        setIsEnableWorkProfileDialogShowing(false)
    }

    fun onEnableWorkProfileDialogCancel() {
        setIsEnableWorkProfileDialogShowing(false)
    }

    private fun setIsEnableWidgetDialogShowing(isVisible: Boolean) {
        _isEnableWidgetDialogShowing.value = isVisible
    }

    private fun setIsEnableWorkProfileDialogShowing(isVisible: Boolean) {
        _isEnableWorkProfileDialogShowing.value = isVisible
    }

    private fun setCurrentPopupType(popupType: PopupType?) {
        _currentPopup.value = popupType
        delayedHideCurrentPopupJob?.cancel()

        if (popupType != null) {
            delayedHideCurrentPopupJob =
                scope.launch {
                    delay(POPUP_AUTO_HIDE_TIMEOUT_MS)
                    setCurrentPopupType(null)
                }
        } else {
            delayedHideCurrentPopupJob = null
        }
    }

    private var delayedHideCurrentPopupJob: Job? = null

    /** Whether we can transition to a new scene based on a user gesture. */
    fun canChangeScene(): Boolean {
        return !shadeInteractor.isAnyFullyExpanded.value
    }

    /**
     * Whether touches should be disabled in communal.
     *
     * This is needed because the notification shade does not block touches in blank areas and these
     * fall through to the glanceable hub, which we don't want.
     *
     * Using a StateFlow as the value does not necessarily change when hub becomes available.
     */
    val touchesAllowed: StateFlow<Boolean> =
        not(shadeInteractor.isAnyFullyExpanded)
            .stateIn(bgScope, SharingStarted.Eagerly, initialValue = false)

    /** The type of background to use for the hub. */
    val communalBackground: Flow<CommunalBackgroundType> =
        communalSettingsInteractor.communalBackground

    companion object {
        const val POPUP_AUTO_HIDE_TIMEOUT_MS = 12000L
    }
}

sealed class PopupType {
    data object CtaTile : PopupType()

    data object CustomizeWidgetButton : PopupType()
}
