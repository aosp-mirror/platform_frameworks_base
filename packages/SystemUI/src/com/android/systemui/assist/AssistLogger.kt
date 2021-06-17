/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.assist

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.android.internal.app.AssistUtils
import com.android.internal.logging.InstanceId
import com.android.internal.logging.InstanceIdSequence
import com.android.internal.logging.UiEventLogger
import com.android.internal.util.FrameworkStatsLog
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.assist.AssistantInvocationEvent.Companion.deviceStateFromLegacyDeviceState
import com.android.systemui.assist.AssistantInvocationEvent.Companion.eventFromLegacyInvocationType
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/** Class for reporting events related to Assistant sessions. */
@SysUISingleton
open class AssistLogger @Inject constructor(
    protected val context: Context,
    protected val uiEventLogger: UiEventLogger,
    private val assistUtils: AssistUtils,
    private val phoneStateMonitor: PhoneStateMonitor
) {

    private val instanceIdSequence = InstanceIdSequence(INSTANCE_ID_MAX)

    private var currentInstanceId: InstanceId? = null

    fun reportAssistantInvocationEventFromLegacy(
        legacyInvocationType: Int,
        isInvocationComplete: Boolean,
        assistantComponent: ComponentName? = null,
        legacyDeviceState: Int? = null
    ) {
        val deviceState = if (legacyDeviceState == null) {
            null
        } else {
            deviceStateFromLegacyDeviceState(legacyDeviceState)
        }
        reportAssistantInvocationEvent(
                eventFromLegacyInvocationType(legacyInvocationType, isInvocationComplete),
                assistantComponent,
                deviceState)
    }

    fun reportAssistantInvocationEvent(
        invocationEvent: UiEventLogger.UiEventEnum,
        assistantComponent: ComponentName? = null,
        deviceState: Int? = null
    ) {

        val assistComponentFinal = assistantComponent ?: getAssistantComponentForCurrentUser()

        val assistantUid = getAssistantUid(assistComponentFinal)

        val deviceStateFinal = deviceState
                ?: deviceStateFromLegacyDeviceState(phoneStateMonitor.phoneState)

        FrameworkStatsLog.write(
                FrameworkStatsLog.ASSISTANT_INVOCATION_REPORTED,
                invocationEvent.id,
                assistantUid,
                assistComponentFinal.flattenToString(),
                getOrCreateInstanceId().id,
                deviceStateFinal,
                false)
        reportAssistantInvocationExtraData()
    }

    fun reportAssistantSessionEvent(sessionEvent: UiEventLogger.UiEventEnum) {
        val assistantComponent = getAssistantComponentForCurrentUser()
        val assistantUid = getAssistantUid(assistantComponent)
        uiEventLogger.logWithInstanceId(
                sessionEvent,
                assistantUid,
                assistantComponent.flattenToString(),
                getOrCreateInstanceId())

        if (SESSION_END_EVENTS.contains(sessionEvent)) {
            clearInstanceId()
        }
    }

    protected open fun reportAssistantInvocationExtraData() {
    }

    protected fun getOrCreateInstanceId(): InstanceId {
        val instanceId = currentInstanceId ?: instanceIdSequence.newInstanceId()
        currentInstanceId = instanceId
        return instanceId
    }

    protected fun clearInstanceId() {
        currentInstanceId = null
    }

    protected fun getAssistantComponentForCurrentUser(): ComponentName {
        return assistUtils.getAssistComponentForUser(KeyguardUpdateMonitor.getCurrentUser())
    }

    protected fun getAssistantUid(assistantComponent: ComponentName): Int {
        var assistantUid = 0
        try {
            assistantUid = context.packageManager.getApplicationInfo(
                    assistantComponent.packageName, /* flags = */
                    0).uid
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Unable to find Assistant UID", e)
        }
        return assistantUid
    }

    companion object {
        protected const val TAG = "AssistLogger"

        private const val INSTANCE_ID_MAX = 1 shl 20

        private val SESSION_END_EVENTS =
                setOf(
                        AssistantSessionEvent.ASSISTANT_SESSION_INVOCATION_CANCELLED,
                        AssistantSessionEvent.ASSISTANT_SESSION_CLOSE)
    }
}