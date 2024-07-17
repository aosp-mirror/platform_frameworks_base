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

package com.android.systemui.statusbar.pipeline.shared.data.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.LogMessageImpl
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DefaultConnectionModelTest : SysuiTestCase() {
    @Test
    fun messageInitializerAndPrinter_isValidatedFalse_hasCorrectInfo() {
        val model =
            DefaultConnectionModel(
                DefaultConnectionModel.Wifi(isDefault = false),
                DefaultConnectionModel.Mobile(isDefault = true),
                DefaultConnectionModel.CarrierMerged(isDefault = true),
                DefaultConnectionModel.Ethernet(isDefault = false),
                isValidated = false,
            )
        val message = LogMessageImpl.create()

        model.messageInitializer(message)
        val messageString = model.messagePrinter(message)

        assertThat(messageString).contains("wifi.isDefault=false")
        assertThat(messageString).contains("mobile.isDefault=true")
        assertThat(messageString).contains("carrierMerged.isDefault=true")
        assertThat(messageString).contains("ethernet.isDefault=false")
        assertThat(messageString).contains("isValidated=false")
    }

    @Test
    fun messageInitializerAndPrinter_isValidatedTrue_hasCorrectInfo() {
        val model =
            DefaultConnectionModel(
                DefaultConnectionModel.Wifi(isDefault = true),
                DefaultConnectionModel.Mobile(isDefault = false),
                DefaultConnectionModel.CarrierMerged(isDefault = false),
                DefaultConnectionModel.Ethernet(isDefault = false),
                isValidated = true,
            )
        val message = LogMessageImpl.create()

        model.messageInitializer(message)
        val messageString = model.messagePrinter(message)

        assertThat(messageString).contains("wifi.isDefault=true")
        assertThat(messageString).contains("mobile.isDefault=false")
        assertThat(messageString).contains("carrierMerged.isDefault=false")
        assertThat(messageString).contains("ethernet.isDefault=false")
        assertThat(messageString).contains("isValidated=true")
    }
}
