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

import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.ALTERNATE_BOUNCER
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.GLANCEABLE_HUB
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.StatusBarState.SHADE
import com.android.systemui.keyguard.shared.model.StatusBarState.SHADE_LOCKED
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
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
import com.android.systemui.keyguard.ui.viewmodel.OccludedToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.ViewStateAccessor
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.kotlin.Utils.Companion.sample as sampleCombine
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.distinctUntilChanged
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
    communalInteractor: CommunalInteractor,
    private val alternateBouncerToGoneTransitionViewModel:
        AlternateBouncerToGoneTransitionViewModel,
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
    private val occludedToLockscreenTransitionViewModel: OccludedToLockscreenTransitionViewModel,
    private val primaryBouncerToGoneTransitionViewModel: PrimaryBouncerToGoneTransitionViewModel,
    private val primaryBouncerToLockscreenTransitionViewModel:
        PrimaryBouncerToLockscreenTransitionViewModel,
    private val aodBurnInViewModel: AodBurnInViewModel,
) : FlowDumperImpl(dumpManager) {
    private val statesForConstrainedNotifications: Set<KeyguardState> =
        setOf(AOD, LOCKSCREEN, DOZING, ALTERNATE_BOUNCER, PRIMARY_BOUNCER)

    private val lockscreenToGlanceableHubRunning =
        keyguardTransitionInteractor
            .transition(LOCKSCREEN, GLANCEABLE_HUB)
            .map { it.transitionState == STARTED || it.transitionState == RUNNING }
            .distinctUntilChanged()
            .onStart { emit(false) }
            .dumpWhileCollecting("lockscreenToGlanceableHubRunning")

    private val glanceableHubToLockscreenRunning =
        keyguardTransitionInteractor
            .transition(GLANCEABLE_HUB, LOCKSCREEN)
            .map { it.transitionState == STARTED || it.transitionState == RUNNING }
            .distinctUntilChanged()
            .onStart { emit(false) }
            .dumpWhileCollecting("glanceableHubToLockscreenRunning")

    /**
     * Shade locked is a legacy concept, but necessary to mimic current functionality. Listen for
     * both SHADE_LOCKED and shade/qs expansion in order to determine lock state, as one can arrive
     * before the other.
     */
    private val isShadeLocked: Flow<Boolean> =
        combine(
                keyguardInteractor.statusBarState.map { it == SHADE_LOCKED },
                shadeInteractor.qsExpansion.map { it > 0f },
                shadeInteractor.shadeExpansion.map { it > 0f },
            ) { isShadeLocked, isQsExpanded, isShadeExpanded ->
                isShadeLocked && (isQsExpanded || isShadeExpanded)
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("isShadeLocked")

    private val shadeCollapseFadeInComplete =
        MutableStateFlow(false).dumpValue("shadeCollapseFadeInComplete")

    val configurationBasedDimensions: Flow<ConfigurationBasedDimensions> =
        interactor.configurationBasedDimensions
            .map {
                val marginTop =
                    if (it.useLargeScreenHeader) it.marginTopLargeScreen else it.marginTop
                ConfigurationBasedDimensions(
                    marginStart = if (it.useSplitShade) 0 else it.marginHorizontal,
                    marginEnd = it.marginHorizontal,
                    marginBottom = it.marginBottom,
                    marginTop = marginTop,
                    useSplitShade = it.useSplitShade,
                    paddingTop =
                        if (it.useSplitShade) {
                            marginTop
                        } else {
                            0
                        },
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
                keyguardTransitionInteractor
                    .isInTransitionWhere { from, to -> from == LOCKSCREEN || to == LOCKSCREEN }
                    .onStart { emit(false) }
            ) { constrainedNotificationState, transitioningToOrFromLockscreen ->
                constrainedNotificationState || transitioningToOrFromLockscreen
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("isOnLockscreen")

    /** Are we purely on the keyguard without the shade/qs? */
    val isOnLockscreenWithoutShade: Flow<Boolean> =
        combine(
                isOnLockscreen,
                // Shade with notifications
                shadeInteractor.shadeExpansion.map { it > 0f },
                // Shade without notifications, quick settings only (pull down from very top on
                // lockscreen)
                shadeInteractor.qsExpansion.map { it > 0f },
            ) { isKeyguard, isShadeVisible, qsExpansion ->
                isKeyguard && !(isShadeVisible || qsExpansion)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpValue("isOnLockscreenWithoutShade")

    /** Are we purely on the glanceable hub without the shade/qs? */
    val isOnGlanceableHubWithoutShade: Flow<Boolean> =
        combine(
                communalInteractor.isIdleOnCommunal,
                // Shade with notifications
                shadeInteractor.shadeExpansion.map { it > 0f },
                // Shade without notifications, quick settings only (pull down from very top on
                // lockscreen)
                shadeInteractor.qsExpansion.map { it > 0f },
            ) { isIdleOnCommunal, isShadeVisible, qsExpansion ->
                isIdleOnCommunal && !(isShadeVisible || qsExpansion)
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )
            .dumpWhileCollecting("isOnGlanceableHubWithoutShade")

    /**
     * Fade in if the user swipes the shade back up, not if collapsed by going to AOD. This is
     * needed due to the lack of a SHADE state with existing keyguard transitions.
     */
    private fun awaitCollapse(): Flow<Boolean> {
        var aodTransitionIsComplete = true
        return combine(
                isOnLockscreenWithoutShade,
                keyguardTransitionInteractor
                    .isInTransitionWhere(
                        fromStatePredicate = { it == LOCKSCREEN },
                        toStatePredicate = { it == AOD }
                    )
                    .onStart { emit(false) },
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
                            // ... and then for the animation to complete
                            shadeCollapseFadeInComplete.first { it }
                            shadeCollapseFadeInComplete.value = false
                        }
                    }
                }
            }
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )
            .dumpWhileCollecting("shadeCollapseFadeIn")

    /**
     * The container occupies the entire screen, and must be positioned relative to other elements.
     *
     * On keyguard, this generally fits below the clock and above the lock icon, or in split shade,
     * the top of the screen to the lock icon.
     *
     * When the shade is expanding, the position is controlled by... the shade.
     */
    val bounds: StateFlow<NotificationContainerBounds> =
        combine(
                isOnLockscreenWithoutShade,
                keyguardInteractor.notificationContainerBounds,
                configurationBasedDimensions,
                interactor.topPosition
                    .sampleCombine(
                        keyguardTransitionInteractor.isInTransitionToAnyState,
                        shadeInteractor.qsExpansion,
                    )
                    .onStart { emit(Triple(0f, false, 0f)) }
            ) { onLockscreen, bounds, config, (top, isInTransitionToAnyState, qsExpansion) ->
                if (onLockscreen) {
                    bounds.copy(top = bounds.top - config.paddingTop)
                } else {
                    // When QS expansion > 0, it should directly set the top padding so do not
                    // animate it
                    val animate = qsExpansion == 0f && !isInTransitionToAnyState
                    keyguardInteractor.notificationContainerBounds.value.copy(
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
                        } else {
                            // Fade as QS shade expands
                            emit(1f - qsExpansion)
                        }
                    }
                }
            }
            .onStart { emit(0f) }
            .dumpWhileCollecting("alphaForShadeAndQsExpansion")

    private val alphaWhenGoneAndShadeState: Flow<Float> =
        combineTransform(
                keyguardTransitionInteractor.transitions
                    .map { step -> step.to == GONE && step.transitionState == FINISHED }
                    .distinctUntilChanged(),
                keyguardInteractor.statusBarState,
            ) { isGoneTransitionFinished, statusBarState ->
                if (isGoneTransitionFinished && statusBarState == SHADE) {
                    emit(1f)
                }
            }
            .dumpWhileCollecting("alphaWhenGoneAndShadeState")

    fun keyguardAlpha(viewState: ViewStateAccessor): Flow<Float> {
        // All transition view models are mututally exclusive, and safe to merge
        val alphaTransitions =
            merge(
                alternateBouncerToGoneTransitionViewModel.lockscreenAlpha,
                aodToLockscreenTransitionViewModel.notificationAlpha,
                aodToOccludedTransitionViewModel.lockscreenAlpha(viewState),
                dozingToLockscreenTransitionViewModel.lockscreenAlpha,
                dozingToOccludedTransitionViewModel.lockscreenAlpha(viewState),
                dreamingToLockscreenTransitionViewModel.lockscreenAlpha,
                goneToAodTransitionViewModel.notificationAlpha,
                goneToDreamingTransitionViewModel.lockscreenAlpha,
                goneToDozingTransitionViewModel.lockscreenAlpha,
                lockscreenToDreamingTransitionViewModel.lockscreenAlpha,
                lockscreenToGoneTransitionViewModel.lockscreenAlpha(viewState),
                lockscreenToOccludedTransitionViewModel.lockscreenAlpha,
                lockscreenToPrimaryBouncerTransitionViewModel.lockscreenAlpha,
                occludedToAodTransitionViewModel.lockscreenAlpha,
                occludedToLockscreenTransitionViewModel.lockscreenAlpha,
                primaryBouncerToGoneTransitionViewModel.notificationAlpha,
                primaryBouncerToLockscreenTransitionViewModel.lockscreenAlpha,
            )

        return merge(
                alphaTransitions,
                // Sends a final alpha value of 1f when truly gone, to make sure HUNs appear
                alphaWhenGoneAndShadeState,
                // These remaining cases handle alpha changes within an existing state, such as
                // shade expansion or swipe to dismiss
                combineTransform(
                    isOnLockscreenWithoutShade,
                    shadeCollapseFadeIn,
                    alphaForShadeAndQsExpansion,
                    keyguardInteractor.dismissAlpha.dumpWhileCollecting(
                        "keyguardInteractor.keyguardAlpha"
                    ),
                ) {
                    isOnLockscreenWithoutShade,
                    shadeCollapseFadeIn,
                    alphaForShadeAndQsExpansion,
                    dismissAlpha ->
                    if (isOnLockscreenWithoutShade) {
                        if (!shadeCollapseFadeIn && dismissAlpha != null) {
                            emit(dismissAlpha)
                        }
                    } else {
                        emit(alphaForShadeAndQsExpansion)
                    }
                },
            )
            .distinctUntilChanged()
            .dumpWhileCollecting("keyguardAlpha")
    }

    /**
     * Returns a flow of the expected alpha while running a LOCKSCREEN<->GLANCEABLE_HUB transition
     * or idle on the glanceable hub.
     *
     * Must return 1.0f when not controlling the alpha since notifications does a min of all the
     * alpha sources.
     */
    val glanceableHubAlpha: Flow<Float> =
        isOnGlanceableHubWithoutShade
            .flatMapLatest { isOnGlanceableHubWithoutShade ->
                combineTransform(
                    lockscreenToGlanceableHubRunning,
                    glanceableHubToLockscreenRunning,
                    merge(
                        lockscreenToGlanceableHubTransitionViewModel.notificationAlpha,
                        glanceableHubToLockscreenTransitionViewModel.notificationAlpha,
                    )
                ) { lockscreenToGlanceableHubRunning, glanceableHubToLockscreenRunning, alpha ->
                    if (isOnGlanceableHubWithoutShade) {
                        // Notifications should not be visible on the glanceable hub.
                        // TODO(b/321075734): implement a way to actually set the notifications to
                        // gone
                        //  while on the hub instead of just adjusting alpha
                        emit(0f)
                    } else if (
                        lockscreenToGlanceableHubRunning || glanceableHubToLockscreenRunning
                    ) {
                        emit(alpha)
                    } else {
                        // Not on the hub and no transitions running, return full visibility so we
                        // don't
                        // block the notifications from showing.
                        emit(1f)
                    }
                }
            }
            .dumpWhileCollecting("glanceableHubAlpha")

    /**
     * Under certain scenarios, such as swiping up on the lockscreen, the container will need to be
     * translated as the keyguard fades out.
     */
    fun translationY(params: BurnInParameters): Flow<Float> {
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

    /**
     * The container may need to be translated in the x direction as the keyguard fades out, such as
     * when swiping open the glanceable hub from the lockscreen.
     */
    val translationX: Flow<Float> =
        merge(
                lockscreenToGlanceableHubTransitionViewModel.notificationTranslationX,
                glanceableHubToLockscreenTransitionViewModel.notificationTranslationX,
            )
            .dumpWhileCollecting("translationX")

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
            ) { isOnLockscreen, statusBarState ->
                statusBarState == SHADE_LOCKED || !isOnLockscreen
            }

        return combineTransform(
                showLimitedNotifications,
                showUnlimitedNotifications,
                shadeInteractor.isUserInteracting,
                bounds,
                interactor.notificationStackChanged.onStart { emit(Unit) },
                interactor.useExtraShelfSpace,
            ) { flows ->
                val showLimitedNotifications = flows[0] as Boolean
                val showUnlimitedNotifications = flows[1] as Boolean
                val isUserInteracting = flows[2] as Boolean
                val bounds = flows[3] as NotificationContainerBounds
                val useExtraShelfSpace = flows[5] as Boolean

                if (!isUserInteracting) {
                    if (showLimitedNotifications) {
                        emit(calculateSpace(bounds.bottom - bounds.top, useExtraShelfSpace))
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

    fun setShadeCollapseFadeInComplete(complete: Boolean) {
        shadeCollapseFadeInComplete.value = complete
    }

    data class ConfigurationBasedDimensions(
        val marginStart: Int,
        val marginTop: Int,
        val marginEnd: Int,
        val marginBottom: Int,
        val useSplitShade: Boolean,
        val paddingTop: Int,
    )
}
