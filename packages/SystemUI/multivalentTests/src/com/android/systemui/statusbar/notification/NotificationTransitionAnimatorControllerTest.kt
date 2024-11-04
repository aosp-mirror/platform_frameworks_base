package com.android.systemui.statusbar.notification

import android.app.Notification.GROUP_ALERT_SUMMARY
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.jank.interactionJankMonitor
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.data.repository.NotificationLaunchAnimationRepository
import com.android.systemui.statusbar.notification.domain.interactor.NotificationLaunchAnimationInteractor
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.HeadsUpUtil
import com.android.systemui.testKosmos
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class NotificationTransitionAnimatorControllerTest : SysuiTestCase() {
    @Mock lateinit var notificationListContainer: NotificationListContainer
    @Mock lateinit var headsUpManager: HeadsUpManager
    @Mock lateinit var onFinishAnimationCallback: Runnable

    private lateinit var notificationTestHelper: NotificationTestHelper
    private lateinit var notification: ExpandableNotificationRow
    private lateinit var controller: NotificationTransitionAnimatorController
    private val notificationLaunchAnimationInteractor =
        NotificationLaunchAnimationInteractor(NotificationLaunchAnimationRepository())

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val notificationKey: String
        get() = notification.entry.sbn.key

    @get:Rule val rule = MockitoJUnit.rule()

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        notificationTestHelper =
            NotificationTestHelper(mContext, mDependency, TestableLooper.get(this))
        notification = notificationTestHelper.createRow()
        controller =
            NotificationTransitionAnimatorController(
                notificationLaunchAnimationInteractor,
                notificationListContainer,
                headsUpManager,
                notification,
                kosmos.interactionJankMonitor,
                onFinishAnimationCallback
            )
    }

    private fun flagNotificationAsHun() {
        `when`(headsUpManager.isHeadsUpEntry(notificationKey)).thenReturn(true)
    }

    @Test
    fun testHunIsRemovedAndCallbackIsInvokedIfWeDontAnimateLaunch() {
        flagNotificationAsHun()
        controller.onIntentStarted(willAnimate = false)

        assertTrue(HeadsUpUtil.isClickedHeadsUpNotification(notification))
        assertFalse(notification.entry.isExpandAnimationRunning)
        val isExpandAnimationRunning by
            testScope.collectLastValue(
                notificationLaunchAnimationInteractor.isLaunchAnimationRunning
            )
        assertFalse(isExpandAnimationRunning!!)

        verify(headsUpManager)
            .removeNotification(
                notificationKey,
                /* releaseImmediately= */ true,
                /* animate= */ true,
                /* reason= */ "onIntentStarted(willAnimate=false)"
            )
        verify(onFinishAnimationCallback).run()
    }

    @Test
    fun testHunIsRemovedAndCallbackIsInvokedWhenAnimationIsCancelled() {
        flagNotificationAsHun()
        controller.onTransitionAnimationCancelled()

        assertTrue(HeadsUpUtil.isClickedHeadsUpNotification(notification))
        assertFalse(notification.entry.isExpandAnimationRunning)
        val isExpandAnimationRunning by
            testScope.collectLastValue(
                notificationLaunchAnimationInteractor.isLaunchAnimationRunning
            )
        assertFalse(isExpandAnimationRunning!!)

        verify(headsUpManager)
            .removeNotification(
                notificationKey,
                /* releaseImmediately= */ true,
                /* animate= */ true,
                /* reason= */ "onLaunchAnimationCancelled()"
            )
        verify(onFinishAnimationCallback).run()
    }

    @Test
    fun testHunIsRemovedAndCallbackIsInvokedWhenAnimationEnds() {
        flagNotificationAsHun()
        controller.onTransitionAnimationEnd(isExpandingFullyAbove = true)

        assertFalse(HeadsUpUtil.isClickedHeadsUpNotification(notification))
        assertFalse(notification.entry.isExpandAnimationRunning)
        val isExpandAnimationRunning by
            testScope.collectLastValue(
                notificationLaunchAnimationInteractor.isLaunchAnimationRunning
            )
        assertFalse(isExpandAnimationRunning!!)

        verify(headsUpManager)
            .removeNotification(
                notificationKey,
                /* releaseImmediately= */ true,
                /* animate= */ false,
                /* reason= */ "onLaunchAnimationEnd()"
            )
        verify(onFinishAnimationCallback).run()
    }

    @Test
    fun testAlertingSummaryHunRemovedOnNonAlertingChildLaunch() {
        val GROUP_KEY = "test_group_key"

        val summary =
            NotificationEntryBuilder()
                .setGroup(mContext, GROUP_KEY)
                .setId(0)
                .apply { modifyNotification(mContext).setSmallIcon(R.drawable.ic_person) }
                .build()
        assertNotSame(summary.key, notification.entry.key)

        notificationTestHelper.createRow(summary)

        GroupEntryBuilder()
            .setKey(GROUP_KEY)
            .setSummary(summary)
            .addChild(notification.entry)
            .build()
        assertSame(summary, notification.entry.parent?.summary)

        `when`(headsUpManager.isHeadsUpEntry(notificationKey)).thenReturn(false)
        `when`(headsUpManager.isHeadsUpEntry(summary.key)).thenReturn(true)

        assertNotSame(GROUP_ALERT_SUMMARY, summary.sbn.notification.groupAlertBehavior)
        assertNotSame(GROUP_ALERT_SUMMARY, notification.entry.sbn.notification.groupAlertBehavior)

        controller.onTransitionAnimationEnd(isExpandingFullyAbove = true)

        verify(headsUpManager)
            .removeNotification(
                summary.key,
                /* releaseImmediately= */ true,
                /* animate= */ false,
                /* reason= */ "onLaunchAnimationEnd()"
            )
        verify(headsUpManager, never())
            .removeNotification(
                notification.entry.key,
                /* releaseImmediately= */ true,
                /* animate= */ false,
                /* reason= */ "onLaunchAnimationEnd()"
            )
    }

    @Test
    fun testNotificationIsExpandingDuringAnimation() {
        controller.onIntentStarted(willAnimate = true)

        assertTrue(notification.entry.isExpandAnimationRunning)
        val isExpandAnimationRunning by
            testScope.collectLastValue(
                notificationLaunchAnimationInteractor.isLaunchAnimationRunning
            )
        assertTrue(isExpandAnimationRunning!!)
    }
}
