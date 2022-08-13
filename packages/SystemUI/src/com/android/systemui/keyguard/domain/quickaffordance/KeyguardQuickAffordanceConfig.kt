/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.quickaffordance

import android.content.Intent
import androidx.annotation.StringRes
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.containeddrawable.ContainedDrawable
import kotlinx.coroutines.flow.Flow

/** Defines interface that can act as data source for a single quick affordance model. */
interface KeyguardQuickAffordanceConfig {

    val state: Flow<State>

    fun onQuickAffordanceClicked(
        animationController: ActivityLaunchAnimator.Controller?
    ): OnClickedResult

    /**
     * Encapsulates the state of a "quick affordance" in the keyguard bottom area (for example, a
     * button on the lock-screen).
     */
    sealed class State {

        /** No affordance should show up. */
        object Hidden : State()

        /** An affordance is visible. */
        data class Visible(
            /** An icon for the affordance. */
            val icon: ContainedDrawable,
            /**
             * Resource ID for a string to use for the accessibility content description text of the
             * affordance.
             */
            @StringRes val contentDescriptionResourceId: Int,
        ) : State()
    }

    sealed class OnClickedResult {
        /**
         * Returning this as a result from the [onQuickAffordanceClicked] method means that the
         * implementation has taken care of the click, the system will do nothing.
         */
        object Handled : OnClickedResult()

        /**
         * Returning this as a result from the [onQuickAffordanceClicked] method means that the
         * implementation has _not_ taken care of the click and the system should start an activity
         * using the given [Intent].
         */
        data class StartActivity(
            val intent: Intent,
            val canShowWhileLocked: Boolean,
        ) : OnClickedResult()
    }
}
