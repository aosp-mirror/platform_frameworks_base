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

package com.android.systemui.statusbar

import android.content.Context
import android.util.IndentingPrintWriter
import android.util.MathUtils
import androidx.annotation.FloatRange
import androidx.annotation.Px
import com.android.systemui.R
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.qs.QS
import com.android.systemui.statusbar.policy.ConfigurationController
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.math.max

/** Responsible for the QS components during the lockscreen shade transition. */
class LockscreenShadeQsTransitionController
@AssistedInject
constructor(
    context: Context,
    configurationController: ConfigurationController,
    dumpManager: DumpManager,
    @Assisted private val qsProvider: () -> QS,
) : AbstractLockscreenShadeTransitionController(context, configurationController, dumpManager) {

    private val qs: QS
        get() = qsProvider()

    /**
     * The progress fraction of the QS transition during lockscreen shade expansion.
     *
     * Note that this value might be 0 for some time when the expansion is already in progress,
     * since there is a transition start delay for QS on some device configurations. For this
     * reason, don't use this value to check whether the shade expansion is in progress.
     */
    @FloatRange(from = 0.0, to = 1.0)
    var qsTransitionFraction = 0f
        private set

    /**
     * The fraction of the QS "squish" transition progress during lockscreen shade expansion.
     *
     * Note that in some device configurations (large screens) this value can start at a value
     * greater than 0. For this reason don't use this value to check whether the QS transition has
     * started or not.
     */
    @FloatRange(from = 0.0, to = 1.0)
    var qsSquishTransitionFraction = 0f
        private set

    /**
     * The drag down amount, in pixels __for the QS transition__, also taking into account the
     * [qsTransitionStartDelay].
     *
     * Since it takes into account the start delay, it is __not__ the same as the raw drag down
     * amount from the shade expansion.
     */
    @Px private var qsDragDownAmount = 0f

    /**
     * Distance it takes for the QS transition to complete during the lockscreen shade expansion.
     */
    @Px private var qsTransitionDistance = 0

    /** Distance delay for the QS transition to start during the lockscreen shade expansion. */
    @Px private var qsTransitionStartDelay = 0

    /**
     * Distance that it takes to complete the QS "squish" transition during the lockscreen shade
     * expansion.
     */
    @Px private var qsSquishTransitionDistance = 0

    /**
     * Whether the transition to full shade is in progress. Might be `true` even though
     * [qsTransitionFraction] is still 0, due to [qsTransitionStartDelay].
     */
    private var isTransitioningToFullShade = false

    /**
     * The fraction at which the QS "squish" transition should start during the lockscreen shade
     * expansion.
     *
     * 0 is fully collapsed, 1 is fully expanded.
     */
    @FloatRange(from = 0.0, to = 1.0) private var qsSquishStartFraction = 0f

    override fun updateResources() {
        qsTransitionDistance =
            context.resources.getDimensionPixelSize(R.dimen.lockscreen_shade_qs_transition_distance)
        qsTransitionStartDelay =
            context.resources.getDimensionPixelSize(R.dimen.lockscreen_shade_qs_transition_delay)
        qsSquishTransitionDistance =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_qs_squish_transition_distance
            )
        qsSquishStartFraction =
            context.resources.getFloat(R.dimen.lockscreen_shade_qs_squish_start_fraction)

        qsSquishTransitionFraction = max(qsSquishTransitionFraction, qsSquishStartFraction)
    }

    override fun onDragDownAmountChanged(dragDownAmount: Float) {
        qsDragDownAmount = dragDownAmount - qsTransitionStartDelay
        qsTransitionFraction = MathUtils.saturate(qsDragDownAmount / qsTransitionDistance)
        qsSquishTransitionFraction =
            MathUtils.lerp(
                /* start= */ qsSquishStartFraction,
                /* stop= */ 1f,
                /* amount= */ MathUtils.saturate(qsDragDownAmount / qsSquishTransitionDistance)
            )
        isTransitioningToFullShade = dragDownAmount > 0.0f
        qs.setTransitionToFullShadeProgress(
            isTransitioningToFullShade,
            qsTransitionFraction,
            qsSquishTransitionFraction
        )
    }

    override fun dump(pw: IndentingPrintWriter) {
        pw.println(
            """
            Resources:
              qsTransitionDistance: $qsTransitionDistance
              qsTransitionStartDelay: $qsTransitionStartDelay
              qsSquishTransitionDistance: $qsSquishTransitionDistance
              qsSquishStartFraction: $qsSquishStartFraction
            State:
              dragDownAmount: $dragDownAmount
              qsDragDownAmount: $qsDragDownAmount
              qsDragFraction: $qsTransitionFraction
              qsSquishFraction: $qsSquishTransitionFraction
              isTransitioningToFullShade: $isTransitioningToFullShade
        """
                .trimIndent()
        )
    }

    @AssistedFactory
    fun interface Factory {
        fun create(qsProvider: () -> QS): LockscreenShadeQsTransitionController
    }
}
