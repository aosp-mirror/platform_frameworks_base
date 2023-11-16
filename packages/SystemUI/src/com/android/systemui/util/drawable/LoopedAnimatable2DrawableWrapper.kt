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

package com.android.systemui.util.drawable

import android.content.res.Resources
import android.graphics.drawable.Animatable2
import android.graphics.drawable.Drawable
import androidx.appcompat.graphics.drawable.DrawableWrapperCompat

/**
 * Create a looped [Animatable2] restarting it when the animation finishes on its own. Calling
 * [LoopedAnimatable2DrawableWrapper.stop] cancels further looping.
 */
class LoopedAnimatable2DrawableWrapper private constructor(private val animatable2: Animatable2) :
    DrawableWrapperCompat(animatable2 as Drawable), Animatable2 {

    private val loopedCallback = LoopedCallback()

    override fun start() {
        animatable2.start()
        animatable2.registerAnimationCallback(loopedCallback)
    }

    override fun stop() {
        // stop looping if someone stops the animation
        animatable2.unregisterAnimationCallback(loopedCallback)
        animatable2.stop()
    }

    override fun isRunning(): Boolean = animatable2.isRunning

    override fun registerAnimationCallback(callback: Animatable2.AnimationCallback) =
        animatable2.registerAnimationCallback(callback)

    override fun unregisterAnimationCallback(callback: Animatable2.AnimationCallback): Boolean =
        animatable2.unregisterAnimationCallback(callback)

    override fun clearAnimationCallbacks() = animatable2.clearAnimationCallbacks()

    override fun getConstantState(): ConstantState? =
        drawable!!.constantState?.let(LoopedAnimatable2DrawableWrapper::LoopedDrawableState)

    companion object {

        /**
         * Creates [LoopedAnimatable2DrawableWrapper] from a [drawable]. The [drawable] should
         * implement [Animatable2].
         *
         * It supports the following resource tags:
         * - `<animated-image>`
         * - `<animated-vector>`
         */
        fun fromDrawable(drawable: Drawable): LoopedAnimatable2DrawableWrapper {
            require(drawable is Animatable2)
            return LoopedAnimatable2DrawableWrapper(drawable)
        }
    }

    private class LoopedCallback : Animatable2.AnimationCallback() {

        override fun onAnimationEnd(drawable: Drawable?) {
            (drawable as? Animatable2)?.start()
        }
    }

    private class LoopedDrawableState(private val nestedState: ConstantState) : ConstantState() {

        override fun newDrawable(): Drawable = fromDrawable(nestedState.newDrawable())

        override fun newDrawable(res: Resources?): Drawable =
            fromDrawable(nestedState.newDrawable(res))

        override fun newDrawable(res: Resources?, theme: Resources.Theme?): Drawable =
            fromDrawable(nestedState.newDrawable(res, theme))

        override fun canApplyTheme(): Boolean = nestedState.canApplyTheme()

        override fun getChangingConfigurations(): Int = nestedState.changingConfigurations
    }
}
