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
import android.hardware.display.DisplayManager
import android.os.UserHandle
import android.os.UserManager
import android.testing.AndroidTestingRunner
import android.view.Display
import android.view.WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER
import android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.util.ScreenshotRequest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags.MULTI_DISPLAY_SCREENSHOT
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_CAPTURE_FAILED
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_REQUESTED_KEY_OTHER
import com.android.systemui.screenshot.TakeScreenshotService.RequestCallback
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import java.util.function.Consumer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions

@RunWith(AndroidTestingRunner::class)
@SmallTest
class TakeScreenshotServiceTest : SysuiTestCase() {

    private val application = mock<Application>()
    private val controller = mock<ScreenshotController>()
    private val controllerFactory = mock<ScreenshotController.Factory>()
    private val takeScreenshotExecutor = mock<TakeScreenshotExecutor>()
    private val userManager = mock<UserManager>()
    private val requestProcessor = mock<RequestProcessor>()
    private val devicePolicyManager = mock<DevicePolicyManager>()
    private val devicePolicyResourcesManager = mock<DevicePolicyResourcesManager>()
    private val notificationsControllerFactory = mock<ScreenshotNotificationsController.Factory>()
    private val notificationsController = mock<ScreenshotNotificationsController>()
    private val callback = mock<RequestCallback>()
    private val displayManager = mock<DisplayManager>()

    private val eventLogger = UiEventLoggerFake()
    private val flags = FakeFeatureFlags()
    private val topComponent = ComponentName(mContext, TakeScreenshotServiceTest::class.java)

    private lateinit var service: TakeScreenshotService

    @Before
    fun setUp() {
        flags.set(MULTI_DISPLAY_SCREENSHOT, false)
        whenever(devicePolicyManager.resources).thenReturn(devicePolicyResourcesManager)
        whenever(
                devicePolicyManager.getScreenCaptureDisabled(
                    /* admin component (null: any admin) */ isNull(),
                    eq(UserHandle.USER_ALL)
                )
            )
            .thenReturn(false)
        whenever(userManager.isUserUnlocked).thenReturn(true)
        whenever(controllerFactory.create(nullable<Display>(), any())).thenReturn(controller)
        whenever(notificationsControllerFactory.create(any())).thenReturn(notificationsController)

        // Stub request processor as a synchronous no-op for tests with the flag enabled
        doAnswer {
                val request: ScreenshotData = it.getArgument(0) as ScreenshotData
                val consumer: Consumer<ScreenshotData> = it.getArgument(1)
                consumer.accept(request)
            }
            .whenever(requestProcessor)
            .processAsync(/* screenshot= */ any(ScreenshotData::class.java), /* callback= */ any())

        service = createService()
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
        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER)
                .setTopComponent(topComponent)
                .build()

        service.handleRequest(request, { /* onSaved */}, callback)

        verify(controller, times(1))
            .handleScreenshot(
                eq(ScreenshotData.fromRequest(request, Display.DEFAULT_DISPLAY)),
                /* onSavedListener = */ any(),
                /* requestCallback = */ any()
            )

        assertEquals("Expected one UiEvent", eventLogger.numLogs(), 1)
        val logEvent = eventLogger.get(0)

