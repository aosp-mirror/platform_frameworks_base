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
import android.graphics.Rect
import android.graphics.Region
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewTreeObserver.InternalInsetsInfo
import androidx.constraintlayout.motion.widget.MotionLayout
import com.android.internal.view.RotationPolicy
import com.android.systemui.res.R
import com.android.systemui.util.children
import com.android.systemui.volume.SystemUIInterpolators
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ringer.ui.binder.VolumeDialogRingerViewBinder
import com.android.systemui.volume.dialog.settings.ui.binder.VolumeDialogSettingsButtonViewBinder
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSlidersViewBinder
import com.android.systemui.volume.dialog.ui.VolumeDialogResources
import com.android.systemui.volume.dialog.ui.utils.JankListenerFactory
import com.android.systemui.volume.dialog.ui.utils.suspendAnimate
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import com.android.systemui.volume.dialog.utils.VolumeTracer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    private val volumeResources: VolumeDialogResources,
    private val viewModel: VolumeDialogViewModel,
    private val jankListenerFactory: JankListenerFactory,
    private val tracer: VolumeTracer,
    private val volumeDialogRingerViewBinder: VolumeDialogRingerViewBinder,
    private val slidersViewBinder: VolumeDialogSlidersViewBinder,
    private val settingsButtonViewBinder: VolumeDialogSettingsButtonViewBinder,
) {

    fun CoroutineScope.bind(dialog: Dialog) {
        // Root view of the Volume Dialog.
        val root: MotionLayout = dialog.requireViewById(R.id.volume_dialog_root)
        root.alpha = 0f

        animateVisibility(root, dialog, viewModel.dialogVisibilityModel)

        viewModel.dialogTitle.onEach { dialog.window?.setTitle(it) }.launchIn(this)
        viewModel.motionState
            .scan(0) { acc, motionState ->
                // don't animate the initial state
                root.transitionToState(motionState, animate = acc != 0)
                acc + 1
            }
            .launchIn(this)

        launch { root.viewTreeObserver.computeInternalInsetsListener(root) }

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
                        calculateTranslationX(view)?.let(view::setTranslationX)
                        view.animateShow(volumeResources.dialogShowDurationMillis.first())
                    }
                    is VolumeDialogVisibilityModel.Dismissed -> {
                        tracer.traceVisibilityEnd(it)
                        view.animateHide(
                            duration = volumeResources.dialogHideDurationMillis.first(),
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

    private suspend fun View.animateShow(duration: Long) {
        animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(duration)
            .setInterpolator(SystemUIInterpolators.LogDecelerateInterpolator())
            .suspendAnimate(jankListenerFactory.show(this, duration))
        /* TODO(b/369993851)
        .withEndAction(Runnable {
            if (!Prefs.getBoolean(mContext, Prefs.Key.TOUCHED_RINGER_TOGGLE, false)) {
                if (mRingerIcon != null) {
                    mRingerIcon.postOnAnimationDelayed(
                        getSinglePressFor(mRingerIcon), 1500
                    )
                }
            }
        })
         */
    }

    private suspend fun View.animateHide(duration: Long, translationX: Float?) {
        val animator =
            animate()
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(SystemUIInterpolators.LogAccelerateInterpolator())
        /*  TODO(b/369993851)
        .withEndAction(
            Runnable {
                mHandler.postDelayed(
                    Runnable {
                        hideRingerDrawer()

                    },
                    50
                )
            }
        )
         */
        if (translationX != null) {
            animator.translationX(translationX)
        }
        animator.suspendAnimate(jankListenerFactory.dismiss(this, duration))
    }

    private suspend fun ViewTreeObserver.computeInternalInsetsListener(viewGroup: ViewGroup) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val listener =
                ViewTreeObserver.OnComputeInternalInsetsListener { inoutInfo ->
                    viewGroup.fillTouchableBounds(inoutInfo)
                }
            addOnComputeInternalInsetsListener(listener)
            continuation.invokeOnCancellation { removeOnComputeInternalInsetsListener(listener) }
        }

    private fun ViewGroup.fillTouchableBounds(internalInsetsInfo: InternalInsetsInfo) {
        for (child in children) {
            val boundsRect = Rect()
            internalInsetsInfo.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_REGION)

            child.getBoundsInWindow(boundsRect, false)
            internalInsetsInfo.touchableRegion.op(boundsRect, Region.Op.UNION)
        }
        val boundsRect = Rect()
        getBoundsInWindow(boundsRect, false)
    }

    private fun MotionLayout.transitionToState(newState: Int, animate: Boolean) {
        if (animate) {
            transitionToState(newState)
        } else {
            jumpToState(newState)
        }
    }
}
