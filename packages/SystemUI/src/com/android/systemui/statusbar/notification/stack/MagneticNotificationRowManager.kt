/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack

import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow

/**
 * An interface to coordinate the magnetic behavior of notifications when swiped.
 *
 * During swiping a notification, this manager receives calls to set the horizontal translation of
 * the notification and events that indicate that the interaction with the notification ended ( see
 * the documentation for [onMagneticInteractionEnd]). The latter represent events when the
 * notification is swiped out by dragging or flinging, or when it snaps back when the view is
 * released from the gesture.
 *
 * This manager uses all of these inputs to implement a magnetic attachment between the notification
 * swiped and its neighbors, as well as a detaching moment after crossing a threshold.
 */
interface MagneticNotificationRowManager {

    /**
     * Set the swipe threshold in pixels. After crossing the threshold, the magnetic target detaches
     * and the magnetic neighbors snap back.
     *
     * @param[threshold] Swipe threshold in pixels.
     */
    fun setSwipeThresholdPx(thresholdPx: Float)

    /**
     * Set the magnetic and roundable targets of a magnetic swipe interaction.
     *
     * This method should construct and set a list of [MagneticRowListener] objects and a
     * [RoundableTargets] object when an [ExpandableNotificationRow] starts to be swiped. The
     * magnetic targets interact magnetically as the [expandableNotificationRow] is swiped, and the
     * [RoundableTargets] get rounded when the row detaches from its magnetic couplings.
     *
     * This method must be called when the [swipingRow] starts to be swiped. It represents the
     * beginning of the magnetic swipe.
     *
     * @param[swipingRow] The [ExpandableNotificationRow] that is being swiped. This is the main
     *   magnetic element that pulls its neighbors as it is swiped.
     * @param[stackScrollLayout] The [NotificationStackScrollLayout] that contains notifications.
     * @param[sectionsManager] The [NotificationSectionsManager] that helps identify roundable
     *   targets.
     */
    fun setMagneticAndRoundableTargets(
        swipingRow: ExpandableNotificationRow,
        stackScrollLayout: NotificationStackScrollLayout,
        sectionsManager: NotificationSectionsManager,
    )

    /**
     * Set the translation of an [ExpandableNotificationRow].
     *
     * This method must be called after [setMagneticAndRoundableTargets] has been called and must be
     * called for each movement on the [row] being that is being swiped.
     *
     * @return true if the given [row] is the current magnetic view being swiped by the user, false
     *   otherwise. If false, no translation is applied and this method has no effect.
     */
    fun setMagneticRowTranslation(row: ExpandableNotificationRow, translation: Float): Boolean

    /**
     * Notifies that the magnetic interactions with the [ExpandableNotificationRow] stopped.
     *
     * This occurs if the row stopped being swiped and will snap back, or if it was ultimately
     * dismissed. This method represents the end of the magnetic interaction and must be called
     * after calls to [setMagneticRowTranslation].
     *
     * @param[row] [ExpandableNotificationRow] that stopped whose interaction stopped.
     * @param[velocity] Optional velocity at the end of the interaction. Use this to trigger
     *   animations with a start velocity.
     */
    fun onMagneticInteractionEnd(row: ExpandableNotificationRow, velocity: Float? = null)

    /**
     * Reset any magnetic and roundable targets set, as well as any internal state.
     *
     * This method is in charge of proper cleanup by cancelling animations, clearing targets and
     * resetting any internal state in the implementation. One use case of this method is when
     * notifications must be cleared in the middle of a magnetic interaction and
     * [onMagneticInteractionEnd] will not be called from the lifecycle of the user gesture.
     */
    fun reset()

    companion object {
        /** Detaching threshold in dp */
        const val MAGNETIC_DETACH_THRESHOLD_DP = 56

        /* An empty implementation of a manager */
        @JvmStatic
        val Empty: MagneticNotificationRowManager
            get() =
                object : MagneticNotificationRowManager {
                    override fun setSwipeThresholdPx(thresholdPx: Float) {}

                    override fun setMagneticAndRoundableTargets(
                        swipingRow: ExpandableNotificationRow,
                        stackScrollLayout: NotificationStackScrollLayout,
                        sectionsManager: NotificationSectionsManager,
                    ) {}

                    override fun setMagneticRowTranslation(
                        row: ExpandableNotificationRow,
                        translation: Float,
                    ): Boolean = false

                    override fun onMagneticInteractionEnd(
                        row: ExpandableNotificationRow,
                        velocity: Float?,
                    ) {}

                    override fun reset() {}
                }
    }
}
