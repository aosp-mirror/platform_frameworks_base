package com.android.systemui.statusbar.notification.stack

import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for {@link NotificationTargetsHelper}. */
@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class NotificationTargetsHelperTest : SysuiTestCase() {
    private val featureFlags = FakeFeatureFlagsClassic()
    lateinit var notificationTestHelper: NotificationTestHelper
    private val sectionsManager: NotificationSectionsManager = mock()
    private val stackScrollLayout: NotificationStackScrollLayout = mock()

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        notificationTestHelper =
            NotificationTestHelper(mContext, mDependency, TestableLooper.get(this), featureFlags)
    }

    private fun notificationTargetsHelper() = NotificationTargetsHelper()

    @Test
    fun targetsForFirstNotificationInGroup() {
        val children = notificationTestHelper.createGroup(3).childrenContainer
        val swiped = children.attachedChildren[0]

        val actual =
            notificationTargetsHelper()
                .findRoundableTargets(
                    viewSwiped = swiped,
                    stackScrollLayout = stackScrollLayout,
                    sectionsManager = sectionsManager,
                )

        val expected =
            RoundableTargets(
                before = children.notificationHeaderWrapper, // group header
                swiped = swiped,
                after = children.attachedChildren[1],
            )
        assertEquals(expected, actual)
    }

    @Test
    fun targetsForMiddleNotificationInGroup() {
        val children = notificationTestHelper.createGroup(3).childrenContainer
        val swiped = children.attachedChildren[1]

        val actual =
            notificationTargetsHelper()
                .findRoundableTargets(
                    viewSwiped = swiped,
                    stackScrollLayout = stackScrollLayout,
                    sectionsManager = sectionsManager,
                )

        val expected =
            RoundableTargets(
                before = children.attachedChildren[0],
                swiped = swiped,
                after = children.attachedChildren[2],
            )
        assertEquals(expected, actual)
    }

    @Test
    fun targetsForLastNotificationInGroup() {
        val children = notificationTestHelper.createGroup(3).childrenContainer
        val swiped = children.attachedChildren[2]

        val actual =
            notificationTargetsHelper()
                .findRoundableTargets(
                    viewSwiped = swiped,
                    stackScrollLayout = stackScrollLayout,
                    sectionsManager = sectionsManager,
                )

        val expected =
            RoundableTargets(before = children.attachedChildren[1], swiped = swiped, after = null)
        assertEquals(expected, actual)
    }

    @Test
    fun findMagneticTargets_forMiddleChild_createsAllTargets() {
        val childrenNumber = 5
        val children = notificationTestHelper.createGroup(childrenNumber).childrenContainer

        // WHEN the swiped view is the one at the middle of the container
        val swiped = children.attachedChildren[childrenNumber / 2]

        // THEN all the views that surround it become targets with the swiped view at the middle
        val actual =
            notificationTargetsHelper()
                .findMagneticTargets(viewSwiped = swiped, stackScrollLayout = stackScrollLayout, 5)
        assertMagneticTargetsForChildren(actual, children.attachedChildren)
    }

    @Test
    fun findMagneticTargets_forTopChild_createsEligibleTargets() {
        val childrenNumber = 5
        val children = notificationTestHelper.createGroup(childrenNumber).childrenContainer

        // WHEN the swiped view is the first one in the container
        val swiped = children.attachedChildren[0]

        // THEN the neighboring views become targets, with the swiped view at the middle and nulls
        // to the left
        val actual =
            notificationTargetsHelper()
                .findMagneticTargets(viewSwiped = swiped, stackScrollLayout = stackScrollLayout, 5)
        val expectedRows =
            listOf(null, null, swiped, children.attachedChildren[1], children.attachedChildren[2])
        assertMagneticTargetsForChildren(actual, expectedRows)
    }

    @Test
    fun findMagneticTargets_forBottomChild_createsEligibleTargets() {
        val childrenNumber = 5
        val children = notificationTestHelper.createGroup(childrenNumber).childrenContainer

        // WHEN the view swiped is the last one in the container
        val swiped = children.attachedChildren[childrenNumber - 1]

        // THEN the neighboring views become targets, with the swiped view at the middle and nulls
        // to the right
        val actual =
            notificationTargetsHelper()
                .findMagneticTargets(viewSwiped = swiped, stackScrollLayout = stackScrollLayout, 5)
        val expectedRows =
            listOf(
                children.attachedChildren[childrenNumber - 3],
                children.attachedChildren[childrenNumber - 2],
                swiped,
                null,
                null,
            )
        assertMagneticTargetsForChildren(actual, expectedRows)
    }

    private fun assertMagneticTargetsForChildren(
        targets: List<MagneticRowListener?>,
        children: List<ExpandableNotificationRow?>,
    ) {
        assertThat(targets.size).isEqualTo(children.size)
        targets.forEachIndexed { i, target ->
            assertThat(target).isEqualTo(children[i]?.magneticRowListener)
        }
    }
}
