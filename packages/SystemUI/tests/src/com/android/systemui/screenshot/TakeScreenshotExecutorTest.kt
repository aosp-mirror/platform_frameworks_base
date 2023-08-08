package com.android.systemui.screenshot

import android.content.ComponentName
import android.net.Uri
import android.testing.AndroidTestingRunner
import android.view.Display
import android.view.Display.TYPE_EXTERNAL
import android.view.Display.TYPE_INTERNAL
import android.view.Display.TYPE_OVERLAY
import android.view.Display.TYPE_VIRTUAL
import android.view.Display.TYPE_WIFI
import android.view.WindowManager
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.util.ScreenshotRequest
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.FakeDisplayRepository
import com.android.systemui.display.data.repository.display
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.kotlinArgumentCaptor as ArgumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

@RunWith(AndroidTestingRunner::class)
@SmallTest
class TakeScreenshotExecutorTest : SysuiTestCase() {

    private val controller0 = mock<ScreenshotController>()
    private val controller1 = mock<ScreenshotController>()
    private val controllerFactory = mock<ScreenshotController.Factory>()
    private val callback = mock<TakeScreenshotService.RequestCallback>()

    private val fakeDisplayRepository = FakeDisplayRepository()
    private val requestProcessor = FakeRequestProcessor()
    private val topComponent = ComponentName(mContext, TakeScreenshotExecutorTest::class.java)
    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val eventLogger = UiEventLoggerFake()

    private val screenshotExecutor =
        TakeScreenshotExecutor(
            controllerFactory,
            fakeDisplayRepository,
            testScope,
            requestProcessor,
            eventLogger,
        )

    @Before
    fun setUp() {
        whenever(controllerFactory.create(eq(0))).thenReturn(controller0)
        whenever(controllerFactory.create(eq(1))).thenReturn(controller1)
    }

    @Test
    fun executeScreenshots_severalDisplays_callsControllerForEachOne() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            verify(controllerFactory).create(eq(0))
            verify(controllerFactory).create(eq(1))

            val capturer = ArgumentCaptor<ScreenshotData>()

            verify(controller0).handleScreenshot(capturer.capture(), any(), any())
            assertThat(capturer.value.displayId).isEqualTo(0)
            // OnSaved callback should be different.
            verify(controller1).handleScreenshot(capturer.capture(), any(), any())
            assertThat(capturer.value.displayId).isEqualTo(1)

            assertThat(eventLogger.numLogs()).isEqualTo(2)
            assertThat(eventLogger.get(0).eventId)
                .isEqualTo(ScreenshotEvent.SCREENSHOT_REQUESTED_KEY_OTHER.id)
            assertThat(eventLogger.get(0).packageName).isEqualTo(topComponent.packageName)
            assertThat(eventLogger.get(1).eventId)
                .isEqualTo(ScreenshotEvent.SCREENSHOT_REQUESTED_KEY_OTHER.id)
            assertThat(eventLogger.get(1).packageName).isEqualTo(topComponent.packageName)

