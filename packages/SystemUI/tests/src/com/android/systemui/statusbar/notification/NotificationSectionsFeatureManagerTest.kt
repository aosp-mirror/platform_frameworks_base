/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification

import android.platform.test.annotations.DisableFlags
import android.provider.DeviceConfig
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags.NOTIFICATIONS_USE_PEOPLE_FILTERING
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.shared.NotificationMinimalismPrototype
import com.android.systemui.statusbar.notification.shared.PriorityPeopleSection
import com.android.systemui.util.DeviceConfigProxyFake
import com.android.systemui.util.Utils
import com.android.systemui.util.mockito.any
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoSession
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

@RunWith(AndroidJUnit4::class)
@SmallTest
// this class has no testable logic with either of these flags enabled
@DisableFlags(PriorityPeopleSection.FLAG_NAME, NotificationMinimalismPrototype.V2.FLAG_NAME)
class NotificationSectionsFeatureManagerTest : SysuiTestCase() {
    lateinit var manager: NotificationSectionsFeatureManager
    private val proxyFake = DeviceConfigProxyFake()
    private lateinit var staticMockSession: MockitoSession

    @Before
    fun setup() {
        manager = NotificationSectionsFeatureManager(proxyFake, mContext)
        manager.clearCache()
        staticMockSession =
            ExtendedMockito.mockitoSession()
                .mockStatic(Utils::class.java)
                .strictness(Strictness.LENIENT)
                .startMocking()
        whenever(Utils.useQsMediaPlayer(any())).thenReturn(false)
        Settings.Global.putInt(
            context.getContentResolver(),
            Settings.Global.SHOW_MEDIA_ON_QUICK_SETTINGS,
            0
        )
    }

    @After
    fun teardown() {
        staticMockSession.finishMocking()
    }

    @Test
    fun testPeopleFilteringOff_newInterruptionModelOn() {
        proxyFake.setProperty(
            DeviceConfig.NAMESPACE_SYSTEMUI,
            NOTIFICATIONS_USE_PEOPLE_FILTERING,
            "false",
            false
        )

        assertFalse("People filtering should be disabled", manager.isFilteringEnabled())
        assertTrue(
            "Expecting 2 buckets when people filtering is disabled",
            manager.getNumberOfBuckets() == 2
        )
    }

    @Test
    fun testPeopleFilteringOn_newInterruptionModelOn() {
        proxyFake.setProperty(
            DeviceConfig.NAMESPACE_SYSTEMUI,
            NOTIFICATIONS_USE_PEOPLE_FILTERING,
            "true",
            false
        )

        assertTrue("People filtering should be enabled", manager.isFilteringEnabled())
        assertTrue(
            "Expecting 5 buckets when people filtering is enabled",
            manager.getNumberOfBuckets() == 5
        )
    }
}
