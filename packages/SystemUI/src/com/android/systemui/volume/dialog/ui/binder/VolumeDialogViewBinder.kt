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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import com.android.internal.view.RotationPolicy
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.res.R
import com.android.systemui.volume.SystemUIInterpolators
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ringer.ui.binder.VolumeDialogRingerViewBinder
import com.android.systemui.volume.dialog.settings.ui.binder.VolumeDialogSettingsButtonViewBinder
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSlidersViewBinder
import com.android.systemui.volume.dialog.ui.VolumeDialogResources
import com.android.systemui.volume.dialog.ui.utils.JankListenerFactory
import com.android.systemui.volume.dialog.ui.utils.suspendAnimate
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogGravityViewModel
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogViewModel
import com.android.systemui.volume.dialog.utils.VolumeTracer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach

/** Binds the root view of the Volume Dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
@VolumeDialogScope
class VolumeDialogViewBinder
@Inject
constructor(
    private val volumeResources: VolumeDialogResources,
    private val gravityViewModel: VolumeDialogGravityViewModel,
    private val viewModelFactory: VolumeDialogViewModel.Factory,
    private val jankListenerFactory: JankListenerFactory,
    private val tracer: VolumeTracer,
    @VolumeDialog private val coroutineScope: CoroutineScope,
    private val volumeDialogRingerViewBinder: VolumeDialogRingerViewBinder,
    private val slidersViewBinder: VolumeDialogSlidersViewBinder,
    private val settingsButtonViewBinder: VolumeDialogSettingsButtonViewBinder,
) {

    fun bind(dialog: Dialog) {
        setupDialog(dialog)
        val view: View = dialog.requireViewById(R.id.volume_dialog_container)
        view.alpha = 0f
        view.repeatWhenAttached {
            view.viewModel(
                traceName = "VolumeDialogViewBinder",
                minWindowLifecycleState = WindowLifecycleState.ATTACHED,
                factory = { viewModelFactory.create() },
            ) { viewModel ->
                viewModel.dialogTitle.onEach { dialog.window?.setTitle(it) }.launchIn(this)

                animateVisibility(view, dialog, viewModel.dialogVisibilityModel)

                awaitCancellation()
            }
        }
        volumeDialogRingerViewBinder.bind(view)
        slidersViewBinder.bind(view)
        settingsButtonViewBinder.bind(view)
    }

    /** Configures [Window] for the [Dialog]. */
    private fun setupDialog(dialog: Dialog) {
        with(dialog.window!!) {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)

            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            setWindowAnimations(-1)
            setFormat(PixelFormat.TRANSLUCENT)

            attributes =
                attributes.apply {
                    title = "VolumeDialog" // Not the same as Window#setTitle
                }
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            gravityViewModel.dialogGravity.onEach { setGravity(it) }.launchIn(coroutineScope)
        }
        dialog.setContentView(R.layout.volume_dialog)
        dialog.setCanceledOnTouchOutside(true)
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

    private suspend fun calculateTranslationX(view: View): Float? {
        return if (view.display.rotation == RotationPolicy.NATURAL_ROTATION) {
            val dialogGravity = gravityViewModel.dialogGravity.first()
            val isGravityLeft = (dialogGravity and Gravity.LEFT) == Gravity.LEFT
            if (isGravityLeft) {
                -1
            } else {
                1
            } * view.width / 2.0f
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
}
