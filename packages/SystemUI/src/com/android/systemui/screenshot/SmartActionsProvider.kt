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

import android.app.Notification
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.provider.DeviceConfig
import android.util.Log
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.systemui.log.DebugLogger.debugLog
import com.android.systemui.screenshot.LogConfig.DEBUG_ACTIONS
import com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider.ScreenshotSmartActionType.QUICK_SHARE_ACTION
import com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider.ScreenshotSmartActionType.REGULAR_SMART_ACTIONS
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import kotlin.random.Random

/**
 * Handle requesting smart/quickshare actions from the provider and executing an action when the
 * action futures complete.
 */
class SmartActionsProvider
@Inject
constructor(
    private val context: Context,
    private val smartActions: ScreenshotNotificationSmartActionsProvider,
) {
    /**
     * Requests quick share action for a given screenshot.
     *
     * @param data the ScreenshotData request
     * @param id the request id for the screenshot
     * @param onAction callback to run when quick share action is returned
     */
    fun requestQuickShare(
        data: ScreenshotData,
        id: String,
        onAction: (Notification.Action) -> Unit
    ) {
        val bitmap = data.bitmap ?: return
        val component = data.topComponent ?: ComponentName("", "")
        requestQuickShareAction(id, bitmap, component, data.getUserOrDefault()) { quickShare ->
            onAction(quickShare)
        }
    }

    /**
     * Requests smart actions for a given screenshot.
     *
     * @param data the ScreenshotData request
     * @param id the request id for the screenshot
     * @param result the data for the saved image
     * @param onActions callback to run when actions are returned
     */
    fun requestSmartActions(
        data: ScreenshotData,
        id: String,
        result: ScreenshotSavedResult,
        onActions: (List<Notification.Action>) -> Unit
    ) {
        val bitmap = data.bitmap ?: return
        val component = data.topComponent ?: ComponentName("", "")
        requestSmartActions(
            id,
            bitmap,
            component,
            data.getUserOrDefault(),
            result.uri,
            REGULAR_SMART_ACTIONS
        ) { actions ->
            onActions(actions)
        }
    }

    /**
     * Wraps the given quick share action in a broadcast intent.
     *
     * @param quickShare the quick share action to wrap
     * @param uri the URI of the saved screenshot
     * @param subject the subject/title for the screenshot
     * @param id the request ID of the screenshot
     * @return the pending intent with correct URI
     */
    fun wrapIntent(
        quickShare: Notification.Action,
        uri: Uri,
        subject: String,
        id: String
    ): PendingIntent {
        val wrappedIntent: Intent =
            Intent(context, SmartActionsReceiver::class.java)
                .putExtra(ScreenshotController.EXTRA_ACTION_INTENT, quickShare.actionIntent)
                .putExtra(
                    ScreenshotController.EXTRA_ACTION_INTENT_FILLIN,
                    createFillInIntent(uri, subject)
                )
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val extras: Bundle = quickShare.extras
        val actionType =
            extras.getString(
                ScreenshotNotificationSmartActionsProvider.ACTION_TYPE,
                ScreenshotNotificationSmartActionsProvider.DEFAULT_ACTION_TYPE
            )
        // We only query for quick share actions when smart actions are enabled, so we can assert
        // that it's true here.
        wrappedIntent
            .putExtra(ScreenshotController.EXTRA_ACTION_TYPE, actionType)
            .putExtra(ScreenshotController.EXTRA_ID, id)
            .putExtra(ScreenshotController.EXTRA_SMART_ACTIONS_ENABLED, true)
        return PendingIntent.getBroadcast(
            context,
            Random.nextInt(),
            wrappedIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createFillInIntent(uri: Uri, subject: String): Intent {
        val fillIn = Intent()
        fillIn.setType("image/png")
        fillIn.putExtra(Intent.EXTRA_STREAM, uri)
        fillIn.putExtra(Intent.EXTRA_SUBJECT, subject)
        // Include URI in ClipData also, so that grantPermission picks it up.
        // We don't use setData here because some apps interpret this as "to:".
        val clipData =
            ClipData(ClipDescription("content", arrayOf("image/png")), ClipData.Item(uri))
        fillIn.clipData = clipData
        fillIn.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return fillIn
    }

    private fun requestQuickShareAction(
        id: String,
        image: Bitmap,
        component: ComponentName,
        user: UserHandle,
        timeoutMs: Long = 500,
        onAction: (Notification.Action) -> Unit
    ) {
        requestSmartActions(id, image, component, user, null, QUICK_SHARE_ACTION, timeoutMs) {
            it.firstOrNull()?.let { action -> onAction(action) }
        }
    }

    private fun requestSmartActions(
        id: String,
        image: Bitmap,
        component: ComponentName,
        user: UserHandle,
        uri: Uri?,
        actionType: ScreenshotNotificationSmartActionsProvider.ScreenshotSmartActionType,
        timeoutMs: Long = 500,
        onActions: (List<Notification.Action>) -> Unit
    ) {
        val enabled = isSmartActionsEnabled(user)
        debugLog(DEBUG_ACTIONS) {
            ("getSmartActionsFuture id=$id, uri=$uri, provider=$smartActions, " +
                "actionType=$actionType, smartActionsEnabled=$enabled, userHandle=$user")
        }
        if (!enabled) {
            debugLog(DEBUG_ACTIONS) { "Screenshot Intelligence not enabled, returning empty list" }
            onActions(listOf())
            return
        }
        if (image.config != Bitmap.Config.HARDWARE) {
            debugLog(DEBUG_ACTIONS) {
                "Bitmap expected: Hardware, Bitmap found: ${image.config}. Returning empty list."
            }
            onActions(listOf())
            return
        }
        val smartActionsFuture: CompletableFuture<List<Notification.Action>>
        val startTimeMs = SystemClock.uptimeMillis()
        try {
            smartActionsFuture =
                smartActions.getActions(id, uri, image, component, actionType, user)
        } catch (e: Throwable) {
            val waitTimeMs = SystemClock.uptimeMillis() - startTimeMs
            debugLog(DEBUG_ACTIONS, error = e) {
                "Failed to get future for screenshot notification smart actions."
            }
            notifyScreenshotOp(
                id,
                ScreenshotNotificationSmartActionsProvider.ScreenshotOp.REQUEST_SMART_ACTIONS,
                ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.ERROR,
                waitTimeMs
            )
            onActions(listOf())
            return
        }
        try {
            val actions = smartActionsFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
            val waitTimeMs = SystemClock.uptimeMillis() - startTimeMs
            debugLog(DEBUG_ACTIONS) {
                ("Got ${actions.size} smart actions. Wait time: $waitTimeMs ms, " +
                    "actionType=$actionType")
            }
            notifyScreenshotOp(
                id,
                ScreenshotNotificationSmartActionsProvider.ScreenshotOp.WAIT_FOR_SMART_ACTIONS,
                ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.SUCCESS,
                waitTimeMs
            )
            onActions(actions)
        } catch (e: Throwable) {
            val waitTimeMs = SystemClock.uptimeMillis() - startTimeMs
            debugLog(DEBUG_ACTIONS, error = e) {
                "Error getting smart actions. Wait time: $waitTimeMs ms, actionType=$actionType"
            }
            val status =
                if (e is TimeoutException) {
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.TIMEOUT
                } else {
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.ERROR
                }
            notifyScreenshotOp(
                id,
                ScreenshotNotificationSmartActionsProvider.ScreenshotOp.WAIT_FOR_SMART_ACTIONS,
                status,
                waitTimeMs
            )
            onActions(listOf())
        }
    }

    private fun notifyScreenshotOp(
        screenshotId: String,
        op: ScreenshotNotificationSmartActionsProvider.ScreenshotOp,
        status: ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus,
        durationMs: Long
    ) {
        debugLog(DEBUG_ACTIONS) {
            "$smartActions notifyOp: $op id=$screenshotId, status=$status, durationMs=$durationMs"
        }
        try {
            smartActions.notifyOp(screenshotId, op, status, durationMs)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in notifyScreenshotOp: ", e)
        }
    }

    private fun isSmartActionsEnabled(user: UserHandle): Boolean {
        // Smart actions don't yet work for cross-user saves.
        val savingToOtherUser = user !== Process.myUserHandle()
        val actionsEnabled =
            DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.ENABLE_SCREENSHOT_NOTIFICATION_SMART_ACTIONS,
                true
            )
        return !savingToOtherUser && actionsEnabled
    }

    companion object {
        private const val TAG = "SmartActionsProvider"
        private const val SCREENSHOT_SHARE_SUBJECT_TEMPLATE = "Screenshot (%s)"
    }
}
