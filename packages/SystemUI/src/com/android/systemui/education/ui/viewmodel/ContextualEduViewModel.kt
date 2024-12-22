/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.ui.viewmodel

import android.content.res.Resources
import com.android.systemui.contextualeducation.GestureType.ALL_APPS
import com.android.systemui.contextualeducation.GestureType.BACK
import com.android.systemui.contextualeducation.GestureType.HOME
import com.android.systemui.contextualeducation.GestureType.OVERVIEW
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.education.domain.interactor.KeyboardTouchpadEduInteractor
import com.android.systemui.education.shared.model.EducationInfo
import com.android.systemui.education.shared.model.EducationUiType
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

@SysUISingleton
class ContextualEduViewModel
@Inject
constructor(@Main private val resources: Resources, interactor: KeyboardTouchpadEduInteractor) {
    val eduContent: Flow<ContextualEduContentViewModel> =
        interactor.educationTriggered.filterNotNull().map {
            if (it.educationUiType == EducationUiType.Notification) {
                ContextualEduNotificationViewModel(getEduTitle(it), getEduContent(it), it.userId)
            } else {
                ContextualEduToastViewModel(getEduContent(it), it.userId)
            }
        }

    private fun getEduContent(educationInfo: EducationInfo): String {
        val resourceId =
            if (educationInfo.educationUiType == EducationUiType.Notification) {
                when (educationInfo.gestureType) {
                    BACK -> R.string.back_edu_notification_content
                    HOME -> R.string.home_edu_notification_content
                    OVERVIEW -> R.string.overview_edu_notification_content
                    ALL_APPS -> R.string.all_apps_edu_notification_content
                }
            } else {
                when (educationInfo.gestureType) {
                    BACK -> R.string.back_edu_toast_content
                    HOME -> R.string.home_edu_toast_content
                    OVERVIEW -> R.string.overview_edu_toast_content
                    ALL_APPS -> R.string.all_apps_edu_toast_content
                }
            }

        return resources.getString(resourceId)
    }

    private fun getEduTitle(educationInfo: EducationInfo): String {
        val resourceId =
            when (educationInfo.gestureType) {
                BACK -> R.string.back_edu_notification_title
                HOME -> R.string.home_edu_notification_title
                OVERVIEW -> R.string.overview_edu_notification_title
                ALL_APPS -> R.string.all_apps_edu_notification_title
            }

        return resources.getString(resourceId)
    }
}
