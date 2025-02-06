/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume.dialog.ui.binder

import android.app.Dialog
import android.content.res.Resources
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.view.updatePadding
import com.android.internal.view.RotationPolicy
import com.android.systemui.common.ui.view.onApplyWindowInsets
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.util.kotlin.awaitCancellationThenDispose
import com.android.systemui.volume.SystemUIInterpolators
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ringer.ui.binder.VolumeDialogRingerViewBinder
import com.android.systemui.volume.dialog.settings.ui.binder.VolumeDialogSettingsButtonViewBinder
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSlidersViewBinder
import com.android.systemui.volume.dialog.ui.utils.JankListenerFactory
import com.android.systemui.volume.dialog.ui.utils.suspendAnimate
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import com.android.systemui.volume.dialog.utils.VolumeTracer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Binds the root view of the Volume Dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
@VolumeDialogScope
class VolumeDialogViewBinder
@Inject
constructor(
    @Main resources: Resources,
    private val viewModel: VolumeDialogViewModel,
    private val jankListenerFactory: JankListenerFactory,
    private val tracer: VolumeTracer,
    private val volumeDialogRingerViewBinder: VolumeDialogRingerViewBinder,
    private val slidersViewBinder: VolumeDialogSlidersViewBinder,
    private val settingsButtonViewBinder: VolumeDialogSettingsButtonViewBinder,
) {

    private val dialogShowAnimationDurationMs =
        resources.getInteger(R.integer.config_dialogShowAnimationDurationMs).toLong()
    private val dialogHideAnimationDurationMs =
        resources.getInteger(R.integer.config_dialogHideAnimationDurationMs).toLong()

    fun CoroutineScope.bind(dialog: Dialog) {
        val insets: MutableStateFlow<WindowInsets> =
            MutableStateFlow(WindowInsets.Builder().build())
        // Root view of the Volume Dialog.
        val root: MotionLayout = dialog.requireViewById(R.id.volume_dialog_root)

        animateVisibility(root, dialog, viewModel.dialogVisibilityModel)

        viewModel.dialogTitle.onEach { dialog.window?.setTitle(it) }.launchIn(this)
        viewModel.motionState
            .scan(0) { acc, motionState ->
                // don't animate the initial state
                root.transitionToState(motionState, animate = acc != 0)
                acc + 1
            }
            .launchIn(this)

        launch { root.viewTreeObserver.listenToComputeInternalInsets() }

        launch {
            root
                .onApplyWindowInsets { v, newInsets ->
                    val insetsValues = newInsets.getInsets(WindowInsets.Type.displayCutout())
                    v.updatePadding(
                        left = insetsValues.left,
                        top = insetsValues.top,
                        right = insetsValues.right,
                        bottom = insetsValues.bottom,
                    )
                    insets.value = newInsets
                    WindowInsets.CONSUMED
                }
                .awaitCancellationThenDispose()
        }

        with(volumeDialogRingerViewBinder) { bind(root) }
        with(slidersViewBinder) { bind(root) }
        with(settingsButtonViewBinder) { bind(root) }
    }

    private fun CoroutineScope.animateVisibility(
        view: View,
        dialog: Dialog,
        visibilityModel: Flow<VolumeDialogVisibilityModel>,
    ) {
        visibilityModel
            .mapLatest {
                when (it) {
                    is VolumeDialogVisibilityModel.Visible -> {
                        tracer.traceVisibilityEnd(it)
                        view.animateShow(
                            duration = dialogShowAnimationDurationMs,
                            translationX = calculateTranslationX(view),
                        )
                    }
                    is VolumeDialogVisibilityModel.Dismissed -> {
                        tracer.traceVisibilityEnd(it)
                        view.animateHide(
                            duration = dialogHideAnimationDurationMs,
                            translationX = calculateTranslationX(view),
                        )
                        dialog.dismiss()
                    }
                    is VolumeDialogVisibilityModel.Invisible -> {
                        // do nothing
                    }
                }
            }
            .launchIn(this)
    }

    private fun calculateTranslationX(view: View): Float? {
        return if (view.display.rotation == RotationPolicy.NATURAL_ROTATION) {
            if (view.isLayoutRtl) {
                -1
            } else {
                1
            } * view.width / 2f
        } else {
            null
        }
    }

    private suspend fun View.animateShow(duration: Long, translationX: Float?) {
        translationX?.let { setTranslationX(translationX) }
        alpha = 0f
        animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(duration)
            .setInterpolator(SystemUIInterpolators.LogDecelerateInterpolator())
            .suspendAnimate(jankListenerFactory.show(this, duration))
    }

    private suspend fun View.animateHide(duration: Long, translationX: Float?) {
        val animator =
            animate()
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(SystemUIInterpolators.LogAccelerateInterpolator())
        translationX?.let { animator.translationX(it) }
        animator.suspendAnimate(jankListenerFactory.dismiss(this, duration))
    }

    private suspend fun ViewTreeObserver.listenToComputeInternalInsets() =
        suspendCancellableCoroutine<Unit> { continuation ->
            val listener =
                ViewTreeObserver.OnComputeInternalInsetsListener { inoutInfo ->
                    viewModel.fillTouchableBounds(inoutInfo)
                }
            addOnComputeInternalInsetsListener(listener)
            continuation.invokeOnCancellation { removeOnComputeInternalInsetsListener(listener) }
        }

    private fun MotionLayout.transitionToState(newState: Int, animate: Boolean) {
        if (animate) {
            transitionToState(newState)
        } else {
            jumpToState(newState)
        }
    }
}
