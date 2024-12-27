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

package com.android.systemui.volume.dialog.ringer.ui.util

import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.view.View
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.res.R
import com.android.systemui.util.children

fun updateOpenState(ringerDrawer: MotionLayout, orientation: Int) {
    val openSet = ringerDrawer.cloneConstraintSet(R.id.volume_dialog_ringer_drawer_open)
    openSet.adjustOpenConstraintsForDrawer(ringerDrawer, orientation)
    ringerDrawer.updateState(R.id.volume_dialog_ringer_drawer_open, openSet)
}

fun updateCloseState(ringerDrawer: MotionLayout, selectedIndex: Int, orientation: Int) {
    val closeSet = ringerDrawer.cloneConstraintSet(R.id.volume_dialog_ringer_drawer_close)
    closeSet.adjustClosedConstraintsForDrawer(ringerDrawer, selectedIndex, orientation)
    ringerDrawer.updateState(R.id.volume_dialog_ringer_drawer_close, closeSet)
}

private fun ConstraintSet.setButtonPositionPortraitConstraints(
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
    clear(button.id, ConstraintSet.LEFT)
    clear(button.id, ConstraintSet.RIGHT)
}

private fun ConstraintSet.setButtonPositionLandscapeConstraints(
    motionLayout: MotionLayout,
    index: Int,
    button: View,
) {
    if (motionLayout.getChildAt(index - 1) == null) {
        connect(button.id, ConstraintSet.LEFT, motionLayout.id, ConstraintSet.LEFT)
    } else {
        connect(
            button.id,
            ConstraintSet.LEFT,
            motionLayout.getChildAt(index - 1).id,
            ConstraintSet.RIGHT,
        )
    }
    if (motionLayout.getChildAt(index + 1) == null) {
        connect(button.id, ConstraintSet.RIGHT, motionLayout.id, ConstraintSet.RIGHT)
    } else {
        connect(
            button.id,
            ConstraintSet.RIGHT,
            motionLayout.getChildAt(index + 1).id,
            ConstraintSet.LEFT,
        )
    }
    connect(button.id, ConstraintSet.TOP, motionLayout.id, ConstraintSet.TOP)
    connect(button.id, ConstraintSet.BOTTOM, motionLayout.id, ConstraintSet.BOTTOM)
    clear(button.id, ConstraintSet.START)
    clear(button.id, ConstraintSet.END)
}

private fun ConstraintSet.adjustOpenConstraintsForDrawer(
    motionLayout: MotionLayout,
    lastOrientation: Int,
) {
    motionLayout.children.forEachIndexed { index, button ->
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
        when (lastOrientation) {
            ORIENTATION_LANDSCAPE -> {
                if (index == 0) {
                    setMargin(
                        button.id,
                        ConstraintSet.LEFT,
                        motionLayout.context.resources.getDimensionPixelSize(
                            R.dimen.volume_dialog_ringer_drawer_left_margin
                        ),
                    )
                }
                setButtonPositionLandscapeConstraints(motionLayout, index, button)
                if (index != motionLayout.childCount - 1) {
                    setMargin(
                        button.id,
                        ConstraintSet.RIGHT,
                        motionLayout.context.resources.getDimensionPixelSize(
                            R.dimen.volume_dialog_components_spacing
                        ),
                    )
                } else {
                    setMargin(button.id, ConstraintSet.RIGHT, 0)
                }
                setMargin(button.id, ConstraintSet.BOTTOM, 0)
            }
            ORIENTATION_PORTRAIT -> {
                if (index == 0) {
                    setMargin(button.id, ConstraintSet.LEFT, 0)
                }
                setButtonPositionPortraitConstraints(motionLayout, index, button)
                if (index != motionLayout.childCount - 1) {
                    setMargin(
                        button.id,
                        ConstraintSet.BOTTOM,
                        motionLayout.context.resources.getDimensionPixelSize(
                            R.dimen.volume_dialog_components_spacing
                        ),
                    )
                } else {
                    setMargin(button.id, ConstraintSet.BOTTOM, 0)
                }
                setMargin(button.id, ConstraintSet.RIGHT, 0)
            }
        }
    }
}

private fun ConstraintSet.adjustClosedConstraintsForDrawer(
    motionLayout: MotionLayout,
    selectedIndex: Int,
    lastOrientation: Int,
) {
    motionLayout.children.forEachIndexed { index, button ->
        setMargin(button.id, ConstraintSet.RIGHT, 0)
        setMargin(button.id, ConstraintSet.BOTTOM, 0)
        when (lastOrientation) {
            ORIENTATION_LANDSCAPE -> {
                setButtonPositionLandscapeConstraints(motionLayout, index, button)
                if (selectedIndex != motionLayout.childCount - index - 1) {
                    setAlpha(button.id, 0.0F)
                    constrainWidth(button.id, 0)
                } else {
                    setAlpha(button.id, 1.0F)
                    constrainWidth(
                        button.id,
                        motionLayout.context.resources.getDimensionPixelSize(
                            R.dimen.volume_dialog_ringer_drawer_button_size
                        ),
                    )
                }
                constrainHeight(
                    button.id,
                    motionLayout.context.resources.getDimensionPixelSize(
                        R.dimen.volume_dialog_ringer_drawer_button_size
                    ),
                )
            }
            ORIENTATION_PORTRAIT -> {
                setButtonPositionPortraitConstraints(motionLayout, index, button)
                if (selectedIndex != motionLayout.childCount - index - 1) {
                    setAlpha(button.id, 0.0F)
                    constrainHeight(button.id, 0)
                } else {
                    setAlpha(button.id, 1.0F)
                    constrainHeight(
                        button.id,
                        motionLayout.context.resources.getDimensionPixelSize(
                            R.dimen.volume_dialog_ringer_drawer_button_size
                        ),
                    )
                }
                constrainWidth(
                    button.id,
                    motionLayout.context.resources.getDimensionPixelSize(
                        R.dimen.volume_dialog_ringer_drawer_button_size
                    ),
                )
            }
        }
    }
}
