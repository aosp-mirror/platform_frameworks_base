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

package com.android.systemui.bouncer.domain.interactor

import android.content.Intent
import android.content.applicationContext
import com.android.app.activityTaskManager
import com.android.internal.logging.metricsLogger
import com.android.internal.util.emergencyAffordanceManager
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.bouncer.data.repository.emergencyServicesRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.repository.mobileConnectionsRepository
import com.android.systemui.telephony.domain.interactor.telephonyInteractor
import com.android.systemui.user.domain.interactor.selectedUserInteractor
import com.android.systemui.util.mockito.mock
import com.android.telecom.telecomManager

val Kosmos.bouncerActionButtonInteractor by Fixture {
    BouncerActionButtonInteractor(
        applicationContext = applicationContext,
        backgroundDispatcher = testDispatcher,
        repository = emergencyServicesRepository,
        mobileConnectionsRepository = mobileConnectionsRepository,
        telephonyInteractor = telephonyInteractor,
        authenticationInteractor = authenticationInteractor,
        selectedUserInteractor = selectedUserInteractor,
        activityTaskManager = activityTaskManager,
        telecomManager = telecomManager,
        emergencyAffordanceManager = emergencyAffordanceManager,
        emergencyDialerIntentFactory =
            object : EmergencyDialerIntentFactory {
                override fun invoke(): Intent = Intent()
            },
        metricsLogger = metricsLogger,
        dozeLogger = mock(),
        sceneInteractor = { sceneInteractor },
    )
}
