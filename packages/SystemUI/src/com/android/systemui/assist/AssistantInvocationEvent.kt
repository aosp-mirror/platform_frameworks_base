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

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.internal.util.FrameworkStatsLog.ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__AOD1
import com.android.internal.util.FrameworkStatsLog.ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__AOD2
import com.android.internal.util.FrameworkStatsLog.ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__APP_DEFAULT
import com.android.internal.util.FrameworkStatsLog.ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__APP_FULLSCREEN
import com.android.internal.util.FrameworkStatsLog.ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__APP_IMMERSIVE
import com.android.internal.util.FrameworkStatsLog.ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__BOUNCER
import com.android.internal.util.FrameworkStatsLog.ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__LAUNCHER_ALL_APPS
import com.android.internal.util.FrameworkStatsLog.ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__LAUNCHER_HOME
import com.android.internal.util.FrameworkStatsLog.ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__LAUNCHER_OVERVIEW
import com.android.internal.util.FrameworkStatsLog.ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__UNKNOWN_DEVICE_STATE
import com.android.internal.util.FrameworkStatsLog.ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__UNLOCKED_LOCKSCREEN

enum class AssistantInvocationEvent(private val id: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "Assistant invoked by unknown method")
    ASSISTANT_INVOCATION_UNKNOWN(442),

    @UiEvent(doc = "Assistant invoked by touch gesture")
    ASSISTANT_INVOCATION_TOUCH_GESTURE(443),

    @UiEvent(doc = "Assistant invoked by alternate touch gesture")
    ASSISTANT_INVOCATION_TOUCH_GESTURE_ALT(444),

    @UiEvent(doc = "Assistant invoked by hotword")
    ASSISTANT_INVOCATION_HOTWORD(445),

    @UiEvent(doc = "Assistant invoked by tapping quick search bar icon")
    ASSISTANT_INVOCATION_QUICK_SEARCH_BAR(446),

    @UiEvent(doc = "Assistant invoked by home button long press")
    ASSISTANT_INVOCATION_HOME_LONG_PRESS(447),

    @UiEvent(doc = "Assistant invoked by physical gesture")
    ASSISTANT_INVOCATION_PHYSICAL_GESTURE(448),

    @UiEvent(doc = "Assistant invocation started by unknown method")
    ASSISTANT_INVOCATION_START_UNKNOWN(530),

    @UiEvent(doc = "Assistant invocation started by touch gesture")
    ASSISTANT_INVOCATION_START_TOUCH_GESTURE(531),

    @UiEvent(doc = "Assistant invocation started by physical gesture")
    ASSISTANT_INVOCATION_START_PHYSICAL_GESTURE(532),

    @UiEvent(doc = "Assistant invoked by long press on the physical power button")
    ASSISTANT_INVOCATION_POWER_LONG_PRESS(758);

    override fun getId(): Int {
        return id
    }

    companion object {
        fun eventFromLegacyInvocationType(legacyInvocationType: Int, isInvocationComplete: Boolean):
                AssistantInvocationEvent {
            return if (isInvocationComplete) {
                when (legacyInvocationType) {
                    AssistManager.INVOCATION_TYPE_GESTURE ->
                        ASSISTANT_INVOCATION_TOUCH_GESTURE

                    AssistManager.INVOCATION_TYPE_OTHER ->
                        ASSISTANT_INVOCATION_PHYSICAL_GESTURE

                    AssistManager.INVOCATION_TYPE_VOICE ->
                        ASSISTANT_INVOCATION_HOTWORD

                    AssistManager.INVOCATION_TYPE_QUICK_SEARCH_BAR ->
                        ASSISTANT_INVOCATION_QUICK_SEARCH_BAR

                    AssistManager.INVOCATION_TYPE_HOME_BUTTON_LONG_PRESS ->
                        ASSISTANT_INVOCATION_HOME_LONG_PRESS

                    AssistManager.INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS ->
                        ASSISTANT_INVOCATION_POWER_LONG_PRESS

                    else ->
                        ASSISTANT_INVOCATION_UNKNOWN
                }
            } else {
                when (legacyInvocationType) {
                    AssistManager.INVOCATION_TYPE_GESTURE ->
                        ASSISTANT_INVOCATION_START_TOUCH_GESTURE

                    AssistManager.INVOCATION_TYPE_OTHER ->
                        ASSISTANT_INVOCATION_START_PHYSICAL_GESTURE

                    else -> ASSISTANT_INVOCATION_START_UNKNOWN
                }
            }
        }

        fun deviceStateFromLegacyDeviceState(legacyDeviceState: Int): Int {
            return when (legacyDeviceState) {
                PhoneStateMonitor.PHONE_STATE_AOD1 ->
                    ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__AOD1

                PhoneStateMonitor.PHONE_STATE_AOD2 ->
                    ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__AOD2

                PhoneStateMonitor.PHONE_STATE_BOUNCER ->
                    ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__BOUNCER

                PhoneStateMonitor.PHONE_STATE_UNLOCKED_LOCKSCREEN ->
                    ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__UNLOCKED_LOCKSCREEN

                PhoneStateMonitor.PHONE_STATE_HOME ->
                    ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__LAUNCHER_HOME

                PhoneStateMonitor.PHONE_STATE_OVERVIEW ->
                    ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__LAUNCHER_OVERVIEW

                PhoneStateMonitor.PHONE_STATE_ALL_APPS ->
                    ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__LAUNCHER_ALL_APPS

                PhoneStateMonitor.PHONE_STATE_APP_DEFAULT ->
                    ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__APP_DEFAULT

                PhoneStateMonitor.PHONE_STATE_APP_IMMERSIVE ->
                    ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__APP_IMMERSIVE

                PhoneStateMonitor.PHONE_STATE_APP_FULLSCREEN ->
                    ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__APP_FULLSCREEN

                else ->
                    ASSISTANT_INVOCATION_REPORTED__DEVICE_STATE__UNKNOWN_DEVICE_STATE
            }
        }
    }
}
