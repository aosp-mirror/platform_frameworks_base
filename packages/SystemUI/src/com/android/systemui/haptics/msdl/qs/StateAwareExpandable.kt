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

package com.android.systemui.haptics.msdl.qs

import android.content.ComponentName
import android.view.ViewGroup
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable

private fun ActivityTransitionAnimator.Controller.withStateAwareness(
    onActivityLaunchTransitionStart: () -> Unit,
    onActivityLaunchTransitionEnd: () -> Unit,
): ActivityTransitionAnimator.Controller {
    val delegate = this
    return object : ActivityTransitionAnimator.Controller by delegate {
        override fun onTransitionAnimationStart(isExpandingFullyAbove: Boolean) {
            onActivityLaunchTransitionStart()
            delegate.onTransitionAnimationStart(isExpandingFullyAbove)
        }

        override fun onTransitionAnimationCancelled(newKeyguardOccludedState: Boolean?) {
            onActivityLaunchTransitionEnd()
            delegate.onTransitionAnimationCancelled(newKeyguardOccludedState)
        }

        override fun onTransitionAnimationEnd(isExpandingFullyAbove: Boolean) {
            onActivityLaunchTransitionEnd()
            delegate.onTransitionAnimationEnd(isExpandingFullyAbove)
        }
    }
}

private fun DialogTransitionAnimator.Controller.withStateAwareness(
    onDialogDrawingStart: () -> Unit,
    onDialogDrawingEnd: () -> Unit,
): DialogTransitionAnimator.Controller {
    val delegate = this
    return object : DialogTransitionAnimator.Controller by delegate {

        override fun startDrawingInOverlayOf(viewGroup: ViewGroup) {
            onDialogDrawingStart()
            delegate.startDrawingInOverlayOf(viewGroup)
        }

        override fun stopDrawingInOverlay() {
            onDialogDrawingEnd()
            delegate.stopDrawingInOverlay()
        }
    }
}

fun Expandable.withStateAwareness(
    onDialogDrawingStart: () -> Unit,
    onDialogDrawingEnd: () -> Unit,
    onActivityLaunchTransitionStart: () -> Unit,
    onActivityLaunchTransitionEnd: () -> Unit,
): Expandable {
    val delegate = this
    return object : Expandable {
        override fun activityTransitionController(
            launchCujType: Int?,
            cookie: ActivityTransitionAnimator.TransitionCookie?,
            component: ComponentName?,
            returnCujType: Int?,
            isEphemeral: Boolean,
        ): ActivityTransitionAnimator.Controller? =
            delegate
                .activityTransitionController(
                    launchCujType,
                    cookie,
                    component,
                    returnCujType,
                    isEphemeral,
                )
                ?.withStateAwareness(onActivityLaunchTransitionStart, onActivityLaunchTransitionEnd)

        override fun dialogTransitionController(
            cuj: DialogCuj?
        ): DialogTransitionAnimator.Controller? =
            delegate
                .dialogTransitionController(cuj)
                ?.withStateAwareness(onDialogDrawingStart, onDialogDrawingEnd)
    }
}
