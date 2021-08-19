/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.shared.animation

import android.graphics.Point
import android.util.MathUtils.lerp
import android.view.Surface
import android.view.View
import android.view.WindowManager
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import java.lang.ref.WeakReference

/**
 * Creates an animation where all registered views are moved into their final location
 * by moving from the center of the screen to the sides
 */
class UnfoldMoveFromCenterAnimator(
    private val windowManager: WindowManager,
    /**
     * Allows to set custom translation applier
     * Could be useful when a view could be translated from
     * several sources and we want to set the translation
     * using custom methods instead of [View.setTranslationX] or
     * [View.setTranslationY]
     */
    var translationApplier: TranslationApplier = object : TranslationApplier {}
) : UnfoldTransitionProgressProvider.TransitionProgressListener {

    private val screenSize = Point()
    private var isVerticalFold = false

    private val animatedViews: MutableList<AnimatedView> = arrayListOf()
    private val tmpArray = IntArray(2)

    /**
     * Updates display properties in order to calculate the initial position for the views
     * Must be called before [registerViewForAnimation]
     */
    fun updateDisplayProperties() {
        windowManager.defaultDisplay.getSize(screenSize)

        // Simple implementation to get current fold orientation,
        // this might not be correct on all devices
        // TODO: use JetPack WindowManager library to get the fold orientation
        isVerticalFold = windowManager.defaultDisplay.rotation == Surface.ROTATION_0 ||
            windowManager.defaultDisplay.rotation == Surface.ROTATION_180
    }

    /**
     * Registers a view to be animated, the view should be measured and layouted
     * After finishing the animation it is necessary to clear
     * the views using [clearRegisteredViews]
     */
    fun registerViewForAnimation(view: View) {
        val animatedView = createAnimatedView(view)
        animatedViews.add(animatedView)
    }

    /**
     * Unregisters all registered views and resets their translation
     */
    fun clearRegisteredViews() {
        onTransitionProgress(1f)
        animatedViews.clear()
    }

    override fun onTransitionProgress(progress: Float) {
        animatedViews.forEach {
            it.view.get()?.let { view ->
                translationApplier.apply(
                    view = view,
                    x = lerp(it.startTranslationX, it.finishTranslationX, progress),
                    y = lerp(it.startTranslationY, it.finishTranslationY, progress)
                )
            }
        }
    }

    private fun createAnimatedView(view: View): AnimatedView {
        val viewLocation = tmpArray
        view.getLocationOnScreen(viewLocation)

        val viewX = viewLocation[0].toFloat()
        val viewY = viewLocation[1].toFloat()

        val viewCenterX = viewX + view.width / 2
        val viewCenterY = viewY + view.height / 2

        val translationXDiff: Float
        val translationYDiff: Float

        if (isVerticalFold) {
            val distanceFromScreenCenterToViewCenter = screenSize.x / 2 - viewCenterX
            translationXDiff = distanceFromScreenCenterToViewCenter * TRANSLATION_PERCENTAGE
            translationYDiff = 0f
        } else {
            val distanceFromScreenCenterToViewCenter = screenSize.y / 2 - viewCenterY
            translationXDiff = 0f
            translationYDiff = distanceFromScreenCenterToViewCenter * TRANSLATION_PERCENTAGE
        }

        return AnimatedView(
            view = WeakReference(view),
            startTranslationX = view.translationX + translationXDiff,
            startTranslationY = view.translationY + translationYDiff,
            finishTranslationX = view.translationX,
            finishTranslationY = view.translationY
        )
    }

    /**
     * Interface that allows to use custom logic to apply translation to view
     */
    interface TranslationApplier {
        /**
         * Called when we need to apply [x] and [y] translation to [view]
         */
        fun apply(view: View, x: Float, y: Float) {
            view.translationX = x
            view.translationY = y
        }
    }

    private class AnimatedView(
        val view: WeakReference<View>,
        val startTranslationX: Float,
        val startTranslationY: Float,
        val finishTranslationX: Float,
        val finishTranslationY: Float
    )
}

private const val TRANSLATION_PERCENTAGE = 0.3f
