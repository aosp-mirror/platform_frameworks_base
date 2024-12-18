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

package com.android.systemui.brightness.domain.interactor

import com.android.settingslib.RestrictedLockUtils
import com.android.systemui.brightness.data.repository.BrightnessPolicyRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.utils.PolicyRestriction
import javax.inject.Inject

/** Interactor for enforcing the policy that may disallow brightness changing. */
@SysUISingleton
class BrightnessPolicyEnforcementInteractor
@Inject
constructor(
    brightnessPolicyRepository: BrightnessPolicyRepository,
    private val activityStarter: ActivityStarter,
) {

    /** Brightness policy restriction for the current user. */
    val brightnessPolicyRestriction = brightnessPolicyRepository.restrictionPolicy

    /**
     * Starts the dialog with details about the current restriction for changing brightness. Should
     * be triggered when a restricted user tries to change the brightness.
     */
    fun startAdminSupportDetailsDialog(restriction: PolicyRestriction.Restricted) {
        val intent = RestrictedLockUtils.getShowAdminSupportDetailsIntent(restriction.admin)
        activityStarter.postStartActivityDismissingKeyguard(intent, 0)
    }
}
