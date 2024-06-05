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
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancePosition
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

@OptIn(ExperimentalCoroutinesApi::class)
class KeyguardQuickAffordancesCombinedViewModel
@Inject
constructor(
    private val quickAffordanceInteractor: KeyguardQuickAffordanceInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    shadeInteractor: ShadeInteractor,
    aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel,
    dozingToLockscreenTransitionViewModel: DozingToLockscreenTransitionViewModel,
    dreamingHostedToLockscreenTransitionViewModel: DreamingHostedToLockscreenTransitionViewModel,
    dreamingToLockscreenTransitionViewModel: DreamingToLockscreenTransitionViewModel,
    goneToLockscreenTransitionViewModel: GoneToLockscreenTransitionViewModel,
    occludedToLockscreenTransitionViewModel: OccludedToLockscreenTransitionViewModel,
    offToLockscreenTransitionViewModel: OffToLockscreenTransitionViewModel,
    primaryBouncerToLockscreenTransitionViewModel: PrimaryBouncerToLockscreenTransitionViewModel,
    glanceableHubToLockscreenTransitionViewModel: GlanceableHubToLockscreenTransitionViewModel,
    lockscreenToAodTransitionViewModel: LockscreenToAodTransitionViewModel,
    lockscreenToDozingTransitionViewModel: LockscreenToDozingTransitionViewModel,
    lockscreenToDreamingHostedTransitionViewModel: LockscreenToDreamingHostedTransitionViewModel,
    lockscreenToDreamingTransitionViewModel: LockscreenToDreamingTransitionViewModel,
    lockscreenToGoneTransitionViewModel: LockscreenToGoneTransitionViewModel,
    lockscreenToOccludedTransitionViewModel: LockscreenToOccludedTransitionViewModel,
    lockscreenToPrimaryBouncerTransitionViewModel: LockscreenToPrimaryBouncerTransitionViewModel,
    lockscreenToGlanceableHubTransitionViewModel: LockscreenToGlanceableHubTransitionViewModel,
    transitionInteractor: KeyguardTransitionInteractor,
) {

    data class PreviewMode(
        val isInPreviewMode: Boolean = false,
        val shouldHighlightSelectedAffordance: Boolean = false,
    )

    /**
     * Whether this view-model instance is powering the preview experience that renders exclusively
     * in the wallpaper picker application. This should _always_ be `false` for the real lock screen
     * experience.
     */
    private val previewMode = MutableStateFlow(PreviewMode())

    private val showingLockscreen: Flow<Boolean> =
        transitionInteractor.finishedKeyguardState.map { keyguardState ->
            keyguardState == KeyguardState.LOCKSCREEN
        }

    /** The only time the expansion is important is while lockscreen is actively displayed */
    private val shadeExpansionAlpha =
        combine(
            showingLockscreen,
            shadeInteractor.anyExpansion,
        ) { showingLockscreen, expansion ->
            if (showingLockscreen) {
                1 - expansion
            } else {
                0f
            }
        }

    /**
     * ID of the slot that's currently selected in the preview that renders exclusively in the
     * wallpaper picker application. This is ignored for the actual, real lock screen experience.
     */
    private val selectedPreviewSlotId =
        MutableStateFlow(KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START)

    /** alpha while fading the quick affordances out */
    private val fadeInAlpha: Flow<Float> =
        merge(
            aodToLockscreenTransitionViewModel.shortcutsAlpha,
            dozingToLockscreenTransitionViewModel.shortcutsAlpha,
            dreamingHostedToLockscreenTransitionViewModel.shortcutsAlpha,
            dreamingToLockscreenTransitionViewModel.shortcutsAlpha,
            goneToLockscreenTransitionViewModel.shortcutsAlpha,
            occludedToLockscreenTransitionViewModel.shortcutsAlpha,
            offToLockscreenTransitionViewModel.shortcutsAlpha,
            primaryBouncerToLockscreenTransitionViewModel.shortcutsAlpha,
            glanceableHubToLockscreenTransitionViewModel.shortcutsAlpha,
        )

    /** alpha while fading the quick affordances in */
    private val fadeOutAlpha: Flow<Float> =
        merge(
            lockscreenToAodTransitionViewModel.shortcutsAlpha,
            lockscreenToDozingTransitionViewModel.shortcutsAlpha,
            lockscreenToDreamingHostedTransitionViewModel.shortcutsAlpha,
            lockscreenToDreamingTransitionViewModel.shortcutsAlpha,
            lockscreenToGoneTransitionViewModel.shortcutsAlpha,
            lockscreenToOccludedTransitionViewModel.shortcutsAlpha,
            lockscreenToPrimaryBouncerTransitionViewModel.shortcutsAlpha,
            lockscreenToGlanceableHubTransitionViewModel.shortcutsAlpha,
            shadeExpansionAlpha,
        )

    /** The source of truth of alpha for all of the quick affordances on lockscreen */
    val transitionAlpha: Flow<Float> =
        merge(
            fadeInAlpha,
            fadeOutAlpha,
        )

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
        transitionAlpha
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
     * @see [enablePreviewMode]
     */
    fun onPreviewSlotSelected(slotId: String) {
        selectedPreviewSlotId.value = slotId
    }

    /**
     * Puts this view-model in "preview mode", which means it's being used for UI that is rendering
     * the lock screen preview in wallpaper picker / settings and not the real experience on the
     * lock screen.
     *
     * @param initiallySelectedSlotId The ID of the initial slot to render as the selected one.
     * @param shouldHighlightSelectedAffordance Whether the selected quick affordance should be
     *   highlighted (while all others are dimmed to make the selected one stand out).
     */
    fun enablePreviewMode(
        initiallySelectedSlotId: String?,
        shouldHighlightSelectedAffordance: Boolean,
    ) {
        val newPreviewMode =
            PreviewMode(
                isInPreviewMode = true,
                shouldHighlightSelectedAffordance = shouldHighlightSelectedAffordance,
            )
        onPreviewSlotSelected(
            initiallySelectedSlotId ?: KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START
        )
        previewMode.value = newPreviewMode
    }

    private fun button(
        position: KeyguardQuickAffordancePosition
    ): Flow<KeyguardQuickAffordanceViewModel> {
        return previewMode.flatMapLatest { previewMode ->
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
        @VisibleForTesting const val AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD = 0.95f
    }
}
