package com.android.systemui.qs

import android.content.Context
import android.permission.PermissionManager
import android.provider.DeviceConfig
import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.appops.AppOpsController
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.privacy.PrivacyDialogController
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.DeviceConfigProxyFake
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.concurrent.Executor
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class HeaderPrivacyIconsControllerTest : SysuiTestCase() {

    companion object {
        const val SAFETY_CENTER_ENABLED = "safety_center_is_enabled"
    }

    @Mock
    private lateinit var privacyItemController: PrivacyItemController
    @Mock
    private lateinit var uiEventLogger: UiEventLogger
    @Mock
    private lateinit var privacyChip: OngoingPrivacyChip
    @Mock
    private lateinit var privacyDialogController: PrivacyDialogController
    @Mock
    private lateinit var privacyLogger: PrivacyLogger
    @Mock
    private lateinit var iconContainer: StatusIconContainer
    @Mock
    private lateinit var permissionManager: PermissionManager
    @Mock
    private lateinit var backgroundExecutor: Executor
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var appOpsController: AppOpsController

    private val uiExecutor = FakeExecutor(FakeSystemClock())
    private lateinit var deviceConfigProxy: DeviceConfigProxy
    private lateinit var cameraSlotName: String
    private lateinit var microphoneSlotName: String
    private lateinit var locationSlotName: String
    private lateinit var controller: HeaderPrivacyIconsController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(privacyChip.context).thenReturn(context)
        whenever(privacyChip.resources).thenReturn(context.resources)

        cameraSlotName = context.getString(com.android.internal.R.string.status_bar_camera)
        microphoneSlotName = context.getString(com.android.internal.R.string.status_bar_microphone)
        locationSlotName = context.getString(com.android.internal.R.string.status_bar_location)
        deviceConfigProxy = DeviceConfigProxyFake()

        controller = HeaderPrivacyIconsController(
                privacyItemController,
                uiEventLogger,
                privacyChip,
                privacyDialogController,
                privacyLogger,
                iconContainer,
                permissionManager,
                backgroundExecutor,
                uiExecutor,
                activityStarter,
                appOpsController,
                deviceConfigProxy
        )
    }

    @Test
    fun testIgnoredSlotsOnParentVisible_noIndicators() {
        setPrivacyController(micCamera = false, location = false)

        controller.onParentVisible()

        verify(iconContainer).removeIgnoredSlot(cameraSlotName)
        verify(iconContainer).removeIgnoredSlot(microphoneSlotName)
        verify(iconContainer).removeIgnoredSlot(locationSlotName)
    }

    @Test
    fun testIgnoredSlotsOnParentVisible_onlyMicCamera() {
        setPrivacyController(micCamera = true, location = false)

        controller.onParentVisible()

        verify(iconContainer).addIgnoredSlot(cameraSlotName)
        verify(iconContainer).addIgnoredSlot(microphoneSlotName)
        verify(iconContainer).removeIgnoredSlot(locationSlotName)
    }

    @Test
    fun testIgnoredSlotsOnParentVisible_onlyLocation() {
        setPrivacyController(micCamera = false, location = true)

        controller.onParentVisible()

        verify(iconContainer).removeIgnoredSlot(cameraSlotName)
        verify(iconContainer).removeIgnoredSlot(microphoneSlotName)
        verify(iconContainer).addIgnoredSlot(locationSlotName)
    }

    @Test
    fun testIgnoredSlotsOnParentVisible_locationMicCamera() {
        setPrivacyController(micCamera = true, location = true)

        controller.onParentVisible()

        verify(iconContainer).addIgnoredSlot(cameraSlotName)
        verify(iconContainer).addIgnoredSlot(microphoneSlotName)
        verify(iconContainer).addIgnoredSlot(locationSlotName)
    }

    @Test
    fun testPrivacyChipClicked() {
        changeProperty(SAFETY_CENTER_ENABLED, false)
        controller.onParentVisible()

        val captor = argumentCaptor<View.OnClickListener>()
        verify(privacyChip).setOnClickListener(capture(captor))
        captor.value.onClick(privacyChip)

        verify(privacyDialogController).showDialog(any(Context::class.java))
    }

    @Test
    fun testSafetyCenterFlag() {
        changeProperty(SAFETY_CENTER_ENABLED, true)
        controller.onParentVisible()
        val captor = argumentCaptor<View.OnClickListener>()
        verify(privacyChip).setOnClickListener(capture(captor))
        captor.value.onClick(privacyChip)
        verify(privacyDialogController, never()).showDialog(any(Context::class.java))
    }

    private fun setPrivacyController(micCamera: Boolean, location: Boolean) {
        whenever(privacyItemController.micCameraAvailable).thenReturn(micCamera)
        whenever(privacyItemController.locationAvailable).thenReturn(location)
    }

    private fun changeProperty(name: String, value: Boolean?) {
        deviceConfigProxy.setProperty(
            DeviceConfig.NAMESPACE_PRIVACY,
            name,
            value?.toString(),
            false
        )
        uiExecutor.runAllReady()
    }
}