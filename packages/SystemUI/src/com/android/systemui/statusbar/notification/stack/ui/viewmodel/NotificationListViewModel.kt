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

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.domain.interactor.RemoteInputInteractor
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.domain.interactor.HeadsUpNotificationInteractor
import com.android.systemui.statusbar.notification.domain.interactor.SeenNotificationsInteractor
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor
import com.android.systemui.statusbar.notification.footer.ui.viewmodel.FooterViewModel
import com.android.systemui.statusbar.notification.shared.HeadsUpRowKey
import com.android.systemui.statusbar.notification.shared.NotificationsHeadsUpRefactor
import com.android.systemui.statusbar.notification.shelf.ui.viewmodel.NotificationShelfViewModel
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackInteractor
import com.android.systemui.statusbar.policy.domain.interactor.UserSetupInteractor
import com.android.systemui.statusbar.policy.domain.interactor.ZenModeInteractor
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.util.kotlin.sample
import com.android.systemui.util.ui.AnimatableEvent
import com.android.systemui.util.ui.AnimatedValue
import com.android.systemui.util.ui.toAnimatedValueFlow
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** ViewModel for the list of notifications. */
class NotificationListViewModel
@Inject
constructor(
    val shelf: NotificationShelfViewModel,
    val hideListViewModel: HideListViewModel,
    val footer: Optional<FooterViewModel>,
    val logger: Optional<NotificationLoggerViewModel>,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    notificationStackInteractor: NotificationStackInteractor,
    private val headsUpNotificationInteractor: HeadsUpNotificationInteractor,
    remoteInputInteractor: RemoteInputInteractor,
    seenNotificationsInteractor: SeenNotificationsInteractor,
    shadeInteractor: ShadeInteractor,
    userSetupInteractor: UserSetupInteractor,
    zenModeInteractor: ZenModeInteractor,
    @Background bgDispatcher: CoroutineDispatcher,
    dumpManager: DumpManager,
) : FlowDumperImpl(dumpManager) {
    /**
     * We want the NSSL to be unimportant for accessibility when there are no notifications in it
     * while the device is on lock screen, to avoid an unlabelled NSSL view in TalkBack. Otherwise,
     * we want it to be important for accessibility to enable accessibility auto-scrolling in NSSL.
     * See b/242235264 for more details.
     */
    val isImportantForAccessibility: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(true)
        } else {
            combine(
                    activeNotificationsInteractor.areAnyNotificationsPresent,
                    notificationStackInteractor.isShowingOnLockscreen,
                ) { hasNotifications, isShowingOnLockscreen ->
                    hasNotifications || !isShowingOnLockscreen
                }
                .distinctUntilChanged()
                .dumpWhileCollecting("isImportantForAccessibility")
                .flowOn(bgDispatcher)
        }
    }

    val shouldShowEmptyShadeView: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            combine(
                    activeNotificationsInteractor.areAnyNotificationsPresent,
                    shadeInteractor.isQsFullscreen,
                    notificationStackInteractor.isShowingOnLockscreen,
                ) { hasNotifications, isQsFullScreen, isShowingOnLockscreen ->
                    when {
                        hasNotifications -> false
                        isQsFullScreen -> false
                        // Do not show the empty shade if the lockscreen is visible (including AOD
                        // b/228790482 and bouncer b/267060171), except if the shade is opened on
                        // top.
                        isShowingOnLockscreen -> false
                        else -> true
                    }
                }
                .distinctUntilChanged()
                .dumpWhileCollecting("shouldShowEmptyShadeView")
                .flowOn(bgDispatcher)
        }
    }

    /**
     * Whether the footer should not be visible for the user, even if it's present in the list (as
     * per [shouldIncludeFooterView] below).
     *
     * This essentially corresponds to having the view set to INVISIBLE.
     */
    val shouldHideFooterView: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            // When the shade is closed, the footer is still present in the list, but not visible.
            // This prevents the footer from being shown when a HUN is present, while still allowing
            // the footer to be counted as part of the shade for measurements.
            shadeInteractor.shadeExpansion
                .map { it == 0f }
                .distinctUntilChanged()
                .dumpWhileCollecting("shouldHideFooterView")
                .flowOn(bgDispatcher)
        }
    }

    /**
     * Whether the footer should be part of the list or not, and whether the transition from one
     * state to another should be animated. This essentially corresponds to transitioning the view
     * visibility from VISIBLE to GONE and vice versa.
     *
     * Note that this value being true doesn't necessarily mean that the footer is visible. It could
     * be hidden by another condition (see [shouldHideFooterView] above).
     */
    val shouldIncludeFooterView: Flow<AnimatedValue<Boolean>> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(AnimatedValue.NotAnimating(false))
        } else {
            combine(
                    activeNotificationsInteractor.areAnyNotificationsPresent,
                    userSetupInteractor.isUserSetUp,
                    notificationStackInteractor.isShowingOnLockscreen,
                    shadeInteractor.isQsFullscreen,
                    remoteInputInteractor.isRemoteInputActive
                ) {
                    hasNotifications,
                    isUserSetUp,
                    isShowingOnLockscreen,
                    qsFullScreen,
                    isRemoteInputActive ->
                    when {
                        !hasNotifications -> VisibilityChange.DISAPPEAR_WITH_ANIMATION
                        // Hide the footer until the user setup is complete, to prevent access
                        // to settings (b/193149550).
                        !isUserSetUp -> VisibilityChange.DISAPPEAR_WITH_ANIMATION
                        // Do not show the footer if the lockscreen is visible (incl. AOD),
                        // except if the shade is opened on top. See also b/219680200.
                        // Do not animate, as that makes the footer appear briefly when
                        // transitioning between the shade and keyguard.
                        isShowingOnLockscreen -> VisibilityChange.DISAPPEAR_WITHOUT_ANIMATION
                        // Do not show the footer if quick settings are fully expanded (except
                        // for the foldable split shade view). See b/201427195 && b/222699879.
                        qsFullScreen -> VisibilityChange.DISAPPEAR_WITH_ANIMATION
                        // Hide the footer if remote input is active (i.e. user is replying to a
                        // notification). See b/75984847.
                        isRemoteInputActive -> VisibilityChange.DISAPPEAR_WITH_ANIMATION
                        else -> VisibilityChange.APPEAR_WITH_ANIMATION
                    }
                }
                .distinctUntilChanged(
                    // Equivalent unless visibility changes
                    areEquivalent = { a: VisibilityChange, b: VisibilityChange ->
                        a.visible == b.visible
                    }
                )
                // Should we animate the visibility change?
                .sample(
                    // TODO(b/322167853): This check is currently duplicated in FooterViewModel,
                    //  but instead it should be a field in ShadeAnimationInteractor.
                    combine(
                            shadeInteractor.isShadeFullyExpanded,
                            shadeInteractor.isShadeTouchable,
                            ::Pair
                        )
                        .onStart { emit(Pair(false, false)) }
                ) { visibilityChange, (isShadeFullyExpanded, animationsEnabled) ->
                    // Animate if the shade is interactive, but NOT on the lockscreen. Having
                    // animations enabled while on the lockscreen makes the footer appear briefly
                    // when transitioning between the shade and keyguard.
                    val shouldAnimate =
                        isShadeFullyExpanded && animationsEnabled && visibilityChange.canAnimate
                    AnimatableEvent(visibilityChange.visible, shouldAnimate)
                }
                .toAnimatedValueFlow()
                .dumpWhileCollecting("shouldIncludeFooterView")
                .flowOn(bgDispatcher)
        }
    }

    enum class VisibilityChange(val visible: Boolean, val canAnimate: Boolean) {
        DISAPPEAR_WITHOUT_ANIMATION(visible = false, canAnimate = false),
        DISAPPEAR_WITH_ANIMATION(visible = false, canAnimate = true),
        APPEAR_WITH_ANIMATION(visible = true, canAnimate = true)
    }

    // TODO(b/308591475): This should be tracked separately by the empty shade.
    val areNotificationsHiddenInShade: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            zenModeInteractor.areNotificationsHiddenInShade.dumpWhileCollecting(
                "areNotificationsHiddenInShade"
            )
        }
    }

    // TODO(b/308591475): This should be tracked separately by the empty shade.
    val hasFilteredOutSeenNotifications: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            seenNotificationsInteractor.hasFilteredOutSeenNotifications.dumpWhileCollecting(
                "hasFilteredOutSeenNotifications"
            )
        }
    }

    val hasClearableAlertingNotifications: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            activeNotificationsInteractor.hasClearableAlertingNotifications.dumpWhileCollecting(
                "hasClearableAlertingNotifications"
            )
        }
    }

    val hasNonClearableSilentNotifications: Flow<Boolean> by lazy {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            activeNotificationsInteractor.hasNonClearableSilentNotifications.dumpWhileCollecting(
                "hasNonClearableSilentNotifications"
            )
        }
    }

    val topHeadsUpRow: Flow<HeadsUpRowKey?> by lazy {
        if (NotificationsHeadsUpRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(null)
        } else {
            headsUpNotificationInteractor.topHeadsUpRow.dumpWhileCollecting("topHeadsUpRow")
        }
    }

    val pinnedHeadsUpRows: Flow<Set<HeadsUpRowKey>> by lazy {
        if (NotificationsHeadsUpRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(emptySet())
        } else {
            headsUpNotificationInteractor.pinnedHeadsUpRows.dumpWhileCollecting("pinnedHeadsUpRows")
        }
    }

    val headsUpAnimationsEnabled: Flow<Boolean> by lazy {
        if (NotificationsHeadsUpRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            combine(
                notificationStackInteractor.isShowingOnLockscreen,
                shadeInteractor.isShadeFullyCollapsed
            ) { (isKeyguardShowing, isShadeFullyCollapsed) ->
                !isKeyguardShowing && isShadeFullyCollapsed
            }
                .dumpWhileCollecting("headsUpAnimationsEnabled")
        }
    }

    val hasPinnedHeadsUpRow: Flow<Boolean> by lazy {
        if (NotificationsHeadsUpRefactor.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            headsUpNotificationInteractor.hasPinnedRows.dumpWhileCollecting("hasPinnedHeadsUpRow")
        }
    }

    // TODO(b/325936094) use it for the text displayed in the StatusBar
    fun headsUpRow(key: HeadsUpRowKey): HeadsUpRowViewModel =
        HeadsUpRowViewModel(headsUpNotificationInteractor.headsUpRow(key))

    fun elementKeyFor(key: HeadsUpRowKey): Any = headsUpNotificationInteractor.elementKeyFor(key)

    fun setHeadsUpAnimatingAway(animatingAway: Boolean) {
        headsUpNotificationInteractor.setHeadsUpAnimatingAway(animatingAway)
    }
}
