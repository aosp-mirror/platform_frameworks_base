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

package com.android.systemui.education

import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.education.shared.model.EducationUiType
import com.android.systemui.shared.system.SysUiStatsLog
import javax.inject.Inject

class ContextualEducationMetricsLogger @Inject constructor() {
    fun logContextualEducationTriggered(gestureType: GestureType, uiType: EducationUiType) {
        val statsGestureType =
            when (gestureType) {
                GestureType.BACK -> SysUiStatsLog.CONTEXTUAL_EDUCATION_TRIGGERED__GESTURE_TYPE__BACK
                GestureType.HOME -> SysUiStatsLog.CONTEXTUAL_EDUCATION_TRIGGERED__GESTURE_TYPE__HOME
                GestureType.OVERVIEW ->
                    SysUiStatsLog.CONTEXTUAL_EDUCATION_TRIGGERED__GESTURE_TYPE__OVERVIEW
                GestureType.ALL_APPS ->
                    SysUiStatsLog.CONTEXTUAL_EDUCATION_TRIGGERED__GESTURE_TYPE__ALL_APPS
            }

        val statsEducationType =
            when (uiType) {
                EducationUiType.Toast ->
                    SysUiStatsLog.CONTEXTUAL_EDUCATION_TRIGGERED__EDUCATION_TYPE__TOAST
                EducationUiType.Notification ->
                    SysUiStatsLog.CONTEXTUAL_EDUCATION_TRIGGERED__EDUCATION_TYPE__NOTIFICATION
            }
        SysUiStatsLog.write(
            SysUiStatsLog.CONTEXTUAL_EDUCATION_TRIGGERED,
            statsGestureType,
            statsEducationType,
        )
    }
}
