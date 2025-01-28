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

package com.android.systemui.volume.dialog.ringer.ui.binder

import android.animation.ArgbEvaluator
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import androidx.annotation.LayoutRes
import androidx.compose.ui.util.fastForEachIndexed
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.R as internalR
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ringer.ui.util.VolumeDialogRingerDrawerTransitionListener
import com.android.systemui.volume.dialog.ringer.ui.util.updateCloseState
import com.android.systemui.volume.dialog.ringer.ui.util.updateOpenState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerButtonUiModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerButtonViewModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerDrawerState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerViewModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerViewModelState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.VolumeDialogRingerDrawerViewModel
import com.android.systemui.volume.dialog.ui.utils.suspendAnimate
import javax.inject.Inject
import kotlin.properties.Delegates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

private const val CLOSE_DRAWER_DELAY = 300L
// Ensure roundness and color of button is updated when progress is changed by a minimum fraction.
private const val BUTTON_MIN_VISIBLE_CHANGE = 0.05F

@VolumeDialogScope
class VolumeDialogRingerViewBinder
@Inject
constructor(private val viewModel: VolumeDialogRingerDrawerViewModel) {
    private val roundnessSpringForce =
        SpringForce(1F).apply {
            stiffness = 800F
            dampingRatio = 0.6F
        }
    private val colorSpringForce =
        SpringForce(1F).apply {
            stiffness = 3800F
            dampingRatio = 1F
        }
    private val rgbEvaluator = ArgbEvaluator()

    fun CoroutineScope.bind(view: View) {
        val volumeDialogBackgroundView = view.requireViewById<View>(R.id.volume_dialog_background)
        val ringerBackgroundView = view.requireViewById<View>(R.id.ringer_buttons_background)
        val drawerContainer = view.requireViewById<MotionLayout>(R.id.volume_ringer_drawer)
        val unselectedButtonUiModel = RingerButtonUiModel.getUnselectedButton(view.context)
        val selectedButtonUiModel = RingerButtonUiModel.getSelectedButton(view.context)
        val volumeDialogBgSmallRadius =
            view.context.resources.getDimensionPixelSize(
                R.dimen.volume_dialog_background_square_corner_radius
            )
        val volumeDialogBgFullRadius =
            view.context.resources.getDimensionPixelSize(
                R.dimen.volume_dialog_background_corner_radius
            )
        val bottomDefaultRadius = volumeDialogBgFullRadius.toFloat()
        val bottomCornerRadii =
            floatArrayOf(
                0F,
                0F,
                0F,
                0F,
                bottomDefaultRadius,
                bottomDefaultRadius,
                bottomDefaultRadius,
                bottomDefaultRadius,
            )
        var backgroundAnimationProgress: Float by
            Delegates.observable(0F) { _, _, progress ->
                ringerBackgroundView.applyCorners(
                    fullRadius = volumeDialogBgFullRadius,
                    diff = volumeDialogBgFullRadius - volumeDialogBgSmallRadius,
                    progress,
                    isBottom = false,
                )
                volumeDialogBackgroundView.applyCorners(
                    fullRadius = volumeDialogBgFullRadius,
                    diff = volumeDialogBgFullRadius - volumeDialogBgSmallRadius,
                    progress,
                    isBottom = true,
                )
            }
        val ringerDrawerTransitionListener = VolumeDialogRingerDrawerTransitionListener {
            backgroundAnimationProgress = it
        }
        drawerContainer.setTransitionListener(ringerDrawerTransitionListener)
        volumeDialogBackgroundView.background = volumeDialogBackgroundView.background.mutate()
        ringerBackgroundView.background = ringerBackgroundView.background.mutate()

        viewModel.ringerViewModel
            .mapLatest { ringerState ->
                when (ringerState) {
                    is RingerViewModelState.Available -> {
                        val uiModel = ringerState.uiModel

                        // Set up view background and visibility
                        drawerContainer.visibility = View.VISIBLE
                        when (uiModel.drawerState) {
                            is RingerDrawerState.Initial -> {
                                (volumeDialogBackgroundView.background as GradientDrawable)
                                    .cornerRadii = bottomCornerRadii
                                drawerContainer.animateAndBindDrawerButtons(
                                    viewModel,
                                    uiModel,
                                    selectedButtonUiModel,
                                    unselectedButtonUiModel,
                                )
                                ringerDrawerTransitionListener.setProgressChangeEnabled(true)
                                drawerContainer.closeDrawer(
                                    ringerBackgroundView,
                                    uiModel.currentButtonIndex,
                                    ringerState.orientation,
                                )
                            }
                            is RingerDrawerState.Closed -> {
                                if (
                                    uiModel.selectedButton.ringerMode ==
                                        uiModel.drawerState.currentMode
                                ) {
                                    drawerContainer.animateAndBindDrawerButtons(
                                        viewModel,
                                        uiModel,
                                        selectedButtonUiModel,
                                        unselectedButtonUiModel,
                                        onProgressChanged = { progress, isReverse ->
                                            // Let's make button progress when switching matches
                                            // motionLayout transition progress. When full
                                            // radius,
                                            // progress is 0.0. When small radius, progress is
                                            // 1.0.
                                            backgroundAnimationProgress =
                                                if (isReverse) {
                                                    1F - progress
                                                } else {
                                                    progress
                                                }
                                        },
                                    ) {
                                        if (
                                            uiModel.currentButtonIndex ==
                                                uiModel.availableButtons.size - 1
                                        ) {
                                            ringerDrawerTransitionListener.setProgressChangeEnabled(
                                                false
                                            )
                                        } else {
                                            ringerDrawerTransitionListener.setProgressChangeEnabled(
                                                true
                                            )
                                        }
                                        drawerContainer.closeDrawer(
                                            ringerBackgroundView,
                                            uiModel.currentButtonIndex,
                                            ringerState.orientation,
                                        )
                                    }
                                }
                            }
                            is RingerDrawerState.Open -> {
                                drawerContainer.animateAndBindDrawerButtons(
                                    viewModel,
                                    uiModel,
                                    selectedButtonUiModel,
                                    unselectedButtonUiModel,
                                )
                                // Open drawer
                                if (
                                    uiModel.currentButtonIndex == uiModel.availableButtons.size - 1
                                ) {
                                    ringerDrawerTransitionListener.setProgressChangeEnabled(false)
                                } else {
                                    ringerDrawerTransitionListener.setProgressChangeEnabled(true)
                                }
                                updateOpenState(
                                    drawerContainer,
                                    ringerState.orientation,
                                    ringerBackgroundView,
                                )
                                drawerContainer.transitionToState(
                                    R.id.volume_dialog_ringer_drawer_open
                                )
                                volumeDialogBackgroundView.background =
                                    volumeDialogBackgroundView.background.mutate()
                                ringerBackgroundView.background =
                                    ringerBackgroundView.background.mutate()
                            }
                        }
                    }
                    is RingerViewModelState.Unavailable -> {
                        drawerContainer.visibility = View.GONE
                        volumeDialogBackgroundView.setBackgroundResource(
                            R.drawable.volume_dialog_background
                        )
                    }
                }
            }
            .launchIn(this)
    }

    private suspend fun MotionLayout.animateAndBindDrawerButtons(
        viewModel: VolumeDialogRingerDrawerViewModel,
        uiModel: RingerViewModel,
        selectedButtonUiModel: RingerButtonUiModel,
        unselectedButtonUiModel: RingerButtonUiModel,
        onProgressChanged: (Float, Boolean) -> Unit = { _, _ -> },
        onAnimationEnd: Runnable? = null,
    ) {
        ensureChildCount(R.layout.volume_ringer_button, uiModel.availableButtons.size)
        if (
            uiModel.drawerState is RingerDrawerState.Closed &&
                uiModel.drawerState.currentMode != uiModel.drawerState.previousMode
        ) {
            val count = uiModel.availableButtons.size
            val selectedButton =
                getChildAt(count - uiModel.currentButtonIndex)
                    .requireViewById<ImageButton>(R.id.volume_drawer_button)
            val previousIndex =
                uiModel.availableButtons.indexOfFirst {
                    it.ringerMode == uiModel.drawerState.previousMode
                }
            val unselectedButton =
                getChildAt(count - previousIndex)
                    .requireViewById<ImageButton>(R.id.volume_drawer_button)
            // We only need to execute on roundness animation end and volume dialog background
            // progress update once because these changes should be applied once on volume dialog
            // background and ringer drawer views.
            coroutineScope {
                val selectedCornerRadius =
                    (selectedButton.background as GradientDrawable).cornerRadius
                if (selectedCornerRadius.toInt() != selectedButtonUiModel.cornerRadius) {
                    launch {
                        selectedButton.animateTo(
                            selectedButtonUiModel,
                            if (uiModel.currentButtonIndex == count - 1) {
                                onProgressChanged
                            } else {
                                { _, _ -> }
                            },
                        )
                    }
                }
                val unselectedCornerRadius =
                    (unselectedButton.background as GradientDrawable).cornerRadius
                if (unselectedCornerRadius.toInt() != unselectedButtonUiModel.cornerRadius) {
                    launch {
                        unselectedButton.animateTo(
                            unselectedButtonUiModel,
                            if (previousIndex == count - 1) {
                                onProgressChanged
                            } else {
                                { _, _ -> }
                            },
                        )
                    }
                }
                launch {
                    delay(CLOSE_DRAWER_DELAY)
                    bindButtons(viewModel, uiModel, onAnimationEnd, isAnimated = true)
                }
            }
        } else {
            bindButtons(viewModel, uiModel, onAnimationEnd)
        }
    }

    private fun MotionLayout.bindButtons(
        viewModel: VolumeDialogRingerDrawerViewModel,
        uiModel: RingerViewModel,
        onAnimationEnd: Runnable? = null,
        isAnimated: Boolean = false,
    ) {
        val count = uiModel.availableButtons.size
        uiModel.availableButtons.fastForEachIndexed { index, ringerButton ->
            val view = getChildAt(count - index)
            val isOpen = uiModel.drawerState is RingerDrawerState.Open
            if (index == uiModel.currentButtonIndex) {
                view.bindDrawerButton(
                    if (isOpen) ringerButton else uiModel.selectedButton,
                    viewModel,
                    isOpen,
                    isSelected = true,
                    isAnimated = isAnimated,
                )
            } else {
                view.bindDrawerButton(ringerButton, viewModel, isOpen, isAnimated = isAnimated)
            }
        }
        onAnimationEnd?.run()
    }

    private fun View.bindDrawerButton(
        buttonViewModel: RingerButtonViewModel,
        viewModel: VolumeDialogRingerDrawerViewModel,
        isOpen: Boolean,
        isSelected: Boolean = false,
        isAnimated: Boolean = false,
    ) {
        val ringerContentDesc = context.getString(buttonViewModel.contentDescriptionResId)
        with(requireViewById<ImageButton>(R.id.volume_drawer_button)) {
            setImageResource(buttonViewModel.imageResId)
            contentDescription =
                if (isSelected && !isOpen) {
                    context.getString(
                        R.string.volume_ringer_drawer_closed_content_description,
                        ringerContentDesc,
                    )
                } else {
                    ringerContentDesc
                }
            if (isSelected && !isAnimated) {
                setBackgroundResource(R.drawable.volume_drawer_selection_bg)
                setColorFilter(context.getColor(internalR.color.materialColorOnPrimary))
                background = background.mutate()
            } else if (!isAnimated) {
                setBackgroundResource(R.drawable.volume_ringer_item_bg)
                setColorFilter(context.getColor(internalR.color.materialColorOnSurface))
                background = background.mutate()
            }
            setOnClickListener {
                viewModel.onRingerButtonClicked(buttonViewModel.ringerMode, isSelected)
            }
        }
    }

    private fun MotionLayout.ensureChildCount(@LayoutRes viewLayoutId: Int, count: Int) {
        val childCountDelta = childCount - count - 1
        when {
            childCountDelta > 0 -> {
                removeViews(0, childCountDelta)
            }
            childCountDelta < 0 -> {
                val inflater = LayoutInflater.from(context)
                repeat(-childCountDelta) {
                    inflater.inflate(viewLayoutId, this, true)
                    getChildAt(childCount - 1).id = View.generateViewId()
                }
            }
        }
    }

    private fun MotionLayout.closeDrawer(
        ringerBackground: View,
        selectedIndex: Int,
        orientation: Int,
    ) {
        setTransition(R.id.close_to_open_transition)
        updateCloseState(this, selectedIndex, orientation, ringerBackground)
        transitionToState(R.id.volume_dialog_ringer_drawer_close)
    }

    private suspend fun ImageButton.animateTo(
        ringerButtonUiModel: RingerButtonUiModel,
        onProgressChanged: (Float, Boolean) -> Unit = { _, _ -> },
    ) {
        val roundnessAnimation =
            SpringAnimation(FloatValueHolder(0F), 1F).setSpring(roundnessSpringForce)
        val colorAnimation = SpringAnimation(FloatValueHolder(0F), 1F).setSpring(colorSpringForce)
        val radius = (background as GradientDrawable).cornerRadius
        val cornerRadiusDiff =
            ringerButtonUiModel.cornerRadius - (background as GradientDrawable).cornerRadius

        roundnessAnimation.minimumVisibleChange = BUTTON_MIN_VISIBLE_CHANGE
        colorAnimation.minimumVisibleChange = BUTTON_MIN_VISIBLE_CHANGE
        coroutineScope {
            launch {
                colorAnimation.suspendAnimate { value ->
                    val currentIconColor =
                        rgbEvaluator.evaluate(
                            value.coerceIn(0F, 1F),
                            imageTintList?.colors?.first(),
                            ringerButtonUiModel.tintColor,
                        ) as Int
                    val currentBgColor =
                        rgbEvaluator.evaluate(
                            value.coerceIn(0F, 1F),
                            (background as GradientDrawable).color?.colors?.get(0),
                            ringerButtonUiModel.backgroundColor,
                        ) as Int

                    (background as GradientDrawable).setColor(currentBgColor)
                    background.invalidateSelf()
                    setColorFilter(currentIconColor)
                }
            }
            roundnessAnimation.suspendAnimate { value ->
                onProgressChanged(value, cornerRadiusDiff > 0F)
                (background as GradientDrawable).cornerRadius = radius + value * cornerRadiusDiff
                background.invalidateSelf()
            }
        }
    }

    private fun View.applyCorners(fullRadius: Int, diff: Int, progress: Float, isBottom: Boolean) {
        val radius = fullRadius - progress * diff
        (background as GradientDrawable).cornerRadii =
            if (isBottom) {
                floatArrayOf(0F, 0F, 0F, 0F, radius, radius, radius, radius)
            } else {
                FloatArray(8) { radius }
            }
        background.invalidateSelf()
    }
}
