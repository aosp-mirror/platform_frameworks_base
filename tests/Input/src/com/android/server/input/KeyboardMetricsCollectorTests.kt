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
import android.hardware.input.KeyboardLayoutSelectionResult
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
    deviceBus: Int,
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
        .setDeviceBus(deviceBus)
        .setKeyboardLanguageTag(languageTag)
        .setKeyboardLayoutType(layoutType)
        .build()

private fun createImeSubtype(
    imeSubtypeId: Int,
    languageTag: ULocale?,
    layoutType: String
): InputMethodSubtype =
    InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeId(imeSubtypeId)
        .setPhysicalKeyboardHint(languageTag, layoutType).build()

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
        const val DEFAULT_DEVICE_BUS = 789
    }

    @Test
    fun testCreateKeyboardConfigurationEvent_throwsExceptionWithoutAnyLayoutConfiguration() {
        assertThrows(IllegalStateException::class.java) {
            KeyboardMetricsCollector.KeyboardConfigurationEvent.Builder(
                createKeyboard(
                    DEVICE_ID,
                    DEFAULT_VENDOR_ID,
                    DEFAULT_PRODUCT_ID,
                    DEFAULT_DEVICE_BUS,
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
                    DEFAULT_DEVICE_BUS,
                    null,
                    null
                )
            ).addLayoutSelection(createImeSubtype(1, ULocale.forLanguageTag("en-US"), "qwerty"),
             null, 123).build()
        }
    }

    @Test
    fun testCreateKeyboardConfigurationEvent_withMultipleConfigurations() {
        val builder = KeyboardMetricsCollector.KeyboardConfigurationEvent.Builder(
            createKeyboard(
                DEVICE_ID,
                DEFAULT_VENDOR_ID,
                DEFAULT_PRODUCT_ID,
                DEFAULT_DEVICE_BUS,
                "de-CH",
                "qwertz"
            )
        )
        val event = builder.addLayoutSelection(
            createImeSubtype(1, ULocale.forLanguageTag("en-US"), "qwerty"),
            "English(US)(Qwerty)",
            KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD
        ).addLayoutSelection(
            createImeSubtype(2, ULocale.forLanguageTag("en-US"), "azerty"),
            null, // Default layout type
            KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_USER
        ).addLayoutSelection(
            createImeSubtype(3, ULocale.forLanguageTag("en-US"), "qwerty"),
            "German",
            KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEVICE
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
        assertEquals(
             "KeyboardConfigurationEvent should pick device bus from provided InputDevice",
             DEFAULT_DEVICE_BUS,
             event.deviceBus
        )
        assertTrue(event.isFirstConfiguration)

        assertEquals(
            "KeyboardConfigurationEvent should contain 3 configurations provided",
            3,
            event.layoutConfigurations.size
        )
        assertExpectedLayoutConfiguration(
            event.layoutConfigurations[0],
            "de-CH",
            KeyboardLayout.LayoutType.getLayoutTypeEnumValue("qwertz"),
            "English(US)(Qwerty)",
            KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD,
            "en-US",
            KeyboardLayout.LayoutType.getLayoutTypeEnumValue("qwerty"),
        )
        assertExpectedLayoutConfiguration(
            event.layoutConfigurations[1],
            "de-CH",
            KeyboardLayout.LayoutType.getLayoutTypeEnumValue("qwertz"),
            KeyboardMetricsCollector.DEFAULT_LAYOUT_NAME,
            KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_USER,
            "en-US",
            KeyboardLayout.LayoutType.getLayoutTypeEnumValue("azerty"),
        )
        assertExpectedLayoutConfiguration(
            event.layoutConfigurations[2],
            "de-CH",
            KeyboardLayout.LayoutType.getLayoutTypeEnumValue("qwertz"),
            "German",
            KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEVICE,
            "en-US",
            KeyboardLayout.LayoutType.getLayoutTypeEnumValue("qwerty"),
        )
    }

    @Test
    fun testCreateKeyboardConfigurationEvent_withDefaultLanguageTag() {
        val builder = KeyboardMetricsCollector.KeyboardConfigurationEvent.Builder(
            createKeyboard(
                DEVICE_ID,
                DEFAULT_VENDOR_ID,
                DEFAULT_PRODUCT_ID,
                DEFAULT_DEVICE_BUS,
                "und", // Undefined language tag
                "azerty"
            )
        )
        val event = builder.addLayoutSelection(
            createImeSubtype(4, null, "qwerty"), // Default language tag
            "German",
            KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEVICE
        ).build()

        assertExpectedLayoutConfiguration(
            event.layoutConfigurations[0],
            KeyboardMetricsCollector.DEFAULT_LANGUAGE_TAG,
            KeyboardLayout.LayoutType.getLayoutTypeEnumValue("azerty"),
            "German",
            KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEVICE,
            KeyboardMetricsCollector.DEFAULT_LANGUAGE_TAG,
            KeyboardLayout.LayoutType.getLayoutTypeEnumValue("qwerty"),
        )
    }

    private fun assertExpectedLayoutConfiguration(
        configuration: KeyboardMetricsCollector.LayoutConfiguration,
        expectedKeyboardLanguageTag: String,
        expectedKeyboardLayoutType: Int,
        expectedSelectedLayout: String,
        expectedLayoutSelectionCriteria: Int,
        expectedImeLanguageTag: String,
        expectedImeLayoutType: Int
    ) {
        assertEquals(expectedKeyboardLanguageTag, configuration.keyboardLanguageTag)
        assertEquals(expectedKeyboardLayoutType, configuration.keyboardLayoutType)
        assertEquals(expectedSelectedLayout, configuration.keyboardLayoutName)
        assertEquals(expectedLayoutSelectionCriteria, configuration.layoutSelectionCriteria)
        assertEquals(expectedImeLanguageTag, configuration.imeLanguageTag)
        assertEquals(expectedImeLayoutType, configuration.imeLayoutType)
    }
}
