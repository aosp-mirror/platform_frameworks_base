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
import android.net.Uri
import android.os.UserHandle
import android.os.UserManager
import android.testing.AndroidTestingRunner
import android.view.WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER
import android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.util.ScreenshotRequest
import com.android.systemui.SysuiTestCase
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_CAPTURE_FAILED
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_REQUESTED_KEY_OTHER
import com.android.systemui.screenshot.TakeScreenshotService.RequestCallback
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.function.Consumer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.isNull
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@RunWith(AndroidTestingRunner::class)
class TakeScreenshotServiceTest : SysuiTestCase() {

    private val userManager = mock<UserManager> { on { isUserUnlocked } doReturn (true) }

    private val devicePolicyResourcesManager =
        mock<DevicePolicyResourcesManager> {
            on { getString(eq(SCREENSHOT_BLOCKED_BY_ADMIN), /* defaultStringLoader= */ any()) }
                .doReturn("SCREENSHOT_BLOCKED_BY_ADMIN")
        }

    private val devicePolicyManager =
        mock<DevicePolicyManager> {
            on { resources } doReturn (devicePolicyResourcesManager)
            on { getScreenCaptureDisabled(/* admin= */ isNull(), eq(UserHandle.USER_ALL)) }
                .doReturn(false)
        }

    private val notificationsController = mock<ScreenshotNotificationsController>()
    private val notificationsControllerFactory =
        ScreenshotNotificationsController.Factory { notificationsController }

    private val executor = FakeScreenshotExecutor()
    private val callback = FakeRequestCallback()
    private val eventLogger = UiEventLoggerFake()
    private val topComponent = ComponentName(mContext, TakeScreenshotServiceTest::class.java)

    @Test
    fun testServiceLifecycle() {
        val service = createService()
        service.onCreate()
        service.onBind(null /* unused: Intent */)
        assertThat(executor.windowsPresent).isTrue()

        service.onUnbind(null /* unused: Intent */)
        assertThat(executor.windowsPresent).isFalse()

        service.onDestroy()
        assertThat(executor.destroyed).isTrue()
    }

    @Test
    fun takeScreenshotFullscreen() {
        val service = createService()

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER)
                .setTopComponent(topComponent)
                .build()

        service.handleRequest(request, { /* onSaved */}, callback)
        assertWithMessage("request received by executor").that(executor.requestReceived).isNotNull()

        assertWithMessage("request received by executor")
            .that(ScreenshotData.fromRequest(executor.requestReceived!!))
            .isEqualTo(ScreenshotData.fromRequest(request))
    }

    @Test
    fun takeScreenshotFullscreen_userLocked() {
        val service = createService()
        whenever(userManager.isUserUnlocked).doReturn(false)

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER)
                .setTopComponent(topComponent)
                .build()

        service.handleRequest(request, { /* onSaved */}, callback)

        verify(notificationsController, times(1)).notifyScreenshotError(anyInt())

        assertWithMessage("callback errorReported").that(callback.errorReported).isTrue()

        assertWithMessage("UiEvent count").that(eventLogger.numLogs()).isEqualTo(2)

        val requestEvent = eventLogger.get(0)
        assertWithMessage("request UiEvent id")
            .that(requestEvent.eventId)
            .isEqualTo(SCREENSHOT_REQUESTED_KEY_OTHER.id)

        assertWithMessage("topComponent package name")
            .that(requestEvent.packageName)
            .isEqualTo(topComponent.packageName)

        val failureEvent = eventLogger.get(1)
        assertWithMessage("failure UiEvent id")
            .that(failureEvent.eventId)
            .isEqualTo(SCREENSHOT_CAPTURE_FAILED.id)

        assertWithMessage("Supplied package name")
            .that(failureEvent.packageName)
            .isEqualTo(topComponent.packageName)
    }

    @Test
    fun takeScreenshotFullscreen_screenCaptureDisabled_allUsers() {
        val service = createService()

        whenever(
                devicePolicyManager.getScreenCaptureDisabled(
                    /* admin= */ isNull(),
                    eq(UserHandle.USER_ALL)
                )
            )
            .doReturn(true)

        val request =
            ScreenshotRequest.Builder(TAKE_SCREENSHOT_FULLSCREEN, SCREENSHOT_KEY_OTHER)
                .setTopComponent(topComponent)
                .build()

        service.handleRequest(request, { /* onSaved */}, callback)
        assertThat(callback.errorReported).isTrue()
        assertWithMessage("Expected two UiEvents").that(eventLogger.numLogs()).isEqualTo(2)

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

    private fun createService(): TakeScreenshotService {
        val service =
            TakeScreenshotService(
                userManager,
                devicePolicyManager,
                eventLogger,
                notificationsControllerFactory,
                mContext,
                Runnable::run,
                executor
            )

        service.attach(
            mContext,
            /* thread = */ null,
            /* className = */ null,
            /* token = */ null,
            mock<Application>(),
            /* activityManager = */ null
        )
        return service
    }
}

internal class FakeRequestCallback : RequestCallback {
    var errorReported = false
    var finished = false
    override fun reportError() {
        errorReported = true
    }

    override fun onFinish() {
        finished = true
    }
}

internal class FakeScreenshotExecutor : TakeScreenshotExecutor {
    var requestReceived: ScreenshotRequest? = null
    var windowsPresent = true
    var destroyed = false
    override fun onCloseSystemDialogsReceived() {}
    override suspend fun executeScreenshots(
        screenshotRequest: ScreenshotRequest,
        onSaved: (Uri?) -> Unit,
        requestCallback: RequestCallback,
    ) {
        requestReceived = screenshotRequest
    }

    override fun removeWindows() {
        windowsPresent = false
    }

    override fun onDestroy() {
        destroyed = true
    }

    override fun executeScreenshotsAsync(
        screenshotRequest: ScreenshotRequest,
        onSaved: Consumer<Uri?>,
        requestCallback: RequestCallback,
    ) {
        runBlocking {
            executeScreenshots(screenshotRequest, { onSaved.accept(it) }, requestCallback)
        }
    }
}