            screenshotExecutor.onDestroy()
        }

    @Test
    fun executeScreenshots_onlyVirtualDisplays_noInteractionsWithControllers() =
        testScope.runTest {
            setDisplays(display(TYPE_VIRTUAL, id = 0), display(TYPE_VIRTUAL, id = 1))
            val onSaved = { _: Uri -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            verifyNoMoreInteractions(controllerFactory)
            screenshotExecutor.onDestroy()
        }

    @Test
    fun executeScreenshots_allowedTypes_allCaptured() =
        testScope.runTest {
            whenever(controllerFactory.create(any())).thenReturn(controller0)

            setDisplays(
                display(TYPE_INTERNAL, id = 0),
                display(TYPE_EXTERNAL, id = 1),
                display(TYPE_OVERLAY, id = 2),
                display(TYPE_WIFI, id = 3)
            )
            val onSaved = { _: Uri -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            verify(controller0, times(4)).handleScreenshot(any(), any(), any())
            screenshotExecutor.onDestroy()
        }

    @Test
    fun executeScreenshots_reportsOnFinishedOnlyWhenBothFinished() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            val capturer0 = ArgumentCaptor<TakeScreenshotService.RequestCallback>()
            val capturer1 = ArgumentCaptor<TakeScreenshotService.RequestCallback>()

            verify(controller0).handleScreenshot(any(), any(), capturer0.capture())
            verify(controller1).handleScreenshot(any(), any(), capturer1.capture())

            verify(callback, never()).onFinish()

            capturer0.value.onFinish()

            verify(callback, never()).onFinish()

            capturer1.value.onFinish()

            verify(callback).onFinish()
            screenshotExecutor.onDestroy()
        }

    @Test
    fun executeScreenshots_doesNotReportFinishedIfOneFinishesOtherFails() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            val capturer0 = ArgumentCaptor<TakeScreenshotService.RequestCallback>()
            val capturer1 = ArgumentCaptor<TakeScreenshotService.RequestCallback>()

            verify(controller0).handleScreenshot(any(), any(), capturer0.capture())
            verify(controller1).handleScreenshot(any(), nullable(), capturer1.capture())

            verify(callback, never()).onFinish()

            capturer0.value.onFinish()

            verify(callback, never()).onFinish()

            capturer1.value.reportError()

            verify(callback, never()).onFinish()
            verify(callback).reportError()

            screenshotExecutor.onDestroy()
        }

    @Test
    fun executeScreenshots_doesNotReportFinishedAfterOneFails() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            val capturer0 = ArgumentCaptor<TakeScreenshotService.RequestCallback>()
            val capturer1 = ArgumentCaptor<TakeScreenshotService.RequestCallback>()

            verify(controller0).handleScreenshot(any(), any(), capturer0.capture())
            verify(controller1).handleScreenshot(any(), any(), capturer1.capture())

            verify(callback, never()).onFinish()

            capturer0.value.reportError()

            verify(callback, never()).onFinish()
            verify(callback).reportError()

            capturer1.value.onFinish()

            verify(callback, never()).onFinish()
            screenshotExecutor.onDestroy()
        }

    @Test
    fun onDestroy_propagatedToControllers() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            screenshotExecutor.onDestroy()
            verify(controller0).onDestroy()
            verify(controller1).onDestroy()
        }

    @Test
    fun removeWindows_propagatedToControllers() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            screenshotExecutor.removeWindows()
            verify(controller0).removeWindow()
            verify(controller1).removeWindow()

            screenshotExecutor.onDestroy()
        }

    @Test
    fun onCloseSystemDialogsReceived_propagatedToControllers() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            screenshotExecutor.onCloseSystemDialogsReceived()
            verify(controller0).dismissScreenshot(any())
            verify(controller1).dismissScreenshot(any())

            screenshotExecutor.onDestroy()
        }

    @Test
    fun onCloseSystemDialogsReceived_someControllerHavePendingTransitions() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            whenever(controller0.isPendingSharedTransition).thenReturn(true)
            whenever(controller1.isPendingSharedTransition).thenReturn(false)
            val onSaved = { _: Uri -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            screenshotExecutor.onCloseSystemDialogsReceived()
            verify(controller0, never()).dismissScreenshot(any())
            verify(controller1).dismissScreenshot(any())

            screenshotExecutor.onDestroy()
        }

    @Test
    fun executeScreenshots_controllerCalledWithRequestProcessorReturnValue() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0))
            val screenshotRequest = createScreenshotRequest()
            val toBeReturnedByProcessor = ScreenshotData.forTesting()
            requestProcessor.toReturn = toBeReturnedByProcessor

            val onSaved = { _: Uri -> }
            screenshotExecutor.executeScreenshots(screenshotRequest, onSaved, callback)

            assertThat(requestProcessor.processed)
                .isEqualTo(ScreenshotData.fromRequest(screenshotRequest))

            val capturer = ArgumentCaptor<ScreenshotData>()
            verify(controller0).handleScreenshot(capturer.capture(), any(), any())
            assertThat(capturer.value).isEqualTo(toBeReturnedByProcessor)

            screenshotExecutor.onDestroy()
        }

    private suspend fun TestScope.setDisplays(vararg displays: Display) {
        fakeDisplayRepository.emit(displays.toSet())
        runCurrent()
    }

    private fun createScreenshotRequest() =
        ScreenshotRequest.Builder(
                WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
                WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER
            )
            .setTopComponent(topComponent)
            .build()

    private class FakeRequestProcessor : ScreenshotRequestProcessor {
        var processed: ScreenshotData? = null
        var toReturn: ScreenshotData? = null

        override suspend fun process(screenshot: ScreenshotData): ScreenshotData {
            processed = screenshot
            return toReturn ?: screenshot
        }
    }
}
