/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import android.content.Intent
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfigTest.Companion.createTestConfig
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.MockitoSession
import org.mockito.quality.Strictness

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CarrierConfigRepositoryTest : SysuiTestCase() {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: CarrierConfigRepository
    private lateinit var mockitoSession: MockitoSession
    private lateinit var carrierConfigCoreStartable: CarrierConfigCoreStartable

    @Mock private lateinit var logger: MobileInputLogger
    @Mock private lateinit var carrierConfigManager: CarrierConfigManager
    @Mock private lateinit var dumpManager: DumpManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mockitoSession =
            mockitoSession()
                .initMocks(this)
                .mockStatic(CarrierConfigManager::class.java)
                .strictness(Strictness.LENIENT)
                .startMocking()

        whenever(CarrierConfigManager.getDefaultConfig()).thenReturn(DEFAULT_CONFIG)

        whenever(carrierConfigManager.getConfigForSubId(anyInt())).thenAnswer { invocation ->
            when (invocation.getArgument(0) as Int) {
                1 -> CONFIG_1
                2 -> CONFIG_2
                else -> null
            }
        }

        underTest =
            CarrierConfigRepository(
                fakeBroadcastDispatcher,
                carrierConfigManager,
                dumpManager,
                logger,
                testScope.backgroundScope,
            )

        carrierConfigCoreStartable =
            CarrierConfigCoreStartable(underTest, testScope.backgroundScope)
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    @Test
    fun carrierConfigStreamProducesIntBundlePairs() =
        testScope.runTest {
            var latest: Pair<Int, PersistableBundle>? = null
            val job = underTest.carrierConfigStream.onEach { latest = it }.launchIn(this)

            sendConfig(SUB_ID_1)
            assertThat(latest).isEqualTo(Pair(SUB_ID_1, CONFIG_1))

            sendConfig(SUB_ID_2)
            assertThat(latest).isEqualTo(Pair(SUB_ID_2, CONFIG_2))

            job.cancel()
        }

    @Test
    fun carrierConfigStreamIgnoresInvalidSubscriptions() =
        testScope.runTest {
            var latest: Pair<Int, PersistableBundle>? = null
            val job = underTest.carrierConfigStream.onEach { latest = it }.launchIn(this)

            sendConfig(INVALID_SUBSCRIPTION_ID)

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun getOrCreateConfig_usesDefaultConfigIfNoOverride() {
        val config = underTest.getOrCreateConfigForSubId(123)
        assertThat(config.isUsingDefault).isTrue()
    }

    @Test
    fun getOrCreateConfig_usesOverrideIfExists() {
        val config = underTest.getOrCreateConfigForSubId(SUB_ID_1)
        assertThat(config.isUsingDefault).isFalse()
    }

    @Test
    fun config_updatesWhileConfigStreamIsCollected() =
        testScope.runTest {
            CONFIG_1.putBoolean(CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL, false)

            carrierConfigCoreStartable.start()

            val config = underTest.getOrCreateConfigForSubId(SUB_ID_1)
            assertThat(config.shouldInflateSignalStrength.value).isFalse()

            CONFIG_1.putBoolean(CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL, true)
            sendConfig(SUB_ID_1)

            assertThat(config.shouldInflateSignalStrength.value).isTrue()
        }

    private fun sendConfig(subId: Int) {
        fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED)
                .putExtra(CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX, subId),
        )
    }

    companion object {
        private const val SUB_ID_1 = 1
        private const val SUB_ID_2 = 2

        private val DEFAULT_CONFIG = createTestConfig()
        private val CONFIG_1 = createTestConfig()
        private val CONFIG_2 = createTestConfig()
    }
}
