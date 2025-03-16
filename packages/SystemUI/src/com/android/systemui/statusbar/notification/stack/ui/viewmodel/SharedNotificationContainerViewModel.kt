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

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.coroutines.flow.flowName
import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
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
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.AodToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DozingToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToDozingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GoneToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToOccludedTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToPrimaryBouncerTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToAodTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OccludedToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.OffToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.ViewStateAccessor
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.LargeScreenHeaderHelper
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode.Dual
import com.android.systemui.shade.shared.model.ShadeMode.Single
import com.android.systemui.shade.shared.model.ShadeMode.Split
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.android.systemui.unfold.domain.interactor.UnfoldTransitionInteractor
import com.android.systemui.util.kotlin.BooleanFlowOperators.anyOf
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.kotlin.Utils.Companion.sample as sampleCombine
import com.android.systemui.util.kotlin.sample
import dagger.Lazy
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
import kotlinx.coroutines.flow.filter
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
    @ShadeDisplayAware private val context: Context,
    @ShadeDisplayAware configurationInteractor: ConfigurationInteractor,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val notificationStackAppearanceInteractor: NotificationStackAppearanceInteractor,
    private val alternateBouncerToGoneTransitionViewModel:
        AlternateBouncerToGoneTransitionViewModel,
    private val alternateBouncerToPrimaryBouncerTransitionViewModel:
        AlternateBouncerToPrimaryBouncerTransitionViewModel,
    private val aodToGoneTransitionViewModel: AodToGoneTransitionViewModel,
    private val aodToLockscreenTransitionViewModel: AodToLockscreenTransitionViewModel,
    private val aodToOccludedTransitionViewModel: AodToOccludedTransitionViewModel,
    dozingToGlanceableHubTransitionViewModel: DozingToGlanceableHubTransitionViewModel,
    private val dozingToLockscreenTransitionViewModel: DozingToLockscreenTransitionViewModel,
    private val dozingToOccludedTransitionViewModel: DozingToOccludedTransitionViewModel,
    private val dreamingToLockscreenTransitionViewModel: DreamingToLockscreenTransitionViewModel,
    private val glanceableHubToLockscreenTransitionViewModel:
        GlanceableHubToLockscreenTransitionViewModel,
    private val goneToAodTransitionViewModel: GoneToAodTransitionViewModel,
    private val goneToDozingTransitionViewModel: GoneToDozingTransitionViewModel,
    private val goneToDreamingTransitionViewModel: GoneToDreamingTransitionViewModel,
    private val goneToLockscreenTransitionViewModel: GoneToLockscreenTransitionViewModel,
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
    private val offToLockscreenTransitionViewModel: OffToLockscreenTransitionViewModel,
    private val primaryBouncerToGoneTransitionViewModel: PrimaryBouncerToGoneTransitionViewModel,
    private val primaryBouncerToLockscreenTransitionViewModel:
        PrimaryBouncerToLockscreenTransitionViewModel,
    aodBurnInViewModel: AodBurnInViewModel,
    private val communalSceneInteractor: CommunalSceneInteractor,
    // Lazy because it's only used in the SceneContainer + Dual Shade configuration.
    headsUpNotificationInteractor: Lazy<HeadsUpNotificationInteractor>,
    private val largeScreenHeaderHelperLazy: Lazy<LargeScreenHeaderHelper>,
    unfoldTransitionInteractor: UnfoldTransitionInteractor,
) : FlowDumperImpl(dumpManager) {

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
            .flowName("isAnyExpanded")
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
        combine(keyguardInteractor.statusBarState.map { it == SHADE_LOCKED }, isAnyExpanded) {
                isShadeLocked,
                isAnyExpanded ->
                isShadeLocked && isAnyExpanded
            }
            .flowName("isShadeLocked")
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpWhileCollecting("isShadeLocked")

    @VisibleForTesting
    val paddingTopDimen: Flow<Int> =
        if (SceneContainerFlag.isEnabled) {
                configurationInteractor.onAnyConfigurationChange.map {
                    with(context.resources) {
                        val useLargeScreenHeader =
                            getBoolean(R.bool.config_use_large_screen_shade_header)
                        if (useLargeScreenHeader) {
                            largeScreenHeaderHelperLazy.get().getLargeScreenHeaderHeight()
                        } else {
                            getDimensionPixelSize(R.dimen.notification_panel_margin_top)
                        }
                    }
                }
            } else {
                interactor.configurationBasedDimensions.map {
                    when {
                        it.useLargeScreenHeader -> it.marginTopLargeScreen
                        else -> it.marginTop
                    }
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("paddingTopDimen")

    val configurationBasedDimensions: Flow<ConfigurationBasedDimensions> =
        if (SceneContainerFlag.isEnabled) {
                combine(
                    shadeInteractor.isShadeLayoutWide,
                    shadeInteractor.shadeMode,
                    configurationInteractor.onAnyConfigurationChange,
                ) { isShadeLayoutWide, shadeMode, _ ->
                    with(context.resources) {
                        val marginHorizontal =
                            getDimensionPixelSize(
                                if (shadeMode is Dual) {
                                    R.dimen.shade_panel_margin_horizontal
                                } else {
                                    R.dimen.notification_panel_margin_horizontal
                                }
                            )

                        val horizontalPosition =
                            when (shadeMode) {
                                Single -> HorizontalPosition.EdgeToEdge
                                Split -> HorizontalPosition.MiddleToEdge(ratio = 0.5f)
                                Dual ->
                                    if (isShadeLayoutWide) {
                                        HorizontalPosition.FloatAtEnd(
                                            width = getDimensionPixelSize(R.dimen.shade_panel_width)
                                        )
                                    } else {
                                        HorizontalPosition.EdgeToEdge
                                    }
                            }

                        ConfigurationBasedDimensions(
                            horizontalPosition = horizontalPosition,
                            marginStart = if (shadeMode is Split) 0 else marginHorizontal,
                            marginEnd = marginHorizontal,
                            marginBottom =
                                getDimensionPixelSize(R.dimen.notification_panel_margin_bottom),
                            // y position of the NSSL in the window needs to be 0 under scene
                            // container
                            marginTop = 0,
                        )
                    }
                }
            } else {
                interactor.configurationBasedDimensions.map {
                    ConfigurationBasedDimensions(
                        horizontalPosition =
                            if (it.useSplitShade) HorizontalPosition.MiddleToEdge()
                            else HorizontalPosition.EdgeToEdge,
                        marginStart = if (it.useSplitShade) 0 else it.marginHorizontal,
                        marginEnd = it.marginHorizontal,
                        marginBottom = it.marginBottom,
                        marginTop =
                            if (it.useLargeScreenHeader) it.marginTopLargeScreen else it.marginTop,
                    )
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("configurationBasedDimensions")

    /** If the user is visually on one of the unoccluded lockscreen states. */
    val isOnLockscreen: Flow<Boolean> =
        anyOf(
                keyguardTransitionInteractor.transitionValue(AOD).map { it > 0f },
                keyguardTransitionInteractor.transitionValue(DOZING).map { it > 0f },
                keyguardTransitionInteractor.transitionValue(ALTERNATE_BOUNCER).map { it > 0f },
                keyguardTransitionInteractor
                    .transitionValue(
                        scene = Scenes.Bouncer,
                        stateWithoutSceneContainer = PRIMARY_BOUNCER,
                    )
                    .map { it > 0f },
                keyguardTransitionInteractor.transitionValue(LOCKSCREEN).map { it > 0f },
            )
            .flowName("isOnLockscreen")
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpValue("isOnLockscreen")

    /** Are we purely on the keyguard without the shade/qs? */
    val isOnLockscreenWithoutShade: Flow<Boolean> =
        combine(isOnLockscreen, isAnyExpanded) { isKeyguard, isAnyExpanded ->
                isKeyguard && !isAnyExpanded
            }
            .flowName("isOnLockscreenWithoutShade")
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpValue("isOnLockscreenWithoutShade")

    /** If the user is visually on the glanceable hub or transitioning to/from it */
    private val isOnGlanceableHub: Flow<Boolean> =
        combine(
                keyguardTransitionInteractor.isFinishedIn(
                    scene = Scenes.Communal,
                    stateWithoutSceneContainer = GLANCEABLE_HUB,
                ),
                anyOf(
                    keyguardTransitionInteractor.isInTransition(
                        edge = Edge.create(to = Scenes.Communal),
                        edgeWithoutSceneContainer = Edge.create(to = GLANCEABLE_HUB),
                    ),
                    keyguardTransitionInteractor.isInTransition(
                        edge = Edge.create(from = Scenes.Communal),
                        edgeWithoutSceneContainer = Edge.create(from = GLANCEABLE_HUB),
                    ),
                ),
            ) { isOnGlanceableHub, transitioningToOrFromHub ->
                isOnGlanceableHub || transitioningToOrFromHub
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("isOnGlanceableHub")

    /** Are we purely on the glanceable hub without the shade/qs? */
    val isOnGlanceableHubWithoutShade: Flow<Boolean> =
        combine(isOnGlanceableHub, isAnyExpanded) { isGlanceableHub, isAnyExpanded ->
                isGlanceableHub && !isAnyExpanded
            }
            .flowName("isOnGlanceableHubWithoutShade")
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )
            .dumpValue("isOnGlanceableHubWithoutShade")

    /** Are we on the dream without the shade/qs? */
    private val isDreamingWithoutShade: Flow<Boolean> =
        combine(keyguardTransitionInteractor.isFinishedIn(DREAMING), isAnyExpanded) {
                isDreaming,
                isAnyExpanded ->
                isDreaming && !isAnyExpanded
            }
            .flowName("isDreamingWithoutShade")
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
                    edge = Edge.create(from = LOCKSCREEN, to = AOD)
                ),
                ::Pair,
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
            .flowName("shadeCollapseFadeIn")
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
                        keyguardTransitionInteractor.isInTransition,
                        shadeInteractor.qsExpansion,
                    )
                    .onStart { emit(Triple(0f, false, 0f)) },
            ) { onLockscreen, bounds, paddingTop, (top, isInTransitionToAnyState, qsExpansion) ->
                if (onLockscreen) {
                    bounds.copy(top = bounds.top - paddingTop)
                } else {
                    // When QS expansion > 0, it should directly set the top padding so do not
                    // animate it
                    val animate = qsExpansion == 0f && !isInTransitionToAnyState
                    bounds.copy(top = top, isAnimated = animate)
                }
            }
            .flowName("bounds")
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
        if (SceneContainerFlag.isEnabled) {
                shadeInteractor.shadeMode.flatMapLatest { shadeMode ->
                    when (shadeMode) {
                        Single ->
                            combineTransform(
                                shadeInteractor.shadeExpansion,
                                shadeInteractor.qsExpansion,
                            ) { shadeExpansion, qsExpansion ->
                                if (qsExpansion == 1f) {
                                    // Ensure HUNs will be visible in QS shade (at least while
                                    // unlocked)
                                    emit(1f)
                                } else if (shadeExpansion > 0f || qsExpansion > 0f) {
                                    // Fade as QS shade expands
                                    emit(1f - qsExpansion)
                                }
                            }
                        Split -> isAnyExpanded.filter { it }.map { 1f }
                        Dual ->
                            combineTransform(
                                headsUpNotificationInteractor.get().isHeadsUpOrAnimatingAway,
                                shadeInteractor.shadeExpansion,
                                shadeInteractor.qsExpansion,
                            ) { isHeadsUpOrAnimatingAway, shadeExpansion, qsExpansion ->
                                if (isHeadsUpOrAnimatingAway) {
                                    // Ensure HUNs will be visible in QS shade (at least while
                                    // unlocked)
                                    emit(1f)
                                } else if (shadeExpansion > 0f || qsExpansion > 0f) {
                                    // Fade out as QS shade expands
                                    emit(1f - qsExpansion)
                                }
                            }
                    }
                }
            } else {
                interactor.configurationBasedDimensions.flatMapLatest { configurationBasedDimensions
                    ->
                    combineTransform(shadeInteractor.shadeExpansion, shadeInteractor.qsExpansion) {
                        shadeExpansion,
                        qsExpansion ->
                        if (shadeExpansion > 0f || qsExpansion > 0f) {
                            if (configurationBasedDimensions.useSplitShade) {
                                emit(1f)
                            } else if (qsExpansion == 1f) {
                                // Ensure HUNs will be visible in QS shade (at least while
                                // unlocked)
                                emit(1f)
                            } else {
                                // Fade as QS shade expands
                                emit(1f - qsExpansion)
                            }
                        }
                    }
                }
            }
            .onStart { emit(1f) }
            .dumpWhileCollecting("alphaForShadeAndQsExpansion")

    val panelAlpha = keyguardInteractor.panelAlpha

    private fun bouncerToGoneNotificationAlpha(viewState: ViewStateAccessor): Flow<Float> =
        merge(
                primaryBouncerToGoneTransitionViewModel.notificationAlpha,
                alternateBouncerToGoneTransitionViewModel.notificationAlpha(viewState),
            )
            .sample(communalSceneInteractor.isCommunalVisible) { alpha, isCommunalVisible ->
                // when glanceable hub is visible, hide notifications during the transition to GONE
                if (isCommunalVisible) 0f else alpha
            }
            .dumpWhileCollecting("bouncerToGoneNotificationAlpha")

    private fun alphaForTransitions(viewState: ViewStateAccessor): Flow<Float> {
        return merge(
            keyguardInteractor.dismissAlpha.dumpWhileCollecting("keyguardInteractor.dismissAlpha"),
            // All transition view models are mutually exclusive, and safe to merge
            bouncerToGoneNotificationAlpha(viewState),
            aodToGoneTransitionViewModel.notificationAlpha(viewState),
            aodToLockscreenTransitionViewModel.notificationAlpha,
            aodToOccludedTransitionViewModel.lockscreenAlpha(viewState),
            dozingToLockscreenTransitionViewModel.lockscreenAlpha,
            dozingToOccludedTransitionViewModel.lockscreenAlpha(viewState),
            dreamingToLockscreenTransitionViewModel.lockscreenAlpha,
            goneToAodTransitionViewModel.notificationAlpha,
            goneToDreamingTransitionViewModel.lockscreenAlpha,
            goneToDozingTransitionViewModel.notificationAlpha,
            goneToLockscreenTransitionViewModel.lockscreenAlpha,
            lockscreenToDreamingTransitionViewModel.lockscreenAlpha,
            lockscreenToGoneTransitionViewModel.notificationAlpha(viewState),
            lockscreenToOccludedTransitionViewModel.lockscreenAlpha,
            lockscreenToPrimaryBouncerTransitionViewModel.lockscreenAlpha,
            alternateBouncerToPrimaryBouncerTransitionViewModel.lockscreenAlpha,
            occludedToAodTransitionViewModel.lockscreenAlpha,
            occludedToGoneTransitionViewModel.notificationAlpha(viewState),
            occludedToLockscreenTransitionViewModel.lockscreenAlpha,
            offToLockscreenTransitionViewModel.lockscreenAlpha,
            primaryBouncerToLockscreenTransitionViewModel.lockscreenAlpha(viewState),
            glanceableHubToLockscreenTransitionViewModel.keyguardAlpha,
            lockscreenToGlanceableHubTransitionViewModel.keyguardAlpha,
        )
    }

    fun keyguardAlpha(viewState: ViewStateAccessor, scope: CoroutineScope): Flow<Float> {
        val isKeyguardOccluded =
            keyguardTransitionInteractor.transitionValue(OCCLUDED).map { it == 1f }

        val isKeyguardNotVisibleInState =
            if (SceneContainerFlag.isEnabled) {
                isKeyguardOccluded
            } else {
                anyOf(
                    isKeyguardOccluded,
                    keyguardTransitionInteractor
                        .transitionValue(scene = Scenes.Gone, stateWithoutSceneContainer = GONE)
                        .map { it == 1f },
                )
            }

        // Transitions are not (yet) authoritative for NSSL; they still rely on StatusBarState to
        // help determine when the device has fully moved to GONE or OCCLUDED state. Once SHADE
        // state has been set, let shade alpha take over
        val isKeyguardNotVisible =
            combine(isKeyguardNotVisibleInState, keyguardInteractor.statusBarState) {
                isKeyguardNotVisibleInState,
                statusBarState ->
                isKeyguardNotVisibleInState && statusBarState == SHADE
            }

        // This needs to continue collecting the current value so that when it is selected in the
        // flatMapLatest below, the last value gets emitted, to avoid the randomness of `merge`.
        val alphaForTransitionsAndShade =
            merge(alphaForTransitions(viewState), alphaForShadeAndQsExpansion)
                .flowName("alphaForTransitionsAndShade")
                .stateIn(
                    // Use view-level scope instead of ApplicationScope, to prevent collection that
                    // never stops
                    scope = scope,
                    started = SharingStarted.Eagerly,
                    initialValue = 1f,
                )
                .dumpValue("alphaForTransitionsAndShade")

        return isKeyguardNotVisible
            .flatMapLatest { isKeyguardNotVisible ->
                if (isKeyguardNotVisible) {
                    alphaForShadeAndQsExpansion
                } else {
                    alphaForTransitionsAndShade
                }
            }
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
                        dozingToGlanceableHubTransitionViewModel.notificationAlpha,
                    )
                    // Manually emit on start because [notificationAlpha] only starts emitting
                    // when transitions start.
                    .onStart { emit(1f) },
            ) { isOnGlanceableHubWithoutShade, isOnLockscreen, isDreamingWithoutShade, alpha ->
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
    val translationY: Flow<Float> =
        combine(
                aodBurnInViewModel.movement.map { it.translationY.toFloat() }.onStart { emit(0f) },
                isOnLockscreenWithoutShade,
                merge(
                    keyguardInteractor.keyguardTranslationY,
                    occludedToLockscreenTransitionViewModel.lockscreenTranslationY,
                ),
            ) { burnInY, isOnLockscreenWithoutShade, translationY ->
                // with SceneContainer, x translation is handled by views, y is handled by compose
                SceneContainerFlag.assertInLegacyMode()

                if (isOnLockscreenWithoutShade) {
                    burnInY + translationY
                } else {
                    0f
                }
            }
            .dumpWhileCollecting("translationY")

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
                },
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
    fun getLockscreenDisplayConfig(
        calculateSpace: (Float, Boolean) -> Int
    ): Flow<LockscreenDisplayConfig> {
        val showLimitedNotifications = isOnLockscreenWithoutShade
        val showUnlimitedNotificationsAndIsOnLockScreen =
            combine(
                isOnLockscreen,
                keyguardInteractor.statusBarState,
                merge(
                        primaryBouncerToGoneTransitionViewModel.showAllNotifications,
                        alternateBouncerToGoneTransitionViewModel.showAllNotifications,
                    )
                    .onStart { emit(false) },
            ) { isOnLockscreen, statusBarState, showAllNotifications ->
                (statusBarState == SHADE_LOCKED || !isOnLockscreen || showAllNotifications) to
                    isOnLockscreen
            }

        @Suppress("UNCHECKED_CAST")
        return combineTransform(
                showLimitedNotifications,
                showUnlimitedNotificationsAndIsOnLockScreen,
                shadeInteractor.isUserInteracting,
                availableHeight,
                interactor.notificationStackChanged,
                interactor.useExtraShelfSpace,
            ) { flows ->
                val showLimitedNotifications = flows[0] as Boolean
                val (showUnlimitedNotifications, isOnLockscreen) =
                    flows[1] as Pair<Boolean, Boolean>
                val isUserInteracting = flows[2] as Boolean
                val availableHeight = flows[3] as Float
                val useExtraShelfSpace = flows[5] as Boolean

                if (!isUserInteracting) {
                    if (showLimitedNotifications) {
                        emit(
                            LockscreenDisplayConfig(
                                isOnLockscreen = isOnLockscreen,
                                maxNotifications =
                                    calculateSpace(availableHeight, useExtraShelfSpace),
                            )
                        )
                    } else if (showUnlimitedNotifications) {
                        emit(
                            LockscreenDisplayConfig(
                                isOnLockscreen = isOnLockscreen,
                                maxNotifications = -1,
                            )
                        )
                    }
                }
            }
            .distinctUntilChanged()
            .dumpWhileCollecting("maxNotifications")
    }

    /**
     * Wallpaper needs the absolute bottom of notification stack to avoid occlusion
     *
     * @param calculateMaxNotifications is required by getMaxNotifications as calculateSpace by
     *   calling computeMaxKeyguardNotifications in NotificationStackSizeCalculator
     * @param calculateHeight is calling computeHeight in NotificationStackSizeCalculator The edge
     *   case is that when maxNotifications is 0, we won't take shelfHeight into account
     */
    fun getNotificationStackAbsoluteBottom(
        calculateMaxNotifications: (Float, Boolean) -> Int,
        calculateHeight: (Int) -> Float,
        shelfHeight: Float,
    ): Flow<Float> {
        SceneContainerFlag.assertInLegacyMode()

        return combine(
            getLockscreenDisplayConfig(calculateMaxNotifications).map { (_, maxNotifications) ->
                val height = calculateHeight(maxNotifications)
                if (maxNotifications == 0) {
                    height - shelfHeight
                } else {
                    height
                }
            },
            bounds.map { it.top },
        ) { height, top ->
            top + height
        }
    }

    fun notificationStackChanged() {
        interactor.notificationStackChanged()
    }

    data class ConfigurationBasedDimensions(
        val horizontalPosition: HorizontalPosition,
        val marginStart: Int,
        val marginTop: Int,
        val marginEnd: Int,
        val marginBottom: Int,
    )

    /** Specifies the horizontal layout constraints for the notification container. */
    sealed interface HorizontalPosition {
        /** The container is using the full width of the screen (minus any margins). */
        data object EdgeToEdge : HorizontalPosition

        /** The container is laid out from the given [ratio] of the screen width to the end edge. */
        data class MiddleToEdge(val ratio: Float = 0.5f) : HorizontalPosition

        /**
         * The container has a fixed [width] and is aligned to the end of the screen. In this
         * layout, the start edge of the container is floating, i.e. unconstrained.
         */
        data class FloatAtEnd(val width: Int) : HorizontalPosition
    }

    /**
     * Data class representing a configuration for displaying Notifications on the Lockscreen.
     *
     * @param isOnLockscreen is the user on the lockscreen
     * @param maxNotifications Limit for the max number of top-level Notifications to be displayed.
     *   A value of -1 indicates no limit.
     */
    data class LockscreenDisplayConfig(val isOnLockscreen: Boolean, val maxNotifications: Int)
}
