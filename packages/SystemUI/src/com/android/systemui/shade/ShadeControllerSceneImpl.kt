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
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.dagger.ShadeTouchLog
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.shade.ShadeController.ShadeVisibilityListener
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.NotificationShadeWindowController
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
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val notificationStackScrollLayout: NotificationStackScrollLayout,
    @ShadeTouchLog private val touchLog: LogBuffer,
    commandQueue: CommandQueue,
    statusBarKeyguardViewManager: StatusBarKeyguardViewManager,
    notificationShadeWindowController: NotificationShadeWindowController,
    assistManagerLazy: Lazy<AssistManager>,
) :
    BaseShadeControllerImpl(
        touchLog,
        commandQueue,
        statusBarKeyguardViewManager,
        notificationShadeWindowController,
        assistManagerLazy,
    ) {

    init {
        scope.launch {
            shadeInteractor.isAnyExpanded.collect {
                if (!it) {
                    runPostCollapseActions()
                }
            }
        }
    }

    override fun isShadeEnabled() = shadeInteractor.isShadeEnabled.value

    override fun isShadeFullyOpen(): Boolean = shadeInteractor.isAnyFullyExpanded.value

    override fun isExpandingOrCollapsing(): Boolean =
        shadeInteractor.anyExpansion.value > 0f && shadeInteractor.anyExpansion.value < 1f

    override fun instantExpandShade() {
        // Do nothing
    }

    override fun instantCollapseShade() {
        // TODO(b/315921512) add support for instant transition
        sceneInteractor.changeScene(
            SceneModel(getCollapseDestinationScene(), "instant"),
            "hide shade"
        )
    }

    override fun animateCollapseShade(
        flags: Int,
        force: Boolean,
        delayed: Boolean,
        speedUpFactor: Float
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
            sceneInteractor.changeScene(
                SceneModel(SceneKey.Shade, null),
                "ShadeController.animateExpandShade"
            )
            if (delayed) {
                scope.launch {
                    delay(125)
                    animateCollapseShadeInternal()
                }
            } else {
                animateCollapseShadeInternal()
            }
        }
    }

    private fun animateCollapseShadeInternal() {
        sceneInteractor.changeScene(
            SceneModel(getCollapseDestinationScene(), "ShadeController.animateCollapseShade"),
            "ShadeController.animateCollapseShade"
        )
    }

    private fun getCollapseDestinationScene(): SceneKey {
        return if (deviceEntryInteractor.isDeviceEntered.value) {
            SceneKey.Gone
        } else {
            SceneKey.Lockscreen
        }
    }

    override fun cancelExpansionAndCollapseShade() {
        // TODO do we need to actually cancel the touch session?
        animateCollapseShade()
    }

    override fun closeShadeIfOpen(): Boolean {
        if (shadeInteractor.isAnyExpanded.value) {
            commandQueue.animateCollapsePanels(
                CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL,
                true /* force */
            )
            assistManagerLazy.get().hideAssist()
        }
        return false
    }

    override fun collapseShade() {
        animateCollapseShadeForcedDelayed()
    }

    override fun collapseShade(animate: Boolean) {
        if (animate) {
            animateCollapseShade()
        } else {
            instantCollapseShade()
        }
    }

    override fun collapseOnMainThread() {
        // TODO if this works with delegation alone, we can deprecate and delete
        collapseShade()
    }

    override fun expandToNotifications() {
        sceneInteractor.changeScene(
            SceneModel(SceneKey.Shade, null),
            "ShadeController.animateExpandShade"
        )
    }

    override fun expandToQs() {
        sceneInteractor.changeScene(
            SceneModel(SceneKey.QuickSettings, null),
            "ShadeController.animateExpandQs"
        )
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

    override fun postAnimateCollapseShade() {
        animateCollapseShade()
    }

    override fun postAnimateForceCollapseShade() {
        animateCollapseShadeForced()
    }

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

    override fun makeExpandedInvisible() {
        // Do nothing
    }

    override fun makeExpandedVisible(force: Boolean) {
        // Do nothing
    }

    override fun isExpandedVisible(): Boolean {
        return sceneInteractor.desiredScene.value.key != SceneKey.Gone
    }

    override fun onStatusBarTouch(event: MotionEvent) {
        // The only call to this doesn't happen with KeyguardShadeMigrationNssl enabled
        throw UnsupportedOperationException()
    }
}
