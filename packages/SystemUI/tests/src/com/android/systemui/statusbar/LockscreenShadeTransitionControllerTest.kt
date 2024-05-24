package com.android.systemui.statusbar

import android.app.StatusBarManager.DISABLE2_NOTIFICATION_SHADE
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.ExpandHelper
import com.android.systemui.SysUITestModule
import com.android.systemui.SysuiTestCase
import com.android.systemui.TestMocksModule
import com.android.systemui.classifier.FalsingCollectorFake
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FakeFeatureFlagsClassicModule
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.NaturalScrollingSettingObserver
import com.android.systemui.media.controls.ui.controller.MediaHierarchyManager
import com.android.systemui.plugins.qs.QS
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.qs.ui.adapter.FakeQSSceneAdapter
import com.android.systemui.res.R
import com.android.systemui.shade.ShadeLockscreenInteractor
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.disableflags.data.model.DisableFlagsModel
import com.android.systemui.statusbar.disableflags.data.repository.FakeDisableFlagsRepository
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController
import com.android.systemui.statusbar.phone.CentralSurfaces
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
import com.android.systemui.user.domain.UserDomainLayerModule
import com.android.systemui.util.mockito.mock
import dagger.BindsInstance
import dagger.Component
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.junit.MockitoJUnit
import org.mockito.Mockito.`when` as whenever

