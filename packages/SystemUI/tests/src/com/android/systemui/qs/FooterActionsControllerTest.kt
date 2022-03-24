package com.android.systemui.qs

import android.os.Handler
import android.os.UserManager
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.LayoutInflater
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.testing.FakeMetricsLogger
import com.android.systemui.R
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.MultiUserSwitchController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.utils.leaks.LeakCheckedTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import javax.inject.Provider
import org.mockito.Mockito.`when` as whenever

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class FooterActionsControllerTest : LeakCheckedTest() {
    @Mock
    private lateinit var userManager: UserManager
    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock
    private lateinit var userInfoController: UserInfoController
    @Mock
    private lateinit var multiUserSwitchControllerFactory: MultiUserSwitchController.Factory
    @Mock
    private lateinit var multiUserSwitchController: MultiUserSwitchController
    @Mock
    private lateinit var globalActionsDialogProvider: Provider<GlobalActionsDialogLite>
    @Mock
    private lateinit var globalActionsDialog: GlobalActionsDialogLite
    @Mock
    private lateinit var uiEventLogger: UiEventLogger
    @Mock
    private lateinit var featureFlags: FeatureFlags
    @Mock
    private lateinit var securityFooterController: QSSecurityFooter
    @Mock
    private lateinit var fgsManagerController: QSFgsManagerFooter

    private lateinit var controller: FooterActionsController

    private val metricsLogger: MetricsLogger = FakeMetricsLogger()
    private lateinit var view: FooterActionsView
    private val falsingManager: FalsingManagerFake = FalsingManagerFake()
    private lateinit var testableLooper: TestableLooper
    private lateinit var fakeSettings: FakeSettings

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        fakeSettings = FakeSettings()

        whenever(multiUserSwitchControllerFactory.create(any()))
                .thenReturn(multiUserSwitchController)
        whenever(featureFlags.isEnabled(Flags.NEW_FOOTER)).thenReturn(false)
        whenever(globalActionsDialogProvider.get()).thenReturn(globalActionsDialog)

        view = inflateView()

        controller = constructFooterActionsController(view)
        controller.init()
        ViewUtils.attachView(view)
        // View looper is the testable looper associated with the test
        testableLooper.processAllMessages()
    }

    @After
    fun tearDown() {
        if (view.isAttachedToWindow) {
            ViewUtils.detachView(view)
        }
    }

    @Test
    fun testLogPowerMenuClick() {
        controller.visible = true
        falsingManager.setFalseTap(false)

        view.findViewById<View>(R.id.pm_lite).performClick()
        // Verify clicks are logged
        verify(uiEventLogger, Mockito.times(1))
                .log(GlobalActionsDialogLite.GlobalActionsEvent.GA_OPEN_QS)
    }

    @Test
    fun testSettings_UserNotSetup() {
        whenever(deviceProvisionedController.isCurrentUserSetup).thenReturn(false)
        view.findViewById<View>(R.id.settings_button).performClick()
        // Verify Settings wasn't launched.
        verify<ActivityStarter>(activityStarter, Mockito.never()).startActivity(any(), anyBoolean())
    }

    @Test
    fun testMultiUserSwitchUpdatedWhenExpansionStarts() {
        // When expansion starts, listening is set to true
        val multiUserSwitch = view.requireViewById<View>(R.id.multi_user_switch)

        assertThat(multiUserSwitch.visibility).isNotEqualTo(View.VISIBLE)

        whenever(multiUserSwitchController.isMultiUserEnabled).thenReturn(true)

        controller.setListening(true)
        testableLooper.processAllMessages()

        assertThat(multiUserSwitch.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testMultiUserSwitchUpdatedWhenSettingChanged() {
        // Always listening to setting while View is attached
        testableLooper.processAllMessages()

        val multiUserSwitch = view.requireViewById<View>(R.id.multi_user_switch)
        assertThat(multiUserSwitch.visibility).isNotEqualTo(View.VISIBLE)

        // The setting is only used as an indicator for whether the view should refresh. The actual
        // value of the setting is ignored; isMultiUserEnabled is the source of truth
        whenever(multiUserSwitchController.isMultiUserEnabled).thenReturn(true)

        // Changing the value of USER_SWITCHER_ENABLED should cause the view to update
        fakeSettings.putIntForUser(Settings.Global.USER_SWITCHER_ENABLED, 1, userTracker.userId)
        testableLooper.processAllMessages()

        assertThat(multiUserSwitch.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testMultiUserSettingNotListenedAfterDetach() {
        testableLooper.processAllMessages()

        val multiUserSwitch = view.requireViewById<View>(R.id.multi_user_switch)
        assertThat(multiUserSwitch.visibility).isNotEqualTo(View.VISIBLE)

        ViewUtils.detachView(view)

        // The setting is only used as an indicator for whether the view should refresh. The actual
        // value of the setting is ignored; isMultiUserEnabled is the source of truth
        whenever(multiUserSwitchController.isMultiUserEnabled).thenReturn(true)

        // Changing the value of USER_SWITCHER_ENABLED should cause the view to update
        fakeSettings.putIntForUser(Settings.Global.USER_SWITCHER_ENABLED, 1, userTracker.userId)
        testableLooper.processAllMessages()

        assertThat(multiUserSwitch.visibility).isNotEqualTo(View.VISIBLE)
    }

    @Test
    fun testCleanUpGAD() {
        reset(globalActionsDialogProvider)
        whenever(globalActionsDialogProvider.get()).thenReturn(globalActionsDialog)
        val view = inflateView()
        controller = constructFooterActionsController(view)
        controller.init()
        verify(globalActionsDialogProvider, never()).get()

        // GAD is constructed during attachment
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()
        verify(globalActionsDialogProvider).get()

        ViewUtils.detachView(view)
        testableLooper.processAllMessages()
        verify(globalActionsDialog).destroy()
    }

    private fun inflateView(): FooterActionsView {
        return LayoutInflater.from(context)
                .inflate(R.layout.footer_actions, null) as FooterActionsView
    }

    private fun constructFooterActionsController(view: FooterActionsView): FooterActionsController {
        return FooterActionsController(view, multiUserSwitchControllerFactory,
                activityStarter, userManager, userTracker, userInfoController,
                deviceProvisionedController, securityFooterController, fgsManagerController,
                falsingManager, metricsLogger, globalActionsDialogProvider, uiEventLogger,
                showPMLiteButton = true, fakeSettings, Handler(testableLooper.looper), featureFlags)
    }
}