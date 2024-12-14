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

package com.android.settingslib.bluetooth.devicesettings

import android.os.Bundle
import android.os.Parcel
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeviceSettingsConfigTest {

    @Test
    fun parcelOperation() {
        val config =
            DeviceSettingsConfig(
                mainContentItems =
                    listOf(
                        DeviceSettingItem(
                            settingId = 1,
                            packageName = "package_name_1",
                            className = "class_name_1",
                            intentAction = "intent_action_1",
                            preferenceKey = null,
                            highlighted = false,
                            extras = Bundle(),
                        )),
                moreSettingsItems =
                    listOf(
                        DeviceSettingItem(
                            settingId = 2,
                            packageName = "package_name_2",
                            className = "class_name_2",
                            intentAction = "intent_action_2",
                            preferenceKey = null,
                            highlighted = false,
                            extras = Bundle(),
                        )),
                moreSettingsHelpItem = DeviceSettingItem(
                    settingId = 3,
                    packageName = "package_name_2",
                    className = "class_name_2",
                    intentAction = "intent_action_2",
                    preferenceKey = null,
                    highlighted = false,
                    extras = Bundle(),
                ),
                extras = Bundle().apply { putString("key1", "value1") },
            )

        val fromParcel = writeAndRead(config)

        assertThat(fromParcel.mainContentItems.stream().map { it.settingId }.toList())
            .containsExactly(1)
        assertThat(fromParcel.mainContentItems.stream().map { it.packageName }.toList())
            .containsExactly("package_name_1")
        assertThat(fromParcel.mainContentItems.stream().map { it.className }.toList())
            .containsExactly("class_name_1")
        assertThat(fromParcel.mainContentItems.stream().map { it.intentAction }.toList())
            .containsExactly("intent_action_1")
        assertThat(fromParcel.moreSettingsItems.stream().map { it.settingId }.toList())
            .containsExactly(2)
        assertThat(fromParcel.moreSettingsItems.stream().map { it.packageName }.toList())
            .containsExactly("package_name_2")
        assertThat(fromParcel.moreSettingsItems.stream().map { it.className }.toList())
            .containsExactly("class_name_2")
        assertThat(fromParcel.moreSettingsItems.stream().map { it.intentAction }.toList())
            .containsExactly("intent_action_2")
        assertThat(fromParcel.moreSettingsHelpItem?.settingId).isEqualTo(3)
        assertThat(fromParcel.moreSettingsHelpItem?.packageName).isEqualTo("package_name_2")
        assertThat(fromParcel.moreSettingsHelpItem?.className).isEqualTo("class_name_2")
        assertThat(fromParcel.moreSettingsHelpItem?.intentAction).isEqualTo("intent_action_2")
    }

    private fun writeAndRead(item: DeviceSettingsConfig): DeviceSettingsConfig {
        val parcel = Parcel.obtain()
        item.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        return DeviceSettingsConfig.CREATOR.createFromParcel(parcel)
    }
}
