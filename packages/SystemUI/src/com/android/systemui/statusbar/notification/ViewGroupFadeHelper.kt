/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import com.android.systemui.Interpolators
import com.android.systemui.R

/**
 * Class to help with fading of view groups without fading one subview
 */
class ViewGroupFadeHelper {
    companion object {
        private val visibilityIncluder = {
            view: View -> view.visibility == View.VISIBLE
        }

        /**
         * Fade out all views of a root except a single child. This will iterate over all children
         * of the view and make sure that the animation works smoothly.
         * @param root the view root to fade the children away
         * @param excludedView which view should remain
         * @param duration the duration of the animation
         */
        @JvmStatic
        fun fadeOutAllChildrenExcept(root: ViewGroup, excludedView: View, duration: Long,
                                     endRunnable: Runnable?) {
            // starting from the view going up, we are adding the siblings of the child to the set
            // of views that need to be faded.
            val viewsToFadeOut = gatherViews(root, excludedView, visibilityIncluder)

            // Applying the right layertypes for the animation
            for (viewToFade in viewsToFadeOut) {
                if (viewToFade.hasOverlappingRendering
                        && viewToFade.layerType == View.LAYER_TYPE_NONE) {
                    viewToFade.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    viewToFade.setTag(R.id.view_group_fade_helper_hardware_layer, true)
                }
            }

            val animator = ValueAnimator.ofFloat(1.0f, 0.0f).apply {
                this.duration = duration
                interpolator = Interpolators.ALPHA_OUT
                addUpdateListener { animation ->
                    val previousSetAlpha = root.getTag(
                            R.id.view_group_fade_helper_previous_value_tag) as Float?
                    val newAlpha = animation.animatedValue as Float
                    for (viewToFade in viewsToFadeOut) {
                        if (viewToFade.alpha != previousSetAlpha) {
                            // A value was set that wasn't set from our view, let's store it and restore
                            // it at the end
                            viewToFade.setTag(R.id.view_group_fade_helper_restore_tag, viewToFade.alpha)
                        }
                        viewToFade.alpha = newAlpha
                    }
                    root.setTag(R.id.view_group_fade_helper_previous_value_tag, newAlpha)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        endRunnable?.run()
                    }
                })
                start()
            }
            root.setTag(R.id.view_group_fade_helper_modified_views, viewsToFadeOut)
            root.setTag(R.id.view_group_fade_helper_animator, animator)
        }

        private fun gatherViews(root: ViewGroup, excludedView: View,
                                shouldInclude: (View) -> Boolean): MutableSet<View> {
            val viewsToFadeOut = mutableSetOf<View>()
            var parent = excludedView.parent as ViewGroup?
            var viewContainingExcludedView = excludedView;
            while (parent != null) {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (shouldInclude.invoke(child) && viewContainingExcludedView != child) {
                        viewsToFadeOut.add(child)
                    }
                }
                if (parent == root) {
                    break;
                }
                viewContainingExcludedView = parent
                parent = parent.parent as ViewGroup?
            }
            return viewsToFadeOut
        }

        /**
         * Reset all view alphas for views previously transformed away.
         */
        @JvmStatic
        fun reset(root: ViewGroup) {
            @Suppress("UNCHECKED_CAST")
            val modifiedViews = root.getTag(R.id.view_group_fade_helper_modified_views)
                    as MutableSet<View>?
            val animator = root.getTag(R.id.view_group_fade_helper_animator) as Animator?
            if (modifiedViews == null || animator == null) {
                // nothing to restore
                return
            }
            animator.cancel()
            val lastSetValue = root.getTag(
                    R.id.view_group_fade_helper_previous_value_tag) as Float?
            for (viewToFade in modifiedViews) {
                val restoreAlpha = viewToFade.getTag(
                        R.id.view_group_fade_helper_restore_tag) as Float?
                if (restoreAlpha == null) {
                    continue
                }
                if (lastSetValue == viewToFade.alpha) {
                    // it was modified after the transition!
                    viewToFade.alpha = restoreAlpha
                }
                val needsLayerReset = viewToFade.getTag(
                        R.id.view_group_fade_helper_hardware_layer) as Boolean?
                if (needsLayerReset == true) {
                    viewToFade.setLayerType(View.LAYER_TYPE_NONE, null)
                    viewToFade.setTag(R.id.view_group_fade_helper_hardware_layer, null)
                }
                viewToFade.setTag(R.id.view_group_fade_helper_restore_tag, null)
            }
            root.setTag(R.id.view_group_fade_helper_modified_views, null)
            root.setTag(R.id.view_group_fade_helper_previous_value_tag, null)
            root.setTag(R.id.view_group_fade_helper_animator, null)
        }
    }
}
