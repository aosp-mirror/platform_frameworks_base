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

package com.android.systemui.keyguard.ui.composable.section

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.compose.modifiers.thenIf
import com.android.systemui.common.ui.ConfigurationState
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.composable.blueprint.rememberBurnIn
import com.android.systemui.keyguard.ui.composable.modifier.burnInAware
import com.android.systemui.keyguard.ui.viewmodel.AodBurnInViewModel
import com.android.systemui.keyguard.ui.viewmodel.BurnInParameters
import com.android.systemui.keyguard.ui.viewmodel.KeyguardRootViewModel
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.notifications.ui.composable.ConstrainedNotificationStack
import com.android.systemui.notifications.ui.composable.SnoozeableHeadsUpNotificationSpace
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.AlwaysOnDisplayNotificationIconViewStore
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder
import com.android.systemui.statusbar.notification.icon.ui.viewbinder.StatusBarIconViewBindingFailureTracker
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel
import com.android.systemui.statusbar.notification.promoted.AODPromotedNotification
import com.android.systemui.statusbar.notification.promoted.PromotedNotificationUiAod
import com.android.systemui.statusbar.notification.promoted.ui.viewmodel.AODPromotedNotificationViewModel
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationScrollView
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationsPlaceholderViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.ui.SystemBarUtilsState
import com.android.systemui.util.ui.isAnimating
import com.android.systemui.util.ui.stopAnimating
import com.android.systemui.util.ui.value
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.launch

@SysUISingleton
class NotificationSection
@Inject
constructor(
    private val stackScrollView: Lazy<NotificationScrollView>,
    private val viewModelFactory: NotificationsPlaceholderViewModel.Factory,
    private val aodBurnInViewModel: AodBurnInViewModel,
    sharedNotificationContainer: SharedNotificationContainer,
    sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    stackScrollLayout: NotificationStackScrollLayout,
    sharedNotificationContainerBinder: SharedNotificationContainerBinder,
    private val keyguardRootViewModel: KeyguardRootViewModel,
    @ShadeDisplayAware private val configurationState: ConfigurationState,
    private val iconBindingFailureTracker: StatusBarIconViewBindingFailureTracker,
    private val nicAodViewModel: NotificationIconContainerAlwaysOnDisplayViewModel,
    private val nicAodIconViewStore: AlwaysOnDisplayNotificationIconViewStore,
    private val aodPromotedNotificationViewModelFactory: AODPromotedNotificationViewModel.Factory,
    private val systemBarUtilsState: SystemBarUtilsState,
    private val clockInteractor: KeyguardClockInteractor,
) {

    init {
        // This scene container section moves the NSSL to the SharedNotificationContainer.
        // This also requires that SharedNotificationContainer gets moved to the
        // SceneWindowRootView by the SceneWindowRootViewBinder. Prior to Scene Container,
        // NSSL is moved into this container by the NotificationStackScrollLayoutSection.
        // Ensure stackScrollLayout is a child of sharedNotificationContainer.

        if (stackScrollLayout.parent != sharedNotificationContainer) {
            (stackScrollLayout.parent as? ViewGroup)?.removeView(stackScrollLayout)
            sharedNotificationContainer.addNotificationStackScrollLayout(stackScrollLayout)
        }

        sharedNotificationContainerBinder.bind(
            sharedNotificationContainer,
            sharedNotificationContainerViewModel,
        )
    }

    @Composable
    fun AodPromotedNotificationArea(modifier: Modifier = Modifier) {
        if (!PromotedNotificationUiAod.isEnabled) {
            return
        }

        val isVisible by
            keyguardRootViewModel.isAodPromotedNotifVisible.collectAsStateWithLifecycle()
        val burnIn = rememberBurnIn(clockInteractor)

        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = modifier.burnInAware(aodBurnInViewModel, burnIn.parameters),
        ) {
            AODPromotedNotification(aodPromotedNotificationViewModelFactory)
        }
    }

    @Composable
    fun AodNotificationIcons(modifier: Modifier = Modifier) {
        val isVisible by
            keyguardRootViewModel.isNotifIconContainerVisible.collectAsStateWithLifecycle()
        val transitionState = remember { MutableTransitionState(isVisible.value) }
        LaunchedEffect(key1 = isVisible, key2 = transitionState.isIdle) {
            transitionState.targetState = isVisible.value
            if (isVisible.isAnimating && transitionState.isIdle) {
                isVisible.stopAnimating()
            }
        }
        val burnIn = rememberBurnIn(clockInteractor)
        AnimatedVisibility(
            visibleState = transitionState,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                modifier
                    .height(dimensionResource(R.dimen.notification_shelf_height))
                    .burnInAware(aodBurnInViewModel, burnIn.parameters),
        ) {
            val scope = rememberCoroutineScope()
            AndroidView(
                factory = { context ->
                    NotificationIconContainer(context, null).also { nic ->
                        scope.launch {
                            NotificationIconContainerViewBinder.bind(
                                nic,
                                nicAodViewModel,
                                configurationState,
                                systemBarUtilsState,
                                iconBindingFailureTracker,
                                nicAodIconViewStore,
                            )
                        }
                    }
                }
            )
        }
    }

    @Composable
    fun ContentScope.HeadsUpNotifications() {
        SnoozeableHeadsUpNotificationSpace(
            stackScrollView = stackScrollView.get(),
            viewModel = rememberViewModel("HeadsUpNotifications") { viewModelFactory.create() },
        )
    }

    /**
     * @param burnInParams params to make this view adaptive to burn-in, `null` to disable burn-in
     *   adjustment
     */
    @Composable
    fun ContentScope.Notifications(
        areNotificationsVisible: Boolean,
        isShadeLayoutWide: Boolean,
        burnInParams: BurnInParameters?,
        modifier: Modifier = Modifier,
    ) {
        if (!areNotificationsVisible) {
            return
        }

        ConstrainedNotificationStack(
            stackScrollView = stackScrollView.get(),
            viewModel = rememberViewModel("Notifications") { viewModelFactory.create() },
            modifier =
                modifier
                    .fillMaxWidth()
                    .thenIf(isShadeLayoutWide) { Modifier.padding(top = 12.dp) }
                    .let {
                        if (burnInParams == null) {
                            it
                        } else {
                            it.burnInAware(viewModel = aodBurnInViewModel, params = burnInParams)
                        }
                    },
        )
    }
}
