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
import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager
import com.android.systemui.statusbar.LockscreenShadeTransitionController
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.systemui.util.Compile
import com.android.systemui.util.children
import java.io.PrintWriter
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.Delegates.notNull

private const val TAG = "NotifStackSizeCalc"
private val DEBUG = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.DEBUG)
private val SPEW = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.VERBOSE)

/**
 * Calculates number of notifications to display and the height of the notification stack.
 * "Notifications" refers to any ExpandableView that we show on lockscreen, which can include the
 * media player.
 */
@SysUISingleton
class NotificationStackSizeCalculator
@Inject
constructor(
    private val statusBarStateController: SysuiStatusBarStateController,
    private val lockscreenShadeTransitionController: LockscreenShadeTransitionController,
    private val mediaDataManager: MediaDataManager,
    @Main private val resources: Resources,
    private val splitShadeStateController: SplitShadeStateController
) {

    /**
     * Maximum # notifications to show on Keyguard; extras will be collapsed in an overflow shelf.
     * If there are exactly 1 + mMaxKeyguardNotifications, and they fit in the available space
     * (considering the overflow shelf is not displayed in this case), then all notifications are
     * shown.
     */
    private var maxKeyguardNotifications by notNull<Int>()

    /** Minimum space between two notifications, see [calculateGapAndDividerHeight]. */
    private var dividerHeight by notNull<Float>()

    /**
     * True when there is not enough vertical space to show at least one notification with heads up
     * layout. When true, notifications always show collapsed layout.
     */
    private var saveSpaceOnLockscreen = false

    init {
        updateResources()
    }

    /**
     * Returns whether notifications and (shelf if visible) can fit in total space available.
     * [shelfSpace] is extra vertical space allowed for the shelf to overlap the lock icon.
     */
    private fun canStackFitInSpace(
        stackHeight: StackHeight,
        notifSpace: Float,
        shelfSpace: Float,
    ): FitResult {
        val (notifHeight, notifHeightSaveSpace, shelfHeightWithSpaceBefore) = stackHeight

        if (shelfHeightWithSpaceBefore == 0f) {
            if (notifHeight <= notifSpace) {
                log {
                    "\tcanStackFitInSpace[FIT] = notifHeight[$notifHeight]" +
                        " <= notifSpace[$notifSpace]"
                }
                return FitResult.FIT
            }
            if (notifHeightSaveSpace <= notifSpace) {
                log {
                    "\tcanStackFitInSpace[FIT_IF_SAVE_SPACE]" +
                        " = notifHeightSaveSpace[$notifHeightSaveSpace]" +
                        " <= notifSpace[$notifSpace]"
                }
                return FitResult.FIT_IF_SAVE_SPACE
            }
            log {
                "\tcanStackFitInSpace[NO_FIT]" +
                    " = notifHeightSaveSpace[$notifHeightSaveSpace] > notifSpace[$notifSpace]"
            }
            return FitResult.NO_FIT
        } else {
            if ((notifHeight + shelfHeightWithSpaceBefore) <= (notifSpace + shelfSpace)) {
                log {
                    "\tcanStackFitInSpace[FIT] = (notifHeight[$notifHeight]" +
                        " + shelfHeightWithSpaceBefore[$shelfHeightWithSpaceBefore])" +
                        " <= (notifSpace[$notifSpace] " +
                        " + spaceForShelf[$shelfSpace])"
                }
                return FitResult.FIT
            } else if (
                (notifHeightSaveSpace + shelfHeightWithSpaceBefore) <= (notifSpace + shelfSpace)
            ) {
                log {
                    "\tcanStackFitInSpace[FIT_IF_SAVE_SPACE]" +
                        " = (notifHeightSaveSpace[$notifHeightSaveSpace]" +
                        " + shelfHeightWithSpaceBefore[$shelfHeightWithSpaceBefore])" +
                        " <= (notifSpace[$notifSpace] + shelfSpace[$shelfSpace])"
                }
                return FitResult.FIT_IF_SAVE_SPACE
            } else {
                log {
                    "\tcanStackFitInSpace[NO_FIT]" +
                        " = (notifHeightSaveSpace[$notifHeightSaveSpace]" +
                        " + shelfHeightWithSpaceBefore[$shelfHeightWithSpaceBefore])" +
                        " > (notifSpace[$notifSpace] + shelfSpace[$shelfSpace])"
                }
                return FitResult.NO_FIT
            }
        }
    }

    /**
     * Given the [notifSpace] and [shelfSpace] constraints, calculate how many notifications to
     * show. This number is only valid in keyguard.
     *
     * @param totalAvailableSpace space for notifications. This includes the space for the shelf.
     */
    fun computeMaxKeyguardNotifications(
        stack: NotificationStackScrollLayout,
        notifSpace: Float,
        shelfSpace: Float,
        shelfHeight: Float,
    ): Int {
        log { "\n " }
        log {
            "computeMaxKeyguardNotifications ---" +
                "\n\tnotifSpace $notifSpace" +
                "\n\tspaceForShelf $shelfSpace" +
                "\n\tshelfIntrinsicHeight $shelfHeight"
        }
        if (notifSpace + shelfSpace <= 0f) {
            log { "--- No space to show anything. maxNotifs=0" }
            return 0
        }
        log { "\n" }

        val stackHeightSequence = computeHeightPerNotificationLimit(stack, shelfHeight)
        val isMediaShowing = mediaDataManager.hasActiveMediaOrRecommendation()

        log { "\tGet maxNotifWithoutSavingSpace ---" }
        val maxNotifWithoutSavingSpace =
            stackHeightSequence.lastIndexWhile { heightResult ->
                canStackFitInSpace(
                    heightResult,
                    notifSpace = notifSpace,
                    shelfSpace = shelfSpace
                ) == FitResult.FIT
            }

        // How many notifications we can show at heightWithoutLockscreenConstraints
        var minCountAtHeightWithoutConstraints =
            if (isMediaShowing && !splitShadeStateController
                    .shouldUseSplitNotificationShade(resources)) 2 else 1
        log {
            "\t---maxNotifWithoutSavingSpace=$maxNotifWithoutSavingSpace " +
                "isMediaShowing=$isMediaShowing" +
                "minCountAtHeightWithoutConstraints=$minCountAtHeightWithoutConstraints"
        }
        log { "\n" }

        var maxNotifications: Int
        if (maxNotifWithoutSavingSpace >= minCountAtHeightWithoutConstraints) {
            saveSpaceOnLockscreen = false
            maxNotifications = maxNotifWithoutSavingSpace
            log {
                "\tDo NOT save space. maxNotifications=maxNotifWithoutSavingSpace=$maxNotifications"
            }
        } else {
            log { "\tSAVE space ---" }
            saveSpaceOnLockscreen = true
            maxNotifications =
                stackHeightSequence.lastIndexWhile { heightResult ->
                    canStackFitInSpace(
                        heightResult,
                        notifSpace = notifSpace,
                        shelfSpace = shelfSpace
                    ) != FitResult.NO_FIT
                }
            log { "\t--- maxNotifications=$maxNotifications" }
        }

        // Must update views immediately to avoid mismatches between initial HUN layout height
        // and the height adapted to lockscreen space constraints, which causes jump cuts.
        stack.showableChildren().toList().forEach { currentNotification ->
            run {
                if (currentNotification is ExpandableNotificationRow) {
                    currentNotification.saveSpaceOnLockscreen = saveSpaceOnLockscreen
                }
            }
        }

        if (onLockscreen()) {
            maxNotifications = min(maxKeyguardNotifications, maxNotifications)
        }

        // Could be < 0 if the space available is less than the shelf size. Returns 0 in this case.
        maxNotifications = max(0, maxNotifications)
        log {
            val sequence = if (SPEW) " stackHeightSequence=${stackHeightSequence.toList()}" else ""
            "--- computeMaxKeyguardNotifications(" +
                " notifSpace=$notifSpace" +
                " shelfSpace=$shelfSpace" +
                " shelfHeight=$shelfHeight) -> $maxNotifications$sequence"
        }
        log { "\n" }
        return maxNotifications
    }

    /**
     * Given the [maxNotifs] constraint, calculates the height of the
     * [NotificationStackScrollLayout]. This might or might not be in keyguard.
     *
     * @param stack stack containing notifications as children.
     * @param maxNotifs Maximum number of notifications. When reached, the others will go into the
     *   shelf.
     * @param shelfHeight height of the shelf, without any padding. It might be zero.
     * @return height of the stack, including shelf height, if needed.
     */
    fun computeHeight(
        stack: NotificationStackScrollLayout,
        maxNotifs: Int,
        shelfHeight: Float
    ): Float {
        log { "\n" }
        log { "computeHeight ---" }

        val stackHeightSequence = computeHeightPerNotificationLimit(stack, shelfHeight)

        val (notifsHeight, notifsHeightSavingSpace, shelfHeightWithSpaceBefore) =
            stackHeightSequence.elementAtOrElse(maxNotifs) {
                stackHeightSequence.last() // Height with all notifications visible.
            }

        var height: Float
        if (saveSpaceOnLockscreen) {
            height = notifsHeightSavingSpace + shelfHeightWithSpaceBefore
            log {
                "--- computeHeight(maxNotifs=$maxNotifs, shelfHeight=$shelfHeight)" +
                    " -> $height=($notifsHeightSavingSpace+$shelfHeightWithSpaceBefore)," +
                    " | saveSpaceOnLockscreen=$saveSpaceOnLockscreen"
            }
        } else {
            height = notifsHeight + shelfHeightWithSpaceBefore
            log {
                "--- computeHeight(maxNotifs=$maxNotifs, shelfHeight=$shelfHeight)" +
                    " -> ${height}=($notifsHeight+$shelfHeightWithSpaceBefore)" +
                    " | saveSpaceOnLockscreen=$saveSpaceOnLockscreen"
            }
        }
        return height
    }

    private enum class FitResult {
        FIT,
        FIT_IF_SAVE_SPACE,
        NO_FIT
    }

    data class SpaceNeeded(
        // Float height of spaceNeeded when showing heads up layout for FSI HUNs.
        val whenEnoughSpace: Float,

        // Float height of space needed when showing collapsed layout for FSI HUNs.
        val whenSavingSpace: Float
    )

    private data class StackHeight(
        // Float height with ith max notifications (not including shelf)
        val notifsHeight: Float,

        // Float height with ith max notifications
        // (not including shelf, using collapsed layout for FSI HUN)
        val notifsHeightSavingSpace: Float,

        // Float height of shelf (0 if shelf is not showing), and space before the shelf that
        // changes during the lockscreen <=> full shade transition.
        val shelfHeightWithSpaceBefore: Float
    )

    private fun computeHeightPerNotificationLimit(
        stack: NotificationStackScrollLayout,
        shelfHeight: Float,
    ): Sequence<StackHeight> = sequence {
        val children = stack.showableChildren().toList()
        var notifications = 0f
        var notifsWithCollapsedHun = 0f
        var previous: ExpandableView? = null
        val onLockscreen = onLockscreen()

        // Only shelf. This should never happen, since we allow 1 view minimum (EmptyViewState).
        yield(
            StackHeight(
                notifsHeight = 0f,
                notifsHeightSavingSpace = 0f,
                shelfHeightWithSpaceBefore = shelfHeight
            )
        )

        children.forEachIndexed { i, currentNotification ->
            val space = getSpaceNeeded(currentNotification, i, previous, stack, onLockscreen)
            notifications += space.whenEnoughSpace
            notifsWithCollapsedHun += space.whenSavingSpace

            previous = currentNotification

            val shelfWithSpaceBefore =
                if (i == children.lastIndex) {
                    0f // No shelf needed.
                } else {
                    val firstViewInShelfIndex = i + 1
                    val spaceBeforeShelf =
                        calculateGapAndDividerHeight(
                            stack,
                            previous = currentNotification,
                            current = children[firstViewInShelfIndex],
                            currentIndex = firstViewInShelfIndex
                        )
                    spaceBeforeShelf + shelfHeight
                }

            log {
                "\tcomputeHeightPerNotificationLimit i=$i notifs=$notifications " +
                    "notifsHeightSavingSpace=$notifsWithCollapsedHun" +
                    " shelfWithSpaceBefore=$shelfWithSpaceBefore"
            }
            yield(
                StackHeight(
                    notifsHeight = notifications,
                    notifsHeightSavingSpace = notifsWithCollapsedHun,
                    shelfHeightWithSpaceBefore = shelfWithSpaceBefore
                )
            )
        }
    }

    fun updateResources() {
        maxKeyguardNotifications =
            infiniteIfNegative(resources.getInteger(R.integer.keyguard_max_notification_count))

        dividerHeight =
            max(1f, resources.getDimensionPixelSize(R.dimen.notification_divider_height).toFloat())
    }

    private val NotificationStackScrollLayout.childrenSequence: Sequence<ExpandableView>
        get() = children.map { it as ExpandableView }

    @VisibleForTesting
    fun onLockscreen(): Boolean {
        return statusBarStateController.state == KEYGUARD &&
            lockscreenShadeTransitionController.fractionToShade == 0f
    }

    @VisibleForTesting
    fun getSpaceNeeded(
        view: ExpandableView,
        visibleIndex: Int,
        previousView: ExpandableView?,
        stack: NotificationStackScrollLayout,
        onLockscreen: Boolean,
    ): SpaceNeeded {
        assert(view.isShowable(onLockscreen))

        // Must use heightWithoutLockscreenConstraints because intrinsicHeight references
        // mSaveSpaceOnLockscreen and using intrinsicHeight here will result in stack overflow.
        val height = view.heightWithoutLockscreenConstraints.toFloat()
        val gapAndDividerHeight =
            calculateGapAndDividerHeight(stack, previousView, current = view, visibleIndex)

        var size =
            if (onLockscreen) {
                if (view is ExpandableNotificationRow && view.entry.isStickyAndNotDemoted) {
                    height
                } else {
                    view.getMinHeight(/* ignoreTemporaryStates= */ true).toFloat()
                }
            } else {
                height
            }
        size += gapAndDividerHeight

        var sizeWhenSavingSpace =
            if (onLockscreen) {
                view.getMinHeight(/* ignoreTemporaryStates= */ true).toFloat()
            } else {
                height
            }
        sizeWhenSavingSpace += gapAndDividerHeight

        return SpaceNeeded(size, sizeWhenSavingSpace)
    }

    fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("NotificationStackSizeCalculator saveSpaceOnLockscreen=$saveSpaceOnLockscreen")
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
        currentIndex: Int
    ): Float {
        if (currentIndex == 0) {
            return 0f
        }
        return stack.calculateGapHeight(previous, current, currentIndex) + dividerHeight
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

    private inline fun log(s: () -> String) {
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
