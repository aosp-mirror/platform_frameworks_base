/*
 * Copyright 2023 The Android Open Source Project
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
import android.content.Intent
import android.telecom.TelecomManager
import android.telephony.euicc.EuiccManager
import com.android.internal.util.EmergencyAffordanceManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import dagger.Module
import dagger.Provides

/** Module for providing interactor-related objects for the bouncer. */
@Module
object BouncerInteractorModule {

    @Provides
    fun emergencyDialerIntentFactory(
        telecomManager: TelecomManager?
    ): EmergencyDialerIntentFactory {
        return object : EmergencyDialerIntentFactory {
            override fun invoke(): Intent? {
                return telecomManager?.createLaunchEmergencyDialerIntent(/* number = */ null)
            }
        }
    }

    @Provides
    @SysUISingleton
    fun emergencyAffordanceManager(
        @Application applicationContext: Context,
    ): EmergencyAffordanceManager {
        return EmergencyAffordanceManager(applicationContext)
    }

    @Provides
    fun provideEuiccManager(@Application applicationContext: Context): EuiccManager? {
        return applicationContext.getSystemService(Context.EUICC_SERVICE) as EuiccManager?
    }
}
