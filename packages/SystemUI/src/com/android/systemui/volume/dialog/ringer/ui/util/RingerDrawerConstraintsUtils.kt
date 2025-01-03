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
import android.util.TypedValue
import android.view.View
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.res.R
import com.android.systemui.util.children

fun updateOpenState(ringerDrawer: MotionLayout, orientation: Int, ringerBackground: View) {
    val openSet = ringerDrawer.cloneConstraintSet(R.id.volume_dialog_ringer_drawer_open)
    openSet.setVisibility(ringerBackground.id, View.VISIBLE)
    openSet.adjustOpenConstraintsForDrawer(ringerDrawer, orientation)
    ringerDrawer.updateState(R.id.volume_dialog_ringer_drawer_open, openSet)
}

fun updateCloseState(
    ringerDrawer: MotionLayout,
    selectedIndex: Int,
    orientation: Int,
    ringerBackground: View,
) {
    val closeSet = ringerDrawer.cloneConstraintSet(R.id.volume_dialog_ringer_drawer_close)
    closeSet.setVisibility(ringerBackground.id, View.VISIBLE)
    closeSet.adjustClosedConstraintsForDrawer(ringerDrawer, selectedIndex, orientation)
    ringerDrawer.updateState(R.id.volume_dialog_ringer_drawer_close, closeSet)
}

private fun ConstraintSet.setButtonPositionPortraitConstraints(
    motionLayout: MotionLayout,
    index: Int,
    button: View,
) {
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
    connect(button.id, ConstraintSet.END, motionLayout.id, ConstraintSet.END)
}

private fun ConstraintSet.setButtonPositionLandscapeConstraints(
    motionLayout: MotionLayout,
    index: Int,
    button: View,
) {
    if (motionLayout.getChildAt(index + 1) == null) {
        connect(button.id, ConstraintSet.END, motionLayout.id, ConstraintSet.END)
    } else {
        connect(
            button.id,
            ConstraintSet.END,
            motionLayout.getChildAt(index + 1).id,
            ConstraintSet.START,
        )
    }
    connect(button.id, ConstraintSet.BOTTOM, motionLayout.id, ConstraintSet.BOTTOM)

    // Index 1 is the first button in the children of motionLayout.
    if (index == 1) {
        clear(button.id, ConstraintSet.START)
    }
}

private fun ConstraintSet.adjustOpenConstraintsForDrawer(
    motionLayout: MotionLayout,
    lastOrientation: Int,
) {
    motionLayout.children.forEachIndexed { index, view ->
        if (view.id != R.id.ringer_buttons_background) {
            setAlpha(view.id, 1.0F)
            constrainWidth(
                view.id,
                motionLayout.context.resources.getDimensionPixelSize(
                    R.dimen.volume_dialog_ringer_drawer_button_size
                ),
            )
            constrainHeight(
                view.id,
                motionLayout.context.resources.getDimensionPixelSize(
                    R.dimen.volume_dialog_ringer_drawer_button_size
                ),
            )
            when (lastOrientation) {
                ORIENTATION_LANDSCAPE -> {
                    if (index == 1) {
                        setMargin(
                            view.id,
                            ConstraintSet.START,
                            motionLayout.context.resources.getDimensionPixelSize(
                                R.dimen.volume_dialog_ringer_drawer_margin
                            ),
                        )
                    }
                    setButtonPositionLandscapeConstraints(motionLayout, index, view)
                    if (index != motionLayout.childCount - 1) {
                        setMargin(
                            view.id,
                            ConstraintSet.END,
                            motionLayout.context.resources.getDimensionPixelSize(
                                R.dimen.volume_dialog_components_spacing
                            ),
                        )
                    } else {
                        setMargin(view.id, ConstraintSet.END, 0)
                    }
                    setMargin(view.id, ConstraintSet.BOTTOM, 0)
                }

                ORIENTATION_PORTRAIT -> {
                    if (index == 1) {
                        setMargin(view.id, ConstraintSet.START, 0)
                    }
                    setButtonPositionPortraitConstraints(motionLayout, index, view)
                    if (index != motionLayout.childCount - 1) {
                        setMargin(
                            view.id,
                            ConstraintSet.BOTTOM,
                            motionLayout.context.resources.getDimensionPixelSize(
                                R.dimen.volume_dialog_components_spacing
                            ),
                        )
                    } else {
                        setMargin(view.id, ConstraintSet.BOTTOM, 0)
                    }
                    setMargin(view.id, ConstraintSet.END, 0)
                }
            }
        } else {
            constrainWidth(
                view.id,
                when (lastOrientation) {
                    ORIENTATION_LANDSCAPE ->
                        (motionLayout.context.resources.getDimensionPixelSize(
                            R.dimen.volume_dialog_ringer_drawer_button_size
                        ) * (motionLayout.childCount - 1)) +
                            (motionLayout.context.resources.getDimensionPixelSize(
                                R.dimen.volume_dialog_ringer_drawer_margin
                            ) * 2) +
                            (motionLayout.context.resources.getDimensionPixelSize(
                                R.dimen.volume_dialog_components_spacing
                            ) * (motionLayout.childCount - 2))

                    ORIENTATION_PORTRAIT ->
                        motionLayout.context.resources.getDimensionPixelSize(
                            R.dimen.volume_dialog_width
                        )

                    else -> 0
                },
            )
            connect(view.id, ConstraintSet.BOTTOM, motionLayout.id, ConstraintSet.BOTTOM)
            connect(
                view.id,
                ConstraintSet.START,
                motionLayout.getChildAt(1).id,
                ConstraintSet.START,
            )
            connect(view.id, ConstraintSet.END, motionLayout.id, ConstraintSet.END)
            connect(view.id, ConstraintSet.TOP, motionLayout.getChildAt(1).id, ConstraintSet.TOP)
        }
    }
}

