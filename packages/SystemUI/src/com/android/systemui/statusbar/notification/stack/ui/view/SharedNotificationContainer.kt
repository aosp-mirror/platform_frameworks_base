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
 *
 */
package com.android.systemui.statusbar.notification.stack.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.core.widgets.Optimizer
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.VERTICAL
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel.HorizontalPosition

/**
 * Container for the stack scroller, so that the bounds can be externally specified, such as from
 * the keyguard or shade scenes.
 */
class SharedNotificationContainer(context: Context, attrs: AttributeSet?) :
    ConstraintLayout(context, attrs) {

    private val baseConstraintSet = ConstraintSet()

    init {
        optimizationLevel = optimizationLevel or Optimizer.OPTIMIZATION_GRAPH
        baseConstraintSet.apply {
            create(R.id.nssl_guideline, VERTICAL)
            setGuidelinePercent(R.id.nssl_guideline, 0.5f)
        }
        baseConstraintSet.applyTo(this)
    }

    fun addNotificationStackScrollLayout(nssl: View) {
        addView(nssl)
    }

    fun updateConstraints(
        horizontalPosition: HorizontalPosition,
        marginStart: Int,
        marginTop: Int,
        marginEnd: Int,
        marginBottom: Int,
    ) {
        val constraintSet = ConstraintSet()
        constraintSet.clone(baseConstraintSet)

        val startConstraintId =
            if (horizontalPosition is HorizontalPosition.MiddleToEdge) {
                R.id.nssl_guideline
            } else {
                PARENT_ID
            }

        val nsslId = R.id.notification_stack_scroller
        constraintSet.apply {
            if (SceneContainerFlag.isEnabled) {
                when (horizontalPosition) {
                    is HorizontalPosition.FloatAtEnd ->
                        constrainWidth(nsslId, horizontalPosition.width)
                    is HorizontalPosition.MiddleToEdge ->
                        setGuidelinePercent(R.id.nssl_guideline, horizontalPosition.ratio)
                    else -> Unit
                }
            }

            if (
                !SceneContainerFlag.isEnabled ||
                    horizontalPosition !is HorizontalPosition.FloatAtEnd
            ) {
                connect(nsslId, START, startConstraintId, START, marginStart)
            }
            connect(nsslId, END, PARENT_ID, END, marginEnd)
            connect(nsslId, BOTTOM, PARENT_ID, BOTTOM, marginBottom)
            connect(nsslId, TOP, PARENT_ID, TOP, marginTop)
        }
        constraintSet.applyTo(this)
    }
}
