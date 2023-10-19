/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton

/**
 * Standard implementation of [CombinedShadeHeadersConstraintManager].
 */
@SysUISingleton
object CombinedShadeHeadersConstraintManagerImpl : CombinedShadeHeadersConstraintManager {

    override fun privacyChipVisibilityConstraints(visible: Boolean): ConstraintsChanges {
        val constraintAlpha = if (visible) 0f else 1f
        return ConstraintsChanges(
            qqsConstraintsChanges = {
                setAlpha(R.id.shade_header_system_icons, constraintAlpha)
            }
        )
    }

    override fun emptyCutoutConstraints(): ConstraintsChanges {
        return ConstraintsChanges(
            qqsConstraintsChanges = {
                connect(R.id.date, ConstraintSet.END, R.id.barrier, ConstraintSet.START)
                createBarrier(
                    R.id.barrier,
                    ConstraintSet.START,
                    0,
                    R.id.shade_header_system_icons,
                    R.id.privacy_container
                )
                connect(R.id.shade_header_system_icons, ConstraintSet.START, R.id.date,
                    ConstraintSet.END)
                connect(R.id.privacy_container, ConstraintSet.START, R.id.date, ConstraintSet.END)
                constrainWidth(R.id.shade_header_system_icons, ViewGroup.LayoutParams.WRAP_CONTENT)
                constrainedWidth(R.id.date, true)
                constrainedWidth(R.id.shade_header_system_icons, true)
            }
        )
    }

    override fun edgesGuidelinesConstraints(
        cutoutStart: Int,
        paddingStart: Int,
        cutoutEnd: Int,
        paddingEnd: Int
    ): ConstraintsChanges {
        val change: ConstraintChange = {
            setGuidelineBegin(R.id.begin_guide, Math.max(cutoutStart - paddingStart, 0))
            setGuidelineEnd(R.id.end_guide, Math.max(cutoutEnd - paddingEnd, 0))
        }
        return ConstraintsChanges(
            qqsConstraintsChanges = change,
            qsConstraintsChanges = change,
            largeScreenConstraintsChanges = change,
        )
    }

    override fun centerCutoutConstraints(rtl: Boolean, offsetFromEdge: Int): ConstraintsChanges {
        val centerStart = if (!rtl) R.id.center_left else R.id.center_right
        val centerEnd = if (!rtl) R.id.center_right else R.id.center_left
        // Use guidelines to block the center cutout area.
        return ConstraintsChanges(
            qqsConstraintsChanges = {
                setGuidelineBegin(centerStart, offsetFromEdge)
                setGuidelineEnd(centerEnd, offsetFromEdge)
                connect(R.id.date, ConstraintSet.END, centerStart, ConstraintSet.START)
                connect(
                    R.id.shade_header_system_icons,
                    ConstraintSet.START,
                    centerEnd,
                    ConstraintSet.END
                )
                connect(
                    R.id.privacy_container,
                    ConstraintSet.START,
                    centerEnd,
                    ConstraintSet.END
                )
                constrainedWidth(R.id.date, true)
                constrainedWidth(R.id.shade_header_system_icons, true)
            },
            qsConstraintsChanges = {
                setGuidelineBegin(centerStart, offsetFromEdge)
                setGuidelineEnd(centerEnd, offsetFromEdge)
                connect(
                    R.id.privacy_container,
                    ConstraintSet.START,
                    centerEnd,
                    ConstraintSet.END
                )
            }
        )
    }
}
