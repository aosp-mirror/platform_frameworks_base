package com.android.systemui.statusbar

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.util.DisplayMetrics
import androidx.test.filters.SmallTest
import com.android.systemui.ExpandHelper
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.media.MediaHierarchyManager
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QS
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.LockscreenGestureLogger
import com.android.systemui.statusbar.phone.NotificationPanelViewController
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.phone.StatusBar
import com.android.systemui.statusbar.policy.ConfigurationController
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.Mockito.`when` as whenever

private fun <T> anyObject(): T {
    return Mockito.anyObject<T>()
}

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class LockscreenShadeTransitionControllerTest : SysuiTestCase() {

    lateinit var transitionController: LockscreenShadeTransitionController
    lateinit var row: ExpandableNotificationRow
    @Mock lateinit var statusbarStateController: SysuiStatusBarStateController
    @Mock lateinit var lockscreenGestureLogger: LockscreenGestureLogger
    @Mock lateinit var keyguardBypassController: KeyguardBypassController
    @Mock lateinit var lockScreenUserManager: NotificationLockscreenUserManager
    @Mock lateinit var falsingCollector: FalsingCollector
    @Mock lateinit var ambientState: AmbientState
    @Mock lateinit var displayMetrics: DisplayMetrics
    @Mock lateinit var mediaHierarchyManager: MediaHierarchyManager
    @Mock lateinit var scrimController: ScrimController
    @Mock lateinit var configurationController: ConfigurationController
    @Mock lateinit var falsingManager: FalsingManager
    @Mock lateinit var notificationPanelController: NotificationPanelViewController
    @Mock lateinit var nsslController: NotificationStackScrollLayoutController
    @Mock lateinit var featureFlags: FeatureFlags
    @Mock lateinit var stackscroller: NotificationStackScrollLayout
    @Mock lateinit var expandHelperCallback: ExpandHelper.Callback
    @Mock lateinit var statusbar: StatusBar
    @Mock lateinit var qS: QS
    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Before
    fun setup() {
        val helper = NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this))
        row = helper.createRow()
        transitionController = LockscreenShadeTransitionController(
            statusBarStateController = statusbarStateController,
            lockscreenGestureLogger = lockscreenGestureLogger,
            keyguardBypassController = keyguardBypassController,
            lockScreenUserManager = lockScreenUserManager,
            falsingCollector = falsingCollector,
            ambientState = ambientState,
            displayMetrics = displayMetrics,
            mediaHierarchyManager = mediaHierarchyManager,
            scrimController = scrimController,
            featureFlags = featureFlags,
            context = context,
            configurationController = configurationController,
            falsingManager = falsingManager
        )
        whenever(nsslController.view).thenReturn(stackscroller)
        whenever(nsslController.expandHelperCallback).thenReturn(expandHelperCallback)
        transitionController.notificationPanelController = notificationPanelController
        transitionController.statusbar = statusbar
        transitionController.qS = qS
        transitionController.setStackScroller(nsslController)
        whenever(statusbarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        whenever(nsslController.isInLockedDownShade).thenReturn(false)
        whenever(qS.isFullyCollapsed).thenReturn(true)
        whenever(lockScreenUserManager.userAllowsPrivateNotificationsInPublic(anyInt())).thenReturn(
                true)
        whenever(lockScreenUserManager.shouldShowLockscreenNotifications()).thenReturn(true)
        whenever(lockScreenUserManager.isLockscreenPublicMode(anyInt())).thenReturn(true)
        whenever(falsingCollector.shouldEnforceBouncer()).thenReturn(false)
        whenever(keyguardBypassController.bypassEnabled).thenReturn(false)
        clearInvocations(statusbar)
    }

    @After
    fun tearDown() {
        transitionController.dragDownAnimator?.cancel()
    }

    @Test
    fun testCantDragDownWhenQSExpanded() {
        assertTrue("Can't drag down on keyguard", transitionController.canDragDown())
        whenever(qS.isFullyCollapsed).thenReturn(false)
        assertFalse("Can drag down when QS is expanded", transitionController.canDragDown())
    }

    @Test
    fun testCanDragDownInLockedDownShade() {
        whenever(statusbarStateController.state).thenReturn(StatusBarState.SHADE_LOCKED)
        assertFalse("Can drag down in shade locked", transitionController.canDragDown())
        whenever(nsslController.isInLockedDownShade).thenReturn(true)
        assertTrue("Can't drag down in locked down shade", transitionController.canDragDown())
    }

    @Test
    fun testGoingToLockedShade() {
        transitionController.goToLockedShade(null)
        verify(statusbarStateController).setState(StatusBarState.SHADE_LOCKED)
    }

    @Test
    fun testGoToLockedShadeOnlyOnKeyguard() {
        whenever(statusbarStateController.state).thenReturn(StatusBarState.SHADE_LOCKED)
        transitionController.goToLockedShade(null)
        whenever(statusbarStateController.state).thenReturn(StatusBarState.SHADE)
        transitionController.goToLockedShade(null)
        whenever(statusbarStateController.state).thenReturn(StatusBarState.FULLSCREEN_USER_SWITCHER)
        transitionController.goToLockedShade(null)
        verify(statusbarStateController, never()).setState(anyInt())
    }

    @Test
    fun testDontGoWhenShadeDisabled() {
        whenever(statusbar.isShadeDisabled).thenReturn(true)
        transitionController.goToLockedShade(null)
        verify(statusbarStateController, never()).setState(anyInt())
    }

    @Test
    fun testUserExpandsViewOnGoingToFullShade() {
        assertFalse("Row shouldn't be user expanded yet", row.isUserExpanded)
        transitionController.goToLockedShade(row)
        assertTrue("Row wasn't user expanded on drag down", row.isUserExpanded)
    }

    @Test
    fun testTriggeringBouncerWhenPrivateNotificationsArentAllowed() {
        whenever(lockScreenUserManager.userAllowsPrivateNotificationsInPublic(anyInt())).thenReturn(
                false)
        transitionController.goToLockedShade(null)
        verify(statusbarStateController, never()).setState(anyInt())
        verify(statusbarStateController).setLeaveOpenOnKeyguardHide(true)
        verify(statusbar).showBouncerWithDimissAndCancelIfKeyguard(anyObject(), anyObject())
    }

    @Test
    fun testTriggeringBouncerNoNotificationsOnLockscreen() {
        whenever(lockScreenUserManager.shouldShowLockscreenNotifications()).thenReturn(false)
        transitionController.goToLockedShade(null)
        verify(statusbarStateController, never()).setState(anyInt())
        verify(statusbarStateController).setLeaveOpenOnKeyguardHide(true)
        verify(statusbar).showBouncerWithDimissAndCancelIfKeyguard(anyObject(), anyObject())
    }

    @Test
    fun testGoToLockedShadeCreatesQSAnimation() {
        transitionController.goToLockedShade(null)
        verify(statusbarStateController).setState(StatusBarState.SHADE_LOCKED)
        verify(notificationPanelController).animateToFullShade(anyLong())
        assertNotNull(transitionController.dragDownAnimator)
    }

    @Test
    fun testGoToLockedShadeDoesntCreateQSAnimation() {
        transitionController.goToLockedShade(null, needsQSAnimation = false)
        verify(statusbarStateController).setState(StatusBarState.SHADE_LOCKED)
        verify(notificationPanelController).animateToFullShade(anyLong())
        assertNull(transitionController.dragDownAnimator)
    }

    @Test
    fun testDragDownAmountDoesntCallOutInLockedDownShade() {
        whenever(nsslController.isInLockedDownShade).thenReturn(true)
        transitionController.dragDownAmount = 10f
        verify(nsslController, never()).setTransitionToFullShadeAmount(anyFloat())
        verify(mediaHierarchyManager, never()).setTransitionToFullShadeAmount(anyFloat())
        verify(scrimController, never()).setTransitionToFullShadeProgress(anyFloat())
        verify(notificationPanelController, never()).setTransitionToFullShadeAmount(anyFloat(),
                anyBoolean(), anyLong())
        verify(qS, never()).setTransitionToFullShadeAmount(anyFloat(), anyBoolean())
    }

    @Test
    fun testDragDownAmountCallsOut() {
        transitionController.dragDownAmount = 10f
        verify(nsslController).setTransitionToFullShadeAmount(anyFloat())
        verify(mediaHierarchyManager).setTransitionToFullShadeAmount(anyFloat())
        verify(scrimController).setTransitionToFullShadeProgress(anyFloat())
        verify(notificationPanelController).setTransitionToFullShadeAmount(anyFloat(),
                anyBoolean(), anyLong())
        verify(qS).setTransitionToFullShadeAmount(anyFloat(), anyBoolean())
    }
}
