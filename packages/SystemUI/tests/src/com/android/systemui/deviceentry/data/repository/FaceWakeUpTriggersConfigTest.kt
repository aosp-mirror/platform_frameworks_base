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

package com.android.systemui.deviceentry.data.repository

import android.os.PowerManager
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.settings.GlobalSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class FaceWakeUpTriggersConfigTest : SysuiTestCase() {
    @Mock lateinit var globalSettings: GlobalSettings
    @Mock lateinit var dumpManager: DumpManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testShouldTriggerFaceAuthOnWakeUpFrom_inConfig_returnsTrue() {
        val faceWakeUpTriggersConfig =
            createFaceWakeUpTriggersConfig(
                intArrayOf(PowerManager.WAKE_REASON_POWER_BUTTON, PowerManager.WAKE_REASON_GESTURE)
            )

        assertTrue(
            faceWakeUpTriggersConfig.shouldTriggerFaceAuthOnWakeUpFrom(
                PowerManager.WAKE_REASON_POWER_BUTTON
            )
        )
        assertTrue(
            faceWakeUpTriggersConfig.shouldTriggerFaceAuthOnWakeUpFrom(
                PowerManager.WAKE_REASON_GESTURE
            )
        )
        assertFalse(
            faceWakeUpTriggersConfig.shouldTriggerFaceAuthOnWakeUpFrom(
                PowerManager.WAKE_REASON_APPLICATION
            )
        )
    }

    private fun createFaceWakeUpTriggersConfig(wakeUpTriggers: IntArray): FaceWakeUpTriggersConfig {
        overrideResource(
            com.android.systemui.res.R.array.config_face_auth_wake_up_triggers,
            wakeUpTriggers
        )

        return FaceWakeUpTriggersConfigImpl(mContext.resources, globalSettings, dumpManager)
    }
}
