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

package com.android.systemui.statusbar.notification.stack

import android.content.res.Resources
import android.util.Log
import android.view.View.GONE
import androidx.annotation.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.util.children
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.Delegates.notNull

private const val TAG = "NotificationStackSizeCalculator"
private const val DEBUG = false

/** Calculates number of notifications to display and the height of the notification stack. */
@SysUISingleton
class NotificationStackSizeCalculator
@Inject
constructor(
    private val statusBarStateController: SysuiStatusBarStateController,
    private val lockscreenShadeTransitionController: LockscreenShadeTransitionController,
    @Main private val resources: Resources
) {

    /**
     * Maximum # notifications to show on Keyguard; extras will be collapsed in an overflow shelf.
     * If there are exactly 1 + mMaxKeyguardNotifications, and they fit in the available space
     * (considering the overflow shelf is not displayed in this case), then all notifications are
     * shown.
     */
    private var maxKeyguardNotifications by notNull<Int>()

    /** Minimum space between two notifications, see [calculateGapAndDividerHeight]. */
    private var dividerHeight by notNull<Int>()

    init {
        updateResources()
    }

    /**
     * Given the [totalAvailableSpace] constraint, calculates how many notification to show.
     *
     * This number is only valid in keyguard.
     *
     * @param totalAvailableSpace space for notifications. This includes the space for the shelf.
     */
    fun computeMaxKeyguardNotifications(
        stack: NotificationStackScrollLayout,
        totalAvailableSpace: Float,
        shelfIntrinsicHeight: Float
    ): Int {
        val stackHeightSequence = computeHeightPerNotificationLimit(stack, shelfIntrinsicHeight)

        var maxNotifications =
            stackHeightSequence.lastIndexWhile { stackHeight -> stackHeight <= totalAvailableSpace }

        if (onLockscreen()) {
            maxNotifications = min(maxKeyguardNotifications, maxNotifications)
        }

        // Could be < 0 if the space available is less than the shelf size. Returns 0 in this case.
        maxNotifications = max(0, maxNotifications)
        log {
            "computeMaxKeyguardNotifications(" +
                "availableSpace=$totalAvailableSpace" +
                " shelfHeight=$shelfIntrinsicHeight) -> $maxNotifications"
        }
        return maxNotifications
    }

    /**
     * Given the [maxNotifications] constraint, calculates the height of the
     * [NotificationStackScrollLayout]. This might or might not be in keyguard.
     *
     * @param stack stack containing notifications as children.
     * @param maxNotifications Maximum number of notifications. When reached, the others will go
     * into the shelf.
     * @param shelfIntrinsicHeight height of the shelf, without any padding. It might be zero.
     *
     * @return height of the stack, including shelf height, if needed.
     */
    fun computeHeight(
        stack: NotificationStackScrollLayout,
        maxNotifications: Int,
        shelfIntrinsicHeight: Float
    ): Float {
        val heightPerMaxNotifications =
            computeHeightPerNotificationLimit(stack, shelfIntrinsicHeight)
        val height =
            heightPerMaxNotifications.elementAtOrElse(maxNotifications) {
                heightPerMaxNotifications.last() // Height with all notifications visible.
            }
        log { "computeHeight(maxNotifications=$maxNotifications) -> $height" }
        return height
    }

    /** The ith result in the sequence is the height with ith max notifications. */
    private fun computeHeightPerNotificationLimit(
        stack: NotificationStackScrollLayout,
        shelfIntrinsicHeight: Float
    ): Sequence<Float> = sequence {
        val children = stack.showableChildren().toList()
        var height = 0f
        var previous: ExpandableView? = null
        val onLockscreen = onLockscreen()

        yield(dividerHeight + shelfIntrinsicHeight) // Only shelf.

        children.forEachIndexed { i, currentNotification ->
            height += spaceNeeded(currentNotification, i, previous, stack, onLockscreen)
            previous = currentNotification

            val shelfHeight =
                if (i == children.lastIndex) {
                    0f // No shelf needed.
                } else {
                    val spaceBeforeShelf =
                        calculateGapAndDividerHeight(
                            stack, previous = currentNotification, current = children[i + 1], i)
                    spaceBeforeShelf + shelfIntrinsicHeight
                }

            yield(height + shelfHeight)
        }
    }

    fun updateResources() {
        maxKeyguardNotifications =
            infiniteIfNegative(resources.getInteger(R.integer.keyguard_max_notification_count))

        dividerHeight = max(1, resources.getDimensionPixelSize(R.dimen.notification_divider_height))
    }

    private val NotificationStackScrollLayout.childrenSequence: Sequence<ExpandableView>
        get() = children.map { it as ExpandableView }

    @VisibleForTesting
    fun onLockscreen() : Boolean {
        return statusBarStateController.state == KEYGUARD
                && lockscreenShadeTransitionController.fractionToShade == 0f
    }

    @VisibleForTesting
    fun spaceNeeded(
        view: ExpandableView,
        visibleIndex: Int,
        previousView: ExpandableView?,
        stack: NotificationStackScrollLayout,
        onLockscreen: Boolean
    ): Float {
        assert(view.isShowable(onLockscreen))
        var size =
            if (onLockscreen) {
                view.getMinHeight(/* ignoreTemporaryStates= */ true).toFloat()
            } else {
                view.intrinsicHeight.toFloat()
            }
        size += calculateGapAndDividerHeight(stack, previousView, current = view, visibleIndex)
        return size
    }

    private fun ExpandableView.isShowable(onLockscreen: Boolean): Boolean {
        if (visibility == GONE || hasNoContentHeight()) return false
        if (onLockscreen) {
            when (this) {
                is ExpandableNotificationRow -> {
                    if (!canShowViewOnLockscreen() || isRemoved) {
                        return false
                    }
                }
                is MediaContainerView -> if (intrinsicHeight == 0) return false
                else -> return false
            }
        }
        return true
    }

    private fun calculateGapAndDividerHeight(
        stack: NotificationStackScrollLayout,
        previous: ExpandableView?,
        current: ExpandableView?,
        visibleIndex: Int
    ): Float {
        var height = stack.calculateGapHeight(previous, current, visibleIndex)
        if (visibleIndex != 0) {
            height += dividerHeight
        }
        return height
    }

    private fun NotificationStackScrollLayout.showableChildren() =
        this.childrenSequence.filter { it.isShowable(onLockscreen()) }

    /**
     * Can a view be shown on the lockscreen when calculating the number of allowed notifications to
     * show?
     *
     * @return `true` if it can be shown.
     */
    private fun ExpandableView.canShowViewOnLockscreen(): Boolean {
        if (hasNoContentHeight()) {
            return false
        } else if (visibility == GONE) {
            return false
        }
        return true
    }

    private fun log(s: () -> String) {
        if (DEBUG) {
            Log.d(TAG, s())
        }
    }

    /** Returns infinite when [v] is negative. Useful in case a resource doesn't limit when -1. */
    private fun infiniteIfNegative(v: Int): Int =
        if (v < 0) {
            Int.MAX_VALUE
        } else {
            v
        }

    /** Returns the last index where [predicate] returns true, or -1 if it was always false. */
    private fun <T> Sequence<T>.lastIndexWhile(predicate: (T) -> Boolean): Int =
        takeWhile(predicate).count() - 1
}
