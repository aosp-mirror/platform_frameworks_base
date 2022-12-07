package com.android.systemui.statusbar.notification.stack

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.util.mockito.mock
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for {@link NotificationTargetsHelper}. */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class NotificationTargetsHelperTest : SysuiTestCase() {
    lateinit var notificationTestHelper: NotificationTestHelper
    private val sectionsManager: NotificationSectionsManager = mock()
    private val stackScrollLayout: NotificationStackScrollLayout = mock()

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        notificationTestHelper =
            NotificationTestHelper(mContext, mDependency, TestableLooper.get(this))
    }

    private fun notificationTargetsHelper(
        notificationGroupCorner: Boolean = true,
    ) =
        NotificationTargetsHelper(
            FakeFeatureFlags().apply {
                set(Flags.USE_ROUNDNESS_SOURCETYPES, notificationGroupCorner)
            }
        )

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
            RoundableTargets(
                before = children.attachedChildren[1],
                swiped = swiped,
                after = null,
            )
        assertEquals(expected, actual)
    }
}
