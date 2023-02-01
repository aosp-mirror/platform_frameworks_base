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

import android.annotation.UserIdInt
import android.app.ActivityTaskManager
import android.app.ActivityTaskManager.RootTaskInfo
import android.app.IActivityTaskManager
import android.app.WindowConfiguration
import android.app.WindowConfiguration.activityTypeToString
import android.app.WindowConfiguration.windowingModeToString
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Process
import android.os.RemoteException
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.Display.DEFAULT_DISPLAY
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.infra.ServiceConnector
import com.android.systemui.SystemUIService
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.screenshot.ScreenshotPolicy.DisplayContentInfo
import java.util.Arrays
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@SysUISingleton
internal open class ScreenshotPolicyImpl @Inject constructor(
    context: Context,
    private val userMgr: UserManager,
    private val atmService: IActivityTaskManager,
    @Background val bgDispatcher: CoroutineDispatcher,
) : ScreenshotPolicy {

    private val proxyConnector: ServiceConnector<IScreenshotProxy> =
        ServiceConnector.Impl(
            context,
            Intent(context, ScreenshotProxyService::class.java),
            Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY or Context.BIND_NOT_VISIBLE,
            context.userId,
            IScreenshotProxy.Stub::asInterface
        )

    override fun getDefaultDisplayId(): Int {
        return DEFAULT_DISPLAY
    }

    override suspend fun isManagedProfile(@UserIdInt userId: Int): Boolean {
        return withContext(bgDispatcher) { userMgr.isManagedProfile(userId) }
    }

    private fun nonPipVisibleTask(info: RootTaskInfo): Boolean {
        if (DEBUG) {
            debugLogRootTaskInfo(info)
        }
        return info.windowingMode != WindowConfiguration.WINDOWING_MODE_PINNED &&
            info.isVisible &&
            info.isRunning &&
            info.numActivities > 0 &&
            info.topActivity != null &&
            info.childTaskIds.isNotEmpty()
    }

    /**
     * Uses RootTaskInfo from ActivityTaskManager to guess at the primary focused task within a
     * display. If no task is visible or the top task is covered by a system window, the info
     * reported will reference a SystemUI component instead.
     */
    override suspend fun findPrimaryContent(displayId: Int): DisplayContentInfo {
        // Determine if the notification shade is expanded. If so, task windows are not
        // visible behind it, so the screenshot should instead be associated with SystemUI.
        if (isNotificationShadeExpanded()) {
            return systemUiContent
        }

        val taskInfoList = getAllRootTaskInfosOnDisplay(displayId)

        // If no visible task is located, then report SystemUI as the foreground content
        val target = taskInfoList.firstOrNull(::nonPipVisibleTask) ?: return systemUiContent
        return target.toDisplayContentInfo()
    }

    private fun debugLogRootTaskInfo(info: RootTaskInfo) {
        Log.d(TAG, "RootTaskInfo={" +
                "taskId=${info.taskId} " +
                "parentTaskId=${info.parentTaskId} " +
                "position=${info.position} " +
                "positionInParent=${info.positionInParent} " +
                "isVisible=${info.isVisible()} " +
                "visible=${info.visible} " +
                "isFocused=${info.isFocused} " +
                "isSleeping=${info.isSleeping} " +
                "isRunning=${info.isRunning} " +
                "windowMode=${windowingModeToString(info.windowingMode)} " +
                "activityType=${activityTypeToString(info.activityType)} " +
                "topActivity=${info.topActivity} " +
                "topActivityInfo=${info.topActivityInfo} " +
                "numActivities=${info.numActivities} " +
                "childTaskIds=${Arrays.toString(info.childTaskIds)} " +
                "childUserIds=${Arrays.toString(info.childTaskUserIds)} " +
                "childTaskBounds=${Arrays.toString(info.childTaskBounds)} " +
                "childTaskNames=${Arrays.toString(info.childTaskNames)}" +
                "}"
        )

        for (j in 0 until info.childTaskIds.size) {
            Log.d(TAG, "    *** [$j] ******")
            Log.d(TAG, "        ***  childTaskIds[$j]: ${info.childTaskIds[j]}")
            Log.d(TAG, "        ***  childTaskUserIds[$j]: ${info.childTaskUserIds[j]}")
            Log.d(TAG, "        ***  childTaskBounds[$j]: ${info.childTaskBounds[j]}")
            Log.d(TAG, "        ***  childTaskNames[$j]: ${info.childTaskNames[j]}")
        }
    }

    @VisibleForTesting
    open suspend fun getAllRootTaskInfosOnDisplay(displayId: Int): List<RootTaskInfo> =
        withContext(bgDispatcher) {
            try {
                atmService.getAllRootTaskInfosOnDisplay(displayId)
            } catch (e: RemoteException) {
                Log.e(TAG, "getAllRootTaskInfosOnDisplay", e)
                listOf()
            }
        }

    @VisibleForTesting
    open suspend fun isNotificationShadeExpanded(): Boolean = suspendCoroutine { k ->
        proxyConnector
            .postForResult { it.isNotificationShadeExpanded }
            .whenComplete { expanded, error ->
                if (error != null) {
                    Log.e(TAG, "isNotificationShadeExpanded", error)
                }
                k.resume(expanded ?: false)
            }
    }

    @VisibleForTesting
    internal val systemUiContent =
        DisplayContentInfo(
            ComponentName(context, SystemUIService::class.java),
            Rect(),
            Process.myUserHandle(),
            ActivityTaskManager.INVALID_TASK_ID
        )
}

private const val TAG: String = "ScreenshotPolicyImpl"
private const val DEBUG: Boolean = false

@VisibleForTesting
internal fun RootTaskInfo.toDisplayContentInfo(): DisplayContentInfo {
    val topActivity: ComponentName = topActivity ?: error("should not be null")
    val topChildTask = childTaskIds.size - 1
    val childTaskId = childTaskIds[topChildTask]
    val childTaskUserId = childTaskUserIds[topChildTask]
    val childTaskBounds = childTaskBounds[topChildTask]

    return DisplayContentInfo(
        topActivity,
        childTaskBounds,
        UserHandle.of(childTaskUserId),
        childTaskId)
}
