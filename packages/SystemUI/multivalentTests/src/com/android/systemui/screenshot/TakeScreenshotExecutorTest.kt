package com.android.systemui.screenshot

import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.view.Display
import android.view.Display.TYPE_EXTERNAL
import android.view.Display.TYPE_INTERNAL
import android.view.Display.TYPE_OVERLAY
import android.view.Display.TYPE_VIRTUAL
import android.view.Display.TYPE_WIFI
import android.view.WindowManager
import android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.util.ScreenshotRequest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.FakeDisplayRepository
import com.android.systemui.display.data.repository.FakeFocusedDisplayRepository
import com.android.systemui.display.data.repository.display
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.kotlinArgumentCaptor as ArgumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.lang.IllegalStateException
import java.util.function.Consumer
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

@RunWith(AndroidJUnit4::class)
@SmallTest
class TakeScreenshotExecutorTest : SysuiTestCase() {

    private val controller = mock<LegacyScreenshotController>()
    private val notificationsController0 = mock<ScreenshotNotificationsController>()
    private val notificationsController1 = mock<ScreenshotNotificationsController>()
    private val controllerFactory = mock<InteractiveScreenshotHandler.Factory>()
    private val callback = mock<TakeScreenshotService.RequestCallback>()
    private val notificationControllerFactory = mock<ScreenshotNotificationsController.Factory>()

    private val fakeDisplayRepository = FakeDisplayRepository()
    private val requestProcessor = FakeRequestProcessor()
    private val topComponent = ComponentName(mContext, TakeScreenshotExecutorTest::class.java)
    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val eventLogger = UiEventLoggerFake()
    private val headlessHandler = mock<HeadlessScreenshotHandler>()
    private val focusedDisplayRepository = FakeFocusedDisplayRepository()

    private val screenshotExecutor =
        TakeScreenshotExecutorImpl(
            controllerFactory,
            fakeDisplayRepository,
            testScope,
            requestProcessor,
            eventLogger,
            notificationControllerFactory,
            headlessHandler,
            focusedDisplayRepository,
        )

    @Before
    fun setUp() {
        whenever(controllerFactory.create(any())).thenReturn(controller)
        whenever(notificationControllerFactory.create(eq(0))).thenReturn(notificationsController0)
        whenever(notificationControllerFactory.create(eq(1))).thenReturn(notificationsController1)
    }

    @Test
    @DisableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_severalDisplays_callsControllerForEachOne() =
        testScope.runTest {
            val internalDisplay = display(TYPE_INTERNAL, id = 0)
            val externalDisplay = display(TYPE_EXTERNAL, id = 1)
            setDisplays(internalDisplay, externalDisplay)
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            verify(controllerFactory).create(eq(internalDisplay))
            verify(controllerFactory, never()).create(eq(externalDisplay))

            val capturer = ArgumentCaptor<ScreenshotData>()

            verify(controller).handleScreenshot(capturer.capture(), any(), any())
            assertThat(capturer.value.displayId).isEqualTo(0)
            // OnSaved callback should be different.
            verify(headlessHandler).handleScreenshot(capturer.capture(), any(), any())
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
    @DisableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_providedImageType_callsOnlyDefaultDisplayController() =
        testScope.runTest {
            val internalDisplay = display(TYPE_INTERNAL, id = 0)
            val externalDisplay = display(TYPE_EXTERNAL, id = 1)
            setDisplays(internalDisplay, externalDisplay)
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(
                createScreenshotRequest(TAKE_SCREENSHOT_PROVIDED_IMAGE),
                onSaved,
                callback,
            )

            verify(controllerFactory).create(eq(internalDisplay))
            verify(controllerFactory, never()).create(eq(externalDisplay))

            val capturer = ArgumentCaptor<ScreenshotData>()

            verify(controller).handleScreenshot(capturer.capture(), any(), any())
            assertThat(capturer.value.displayId).isEqualTo(0)
            // OnSaved callback should be different.
            verify(headlessHandler, never()).handleScreenshot(any(), any(), any())

            assertThat(eventLogger.numLogs()).isEqualTo(1)
            assertThat(eventLogger.get(0).eventId)
                .isEqualTo(ScreenshotEvent.SCREENSHOT_REQUESTED_KEY_OTHER.id)
            assertThat(eventLogger.get(0).packageName).isEqualTo(topComponent.packageName)

            screenshotExecutor.onDestroy()
        }

    @Test
    @DisableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_onlyVirtualDisplays_noInteractionsWithControllers() =
        testScope.runTest {
            setDisplays(display(TYPE_VIRTUAL, id = 0), display(TYPE_VIRTUAL, id = 1))
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            verifyNoMoreInteractions(controllerFactory)
            verify(headlessHandler, never()).handleScreenshot(any(), any(), any())
            screenshotExecutor.onDestroy()
        }

    @Test
    @DisableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_allowedTypes_allCaptured() =
        testScope.runTest {
            whenever(controllerFactory.create(any())).thenReturn(controller)

            setDisplays(
                display(TYPE_INTERNAL, id = 0),
                display(TYPE_EXTERNAL, id = 1),
                display(TYPE_OVERLAY, id = 2),
                display(TYPE_WIFI, id = 3),
            )
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            verify(controller, times(1)).handleScreenshot(any(), any(), any())
            verify(headlessHandler, times(3)).handleScreenshot(any(), any(), any())
            screenshotExecutor.onDestroy()
        }

    @Test
    @DisableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_reportsOnFinishedOnlyWhenBothFinished() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            val capturer0 = ArgumentCaptor<TakeScreenshotService.RequestCallback>()
            val capturer1 = ArgumentCaptor<TakeScreenshotService.RequestCallback>()

            verify(controller).handleScreenshot(any(), any(), capturer0.capture())
            verify(headlessHandler).handleScreenshot(any(), any(), capturer1.capture())

            verify(callback, never()).onFinish()

            capturer0.value.onFinish()

            verify(callback, never()).onFinish()

            capturer1.value.onFinish()

            verify(callback).onFinish()
            screenshotExecutor.onDestroy()
        }

