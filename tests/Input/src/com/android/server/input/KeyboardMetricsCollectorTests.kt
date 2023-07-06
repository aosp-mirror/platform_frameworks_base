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

package com.android.server.input

import android.hardware.input.KeyboardLayout
import android.icu.util.ULocale
import android.platform.test.annotations.Presubmit
import android.view.InputDevice
import android.view.inputmethod.InputMethodSubtype
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private fun createKeyboard(
    deviceId: Int,
    vendorId: Int,
    productId: Int,
    languageTag: String?,
    layoutType: String?
): InputDevice =
    InputDevice.Builder()
        .setId(deviceId)
        .setName("Device $deviceId")
        .setDescriptor("descriptor $deviceId")
        .setSources(InputDevice.SOURCE_KEYBOARD)
        .setKeyboardType(InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        .setExternal(true)
        .setVendorId(vendorId)
        .setProductId(productId)
        .setKeyboardLanguageTag(languageTag)
        .setKeyboardLayoutType(layoutType)
        .build()

private fun createImeSubtype(
    imeSubtypeId: Int,
    languageTag: String,
    layoutType: String
): InputMethodSubtype =
    InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeId(imeSubtypeId)
        .setPhysicalKeyboardHint(ULocale.forLanguageTag(languageTag), layoutType).build()

/**
 * Tests for {@link KeyboardMetricsCollector}.
 *
 * Build/Install/Run:
 * atest InputTests:KeyboardMetricsCollectorTests
 */
@Presubmit
class KeyboardMetricsCollectorTests {

    companion object {
        const val DEVICE_ID = 1
        const val DEFAULT_VENDOR_ID = 123
        const val DEFAULT_PRODUCT_ID = 456
    }

    @Test
    fun testCreateKeyboardConfigurationEvent_throwsExceptionWithoutAnyLayoutConfiguration() {
        assertThrows(IllegalStateException::class.java) {
            KeyboardMetricsCollector.KeyboardConfigurationEvent.Builder(
                createKeyboard(
                    DEVICE_ID,
                    DEFAULT_VENDOR_ID,
                    DEFAULT_PRODUCT_ID,
                    null,
                    null
                )
            ).build()
        }
    }

    @Test
    fun testCreateKeyboardConfigurationEvent_throwsExceptionWithInvalidLayoutSelectionCriteria() {
        assertThrows(IllegalStateException::class.java) {
            KeyboardMetricsCollector.KeyboardConfigurationEvent.Builder(
                createKeyboard(
                    DEVICE_ID,
                    DEFAULT_VENDOR_ID,
                    DEFAULT_PRODUCT_ID,
                    null,
                    null
                )
            ).addLayoutSelection(createImeSubtype(1, "en-US", "qwerty"), null, 123).build()
        }
    }

    @Test
    fun testCreateKeyboardConfigurationEvent_withMultipleConfigurations() {
        val builder = KeyboardMetricsCollector.KeyboardConfigurationEvent.Builder(
            createKeyboard(
                DEVICE_ID,
                DEFAULT_VENDOR_ID,
                DEFAULT_PRODUCT_ID,
                "de-CH",
                "qwertz"
            )
        )
        val event = builder.addLayoutSelection(
            createImeSubtype(1, "en-US", "qwerty"),
            KeyboardLayout(null, "English(US)(Qwerty)", null, 0, null, 0, 0, 0),
            KeyboardMetricsCollector.LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD
        ).addLayoutSelection(
            createImeSubtype(2, "en-US", "azerty"),
            null,
            KeyboardMetricsCollector.LAYOUT_SELECTION_CRITERIA_USER
        ).addLayoutSelection(
            createImeSubtype(3, "en-US", "qwerty"),
            KeyboardLayout(null, "German", null, 0, null, 0, 0, 0),
            KeyboardMetricsCollector.LAYOUT_SELECTION_CRITERIA_DEVICE
        ).setIsFirstTimeConfiguration(true).build()

        assertEquals(
            "KeyboardConfigurationEvent should pick vendor ID from provided InputDevice",
            DEFAULT_VENDOR_ID,
            event.vendorId
        )
        assertEquals(
            "KeyboardConfigurationEvent should pick product ID from provided InputDevice",
            DEFAULT_PRODUCT_ID,
            event.productId
        )
        assertTrue(event.isFirstConfiguration)

        assertEquals(
            "KeyboardConfigurationEvent should contain 3 configurations provided",
            3,
            event.layoutConfigurations.size
        )
        assertExpectedLayoutConfiguration(
            event.layoutConfigurations[0],
            "en-US",
            KeyboardLayout.LayoutType.getLayoutTypeEnumValue("qwerty"),
            "English(US)(Qwerty)",
            KeyboardMetricsCollector.LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD
        )
        assertExpectedLayoutConfiguration(
            event.layoutConfigurations[1],
            "en-US",
            KeyboardLayout.LayoutType.getLayoutTypeEnumValue("azerty"),
            KeyboardMetricsCollector.DEFAULT_LAYOUT,
            KeyboardMetricsCollector.LAYOUT_SELECTION_CRITERIA_USER
        )
        assertExpectedLayoutConfiguration(
            event.layoutConfigurations[2],
            "de-CH",
            KeyboardLayout.LayoutType.getLayoutTypeEnumValue("qwertz"),
            "German",
            KeyboardMetricsCollector.LAYOUT_SELECTION_CRITERIA_DEVICE
        )
    }

    private fun assertExpectedLayoutConfiguration(
        configuration: KeyboardMetricsCollector.LayoutConfiguration,
        expectedLanguageTag: String,
        expectedLayoutType: Int,
        expectedSelectedLayout: String,
        expectedLayoutSelectionCriteria: Int
    ) {
        assertEquals(expectedLanguageTag, configuration.keyboardLanguageTag)
        assertEquals(expectedLayoutType, configuration.keyboardLayoutType)
        assertEquals(expectedSelectedLayout, configuration.keyboardLayoutName)
        assertEquals(expectedLayoutSelectionCriteria, configuration.layoutSelectionCriteria)
    }
}