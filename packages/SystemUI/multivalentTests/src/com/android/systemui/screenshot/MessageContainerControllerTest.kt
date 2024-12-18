package com.android.systemui.screenshot

import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.Guideline
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.screenshot.message.LabeledIcon
import com.android.systemui.screenshot.message.ProfileMessageController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class MessageContainerControllerTest : SysuiTestCase() {
    lateinit var messageContainer: MessageContainerController

    @Mock lateinit var workProfileMessageController: WorkProfileMessageController
    @Mock lateinit var profileMessageController: ProfileMessageController

    @Mock lateinit var screenshotDetectionController: ScreenshotDetectionController

    @Mock lateinit var icon: Drawable

    lateinit var workProfileFirstRunView: ViewGroup
    lateinit var detectionNoticeView: ViewGroup
    lateinit var container: FrameLayout

    lateinit var screenshotView: ViewGroup

    val userHandle = UserHandle.of(5)
    val screenshotData = ScreenshotData.forTesting(userHandle = userHandle)

    val appName = "app name"
    lateinit var workProfileData: WorkProfileMessageController.WorkProfileFirstRunData

    @Before
    @ExperimentalCoroutinesApi
    fun setup() {
        MockitoAnnotations.initMocks(this)
        messageContainer =
            MessageContainerController(
                workProfileMessageController,
                profileMessageController,
                screenshotDetectionController,
                TestScope(UnconfinedTestDispatcher()),
            )
        screenshotView = ConstraintLayout(mContext)
        workProfileData = WorkProfileMessageController.WorkProfileFirstRunData(appName, icon)

        val guideline = Guideline(mContext)
        guideline.id = com.android.systemui.res.R.id.guideline
        screenshotView.addView(guideline)

        container = FrameLayout(mContext)
        container.id = com.android.systemui.res.R.id.screenshot_message_container
        screenshotView.addView(container)

        workProfileFirstRunView = FrameLayout(mContext)
        workProfileFirstRunView.id = com.android.systemui.res.R.id.work_profile_first_run
        container.addView(workProfileFirstRunView)

        detectionNoticeView = FrameLayout(mContext)
        detectionNoticeView.id = com.android.systemui.res.R.id.screenshot_detection_notice
        container.addView(detectionNoticeView)

        messageContainer.setView(screenshotView)
    }

    @Test
    fun testOnScreenshotTakenUserHandle_withProfileProfileFirstRun() = runTest {
        val profileData =
            ProfileMessageController.ProfileFirstRunData(
                LabeledIcon(appName, icon),
                ProfileMessageController.FirstRunProfile.PRIVATE,
            )
        whenever(profileMessageController.onScreenshotTaken(eq(userHandle))).thenReturn(profileData)
        messageContainer.onScreenshotTaken(screenshotData)

        verify(profileMessageController)
            .bindView(eq(workProfileFirstRunView), eq(profileData), any())
        assertEquals(View.VISIBLE, workProfileFirstRunView.visibility)
        assertEquals(View.GONE, detectionNoticeView.visibility)
    }

    @Test
    fun testOnScreenshotTakenScreenshotData_nothingToShow() {
        messageContainer.onScreenshotTaken(screenshotData)

        verify(workProfileMessageController, never()).populateView(any(), any(), any())
        verify(profileMessageController, never()).bindView(any(), any(), any())
        verify(screenshotDetectionController, never()).populateView(any(), any())

        assertEquals(View.GONE, container.visibility)
    }
}
