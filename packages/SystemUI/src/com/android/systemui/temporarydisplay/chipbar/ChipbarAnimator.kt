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
 */

package com.android.systemui.temporarydisplay.chipbar

import android.view.View
import android.view.ViewGroup
import com.android.systemui.animation.Interpolators
import com.android.systemui.animation.ViewHierarchyAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.children
import javax.inject.Inject

/**
 * A class controlling chipbar animations. Typically delegates to [ViewHierarchyAnimator].
 *
 * Used so that animations can be mocked out in tests.
 */
@SysUISingleton
open class ChipbarAnimator @Inject constructor() {
    /**
     * Animates [innerView] and its children into view.
     *
     * @return true if the animation was successfully started and false if the animation can't be
     *   run for any reason.
     *
     * See [ViewHierarchyAnimator.animateAddition].
     */
    open fun animateViewIn(innerView: ViewGroup, onAnimationEnd: Runnable): Boolean {
        return ViewHierarchyAnimator.animateAddition(
            innerView,
            ViewHierarchyAnimator.Hotspot.TOP,
            Interpolators.EMPHASIZED_DECELERATE,
            duration = ANIMATION_IN_DURATION,
            includeMargins = true,
            includeFadeIn = true,
            onAnimationEnd = onAnimationEnd,
        )
    }

    /**
     * Animates [innerView] and its children out of view.
     *
     * @return true if the animation was successfully started and false if the animation can't be
     *   run for any reason.
     *
     * See [ViewHierarchyAnimator.animateRemoval].
     */
    open fun animateViewOut(innerView: ViewGroup, onAnimationEnd: Runnable): Boolean {
        return ViewHierarchyAnimator.animateRemoval(
            innerView,
            ViewHierarchyAnimator.Hotspot.TOP,
            Interpolators.EMPHASIZED_ACCELERATE,
            ANIMATION_OUT_DURATION,
            includeMargins = true,
            onAnimationEnd,
        )
    }

    /** Force shows this view and all child views. Should be used in case [animateViewIn] fails. */
    fun forceDisplayView(innerView: View) {
        innerView.alpha = 1f
        if (innerView is ViewGroup) {
            innerView.children.forEach { forceDisplayView(it) }
        }
    }
}

private const val ANIMATION_IN_DURATION = 500L
private const val ANIMATION_OUT_DURATION = 250L
