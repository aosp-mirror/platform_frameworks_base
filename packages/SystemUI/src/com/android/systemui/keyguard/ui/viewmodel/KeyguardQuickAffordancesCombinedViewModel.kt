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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import androidx.annotation.VisibleForTesting
import com.android.systemui.keyguard.domain.interactor.KeyguardBottomAreaInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
class KeyguardQuickAffordancesCombinedViewModel
@Inject
constructor(
    private val quickAffordanceInteractor: KeyguardQuickAffordanceInteractor,
    private val keyguardInteractor: KeyguardInteractor,
) {

    /**
     * ID of the slot that's currently selected in the preview that renders exclusively in the
     * wallpaper picker application. This is ignored for the actual, real lock screen experience.
     */
    private val selectedPreviewSlotId =
        MutableStateFlow(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START)

    /**
     * Whether quick affordances are "opaque enough" to be considered visible to and interactive by
     * the user. If they are not interactive, user input should not be allowed on them.
     *
     * Note that there is a margin of error, where we allow very, very slightly transparent views to
     * be considered "fully opaque" for the purpose of being interactive. This is to accommodate the
     * error margin of floating point arithmetic.
     *
     * A view that is visible but with an alpha of less than our threshold either means it's not
     * fully done fading in or is fading/faded out. Either way, it should not be
     * interactive/clickable unless "fully opaque" to avoid issues like in b/241830987.
     */
    private val areQuickAffordancesFullyOpaque: Flow<Boolean> =
        keyguardInteractor.keyguardAlpha
            .map { alpha -> alpha >= AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD }
            .distinctUntilChanged()

    /** An observable for the view-model of the "start button" quick affordance. */
    val startButton: Flow<KeyguardQuickAffordanceViewModel> =
        button(KeyguardQuickAffordancePosition.BOTTOM_START)

    /** An observable for the view-model of the "end button" quick affordance. */
    val endButton: Flow<KeyguardQuickAffordanceViewModel> =
        button(KeyguardQuickAffordancePosition.BOTTOM_END)

    /**
     * Notifies that a slot with the given ID has been selected in the preview experience that is
     * rendering in the wallpaper picker. This is ignored for the real lock screen experience.
     *
     * @see [KeyguardRootViewModel.enablePreviewMode]
     */
    fun onPreviewSlotSelected(slotId: String) {
        selectedPreviewSlotId.value = slotId
    }

    private fun button(
        position: KeyguardQuickAffordancePosition
    ): Flow<KeyguardQuickAffordanceViewModel> {
        return keyguardInteractor.previewMode.flatMapLatest { previewMode ->
            combine(
                if (previewMode.isInPreviewMode) {
                    quickAffordanceInteractor.quickAffordanceAlwaysVisible(position = position)
                } else {
                    quickAffordanceInteractor.quickAffordance(position = position)
                },
                keyguardInteractor.animateDozingTransitions.distinctUntilChanged(),
                areQuickAffordancesFullyOpaque,
                selectedPreviewSlotId,
                quickAffordanceInteractor.useLongPress(),
            ) { model, animateReveal, isFullyOpaque, selectedPreviewSlotId, useLongPress ->
                val slotId = position.toSlotId()
                val isSelected = selectedPreviewSlotId == slotId
                model.toViewModel(
                    animateReveal = !previewMode.isInPreviewMode && animateReveal,
                    isClickable = isFullyOpaque && !previewMode.isInPreviewMode,
                    isSelected =
                    previewMode.isInPreviewMode &&
                        previewMode.shouldHighlightSelectedAffordance &&
                        isSelected,
                    isDimmed =
                    previewMode.isInPreviewMode &&
                        previewMode.shouldHighlightSelectedAffordance &&
                        !isSelected,
                    forceInactive = previewMode.isInPreviewMode,
                    slotId = slotId,
                    useLongPress = useLongPress,
                )
            }
                .distinctUntilChanged()
        }
    }

    private fun KeyguardQuickAffordanceModel.toViewModel(
        animateReveal: Boolean,
        isClickable: Boolean,
        isSelected: Boolean,
        isDimmed: Boolean,
        forceInactive: Boolean,
        slotId: String,
        useLongPress: Boolean,
    ): KeyguardQuickAffordanceViewModel {
        return when (this) {
            is KeyguardQuickAffordanceModel.Visible ->
                KeyguardQuickAffordanceViewModel(
                    configKey = configKey,
                    isVisible = true,
                    animateReveal = animateReveal,
                    icon = icon,
                    onClicked = { parameters ->
                        quickAffordanceInteractor.onQuickAffordanceTriggered(
                            configKey = parameters.configKey,
                            expandable = parameters.expandable,
                            slotId = parameters.slotId,
                        )
                    },
                    isClickable = isClickable,
                    isActivated = !forceInactive && activationState is ActivationState.Active,
                    isSelected = isSelected,
                    useLongPress = useLongPress,
                    isDimmed = isDimmed,
                    slotId = slotId,
                )
            is KeyguardQuickAffordanceModel.Hidden ->
                KeyguardQuickAffordanceViewModel(
                    slotId = slotId,
                )
        }
    }

    companion object {
        // We select a value that's less than 1.0 because we want floating point math precision to
        // not be a factor in determining whether the affordance UI is fully opaque. The number we
        // choose needs to be close enough 1.0 such that the user can't easily tell the difference
        // between the UI with an alpha at the threshold and when the alpha is 1.0. At the same
        // time, we don't want the number to be too close to 1.0 such that there is a chance that we
        // never treat the affordance UI as "fully opaque" as that would risk making it forever not
        // clickable.
        @VisibleForTesting
        const val AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD = 0.95f
    }

}