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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.util.IntProperty
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator

/**
 * A class that allows changes in bounds within a view hierarchy to animate seamlessly between the
 * start and end state.
 */
class ViewHierarchyAnimator {
    // TODO(b/221418522): make this private once it can't be passed as an arg anymore.
    enum class Bound(val label: String, val overrideTag: Int) {
        LEFT("left", R.id.tag_override_left) {
            override fun setValue(view: View, value: Int) {
                view.left = value
            }

            override fun getValue(view: View): Int {
                return view.left
            }
        },
        TOP("top", R.id.tag_override_top) {
            override fun setValue(view: View, value: Int) {
                view.top = value
            }

            override fun getValue(view: View): Int {
                return view.top
            }
        },
        RIGHT("right", R.id.tag_override_right) {
            override fun setValue(view: View, value: Int) {
                view.right = value
            }

            override fun getValue(view: View): Int {
                return view.right
            }
        },
        BOTTOM("bottom", R.id.tag_override_bottom) {
            override fun setValue(view: View, value: Int) {
                view.bottom = value
            }

            override fun getValue(view: View): Int {
                return view.bottom
            }
        };

        abstract fun setValue(view: View, value: Int)
        abstract fun getValue(view: View): Int
    }

    companion object {
        /** Default values for the animation. These can all be overridden at call time. */
        private const val DEFAULT_DURATION = 500L
        private val DEFAULT_INTERPOLATOR = Interpolators.EMPHASIZED
        private val DEFAULT_BOUNDS = setOf(Bound.LEFT, Bound.TOP, Bound.RIGHT, Bound.BOTTOM)

        /** The properties used to animate the view bounds. */
        private val PROPERTIES = mapOf(
            Bound.LEFT to createViewProperty(Bound.LEFT),
            Bound.TOP to createViewProperty(Bound.TOP),
            Bound.RIGHT to createViewProperty(Bound.RIGHT),
            Bound.BOTTOM to createViewProperty(Bound.BOTTOM)
        )

        private fun createViewProperty(bound: Bound): IntProperty<View> {
            return object : IntProperty<View>(bound.label) {
                override fun setValue(view: View, value: Int) {
                    setBound(view, bound, value)
                }

                override fun get(view: View): Int {
                    return getBound(view, bound) ?: bound.getValue(view)
                }
            }
        }

        /**
         * Instruct the animator to watch for changes to the layout of [rootView] and its children
         * and animate them. The animation can be limited to a subset of [bounds]. It uses the
         * given [interpolator] and [duration].
         *
         * If a new layout change happens while an animation is already in progress, the animation
         * is updated to continue from the current values to the new end state.
         *
         * The animator continues to respond to layout changes until [stopAnimating] is called.
         *
         * Successive calls to this method override the previous settings ([interpolator] and
         * [duration]). The changes take effect on the next animation.
         *
         * TODO(b/221418522): remove the ability to select which bounds to animate and always
         * animate all of them.
         */
        @JvmOverloads
        fun animate(
            rootView: View,
            bounds: Set<Bound> = DEFAULT_BOUNDS,
            interpolator: Interpolator = DEFAULT_INTERPOLATOR,
            duration: Long = DEFAULT_DURATION
        ) {
            animate(rootView, bounds, interpolator, duration, false /* ephemeral */)
        }

        /**
         * Like [animate], but only takes effect on the next layout update, then unregisters itself
         * once the first animation is complete.
         *
         * TODO(b/221418522): remove the ability to select which bounds to animate and always
         * animate all of them.
         */
        @JvmOverloads
        fun animateNextUpdate(
            rootView: View,
            bounds: Set<Bound> = DEFAULT_BOUNDS,
            interpolator: Interpolator = DEFAULT_INTERPOLATOR,
            duration: Long = DEFAULT_DURATION
        ) {
            animate(rootView, bounds, interpolator, duration, true /* ephemeral */)
        }

        private fun animate(
            rootView: View,
            bounds: Set<Bound>,
            interpolator: Interpolator,
            duration: Long,
            ephemeral: Boolean
        ) {
            val listener = createListener(bounds, interpolator, duration, ephemeral)
            recursivelyAddListener(rootView, listener)
        }

        /**
         * Instruct the animator to stop watching for changes to the layout of [rootView] and its
         * children.
         *
         * Any animations already in progress continue until their natural conclusion.
         */
        fun stopAnimating(rootView: View) {
            val listener = rootView.getTag(R.id.tag_layout_listener)
            if (listener != null && listener is View.OnLayoutChangeListener) {
                rootView.setTag(R.id.tag_layout_listener, null /* tag */)
                rootView.removeOnLayoutChangeListener(listener)
            }

            if (rootView is ViewGroup) {
                for (i in 0 until rootView.childCount) {
                    stopAnimating(rootView.getChildAt(i))
                }
            }
        }

        private fun createListener(
            bounds: Set<Bound>,
            interpolator: Interpolator,
            duration: Long,
            ephemeral: Boolean
        ): View.OnLayoutChangeListener {
            return object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View?,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int
                ) {
                    if (view == null) return

                    val startLeft = getBound(view, Bound.LEFT) ?: oldLeft
                    val startTop = getBound(view, Bound.TOP) ?: oldTop
                    val startRight = getBound(view, Bound.RIGHT) ?: oldRight
                    val startBottom = getBound(view, Bound.BOTTOM) ?: oldBottom

                    (view.getTag(R.id.tag_animator) as? ObjectAnimator)?.cancel()

                    if (view.visibility == View.GONE || view.visibility == View.INVISIBLE) {
                        setBound(view, Bound.LEFT, left)
                        setBound(view, Bound.TOP, top)
                        setBound(view, Bound.RIGHT, right)
                        setBound(view, Bound.BOTTOM, bottom)
                        return
                    }

                    val startValues = mapOf(
                        Bound.LEFT to startLeft,
                        Bound.TOP to startTop,
                        Bound.RIGHT to startRight,
                        Bound.BOTTOM to startBottom
                    )
                    val endValues = mapOf(
                        Bound.LEFT to left,
                        Bound.TOP to top,
                        Bound.RIGHT to right,
                        Bound.BOTTOM to bottom
                    )

                    val boundsToAnimate = bounds.toMutableSet()
                    if (left == startLeft) boundsToAnimate.remove(Bound.LEFT)
                    if (top == startTop) boundsToAnimate.remove(Bound.TOP)
                    if (right == startRight) boundsToAnimate.remove(Bound.RIGHT)
                    if (bottom == startBottom) boundsToAnimate.remove(Bound.BOTTOM)

                    if (boundsToAnimate.isNotEmpty()) {
                        startAnimation(
                            view,
                            boundsToAnimate,
                            startValues,
                            endValues,
                            interpolator,
                            duration,
                            ephemeral
                        )
                    }
                }
            }
        }

        private fun recursivelyAddListener(view: View, listener: View.OnLayoutChangeListener) {
            // Make sure that only one listener is active at a time.
            val oldListener = view.getTag(R.id.tag_layout_listener)
            if (oldListener != null && oldListener is View.OnLayoutChangeListener) {
                view.removeOnLayoutChangeListener(oldListener)
            }

            view.addOnLayoutChangeListener(listener)
            view.setTag(R.id.tag_layout_listener, listener)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    recursivelyAddListener(view.getChildAt(i), listener)
                }
            }
        }

        private fun getBound(view: View, bound: Bound): Int? {
            return view.getTag(bound.overrideTag) as? Int
        }

        private fun setBound(view: View, bound: Bound, value: Int) {
            view.setTag(bound.overrideTag, value)
            bound.setValue(view, value)
        }

        /**
         * Initiates the animation of a single bound by creating the animator, registering it with
         * the [view], and starting it. If [ephemeral], the layout change listener is unregistered
         * at the end of the animation, so no more animations happen.
         */
        private fun startAnimation(
            view: View,
            bounds: Set<Bound>,
            startValues: Map<Bound, Int>,
            endValues: Map<Bound, Int>,
            interpolator: Interpolator,
            duration: Long,
            ephemeral: Boolean
        ) {
            val propertyValuesHolders = buildList {
                bounds.forEach { bound ->
                    add(
                        PropertyValuesHolder.ofInt(
                            PROPERTIES[bound],
                            startValues.getValue(bound),
                            endValues.getValue(bound)
                        )
                    )
                }
            }.toTypedArray()

            val animator = ObjectAnimator.ofPropertyValuesHolder(view, *propertyValuesHolders)
            animator.interpolator = interpolator
            animator.duration = duration
            animator.addListener(object : AnimatorListenerAdapter() {
                var cancelled = false

                override fun onAnimationEnd(animation: Animator) {
                    view.setTag(R.id.tag_animator, null /* tag */)
                    bounds.forEach { view.setTag(it.overrideTag, null /* tag */) }

                    // When an animation is cancelled, a new one might be taking over. We shouldn't
                    // unregister the listener yet.
                    if (ephemeral && !cancelled) {
                        val listener = view.getTag(R.id.tag_layout_listener)
                        if (listener != null && listener is View.OnLayoutChangeListener) {
                            view.setTag(R.id.tag_layout_listener, null /* tag */)
                            view.removeOnLayoutChangeListener(listener)
                        }
                    }
                }

                override fun onAnimationCancel(animation: Animator?) {
                    cancelled = true
                }
            })

            bounds.forEach { bound -> setBound(view, bound, startValues.getValue(bound)) }

            view.setTag(R.id.tag_animator, animator)
            animator.start()
        }
    }
}
