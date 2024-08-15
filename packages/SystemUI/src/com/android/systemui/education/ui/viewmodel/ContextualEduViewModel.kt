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
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.education.domain.interactor.KeyboardTouchpadEduInteractor
import com.android.systemui.education.shared.model.EducationInfo
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
            ContextualEduContentViewModel(getEduContent(it), it.educationUiType)
        }

    private fun getEduContent(educationInfo: EducationInfo): String {
        // Todo: also check UiType in educationInfo to determine the string
        val resourceId =
            when (educationInfo.gestureType) {
                GestureType.BACK -> R.string.back_edu_toast_content
                GestureType.HOME -> R.string.home_edu_toast_content
                GestureType.OVERVIEW -> R.string.overview_edu_toast_content
                GestureType.ALL_APPS -> R.string.all_apps_edu_toast_content
            }
        return resources.getString(resourceId)
    }
}
