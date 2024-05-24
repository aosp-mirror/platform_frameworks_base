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

package com.android.systemui.animation

import android.view.View

/** A piece of UI that can be expanded into a Dialog or an Activity. */
interface Expandable {
    /**
     * Create an [ActivityTransitionAnimator.Controller] that can be used to expand this
     * [Expandable] into an Activity, or return `null` if this [Expandable] should not be animated
     * (e.g. if it is currently not attached or visible).
     *
     * @param cujType the CUJ type from the [com.android.internal.jank.InteractionJankMonitor]
     *   associated to the launch that will use this controller.
     */
    fun activityTransitionController(cujType: Int? = null): ActivityTransitionAnimator.Controller?

    /**
     * Create a [DialogTransitionAnimator.Controller] that can be used to expand this [Expandable]
     * into a Dialog, or return `null` if this [Expandable] should not be animated (e.g. if it is
     * currently not attached or visible).
     */
    fun dialogTransitionController(cuj: DialogCuj? = null): DialogTransitionAnimator.Controller?

    companion object {
        /**
         * Create an [Expandable] that will animate [view] when expanded.
         *
         * Note: The background of [view] should be a (rounded) rectangle so that it can be properly
         * animated.
         */
        @JvmStatic
        fun fromView(view: View): Expandable {
            return object : Expandable {
                override fun activityTransitionController(
                    cujType: Int?,
                ): ActivityTransitionAnimator.Controller? {
                    return ActivityTransitionAnimator.Controller.fromView(view, cujType)
                }

                override fun dialogTransitionController(
                    cuj: DialogCuj?
                ): DialogTransitionAnimator.Controller? {
                    return DialogTransitionAnimator.Controller.fromView(view, cuj)
                }
            }
        }
    }
}
