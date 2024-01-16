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

import android.content.Context
import android.content.applicationContext
import android.content.res.mainResources
import android.telephony.euicc.EuiccManager
import android.telephony.telephonyManager
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.bouncer.data.repository.simBouncerRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.pipeline.mobile.data.repository.mobileConnectionsRepository

val Kosmos.simBouncerInteractor by Fixture {
    SimBouncerInteractor(
        applicationContext = applicationContext,
        backgroundDispatcher = testDispatcher,
        applicationScope = testScope.backgroundScope,
        repository = simBouncerRepository,
        telephonyManager = telephonyManager,
        resources = mainResources,
        keyguardUpdateMonitor = keyguardUpdateMonitor,
        euiccManager = applicationContext.getSystemService(Context.EUICC_SERVICE) as EuiccManager,
        mobileConnectionsRepository = mobileConnectionsRepository,
    )
}
