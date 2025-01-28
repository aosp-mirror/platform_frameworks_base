package com.android.systemui.statusbar.notification.stack

import androidx.core.view.children
import androidx.core.view.isVisible
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.Roundable
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import javax.inject.Inject

/**
 * Utility class that helps us find the targets of an animation, often used to find the notification
 * ([Roundable]) above and below the current one (see [findRoundableTargets]).
 */
@SysUISingleton
class NotificationTargetsHelper @Inject constructor() {

    /**
     * This method looks for views that can be rounded (and implement [Roundable]) during a
     * notification swipe.
     *
     * @return The [Roundable] targets above/below the [viewSwiped] (if available). The
     *   [RoundableTargets.before] and [RoundableTargets.after] parameters can be `null` if there is
     *   no above/below notification or the notification is not part of the same section.
     */
    fun findRoundableTargets(
        viewSwiped: ExpandableNotificationRow,
        stackScrollLayout: NotificationStackScrollLayout,
        sectionsManager: NotificationSectionsManager,
    ): RoundableTargets {
        val viewBefore: Roundable?
        val viewAfter: Roundable?

        val notificationParent = viewSwiped.notificationParent
        val childrenContainer = notificationParent?.childrenContainer
        val visibleStackChildren =
            stackScrollLayout.children
                .filterIsInstance<ExpandableView>()
                .filter { it.isVisible }
                .toList()
        if (notificationParent != null && childrenContainer != null) {
            // We are inside a notification group

            val visibleGroupChildren = childrenContainer.attachedChildren.filter { it.isVisible }
            val indexOfParentSwipedView = visibleGroupChildren.indexOf(viewSwiped)

            viewBefore =
                visibleGroupChildren.getOrNull(indexOfParentSwipedView - 1)
                    ?: childrenContainer.notificationHeaderWrapper

            viewAfter =
                visibleGroupChildren.getOrNull(indexOfParentSwipedView + 1)
                    ?: visibleStackChildren.indexOf(notificationParent).let {
                        visibleStackChildren.getOrNull(it + 1)
                    }
        } else {
            // Assumption: we are inside the NotificationStackScrollLayout

            val indexOfSwipedView = visibleStackChildren.indexOf(viewSwiped)

            viewBefore =
                visibleStackChildren.getOrNull(indexOfSwipedView - 1)?.takeIf {
                    !sectionsManager.beginsSection(viewSwiped, it)
                }

            viewAfter =
                visibleStackChildren.getOrNull(indexOfSwipedView + 1)?.takeIf {
                    !sectionsManager.beginsSection(it, viewSwiped)
                }
        }

        return RoundableTargets(before = viewBefore, swiped = viewSwiped, after = viewAfter)
    }

    /**
     * This method looks for [ExpandableNotificationRow]s that can magnetically attach to a swiped
     * [ExpandableNotificationRow] and returns their [MagneticRowListener]s in a list.
     *
     * The list contains the swiped row's listener at the center of the list. From the center
     * towards the left, the list contains the closest notification neighbors above the swiped row.
     * From the center towards the right, the list contains the closest neighbors below the row.
     *
     * The list is filled from the center outwards, stopping at the first neighbor that is not an
     * [ExpandableNotificationRow]. In addition, the list does not cross the boundaries of a
     * notification group. Positions where the list halted are filled with null.
     *
     * @param[viewSwiped] The [ExpandableNotificationRow] that is swiped.
     * @param[stackScrollLayout] [NotificationStackScrollLayout] container.
     * @param[totalMagneticTargets] The total number of magnetic listeners in the resulting list.
     *   This includes the listener of the view swiped.
     * @return The list of [MagneticRowListener]s above and below the swiped
     *   [ExpandableNotificationRow]
     */
    fun findMagneticTargets(
        viewSwiped: ExpandableNotificationRow,
        stackScrollLayout: NotificationStackScrollLayout,
        totalMagneticTargets: Int,
    ): List<MagneticRowListener?> {
        val notificationParent = viewSwiped.notificationParent
        val childrenContainer = notificationParent?.childrenContainer
        val visibleStackChildren =
            stackScrollLayout.children
                .filterIsInstance<ExpandableView>()
                .filter { it.isVisible }
                .toList()

        val container: List<ExpandableView> =
            if (notificationParent != null && childrenContainer != null) {
                // We are inside a notification group
                childrenContainer.attachedChildren.filter { it.isVisible }
            } else {
                visibleStackChildren
            }

        // Construct the list of targets
        val magneticTargets = MutableList<MagneticRowListener?>(totalMagneticTargets) { null }
        magneticTargets[totalMagneticTargets / 2] = viewSwiped.magneticRowListener

        // Fill the list outwards from the center
        val centerIndex = container.indexOf(viewSwiped)
        var leftIndex = magneticTargets.size / 2 - 1
        var rightIndex = magneticTargets.size / 2 + 1
        var canMoveLeft = true
        var canMoveRight = true
        for (distance in 1..totalMagneticTargets / 2) {
            if (canMoveLeft) {
                val leftElement = container.getOrNull(index = centerIndex - distance)
                if (leftElement is ExpandableNotificationRow) {
                    magneticTargets[leftIndex] = leftElement.magneticRowListener
                    leftIndex--
                } else {
                    canMoveLeft = false
                }
            }
            if (canMoveRight) {
                val rightElement = container.getOrNull(index = centerIndex + distance)
                if (rightElement is ExpandableNotificationRow) {
                    magneticTargets[rightIndex] = rightElement.magneticRowListener
                    rightIndex++
                } else {
                    canMoveRight = false
                }
            }
        }
        return magneticTargets
    }
}

/**
 * This object contains targets above/below the [swiped] (if available). The [before] and [after]
 * parameters can be `null` if there is no above/below notification or the notification is not part
 * of the same section.
 */
data class RoundableTargets(
    val before: Roundable?,
    val swiped: ExpandableNotificationRow?,
    val after: Roundable?,
)
