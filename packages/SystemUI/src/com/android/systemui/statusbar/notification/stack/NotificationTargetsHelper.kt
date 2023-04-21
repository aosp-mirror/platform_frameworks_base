package com.android.systemui.statusbar.notification.stack

import androidx.core.view.children
import androidx.core.view.isVisible
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.notification.Roundable
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import javax.inject.Inject

/**
 * Utility class that helps us find the targets of an animation, often used to find the notification
 * ([Roundable]) above and below the current one (see [findRoundableTargets]).
 */
@SysUISingleton
class NotificationTargetsHelper
@Inject
constructor(
    featureFlags: FeatureFlags,
) {
    private val useRoundnessSourceTypes = featureFlags.isEnabled(Flags.USE_ROUNDNESS_SOURCETYPES)

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

            if (!useRoundnessSourceTypes) {
                return RoundableTargets(null, null, null)
            }

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

        return RoundableTargets(
            before = viewBefore,
            swiped = viewSwiped,
            after = viewAfter,
        )
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
