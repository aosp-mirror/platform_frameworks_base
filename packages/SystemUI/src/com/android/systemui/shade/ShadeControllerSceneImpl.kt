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

package com.android.systemui.shade

import android.view.MotionEvent
import com.android.systemui.assist.AssistManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.TransitionKeys.Instant
import com.android.systemui.scene.shared.model.TransitionKeys.SlightlyFasterShadeCollapse
import com.android.systemui.shade.ShadeController.ShadeVisibilityListener
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.NotificationShadeWindowController
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Implementation of ShadeController backed by scenes instead of NPVC.
 *
 * TODO(b/300258424) rename to ShadeControllerImpl and inline/delete all the deprecated methods
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class ShadeControllerSceneImpl
@Inject
constructor(
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val scope: CoroutineScope,
    private val shadeInteractor: ShadeInteractor,
    private val sceneInteractor: SceneInteractor,
    private val notificationStackScrollLayout: NotificationStackScrollLayout,
    private val vibratorHelper: VibratorHelper,
    commandQueue: CommandQueue,
    statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    notificationShadeWindowController: NotificationShadeWindowController,
    assistManagerLazy: Lazy<AssistManager>,
) :
    BaseShadeControllerImpl(
        commandQueue,
        statusBarKeyguardViewManager,
        notificationShadeWindowController,
        assistManagerLazy,
    ) {

    init {
        scope.launch {
            shadeInteractor.isAnyExpanded.collect {
                if (!it) {
                    withContext(mainDispatcher) { runPostCollapseActions() }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun isShadeEnabled() = shadeInteractor.isShadeEnabled.value

    @Deprecated("Deprecated in Java")
    override fun isShadeFullyOpen(): Boolean = shadeInteractor.isAnyFullyExpanded.value

    @Deprecated("Deprecated in Java")
    override fun isExpandingOrCollapsing(): Boolean = shadeInteractor.isUserInteracting.value

    @Deprecated("Deprecated in Java")
    override fun instantExpandShade() {
        // Do nothing
    }

    override fun instantCollapseShade() {
        shadeInteractor.collapseNotificationsShade(
            loggingReason = "ShadeControllerSceneImpl.instantCollapseShade",
            transitionKey = Instant,
        )
    }

    override fun animateCollapseShade(
        flags: Int,
        force: Boolean,
        delayed: Boolean,
        speedUpFactor: Float,
    ) {
        if (!force && !shadeInteractor.isAnyExpanded.value) {
            runPostCollapseActions()
            return
        }
        if (
            shadeInteractor.isAnyExpanded.value &&
                flags and CommandQueue.FLAG_EXCLUDE_NOTIFICATION_PANEL == 0
        ) {
            // release focus immediately to kick off focus change transition
            notificationShadeWindowController.setNotificationShadeFocusable(false)
            notificationStackScrollLayout.cancelExpandHelper()
            if (delayed) {
                scope.launch {
                    delay(125)
                    withContext(mainDispatcher) { animateCollapseShadeInternal() }
                }
            } else {
                animateCollapseShadeInternal()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun collapseWithDuration(animationDuration: Int) {
        // TODO(b/300258424) inline this. The only caller uses the default duration.
        animateCollapseShade()
    }

    private fun animateCollapseShadeInternal() {
        // TODO(b/336581871): add sceneState?
        shadeInteractor.collapseEitherShade(
            loggingReason = "ShadeController.animateCollapseShade",
            transitionKey = SlightlyFasterShadeCollapse,
        )
    }

    override fun cancelExpansionAndCollapseShade() {
        // TODO do we need to actually cancel the touch session?
        animateCollapseShade()
    }

    @Deprecated("Deprecated in Java")
    override fun closeShadeIfOpen(): Boolean {
        if (shadeInteractor.isAnyExpanded.value) {
            commandQueue.animateCollapsePanels(
                CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL,
                true, /* force */
            )
            assistManagerLazy.get().hideAssist()
        }
        return false
    }

    override fun collapseShade() {
        animateCollapseShadeForcedDelayed()
    }

    @Deprecated("Deprecated in Java")
    override fun collapseShade(animate: Boolean) {
        if (animate) {
            animateCollapseShade()
        } else {
            instantCollapseShade()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun collapseOnMainThread() {
        // TODO if this works with delegation alone, we can deprecate and delete
        collapseShade()
    }

    override fun expandToNotifications() {
        shadeInteractor.expandNotificationsShade("ShadeController.animateExpandShade")
    }

    override fun expandToQs() {
        shadeInteractor.expandQuickSettingsShade("ShadeController.animateExpandQs")
    }

    override fun setVisibilityListener(listener: ShadeVisibilityListener) {
        scope.launch {
            sceneInteractor.isVisible.collect { isVisible ->
                withContext(mainDispatcher) { listener.expandedVisibleChanged(isVisible) }
            }
        }
    }

    @ExperimentalCoroutinesApi
    override fun collapseShadeForActivityStart() {
        if (shadeInteractor.isAnyExpanded.value) {
            animateCollapseShadeForcedDelayed()
        } else {
            runPostCollapseActions()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun postAnimateCollapseShade() {
        animateCollapseShade()
    }

    @Deprecated("Deprecated in Java")
    override fun postAnimateForceCollapseShade() {
        animateCollapseShadeForced()
    }

    @Deprecated("Deprecated in Java")
    override fun postAnimateExpandQs() {
        expandToQs()
    }

    override fun postOnShadeExpanded(action: Runnable) {
        // TODO verify that clicking "reply" in a work profile notification launches the app
        // TODO verify that there's not a way to replace and deprecate this method
        scope.launch {
            shadeInteractor.isAnyFullyExpanded.first { it }
            action.run()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun makeExpandedInvisible() {
        // Do nothing
    }

    @Deprecated("Deprecated in Java")
    override fun makeExpandedVisible(force: Boolean) {
        // Do nothing
    }

    @Deprecated("Deprecated in Java")
    override fun isExpandedVisible(): Boolean {
        return sceneInteractor.currentScene.value != Scenes.Gone ||
            sceneInteractor.currentOverlays.value.isNotEmpty()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusBarTouch(event: MotionEvent) {
        // The only call to this doesn't happen with MigrateClocksToBlueprint.isEnabled enabled
        throw UnsupportedOperationException()
    }

    override fun performHapticFeedback(constant: Int) {
        vibratorHelper.performHapticFeedback(notificationStackScrollLayout, constant)
    }
}
