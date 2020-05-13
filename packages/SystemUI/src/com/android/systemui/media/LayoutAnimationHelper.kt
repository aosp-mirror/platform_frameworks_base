/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.media

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.android.systemui.statusbar.notification.AnimatableProperty
import com.android.systemui.statusbar.notification.PropertyAnimator
import com.android.systemui.statusbar.notification.stack.AnimationProperties

/**
 * A utility class that helps with animations of bound changes designed for motionlayout which
 * doesn't work together with regular changeBounds.
 */
class LayoutAnimationHelper {

    private val layout: ViewGroup
    private var sizeAnimationPending = false
    private val desiredBounds = mutableMapOf<View, Rect>()
    private val animationProperties = AnimationProperties()
    private val layoutListener = object : View.OnLayoutChangeListener {
        override fun onLayoutChange(v: View?, left: Int, top: Int, right: Int, bottom: Int,
                                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
            v?.let {
                if (v.alpha == 0.0f || v.visibility == View.GONE || oldLeft - oldRight == 0 ||
                        oldTop - oldBottom == 0) {
                    return
                }
                if (oldLeft != left || oldTop != top || oldBottom != bottom || oldRight != right) {
                    val rect = desiredBounds.getOrPut(v, { Rect() })
                    rect.set(left, top, right, bottom)
                    onDesiredLocationChanged(v, rect)
                }
            }
        }
    }

    constructor(layout: ViewGroup) {
        this.layout = layout
        val childCount = this.layout.childCount
        for (i in 0 until childCount) {
            val child = this.layout.getChildAt(i)
            child.addOnLayoutChangeListener(layoutListener)
        }
    }

    private fun onDesiredLocationChanged(v: View, rect: Rect) {
        if (!sizeAnimationPending) {
            applyBounds(v, rect, animate = false)
        }
        // We need to reapply the current bounds in every frame since the layout may override
        // the layout bounds making this view jump and not all calls to apply bounds actually
        // reapply them, for example if there's already an animator to the same target
        reapplyProperty(v, AnimatableProperty.ABSOLUTE_X);
        reapplyProperty(v, AnimatableProperty.ABSOLUTE_Y);
        reapplyProperty(v, AnimatableProperty.WIDTH);
        reapplyProperty(v, AnimatableProperty.HEIGHT);
    }

    private fun reapplyProperty(v: View, property: AnimatableProperty) {
        property.property.set(v, property.property.get(v))
    }

    private fun applyBounds(v: View, newBounds: Rect, animate: Boolean) {
        PropertyAnimator.setProperty(v, AnimatableProperty.ABSOLUTE_X, newBounds.left.toFloat(),
                animationProperties, animate)
        PropertyAnimator.setProperty(v, AnimatableProperty.ABSOLUTE_Y, newBounds.top.toFloat(),
                animationProperties, animate)
        PropertyAnimator.setProperty(v, AnimatableProperty.WIDTH, newBounds.width().toFloat(),
                animationProperties, animate)
        PropertyAnimator.setProperty(v, AnimatableProperty.HEIGHT, newBounds.height().toFloat(),
                animationProperties, animate)
    }

    private fun startBoundAnimation(v: View) {
        val target = desiredBounds[v] ?: return
        applyBounds(v, target, animate = true)
    }

    fun animatePendingSizeChange(duration: Long, delay: Long) {
        animationProperties.duration = duration
        animationProperties.delay = delay
        if (!sizeAnimationPending) {
            sizeAnimationPending = true
            layout.viewTreeObserver.addOnPreDrawListener (
                    object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            layout.viewTreeObserver.removeOnPreDrawListener(this)
                            sizeAnimationPending = false
                            val childCount = layout.childCount
                            for (i in 0 until childCount) {
                                val child = layout.getChildAt(i)
                                startBoundAnimation(child)
                            }
                            return true
                        }
                    })
        }
    }
}