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
 * limitations under the License
 */
package com.android.systemui.usb

import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.IUsbSerialReader
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.testing.AndroidTestingRunner
import android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS
import androidx.test.filters.SmallTest
import androidx.test.rule.ActivityTestRule
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.Exception

/**
 * UsbPermissionActivityTest
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
class UsbPermissionActivityTest : SysuiTestCase() {

    class UsbPermissionActivityTestable : UsbPermissionActivity()

    @Rule
    @JvmField
    var activityRule = ActivityTestRule<UsbPermissionActivityTestable>(
            UsbPermissionActivityTestable::class.java, false, false)

    private val activityIntent = Intent(mContext, UsbPermissionActivityTestable::class.java)
            .apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(UsbManager.EXTRA_PACKAGE, "com.android.systemui")
                putExtra(Intent.EXTRA_INTENT, PendingIntent.getBroadcast(
                        mContext,
                        334,
                        Intent("NO_ACTION"),
                        PendingIntent.FLAG_MUTABLE))
                putExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory(
                        "manufacturer",
                        "model",
                        "description",
                        "version",
                        "uri",
                        object : IUsbSerialReader.Stub() {
                            override fun getSerial(packageName: String): String {
                                return "serial"
                            }
                        }))
            }

    @Before
    fun setUp() {
        activityRule.launchActivity(activityIntent)
    }

    @After
    fun tearDown() {
        activityRule.finishActivity()
    }

    @Test
    @Throws(Exception::class)
    fun testHideNonSystemOverlay() {
        assertThat(activityRule.activity.window.attributes.privateFlags and
                SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS)
                .isEqualTo(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS)
    }
}
