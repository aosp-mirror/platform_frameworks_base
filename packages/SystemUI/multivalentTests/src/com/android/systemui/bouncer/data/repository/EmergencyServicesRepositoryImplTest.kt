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

package com.android.systemui.bouncer.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class EmergencyServicesRepositoryImplTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope

    private lateinit var underTest: EmergencyServicesRepository

    @Before
    fun setUp() {
        overrideResource(
            R.bool.config_enable_emergency_call_while_sim_locked,
            ENABLE_EMERGENCY_CALL_WHILE_SIM_LOCKED
        )

        underTest =
            EmergencyServicesRepository(
                resources = context.resources,
                applicationScope = testScope.backgroundScope,
                configurationRepository = utils.configurationRepository,
            )
    }

    @Test
    fun enableEmergencyCallWhileSimLocked() =
        testScope.runTest {
            val enableEmergencyCallWhileSimLocked by
                collectLastValue(underTest.enableEmergencyCallWhileSimLocked)

            setEmergencyCallWhileSimLocked(isEnabled = false)
            assertThat(enableEmergencyCallWhileSimLocked).isFalse()

            setEmergencyCallWhileSimLocked(isEnabled = true)
            assertThat(enableEmergencyCallWhileSimLocked).isTrue()
        }

    private fun TestScope.setEmergencyCallWhileSimLocked(isEnabled: Boolean) {
        overrideResource(R.bool.config_enable_emergency_call_while_sim_locked, isEnabled)
        utils.configurationRepository.onConfigurationChange()
        runCurrent()
    }

    companion object {
        private const val ENABLE_EMERGENCY_CALL_WHILE_SIM_LOCKED = true
    }
}
