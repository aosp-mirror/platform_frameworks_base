/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.animation.Animator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Insets
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.ScrollCaptureResponse
import android.view.ViewRootImpl.ActivityConfigCallback
import android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN
import android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE
import android.widget.Toast
import android.window.WindowContext
import androidx.core.animation.doOnEnd
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.applications.InterestingConfigChanges
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.broadcast.BroadcastSender
import com.android.systemui.clipboardoverlay.ClipboardOverlayController
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.screenshot.ActionIntentCreator.createLongScreenshotIntent
import com.android.systemui.screenshot.ScreenshotShelfViewProxy.ScreenshotViewCallback
import com.android.systemui.screenshot.scroll.ScrollCaptureController.LongScreenshot
import com.android.systemui.screenshot.scroll.ScrollCaptureExecutor
import com.android.systemui.util.Assert
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer
import javax.inject.Provider
import kotlin.math.abs

/** Controls the state and flow for screenshots. */
class ScreenshotController
@AssistedInject
internal constructor(
    appContext: Context,
    screenshotWindowFactory: ScreenshotWindow.Factory,
    viewProxyFactory: ScreenshotShelfViewProxy.Factory,
    screenshotNotificationsControllerFactory: ScreenshotNotificationsController.Factory,
    screenshotActionsControllerFactory: ScreenshotActionsController.Factory,
    actionExecutorFactory: ActionExecutor.Factory,
    screenshotSoundControllerProvider: Provider<ScreenshotSoundController?>,
    private val uiEventLogger: UiEventLogger,
    private val imageExporter: ImageExporter,
    private val imageCapture: ImageCapture,
    private val scrollCaptureExecutor: ScrollCaptureExecutor,
    private val screenshotHandler: TimeoutHandler,
    private val broadcastSender: BroadcastSender,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val userManager: UserManager,
    private val assistContentRequester: AssistContentRequester,
    private val messageContainerController: MessageContainerController,
    private val announcementResolver: AnnouncementResolver,
    @Main private val mainExecutor: Executor,
    @Assisted private val display: Display,
) : InteractiveScreenshotHandler {
    private val context: WindowContext
    private val viewProxy: ScreenshotShelfViewProxy
    private val notificationController =
        screenshotNotificationsControllerFactory.create(display.displayId)
    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val actionsController: ScreenshotActionsController
    private val window: ScreenshotWindow
    private val actionExecutor: ActionExecutor
    private val copyBroadcastReceiver: BroadcastReceiver

    private var screenshotSoundController: ScreenshotSoundController? = null
    private var screenBitmap: Bitmap? = null
    private var screenshotTakenInPortrait = false
    private var screenshotAnimation: Animator? = null
    private var currentRequestCallback: TakeScreenshotService.RequestCallback? = null
    private var packageName = ""

    /** Tracks config changes that require re-creating UI */
    private val configChanges =
        InterestingConfigChanges(
            ActivityInfo.CONFIG_ORIENTATION or
                ActivityInfo.CONFIG_LAYOUT_DIRECTION or
                ActivityInfo.CONFIG_LOCALE or
                ActivityInfo.CONFIG_UI_MODE or
                ActivityInfo.CONFIG_SCREEN_LAYOUT or
                ActivityInfo.CONFIG_ASSETS_PATHS
        )

    init {
        screenshotHandler.defaultTimeoutMillis = SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS

        window = screenshotWindowFactory.create(display)
        context = window.getContext()

        viewProxy = viewProxyFactory.getProxy(context, display.displayId)

        screenshotHandler.setOnTimeoutRunnable {
            if (LogConfig.DEBUG_UI) {
                Log.d(TAG, "Corner timeout hit")
            }
            viewProxy.requestDismissal(ScreenshotEvent.SCREENSHOT_INTERACTION_TIMEOUT)
        }

        configChanges.applyNewConfig(appContext.resources)
        reloadAssets()

        actionExecutor = actionExecutorFactory.create(window.window, viewProxy) { finishDismiss() }
        actionsController = screenshotActionsControllerFactory.getController(actionExecutor)

        // Sound is only reproduced from the controller of the default display.
        screenshotSoundController =
            if (display.displayId == Display.DEFAULT_DISPLAY) {
                screenshotSoundControllerProvider.get()
            } else {
                null
            }

        copyBroadcastReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (ClipboardOverlayController.COPY_OVERLAY_ACTION == intent.action) {
                        viewProxy.requestDismissal(ScreenshotEvent.SCREENSHOT_DISMISSED_OTHER)
                    }
                }
            }
        broadcastDispatcher.registerReceiver(
            copyBroadcastReceiver,
            IntentFilter(ClipboardOverlayController.COPY_OVERLAY_ACTION),
            null,
            null,
            Context.RECEIVER_NOT_EXPORTED,
            ClipboardOverlayController.SELF_PERMISSION,
        )
    }

    override fun handleScreenshot(
        screenshot: ScreenshotData,
        finisher: Consumer<Uri?>,
        requestCallback: TakeScreenshotService.RequestCallback,
    ) {
        Assert.isMainThread()

        currentRequestCallback = requestCallback
        if (screenshot.type == TAKE_SCREENSHOT_FULLSCREEN && screenshot.bitmap == null) {
            val bounds = fullScreenRect
            screenshot.bitmap = imageCapture.captureDisplay(display.displayId, bounds)
            screenshot.screenBounds = bounds
        }

        val currentBitmap = screenshot.bitmap
        if (currentBitmap == null) {
            Log.e(TAG, "handleScreenshot: Screenshot bitmap was null")
            notificationController.notifyScreenshotError(R.string.screenshot_failed_to_capture_text)
            currentRequestCallback?.reportError()
            return
        }

        screenBitmap = currentBitmap
        val oldPackageName = packageName
        packageName = screenshot.packageNameString

        if (!isUserSetupComplete(Process.myUserHandle())) {
            Log.w(TAG, "User setup not complete, displaying toast only")
            // User setup isn't complete, so we don't want to show any UI beyond a toast, as editing
            // and sharing shouldn't be exposed to the user.
            saveScreenshotAndToast(screenshot, finisher)
            return
        }

        broadcastSender.sendBroadcast(
            Intent(ClipboardOverlayController.SCREENSHOT_ACTION),
            ClipboardOverlayController.SELF_PERMISSION,
        )

        screenshotTakenInPortrait =
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        // Optimizations
        currentBitmap.setHasAlpha(false)
        currentBitmap.prepareToDraw()

        prepareViewForNewScreenshot(screenshot, oldPackageName)
        val requestId = actionsController.setCurrentScreenshot(screenshot)
        saveScreenshotInBackground(screenshot, requestId, finisher) { result ->
            if (result.uri != null) {
                val savedScreenshot =
                    ScreenshotSavedResult(
                        result.uri,
                        screenshot.getUserOrDefault(),
                        result.timestamp,
                    )
                actionsController.setCompletedScreenshot(requestId, savedScreenshot)
            }
        }

        if (screenshot.taskId >= 0) {
            assistContentRequester.requestAssistContent(screenshot.taskId) { assistContent ->
                actionsController.onAssistContent(requestId, assistContent)
            }
        } else {
            actionsController.onAssistContent(requestId, null)
        }

        // The window is focusable by default
        window.setFocusable(true)
        viewProxy.requestFocus()

        enqueueScrollCaptureRequest(requestId, screenshot.userHandle!!)

        window.attachWindow()

        val showFlash: Boolean
        if (screenshot.type == TAKE_SCREENSHOT_PROVIDED_IMAGE) {
            if (aspectRatiosMatch(currentBitmap, screenshot.insets, screenshot.screenBounds)) {
                showFlash = false
            } else {
                showFlash = true
                screenshot.insets = Insets.NONE
                screenshot.screenBounds = Rect(0, 0, currentBitmap.width, currentBitmap.height)
            }
        } else {
            showFlash = true
        }

        // screenshot.screenBounds is expected to be non-null in all cases at this point
        val bounds =
            screenshot.screenBounds ?: Rect(0, 0, currentBitmap.width, currentBitmap.height)

        viewProxy.prepareEntranceAnimation {
            startAnimation(bounds, showFlash) {
                messageContainerController.onScreenshotTaken(screenshot)
            }
        }

        viewProxy.screenshot = screenshot
    }

    private fun prepareViewForNewScreenshot(screenshot: ScreenshotData, oldPackageName: String?) {
        window.whenWindowAttached {
            announcementResolver.getScreenshotAnnouncement(screenshot.userHandle!!.identifier) {
                viewProxy.announceForAccessibility(it)
            }
        }

        viewProxy.reset()

        if (viewProxy.isAttachedToWindow) {
            // if we didn't already dismiss for another reason
            if (!viewProxy.isDismissing) {
                uiEventLogger.log(ScreenshotEvent.SCREENSHOT_REENTERED, 0, oldPackageName)
            }
            if (LogConfig.DEBUG_WINDOW) {
                Log.d(
                    TAG,
                    "saveScreenshot: screenshotView is already attached, resetting. " +
                        "(dismissing=${viewProxy.isDismissing})",
                )
            }
        }

        viewProxy.packageName = packageName
    }

    /**
     * Requests the view to dismiss the current screenshot (may be ignored, if screenshot is already
     * being dismissed)
     */
    override fun requestDismissal(event: ScreenshotEvent) {
        viewProxy.requestDismissal(event)
    }

    override fun isPendingSharedTransition(): Boolean {
        return actionExecutor.isPendingSharedTransition
    }

    // Any cleanup needed when the service is being destroyed.
    override fun onDestroy() {
        removeWindow()
        releaseMediaPlayer()
        releaseContext()
        bgExecutor.shutdown()
    }

    /** Release the constructed window context. */
    private fun releaseContext() {
        broadcastDispatcher.unregisterReceiver(copyBroadcastReceiver)
        context.release()
    }

    private fun releaseMediaPlayer() {
        screenshotSoundController?.releaseScreenshotSoundAsync()
    }

    /** Update resources on configuration change. Reinflate for theme/color changes. */
    private fun reloadAssets() {
        if (LogConfig.DEBUG_UI) {
            Log.d(TAG, "reloadAssets()")
        }

        messageContainerController.setView(viewProxy.view)
        viewProxy.callbacks =
            object : ScreenshotViewCallback {
                override fun onUserInteraction() {
                    if (LogConfig.DEBUG_INPUT) {
                        Log.d(TAG, "onUserInteraction")
                    }
                    screenshotHandler.resetTimeout()
                }

                override fun onDismiss() {
                    finishDismiss()
                }

                override fun onTouchOutside() {
                    // TODO(159460485): Remove this when focus is handled properly in the system
                    window.setFocusable(false)
                }
            }

        if (LogConfig.DEBUG_WINDOW) {
            Log.d(TAG, "setContentView: " + viewProxy.view)
        }
        window.setContentView(viewProxy.view)
    }

    private fun enqueueScrollCaptureRequest(requestId: UUID, owner: UserHandle) {
        // Wait until this window is attached to request because it is
        // the reference used to locate the target window (below).
        window.whenWindowAttached {
            requestScrollCapture(requestId, owner)
            window.setActivityConfigCallback(
                object : ActivityConfigCallback {
                    override fun onConfigurationChanged(
                        overrideConfig: Configuration,
                        newDisplayId: Int,
                    ) {
                        if (configChanges.applyNewConfig(context.resources)) {
                            // Hide the scroll chip until we know it's available in this
                            // orientation
                            actionsController.onScrollChipInvalidated()
                            // Delay scroll capture eval a bit to allow the underlying activity
                            // to set up in the new orientation.
                            screenshotHandler.postDelayed(
                                { requestScrollCapture(requestId, owner) },
                                150,
                            )
                            viewProxy.updateInsets(window.getWindowInsets())
                            // Screenshot animation calculations won't be valid anymore, so just end
                            screenshotAnimation?.let { currentAnimation ->
                                if (currentAnimation.isRunning) {
                                    currentAnimation.end()
                                }
                            }
                        }
                    }
                }
            )
        }
    }

    private fun requestScrollCapture(requestId: UUID, owner: UserHandle) {
        scrollCaptureExecutor.requestScrollCapture(display.displayId, window.getWindowToken()) {
            response: ScrollCaptureResponse ->
            uiEventLogger.log(
                ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_IMPRESSION,
                0,
                response.packageName,
            )
            actionsController.onScrollChipReady(requestId) {
                onScrollButtonClicked(owner, response)
            }
        }
    }

    private fun onScrollButtonClicked(owner: UserHandle, response: ScrollCaptureResponse) {
        if (LogConfig.DEBUG_INPUT) {
            Log.d(TAG, "scroll chip tapped")
        }
        uiEventLogger.log(
            ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_REQUESTED,
            0,
            response.packageName,
        )
        val newScreenshot = imageCapture.captureDisplay(display.displayId, null)
        if (newScreenshot == null) {
            Log.e(TAG, "Failed to capture current screenshot for scroll transition!")
            return
        }
        // delay starting scroll capture to make sure scrim is up before the app moves
        viewProxy.prepareScrollingTransition(response, newScreenshot, screenshotTakenInPortrait) {
            executeBatchScrollCapture(response, owner)
        }
    }

    private fun executeBatchScrollCapture(response: ScrollCaptureResponse, owner: UserHandle) {
        scrollCaptureExecutor.executeBatchScrollCapture(
            response,
            {
                val intent = createLongScreenshotIntent(owner, context)
                context.startActivity(intent)
            },
            { viewProxy.restoreNonScrollingUi() },
            { transitionDestination: Rect, onTransitionEnd: Runnable, longScreenshot: LongScreenshot
                ->
                viewProxy.startLongScreenshotTransition(
                    transitionDestination,
                    onTransitionEnd,
                    longScreenshot,
                )
            },
        )
    }

    override fun removeWindow() {
        window.removeWindow()
        viewProxy.stopInputListening()
    }

    private fun playCameraSoundIfNeeded() {
        // the controller is not-null only on the default display controller
        screenshotSoundController?.playScreenshotSoundAsync()
    }

    /**
     * Save the bitmap but don't show the normal screenshot UI.. just a toast (or notification on
     * failure).
     */
    private fun saveScreenshotAndToast(screenshot: ScreenshotData, finisher: Consumer<Uri?>) {
        // Play the shutter sound to notify that we've taken a screenshot
        playCameraSoundIfNeeded()

        saveScreenshotInBackground(screenshot, UUID.randomUUID(), finisher) {
            result: ImageExporter.Result ->
            if (result.uri != null) {
                screenshotHandler.post {
                    Toast.makeText(context, R.string.screenshot_saved_title, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    /** Starts the animation after taking the screenshot */
    private fun startAnimation(
        screenRect: Rect,
        showFlash: Boolean,
        onAnimationComplete: Runnable?,
    ) {
        screenshotAnimation?.let { currentAnimation ->
            if (currentAnimation.isRunning) {
                currentAnimation.cancel()
            }
        }

        screenshotAnimation =
            viewProxy.createScreenshotDropInAnimation(screenRect, showFlash).apply {
                doOnEnd { onAnimationComplete?.run() }
                // Play the shutter sound to notify that we've taken a screenshot
                playCameraSoundIfNeeded()
                if (LogConfig.DEBUG_ANIM) {
                    Log.d(TAG, "starting post-screenshot animation")
                }
                start()
            }
    }

    /** Reset screenshot view and then call onCompleteRunnable */
    private fun finishDismiss() {
        Log.d(TAG, "finishDismiss")
        actionsController.endScreenshotSession()
        scrollCaptureExecutor.close()
        currentRequestCallback?.onFinish()
        currentRequestCallback = null
        viewProxy.reset()
        removeWindow()
        screenshotHandler.cancelTimeout()
    }

    private fun saveScreenshotInBackground(
        screenshot: ScreenshotData,
        requestId: UUID,
        finisher: Consumer<Uri?>,
        onResult: Consumer<ImageExporter.Result>,
    ) {
        val future =
            imageExporter.export(
                bgExecutor,
                requestId,
                screenshot.bitmap,
                screenshot.getUserOrDefault(),
                display.displayId,
            )
        future.addListener(
            {
                try {
                    val result = future.get()
                    Log.d(TAG, "Saved screenshot: $result")
                    logScreenshotResultStatus(result.uri, screenshot.userHandle!!)
                    onResult.accept(result)
                    if (LogConfig.DEBUG_CALLBACK) {
                        Log.d(TAG, "finished bg processing, calling back with uri: ${result.uri}")
                    }
                    finisher.accept(result.uri)
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to store screenshot", e)
                    if (LogConfig.DEBUG_CALLBACK) {
                        Log.d(TAG, "calling back with uri: null")
                    }
                    finisher.accept(null)
                }
            },
            mainExecutor,
        )
    }

    /** Logs success/failure of the screenshot saving task, and shows an error if it failed. */
    private fun logScreenshotResultStatus(uri: Uri?, owner: UserHandle) {
        if (uri == null) {
            uiEventLogger.log(ScreenshotEvent.SCREENSHOT_NOT_SAVED, 0, packageName)
            notificationController.notifyScreenshotError(R.string.screenshot_failed_to_save_text)
        } else {
            uiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED, 0, packageName)
            if (userManager.isManagedProfile(owner.identifier)) {
                uiEventLogger.log(ScreenshotEvent.SCREENSHOT_SAVED_TO_WORK_PROFILE, 0, packageName)
            }
        }
    }

    private fun isUserSetupComplete(owner: UserHandle): Boolean {
        return Settings.Secure.getInt(
            context.createContextAsUser(owner, 0).contentResolver,
            SETTINGS_SECURE_USER_SETUP_COMPLETE,
            0,
        ) == 1
    }

    private val fullScreenRect: Rect
        get() {
            val displayMetrics = DisplayMetrics()
            display.getRealMetrics(displayMetrics)
            return Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        }

    /** Injectable factory to create screenshot controller instances for a specific display. */
    @AssistedFactory
    interface Factory : InteractiveScreenshotHandler.Factory {
        /**
         * Creates an instance of the controller for that specific display.
         *
         * @param display display to capture
         */
        override fun create(display: Display): ScreenshotController
    }

    companion object {
        private val TAG: String = LogConfig.logTag(ScreenshotController::class.java)

        // From WizardManagerHelper.java
        private const val SETTINGS_SECURE_USER_SETUP_COMPLETE = "user_setup_complete"

        const val SCREENSHOT_CORNER_DEFAULT_TIMEOUT_MILLIS: Int = 6000

        /** Does the aspect ratio of the bitmap with insets removed match the bounds. */
        private fun aspectRatiosMatch(
            bitmap: Bitmap,
            bitmapInsets: Insets,
            screenBounds: Rect?,
        ): Boolean {
            if (screenBounds == null) {
                return false
            }
            val insettedWidth = bitmap.width - bitmapInsets.left - bitmapInsets.right
            val insettedHeight = bitmap.height - bitmapInsets.top - bitmapInsets.bottom

            if (
                insettedHeight == 0 || insettedWidth == 0 || bitmap.width == 0 || bitmap.height == 0
            ) {
                if (LogConfig.DEBUG_UI) {
                    Log.e(
                        TAG,
                        "Provided bitmap and insets create degenerate region: " +
                            "${bitmap.width} x ${bitmap.height} $bitmapInsets",
                    )
                }
                return false
            }

            val insettedBitmapAspect = insettedWidth.toFloat() / insettedHeight
            val boundsAspect = screenBounds.width().toFloat() / screenBounds.height()

            val matchWithinTolerance = abs((insettedBitmapAspect - boundsAspect).toDouble()) < 0.1f
            if (LogConfig.DEBUG_UI) {
                Log.d(
                    TAG,
                    "aspectRatiosMatch: don't match bitmap: " +
                        "$insettedBitmapAspect, bounds: $boundsAspect",
                )
            }
            return matchWithinTolerance
        }
    }
}
