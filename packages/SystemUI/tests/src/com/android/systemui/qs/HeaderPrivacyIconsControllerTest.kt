package com.android.systemui.qs

import android.content.Context
import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.privacy.OngoingPrivacyChip
import com.android.systemui.privacy.PrivacyDialogController
import com.android.systemui.privacy.PrivacyItemController
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.statusbar.phone.StatusIconContainer
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

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

        controller = HeaderPrivacyIconsController(
                privacyItemController,
                uiEventLogger,
                privacyChip,
                privacyDialogController,
                privacyLogger,
                iconContainer
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
        controller.onParentVisible()

        val captor = argumentCaptor<View.OnClickListener>()
        verify(privacyChip).setOnClickListener(capture(captor))

        captor.value.onClick(privacyChip)

        verify(privacyDialogController).showDialog(any(Context::class.java))
    }

    private fun setPrivacyController(micCamera: Boolean, location: Boolean) {
        whenever(privacyItemController.micCameraAvailable).thenReturn(micCamera)
        whenever(privacyItemController.locationAvailable).thenReturn(location)
    }
}