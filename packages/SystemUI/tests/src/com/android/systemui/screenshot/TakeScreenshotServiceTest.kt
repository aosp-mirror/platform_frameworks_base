/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.screenshot

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.app.admin.DevicePolicyResources.Strings.SystemUi.SCREENSHOT_BLOCKED_BY_ADMIN
import android.app.admin.DevicePolicyResourcesManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Bitmap.Config.HARDWARE
import android.graphics.ColorSpace
import android.graphics.Insets
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.UserHandle
import android.os.UserManager
import android.testing.AndroidTestingRunner
import android.view.WindowManager.ScreenshotSource.SCREENSHOT_KEY_CHORD
import android.view.WindowManager.ScreenshotSource.SCREENSHOT_OVERVIEW
import android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN
import android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.util.ScreenshotHelper
import com.android.internal.util.ScreenshotHelper.ScreenshotRequest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags.SCREENSHOT_REQUEST_PROCESSOR
import com.android.systemui.flags.Flags.SCREENSHOT_WORK_PROFILE_POLICY
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_REQUESTED_KEY_CHORD
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_REQUESTED_OVERVIEW
import com.android.systemui.screenshot.TakeScreenshotService.RequestCallback
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argThat
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import java.util.function.Consumer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when` as whenever

private const val USER_ID = 1
private const val TASK_ID = 11

@RunWith(AndroidTestingRunner::class)
@SmallTest
class TakeScreenshotServiceTest : SysuiTestCase() {

    private val application = mock<Application>()
    private val controller = mock<ScreenshotController>()
    private val userManager = mock<UserManager>()
    private val requestProcessor = mock<RequestProcessor>()
    private val devicePolicyManager = mock<DevicePolicyManager>()
    private val devicePolicyResourcesManager = mock<DevicePolicyResourcesManager>()
    private val notificationsController = mock<ScreenshotNotificationsController>()
    private val callback = mock<RequestCallback>()

    private val eventLogger = UiEventLoggerFake()
    private val flags = FakeFeatureFlags()
    private val topComponent = ComponentName(mContext, TakeScreenshotServiceTest::class.java)

    private val service = TakeScreenshotService(
        controller, userManager, devicePolicyManager, eventLogger,
        notificationsController, mContext, Runnable::run, flags, requestProcessor)

    @Before
    fun setUp() {
        whenever(devicePolicyManager.resources).thenReturn(devicePolicyResourcesManager)
        whenever(devicePolicyManager.getScreenCaptureDisabled(
            /* admin component (null: any admin) */ isNull(), eq(UserHandle.USER_ALL)))
            .thenReturn(false)
        whenever(userManager.isUserUnlocked).thenReturn(true)

        // Stub request processor as a synchronous no-op for tests with the flag enabled
        doAnswer {
            val request: ScreenshotRequest = it.getArgument(0) as ScreenshotRequest
            val consumer: Consumer<ScreenshotRequest> = it.getArgument(1)
            consumer.accept(request)
        }.`when`(requestProcessor).processAsync(/* request= */ any(), /* callback= */ any())

        // Flipped in selected test cases
        flags.set(SCREENSHOT_REQUEST_PROCESSOR, false)
        flags.set(SCREENSHOT_WORK_PROFILE_POLICY, false)

        service.attach(
            mContext,
            /* thread = */ null,
            /* className = */ null,
            /* token = */ null,
            application,
            /* activityManager = */ null)
    }

    @Test
    fun testServiceLifecycle() {
        service.onCreate()
        service.onBind(null /* unused: Intent */)

        service.onUnbind(null /* unused: Intent */)
        verify(controller, times(1)).removeWindow()

        service.onDestroy()
        verify(controller, times(1)).onDestroy()
    }

    @Test
    fun takeScreenshotFullscreen() {
        val request = ScreenshotRequest(
            TAKE_SCREENSHOT_FULLSCREEN,
            SCREENSHOT_KEY_CHORD,
            topComponent)

        service.handleRequest(request, { /* onSaved */ }, callback)

        verify(controller, times(1)).takeScreenshotFullscreen(
            eq(topComponent),
            /* onSavedListener = */ any(),
            /* requestCallback = */ any())

        assertEquals("Expected one UiEvent", eventLogger.numLogs(), 1)
        val logEvent = eventLogger.get(0)

        assertEquals("Expected SCREENSHOT_REQUESTED UiEvent",
            logEvent.eventId, SCREENSHOT_REQUESTED_KEY_CHORD.id)
        assertEquals("Expected supplied package name",
            topComponent.packageName, eventLogger.get(0).packageName)
    }

    @Test
    fun takeScreenshot_requestProcessorEnabled() {
        flags.set(SCREENSHOT_REQUEST_PROCESSOR, true)

        val request = ScreenshotRequest(
            TAKE_SCREENSHOT_FULLSCREEN,
            SCREENSHOT_KEY_CHORD,
            topComponent)

        service.handleRequest(request, { /* onSaved */ }, callback)

        verify(controller, times(1)).takeScreenshotFullscreen(
            eq(topComponent),
            /* onSavedListener = */ any(),
            /* requestCallback = */ any())

        assertEquals("Expected one UiEvent", eventLogger.numLogs(), 1)
        val logEvent = eventLogger.get(0)

        assertEquals("Expected SCREENSHOT_REQUESTED UiEvent",
            logEvent.eventId, SCREENSHOT_REQUESTED_KEY_CHORD.id)
        assertEquals("Expected supplied package name",
            topComponent.packageName, eventLogger.get(0).packageName)
    }

    @Test
    fun takeScreenshotProvidedImage() {
        val bounds = Rect(50, 50, 150, 150)
        val bitmap = makeHardwareBitmap(100, 100)
        val bitmapBundle = ScreenshotHelper.HardwareBitmapBundler.hardwareBitmapToBundle(bitmap)

        val request = ScreenshotRequest(TAKE_SCREENSHOT_PROVIDED_IMAGE, SCREENSHOT_OVERVIEW,
            bitmapBundle, bounds, Insets.NONE, TASK_ID, USER_ID, topComponent)

        service.handleRequest(request, { /* onSaved */ }, callback)

        verify(controller, times(1)).handleImageAsScreenshot(
            argThat { b -> b.equalsHardwareBitmap(bitmap) },
            eq(bounds),
            eq(Insets.NONE), eq(TASK_ID), eq(USER_ID), eq(topComponent),
            /* onSavedListener = */ any(), /* requestCallback = */ any())

        assertEquals("Expected one UiEvent", eventLogger.numLogs(), 1)
        val logEvent = eventLogger.get(0)

        assertEquals("Expected SCREENSHOT_REQUESTED_* UiEvent",
            logEvent.eventId, SCREENSHOT_REQUESTED_OVERVIEW.id)
        assertEquals("Expected supplied package name",
            topComponent.packageName, eventLogger.get(0).packageName)
    }

    @Test
    fun takeScreenshotFullscreen_userLocked() {
        whenever(userManager.isUserUnlocked).thenReturn(false)

        val request = ScreenshotRequest(
            TAKE_SCREENSHOT_FULLSCREEN,
            SCREENSHOT_KEY_CHORD,
            topComponent)

        service.handleRequest(request, { /* onSaved */ }, callback)

        verify(notificationsController, times(1)).notifyScreenshotError(anyInt())
        verify(callback, times(1)).reportError()
        verifyZeroInteractions(controller)
    }

    @Test
    fun takeScreenshotFullscreen_screenCaptureDisabled_allUsers() {
        whenever(devicePolicyManager.getScreenCaptureDisabled(
            isNull(), eq(UserHandle.USER_ALL))
        ).thenReturn(true)

        whenever(devicePolicyResourcesManager.getString(
            eq(SCREENSHOT_BLOCKED_BY_ADMIN),
            /* Supplier<String> */ any(),
        )).thenReturn("SCREENSHOT_BLOCKED_BY_ADMIN")

        val request = ScreenshotRequest(
            TAKE_SCREENSHOT_FULLSCREEN,
            SCREENSHOT_KEY_CHORD,
            topComponent)

        service.handleRequest(request, { /* onSaved */ }, callback)

        // error shown: Toast.makeText(...).show(), untestable
        verify(callback, times(1)).reportError()
        verifyZeroInteractions(controller)
    }
}

private fun Bitmap.equalsHardwareBitmap(other: Bitmap): Boolean {
    return config == HARDWARE &&
            other.config == HARDWARE &&
            hardwareBuffer == other.hardwareBuffer &&
            colorSpace == other.colorSpace
}

/** A hardware Bitmap is mandated by use of ScreenshotHelper.HardwareBitmapBundler */
private fun makeHardwareBitmap(width: Int, height: Int): Bitmap {
    val buffer = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
    return Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))!!
}