private fun ConstraintSet.adjustClosedConstraintsForDrawer(
    motionLayout: MotionLayout,
    selectedIndex: Int,
    lastOrientation: Int,
) {
    motionLayout.children.forEachIndexed { index, view ->
        if (view.id != R.id.ringer_buttons_background) {
            setMargin(view.id, ConstraintSet.END, 0)
            setMargin(view.id, ConstraintSet.BOTTOM, 0)
            when (lastOrientation) {
                ORIENTATION_LANDSCAPE -> {
                    setButtonPositionLandscapeConstraints(motionLayout, index, view)
                    if (selectedIndex != motionLayout.childCount - index - 1) {
                        setAlpha(view.id, 0.0F)
                        constrainWidth(
                            view.id,
                            TypedValue.applyDimension(
                                    TypedValue.COMPLEX_UNIT_DIP,
                                    1F,
                                    motionLayout.context.resources.displayMetrics,
                                )
                                .toInt(),
                        )
                    } else {
                        connect(view.id, ConstraintSet.END, motionLayout.id, ConstraintSet.END)
                        setAlpha(view.id, 1.0F)
                        constrainWidth(
                            view.id,
                            motionLayout.context.resources.getDimensionPixelSize(
                                R.dimen.volume_dialog_ringer_drawer_button_size
                            ),
                        )
                    }
                    constrainHeight(
                        view.id,
                        motionLayout.context.resources.getDimensionPixelSize(
                            R.dimen.volume_dialog_ringer_drawer_button_size
                        ),
                    )
                }

                ORIENTATION_PORTRAIT -> {
                    setButtonPositionPortraitConstraints(motionLayout, index, view)
                    if (selectedIndex != motionLayout.childCount - index - 1) {
                        setAlpha(view.id, 0.0F)
                        constrainHeight(
                            view.id,
                            TypedValue.applyDimension(
                                    TypedValue.COMPLEX_UNIT_DIP,
                                    1F,
                                    motionLayout.context.resources.displayMetrics,
                                )
                                .toInt(),
                        )
                    } else {
                        setAlpha(view.id, 1.0F)
                        constrainHeight(
                            view.id,
                            motionLayout.context.resources.getDimensionPixelSize(
                                R.dimen.volume_dialog_ringer_drawer_button_size
                            ),
                        )
                    }
                    constrainWidth(
                        view.id,
                        motionLayout.context.resources.getDimensionPixelSize(
                            R.dimen.volume_dialog_ringer_drawer_button_size
                        ),
                    )
                }
            }
        } else {
            constrainWidth(
                view.id,
                motionLayout.context.resources.getDimensionPixelSize(R.dimen.volume_dialog_width),
            )
            connect(view.id, ConstraintSet.BOTTOM, motionLayout.id, ConstraintSet.BOTTOM)
            connect(
                view.id,
                ConstraintSet.START,
                motionLayout.getChildAt(motionLayout.childCount - selectedIndex - 1).id,
                ConstraintSet.START,
            )
            connect(view.id, ConstraintSet.END, motionLayout.id, ConstraintSet.END)
            connect(
                view.id,
                ConstraintSet.TOP,
                motionLayout.getChildAt(motionLayout.childCount - selectedIndex - 1).id,
                ConstraintSet.TOP,
            )
        }
    }
}
