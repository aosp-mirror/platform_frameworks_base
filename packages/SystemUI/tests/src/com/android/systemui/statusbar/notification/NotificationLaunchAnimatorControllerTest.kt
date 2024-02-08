package com.android.systemui.statusbar.notification

import android.app.Notification.GROUP_ALERT_SUMMARY
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
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
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import kotlinx.coroutines.test.TestScope
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
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class NotificationLaunchAnimatorControllerTest : SysuiTestCase() {
    @Mock lateinit var notificationListContainer: NotificationListContainer
    @Mock lateinit var headsUpManager: HeadsUpManager
    @Mock lateinit var jankMonitor: InteractionJankMonitor
    @Mock lateinit var onFinishAnimationCallback: Runnable

    private lateinit var notificationTestHelper: NotificationTestHelper
    private lateinit var notification: ExpandableNotificationRow
    private lateinit var controller: NotificationLaunchAnimatorController
    private val notificationLaunchAnimationInteractor =
        NotificationLaunchAnimationInteractor(NotificationLaunchAnimationRepository())

    private val testScope = TestScope()

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
            NotificationLaunchAnimatorController(
                notificationLaunchAnimationInteractor,
                notificationListContainer,
                headsUpManager,
                notification,
                jankMonitor,
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
            .removeNotification(notificationKey, true /* releaseImmediately */, true /* animate */)
        verify(onFinishAnimationCallback).run()
    }

    @Test
    fun testHunIsRemovedAndCallbackIsInvokedWhenAnimationIsCancelled() {
        flagNotificationAsHun()
        controller.onLaunchAnimationCancelled()

        assertTrue(HeadsUpUtil.isClickedHeadsUpNotification(notification))
        assertFalse(notification.entry.isExpandAnimationRunning)
        val isExpandAnimationRunning by
            testScope.collectLastValue(
                notificationLaunchAnimationInteractor.isLaunchAnimationRunning
            )
        assertFalse(isExpandAnimationRunning!!)

        verify(headsUpManager)
            .removeNotification(notificationKey, true /* releaseImmediately */, true /* animate */)
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
            .removeNotification(notificationKey, true /* releaseImmediately */, false /* animate */)
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
            .removeNotification(summary.key, true /* releaseImmediately */, false /* animate */)
        verify(headsUpManager, never())
            .removeNotification(
                notification.entry.key,
                true /* releaseImmediately */,
                false /* animate */
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
