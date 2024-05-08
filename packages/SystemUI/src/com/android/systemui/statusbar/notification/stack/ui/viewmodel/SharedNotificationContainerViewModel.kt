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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import androidx.annotation.VisibleForTesting
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.StatusBarState.SHADE
import com.android.systemui.keyguard.shared.model.StatusBarState.SHADE_LOCKED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.BurnInParameters
import com.android.systemui.keyguard.ui.viewmodel.DozingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToDozingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.ViewStateAccessor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.unfold.domain.interactor.UnfoldTransitionInteractor
import com.android.systemui.util.kotlin.BooleanFlowOperators.and
import com.android.systemui.util.kotlin.BooleanFlowOperators.or
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.kotlin.Utils.Companion.sample as sampleCombine
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive

/** View-model for the shared notification container, used by both the shade and keyguard spaces */
@SysUISingleton
class SharedNotificationContainerViewModel
@Inject
constructor(
    private val interactor: SharedNotificationContainerInteractor,
    dumpManager: DumpManager,
    @Application applicationScope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val notificationStackAppearanceInteractor: NotificationStackAppearanceInteractor,
    private val alternateBouncerToGoneTransitionViewModel:
        AlternateBouncerToGoneTransitionViewModel,
    private val aodToGoneTransitionViewModel: AodToGoneTransitionViewModel,
    private val aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel,
    private val aodToOccludedTransitionViewModel: AodToOccludedTransitionViewModel,
    private val dozingToLockscreenTransitionViewModel: DozingToLockscreenTransitionViewModel,
    private val dozingToOccludedTransitionViewModel: DozingToOccludedTransitionViewModel,
    private val dreamingToLockscreenTransitionViewModel: DreamingToLockscreenTransitionViewModel,
    private val glanceableHubToLockscreenTransitionViewModel:
        GlanceableHubToLockscreenTransitionViewModel,
    private val goneToAodTransitionViewModel: GoneToAodTransitionViewModel,
    private val goneToDozingTransitionViewModel: GoneToDozingTransitionViewModel,
    private val goneToDreamingTransitionViewModel: GoneToDreamingTransitionViewModel,
    private val lockscreenToDreamingTransitionViewModel: LockscreenToDreamingTransitionViewModel,
    private val lockscreenToGlanceableHubTransitionViewModel:
        LockscreenToGlanceableHubTransitionViewModel,
    private val lockscreenToGoneTransitionViewModel: LockscreenToGoneTransitionViewModel,
    private val lockscreenToPrimaryBouncerTransitionViewModel:
        LockscreenToPrimaryBouncerTransitionViewModel,
    private val lockscreenToOccludedTransitionViewModel: LockscreenToOccludedTransitionViewModel,
    private val occludedToAodTransitionViewModel: OccludedToAodTransitionViewModel,
    private val occludedToGoneTransitionViewModel: OccludedToGoneTransitionViewModel,
    private val occludedToLockscreenTransitionViewModel: OccludedToLockscreenTransitionViewModel,
    private val primaryBouncerToGoneTransitionViewModel: PrimaryBouncerToGoneTransitionViewModel,
    private val primaryBouncerToLockscreenTransitionViewModel:
        PrimaryBouncerToLockscreenTransitionViewModel,
    private val aodBurnInViewModel: AodBurnInViewModel,
    unfoldTransitionInteractor: UnfoldTransitionInteractor,
) : FlowDumperImpl(dumpManager) {
    private val statesForConstrainedNotifications: Set<KeyguardState> =
        setOf(AOD, LOCKSCREEN, DOZING, ALTERNATE_BOUNCER, PRIMARY_BOUNCER)
    private val statesForHiddenKeyguard: Set<KeyguardState> = setOf(GONE, OCCLUDED)

    /**
     * Is either shade/qs expanded? This intentionally does not use the [ShadeInteractor] version,
     * as the legacy implementation has extra logic that produces incorrect results.
     */
    private val isAnyExpanded =
        combine(
                shadeInteractor.shadeExpansion.map { it > 0f },
                shadeInteractor.qsExpansion.map { it > 0f },
            ) { shadeExpansion, qsExpansion ->
                shadeExpansion || qsExpansion
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    /**
     * Shade locked is a legacy concept, but necessary to mimic current functionality. Listen for
     * both SHADE_LOCKED and shade/qs expansion in order to determine lock state, as one can arrive
     * before the other.
     */
    private val isShadeLocked: Flow<Boolean> =
        combine(
                keyguardInteractor.statusBarState.map { it == SHADE_LOCKED },
                isAnyExpanded,
            ) { isShadeLocked, isAnyExpanded ->
                isShadeLocked && isAnyExpanded
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpWhileCollecting("isShadeLocked")

    @VisibleForTesting
    val paddingTopDimen: Flow<Int> =
        interactor.configurationBasedDimensions
            .map {
                when {
                    !it.useSplitShade -> 0
                    it.useLargeScreenHeader -> it.marginTopLargeScreen
                    else -> it.marginTop
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("paddingTopDimen")

    val configurationBasedDimensions: Flow<ConfigurationBasedDimensions> =
        interactor.configurationBasedDimensions
            .map {
                val marginTop =
                    when {
                        // y position of the NSSL in the window needs to be 0 under scene container
                        SceneContainerFlag.isEnabled -> 0
                        it.useLargeScreenHeader -> it.marginTopLargeScreen
                        else -> it.marginTop
                    }
                ConfigurationBasedDimensions(
                    marginStart = if (it.useSplitShade) 0 else it.marginHorizontal,
                    marginEnd = it.marginHorizontal,
                    marginBottom = it.marginBottom,
                    marginTop = marginTop,
                    useSplitShade = it.useSplitShade,
                )
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("configurationBasedDimensions")

    /** If the user is visually on one of the unoccluded lockscreen states. */
    val isOnLockscreen: Flow<Boolean> =
        combine(
                keyguardTransitionInteractor.finishedKeyguardState.map {
                    statesForConstrainedNotifications.contains(it)
                },
                keyguardTransitionInteractor.transitionValue(LOCKSCREEN).map { it > 0f },
            ) { constrainedNotificationState, transitioningToOrFromLockscreen ->
                constrainedNotificationState || transitioningToOrFromLockscreen
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false
            )
            .dumpValue("isOnLockscreen")

    /** Are we purely on the keyguard without the shade/qs? */
    val isOnLockscreenWithoutShade: Flow<Boolean> =
        combine(
                isOnLockscreen,
                isAnyExpanded,
            ) { isKeyguard, isAnyExpanded ->
                isKeyguard && !isAnyExpanded
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpValue("isOnLockscreenWithoutShade")

    /** If the user is visually on the glanceable hub or transitioning to/from it */
    private val isOnGlanceableHub: Flow<Boolean> =
        combine(
                keyguardTransitionInteractor.finishedKeyguardState.map { state ->
                    state == GLANCEABLE_HUB
                },
                or(
                    keyguardTransitionInteractor.isInTransitionToState(GLANCEABLE_HUB),
                    keyguardTransitionInteractor.isInTransitionFromState(GLANCEABLE_HUB),
                ),
            ) { isOnGlanceableHub, transitioningToOrFromHub ->
                isOnGlanceableHub || transitioningToOrFromHub
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("isOnGlanceableHub")

    /** Are we purely on the glanceable hub without the shade/qs? */
    val isOnGlanceableHubWithoutShade: Flow<Boolean> =
        combine(
                isOnGlanceableHub,
                isAnyExpanded,
            ) { isGlanceableHub, isAnyExpanded ->
                isGlanceableHub && !isAnyExpanded
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpValue("isOnGlanceableHubWithoutShade")

    /** Are we on the dream without the shade/qs? */
    private val isDreamingWithoutShade: Flow<Boolean> =
        combine(
                keyguardTransitionInteractor.isFinishedInState(DREAMING),
                isAnyExpanded,
            ) { isDreaming, isAnyExpanded ->
                isDreaming && !isAnyExpanded
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpValue("isDreamingWithoutShade")

    /**
     * Fade in if the user swipes the shade back up, not if collapsed by going to AOD. This is
     * needed due to the lack of a SHADE state with existing keyguard transitions.
     */
    private fun awaitCollapse(): Flow<Boolean> {
        var aodTransitionIsComplete = true
        return combine(
                isOnLockscreenWithoutShade,
                keyguardTransitionInteractor.isInTransition(
                    from = LOCKSCREEN,
                    to = AOD,
                ),
                ::Pair
            )
            .transformWhile { (isOnLockscreenWithoutShade, aodTransitionIsRunning) ->
                // Wait until the AOD transition is complete before terminating
                if (!aodTransitionIsComplete && !aodTransitionIsRunning) {
                    aodTransitionIsComplete = true
                    emit(false) // do not fade in
                    false
                } else if (aodTransitionIsRunning) {
                    aodTransitionIsComplete = false
                    true
                } else if (isOnLockscreenWithoutShade) {
                    // Shade is closed, fade in and terminate
                    emit(true)
                    false
                } else {
                    true
                }
            }
    }

    /** Fade in only for use after the shade collapses */
    val shadeCollapseFadeIn: Flow<Boolean> =
        flow {
                while (currentCoroutineContext().isActive) {
                    // Ensure shade is collapsed
                    isShadeLocked.first { !it }
                    emit(false)
                    // Wait for shade to be fully expanded
                    isShadeLocked.first { it }
                    // ... and then for it to be collapsed OR a transition to AOD begins.
                    // If AOD, do not fade in (a fade out occurs instead).
                    awaitCollapse().collect { doFadeIn ->
                        if (doFadeIn) {
                            emit(true)
                        }
                    }
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )
            .dumpValue("shadeCollapseFadeIn")

    /**
     * The container occupies the entire screen, and must be positioned relative to other elements.
     *
     * On keyguard, this generally fits below the clock and above the lock icon, or in split shade,
     * the top of the screen to the lock icon.
     *
     * When the shade is expanding, the position is controlled by... the shade.
     */
    val bounds: StateFlow<NotificationContainerBounds> by lazy {
        SceneContainerFlag.assertInLegacyMode()
        combine(
                isOnLockscreenWithoutShade,
                keyguardInteractor.notificationContainerBounds,
                paddingTopDimen,
                interactor.topPosition
                    .sampleCombine(
                        keyguardTransitionInteractor.isInTransitionToAnyState,
                        shadeInteractor.qsExpansion,
                    )
                    .onStart { emit(Triple(0f, false, 0f)) }
            ) { onLockscreen, bounds, paddingTop, (top, isInTransitionToAnyState, qsExpansion) ->
                if (onLockscreen) {
                    bounds.copy(top = bounds.top - paddingTop)
                } else {
                    // When QS expansion > 0, it should directly set the top padding so do not
                    // animate it
                    val animate = qsExpansion == 0f && !isInTransitionToAnyState
                    bounds.copy(
                        top = top,
                        isAnimated = animate,
                    )
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Lazily,
                initialValue = NotificationContainerBounds(),
            )
            .dumpValue("bounds")
    }

    /**
     * Ensure view is visible when the shade/qs are expanded. Also, as QS is expanding, fade out
     * notifications unless in splitshade.
     */
    private val alphaForShadeAndQsExpansion: Flow<Float> =
        interactor.configurationBasedDimensions
            .flatMapLatest { configurationBasedDimensions ->
                combineTransform(
                    shadeInteractor.shadeExpansion,
                    shadeInteractor.qsExpansion,
                ) { shadeExpansion, qsExpansion ->
                    if (shadeExpansion > 0f || qsExpansion > 0f) {
                        if (configurationBasedDimensions.useSplitShade) {
                            emit(1f)
                        } else if (qsExpansion == 1f) {
                            // Ensure HUNs will be visible in QS shade (at least while unlocked)
                            emit(1f)
                        } else {
                            // Fade as QS shade expands
                            emit(1f - qsExpansion)
                        }
                    }
                }
            }
            .onStart { emit(1f) }
            .dumpWhileCollecting("alphaForShadeAndQsExpansion")

    private fun toFlowArray(
        states: Set<KeyguardState>,
        flow: (KeyguardState) -> Flow<Boolean>
    ): Array<Flow<Boolean>> {
        return states.map { flow(it) }.toTypedArray()
    }

    private val isTransitioningToHiddenKeyguard: Flow<Boolean> =
        flow {
                while (currentCoroutineContext().isActive) {
                    emit(false)
                    // Ensure states are inactive to start
                    and(
                            *toFlowArray(statesForHiddenKeyguard) { state ->
                                keyguardTransitionInteractor.transitionValue(state).map { it == 0f }
                            }
                        )
                        .first { it }
                    // Wait for a qualifying transition to begin
                    or(
                            *toFlowArray(statesForHiddenKeyguard) { state ->
                                keyguardTransitionInteractor
                                    .transitionStepsToState(state)
                                    .map { it.value > 0f && it.transitionState == RUNNING }
                                    .onStart { emit(false) }
                            }
                        )
                        .first { it }
                    emit(true)
                    // Now await the signal that SHADE state has been reached or the transition was
                    // reversed. Until SHADE state has been replaced it is the only source of when
                    // it is considered safe to reset alpha to 1f for HUNs.
                    combine(
                            keyguardInteractor.statusBarState,
                            and(
                                *toFlowArray(statesForHiddenKeyguard) { state ->
                                    keyguardTransitionInteractor.transitionValue(state).map {
                                        it == 0f
                                    }
                                }
                            )
                        ) { statusBarState, stateIsReversed ->
                            statusBarState == SHADE || stateIsReversed
                        }
                        .first { it }
                }
            }
            .dumpWhileCollecting("isTransitioningToHiddenKeyguard")

    fun keyguardAlpha(viewState: ViewStateAccessor): Flow<Float> {
        // All transition view models are mututally exclusive, and safe to merge
        val alphaTransitions =
            merge(
                alternateBouncerToGoneTransitionViewModel.notificationAlpha(viewState),
                aodToGoneTransitionViewModel.notificationAlpha(viewState),
                aodToLockscreenTransitionViewModel.notificationAlpha,
                aodToOccludedTransitionViewModel.lockscreenAlpha(viewState),
                dozingToLockscreenTransitionViewModel.lockscreenAlpha,
                dozingToOccludedTransitionViewModel.lockscreenAlpha(viewState),
                dreamingToLockscreenTransitionViewModel.lockscreenAlpha,
                goneToAodTransitionViewModel.notificationAlpha,
                goneToDreamingTransitionViewModel.lockscreenAlpha,
                goneToDozingTransitionViewModel.lockscreenAlpha,
                lockscreenToDreamingTransitionViewModel.lockscreenAlpha,
                lockscreenToGoneTransitionViewModel.notificationAlpha(viewState),
                lockscreenToOccludedTransitionViewModel.lockscreenAlpha,
                lockscreenToPrimaryBouncerTransitionViewModel.lockscreenAlpha,
                occludedToAodTransitionViewModel.lockscreenAlpha,
                occludedToGoneTransitionViewModel.notificationAlpha(viewState),
                occludedToLockscreenTransitionViewModel.lockscreenAlpha,
                primaryBouncerToGoneTransitionViewModel.notificationAlpha,
                primaryBouncerToLockscreenTransitionViewModel.lockscreenAlpha,
            )

        return merge(
                alphaTransitions,
                // These remaining cases handle alpha changes within an existing state, such as
                // shade expansion or swipe to dismiss
                combineTransform(
                    isOnLockscreenWithoutShade,
                    isTransitioningToHiddenKeyguard,
                    shadeCollapseFadeIn,
                    alphaForShadeAndQsExpansion,
                    keyguardInteractor.dismissAlpha.dumpWhileCollecting(
                        "keyguardInteractor.keyguardAlpha"
                    ),
                ) {
                    isOnLockscreenWithoutShade,
                    isTransitioningToHiddenKeyguard,
                    shadeCollapseFadeIn,
                    alphaForShadeAndQsExpansion,
                    dismissAlpha ->
                    if (isOnLockscreenWithoutShade) {
                        if (!shadeCollapseFadeIn && dismissAlpha != null) {
                            emit(dismissAlpha)
                        }
                    } else if (!isTransitioningToHiddenKeyguard) {
                        emit(alphaForShadeAndQsExpansion)
                    }
                },
            )
            .distinctUntilChanged()
            .dumpWhileCollecting("keyguardAlpha")
    }

    /**
     * Returns a flow of the expected alpha while running a LOCKSCREEN<->GLANCEABLE_HUB or
     * DREAMING<->GLANCEABLE_HUB transition or idle on the hub.
     *
     * Must return 1.0f when not controlling the alpha since notifications does a min of all the
     * alpha sources.
     */
    val glanceableHubAlpha: Flow<Float> =
        combineTransform(
                isOnGlanceableHubWithoutShade,
                isOnLockscreen,
                isDreamingWithoutShade,
                merge(
                        lockscreenToGlanceableHubTransitionViewModel.notificationAlpha,
                        glanceableHubToLockscreenTransitionViewModel.notificationAlpha,
                    )
                    // Manually emit on start because [notificationAlpha] only starts emitting
                    // when transitions start.
                    .onStart { emit(1f) }
            ) { isOnGlanceableHubWithoutShade, isOnLockscreen, isDreamingWithoutShade, alpha,
                ->
                if ((isOnGlanceableHubWithoutShade || isDreamingWithoutShade) && !isOnLockscreen) {
                    // Notifications should not be visible on the glanceable hub.
                    // TODO(b/321075734): implement a way to actually set the notifications to
                    // gone while on the hub instead of just adjusting alpha
                    emit(0f)
                } else if (isOnGlanceableHubWithoutShade) {
                    // We are transitioning between hub and lockscreen, so set the alpha for the
                    // transition animation.
                    emit(alpha)
                } else {
                    // Not on the hub and no transitions running, return full visibility so we
                    // don't block the notifications from showing.
                    emit(1f)
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("glanceableHubAlpha")

    /**
     * Under certain scenarios, such as swiping up on the lockscreen, the container will need to be
     * translated as the keyguard fades out.
     */
    fun translationY(params: BurnInParameters): Flow<Float> {
        // with SceneContainer, x translation is handled by views, y is handled by compose
        SceneContainerFlag.assertInLegacyMode()
        return combine(
                aodBurnInViewModel
                    .movement(params)
                    .map { it.translationY.toFloat() }
                    .onStart { emit(0f) },
                isOnLockscreenWithoutShade,
                merge(
                    keyguardInteractor.keyguardTranslationY,
                    occludedToLockscreenTransitionViewModel.lockscreenTranslationY,
                )
            ) { burnInY, isOnLockscreenWithoutShade, translationY ->
                if (isOnLockscreenWithoutShade) {
                    burnInY + translationY
                } else {
                    0f
                }
            }
            .dumpWhileCollecting("translationY")
    }

    /** Horizontal translation to apply to the container. */
    val translationX: Flow<Float> =
        merge(
                // The container may need to be translated along the X axis as the keyguard fades
                // out, such as when swiping open the glanceable hub from the lockscreen.
                lockscreenToGlanceableHubTransitionViewModel.notificationTranslationX,
                glanceableHubToLockscreenTransitionViewModel.notificationTranslationX,
                if (SceneContainerFlag.isEnabled) {
                    // The container may need to be translated along the X axis as the unfolded
                    // foldable is folded slightly.
                    unfoldTransitionInteractor.unfoldTranslationX(isOnStartSide = false)
                } else {
                    emptyFlow()
                }
            )
            .dumpWhileCollecting("translationX")

    private val availableHeight: Flow<Float> =
        if (SceneContainerFlag.isEnabled) {
                notificationStackAppearanceInteractor.constrainedAvailableSpace.map { it.toFloat() }
            } else {
                bounds.map { it.bottom - it.top }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("availableHeight")

    /**
     * When on keyguard, there is limited space to display notifications so calculate how many could
     * be shown. Otherwise, there is no limit since the vertical space will be scrollable.
     *
     * When expanding or when the user is interacting with the shade, keep the count stable; do not
     * emit a value.
     */
    fun getMaxNotifications(calculateSpace: (Float, Boolean) -> Int): Flow<Int> {
        val showLimitedNotifications = isOnLockscreenWithoutShade
        val showUnlimitedNotifications =
            combine(
                isOnLockscreen,
                keyguardInteractor.statusBarState,
                merge(
                        primaryBouncerToGoneTransitionViewModel.showAllNotifications,
                        alternateBouncerToGoneTransitionViewModel.showAllNotifications,
                    )
                    .onStart { emit(false) }
            ) { isOnLockscreen, statusBarState, showAllNotifications ->
                statusBarState == SHADE_LOCKED || !isOnLockscreen || showAllNotifications
            }

        return combineTransform(
                showLimitedNotifications,
                showUnlimitedNotifications,
                shadeInteractor.isUserInteracting,
                availableHeight,
                interactor.notificationStackChanged,
                interactor.useExtraShelfSpace,
            ) { flows ->
                val showLimitedNotifications = flows[0] as Boolean
                val showUnlimitedNotifications = flows[1] as Boolean
                val isUserInteracting = flows[2] as Boolean
                val availableHeight = flows[3] as Float
                val useExtraShelfSpace = flows[5] as Boolean

                if (!isUserInteracting) {
                    if (showLimitedNotifications) {
                        emit(calculateSpace(availableHeight, useExtraShelfSpace))
                    } else if (showUnlimitedNotifications) {
                        emit(-1)
                    }
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("maxNotifications")
    }

    fun notificationStackChanged() {
        interactor.notificationStackChanged()
    }

    data class ConfigurationBasedDimensions(
        val marginStart: Int,
        val marginTop: Int,
        val marginEnd: Int,
        val marginBottom: Int,
        val useSplitShade: Boolean,
    )
}
