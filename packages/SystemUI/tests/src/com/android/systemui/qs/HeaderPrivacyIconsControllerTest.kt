package com.android.systemui.qs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.permission.PermissionManager
import android.safetycenter.SafetyCenterManager
import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.appops.AppOpsController
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.privacy.PrivacyDialogController
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

private fun <T> eq(value: T): T = Mockito.eq(value) ?: value
private fun <T> any(): T = Mockito.any<T>()

@SmallTest
@RunWith(AndroidTestingRunner::class)
class HeaderPrivacyIconsControllerTest : SysuiTestCase() {

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
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var appOpsController: AppOpsController
    @Mock
    private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock
    private lateinit var safetyCenterManager: SafetyCenterManager

    private val uiExecutor = FakeExecutor(FakeSystemClock())
    private val backgroundExecutor = FakeExecutor(FakeSystemClock())
    private lateinit var cameraSlotName: String
    private lateinit var microphoneSlotName: String
    private lateinit var locationSlotName: String
    private lateinit var controller: HeaderPrivacyIconsController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(privacyChip.context).thenReturn(context)
        whenever(privacyChip.resources).thenReturn(context.resources)
        whenever(privacyChip.isAttachedToWindow).thenReturn(true)

        cameraSlotName = context.getString(com.android.internal.R.string.status_bar_camera)
        microphoneSlotName = context.getString(com.android.internal.R.string.status_bar_microphone)
        locationSlotName = context.getString(com.android.internal.R.string.status_bar_location)

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
                broadcastDispatcher,
                safetyCenterManager
        )

        backgroundExecutor.runAllReady()
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
        whenever(safetyCenterManager.isSafetyCenterEnabled).thenReturn(false)
        controller.onParentVisible()
        val captor = argumentCaptor<View.OnClickListener>()
        verify(privacyChip).setOnClickListener(capture(captor))
        captor.value.onClick(privacyChip)
        verify(privacyDialogController).showDialog(any(Context::class.java))
    }

    @Test
    fun testSafetyCenterFlag() {
        val receiverCaptor = argumentCaptor<BroadcastReceiver>()
        whenever(safetyCenterManager.isSafetyCenterEnabled).thenReturn(true)
        verify(broadcastDispatcher).registerReceiver(capture(receiverCaptor),
                any(), any(), nullable(), anyInt(), nullable())
        receiverCaptor.value.onReceive(
                context,
                Intent(SafetyCenterManager.ACTION_SAFETY_CENTER_ENABLED_CHANGED)
        )
        backgroundExecutor.runAllReady()
        controller.onParentVisible()
        val captor = argumentCaptor<View.OnClickListener>()
        verify(privacyChip).setOnClickListener(capture(captor))
        captor.value.onClick(privacyChip)
        verify(privacyDialogController, never()).showDialog(any(Context::class.java))
    }

    @Test
    fun testBroadcastReceiverUnregisteredWhenChipDetached() {
        whenever(safetyCenterManager.isSafetyCenterEnabled).thenReturn(false)
        controller.attachStateChangeListener.onViewDetachedFromWindow(privacyChip)
        backgroundExecutor.runAllReady()
        val broadcastReceiverCaptor = argumentCaptor<BroadcastReceiver>()
        verify(broadcastDispatcher).unregisterReceiver(capture(broadcastReceiverCaptor))
    }

    @Test
    fun testBroadcastReceiverRegisteredWhenChipAttached() {
        whenever(safetyCenterManager.isSafetyCenterEnabled).thenReturn(false)
        controller.attachStateChangeListener.onViewAttachedToWindow(privacyChip)
        backgroundExecutor.runAllReady()
        val broadcastReceiverCaptor = argumentCaptor<BroadcastReceiver>()
        val intentFilterCaptor = argumentCaptor<IntentFilter>()
        // Broadcast receiver is registered on init and when privacy chip is attached
        verify(broadcastDispatcher, times(2)).registerReceiver(
            capture(broadcastReceiverCaptor),
            capture(intentFilterCaptor), any(), nullable(), anyInt(), nullable()
        )
    }

    private fun setPrivacyController(micCamera: Boolean, location: Boolean) {
        whenever(privacyItemController.micCameraAvailable).thenReturn(micCamera)
        whenever(privacyItemController.locationAvailable).thenReturn(location)
    }
}