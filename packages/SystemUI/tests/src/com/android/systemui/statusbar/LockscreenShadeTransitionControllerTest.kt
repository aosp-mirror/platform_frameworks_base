package com.android.systemui.statusbar

import org.mockito.Mockito.`when` as whenever
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import com.android.systemui.ExpandHelper
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.media.MediaHierarchyManager
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QS
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.statusbar.notification.stack.AmbientState
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.LSShadeTransitionLogger
import com.android.systemui.statusbar.phone.NotificationPanelViewController
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.policy.FakeConfigurationController
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
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

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
    @Mock lateinit var logger: LSShadeTransitionLogger
    @Mock lateinit var dumpManager: DumpManager
    @Mock lateinit var keyguardBypassController: KeyguardBypassController
    @Mock lateinit var lockScreenUserManager: NotificationLockscreenUserManager
    @Mock lateinit var falsingCollector: FalsingCollector
    @Mock lateinit var ambientState: AmbientState
    @Mock lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock lateinit var mediaHierarchyManager: MediaHierarchyManager
    @Mock lateinit var scrimController: ScrimController
    @Mock lateinit var falsingManager: FalsingManager
    @Mock lateinit var notificationPanelController: NotificationPanelViewController
    @Mock lateinit var nsslController: NotificationStackScrollLayoutController
    @Mock lateinit var depthController: NotificationShadeDepthController
    @Mock lateinit var stackscroller: NotificationStackScrollLayout
    @Mock lateinit var expandHelperCallback: ExpandHelper.Callback
    @Mock lateinit var mCentralSurfaces: CentralSurfaces
    @Mock lateinit var qS: QS
    @JvmField @Rule val mockito = MockitoJUnit.rule()

    private val configurationController = FakeConfigurationController()

    @Before
    fun setup() {
        val helper = NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this))
        row = helper.createRow()
        context.getOrCreateTestableResources()
                .addOverride(R.bool.config_use_split_notification_shade, false)
        context.getOrCreateTestableResources()
            .addOverride(R.dimen.lockscreen_shade_depth_controller_transition_distance, 100)
        transitionController = LockscreenShadeTransitionController(
            statusBarStateController = statusbarStateController,
            logger = logger,
            keyguardBypassController = keyguardBypassController,
            lockScreenUserManager = lockScreenUserManager,
            falsingCollector = falsingCollector,
            ambientState = ambientState,
            mediaHierarchyManager = mediaHierarchyManager,
            scrimController = scrimController,
            depthController = depthController,
            wakefulnessLifecycle = wakefulnessLifecycle,
            context = context,
            configurationController = configurationController,
            falsingManager = falsingManager,
            dumpManager = dumpManager
        )
        whenever(nsslController.view).thenReturn(stackscroller)
        whenever(nsslController.expandHelperCallback).thenReturn(expandHelperCallback)
        transitionController.notificationPanelController = notificationPanelController
        transitionController.centralSurfaces = mCentralSurfaces
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
        clearInvocations(mCentralSurfaces)
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
    fun testWakingToShadeLockedWhenDozing() {
        whenever(statusbarStateController.isDozing).thenReturn(true)
        transitionController.goToLockedShade(null)
        verify(statusbarStateController).setState(StatusBarState.SHADE_LOCKED)
        assertTrue("Not waking to shade locked", transitionController.isWakingToShadeLocked)
    }

    @Test
    fun testNotWakingToShadeLockedWhenNotDozing() {
        whenever(statusbarStateController.isDozing).thenReturn(false)
        transitionController.goToLockedShade(null)
        verify(statusbarStateController).setState(StatusBarState.SHADE_LOCKED)
        assertFalse("Waking to shade locked when not dozing",
                transitionController.isWakingToShadeLocked)
    }

    @Test
    fun testGoToLockedShadeOnlyOnKeyguard() {
        whenever(statusbarStateController.state).thenReturn(StatusBarState.SHADE_LOCKED)
        transitionController.goToLockedShade(null)
        whenever(statusbarStateController.state).thenReturn(StatusBarState.SHADE)
        transitionController.goToLockedShade(null)
        verify(statusbarStateController, never()).setState(anyInt())
    }

    @Test
    fun testDontGoWhenShadeDisabled() {
        whenever(mCentralSurfaces.isShadeDisabled).thenReturn(true)
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
        verify(mCentralSurfaces).showBouncerWithDimissAndCancelIfKeyguard(anyObject(), anyObject())
    }

    @Test
    fun testTriggeringBouncerNoNotificationsOnLockscreen() {
        whenever(lockScreenUserManager.shouldShowLockscreenNotifications()).thenReturn(false)
        transitionController.goToLockedShade(null)
        verify(statusbarStateController, never()).setState(anyInt())
        verify(statusbarStateController).setLeaveOpenOnKeyguardHide(true)
        verify(mCentralSurfaces).showBouncerWithDimissAndCancelIfKeyguard(anyObject(), anyObject())
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
        verify(nsslController, never()).setTransitionToFullShadeAmount(anyFloat(), anyFloat())
        verify(mediaHierarchyManager, never()).setTransitionToFullShadeAmount(anyFloat())
        verify(scrimController, never()).setTransitionToFullShadeProgress(anyFloat(), anyFloat())
        verify(notificationPanelController, never()).setTransitionToFullShadeAmount(anyFloat(),
                anyBoolean(), anyLong())
        verify(qS, never()).setTransitionToFullShadeAmount(anyFloat(), anyFloat())
    }

    @Test
    fun testDragDownAmountCallsOut() {
        transitionController.dragDownAmount = 10f
        verify(nsslController).setTransitionToFullShadeAmount(anyFloat(), anyFloat())
        verify(mediaHierarchyManager).setTransitionToFullShadeAmount(anyFloat())
        verify(scrimController).setTransitionToFullShadeProgress(anyFloat(), anyFloat())
        verify(notificationPanelController).setTransitionToFullShadeAmount(anyFloat(),
                anyBoolean(), anyLong())
        verify(qS).setTransitionToFullShadeAmount(anyFloat(), anyFloat())
        verify(depthController).transitionToFullShadeProgress = anyFloat()
    }

    @Test
    fun testDragDownAmount_depthDistanceIsZero_setsProgressToZero() {
        context.getOrCreateTestableResources()
            .addOverride(R.dimen.lockscreen_shade_depth_controller_transition_distance, 0)
        configurationController.notifyConfigurationChanged()

        transitionController.dragDownAmount = 10f

        verify(depthController).transitionToFullShadeProgress = 0f
    }

    @Test
    fun testDragDownAmount_depthDistanceNonZero_setsProgressBasedOnDistance() {
        context.getOrCreateTestableResources()
            .addOverride(R.dimen.lockscreen_shade_depth_controller_transition_distance, 100)
        configurationController.notifyConfigurationChanged()

        transitionController.dragDownAmount = 10f

        verify(depthController).transitionToFullShadeProgress = 0.1f
    }

    @Test
    fun setDragAmount_setsKeyguardTransitionProgress() {
        transitionController.dragDownAmount = 10f

        verify(notificationPanelController).setKeyguardTransitionProgress(anyFloat(), anyInt())
    }

    @Test
    fun setDragAmount_setsKeyguardAlphaBasedOnDistance() {
        val alphaDistance = context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_npvc_keyguard_content_alpha_transition_distance)
        transitionController.dragDownAmount = 10f

        val expectedAlpha = 1 - 10f / alphaDistance
        verify(notificationPanelController)
                .setKeyguardTransitionProgress(eq(expectedAlpha), anyInt())
    }

    @Test
    fun setDragAmount_notInSplitShade_setsKeyguardTranslationToZero() {
        val mediaTranslationY = 123
        disableSplitShade()
        whenever(mediaHierarchyManager.getGuidedTransformationTranslationY())
                .thenReturn(mediaTranslationY)

        transitionController.dragDownAmount = 10f

        verify(notificationPanelController).setKeyguardTransitionProgress(anyFloat(), eq(0))
    }

    @Test
    fun setDragAmount_inSplitShade_setsKeyguardTranslationBasedOnMediaTranslation() {
        val mediaTranslationY = 123
        enableSplitShade()
        whenever(mediaHierarchyManager.getGuidedTransformationTranslationY())
                .thenReturn(mediaTranslationY)

        transitionController.dragDownAmount = 10f

        verify(notificationPanelController)
                .setKeyguardTransitionProgress(anyFloat(), eq(mediaTranslationY))
    }

    @Test
    fun setDragDownAmount_setsValueOnMediaHierarchyManager() {
        transitionController.dragDownAmount = 10f

        verify(mediaHierarchyManager).setTransitionToFullShadeAmount(10f)
    }

    @Test
    fun setDragAmount_setsScrimProgressBasedOnScrimDistance() {
        val distance = 10
        context.orCreateTestableResources
                .addOverride(R.dimen.lockscreen_shade_scrim_transition_distance, distance)
        configurationController.notifyConfigurationChanged()

        transitionController.dragDownAmount = 5f

        verify(scrimController).transitionToFullShadeProgress(
                progress = eq(0.5f),
                lockScreenNotificationsProgress = anyFloat()
        )
    }

    @Test
    fun setDragAmount_setsNotificationsScrimProgressBasedOnNotificationsScrimDistanceAndDelay() {
        val distance = 100
        val delay = 10
        context.orCreateTestableResources.addOverride(
                R.dimen.lockscreen_shade_notifications_scrim_transition_distance, distance)
        context.orCreateTestableResources.addOverride(
                R.dimen.lockscreen_shade_notifications_scrim_transition_delay, delay)
        configurationController.notifyConfigurationChanged()

        transitionController.dragDownAmount = 20f

        verify(scrimController).transitionToFullShadeProgress(
                progress = anyFloat(),
                lockScreenNotificationsProgress = eq(0.1f)
        )
    }

    @Test
    fun setDragAmount_dragAmountLessThanNotifDelayDistance_setsNotificationsScrimProgressToZero() {
        val distance = 100
        val delay = 50
        context.orCreateTestableResources.addOverride(
                R.dimen.lockscreen_shade_notifications_scrim_transition_distance, distance)
        context.orCreateTestableResources.addOverride(
                R.dimen.lockscreen_shade_notifications_scrim_transition_delay, delay)
        configurationController.notifyConfigurationChanged()

        transitionController.dragDownAmount = 20f

        verify(scrimController).transitionToFullShadeProgress(
                progress = anyFloat(),
                lockScreenNotificationsProgress = eq(0f)
        )
    }

    @Test
    fun setDragAmount_dragAmountMoreThanTotalDistance_setsNotificationsScrimProgressToOne() {
        val distance = 100
        val delay = 50
        context.orCreateTestableResources.addOverride(
                R.dimen.lockscreen_shade_notifications_scrim_transition_distance, distance)
        context.orCreateTestableResources.addOverride(
                R.dimen.lockscreen_shade_notifications_scrim_transition_delay, delay)
        configurationController.notifyConfigurationChanged()

        transitionController.dragDownAmount = 999999f

        verify(scrimController).transitionToFullShadeProgress(
                progress = anyFloat(),
                lockScreenNotificationsProgress = eq(1f)
        )
    }

    @Test
    fun setDragDownAmount_inSplitShade_setsValueOnMediaHierarchyManager() {
        enableSplitShade()

        transitionController.dragDownAmount = 10f

        verify(mediaHierarchyManager).setTransitionToFullShadeAmount(10f)
    }

    private fun enableSplitShade() {
        setSplitShadeEnabled(true)
    }

    private fun disableSplitShade() {
        setSplitShadeEnabled(false)
    }

    private fun setSplitShadeEnabled(enabled: Boolean) {
        overrideResource(R.bool.config_use_split_notification_shade, enabled)
        configurationController.notifyConfigurationChanged()
    }

    /**
     * Wrapper around [ScrimController.transitionToFullShadeProgress] that has named parameters for
     * clarify and easier refactoring of parameter names.
     */
    private fun ScrimController.transitionToFullShadeProgress(
        progress: Float,
        lockScreenNotificationsProgress: Float
    ) {
        scrimController.setTransitionToFullShadeProgress(
                progress,
                lockScreenNotificationsProgress
        )
    }
}