        assertEquals(
            "Expected SCREENSHOT_REQUESTED UiEvent",
            logEvent.eventId,
            SCREENSHOT_REQUESTED_KEY_OTHER.id
        )
        assertEquals(
            "Expected supplied package name",
            topComponent.packageName,
            eventLogger.get(0).packageName
        )
    }

    @Test
    fun takeScreenshotFullscreen_userLocked() {
        whenever(userManager.isUserUnlocked).thenReturn(false)

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER)
                .setTopComponent(topComponent)
                .build()

        service.handleRequest(request, { /* onSaved */}, callback)

        verify(notificationsController, times(1)).notifyScreenshotError(anyInt())
        verify(callback, times(1)).reportError()
        verifyZeroInteractions(controller)

        assertEquals("Expected two UiEvents", 2, eventLogger.numLogs())
        val requestEvent = eventLogger.get(0)
        assertEquals(
            "Expected SCREENSHOT_REQUESTED_* UiEvent",
            SCREENSHOT_REQUESTED_KEY_OTHER.id,
            requestEvent.eventId
        )
        assertEquals(
            "Expected supplied package name",
            topComponent.packageName,
            requestEvent.packageName
        )
        val failureEvent = eventLogger.get(1)
        assertEquals(
            "Expected SCREENSHOT_CAPTURE_FAILED UiEvent",
            SCREENSHOT_CAPTURE_FAILED.id,
            failureEvent.eventId
        )
        assertEquals(
            "Expected supplied package name",
            topComponent.packageName,
            failureEvent.packageName
        )
    }

    @Test
    fun takeScreenshotFullscreen_screenCaptureDisabled_allUsers() {
        whenever(devicePolicyManager.getScreenCaptureDisabled(isNull(), eq(UserHandle.USER_ALL)))
            .thenReturn(true)

        whenever(
                devicePolicyResourcesManager.getString(
                    eq(SCREENSHOT_BLOCKED_BY_ADMIN),
                    /* Supplier<String> */
                    any(),
                )
            )
            .thenReturn("SCREENSHOT_BLOCKED_BY_ADMIN")

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER)
                .setTopComponent(topComponent)
                .build()

        service.handleRequest(request, { /* onSaved */}, callback)

        // error shown: Toast.makeText(...).show(), untestable
        verify(callback, times(1)).reportError()
        verifyZeroInteractions(controller)
        assertEquals("Expected two UiEvents", 2, eventLogger.numLogs())
        val requestEvent = eventLogger.get(0)
        assertEquals(
            "Expected SCREENSHOT_REQUESTED_* UiEvent",
            SCREENSHOT_REQUESTED_KEY_OTHER.id,
            requestEvent.eventId
        )
        assertEquals(
            "Expected supplied package name",
            topComponent.packageName,
            requestEvent.packageName
        )
        val failureEvent = eventLogger.get(1)
        assertEquals(
            "Expected SCREENSHOT_CAPTURE_FAILED UiEvent",
            SCREENSHOT_CAPTURE_FAILED.id,
            failureEvent.eventId
        )
        assertEquals(
            "Expected supplied package name",
            topComponent.packageName,
            failureEvent.packageName
        )
    }

    @Test
    fun takeScreenshot_workProfile_nullBitmap() {
        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER)
                .setTopComponent(topComponent)
                .build()

        doThrow(IllegalStateException::class.java)
            .whenever(requestProcessor)
            .processAsync(any(ScreenshotData::class.java), any())

        service.handleRequest(request, { /* onSaved */}, callback)

        verify(callback, times(1)).reportError()
        verify(notificationsController, times(1)).notifyScreenshotError(anyInt())
        verifyZeroInteractions(controller)
        assertEquals("Expected two UiEvents", 2, eventLogger.numLogs())
        val requestEvent = eventLogger.get(0)
        assertEquals(
            "Expected SCREENSHOT_REQUESTED_* UiEvent",
            SCREENSHOT_REQUESTED_KEY_OTHER.id,
            requestEvent.eventId
        )
        assertEquals(
            "Expected supplied package name",
            topComponent.packageName,
            requestEvent.packageName
        )
        val failureEvent = eventLogger.get(1)
        assertEquals(
            "Expected SCREENSHOT_CAPTURE_FAILED UiEvent",
            SCREENSHOT_CAPTURE_FAILED.id,
            failureEvent.eventId
        )
        assertEquals(
            "Expected supplied package name",
            topComponent.packageName,
            failureEvent.packageName
        )
    }

    @Test
    fun takeScreenshotFullScreen_multiDisplayFlagEnabled_takeScreenshotExecutor() {
        flags.set(MULTI_DISPLAY_SCREENSHOT, true)
        service = createService()

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER)
                .setTopComponent(topComponent)
                .build()

        service.handleRequest(request, { /* onSaved */}, callback)

        verifyZeroInteractions(controller)
        verify(takeScreenshotExecutor, times(1)).executeScreenshotsAsync(any(), any(), any())

        assertEquals("Expected one UiEvent", 0, eventLogger.numLogs())
    }

    @Test
    fun testServiceLifecycle_multiDisplayScreenshotFlagEnabled() {
        flags.set(MULTI_DISPLAY_SCREENSHOT, true)
        service = createService()

        service.onCreate()
        service.onBind(null /* unused: Intent */)

        service.onUnbind(null /* unused: Intent */)
        verify(takeScreenshotExecutor, times(1)).removeWindows()

        service.onDestroy()
        verify(takeScreenshotExecutor, times(1)).onDestroy()
    }

    @Test
    fun constructor_MultiDisplayFlagOn_screenshotControllerNotCreated() {
        flags.set(MULTI_DISPLAY_SCREENSHOT, true)
        clearInvocations(controllerFactory)

        service = createService()

        verifyZeroInteractions(controllerFactory)
    }

    private fun createService(): TakeScreenshotService {
        val service =
            TakeScreenshotService(
                controllerFactory,
                userManager,
                devicePolicyManager,
                eventLogger,
                notificationsControllerFactory,
                mContext,
                Runnable::run,
                flags,
                requestProcessor,
                { takeScreenshotExecutor },
                displayManager,
            )
        service.attach(
            mContext,
            /* thread = */ null,
            /* className = */ null,
            /* token = */ null,
            application,
            /* activityManager = */ null
        )
        return service
    }
}
