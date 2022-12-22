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

package com.android.server.input

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.hardware.input.IInputManager
import android.hardware.input.InputManager
import android.hardware.input.KeyboardLayout
import android.icu.lang.UScript
import android.icu.util.ULocale
import android.os.Bundle
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import android.provider.Settings
import android.view.InputDevice
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.R
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

private fun createKeyboard(
    deviceId: Int,
    vendorId: Int,
    productId: Int,
    languageTag: String,
    layoutType: String
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

/**
 * Tests for {@link Default UI} and {@link New UI}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:KeyboardLayoutManagerTests
 */
@Presubmit
class KeyboardLayoutManagerTests {
    companion object {
        const val DEVICE_ID = 1
        const val VENDOR_SPECIFIC_DEVICE_ID = 2
        const val ENGLISH_DVORAK_DEVICE_ID = 3
        const val USER_ID = 4
        const val IME_ID = "ime_id"
        const val PACKAGE_NAME = "KeyboardLayoutManagerTests"
        const val RECEIVER_NAME = "DummyReceiver"
        private const val ENGLISH_US_LAYOUT_NAME = "keyboard_layout_english_us"
        private const val ENGLISH_UK_LAYOUT_NAME = "keyboard_layout_english_uk"
        private const val VENDOR_SPECIFIC_LAYOUT_NAME = "keyboard_layout_vendorId:1,productId:1"
    }

    private val ENGLISH_US_LAYOUT_DESCRIPTOR = createLayoutDescriptor(ENGLISH_US_LAYOUT_NAME)
    private val ENGLISH_UK_LAYOUT_DESCRIPTOR = createLayoutDescriptor(ENGLISH_UK_LAYOUT_NAME)
    private val VENDOR_SPECIFIC_LAYOUT_DESCRIPTOR =
        createLayoutDescriptor(VENDOR_SPECIFIC_LAYOUT_NAME)

    @get:Rule
    val rule = MockitoJUnit.rule()!!

    @Mock
    private lateinit var iInputManager: IInputManager

    @Mock
    private lateinit var native: NativeInputManagerService

    @Mock
    private lateinit var packageManager: PackageManager
    private lateinit var keyboardLayoutManager: KeyboardLayoutManager

    private lateinit var imeInfo: InputMethodInfo
    private var nextImeSubtypeId = 0
    private lateinit var context: Context
    private lateinit var dataStore: PersistentDataStore
    private lateinit var testLooper: TestLooper

    // Devices
    private lateinit var keyboardDevice: InputDevice
    private lateinit var vendorSpecificKeyboardDevice: InputDevice
    private lateinit var englishDvorakKeyboardDevice: InputDevice

    @Before
    fun setup() {
        context = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        dataStore = PersistentDataStore(object : PersistentDataStore.Injector() {
            override fun openRead(): InputStream? {
                throw FileNotFoundException()
            }

            override fun startWrite(): FileOutputStream? {
                throw IOException()
            }

            override fun finishWrite(fos: FileOutputStream?, success: Boolean) {}
        })
        testLooper = TestLooper()
        keyboardLayoutManager = KeyboardLayoutManager(context, native, dataStore, testLooper.looper)
        setupInputDevices()
        setupBroadcastReceiver()
        setupIme()
    }

    private fun setupInputDevices() {
        val inputManager = InputManager.resetInstance(iInputManager)
        Mockito.`when`(context.getSystemService(Mockito.eq(Context.INPUT_SERVICE)))
            .thenReturn(inputManager)

        keyboardDevice = createKeyboard(DEVICE_ID, 0, 0, "", "")
        vendorSpecificKeyboardDevice = createKeyboard(VENDOR_SPECIFIC_DEVICE_ID, 1, 1, "", "")
        englishDvorakKeyboardDevice =
            createKeyboard(ENGLISH_DVORAK_DEVICE_ID, 0, 0, "en", "dvorak")
        Mockito.`when`(iInputManager.inputDeviceIds)
            .thenReturn(intArrayOf(DEVICE_ID, VENDOR_SPECIFIC_DEVICE_ID, ENGLISH_DVORAK_DEVICE_ID))
        Mockito.`when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardDevice)
        Mockito.`when`(iInputManager.getInputDevice(VENDOR_SPECIFIC_DEVICE_ID))
            .thenReturn(vendorSpecificKeyboardDevice)
        Mockito.`when`(iInputManager.getInputDevice(ENGLISH_DVORAK_DEVICE_ID))
            .thenReturn(englishDvorakKeyboardDevice)
    }

    private fun setupBroadcastReceiver() {
        Mockito.`when`(context.packageManager).thenReturn(packageManager)

        val info = createMockReceiver()
        Mockito.`when`(packageManager.queryBroadcastReceivers(Mockito.any(), Mockito.anyInt()))
            .thenReturn(listOf(info))
        Mockito.`when`(packageManager.getReceiverInfo(Mockito.any(), Mockito.anyInt()))
            .thenReturn(info.activityInfo)

        val resources = context.resources
        Mockito.`when`(
            packageManager.getResourcesForApplication(
                Mockito.any(
                    ApplicationInfo::class.java
                )
            )
        ).thenReturn(resources)
    }

    private fun setupIme() {
        imeInfo = InputMethodInfo(PACKAGE_NAME, RECEIVER_NAME, "", "", 0)
    }

    @Test
    fun testDefaultUi_getKeyboardLayouts() {
        NewSettingsApiFlag(false).use {
            val keyboardLayouts = keyboardLayoutManager.keyboardLayouts
            assertNotEquals(
                "Default UI: Keyboard layout API should not return empty array",
                0,
                keyboardLayouts.size
            )
            assertTrue(
                "Default UI: Keyboard layout API should provide English(US) layout",
                hasLayout(keyboardLayouts, ENGLISH_US_LAYOUT_DESCRIPTOR)
            )
        }
    }

    @Test
    fun testNewUi_getKeyboardLayouts() {
        NewSettingsApiFlag(true).use {
            val keyboardLayouts = keyboardLayoutManager.keyboardLayouts
            assertNotEquals(
                "New UI: Keyboard layout API should not return empty array",
                0,
                keyboardLayouts.size
            )
            assertTrue(
                "New UI: Keyboard layout API should provide English(US) layout",
                hasLayout(keyboardLayouts, ENGLISH_US_LAYOUT_DESCRIPTOR)
            )
        }
    }

    @Test
    fun testDefaultUi_getKeyboardLayoutsForInputDevice() {
        NewSettingsApiFlag(false).use {
            val keyboardLayouts =
                keyboardLayoutManager.getKeyboardLayoutsForInputDevice(keyboardDevice.identifier)
            assertNotEquals(
                "Default UI: getKeyboardLayoutsForInputDevice API should not return empty array",
                0,
                keyboardLayouts.size
            )
            assertTrue(
                "Default UI: getKeyboardLayoutsForInputDevice API should provide English(US) " +
                        "layout",
                hasLayout(keyboardLayouts, ENGLISH_US_LAYOUT_DESCRIPTOR)
            )

            val vendorSpecificKeyboardLayouts =
                keyboardLayoutManager.getKeyboardLayoutsForInputDevice(
                    vendorSpecificKeyboardDevice.identifier
                )
            assertEquals(
                "Default UI: getKeyboardLayoutsForInputDevice API should return only vendor " +
                        "specific layout",
                1,
                vendorSpecificKeyboardLayouts.size
            )
            assertEquals(
                "Default UI: getKeyboardLayoutsForInputDevice API should return vendor specific " +
                        "layout",
                VENDOR_SPECIFIC_LAYOUT_DESCRIPTOR,
                vendorSpecificKeyboardLayouts[0].descriptor
            )
        }
    }

    @Test
    fun testNewUi_getKeyboardLayoutsForInputDevice() {
        NewSettingsApiFlag(true).use {
            val keyboardLayouts =
                keyboardLayoutManager.getKeyboardLayoutsForInputDevice(keyboardDevice.identifier)
            assertEquals(
                "New UI: getKeyboardLayoutsForInputDevice API should always return empty array",
                0,
                keyboardLayouts.size
            )
        }
    }

    @Test
    fun testDefaultUi_getSetCurrentKeyboardLayoutForInputDevice() {
        NewSettingsApiFlag(false).use {
            assertNull(
                "Default UI: getCurrentKeyboardLayoutForInputDevice API should return null if " +
                        "nothing was set",
                keyboardLayoutManager.getCurrentKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier
                )
            )

            keyboardLayoutManager.setCurrentKeyboardLayoutForInputDevice(
                keyboardDevice.identifier,
                ENGLISH_US_LAYOUT_DESCRIPTOR
            )
            val keyboardLayout =
                keyboardLayoutManager.getCurrentKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier
                )
            assertEquals(
                "Default UI: getCurrentKeyboardLayoutForInputDevice API should return the set " +
                        "layout",
                ENGLISH_US_LAYOUT_DESCRIPTOR,
                keyboardLayout
            )
        }
    }

    @Test
    fun testNewUi_getSetCurrentKeyboardLayoutForInputDevice() {
        NewSettingsApiFlag(true).use {
            keyboardLayoutManager.setCurrentKeyboardLayoutForInputDevice(
                keyboardDevice.identifier,
                ENGLISH_US_LAYOUT_DESCRIPTOR
            )
            assertNull(
                "New UI: getCurrentKeyboardLayoutForInputDevice API should always return null " +
                        "even after setCurrentKeyboardLayoutForInputDevice",
                keyboardLayoutManager.getCurrentKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier
                )
            )
        }
    }

    @Test
    fun testDefaultUi_getEnabledKeyboardLayoutsForInputDevice() {
        NewSettingsApiFlag(false).use {
            keyboardLayoutManager.addKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, ENGLISH_US_LAYOUT_DESCRIPTOR
            )

            val keyboardLayouts =
                keyboardLayoutManager.getEnabledKeyboardLayoutsForInputDevice(
                    keyboardDevice.identifier
                )
            assertEquals(
                "Default UI: getEnabledKeyboardLayoutsForInputDevice API should return added " +
                        "layout",
                1,
                keyboardLayouts.size
            )
            assertEquals(
                "Default UI: getEnabledKeyboardLayoutsForInputDevice API should return " +
                        "English(US) layout",
                ENGLISH_US_LAYOUT_DESCRIPTOR,
                keyboardLayouts[0]
            )
            assertEquals(
                "Default UI: getCurrentKeyboardLayoutForInputDevice API should return " +
                        "English(US) layout (Auto select the first enabled layout)",
                ENGLISH_US_LAYOUT_DESCRIPTOR,
                keyboardLayoutManager.getCurrentKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier
                )
            )

            keyboardLayoutManager.removeKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, ENGLISH_US_LAYOUT_DESCRIPTOR
            )
            assertEquals(
                "Default UI: getKeyboardLayoutsForInputDevice API should return 0 layouts",
                0,
                keyboardLayoutManager.getEnabledKeyboardLayoutsForInputDevice(
                    keyboardDevice.identifier
                ).size
            )
            assertNull(
                "Default UI: getCurrentKeyboardLayoutForInputDevice API should return null after " +
                        "the enabled layout is removed",
                keyboardLayoutManager.getCurrentKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier
                )
            )
        }
    }

    @Test
    fun testNewUi_getEnabledKeyboardLayoutsForInputDevice() {
        NewSettingsApiFlag(true).use {
            keyboardLayoutManager.addKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, ENGLISH_US_LAYOUT_DESCRIPTOR
            )

            assertEquals(
                "New UI: getEnabledKeyboardLayoutsForInputDevice API should return always return " +
                        "an empty array",
                0,
                keyboardLayoutManager.getEnabledKeyboardLayoutsForInputDevice(
                    keyboardDevice.identifier
                ).size
            )
            assertNull(
                "New UI: getCurrentKeyboardLayoutForInputDevice API should always return null",
                keyboardLayoutManager.getCurrentKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier
                )
            )
        }
    }

    @Test
    fun testDefaultUi_switchKeyboardLayout() {
        NewSettingsApiFlag(false).use {
            keyboardLayoutManager.addKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, ENGLISH_US_LAYOUT_DESCRIPTOR
            )
            keyboardLayoutManager.addKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, ENGLISH_UK_LAYOUT_DESCRIPTOR
            )
            assertEquals(
                "Default UI: getCurrentKeyboardLayoutForInputDevice API should return " +
                        "English(US) layout",
                ENGLISH_US_LAYOUT_DESCRIPTOR,
                keyboardLayoutManager.getCurrentKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier
                )
            )

            keyboardLayoutManager.switchKeyboardLayout(DEVICE_ID, 1)

            // Throws null pointer because trying to show toast using TestLooper
            assertThrows(NullPointerException::class.java) { testLooper.dispatchAll() }
            assertEquals("Default UI: getCurrentKeyboardLayoutForInputDevice API should return " +
                    "English(UK) layout",
                ENGLISH_UK_LAYOUT_DESCRIPTOR,
                keyboardLayoutManager.getCurrentKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier
                )
            )
        }
    }

    @Test
    fun testNewUi_switchKeyboardLayout() {
        NewSettingsApiFlag(true).use {
            keyboardLayoutManager.addKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, ENGLISH_US_LAYOUT_DESCRIPTOR
            )
            keyboardLayoutManager.addKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, ENGLISH_UK_LAYOUT_DESCRIPTOR
            )

            keyboardLayoutManager.switchKeyboardLayout(DEVICE_ID, 1)
            testLooper.dispatchAll()

            assertNull("New UI: getCurrentKeyboardLayoutForInputDevice API should always return " +
                    "null",
                keyboardLayoutManager.getCurrentKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier
                )
            )
        }
    }

    @Test
    fun testDefaultUi_getKeyboardLayout() {
        NewSettingsApiFlag(false).use {
            val keyboardLayout =
                keyboardLayoutManager.getKeyboardLayout(ENGLISH_US_LAYOUT_DESCRIPTOR)
            assertEquals("Default UI: getKeyboardLayout API should return correct Layout from " +
                    "available layouts",
                ENGLISH_US_LAYOUT_DESCRIPTOR,
                keyboardLayout!!.descriptor
            )
        }
    }

    @Test
    fun testNewUi_getKeyboardLayout() {
        NewSettingsApiFlag(true).use {
            val keyboardLayout =
                keyboardLayoutManager.getKeyboardLayout(ENGLISH_US_LAYOUT_DESCRIPTOR)
            assertEquals("New UI: getKeyboardLayout API should return correct Layout from " +
                    "available layouts",
                ENGLISH_US_LAYOUT_DESCRIPTOR,
                keyboardLayout!!.descriptor
            )
        }
    }

    @Test
    fun testDefaultUi_getSetKeyboardLayoutForInputDevice_WithImeInfo() {
        NewSettingsApiFlag(false).use {
            val imeSubtype = createImeSubtype()
            keyboardLayoutManager.setKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, USER_ID, imeInfo, imeSubtype,
                ENGLISH_UK_LAYOUT_DESCRIPTOR
            )
            val keyboardLayout =
                keyboardLayoutManager.getKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier, USER_ID, imeInfo, imeSubtype
                )
            assertNull(
                "Default UI: getKeyboardLayoutForInputDevice API should always return null",
                keyboardLayout
            )
        }
    }

    @Test
    fun testNewUi_getSetKeyboardLayoutForInputDevice_withImeInfo() {
        NewSettingsApiFlag(true).use {
            val imeSubtype = createImeSubtype()

            keyboardLayoutManager.setKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, USER_ID, imeInfo, imeSubtype,
                ENGLISH_UK_LAYOUT_DESCRIPTOR
            )
            assertEquals(
                "New UI: getKeyboardLayoutForInputDevice API should return the set layout",
                ENGLISH_UK_LAYOUT_DESCRIPTOR,
                keyboardLayoutManager.getKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier, USER_ID, imeInfo, imeSubtype
                )
            )

            // This should replace previously set layout
            keyboardLayoutManager.setKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, USER_ID, imeInfo, imeSubtype,
                ENGLISH_US_LAYOUT_DESCRIPTOR
            )
            assertEquals(
                "New UI: getKeyboardLayoutForInputDevice API should return the last set layout",
                ENGLISH_US_LAYOUT_DESCRIPTOR,
                keyboardLayoutManager.getKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier, USER_ID, imeInfo, imeSubtype
                )
            )
        }
    }

    @Test
    fun testDefaultUi_getKeyboardLayoutListForInputDevice() {
        NewSettingsApiFlag(false).use {
            val keyboardLayouts =
                keyboardLayoutManager.getKeyboardLayoutListForInputDevice(
                    keyboardDevice.identifier, USER_ID, imeInfo,
                    createImeSubtype()
                )
            assertEquals("Default UI: getKeyboardLayoutListForInputDevice API should always " +
                    "return empty array",
                0,
                keyboardLayouts.size
            )
        }
    }

    @Test
    fun testNewUi_getKeyboardLayoutListForInputDevice() {
        NewSettingsApiFlag(true).use {
            // Check Layouts for "hi-Latn". It should return all 'Latn' keyboard layouts
            var keyboardLayouts =
                keyboardLayoutManager.getKeyboardLayoutListForInputDevice(
                    keyboardDevice.identifier, USER_ID, imeInfo,
                    createImeSubtypeForLanguageTag("hi-Latn")
                )
            assertNotEquals(
                "New UI: getKeyboardLayoutListForInputDevice API should return the list of " +
                        "supported layouts with matching script code",
                0,
                keyboardLayouts.size
            )

            val englishScripts = UScript.getCode(Locale.forLanguageTag("hi-Latn"))
            for (kl in keyboardLayouts) {
                var isCompatible = false
                for (i in 0 until kl.locales.size()) {
                    val locale: Locale = kl.locales.get(i) ?: continue
                    val scripts = UScript.getCode(locale)
                    if (scripts != null && areScriptsCompatible(scripts, englishScripts)) {
                        isCompatible = true
                        break
                    }
                }
                assertTrue(
                    "New UI: getKeyboardLayoutListForInputDevice API should only return " +
                            "compatible layouts but found " + kl.descriptor,
                    isCompatible
                )
            }

            // Check Layouts for "hi" which by default uses 'Deva' script.
            keyboardLayouts =
                keyboardLayoutManager.getKeyboardLayoutListForInputDevice(
                    keyboardDevice.identifier, USER_ID, imeInfo,
                    createImeSubtypeForLanguageTag("hi")
                )
            assertEquals("New UI: getKeyboardLayoutListForInputDevice API should return empty " +
                    "list if no supported layouts available",
                0,
                keyboardLayouts.size
            )

            // If user manually selected some layout, always provide it in the layout list
            val imeSubtype = createImeSubtypeForLanguageTag("hi")
            keyboardLayoutManager.setKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, USER_ID, imeInfo, imeSubtype,
                ENGLISH_US_LAYOUT_DESCRIPTOR
            )
            keyboardLayouts =
                keyboardLayoutManager.getKeyboardLayoutListForInputDevice(
                    keyboardDevice.identifier, USER_ID, imeInfo,
                    imeSubtype
                )
            assertEquals("New UI: getKeyboardLayoutListForInputDevice API should return user " +
                    "selected layout even if the script is incompatible with IME",
                    1,
                keyboardLayouts.size
            )
        }
    }

    @Test
    fun testNewUi_getDefaultKeyboardLayoutForInputDevice_withImeLanguageTag() {
        NewSettingsApiFlag(true).use {
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTag("en-US"),
                ENGLISH_US_LAYOUT_DESCRIPTOR
            )
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTag("en-GB"),
                ENGLISH_UK_LAYOUT_DESCRIPTOR
            )
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTag("de"),
                createLayoutDescriptor("keyboard_layout_german")
            )
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTag("fr-FR"),
                createLayoutDescriptor("keyboard_layout_french")
            )
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTag("ru"),
                createLayoutDescriptor("keyboard_layout_russian")
            )
            assertNull(
                "New UI: getDefaultKeyboardLayoutForInputDevice should return null when no " +
                        "layout available",
                keyboardLayoutManager.getKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier, USER_ID, imeInfo,
                    createImeSubtypeForLanguageTag("it")
                )
            )
            assertNull(
                "New UI: getDefaultKeyboardLayoutForInputDevice should return null when no " +
                        "layout for script code is available",
                keyboardLayoutManager.getKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier, USER_ID, imeInfo,
                    createImeSubtypeForLanguageTag("en-Deva")
                )
            )
        }
    }

    @Test
    fun testNewUi_getDefaultKeyboardLayoutForInputDevice_withImeLanguageTagAndLayoutType() {
        NewSettingsApiFlag(true).use {
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTagAndLayoutType("en-US", "qwerty"),
                ENGLISH_US_LAYOUT_DESCRIPTOR
            )
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTagAndLayoutType("en-US", "dvorak"),
                createLayoutDescriptor("keyboard_layout_english_us_dvorak")
            )
            // Try to match layout type even if country doesn't match
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTagAndLayoutType("en-GB", "dvorak"),
                createLayoutDescriptor("keyboard_layout_english_us_dvorak")
            )
            // Choose layout based on layout type priority, if layout type is not provided by IME
            // (Qwerty > Dvorak > Extended)
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTagAndLayoutType("en-US", ""),
                ENGLISH_US_LAYOUT_DESCRIPTOR
            )
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTagAndLayoutType("en-GB", "qwerty"),
                ENGLISH_UK_LAYOUT_DESCRIPTOR
            )
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTagAndLayoutType("de", "qwertz"),
                createLayoutDescriptor("keyboard_layout_german")
            )
            // Wrong layout type should match with language if provided layout type not available
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTagAndLayoutType("de", "qwerty"),
                createLayoutDescriptor("keyboard_layout_german")
            )
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTagAndLayoutType("fr-FR", "azerty"),
                createLayoutDescriptor("keyboard_layout_french")
            )
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTagAndLayoutType("ru", "qwerty"),
                createLayoutDescriptor("keyboard_layout_russian_qwerty")
            )
            // If layout type is empty then prioritize KCM with empty layout type
            assertCorrectLayout(
                keyboardDevice,
                createImeSubtypeForLanguageTagAndLayoutType("ru", ""),
                createLayoutDescriptor("keyboard_layout_russian")
            )
            assertNull("New UI: getDefaultKeyboardLayoutForInputDevice should return null when " +
                    "no layout for script code is available",
                keyboardLayoutManager.getKeyboardLayoutForInputDevice(
                    keyboardDevice.identifier, USER_ID, imeInfo,
                    createImeSubtypeForLanguageTagAndLayoutType("en-Deva-US", "")
                )
            )
        }
    }

    @Test
    fun testNewUi_getDefaultKeyboardLayoutForInputDevice_withHwLanguageTagAndLayoutType() {
        NewSettingsApiFlag(true).use {
            // Should return English dvorak even if IME current layout is qwerty, since HW says the
            // keyboard is a Dvorak keyboard
            assertCorrectLayout(
                englishDvorakKeyboardDevice,
                createImeSubtypeForLanguageTagAndLayoutType("en", "qwerty"),
                createLayoutDescriptor("keyboard_layout_english_us_dvorak")
            )

            // Fallback to IME information if the HW provided layout script is incompatible with the
            // provided IME subtype
            assertCorrectLayout(
                englishDvorakKeyboardDevice,
                createImeSubtypeForLanguageTagAndLayoutType("ru", ""),
                createLayoutDescriptor("keyboard_layout_russian")
            )
        }
    }

    private fun assertCorrectLayout(
        device: InputDevice,
        imeSubtype: InputMethodSubtype,
        expectedLayout: String
    ) {
        assertEquals(
            "New UI: getDefaultKeyboardLayoutForInputDevice should return $expectedLayout",
            expectedLayout,
            keyboardLayoutManager.getKeyboardLayoutForInputDevice(
                device.identifier, USER_ID, imeInfo, imeSubtype
            )
        )
    }

    private fun createImeSubtype(): InputMethodSubtype =
        InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeId(nextImeSubtypeId++).build()

    private fun createImeSubtypeForLanguageTag(languageTag: String): InputMethodSubtype =
        InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeId(nextImeSubtypeId++)
            .setLanguageTag(languageTag).build()

    private fun createImeSubtypeForLanguageTagAndLayoutType(
        languageTag: String,
        layoutType: String
    ): InputMethodSubtype =
        InputMethodSubtype.InputMethodSubtypeBuilder().setSubtypeId(nextImeSubtypeId++)
            .setPhysicalKeyboardHint(ULocale.forLanguageTag(languageTag), layoutType).build()

    private fun hasLayout(layoutList: Array<KeyboardLayout>, layoutDesc: String): Boolean {
        for (kl in layoutList) {
            if (kl.descriptor == layoutDesc) {
                return true
            }
        }
        return false
    }

    private fun createLayoutDescriptor(keyboardName: String): String =
        "$PACKAGE_NAME/$RECEIVER_NAME/$keyboardName"

    private fun areScriptsCompatible(scriptList1: IntArray, scriptList2: IntArray): Boolean {
        for (s1 in scriptList1) {
            for (s2 in scriptList2) {
                if (s1 == s2) return true
            }
        }
        return false
    }

    private fun createMockReceiver(): ResolveInfo {
        val info = ResolveInfo()
        info.activityInfo = ActivityInfo()
        info.activityInfo.packageName = PACKAGE_NAME
        info.activityInfo.name = RECEIVER_NAME
        info.activityInfo.applicationInfo = ApplicationInfo()
        info.activityInfo.metaData = Bundle()
        info.activityInfo.metaData.putInt(
            InputManager.META_DATA_KEYBOARD_LAYOUTS,
            R.xml.keyboard_layouts
        )
        info.serviceInfo = ServiceInfo()
        info.serviceInfo.packageName = PACKAGE_NAME
        info.serviceInfo.name = RECEIVER_NAME
        return info
    }

    private inner class NewSettingsApiFlag constructor(enabled: Boolean) : AutoCloseable {
        init {
            Settings.Global.putString(
                context.contentResolver,
                "settings_new_keyboard_ui", enabled.toString()
            )
        }

        override fun close() {
            Settings.Global.putString(
                context.contentResolver,
                "settings_new_keyboard_ui",
                ""
            )
        }
    }
}