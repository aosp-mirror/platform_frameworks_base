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

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import androidx.annotation.LayoutRes
import androidx.compose.ui.util.fastForEachIndexed
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.internal.R as internalR
import com.android.settingslib.Utils
import com.android.systemui.lifecycle.WindowLifecycleState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.lifecycle.viewModel
import com.android.systemui.res.R
import com.android.systemui.util.children
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerButtonViewModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerDrawerState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerViewModel
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.RingerViewModelState
import com.android.systemui.volume.dialog.ringer.ui.viewmodel.VolumeDialogRingerDrawerViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

@VolumeDialogScope
class VolumeDialogRingerViewBinder
@Inject
constructor(private val viewModelFactory: VolumeDialogRingerDrawerViewModel.Factory) {

    fun bind(view: View) {
        with(view) {
            val volumeDialogBackgroundView = requireViewById<View>(R.id.volume_dialog_background)
            val drawerContainer = requireViewById<MotionLayout>(R.id.volume_ringer_drawer)
            repeatWhenAttached {
                viewModel(
                    traceName = "VolumeDialogRingerViewBinder",
                    minWindowLifecycleState = WindowLifecycleState.ATTACHED,
                    factory = { viewModelFactory.create() },
                ) { viewModel ->
                    viewModel.ringerViewModel
                        .onEach { ringerState ->
                            when (ringerState) {
                                is RingerViewModelState.Available -> {
                                    val uiModel = ringerState.uiModel

                                    bindDrawerButtons(viewModel, uiModel)

                                    // Set up view background and visibility
                                    drawerContainer.visibility = View.VISIBLE
                                    when (uiModel.drawerState) {
                                        is RingerDrawerState.Initial -> {
                                            drawerContainer.closeDrawer(uiModel.currentButtonIndex)
                                            volumeDialogBackgroundView.setBackgroundResource(
                                                R.drawable.volume_dialog_background
                                            )
                                        }
                                        is RingerDrawerState.Closed -> {
                                            drawerContainer.closeDrawer(uiModel.currentButtonIndex)
                                            volumeDialogBackgroundView.setBackgroundResource(
                                                R.drawable.volume_dialog_background
                                            )
                                        }
                                        is RingerDrawerState.Open -> {
                                            // Open drawer
                                            drawerContainer.transitionToEnd()
                                            if (
                                                uiModel.currentButtonIndex !=
                                                    uiModel.availableButtons.size - 1
                                            ) {
                                                volumeDialogBackgroundView.setBackgroundResource(
                                                    R.drawable.volume_dialog_background_small_radius
                                                )
                                            }
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
            }
        }
    }

    private fun View.bindDrawerButtons(
        viewModel: VolumeDialogRingerDrawerViewModel,
        uiModel: RingerViewModel,
    ) {
        val drawerContainer = requireViewById<MotionLayout>(R.id.volume_ringer_drawer)
        val count = uiModel.availableButtons.size
        drawerContainer.ensureChildCount(R.layout.volume_ringer_button, count)

        uiModel.availableButtons.fastForEachIndexed { index, ringerButton ->
            ringerButton?.let {
                val view = drawerContainer.getChildAt(count - index - 1)
                // TODO (b/369995871): object animator for button switch ( active <-> inactive )
                if (index == uiModel.currentButtonIndex) {
                    view.bindDrawerButton(uiModel.selectedButton, viewModel, isSelected = true)
                } else {
                    view.bindDrawerButton(it, viewModel)
                }
            }
        }
    }

    private fun View.bindDrawerButton(
        buttonViewModel: RingerButtonViewModel,
        viewModel: VolumeDialogRingerDrawerViewModel,
        isSelected: Boolean = false,
    ) {
        with(requireViewById<ImageButton>(R.id.volume_drawer_button)) {
            setImageResource(buttonViewModel.imageResId)
            contentDescription = context.getString(buttonViewModel.contentDescriptionResId)
            if (isSelected) {
                setBackgroundResource(R.drawable.volume_drawer_selection_bg)
                setColorFilter(
                    Utils.getColorAttrDefaultColor(context, internalR.attr.materialColorOnPrimary)
                )
            } else {
                setBackgroundResource(R.drawable.volume_ringer_item_bg)
                setColorFilter(
                    Utils.getColorAttrDefaultColor(context, internalR.attr.materialColorOnSurface)
                )
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
        cloneConstraintSet(R.id.volume_dialog_ringer_drawer_close)
            .adjustClosedConstraintsForDrawer(selectedIndex, this)
        transitionToStart()
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
}
