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

import android.provider.DeviceConfig
import android.provider.Settings
import android.provider.Settings.Secure.NOTIFICATION_NEW_INTERRUPTION_MODEL
import android.testing.AndroidTestingRunner

import androidx.test.filters.SmallTest

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags.NOTIFICATIONS_USE_PEOPLE_FILTERING
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.DeviceConfigProxyFake

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class NotificationSectionsFeatureManagerTest : SysuiTestCase() {
    var manager: NotificationSectionsFeatureManager? = null
    val proxyFake = DeviceConfigProxyFake()
    var originalQsMediaPlayer: Int = 0

    @Before
    public fun setup() {
        Settings.Secure.putInt(mContext.getContentResolver(),
        NOTIFICATION_NEW_INTERRUPTION_MODEL, 1)
        manager = NotificationSectionsFeatureManager(proxyFake, mContext)
        manager!!.clearCache()
        originalQsMediaPlayer = Settings.System.getInt(context.getContentResolver(),
                "qs_media_player", 1)
        Settings.Global.putInt(context.getContentResolver(), "qs_media_player", 0)
    }

    @After
    public fun teardown() {
        Settings.Global.putInt(context.getContentResolver(), "qs_media_player",
                originalQsMediaPlayer)
    }

    @Test
    public fun testPeopleFilteringOff_newInterruptionModelOn() {
        proxyFake.setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI, NOTIFICATIONS_USE_PEOPLE_FILTERING, "false", false)

        assertFalse("People filtering should be disabled", manager!!.isFilteringEnabled())
        assertTrue("Expecting 2 buckets when people filtering is disabled",
                manager!!.getNumberOfBuckets() == 2)
    }

    @Test
    public fun testPeopleFilteringOn_newInterruptionModelOn() {
        proxyFake.setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI, NOTIFICATIONS_USE_PEOPLE_FILTERING, "true", false)

        assertTrue("People filtering should be enabled", manager!!.isFilteringEnabled())
        assertTrue("Expecting 5 buckets when people filtering is enabled",
                manager!!.getNumberOfBuckets() == 5)
    }
}
