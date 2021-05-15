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

package com.android.server.connectivity

import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkScore.KEEP_CONNECTED_NONE
import android.net.NetworkScore.POLICY_EXITING
import android.net.NetworkScore.POLICY_TRANSPORT_PRIMARY
import android.net.NetworkScore.POLICY_YIELD_TO_BAD_WIFI
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.server.connectivity.FullScore.POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD
import com.android.server.connectivity.FullScore.POLICY_IS_VALIDATED
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

private fun score(vararg policies: Int) = FullScore(0,
        policies.fold(0L) { acc, e -> acc or (1L shl e) }, KEEP_CONNECTED_NONE)
private fun caps(transport: Int) = NetworkCapabilities.Builder().addTransportType(transport).build()

@SmallTest
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class NetworkRankerTest {
    private val mRanker = NetworkRanker()

    private class TestScore(private val sc: FullScore, private val nc: NetworkCapabilities)
            : NetworkRanker.Scoreable {
        override fun getScore() = sc
        override fun getCapsNoCopy(): NetworkCapabilities = nc
    }

    @Test
    fun testYieldToBadWiFiOneCell() {
        // Only cell, it wins
        val winner = TestScore(score(POLICY_YIELD_TO_BAD_WIFI, POLICY_IS_VALIDATED),
                caps(TRANSPORT_CELLULAR))
        val scores = listOf(winner)
        assertEquals(winner, mRanker.getBestNetworkByPolicy(scores, null))
    }

    @Test
    fun testYieldToBadWiFiOneCellOneBadWiFi() {
        // Bad wifi wins against yielding validated cell
        val winner = TestScore(score(POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD),
                caps(TRANSPORT_WIFI))
        val scores = listOf(
                winner,
                TestScore(score(POLICY_YIELD_TO_BAD_WIFI, POLICY_IS_VALIDATED),
                        caps(TRANSPORT_CELLULAR))
        )
        assertEquals(winner, mRanker.getBestNetworkByPolicy(scores, null))
    }

    @Test
    fun testYieldToBadWiFiOneCellTwoBadWiFi() {
        // Bad wifi wins against yielding validated cell. Prefer the one that's primary.
        val winner = TestScore(score(POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD,
                POLICY_TRANSPORT_PRIMARY), caps(TRANSPORT_WIFI))
        val scores = listOf(
                winner,
                TestScore(score(POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD),
                        caps(TRANSPORT_WIFI)),
                TestScore(score(POLICY_YIELD_TO_BAD_WIFI, POLICY_IS_VALIDATED),
                        caps(TRANSPORT_CELLULAR))
        )
        assertEquals(winner, mRanker.getBestNetworkByPolicy(scores, null))
    }

    @Test
    fun testYieldToBadWiFiOneCellTwoBadWiFiOneNotAvoided() {
        // Bad wifi ever validated wins against bad wifi that never was validated (or was
        // avoided when bad).
        val winner = TestScore(score(POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD),
                caps(TRANSPORT_WIFI))
        val scores = listOf(
                winner,
                TestScore(score(), caps(TRANSPORT_WIFI)),
                TestScore(score(POLICY_YIELD_TO_BAD_WIFI, POLICY_IS_VALIDATED),
                        caps(TRANSPORT_CELLULAR))
        )
        assertEquals(winner, mRanker.getBestNetworkByPolicy(scores, null))
    }

    @Test
    fun testYieldToBadWiFiOneCellOneBadWiFiOneGoodWiFi() {
        // Good wifi wins
        val winner = TestScore(score(POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD,
                POLICY_IS_VALIDATED), caps(TRANSPORT_WIFI))
        val scores = listOf(
                winner,
                TestScore(score(POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD,
                        POLICY_TRANSPORT_PRIMARY), caps(TRANSPORT_WIFI)),
                TestScore(score(POLICY_YIELD_TO_BAD_WIFI, POLICY_IS_VALIDATED),
                        caps(TRANSPORT_CELLULAR))
        )
        assertEquals(winner, mRanker.getBestNetworkByPolicy(scores, null))
    }

    @Test
    fun testYieldToBadWiFiTwoCellsOneBadWiFi() {
        // Cell that doesn't yield wins over cell that yields and bad wifi
        val winner = TestScore(score(POLICY_IS_VALIDATED), caps(TRANSPORT_CELLULAR))
        val scores = listOf(
                winner,
                TestScore(score(POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD,
                        POLICY_TRANSPORT_PRIMARY), caps(TRANSPORT_WIFI)),
                TestScore(score(POLICY_YIELD_TO_BAD_WIFI, POLICY_IS_VALIDATED),
                        caps(TRANSPORT_CELLULAR))
        )
        assertEquals(winner, mRanker.getBestNetworkByPolicy(scores, null))
    }

    @Test
    fun testYieldToBadWiFiTwoCellsOneBadWiFiOneGoodWiFi() {
        // Good wifi wins over cell that doesn't yield and cell that yields
        val winner = TestScore(score(POLICY_IS_VALIDATED), caps(TRANSPORT_WIFI))
        val scores = listOf(
                winner,
                TestScore(score(POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD,
                        POLICY_TRANSPORT_PRIMARY), caps(TRANSPORT_WIFI)),
                TestScore(score(POLICY_IS_VALIDATED), caps(TRANSPORT_CELLULAR)),
                TestScore(score(POLICY_YIELD_TO_BAD_WIFI, POLICY_IS_VALIDATED),
                        caps(TRANSPORT_CELLULAR))
        )
        assertEquals(winner, mRanker.getBestNetworkByPolicy(scores, null))
    }

    @Test
    fun testYieldToBadWiFiOneExitingGoodWiFi() {
        // Yielding cell wins over good exiting wifi
        val winner = TestScore(score(POLICY_YIELD_TO_BAD_WIFI, POLICY_IS_VALIDATED),
                caps(TRANSPORT_CELLULAR))
        val scores = listOf(
                winner,
                TestScore(score(POLICY_IS_VALIDATED, POLICY_EXITING), caps(TRANSPORT_WIFI))
        )
        assertEquals(winner, mRanker.getBestNetworkByPolicy(scores, null))
    }

    @Test
    fun testYieldToBadWiFiOneExitingBadWiFi() {
        // Yielding cell wins over bad exiting wifi
        val winner = TestScore(score(POLICY_YIELD_TO_BAD_WIFI, POLICY_IS_VALIDATED),
                caps(TRANSPORT_CELLULAR))
        val scores = listOf(
                winner,
                TestScore(score(POLICY_EVER_VALIDATED_NOT_AVOIDED_WHEN_BAD,
                        POLICY_EXITING), caps(TRANSPORT_WIFI))
        )
        assertEquals(winner, mRanker.getBestNetworkByPolicy(scores, null))
    }
}
