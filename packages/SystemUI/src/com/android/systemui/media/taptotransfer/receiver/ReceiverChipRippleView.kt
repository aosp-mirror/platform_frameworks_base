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

package com.android.systemui.media.taptotransfer.receiver

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.util.AttributeSet
import com.android.systemui.surfaceeffects.ripple.RippleShader
import com.android.systemui.surfaceeffects.ripple.RippleView
import kotlin.math.pow

/**
 * An expanding ripple effect for the media tap-to-transfer receiver chip.
 */
class ReceiverChipRippleView(context: Context?, attrs: AttributeSet?) : RippleView(context, attrs) {

    // Indicates whether the ripple started expanding.
    private var isStarted: Boolean

    init {
        setupShader(RippleShader.RippleShape.ELLIPSE)
        setRippleFill(true)
        setSparkleStrength(0f)
        duration = 3000L
        isStarted = false
    }

    fun expandRipple(onAnimationEnd: Runnable? = null) {
        isStarted = true
        super.startRipple(onAnimationEnd)
    }

    /** Used to animate out the ripple. No-op if the ripple was never started via [startRipple]. */
    fun collapseRipple(onAnimationEnd: Runnable? = null) {
        if (!isStarted) {
            return // Ignore if ripple is not started yet.
        }
        // Reset all listeners to animator.
        animator.removeAllListeners()
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                onAnimationEnd?.run()
                isStarted = false
            }
        })
        animator.reverse()
    }

    // Expands the ripple to cover full screen.
    fun expandToFull(newHeight: Float, onAnimationEnd: Runnable? = null) {
        if (!isStarted) {
            return
        }
        // Reset all listeners to animator.
        animator.removeAllListeners()
        animator.removeAllUpdateListeners()

        // Only show the outline as ripple expands and disappears when animation ends.
        setRippleFill(false)

        val startingPercentage = calculateStartingPercentage(newHeight)
        animator.addUpdateListener { updateListener ->
            val now = updateListener.currentPlayTime
            val progress = updateListener.animatedValue as Float
            rippleShader.progress = startingPercentage + (progress * (1 - startingPercentage))
            rippleShader.distortionStrength = 1 - rippleShader.progress
            rippleShader.pixelDensity = 1 - rippleShader.progress
            rippleShader.time = now.toFloat()
            invalidate()
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                animation?.let { visibility = GONE }
                onAnimationEnd?.run()
                isStarted = false
            }
        })
        animator.start()
    }

    // Calculates the actual starting percentage according to ripple shader progress set method.
    // Check calculations in [RippleShader.progress]
    fun calculateStartingPercentage(newHeight: Float): Float {
        val ratio = rippleShader.currentHeight / newHeight
        val remainingPercentage = (1 - ratio).toDouble().pow(1 / 3.toDouble()).toFloat()
        return 1 - remainingPercentage
    }
}
