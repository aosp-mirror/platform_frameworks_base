package com.android.systemui.qs

import android.os.UserManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.LayoutInflater
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.testing.FakeMetricsLogger
import com.android.systemui.Dependency
import com.android.systemui.R
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.FooterActionsController.ExpansionState
import com.android.systemui.statusbar.phone.MultiUserSwitchController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.tuner.TunerService
import com.android.systemui.utils.leaks.FakeTunerService
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
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner::class)
class FooterActionsControllerTest : LeakCheckedTest() {
    @Mock
    private lateinit var userManager: UserManager
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock
    private lateinit var userInfoController: UserInfoController
    @Mock
    private lateinit var qsPanelController: QSPanelController
    @Mock
    private lateinit var multiUserSwitchController: MultiUserSwitchController
    @Mock
    private lateinit var globalActionsDialog: GlobalActionsDialogLite
    @Mock
    private lateinit var uiEventLogger: UiEventLogger
    @Mock
    private lateinit var controller: FooterActionsController

    private val metricsLogger: MetricsLogger = FakeMetricsLogger()
    private lateinit var view: FooterActionsView
    private val falsingManager: FalsingManagerFake = FalsingManagerFake()
    private lateinit var testableLooper: TestableLooper

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        injectLeakCheckedDependencies(*LeakCheckedTest.ALL_SUPPORTED_CLASSES)
        val fakeTunerService = Dependency.get(TunerService::class.java) as FakeTunerService

        view = LayoutInflater.from(context)
                .inflate(R.layout.footer_actions, null) as FooterActionsView

        controller = FooterActionsController(view, qsPanelController, activityStarter,
                userManager, userInfoController, multiUserSwitchController,
                deviceProvisionedController, falsingManager, metricsLogger, fakeTunerService,
                globalActionsDialog, uiEventLogger, showPMLiteButton = true,
                buttonsVisibleState = ExpansionState.EXPANDED)
        controller.init()
        ViewUtils.attachView(view)
        // View looper is the testable looper associated with the test
        testableLooper.processAllMessages()
    }

    @After
    fun tearDown() {
        ViewUtils.detachView(view)
    }

    @Test
    fun testLogPowerMenuClick() {
        controller.expanded = true
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
}