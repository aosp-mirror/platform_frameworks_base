package com.android.systemui.qs

import com.android.systemui.R
import android.os.UserManager
import android.view.LayoutInflater
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.testing.FakeMetricsLogger
import com.android.systemui.Dependency
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.phone.MultiUserSwitchController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.tuner.TunerService
import com.android.systemui.utils.leaks.FakeTunerService
import com.android.systemui.utils.leaks.LeakCheckedTest
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
class QSFooterActionsControllerTest : LeakCheckedTest() {
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
    private lateinit var controller: QSFooterActionsController

    private val metricsLogger: MetricsLogger = FakeMetricsLogger()
    private lateinit var view: QSFooterActionsView
    private val falsingManager: FalsingManagerFake = FalsingManagerFake()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        injectLeakCheckedDependencies(*LeakCheckedTest.ALL_SUPPORTED_CLASSES)
        val fakeTunerService = Dependency.get(TunerService::class.java) as FakeTunerService

        view = LayoutInflater.from(context)
                .inflate(R.layout.qs_footer_actions, null) as QSFooterActionsView

        controller = QSFooterActionsController(view, qsPanelController, activityStarter,
                userManager, userInfoController, multiUserSwitchController,
                deviceProvisionedController, falsingManager, metricsLogger, fakeTunerService,
                globalActionsDialog, uiEventLogger, showPMLiteButton = true)
        controller.init()
        controller.onViewAttached()
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
}