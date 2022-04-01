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
import kotlin.math.max
import kotlin.math.min

/**
 * A class that allows changes in bounds within a view hierarchy to animate seamlessly between the
 * start and end state.
 */
class ViewHierarchyAnimator {
    companion object {
        /** Default values for the animation. These can all be overridden at call time. */
        private const val DEFAULT_DURATION = 500L
        private val DEFAULT_INTERPOLATOR = Interpolators.STANDARD
        private val DEFAULT_ADDITION_INTERPOLATOR = Interpolators.STANDARD_DECELERATE
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
         * Returns true if the [rootView] is already visible and will be animated, false otherwise.
         * To animate the addition of a view, see [animateAddition].
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
        ): Boolean {
            return animate(rootView, bounds, interpolator, duration, ephemeral = false)
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
        ): Boolean {
            return animate(rootView, bounds, interpolator, duration, ephemeral = true)
        }

        private fun animate(
            rootView: View,
            bounds: Set<Bound>,
            interpolator: Interpolator,
            duration: Long,
            ephemeral: Boolean
        ): Boolean {
            if (!isVisible(
                    rootView.visibility,
                    rootView.left,
                    rootView.top,
                    rootView.right,
                    rootView.bottom
                )
            ) {
                return false
            }

            val listener = createUpdateListener(bounds, interpolator, duration, ephemeral)
            recursivelyAddListener(rootView, listener)
            return true
        }

