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

import android.content.res.Resources
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalTutorialInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
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
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.util.kotlin.BooleanFlowOperators.not
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** The default view model used for showing the communal hub. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalViewModel
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @Main private val resources: Resources,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    keyguardInteractor: KeyguardInteractor,
    private val communalInteractor: CommunalInteractor,
    tutorialInteractor: CommunalTutorialInteractor,
    private val shadeInteractor: ShadeInteractor,
    @Named(MediaModule.COMMUNAL_HUB) mediaHost: MediaHost,
    @CommunalLog logBuffer: LogBuffer,
) : BaseCommunalViewModel(communalInteractor, mediaHost) {

    private val logger = Logger(logBuffer, "CommunalViewModel")

    @OptIn(ExperimentalCoroutinesApi::class)
    override val communalContent: Flow<List<CommunalContentModel>> =
        tutorialInteractor.isTutorialAvailable
            .flatMapLatest { isTutorialMode ->
                if (isTutorialMode) {
                    return@flatMapLatest flowOf(communalInteractor.tutorialContent)
                }
                combine(
                    communalInteractor.ongoingContent,
                    communalInteractor.widgetContent,
                    communalInteractor.ctaTileContent,
                ) { ongoing, widgets, ctaTile,
                    ->
                    ongoing + widgets + ctaTile
                }
            }
            .onEach { models ->
                logger.d({ "Content updated: $str1" }) { str1 = models.joinToString { it.key } }
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
                keyguardTransitionInteractor.isFinishedInState(KeyguardState.GLANCEABLE_HUB),
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
                info: AccessibilityNodeInfo
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                // Hint user to long press in order to enter edit mode
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id,
                        resources
                            .getString(R.string.accessibility_action_label_edit_widgets)
                            .lowercase()
                    )
                )
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
            showsOnlyActiveMedia = true
            falsingProtectionNeeded = false
            init(MediaHierarchyManager.LOCATION_COMMUNAL_HUB)
        }
    }

    override fun onOpenWidgetEditor(
        preselectedKey: String?,
        shouldOpenWidgetPickerOnStart: Boolean,
    ) = communalInteractor.showWidgetEditor(preselectedKey, shouldOpenWidgetPickerOnStart)

    override fun onDismissCtaTile() {
        scope.launch {
            communalInteractor.dismissCtaTile()
            setCurrentPopupType(PopupType.CtaTile)
        }
    }

    override fun onShowCustomizeWidgetButton() {
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
     */
    val touchesAllowed: Flow<Boolean> = not(shadeInteractor.isAnyFullyExpanded)

    // TODO(b/339667383): remove this temporary swipe gesture handle
    /**
     * The dream overlay has its own gesture handle as the SysUI window is not visible above the
     * dream. This flow will be false when dreaming so that we don't show a duplicate handle when
     * opening the hub over the dream.
     */
    val showGestureIndicator: Flow<Boolean> = not(keyguardInteractor.isDreaming)

    companion object {
        const val POPUP_AUTO_HIDE_TIMEOUT_MS = 12000L
    }
}

sealed class PopupType {
    object CtaTile : PopupType()
    object CustomizeWidgetButton : PopupType()
}
