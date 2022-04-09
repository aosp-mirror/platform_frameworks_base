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
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.util.children
import javax.inject.Inject
import kotlin.math.max
import kotlin.properties.Delegates.notNull

private const val TAG = "NotificationStackSizeCalculator"
private const val DEBUG = false

/** Calculates number of notifications to display and the height of the notification stack. */
@SysUISingleton
class NotificationStackSizeCalculator
@Inject
constructor(
    private val statusBarStateController: SysuiStatusBarStateController,
    @Main private val resources: Resources
) {

    /**
     * Maximum # notifications to show on Keyguard; extras will be collapsed in an overflow shelf.
     * If there are exactly 1 + mMaxKeyguardNotifications, and they fit in the available space
     * (considering the overflow shelf is not displayed in this case), then all notifications are
     * shown.
     */
    private var maxKeyguardNotifications by notNull<Int>()

    /**
     * Minimum space between two notifications. There might be more space, see [calculateGapHeight].
     */
    private var dividerHeight by notNull<Int>()

    init {
        updateResources()
    }

    /**
     * Given the [availableSpace] constraint, calculates how many notification to show.
     *
     * This number is only valid in keyguard.
     *
     * @param availableSpace space for notifications. This doesn't include the space for the shelf.
     */
    fun computeMaxKeyguardNotifications(
        stack: NotificationStackScrollLayout,
        availableSpace: Float,
        shelfHeight: Float
    ): Int {
        log {
            "computeMaxKeyguardNotifications(" +
                "availableSpace=$availableSpace shelfHeight=$shelfHeight)"
        }

        val children: Sequence<ExpandableView> = stack.childrenSequence
        var remainingSpace: Float = availableSpace
        var count = 0
        var previous: ExpandableView? = null
        val onLockscreen = true
        val showableRows = children.filter { it.isShowable(onLockscreen) }
        val showableRowsCount = showableRows.count()
        showableRows.forEachIndexed { i, current ->
            val spaceNeeded = current.spaceNeeded(count, previous, stack, onLockscreen)
            previous = current
            log { "\ti=$i spaceNeeded=$spaceNeeded remainingSpace=$remainingSpace" }

            if (remainingSpace - spaceNeeded >= 0 && count < maxKeyguardNotifications) {
                count += 1
                remainingSpace -= spaceNeeded
            } else if (remainingSpace - spaceNeeded > -shelfHeight && i == showableRowsCount - 1) {
                log { "Showing all notifications. Shelf is not be needed." }
                // If this is the last one, and it fits using the space shelf would use, then we can
                // display it, as the shelf will not be needed (as all notifications are shown).
                return count + 1
            } else {
                log {
                    "No more fit. Returning $count. Space used: ${availableSpace - remainingSpace}"
                }
                return count
            }
        }
        log { "All fit. Returning $count" }
        return count
    }

    /**
     * Given the [maxNotifications] constraint, calculates the height of the
     * [NotificationStackScrollLayout]. This might or might not be in keyguard.
     *
     * @param stack stack containing notifications as children.
     * @param maxNotifications Maximum number of notifications. When reached, the others will go
     * into the shelf.
     * @param shelfHeight height of the shelf. It might be zero.
     *
     * @return height of the stack, including shelf height, if needed.
     */
    fun computeHeight(
        stack: NotificationStackScrollLayout,
        maxNotifications: Int,
        shelfHeight: Float
    ): Float {
        val children: Sequence<ExpandableView> = stack.childrenSequence
        val maxNotificationsArg = infiniteIfNegative(maxNotifications)
        var height = 0f
        var previous: ExpandableView? = null
        var count = 0
        val onLockscreen = onLockscreen()

        log { "computeHeight(maxNotification=$maxNotifications, shelf=$shelfHeight" }
        children.filter { it.isShowable(onLockscreen) }.forEach { current ->
            if (count < maxNotificationsArg) {
                val spaceNeeded = current.spaceNeeded(count, previous, stack, onLockscreen)
                log { "\ti=$count spaceNeeded=$spaceNeeded" }
                height += spaceNeeded
                count += 1
            } else {
                val gapBeforeFirstViewInShelf = current.calculateGapHeight(stack, previous, count)
                height += gapBeforeFirstViewInShelf
                height += shelfHeight
                log { "returning height with shelf -> $height" }
                return height
            }
            previous = current
        }
        log { "Returning height without shelf -> $height" }
        return height
    }

    fun updateResources() {
        maxKeyguardNotifications =
            infiniteIfNegative(resources.getInteger(R.integer.keyguard_max_notification_count))

        dividerHeight =
            max(1, resources.getDimensionPixelSize(R.dimen.notification_divider_height))
    }

    private val NotificationStackScrollLayout.childrenSequence: Sequence<ExpandableView>
        get() = children.map { it as ExpandableView }

    private fun onLockscreen() = statusBarStateController.state == KEYGUARD

    private fun ExpandableView.spaceNeeded(
        visibleIndex: Int,
        previousView: ExpandableView?,
        stack: NotificationStackScrollLayout,
        onLockscreen: Boolean
    ): Float {
        assert(isShowable(onLockscreen))
        var size =
            if (onLockscreen) {
                getMinHeight(/* ignoreTemporaryStates= */ true).toFloat()
            } else {
                intrinsicHeight.toFloat()
            }
        if (visibleIndex != 0) {
            size += dividerHeight
        }
        val gapHeight = calculateGapHeight(stack, previousView, visibleIndex)
        log { "\ti=$visibleIndex gapHeight=$gapHeight"}
        size += gapHeight
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

    private fun ExpandableView.calculateGapHeight(
        stack: NotificationStackScrollLayout,
        previous: ExpandableView?,
        visibleIndex: Int
    ) = stack.calculateGapHeight(previous, /* current= */ this, visibleIndex)

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
}
