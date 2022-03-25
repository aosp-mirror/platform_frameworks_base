/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.battery

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.battery.BatteryMeterView.BatteryEstimateFetcher
import com.android.systemui.statusbar.policy.BatteryController.EstimateFetchCompletion
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class BatteryMeterViewTest : SysuiTestCase() {

    private lateinit var mBatteryMeterView: BatteryMeterView

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mBatteryMeterView = BatteryMeterView(mContext, null)
    }

    @Test
    fun updatePercentText_estimateModeAndNotCharging_estimateFetched() {
        mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)
        mBatteryMeterView.setBatteryEstimateFetcher(Fetcher())

        mBatteryMeterView.updatePercentText()

        assertThat(mBatteryMeterView.batteryPercentViewText).isEqualTo(ESTIMATE)
    }

    @Test
    fun updatePercentText_noBatteryEstimateFetcher_noCrash() {
        mBatteryMeterView.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE)

        mBatteryMeterView.updatePercentText()
        // No assert needed
    }

    private class Fetcher : BatteryEstimateFetcher {
        override fun fetchBatteryTimeRemainingEstimate(
                completion: EstimateFetchCompletion) {
            completion.onBatteryRemainingEstimateRetrieved(ESTIMATE)
        }
    }

    private companion object {
        const val ESTIMATE = "2 hours 2 minutes"
    }
}