    @Test
    @DisableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_oneFinishesOtherFails_reportFailsOnlyAtTheEnd() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            val capturer0 = ArgumentCaptor<TakeScreenshotService.RequestCallback>()
            val capturer1 = ArgumentCaptor<TakeScreenshotService.RequestCallback>()

            verify(controller).handleScreenshot(any(), any(), capturer0.capture())
            verify(headlessHandler).handleScreenshot(any(), any(), capturer1.capture())

            verify(callback, never()).onFinish()

            capturer0.value.onFinish()

            verify(callback, never()).onFinish()

            capturer1.value.reportError()

            verify(callback, never()).onFinish()
            verify(callback).reportError()

            screenshotExecutor.onDestroy()
        }

    @Test
    @DisableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_allDisplaysFail_reportsFail() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            val capturer0 = ArgumentCaptor<TakeScreenshotService.RequestCallback>()
            val capturer1 = ArgumentCaptor<TakeScreenshotService.RequestCallback>()

            verify(controller).handleScreenshot(any(), any(), capturer0.capture())
            verify(headlessHandler).handleScreenshot(any(), any(), capturer1.capture())

            verify(callback, never()).onFinish()

            capturer0.value.reportError()

            verify(callback, never()).onFinish()
            verify(callback, never()).reportError()

            capturer1.value.reportError()

            verify(callback, never()).onFinish()
            verify(callback).reportError()
            screenshotExecutor.onDestroy()
        }

    @Test
    @EnableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_fromOverview_honorsDisplay() =
        testScope.runTest {
            val displayId = 1
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = displayId))
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(
                createScreenshotRequest(
                    displayId = displayId,
                    source = WindowManager.ScreenshotSource.SCREENSHOT_OVERVIEW,
                ),
                onSaved,
                callback,
            )

            val dataCaptor = ArgumentCaptor<ScreenshotData>()

            verify(controller).handleScreenshot(dataCaptor.capture(), any(), any())

            assertThat(dataCaptor.value.displayId).isEqualTo(displayId)

            screenshotExecutor.onDestroy()
        }

    @Test
    @EnableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_fromOverviewInvalidDisplay_usesDefault() =
        testScope.runTest {
            setDisplays(
                display(TYPE_INTERNAL, id = Display.DEFAULT_DISPLAY),
                display(TYPE_EXTERNAL, id = 1),
            )
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(
                createScreenshotRequest(
                    displayId = 5,
                    source = WindowManager.ScreenshotSource.SCREENSHOT_OVERVIEW,
                ),
                onSaved,
                callback,
            )

            val dataCaptor = ArgumentCaptor<ScreenshotData>()

            verify(controller).handleScreenshot(dataCaptor.capture(), any(), any())

            assertThat(dataCaptor.value.displayId).isEqualTo(Display.DEFAULT_DISPLAY)

            screenshotExecutor.onDestroy()
        }

    @Test
    @EnableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_keyOther_usesFocusedDisplay() =
        testScope.runTest {
            val displayId = 1
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = displayId))
            val onSaved = { _: Uri? -> }
            focusedDisplayRepository.emit(displayId)

            screenshotExecutor.executeScreenshots(
                createScreenshotRequest(
                    source = WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER
                ),
                onSaved,
                callback,
            )

            val dataCaptor = ArgumentCaptor<ScreenshotData>()

            verify(controller).handleScreenshot(dataCaptor.capture(), any(), any())

            assertThat(dataCaptor.value.displayId).isEqualTo(displayId)

            screenshotExecutor.onDestroy()
        }

    @Test
    @EnableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_keyOtherInvalidDisplay_usesDefault() =
        testScope.runTest {
            setDisplays(
                display(TYPE_INTERNAL, id = Display.DEFAULT_DISPLAY),
                display(TYPE_EXTERNAL, id = 1),
            )
            focusedDisplayRepository.emit(5) // invalid display
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(
                createScreenshotRequest(
                    source = WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER
                ),
                onSaved,
                callback,
            )

            val dataCaptor = ArgumentCaptor<ScreenshotData>()

            verify(controller).handleScreenshot(dataCaptor.capture(), any(), any())

            assertThat(dataCaptor.value.displayId).isEqualTo(Display.DEFAULT_DISPLAY)

            screenshotExecutor.onDestroy()
        }

    @Test
    fun onDestroy_propagatedToControllers() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            screenshotExecutor.onDestroy()
            verify(controller).onDestroy()
        }

    @Test
    fun removeWindows_propagatedToController() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            screenshotExecutor.removeWindows()
            verify(controller).removeWindow()

            screenshotExecutor.onDestroy()
        }

    @Test
    fun onCloseSystemDialogsReceived_propagatedToController() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            screenshotExecutor.onCloseSystemDialogsReceived()
            verify(controller).requestDismissal(any())

            screenshotExecutor.onDestroy()
        }

    @Test
    fun onCloseSystemDialogsReceived_controllerHasPendingTransitions() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            whenever(controller.isPendingSharedTransition()).thenReturn(true)
            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            screenshotExecutor.onCloseSystemDialogsReceived()
            verify(controller, never()).requestDismissal(any())

            screenshotExecutor.onDestroy()
        }

    @Test
    fun executeScreenshots_controllerCalledWithRequestProcessorReturnValue() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0))
            val screenshotRequest = createScreenshotRequest()
            val toBeReturnedByProcessor = ScreenshotData.forTesting()
            requestProcessor.toReturn = toBeReturnedByProcessor

            val onSaved = { _: Uri? -> }
            screenshotExecutor.executeScreenshots(screenshotRequest, onSaved, callback)

            assertThat(requestProcessor.processed)
                .isEqualTo(ScreenshotData.fromRequest(screenshotRequest))

            val capturer = ArgumentCaptor<ScreenshotData>()
            verify(controller).handleScreenshot(capturer.capture(), any(), any())
            assertThat(capturer.value).isEqualTo(toBeReturnedByProcessor)

            screenshotExecutor.onDestroy()
        }

    @Test
    @DisableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_errorFromProcessor_logsScreenshotRequested() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            requestProcessor.shouldThrowException = true

            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            val screenshotRequested =
                eventLogger.logs.filter {
                    it.eventId == ScreenshotEvent.SCREENSHOT_REQUESTED_KEY_OTHER.id
                }
            assertThat(screenshotRequested).hasSize(2)
            screenshotExecutor.onDestroy()
        }

    @Test
    @DisableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_errorFromProcessor_logsUiError() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            requestProcessor.shouldThrowException = true

            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            val screenshotRequested =
                eventLogger.logs.filter {
                    it.eventId == ScreenshotEvent.SCREENSHOT_CAPTURE_FAILED.id
                }
            assertThat(screenshotRequested).hasSize(2)
            screenshotExecutor.onDestroy()
        }

    @Test
    fun executeScreenshots_errorFromProcessorOnDefaultDisplay_showsErrorNotification() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            requestProcessor.shouldThrowException = true

            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            verify(notificationsController0).notifyScreenshotError(any())
            screenshotExecutor.onDestroy()
        }

    @Test
    fun executeScreenshots_errorFromProcessorOnSecondaryDisplay_showsErrorNotification() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0))
            val onSaved = { _: Uri? -> }
            requestProcessor.shouldThrowException = true

            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            verify(notificationsController0).notifyScreenshotError(any())
            screenshotExecutor.onDestroy()
        }

    @Test
    @DisableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_errorFromScreenshotController_multidisplay_reportsRequested() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            whenever(controller.handleScreenshot(any(), any(), any()))
                .thenThrow(IllegalStateException::class.java)
            whenever(headlessHandler.handleScreenshot(any(), any(), any()))
                .thenThrow(IllegalStateException::class.java)

            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            val screenshotRequested =
                eventLogger.logs.filter {
                    it.eventId == ScreenshotEvent.SCREENSHOT_REQUESTED_KEY_OTHER.id
                }
            assertThat(screenshotRequested).hasSize(2)
            screenshotExecutor.onDestroy()
        }

    @Test
    @EnableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_errorFromScreenshotController_reportsRequested() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            whenever(controller.handleScreenshot(any(), any(), any()))
                .thenThrow(IllegalStateException::class.java)

            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            val screenshotRequested =
                eventLogger.logs.filter {
                    it.eventId == ScreenshotEvent.SCREENSHOT_REQUESTED_KEY_OTHER.id
                }
            assertThat(screenshotRequested).hasSize(1)
            screenshotExecutor.onDestroy()
        }

    @Test
    @DisableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_errorFromScreenshotController_multidisplay_reportsError() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            whenever(controller.handleScreenshot(any(), any(), any()))
                .thenThrow(IllegalStateException::class.java)
            whenever(headlessHandler.handleScreenshot(any(), any(), any()))
                .thenThrow(IllegalStateException::class.java)

            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            val screenshotRequested =
                eventLogger.logs.filter {
                    it.eventId == ScreenshotEvent.SCREENSHOT_CAPTURE_FAILED.id
                }
            assertThat(screenshotRequested).hasSize(2)
            screenshotExecutor.onDestroy()
        }

    @Test
    @EnableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_errorFromScreenshotController_reportsError() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            whenever(controller.handleScreenshot(any(), any(), any()))
                .thenThrow(IllegalStateException::class.java)

            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            val screenshotRequested =
                eventLogger.logs.filter {
                    it.eventId == ScreenshotEvent.SCREENSHOT_CAPTURE_FAILED.id
                }
            assertThat(screenshotRequested).hasSize(1)
            screenshotExecutor.onDestroy()
        }

    @Test
    @DisableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_errorFromScreenshotController_multidisplay_showsErrorNotification() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            whenever(controller.handleScreenshot(any(), any(), any()))
                .thenThrow(IllegalStateException::class.java)
            whenever(headlessHandler.handleScreenshot(any(), any(), any()))
                .thenThrow(IllegalStateException::class.java)

            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            verify(notificationsController0).notifyScreenshotError(any())
            verify(notificationsController1).notifyScreenshotError(any())
            screenshotExecutor.onDestroy()
        }

    @Test
    @EnableFlags(Flags.FLAG_SCREENSHOT_MULTIDISPLAY_FOCUS_CHANGE)
    fun executeScreenshots_errorFromScreenshotController_showsErrorNotification() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0), display(TYPE_EXTERNAL, id = 1))
            val onSaved = { _: Uri? -> }
            whenever(controller.handleScreenshot(any(), any(), any()))
                .thenThrow(IllegalStateException::class.java)

            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)

            verify(notificationsController0).notifyScreenshotError(any())
            screenshotExecutor.onDestroy()
        }

    @Test
    fun executeScreenshots_finisherCalledWithNullUri_succeeds() =
        testScope.runTest {
            setDisplays(display(TYPE_INTERNAL, id = 0))
            var onSavedCallCount = 0
            val onSaved: (Uri?) -> Unit = {
                assertThat(it).isNull()
                onSavedCallCount += 1
            }
            whenever(controller.handleScreenshot(any(), any(), any())).thenAnswer {
                (it.getArgument(1) as Consumer<Uri?>).accept(null)
            }

            screenshotExecutor.executeScreenshots(createScreenshotRequest(), onSaved, callback)
            assertThat(onSavedCallCount).isEqualTo(1)

            screenshotExecutor.onDestroy()
        }

    private suspend fun TestScope.setDisplays(vararg displays: Display) {
        fakeDisplayRepository.emit(displays.toSet())
        runCurrent()
    }

    private fun createScreenshotRequest(
        type: Int = WindowManager.TAKE_SCREENSHOT_FULLSCREEN,
        source: Int = WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ) =
        ScreenshotRequest.Builder(type, source)
            .setTopComponent(topComponent)
            .setDisplayId(displayId)
            .also {
                if (type == TAKE_SCREENSHOT_PROVIDED_IMAGE) {
                    it.setBitmap(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
                }
            }
            .build()

    private class FakeRequestProcessor : ScreenshotRequestProcessor {
        var processed: ScreenshotData? = null
        var toReturn: ScreenshotData? = null
        var shouldThrowException = false

        override suspend fun process(screenshot: ScreenshotData): ScreenshotData {
            if (shouldThrowException) throw RequestProcessorException("")
            processed = screenshot
            return toReturn ?: screenshot
        }
    }
}