private fun <T> anyObject(): T {
    return Mockito.anyObject<T>()
}

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LockscreenShadeTransitionControllerTest : SysuiTestCase() {

    private lateinit var transitionController: LockscreenShadeTransitionController
    private lateinit var testComponent: TestComponent
    private val configurationController
        get() = testComponent.configurationController
    private val disableFlagsRepository
        get() = testComponent.disableFlagsRepository
    private val testScope
        get() = testComponent.testScope

    private val qsSceneAdapter = FakeQSSceneAdapter({ mock() })

    lateinit var row: ExpandableNotificationRow

    @Mock lateinit var centralSurfaces: CentralSurfaces
    @Mock lateinit var depthController: NotificationShadeDepthController
    @Mock lateinit var expandHelperCallback: ExpandHelper.Callback
    @Mock lateinit var keyguardBypassController: KeyguardBypassController
    @Mock lateinit var lockScreenUserManager: NotificationLockscreenUserManager
    @Mock lateinit var mediaHierarchyManager: MediaHierarchyManager
    @Mock lateinit var nsslController: NotificationStackScrollLayoutController
    @Mock lateinit var qS: QS
    @Mock lateinit var qsTransitionController: LockscreenShadeQsTransitionController
    @Mock lateinit var scrimController: ScrimController
    @Mock lateinit var shadeLockscreenInteractor: ShadeLockscreenInteractor
    @Mock lateinit var singleShadeOverScroller: SingleShadeLockScreenOverScroller
    @Mock lateinit var splitShadeOverScroller: SplitShadeLockScreenOverScroller
    @Mock lateinit var stackscroller: NotificationStackScrollLayout
    @Mock lateinit var statusbarStateController: SysuiStatusBarStateController
    @Mock lateinit var transitionControllerCallback: LockscreenShadeTransitionController.Callback
    @Mock lateinit var naturalScrollingSettingObserver: NaturalScrollingSettingObserver

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Before
    fun setup() {
        val helper = NotificationTestHelper(mContext, mDependency, TestableLooper.get(this))
        row = helper.createRow()
        context
            .getOrCreateTestableResources()
            .addOverride(R.bool.config_use_split_notification_shade, false)
        context
            .getOrCreateTestableResources()
            .addOverride(R.dimen.lockscreen_shade_depth_controller_transition_distance, 100)

        whenever(nsslController.view).thenReturn(stackscroller)
        whenever(nsslController.expandHelperCallback).thenReturn(expandHelperCallback)
        whenever(statusbarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        whenever(nsslController.isInLockedDownShade).thenReturn(false)
        whenever(qS.isFullyCollapsed).thenReturn(true)
        whenever(lockScreenUserManager.userAllowsPrivateNotificationsInPublic(anyInt()))
            .thenReturn(true)
        whenever(lockScreenUserManager.shouldShowLockscreenNotifications()).thenReturn(true)
        whenever(lockScreenUserManager.isLockscreenPublicMode(anyInt())).thenReturn(true)
        whenever(keyguardBypassController.bypassEnabled).thenReturn(false)
        whenever(naturalScrollingSettingObserver.isNaturalScrollingEnabled).thenReturn(true)

        testComponent =
            DaggerLockscreenShadeTransitionControllerTest_TestComponent.factory()
                .create(
                    test = this,
                    featureFlags =
                        FakeFeatureFlagsClassicModule {
                            set(Flags.FULL_SCREEN_USER_SWITCHER, false)
                        },
                    mocks =
                        TestMocksModule(
                            notificationShadeDepthController = depthController,
                            keyguardBypassController = keyguardBypassController,
                            mediaHierarchyManager = mediaHierarchyManager,
                            notificationLockscreenUserManager = lockScreenUserManager,
                            notificationStackScrollLayoutController = nsslController,
                            scrimController = scrimController,
                            statusBarStateController = statusbarStateController,
                        )
                )

        transitionController =
            LockscreenShadeTransitionController(
                statusBarStateController = statusbarStateController,
                logger = mock(),
                keyguardBypassController = keyguardBypassController,
                lockScreenUserManager = lockScreenUserManager,
                falsingCollector = FalsingCollectorFake(),
                ambientState = mock(),
                mediaHierarchyManager = mediaHierarchyManager,
                scrimTransitionController =
                    LockscreenShadeScrimTransitionController(
                        scrimController = scrimController,
                        context = context,
                        configurationController = configurationController,
                        dumpManager = mock(),
                        splitShadeStateController = ResourcesSplitShadeStateController()
                    ),
                keyguardTransitionControllerFactory = { notificationPanelController ->
                    LockscreenShadeKeyguardTransitionController(
                        mediaHierarchyManager = mediaHierarchyManager,
                        shadeLockscreenInteractor = notificationPanelController,
                        context = context,
                        configurationController = configurationController,
                        dumpManager = mock(),
                        splitShadeStateController = ResourcesSplitShadeStateController()
                    )
                },
                depthController = depthController,
                context = context,
                splitShadeOverScrollerFactory = { _, _ -> splitShadeOverScroller },
                singleShadeOverScrollerFactory = { singleShadeOverScroller },
                activityStarter = mock(),
                wakefulnessLifecycle = mock(),
                configurationController = configurationController,
                falsingManager = FalsingManagerFake(),
                dumpManager = mock(),
                qsTransitionControllerFactory = { qsTransitionController },
                shadeRepository = testComponent.shadeRepository,
                shadeInteractor = testComponent.shadeInteractor,
                splitShadeStateController = ResourcesSplitShadeStateController(),
                shadeLockscreenInteractorLazy = {shadeLockscreenInteractor},
                naturalScrollingSettingObserver = naturalScrollingSettingObserver,
                lazyQSSceneAdapter = { qsSceneAdapter }
            )

        transitionController.addCallback(transitionControllerCallback)
        transitionController.centralSurfaces = centralSurfaces
        transitionController.qS = qS
        transitionController.setStackScroller(nsslController)
        clearInvocations(centralSurfaces)

        testScope.runCurrent()
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
        assertFalse(
            "Waking to shade locked when not dozing",
            transitionController.isWakingToShadeLocked
        )
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
        disableFlagsRepository.disableFlags.value =
            DisableFlagsModel(
                disable2 = DISABLE2_NOTIFICATION_SHADE,
            )
        testScope.runCurrent()
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
    fun testTriggeringBouncerNoNotificationsOnLockscreen() {
        whenever(lockScreenUserManager.shouldShowLockscreenNotifications()).thenReturn(false)
        transitionController.goToLockedShade(null)
        verify(statusbarStateController, never()).setState(anyInt())
        verify(statusbarStateController).setLeaveOpenOnKeyguardHide(true)
        verify(centralSurfaces).showBouncerWithDimissAndCancelIfKeyguard(anyObject(), anyObject())
    }

    @Test
    fun testGoToLockedShadeCreatesQSAnimation() {
        transitionController.goToLockedShade(null)
        verify(statusbarStateController).setState(StatusBarState.SHADE_LOCKED)
        verify(shadeLockscreenInteractor).transitionToExpandedShade(anyLong())
        assertNotNull(transitionController.dragDownAnimator)
    }

    @Test
    fun testGoToLockedShadeDoesntCreateQSAnimation() {
        transitionController.goToLockedShade(null, needsQSAnimation = false)
        verify(statusbarStateController).setState(StatusBarState.SHADE_LOCKED)
        verify(shadeLockscreenInteractor).transitionToExpandedShade(anyLong())
        assertNull(transitionController.dragDownAnimator)
    }

    @Test
    fun testGoToLockedShadeAlwaysCreatesQSAnimationInSplitShade() {
        enableSplitShade()
        transitionController.goToLockedShade(null, needsQSAnimation = true)
        verify(shadeLockscreenInteractor).transitionToExpandedShade(anyLong())
        assertNotNull(transitionController.dragDownAnimator)
    }

    @Test
    fun testDragDownAmountDoesntCallOutInLockedDownShade() {
        whenever(nsslController.isInLockedDownShade).thenReturn(true)
        transitionController.dragDownAmount = 10f
        verify(nsslController, never()).setTransitionToFullShadeAmount(anyFloat())
        verify(mediaHierarchyManager, never()).setTransitionToFullShadeAmount(anyFloat())
        verify(scrimController, never()).setTransitionToFullShadeProgress(anyFloat(), anyFloat())
        verify(transitionControllerCallback, never())
            .setTransitionToFullShadeAmount(anyFloat(), anyBoolean(), anyLong())
        verify(qsTransitionController, never()).dragDownAmount = anyFloat()
    }

    @Test
    fun testDragDownAmountCallsOut() {
        transitionController.dragDownAmount = 10f
        verify(nsslController).setTransitionToFullShadeAmount(anyFloat())
        verify(mediaHierarchyManager).setTransitionToFullShadeAmount(anyFloat())
        verify(scrimController).setTransitionToFullShadeProgress(anyFloat(), anyFloat())
        verify(transitionControllerCallback)
            .setTransitionToFullShadeAmount(anyFloat(), anyBoolean(), anyLong())
        verify(qsTransitionController).dragDownAmount = 10f
        verify(depthController).transitionToFullShadeProgress = anyFloat()
    }

    @Test
    fun testDragDownAmount_depthDistanceIsZero_setsProgressToZero() {
        context
            .getOrCreateTestableResources()
            .addOverride(R.dimen.lockscreen_shade_depth_controller_transition_distance, 0)
        configurationController.notifyConfigurationChanged()

        transitionController.dragDownAmount = 10f

        verify(depthController).transitionToFullShadeProgress = 0f
    }

    @Test
    fun testDragDownAmount_depthDistanceNonZero_setsProgressBasedOnDistance() {
        context
            .getOrCreateTestableResources()
            .addOverride(R.dimen.lockscreen_shade_depth_controller_transition_distance, 100)
        configurationController.notifyConfigurationChanged()

        transitionController.dragDownAmount = 10f

        verify(depthController).transitionToFullShadeProgress = 0.1f
    }

    @Test
    fun setDragAmount_setsKeyguardTransitionProgress() {
        transitionController.dragDownAmount = 10f

        verify(shadeLockscreenInteractor).setKeyguardTransitionProgress(anyFloat(), anyInt())
    }

    @Test
    fun setDragAmount_setsKeyguardAlphaBasedOnDistance() {
        val alphaDistance =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_npvc_keyguard_content_alpha_transition_distance
            )
        transitionController.dragDownAmount = 10f

        val expectedAlpha = 1 - 10f / alphaDistance
        verify(shadeLockscreenInteractor).setKeyguardTransitionProgress(eq(expectedAlpha), anyInt())
    }

    @Test
    fun setDragAmount_notInSplitShade_setsKeyguardTranslationToZero() {
        val mediaTranslationY = 123
        disableSplitShade()
        whenever(mediaHierarchyManager.isCurrentlyInGuidedTransformation()).thenReturn(true)
        whenever(mediaHierarchyManager.getGuidedTransformationTranslationY())
            .thenReturn(mediaTranslationY)

        transitionController.dragDownAmount = 10f

        verify(shadeLockscreenInteractor).setKeyguardTransitionProgress(anyFloat(), eq(0))
    }

    @Test
    fun setDragAmount_inSplitShade_setsKeyguardTranslationBasedOnMediaTranslation() {
        val mediaTranslationY = 123
        enableSplitShade()
        whenever(mediaHierarchyManager.isCurrentlyInGuidedTransformation()).thenReturn(true)
        whenever(mediaHierarchyManager.getGuidedTransformationTranslationY())
            .thenReturn(mediaTranslationY)

        transitionController.dragDownAmount = 10f

        verify(shadeLockscreenInteractor)
                .setKeyguardTransitionProgress(anyFloat(), eq(mediaTranslationY))
    }

    @Test
    fun setDragAmount_inSplitShade_mediaNotShowing_setsKeyguardTranslationBasedOnDistance() {
        enableSplitShade()
        whenever(mediaHierarchyManager.isCurrentlyInGuidedTransformation()).thenReturn(false)
        whenever(mediaHierarchyManager.getGuidedTransformationTranslationY()).thenReturn(123)

        transitionController.dragDownAmount = 10f

        val distance =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_keyguard_transition_distance
            )
        val offset =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_keyguard_transition_vertical_offset
            )
        val expectedTranslation = 10f / distance * offset
        verify(shadeLockscreenInteractor)
            .setKeyguardTransitionProgress(anyFloat(), eq(expectedTranslation.toInt()))
    }

    @Test
    fun setDragDownAmount_setsValueOnMediaHierarchyManager() {
        transitionController.dragDownAmount = 10f

        verify(mediaHierarchyManager).setTransitionToFullShadeAmount(10f)
    }

    @Test
    fun setDragAmount_setsScrimProgressBasedOnScrimDistance() {
        val distance = 10
        context.orCreateTestableResources.addOverride(
            R.dimen.lockscreen_shade_scrim_transition_distance,
            distance
        )
        configurationController.notifyConfigurationChanged()

        transitionController.dragDownAmount = 5f

        verify(scrimController)
            .transitionToFullShadeProgress(
                progress = eq(0.5f),
                lockScreenNotificationsProgress = anyFloat()
            )
    }

    @Test
    fun setDragAmount_setsNotificationsScrimProgressBasedOnNotificationsScrimDistanceAndDelay() {
        val distance = 100
        val delay = 10
        context.orCreateTestableResources.addOverride(
            R.dimen.lockscreen_shade_notifications_scrim_transition_distance,
            distance
        )
        context.orCreateTestableResources.addOverride(
            R.dimen.lockscreen_shade_notifications_scrim_transition_delay,
            delay
        )
        configurationController.notifyConfigurationChanged()

        transitionController.dragDownAmount = 20f

        verify(scrimController)
            .transitionToFullShadeProgress(
                progress = anyFloat(),
                lockScreenNotificationsProgress = eq(0.1f)
            )
    }

    @Test
    fun setDragAmount_dragAmountLessThanNotifDelayDistance_setsNotificationsScrimProgressToZero() {
        val distance = 100
        val delay = 50
        context.orCreateTestableResources.addOverride(
            R.dimen.lockscreen_shade_notifications_scrim_transition_distance,
            distance
        )
        context.orCreateTestableResources.addOverride(
            R.dimen.lockscreen_shade_notifications_scrim_transition_delay,
            delay
        )
        configurationController.notifyConfigurationChanged()

        transitionController.dragDownAmount = 20f

        verify(scrimController)
            .transitionToFullShadeProgress(
                progress = anyFloat(),
                lockScreenNotificationsProgress = eq(0f)
            )
    }

    @Test
    fun setDragAmount_dragAmountMoreThanTotalDistance_setsNotificationsScrimProgressToOne() {
        val distance = 100
        val delay = 50
        context.orCreateTestableResources.addOverride(
            R.dimen.lockscreen_shade_notifications_scrim_transition_distance,
            distance
        )
        context.orCreateTestableResources.addOverride(
            R.dimen.lockscreen_shade_notifications_scrim_transition_delay,
            delay
        )
        configurationController.notifyConfigurationChanged()

        transitionController.dragDownAmount = 999999f

        verify(scrimController)
            .transitionToFullShadeProgress(
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

    @Test
    fun setDragAmount_notInSplitShade_forwardsToSingleShadeOverScroller() {
        disableSplitShade()

        transitionController.dragDownAmount = 10f

        verify(singleShadeOverScroller).expansionDragDownAmount = 10f
        verifyZeroInteractions(splitShadeOverScroller)
    }

    @Test
    fun setDragAmount_inSplitShade_forwardsToSplitShadeOverScroller() {
        enableSplitShade()

        transitionController.dragDownAmount = 10f

        verify(splitShadeOverScroller).expansionDragDownAmount = 10f
        verifyZeroInteractions(singleShadeOverScroller)
    }

    @Test
    fun setDragDownAmount_inSplitShade_setsKeyguardStatusBarAlphaBasedOnDistance() {
        val alphaDistance =
            context.resources.getDimensionPixelSize(
                R.dimen.lockscreen_shade_npvc_keyguard_content_alpha_transition_distance
            )
        val dragDownAmount = 10f
        enableSplitShade()

        transitionController.dragDownAmount = dragDownAmount

        val expectedAlpha = 1 - dragDownAmount / alphaDistance
        verify(shadeLockscreenInteractor).setKeyguardStatusBarAlpha(expectedAlpha)
    }

    @Test
    fun setDragDownAmount_notInSplitShade_setsKeyguardStatusBarAlphaToMinusOne() {
        disableSplitShade()

        transitionController.dragDownAmount = 10f

        verify(shadeLockscreenInteractor).setKeyguardStatusBarAlpha(-1f)
    }

    @Test
    fun nullQs_canDragDownFromAdapter() {
        transitionController.qS = null

        qsSceneAdapter.isQsFullyCollapsed = true
        assertTrue("Can't drag down on keyguard", transitionController.canDragDown())
        qsSceneAdapter.isQsFullyCollapsed = false
        assertFalse("Can drag down when QS is expanded", transitionController.canDragDown())
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
        setTransitionToFullShadeProgress(progress, lockScreenNotificationsProgress)
    }

    @SysUISingleton
    @Component(
        modules =
            [
                SysUITestModule::class,
                UserDomainLayerModule::class,
            ]
    )
    interface TestComponent {

        val configurationController: FakeConfigurationController
        val disableFlagsRepository: FakeDisableFlagsRepository
        val powerInteractor: PowerInteractor
        val shadeInteractor: ShadeInteractor
        val shadeRepository: FakeShadeRepository
        val testScope: TestScope

        @Component.Factory
        interface Factory {
            fun create(
                    @BindsInstance test: SysuiTestCase,
                    featureFlags: FakeFeatureFlagsClassicModule,
                    mocks: TestMocksModule,
            ): TestComponent
        }
    }
}