        /**
         * Returns a new [View.OnLayoutChangeListener] that when called triggers a layout animation
         * for the specified [bounds], using [interpolator] and [duration].
         *
         * If [ephemeral] is true, the listener is unregistered after the first animation. Otherwise
         * it keeps listening for further updates.
         */
        private fun createUpdateListener(
            bounds: Set<Bound>,
            interpolator: Interpolator,
            duration: Long,
            ephemeral: Boolean
        ): View.OnLayoutChangeListener {
            return createListener(
                bounds,
                interpolator,
                duration,
                ephemeral
            )
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

        /**
         * Instruct the animator to watch for changes to the layout of [rootView] and its children,
         * and animate the next time the hierarchy appears after not being visible. It uses the
         * given [interpolator] and [duration].
         *
         * The start state of the animation is controlled by [origin]. This value can be any of the
         * four corners, any of the four edges, or the center of the view. If any margins are added
         * on the side(s) of the origin, the translation of those margins can be included by
         * specifying [includeMargins].
         *
         * Returns true if the [rootView] is invisible and will be animated, false otherwise. To
         * animate an already visible view, see [animate] and [animateNextUpdate].
         *
         * Then animator unregisters itself once the first addition animation is complete.
         */
        @JvmOverloads
        fun animateAddition(
            rootView: View,
            origin: Hotspot = Hotspot.CENTER,
            interpolator: Interpolator = DEFAULT_ADDITION_INTERPOLATOR,
            duration: Long = DEFAULT_DURATION,
            includeMargins: Boolean = false
        ): Boolean {
            if (isVisible(
                    rootView.visibility,
                    rootView.left,
                    rootView.top,
                    rootView.right,
                    rootView.bottom
                )
            ) {
                return false
            }

            val listener = createAdditionListener(
                origin, interpolator, duration, ignorePreviousValues = !includeMargins
            )
            recursivelyAddListener(rootView, listener)
            return true
        }

        /**
         * Returns a new [View.OnLayoutChangeListener] that on the next call triggers a layout
         * addition animation from the given [origin], using [interpolator] and [duration].
         *
         * If [ignorePreviousValues] is true, the animation will only span the area covered by the
         * new bounds. Otherwise it will include the margins between the previous and new bounds.
         */
        private fun createAdditionListener(
            origin: Hotspot,
            interpolator: Interpolator,
            duration: Long,
            ignorePreviousValues: Boolean
        ): View.OnLayoutChangeListener {
            return createListener(
                DEFAULT_BOUNDS,
                interpolator,
                duration,
                ephemeral = true,
                origin = origin,
                ignorePreviousValues = ignorePreviousValues
            )
        }

        /**
         * Returns a new [View.OnLayoutChangeListener] that when called triggers a layout animation
         * for the specified [bounds], using [interpolator] and [duration].
         *
         * If [ephemeral] is true, the listener is unregistered after the first animation. Otherwise
         * it keeps listening for further updates.
         *
         * [origin] specifies whether the start values should be determined by a hotspot, and
         * [ignorePreviousValues] controls whether the previous values should be taken into account.
         */
        private fun createListener(
            bounds: Set<Bound>,
            interpolator: Interpolator,
            duration: Long,
            ephemeral: Boolean,
            origin: Hotspot? = null,
            ignorePreviousValues: Boolean = false
        ): View.OnLayoutChangeListener {
            return object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View?,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    previousLeft: Int,
                    previousTop: Int,
                    previousRight: Int,
                    previousBottom: Int
                ) {
                    if (view == null) return

                    val startLeft = getBound(view, Bound.LEFT) ?: previousLeft
                    val startTop = getBound(view, Bound.TOP) ?: previousTop
                    val startRight = getBound(view, Bound.RIGHT) ?: previousRight
                    val startBottom = getBound(view, Bound.BOTTOM) ?: previousBottom

                    (view.getTag(R.id.tag_animator) as? ObjectAnimator)?.cancel()

                    if (!isVisible(view.visibility, left, top, right, bottom)) {
                        setBound(view, Bound.LEFT, left)
                        setBound(view, Bound.TOP, top)
                        setBound(view, Bound.RIGHT, right)
                        setBound(view, Bound.BOTTOM, bottom)
                        return
                    }

                    val startValues = processStartValues(
                        origin,
                        left,
                        top,
                        right,
                        bottom,
                        startLeft,
                        startTop,
                        startRight,
                        startBottom,
                        ignorePreviousValues
                    )
                    val endValues = mapOf(
                        Bound.LEFT to left,
                        Bound.TOP to top,
                        Bound.RIGHT to right,
                        Bound.BOTTOM to bottom
                    )

                    val boundsToAnimate = mutableSetOf<Bound>()
                    bounds.forEach { bound ->
                        if (endValues.getValue(bound) != startValues.getValue(bound)) {
                            boundsToAnimate.add(bound)
                        }
                    }

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

        /**
         * Returns whether the given [visibility] and bounds are consistent with a view being
         * currently visible on screen.
         */
        private fun isVisible(
            visibility: Int,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
        ): Boolean {
            return visibility == View.VISIBLE && left != right && top != bottom
        }

        /**
         * Compute the actual starting values based on the requested [origin] and on
         * [ignorePreviousValues].
         *
         * If [origin] is null, the resolved start values will be the same as those passed in, or
         * the same as the new values if [ignorePreviousValues] is true. If [origin] is not null,
         * the start values are resolved based on it, and [ignorePreviousValues] controls whether or
         * not newly introduced margins are included.
         *
         * Base case
         *     1) origin=TOP
         *         x---------x    x---------x    x---------x    x---------x    x---------x
         *                        x---------x    |         |    |         |    |         |
         *                     ->             -> x---------x -> |         | -> |         |
         *                                                      x---------x    |         |
         *                                                                     x---------x
         *     2) origin=BOTTOM_LEFT
         *                                                                     x---------x
         *                                                      x-------x      |         |
         *                     ->             -> x----x      -> |       |   -> |         |
         *                        x--x           |    |         |       |      |         |
         *         x              x--x           x----x         x-------x      x---------x
         *     3) origin=CENTER
         *                                                                     x---------x
         *                                         x-----x       x-------x     |         |
         *              x      ->    x---x    ->   |     |   ->  |       |  -> |         |
         *                                         x-----x       x-------x     |         |
         *                                                                     x---------x
         *
         * In case the start and end values differ in the direction of the origin, and
         * [ignorePreviousValues] is false, the previous values are used and a translation is
         * included in addition to the view expansion.
         *
         *     origin=TOP_LEFT - (0,0,0,0) -> (30,30,70,70)
         *         x
         *                         x--x
         *                         x--x            x----x
         *                     ->             ->   |    |    ->    x------x
         *                                         x----x          |      |
         *                                                         |      |
         *                                                         x------x
         */
        private fun processStartValues(
            origin: Hotspot?,
            newLeft: Int,
            newTop: Int,
            newRight: Int,
            newBottom: Int,
            previousLeft: Int,
            previousTop: Int,
            previousRight: Int,
            previousBottom: Int,
            ignorePreviousValues: Boolean
        ): Map<Bound, Int> {
            val startLeft = if (ignorePreviousValues) newLeft else previousLeft
            val startTop = if (ignorePreviousValues) newTop else previousTop
            val startRight = if (ignorePreviousValues) newRight else previousRight
            val startBottom = if (ignorePreviousValues) newBottom else previousBottom

            var left = startLeft
            var top = startTop
            var right = startRight
            var bottom = startBottom

            if (origin != null) {
                left = when (origin) {
                    Hotspot.CENTER -> (newLeft + newRight) / 2
                    Hotspot.BOTTOM_LEFT, Hotspot.LEFT, Hotspot.TOP_LEFT -> min(startLeft, newLeft)
                    Hotspot.TOP, Hotspot.BOTTOM -> newLeft
                    Hotspot.TOP_RIGHT, Hotspot.RIGHT, Hotspot.BOTTOM_RIGHT -> max(
                        startRight,
                        newRight
                    )
                }
                top = when (origin) {
                    Hotspot.CENTER -> (newTop + newBottom) / 2
                    Hotspot.TOP_LEFT, Hotspot.TOP, Hotspot.TOP_RIGHT -> min(startTop, newTop)
                    Hotspot.LEFT, Hotspot.RIGHT -> newTop
                    Hotspot.BOTTOM_RIGHT, Hotspot.BOTTOM, Hotspot.BOTTOM_LEFT -> max(
                        startBottom,
                        newBottom
                    )
                }
                right = when (origin) {
                    Hotspot.CENTER -> (newLeft + newRight) / 2
                    Hotspot.TOP_RIGHT, Hotspot.RIGHT, Hotspot.BOTTOM_RIGHT -> max(
                        startRight,
                        newRight
                    )
                    Hotspot.TOP, Hotspot.BOTTOM -> newRight
                    Hotspot.BOTTOM_LEFT, Hotspot.LEFT, Hotspot.TOP_LEFT -> min(startLeft, newLeft)
                }
                bottom = when (origin) {
                    Hotspot.CENTER -> (newTop + newBottom) / 2
                    Hotspot.BOTTOM_RIGHT, Hotspot.BOTTOM, Hotspot.BOTTOM_LEFT -> max(
                        startBottom,
                        newBottom
                    )
                    Hotspot.LEFT, Hotspot.RIGHT -> newBottom
                    Hotspot.TOP_LEFT, Hotspot.TOP, Hotspot.TOP_RIGHT -> min(startTop, newTop)
                }
            }

            return mapOf(
                Bound.LEFT to left,
                Bound.TOP to top,
                Bound.RIGHT to right,
                Bound.BOTTOM to bottom
            )
        }

        private fun recursivelyAddListener(view: View, listener: View.OnLayoutChangeListener) {
            // Make sure that only one listener is active at a time.
            val previousListener = view.getTag(R.id.tag_layout_listener)
            if (previousListener != null && previousListener is View.OnLayoutChangeListener) {
                view.removeOnLayoutChangeListener(previousListener)
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
         * Initiates the animation of the requested [bounds] between [startValues] and [endValues]
         * by creating the animator, registering it with the [view], and starting it using
         * [interpolator] and [duration].
         *
         * If [ephemeral] is true, the layout change listener is unregistered at the end of the
         * animation, so no more animations happen.
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

    /** An enum used to determine the origin of addition animations. */
    enum class Hotspot {
        CENTER, LEFT, TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT
    }

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
}
