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
import androidx.constraintlayout.widget.ConstraintSet
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.internal.R as internalR
import com.android.settingslib.Utils
import com.android.systemui.res.R
import com.android.systemui.util.children
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ringer.ui.util.VolumeDialogRingerDrawerTransitionListener
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private const val CLOSE_DRAWER_DELAY = 300L

@VolumeDialogScope
class VolumeDialogRingerViewBinder
@Inject
constructor(private val viewModel: VolumeDialogRingerDrawerViewModel) {
    private val roundnessSpringForce =
        SpringForce(0F).apply {
            stiffness = 800F
            dampingRatio = 0.6F
        }
    private val colorSpringForce =
        SpringForce(0F).apply {
            stiffness = 3800F
            dampingRatio = 1F
        }
    private val rgbEvaluator = ArgbEvaluator()

    fun CoroutineScope.bind(view: View) {
        val volumeDialogBackgroundView = view.requireViewById<View>(R.id.volume_dialog_background)
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
        var backgroundAnimationProgress: Float by
            Delegates.observable(0F) { _, _, progress ->
                volumeDialogBackgroundView.applyCorners(
                    fullRadius = volumeDialogBgFullRadius,
                    diff = volumeDialogBgFullRadius - volumeDialogBgSmallRadius,
                    progress,
                )
            }
        val ringerDrawerTransitionListener = VolumeDialogRingerDrawerTransitionListener {
            backgroundAnimationProgress = it
        }
        drawerContainer.setTransitionListener(ringerDrawerTransitionListener)
        volumeDialogBackgroundView.background = volumeDialogBackgroundView.background.mutate()
        viewModel.ringerViewModel
            .onEach { ringerState ->
                when (ringerState) {
                    is RingerViewModelState.Available -> {
                        val uiModel = ringerState.uiModel

                        // Set up view background and visibility
                        drawerContainer.visibility = View.VISIBLE
                        when (uiModel.drawerState) {
                            is RingerDrawerState.Initial -> {
                                drawerContainer.animateAndBindDrawerButtons(
                                    viewModel,
                                    uiModel,
                                    selectedButtonUiModel,
                                    unselectedButtonUiModel,
                                )
                                ringerDrawerTransitionListener.setProgressChangeEnabled(true)
                                drawerContainer.closeDrawer(uiModel.currentButtonIndex)
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
                                            // motionLayout transition progress. When full radius,
                                            // progress is 0.0. When small radius, progress is 1.0.
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
                                        drawerContainer.closeDrawer(uiModel.currentButtonIndex)
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
                                drawerContainer.transitionToState(
                                    R.id.volume_dialog_ringer_drawer_open
                                )
                                volumeDialogBackgroundView.background =
                                    volumeDialogBackgroundView.background.mutate()
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
                getChildAt(count - uiModel.currentButtonIndex - 1)
                    .requireViewById<ImageButton>(R.id.volume_drawer_button)
            val previousIndex =
                uiModel.availableButtons.indexOfFirst {
                    it?.ringerMode == uiModel.drawerState.previousMode
                }
            val unselectedButton =
                getChildAt(count - previousIndex - 1)
                    .requireViewById<ImageButton>(R.id.volume_drawer_button)

            // On roundness animation end.
            val roundnessAnimationEndListener =
                DynamicAnimation.OnAnimationEndListener { _, _, _, _ ->
                    postDelayed(
                        { bindButtons(viewModel, uiModel, onAnimationEnd, isAnimated = true) },
                        CLOSE_DRAWER_DELAY,
                    )
                }
            // We only need to execute on roundness animation end and volume dialog background
            // progress update once because these changes should be applied once on volume dialog
            // background and ringer drawer views.
            selectedButton.animateTo(
                selectedButtonUiModel,
                if (uiModel.currentButtonIndex == count - 1) {
                    onProgressChanged
                } else {
                    { _, _ -> }
                },
                roundnessAnimationEndListener,
            )
            unselectedButton.animateTo(
                unselectedButtonUiModel,
                if (previousIndex == count - 1) {
                    onProgressChanged
                } else {
                    { _, _ -> }
                },
            )
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
            ringerButton?.let {
                val view = getChildAt(count - index - 1)
                val isOpen = uiModel.drawerState is RingerDrawerState.Open
                if (index == uiModel.currentButtonIndex) {
                    view.bindDrawerButton(
                        if (isOpen) it else uiModel.selectedButton,
                        viewModel,
                        isOpen,
                        isSelected = true,
                        isAnimated = isAnimated,
                    )
                } else {
                    view.bindDrawerButton(it, viewModel, isOpen, isAnimated = isAnimated)
                }
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
                setColorFilter(
                    Utils.getColorAttrDefaultColor(context, internalR.attr.materialColorOnPrimary)
                )
                background = background.mutate()
            } else if (!isAnimated) {
                setBackgroundResource(R.drawable.volume_ringer_item_bg)
                setColorFilter(
                    Utils.getColorAttrDefaultColor(context, internalR.attr.materialColorOnSurface)
                )
                background = background.mutate()
            }
            setOnClickListener {
                viewModel.onRingerButtonClicked(buttonViewModel.ringerMode, isSelected)
            }
        }
    }

    private fun MotionLayout.ensureChildCount(@LayoutRes viewLayoutId: Int, count: Int) {
        val childCountDelta = childCount - count
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
                cloneConstraintSet(R.id.volume_dialog_ringer_drawer_open)
                    .adjustOpenConstraintsForDrawer(this)
            }
        }
    }

    private fun MotionLayout.closeDrawer(selectedIndex: Int) {
        setTransition(R.id.close_to_open_transition)
        cloneConstraintSet(R.id.volume_dialog_ringer_drawer_close)
            .adjustClosedConstraintsForDrawer(selectedIndex, this)
        transitionToState(R.id.volume_dialog_ringer_drawer_close)
    }

    private fun ConstraintSet.adjustOpenConstraintsForDrawer(motionLayout: MotionLayout) {
        motionLayout.children.forEachIndexed { index, button ->
            setButtonPositionConstraints(motionLayout, index, button)
            setAlpha(button.id, 1.0F)
            constrainWidth(
                button.id,
                motionLayout.context.resources.getDimensionPixelSize(
                    R.dimen.volume_dialog_ringer_drawer_button_size
                ),
            )
            constrainHeight(
                button.id,
                motionLayout.context.resources.getDimensionPixelSize(
                    R.dimen.volume_dialog_ringer_drawer_button_size
                ),
            )
            if (index != motionLayout.childCount - 1) {
                setMargin(
                    button.id,
                    ConstraintSet.BOTTOM,
                    motionLayout.context.resources.getDimensionPixelSize(
                        R.dimen.volume_dialog_components_spacing
                    ),
                )
            }
        }
        motionLayout.updateState(R.id.volume_dialog_ringer_drawer_open, this)
    }

    private fun ConstraintSet.adjustClosedConstraintsForDrawer(
        selectedIndex: Int,
        motionLayout: MotionLayout,
    ) {
        motionLayout.children.forEachIndexed { index, button ->
            setButtonPositionConstraints(motionLayout, index, button)
            constrainWidth(
                button.id,
                motionLayout.context.resources.getDimensionPixelSize(
                    R.dimen.volume_dialog_ringer_drawer_button_size
                ),
            )
            if (selectedIndex != motionLayout.childCount - index - 1) {
                setAlpha(button.id, 0.0F)
                constrainHeight(button.id, 0)
                setMargin(button.id, ConstraintSet.BOTTOM, 0)
            } else {
                setAlpha(button.id, 1.0F)
                constrainHeight(
                    button.id,
                    motionLayout.context.resources.getDimensionPixelSize(
                        R.dimen.volume_dialog_ringer_drawer_button_size
                    ),
                )
            }
        }
        motionLayout.updateState(R.id.volume_dialog_ringer_drawer_close, this)
    }

    private fun ConstraintSet.setButtonPositionConstraints(
        motionLayout: MotionLayout,
        index: Int,
        button: View,
    ) {
        if (motionLayout.getChildAt(index - 1) == null) {
            connect(button.id, ConstraintSet.TOP, motionLayout.id, ConstraintSet.TOP)
        } else {
            connect(
                button.id,
                ConstraintSet.TOP,
                motionLayout.getChildAt(index - 1).id,
                ConstraintSet.BOTTOM,
            )
        }

        if (motionLayout.getChildAt(index + 1) == null) {
            connect(button.id, ConstraintSet.BOTTOM, motionLayout.id, ConstraintSet.BOTTOM)
        } else {
            connect(
                button.id,
                ConstraintSet.BOTTOM,
                motionLayout.getChildAt(index + 1).id,
                ConstraintSet.TOP,
            )
        }
        connect(button.id, ConstraintSet.START, motionLayout.id, ConstraintSet.START)
        connect(button.id, ConstraintSet.END, motionLayout.id, ConstraintSet.END)
    }

    private suspend fun ImageButton.animateTo(
        ringerButtonUiModel: RingerButtonUiModel,
        onProgressChanged: (Float, Boolean) -> Unit = { _, _ -> },
        roundnessAnimationEndListener: DynamicAnimation.OnAnimationEndListener? = null,
    ) {
        val roundnessAnimation =
            SpringAnimation(FloatValueHolder(0F)).setSpring(roundnessSpringForce)
        val colorAnimation = SpringAnimation(FloatValueHolder(0F)).setSpring(colorSpringForce)
        val radius = (background as GradientDrawable).cornerRadius
        val cornerRadiusDiff =
            ringerButtonUiModel.cornerRadius - (background as GradientDrawable).cornerRadius
        val roundnessAnimationUpdateListener =
            DynamicAnimation.OnAnimationUpdateListener { _, value, _ ->
                onProgressChanged(value, cornerRadiusDiff > 0F)
                (background as GradientDrawable).cornerRadius = radius + value * cornerRadiusDiff
                background.invalidateSelf()
            }
        val colorAnimationUpdateListener =
            DynamicAnimation.OnAnimationUpdateListener { _, value, _ ->
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
        coroutineScope {
            launch { colorAnimation.suspendAnimate(colorAnimationUpdateListener) }
            roundnessAnimation.suspendAnimate(
                roundnessAnimationUpdateListener,
                roundnessAnimationEndListener,
            )
        }
    }

    private fun View.applyCorners(fullRadius: Int, diff: Int, progress: Float) {
        (background as GradientDrawable).cornerRadius = fullRadius - progress * diff
        background.invalidateSelf()
    }
}
