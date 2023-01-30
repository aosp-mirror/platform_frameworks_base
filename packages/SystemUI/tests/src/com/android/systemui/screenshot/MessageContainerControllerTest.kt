package com.android.systemui.screenshot

import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class MessageContainerControllerTest : SysuiTestCase() {
    lateinit var messageContainer: MessageContainerController

    @Mock lateinit var workProfileMessageController: WorkProfileMessageController

    @Mock lateinit var screenshotDetectionController: ScreenshotDetectionController

    @Mock lateinit var icon: Drawable

    lateinit var workProfileFirstRunView: ViewGroup
    lateinit var detectionNoticeView: ViewGroup
    lateinit var container: FrameLayout

    var featureFlags = FakeFeatureFlags()
    lateinit var screenshotView: ViewGroup

    val userHandle = UserHandle.of(5)
    val screenshotData = ScreenshotData.forTesting()

    val appName = "app name"
    lateinit var workProfileData: WorkProfileMessageController.WorkProfileFirstRunData

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        messageContainer =
            MessageContainerController(
                workProfileMessageController,
                screenshotDetectionController,
                featureFlags
            )
        screenshotView = ConstraintLayout(mContext)
        workProfileData = WorkProfileMessageController.WorkProfileFirstRunData(appName, icon)

        val guideline = Guideline(mContext)
        guideline.id = com.android.systemui.R.id.guideline
        screenshotView.addView(guideline)

        container = FrameLayout(mContext)
        container.id = com.android.systemui.R.id.screenshot_message_container
        screenshotView.addView(container)

        workProfileFirstRunView = FrameLayout(mContext)
        workProfileFirstRunView.id = com.android.systemui.R.id.work_profile_first_run
        container.addView(workProfileFirstRunView)

        detectionNoticeView = FrameLayout(mContext)
        detectionNoticeView.id = com.android.systemui.R.id.screenshot_detection_notice
        container.addView(detectionNoticeView)

        messageContainer.setView(screenshotView)

        screenshotData.userHandle = userHandle
    }

    @Test
    fun testOnScreenshotTakenUserHandle_noWorkProfileFirstRun() {
        featureFlags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, true)
        // (just being explicit here)
        whenever(workProfileMessageController.onScreenshotTaken(eq(userHandle))).thenReturn(null)

        messageContainer.onScreenshotTaken(userHandle)

        verify(workProfileMessageController, never()).populateView(any(), any(), any())
    }

    @Test
    fun testOnScreenshotTakenUserHandle_noWorkProfileFlag() {
        featureFlags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, false)

        messageContainer.onScreenshotTaken(userHandle)

        verify(workProfileMessageController, never()).onScreenshotTaken(any())
        verify(workProfileMessageController, never()).populateView(any(), any(), any())
    }

    @Test
    fun testOnScreenshotTakenUserHandle_withWorkProfileFirstRun() {
        featureFlags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, true)
        whenever(workProfileMessageController.onScreenshotTaken(eq(userHandle)))
            .thenReturn(workProfileData)
        messageContainer.onScreenshotTaken(userHandle)

        verify(workProfileMessageController)
            .populateView(eq(workProfileFirstRunView), eq(workProfileData), any())
        assertEquals(View.VISIBLE, workProfileFirstRunView.visibility)
        assertEquals(View.GONE, detectionNoticeView.visibility)
    }

    @Test
    fun testOnScreenshotTakenScreenshotData_flagsOff() {
        featureFlags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, false)
        featureFlags.set(Flags.SCREENSHOT_DETECTION, false)

        messageContainer.onScreenshotTaken(screenshotData)

        verify(workProfileMessageController, never()).onScreenshotTaken(any())
        verify(screenshotDetectionController, never()).maybeNotifyOfScreenshot(any())

        assertEquals(View.GONE, container.visibility)
    }

    @Test
    fun testOnScreenshotTakenScreenshotData_nothingToShow() {
        featureFlags.set(Flags.SCREENSHOT_WORK_PROFILE_POLICY, true)
        featureFlags.set(Flags.SCREENSHOT_DETECTION, true)

        messageContainer.onScreenshotTaken(screenshotData)

        verify(workProfileMessageController, never()).populateView(any(), any(), any())
        verify(screenshotDetectionController, never()).populateView(any(), any())

        assertEquals(View.GONE, container.visibility)
    }
}
