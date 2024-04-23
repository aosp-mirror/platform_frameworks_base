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

import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalTutorialInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.media.dagger.MediaModule
import com.android.systemui.shade.domain.interactor.ShadeInteractor
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
    private val communalInteractor: CommunalInteractor,
    tutorialInteractor: CommunalTutorialInteractor,
    private val shadeInteractor: ShadeInteractor,
    deviceEntryInteractor: DeviceEntryInteractor,
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

    private val _isPopupOnDismissCtaShowing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isPopupOnDismissCtaShowing: Flow<Boolean> =
        _isPopupOnDismissCtaShowing.asStateFlow()

    private val _isEnableWidgetDialogShowing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isEnableWidgetDialogShowing: Flow<Boolean> = _isEnableWidgetDialogShowing.asStateFlow()

    private val _isEnableWorkProfileDialogShowing: MutableStateFlow<Boolean> =
        MutableStateFlow(false)
    val isEnableWorkProfileDialogShowing: Flow<Boolean> =
        _isEnableWorkProfileDialogShowing.asStateFlow()

    val deviceUnlocked: Flow<Boolean> = deviceEntryInteractor.isUnlocked

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
            setPopupOnDismissCtaVisibility(true)
            schedulePopupHiding()
        }
    }

    override fun onHidePopupAfterDismissCta() {
        cancelDelayedPopupHiding()
        setPopupOnDismissCtaVisibility(false)
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

    private fun setPopupOnDismissCtaVisibility(isVisible: Boolean) {
        _isPopupOnDismissCtaShowing.value = isVisible
    }

    private var delayedHidePopupJob: Job? = null

    private fun schedulePopupHiding() {
        cancelDelayedPopupHiding()
        delayedHidePopupJob =
            scope.launch {
                delay(POPUP_AUTO_HIDE_TIMEOUT_MS)
                onHidePopupAfterDismissCta()
            }
    }

    private fun cancelDelayedPopupHiding() {
        delayedHidePopupJob?.cancel()
        delayedHidePopupJob = null
    }

    /** Whether we can transition to a new scene based on a user gesture. */
    fun canChangeScene(): Boolean {
        return !shadeInteractor.isAnyFullyExpanded.value
    }

    companion object {
        const val POPUP_AUTO_HIDE_TIMEOUT_MS = 12000L
    }
}
