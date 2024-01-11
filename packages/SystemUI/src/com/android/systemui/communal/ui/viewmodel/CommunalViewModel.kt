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

import android.os.PowerManager
import android.widget.RemoteViews
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalTutorialInteractor
import com.android.systemui.communal.domain.model.CommunalContentModel
import com.android.systemui.communal.widgets.WidgetInteractionHandler
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.media.controls.ui.MediaHost
import com.android.systemui.media.dagger.MediaModule
import com.android.systemui.shade.ShadeViewController
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The default view model used for showing the communal hub. */
@SysUISingleton
class CommunalViewModel
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val communalInteractor: CommunalInteractor,
    private val interactionHandler: WidgetInteractionHandler,
    tutorialInteractor: CommunalTutorialInteractor,
    shadeViewController: Provider<ShadeViewController>,
    powerManager: PowerManager,
    @Named(MediaModule.COMMUNAL_HUB) mediaHost: MediaHost,
) : BaseCommunalViewModel(communalInteractor, shadeViewController, powerManager, mediaHost) {
    @OptIn(ExperimentalCoroutinesApi::class)
    override val communalContent: Flow<List<CommunalContentModel>> =
        tutorialInteractor.isTutorialAvailable.flatMapLatest { isTutorialMode ->
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

    private val _isPopupOnDismissCtaShowing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    override val isPopupOnDismissCtaShowing: Flow<Boolean> =
        _isPopupOnDismissCtaShowing.asStateFlow()

    override fun onOpenWidgetEditor() = communalInteractor.showWidgetEditor()

    override fun onDismissCtaTile() {
        communalInteractor.dismissCtaTile()
        setPopupOnDismissCtaVisibility(true)
        schedulePopupHiding()
    }

    override fun getInteractionHandler(): RemoteViews.InteractionHandler = interactionHandler

    override fun onHidePopupAfterDismissCta() {
        cancelDelayedPopupHiding()
        setPopupOnDismissCtaVisibility(false)
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

    companion object {
        const val POPUP_AUTO_HIDE_TIMEOUT_MS = 12000L
    }
}
