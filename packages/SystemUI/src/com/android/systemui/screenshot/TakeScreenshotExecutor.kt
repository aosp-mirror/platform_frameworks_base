package com.android.systemui.screenshot

import android.net.Uri
import android.os.Trace
import android.util.Log
import android.view.Display
import android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE
import com.android.app.tracing.TraceUtils.Companion.launch
import com.android.internal.logging.UiEventLogger
import com.android.internal.util.ScreenshotRequest
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.display.data.repository.DisplayRepository
import com.android.systemui.res.R
import com.android.systemui.screenshot.ScreenshotEvent.SCREENSHOT_CAPTURE_FAILED
import com.android.systemui.screenshot.TakeScreenshotService.RequestCallback
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * Receives the signal to take a screenshot from [TakeScreenshotService], and calls back with the
 * result.
 *
 * Captures a screenshot for each [Display] available.
 */
@SysUISingleton
class TakeScreenshotExecutor
@Inject
constructor(
    private val screenshotControllerFactory: ScreenshotController.Factory,
    displayRepository: DisplayRepository,
    @Application private val mainScope: CoroutineScope,
    private val screenshotRequestProcessor: ScreenshotRequestProcessor,
    private val uiEventLogger: UiEventLogger,
    private val screenshotNotificationControllerFactory: ScreenshotNotificationsController.Factory,
) {

    private val displays = displayRepository.displays
    private val screenshotControllers = mutableMapOf<Int, ScreenshotController>()
    private val notificationControllers = mutableMapOf<Int, ScreenshotNotificationsController>()

    /**
     * Executes the [ScreenshotRequest].
     *
     * [onSaved] is invoked only on the default display result. [RequestCallback.onFinish] is
     * invoked only when both screenshot UIs are removed.
     */
    suspend fun executeScreenshots(
        screenshotRequest: ScreenshotRequest,
        onSaved: (Uri) -> Unit,
        requestCallback: RequestCallback
    ) {
        val displayIds = getDisplaysToScreenshot(screenshotRequest.type)
        val resultCallbackWrapper = MultiResultCallbackWrapper(requestCallback)
        displayIds.forEach { displayId: Int ->
            Log.d(TAG, "Executing screenshot for display $displayId")
            dispatchToController(
                rawScreenshotData = ScreenshotData.fromRequest(screenshotRequest, displayId),
                onSaved =
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        onSaved
                    } else { _ -> },
                callback = resultCallbackWrapper.createCallbackForId(displayId)
            )
        }
    }

    /** All logging should be triggered only by this method. */
    private suspend fun dispatchToController(
        rawScreenshotData: ScreenshotData,
        onSaved: (Uri) -> Unit,
        callback: RequestCallback
    ) {
        // Let's wait before logging "screenshot requested", as we should log the processed
        // ScreenshotData.
        val screenshotData =
            try {
                screenshotRequestProcessor.process(rawScreenshotData)
            } catch (e: RequestProcessorException) {
                Log.e(TAG, "Failed to process screenshot request!", e)
                logScreenshotRequested(rawScreenshotData)
                onFailedScreenshotRequest(rawScreenshotData, callback)
                return
            }

        logScreenshotRequested(screenshotData)
        Log.d(TAG, "Screenshot request: $screenshotData")
        try {
            getScreenshotController(screenshotData.displayId)
                .handleScreenshot(screenshotData, onSaved, callback)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Error while ScreenshotController was handling ScreenshotData!", e)
            onFailedScreenshotRequest(screenshotData, callback)
            return // After a failure log, nothing else should run.
        }
    }

    /**
     * This should be logged also in case of failed requests, before the [SCREENSHOT_CAPTURE_FAILED]
     * event.
     */
    private fun logScreenshotRequested(screenshotData: ScreenshotData) {
        uiEventLogger.log(
            ScreenshotEvent.getScreenshotSource(screenshotData.source),
            0,
            screenshotData.packageNameString
        )
    }

    private fun onFailedScreenshotRequest(
        screenshotData: ScreenshotData,
        callback: RequestCallback
    ) {
        uiEventLogger.log(SCREENSHOT_CAPTURE_FAILED, 0, screenshotData.packageNameString)
        getNotificationController(screenshotData.displayId)
            .notifyScreenshotError(R.string.screenshot_failed_to_capture_text)
        callback.reportError()
    }

    private suspend fun getDisplaysToScreenshot(requestType: Int): List<Int> {
        return if (requestType == TAKE_SCREENSHOT_PROVIDED_IMAGE) {
            // If this is a provided image, let's show the UI on the default display only.
            listOf(Display.DEFAULT_DISPLAY)
        } else {
            displays.first().filter { it.type in ALLOWED_DISPLAY_TYPES }.map { it.displayId }
        }
    }

    /**
     * Propagates the close system dialog signal to all controllers.
     *
     * TODO(b/295143676): Move the receiver in this class once the flag is flipped.
     */
    fun onCloseSystemDialogsReceived() {
        screenshotControllers.forEach { (_, screenshotController) ->
            if (!screenshotController.isPendingSharedTransition) {
                screenshotController.dismissScreenshot(ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER)
            }
        }
    }

    /** Removes all screenshot related windows. */
    fun removeWindows() {
        screenshotControllers.forEach { (_, screenshotController) ->
            screenshotController.removeWindow()
        }
    }

    /**
     * Destroys the executor. Afterwards, this class is not expected to work as intended anymore.
     */
    fun onDestroy() {
        screenshotControllers.forEach { (_, screenshotController) ->
            screenshotController.onDestroy()
        }
        screenshotControllers.clear()
    }

    private fun getScreenshotController(id: Int): ScreenshotController {
        return screenshotControllers.computeIfAbsent(id) {
            screenshotControllerFactory.create(id, /* showUIOnExternalDisplay= */ false)
        }
    }

    private fun getNotificationController(id: Int): ScreenshotNotificationsController {
        return notificationControllers.computeIfAbsent(id) {
            screenshotNotificationControllerFactory.create(id)
        }
    }

    /** For java compatibility only. see [executeScreenshots] */
    fun executeScreenshotsAsync(
        screenshotRequest: ScreenshotRequest,
        onSaved: Consumer<Uri>,
        requestCallback: RequestCallback
    ) {
        mainScope.launch("TakeScreenshotService#executeScreenshotsAsync") {
            executeScreenshots(screenshotRequest, { uri -> onSaved.accept(uri) }, requestCallback)
        }
    }

    /**
     * Returns a [RequestCallback] that wraps [originalCallback].
     *
     * Each [RequestCallback] created with [createCallbackForId] is expected to be used with either
     * [reportError] or [onFinish]. Once they are both called:
     * - If any finished with an error, [reportError] of [originalCallback] is called
     * - Otherwise, [onFinish] is called.
     */
    private class MultiResultCallbackWrapper(
        private val originalCallback: RequestCallback,
    ) {
        private val idsPending = mutableSetOf<Int>()
        private val idsWithErrors = mutableSetOf<Int>()

        /**
         * Creates a callback for [id].
         *
         * [originalCallback]'s [onFinish] will be called only when this (and the other created)
         * callback's [onFinish] have been called.
         */
        fun createCallbackForId(id: Int): RequestCallback {
            Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_APP, TAG, "Waiting for id=$id", id)
            idsPending += id
            return object : RequestCallback {
                override fun reportError() {
                    endTrace("reportError id=$id")
                    idsWithErrors += id
                    idsPending -= id
                    reportToOriginalIfNeeded()
                }

                override fun onFinish() {
                    endTrace("onFinish id=$id")
                    idsPending -= id
                    reportToOriginalIfNeeded()
                }

                private fun endTrace(reason: String) {
                    Log.d(TAG, "Finished waiting for id=$id. $reason")
                    Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_APP, TAG, id)
                    Trace.instantForTrack(Trace.TRACE_TAG_APP, TAG, reason)
                }
            }
        }

        private fun reportToOriginalIfNeeded() {
            if (idsPending.isNotEmpty()) return
            if (idsWithErrors.isEmpty()) {
                originalCallback.onFinish()
            } else {
                originalCallback.reportError()
            }
        }
    }

    private companion object {
        val TAG = LogConfig.logTag(TakeScreenshotService::class.java)

        val ALLOWED_DISPLAY_TYPES =
            listOf(
                Display.TYPE_EXTERNAL,
                Display.TYPE_INTERNAL,
                Display.TYPE_OVERLAY,
                Display.TYPE_WIFI
            )
    }
}
