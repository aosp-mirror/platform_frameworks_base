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

package com.android.systemui.statusbar.pipeline.mobile.data.model

import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import android.telephony.CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL
import android.telephony.CarrierConfigManager.KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class SystemUiCarrierConfigTest : SysuiTestCase() {

    lateinit var underTest: SystemUiCarrierConfig

    @Before
    fun setUp() {
        underTest = SystemUiCarrierConfig(SUB_1_ID, createTestConfig())
    }

    @Test
    fun `process new config - reflected by isUsingDefault`() {
        // Starts out using the defaults
        assertThat(underTest.isUsingDefault).isTrue()

        // ANY new config means we're no longer tracking defaults
        underTest.processNewCarrierConfig(createTestConfig())

        assertThat(underTest.isUsingDefault).isFalse()
    }

    @Test
    fun `process new config - updates all flows`() {
        assertThat(underTest.shouldInflateSignalStrength.value).isFalse()
        assertThat(underTest.showOperatorNameInStatusBar.value).isFalse()

        underTest.processNewCarrierConfig(
            configWithOverrides(
                KEY_INFLATE_SIGNAL_STRENGTH_BOOL to true,
                KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL to true,
            )
        )

        assertThat(underTest.shouldInflateSignalStrength.value).isTrue()
        assertThat(underTest.showOperatorNameInStatusBar.value).isTrue()
    }

    @Test
    fun `process new config - defaults to false for config overrides`() {
        // This case is only apparent when:
        //   1. The default is true
        //   2. The override config has no value for a given key
        // In this case (per the old code) we would use the default value of false, despite there
        // being no override key present in the override config

        underTest =
            SystemUiCarrierConfig(
                SUB_1_ID,
                configWithOverrides(
                    KEY_INFLATE_SIGNAL_STRENGTH_BOOL to true,
                    KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL to true,
                )
            )

        assertThat(underTest.isUsingDefault).isTrue()
        assertThat(underTest.shouldInflateSignalStrength.value).isTrue()
        assertThat(underTest.showOperatorNameInStatusBar.value).isTrue()

        // Process a new config with no keys
        underTest.processNewCarrierConfig(PersistableBundle())

        assertThat(underTest.isUsingDefault).isFalse()
        assertThat(underTest.shouldInflateSignalStrength.value).isFalse()
        assertThat(underTest.showOperatorNameInStatusBar.value).isFalse()
    }

    companion object {
        private const val SUB_1_ID = 1

        /**
         * In order to keep us from having to update every place that might want to create a config,
         * make sure to add new keys here
         */
        fun createTestConfig() =
            PersistableBundle().also {
                it.putBoolean(CarrierConfigManager.KEY_INFLATE_SIGNAL_STRENGTH_BOOL, false)
                it.putBoolean(CarrierConfigManager.KEY_SHOW_OPERATOR_NAME_IN_STATUSBAR_BOOL, false)
            }

        /** Override the default config with the given (key, value) pair */
        fun configWithOverride(key: String, override: Boolean): PersistableBundle =
            createTestConfig().also { it.putBoolean(key, override) }

        /** Override any number of configs from the default */
        fun configWithOverrides(vararg overrides: Pair<String, Boolean>) =
            createTestConfig().also { config ->
                overrides.forEach { (key, value) -> config.putBoolean(key, value) }
            }
    }
}
