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

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.hardware.input.KeyboardLayoutSelectionResult
import android.hardware.input.IInputManager
import android.hardware.input.InputManager
import android.hardware.input.InputManagerGlobal
import android.hardware.input.KeyboardLayout
import android.icu.util.ULocale
import android.os.Bundle
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import android.util.proto.ProtoOutputStream
import android.view.InputDevice
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.internal.os.KeyboardConfiguredProto
import com.android.internal.util.FrameworkStatsLog
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.test.input.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

fun createKeyboard(
    deviceId: Int,
    vendorId: Int,
    productId: Int,
    deviceBus: Int,
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
        .setDeviceBus(deviceBus)
        .setKeyboardLanguageTag(languageTag)
        .setKeyboardLayoutType(layoutType)
        .build()

/**
 * Tests for {@link Default UI} and {@link New UI}.
 *
 * Build/Install/Run:
 * atest InputTests:KeyboardLayoutManagerTests
 */
@Presubmit
class KeyboardLayoutManagerTests {
    companion object {
        const val DEVICE_ID = 1
        const val VENDOR_SPECIFIC_DEVICE_ID = 2
        const val ENGLISH_DVORAK_DEVICE_ID = 3
        const val ENGLISH_QWERTY_DEVICE_ID = 4
        const val DEFAULT_VENDOR_ID = 123
        const val DEFAULT_PRODUCT_ID = 456
        const val DEFAULT_DEVICE_BUS = 789
        const val USER_ID = 4
        const val IME_ID = "ime_id"
        const val PACKAGE_NAME = "KeyboardLayoutManagerTests"
        const val RECEIVER_NAME = "DummyReceiver"
        private const val ENGLISH_US_LAYOUT_NAME = "keyboard_layout_english_us"
        private const val ENGLISH_UK_LAYOUT_NAME = "keyboard_layout_english_uk"
        private const val GERMAN_LAYOUT_NAME = "keyboard_layout_german"
        private const val VENDOR_SPECIFIC_LAYOUT_NAME = "keyboard_layout_vendorId:1,productId:1"
        const val LAYOUT_TYPE_QWERTZ = 2
        const val LAYOUT_TYPE_QWERTY = 1
        const val LAYOUT_TYPE_DEFAULT = 0
    }

    private val ENGLISH_US_LAYOUT_DESCRIPTOR = createLayoutDescriptor(ENGLISH_US_LAYOUT_NAME)
    private val ENGLISH_UK_LAYOUT_DESCRIPTOR = createLayoutDescriptor(ENGLISH_UK_LAYOUT_NAME)
    private val GERMAN_LAYOUT_DESCRIPTOR = createLayoutDescriptor(GERMAN_LAYOUT_NAME)
    private val VENDOR_SPECIFIC_LAYOUT_DESCRIPTOR =
        createLayoutDescriptor(VENDOR_SPECIFIC_LAYOUT_NAME)

    @JvmField
    @Rule
    val extendedMockitoRule = ExtendedMockitoRule.Builder(this)
            .mockStatic(FrameworkStatsLog::class.java).build()!!

    @Mock
    private lateinit var iInputManager: IInputManager

    @Mock
    private lateinit var native: NativeInputManagerService

    @Mock
    private lateinit var packageManager: PackageManager
    @Mock
    private lateinit var notificationManager: NotificationManager
    private lateinit var keyboardLayoutManager: KeyboardLayoutManager

    private lateinit var imeInfo: InputMethodInfo
    private var nextImeSubtypeId = 0
    private lateinit var context: Context
    private lateinit var dataStore: PersistentDataStore
    private lateinit var testLooper: TestLooper
    private lateinit var inputManagerGlobalSession: InputManagerGlobal.TestSession

    // Devices
    private lateinit var keyboardDevice: InputDevice
    private lateinit var vendorSpecificKeyboardDevice: InputDevice
    private lateinit var englishDvorakKeyboardDevice: InputDevice
    private lateinit var englishQwertyKeyboardDevice: InputDevice

    @Before
    fun setup() {
        context = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        inputManagerGlobalSession = InputManagerGlobal.createTestSession(iInputManager)
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
        keyboardLayoutManager = Mockito.spy(
            KeyboardLayoutManager(context, native, dataStore, testLooper.looper)
        )
        Mockito.`when`(context.getSystemService(Mockito.eq(Context.NOTIFICATION_SERVICE)))
                .thenReturn(notificationManager)
        setupInputDevices()
        setupBroadcastReceiver()
        setupIme()
    }

    @After
    fun tearDown() {
        if (this::inputManagerGlobalSession.isInitialized) {
            inputManagerGlobalSession.close()
        }
    }

    private fun setupInputDevices() {
        val inputManager = InputManager(context)
        Mockito.`when`(context.getSystemService(Mockito.eq(Context.INPUT_SERVICE)))
            .thenReturn(inputManager)

        keyboardDevice = createKeyboard(DEVICE_ID, DEFAULT_VENDOR_ID, DEFAULT_PRODUCT_ID,
                DEFAULT_DEVICE_BUS, "", "")
        vendorSpecificKeyboardDevice = createKeyboard(VENDOR_SPECIFIC_DEVICE_ID, 1, 1,
                1, "", "")
        englishDvorakKeyboardDevice = createKeyboard(ENGLISH_DVORAK_DEVICE_ID, DEFAULT_VENDOR_ID,
                DEFAULT_PRODUCT_ID, DEFAULT_DEVICE_BUS, "en", "dvorak")
        englishQwertyKeyboardDevice = createKeyboard(ENGLISH_QWERTY_DEVICE_ID, DEFAULT_VENDOR_ID,
                DEFAULT_PRODUCT_ID, DEFAULT_DEVICE_BUS, "en", "qwerty")
        Mockito.`when`(iInputManager.inputDeviceIds)
            .thenReturn(intArrayOf(
                DEVICE_ID,
                VENDOR_SPECIFIC_DEVICE_ID,
                ENGLISH_DVORAK_DEVICE_ID,
                ENGLISH_QWERTY_DEVICE_ID
            ))
        Mockito.`when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardDevice)
        Mockito.`when`(iInputManager.getInputDevice(VENDOR_SPECIFIC_DEVICE_ID))
            .thenReturn(vendorSpecificKeyboardDevice)
        Mockito.`when`(iInputManager.getInputDevice(ENGLISH_DVORAK_DEVICE_ID))
            .thenReturn(englishDvorakKeyboardDevice)
        Mockito.`when`(iInputManager.getInputDevice(ENGLISH_QWERTY_DEVICE_ID))
                .thenReturn(englishQwertyKeyboardDevice)
    }

    private fun setupBroadcastReceiver() {
        Mockito.`when`(context.packageManager).thenReturn(packageManager)

        val info = createMockReceiver()
        Mockito.`when`(packageManager.queryBroadcastReceiversAsUser(Mockito.any(), Mockito.anyInt(),
                Mockito.anyInt())).thenReturn(listOf(info))
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
    fun testGetKeyboardLayouts() {
        val keyboardLayouts = keyboardLayoutManager.keyboardLayouts
        assertNotEquals(
            "Keyboard layout API should not return empty array",
            0,
            keyboardLayouts.size
        )
        assertTrue(
            "Keyboard layout API should provide English(US) layout",
            hasLayout(keyboardLayouts, ENGLISH_US_LAYOUT_DESCRIPTOR)
        )
    }

    @Test
    fun testGetKeyboardLayout() {
        val keyboardLayout =
            keyboardLayoutManager.getKeyboardLayout(ENGLISH_US_LAYOUT_DESCRIPTOR)
        assertEquals("getKeyboardLayout API should return correct Layout from " +
                "available layouts",
            ENGLISH_US_LAYOUT_DESCRIPTOR,
            keyboardLayout!!.descriptor
        )
    }

    @Test
    fun testGetSetKeyboardLayoutForInputDevice_withImeInfo() {
        val imeSubtype = createImeSubtype()

        keyboardLayoutManager.setKeyboardLayoutForInputDevice(
            keyboardDevice.identifier, USER_ID, imeInfo, imeSubtype,
            ENGLISH_UK_LAYOUT_DESCRIPTOR
        )
        var result =
            keyboardLayoutManager.getKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, USER_ID, imeInfo, imeSubtype
            )
        assertEquals(
            "getKeyboardLayoutForInputDevice API should return the set layout",
            ENGLISH_UK_LAYOUT_DESCRIPTOR,
            result.layoutDescriptor
        )

        // This should replace previously set layout
        keyboardLayoutManager.setKeyboardLayoutForInputDevice(
            keyboardDevice.identifier, USER_ID, imeInfo, imeSubtype,
            ENGLISH_US_LAYOUT_DESCRIPTOR
        )
        result =
            keyboardLayoutManager.getKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, USER_ID, imeInfo, imeSubtype
            )
        assertEquals(
            "getKeyboardLayoutForInputDevice API should return the last set layout",
            ENGLISH_US_LAYOUT_DESCRIPTOR,
            result.layoutDescriptor
        )
    }

    @Test
    fun testGetKeyboardLayoutListForInputDevice() {
        // Check Layouts for "hi-Latn". It should return all 'Latn' keyboard layouts
        var keyboardLayouts =
            keyboardLayoutManager.getKeyboardLayoutListForInputDevice(
                keyboardDevice.identifier, USER_ID, imeInfo,
                createImeSubtypeForLanguageTag("hi-Latn")
            )
        assertNotEquals(
            "getKeyboardLayoutListForInputDevice API should return the list of " +
                    "supported layouts with matching script code",
            0,
            keyboardLayouts.size
        )
        assertTrue("getKeyboardLayoutListForInputDevice API should return a list " +
                "containing English(US) layout for hi-Latn",
            containsLayout(keyboardLayouts, ENGLISH_US_LAYOUT_DESCRIPTOR)
        )
        assertTrue("getKeyboardLayoutListForInputDevice API should return a list " +
                "containing English(No script code) layout for hi-Latn",
            containsLayout(
                keyboardLayouts,
                createLayoutDescriptor("keyboard_layout_english_without_script_code")
            )
        )

        // Check Layouts for "hi" which by default uses 'Deva' script.
        keyboardLayouts =
            keyboardLayoutManager.getKeyboardLayoutListForInputDevice(
                keyboardDevice.identifier, USER_ID, imeInfo,
                createImeSubtypeForLanguageTag("hi")
            )
        assertEquals("getKeyboardLayoutListForInputDevice API should return empty " +
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
        assertEquals("getKeyboardLayoutListForInputDevice API should return user " +
                "selected layout even if the script is incompatible with IME",
                1,
            keyboardLayouts.size
        )

        // Special case Japanese: UScript ignores provided script code for certain language tags
        // Should manually match provided script codes and then rely on Uscript to derive
        // script from language tags and match those.
        keyboardLayouts =
            keyboardLayoutManager.getKeyboardLayoutListForInputDevice(
                    keyboardDevice.identifier, USER_ID, imeInfo,
                    createImeSubtypeForLanguageTag("ja-Latn-JP")
            )
        assertNotEquals(
            "getKeyboardLayoutListForInputDevice API should return the list of " +
                    "supported layouts with matching script code for ja-Latn-JP",
            0,
            keyboardLayouts.size
        )
        assertTrue("getKeyboardLayoutListForInputDevice API should return a list " +
                "containing English(US) layout for ja-Latn-JP",
            containsLayout(keyboardLayouts, ENGLISH_US_LAYOUT_DESCRIPTOR)
        )
        assertTrue("getKeyboardLayoutListForInputDevice API should return a list " +
                "containing English(No script code) layout for ja-Latn-JP",
            containsLayout(
                keyboardLayouts,
                createLayoutDescriptor("keyboard_layout_english_without_script_code")
            )
        )

        // If script code not explicitly provided for Japanese should rely on Uscript to find
        // derived script code and hence no suitable layout will be found.
        keyboardLayouts =
            keyboardLayoutManager.getKeyboardLayoutListForInputDevice(
                    keyboardDevice.identifier, USER_ID, imeInfo,
                    createImeSubtypeForLanguageTag("ja-JP")
            )
        assertEquals(
            "getKeyboardLayoutListForInputDevice API should return empty list of " +
                    "supported layouts with matching script code for ja-JP",
            0,
            keyboardLayouts.size
        )

        // If IME doesn't have a corresponding language tag, then should show all available
        // layouts no matter the script code.
        keyboardLayouts =
            keyboardLayoutManager.getKeyboardLayoutListForInputDevice(
                keyboardDevice.identifier, USER_ID, imeInfo, null
            )
        assertNotEquals(
            "getKeyboardLayoutListForInputDevice API should return all layouts if" +
                "language tag or subtype not provided",
            0,
            keyboardLayouts.size
        )
        assertTrue("getKeyboardLayoutListForInputDevice API should contain Latin " +
            "layouts if language tag or subtype not provided",
            containsLayout(keyboardLayouts, ENGLISH_US_LAYOUT_DESCRIPTOR)
        )
        assertTrue("getKeyboardLayoutListForInputDevice API should contain Cyrillic " +
            "layouts if language tag or subtype not provided",
            containsLayout(
                keyboardLayouts,
                createLayoutDescriptor("keyboard_layout_russian")
            )
        )
    }

    @Test
    fun testGetDefaultKeyboardLayoutForInputDevice_withImeLanguageTag() {
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
            GERMAN_LAYOUT_DESCRIPTOR
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
        assertEquals(
            "getDefaultKeyboardLayoutForInputDevice should return " +
                    "KeyboardLayoutSelectionResult.FAILED when no layout available",
            KeyboardLayoutSelectionResult.FAILED,
            keyboardLayoutManager.getKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, USER_ID, imeInfo,
                createImeSubtypeForLanguageTag("it")
            )
        )
        assertEquals(
            "getDefaultKeyboardLayoutForInputDevice should return " +
                    "KeyboardLayoutSelectionResult.FAILED when no layout for script code is" +
                    "available",
            KeyboardLayoutSelectionResult.FAILED,
            keyboardLayoutManager.getKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, USER_ID, imeInfo,
                createImeSubtypeForLanguageTag("en-Deva")
            )
        )
    }

    @Test
    fun testGetDefaultKeyboardLayoutForInputDevice_withImeLanguageTagAndLayoutType() {
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
            GERMAN_LAYOUT_DESCRIPTOR
        )
        // Wrong layout type should match with language if provided layout type not available
        assertCorrectLayout(
            keyboardDevice,
            createImeSubtypeForLanguageTagAndLayoutType("de", "qwerty"),
            GERMAN_LAYOUT_DESCRIPTOR
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
        assertEquals("getDefaultKeyboardLayoutForInputDevice should return " +
                "KeyboardLayoutSelectionResult.FAILED when no layout for script code is" +
                "available",
            KeyboardLayoutSelectionResult.FAILED,
            keyboardLayoutManager.getKeyboardLayoutForInputDevice(
                keyboardDevice.identifier, USER_ID, imeInfo,
                createImeSubtypeForLanguageTagAndLayoutType("en-Deva-US", "")
            )
        )
        // If prefer layout with empty country over mismatched country
        assertCorrectLayout(
            keyboardDevice,
            createImeSubtypeForLanguageTagAndLayoutType("en-AU", "qwerty"),
            ENGLISH_US_LAYOUT_DESCRIPTOR
        )
    }

    @Test
    fun testGetDefaultKeyboardLayoutForInputDevice_withHwLanguageTagAndLayoutType() {
        val frenchSubtype = createImeSubtypeForLanguageTagAndLayoutType("fr", "azerty")
        // Should return English dvorak even if IME current layout is French, since HW says the
        // keyboard is a Dvorak keyboard
        assertCorrectLayout(
            englishDvorakKeyboardDevice,
            frenchSubtype,
            createLayoutDescriptor("keyboard_layout_english_us_dvorak")
        )

        // Back to back changing HW keyboards with same product and vendor ID but different
        // language and layout type should configure the layouts correctly.
        assertCorrectLayout(
            englishQwertyKeyboardDevice,
            frenchSubtype,
            createLayoutDescriptor("keyboard_layout_english_us")
        )

        // Fallback to IME information if the HW provided layout script is incompatible with the
        // provided IME subtype
        assertCorrectLayout(
            englishDvorakKeyboardDevice,
            createImeSubtypeForLanguageTagAndLayoutType("ru", ""),
            createLayoutDescriptor("keyboard_layout_russian")
        )
    }

    @Test
    fun testConfigurationLogged_onInputDeviceAdded_VirtualKeyboardBasedSelection() {
        val imeInfos = listOf(
                KeyboardLayoutManager.ImeInfo(0, imeInfo,
                        createImeSubtypeForLanguageTagAndLayoutType("de-Latn", "qwertz")))
        Mockito.doReturn(imeInfos).`when`(keyboardLayoutManager).imeInfoListForLayoutMapping
        keyboardLayoutManager.onInputDeviceAdded(keyboardDevice.id)
        ExtendedMockito.verify {
            FrameworkStatsLog.write(
                    ArgumentMatchers.eq(FrameworkStatsLog.KEYBOARD_CONFIGURED),
                    ArgumentMatchers.anyBoolean(),
                    ArgumentMatchers.eq(keyboardDevice.vendorId),
                    ArgumentMatchers.eq(keyboardDevice.productId),
                    ArgumentMatchers.eq(
                        createByteArray(
                            KeyboardMetricsCollector.DEFAULT_LANGUAGE_TAG,
                            LAYOUT_TYPE_DEFAULT,
                            GERMAN_LAYOUT_NAME,
                            KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD,
                            "de-Latn",
                            LAYOUT_TYPE_QWERTZ
                        ),
                    ),
                    ArgumentMatchers.eq(keyboardDevice.deviceBus),
            )
        }
    }

    @Test
    fun testConfigurationLogged_onInputDeviceAdded_DeviceBasedSelection() {
        val imeInfos = listOf(
                KeyboardLayoutManager.ImeInfo(0, imeInfo,
                        createImeSubtypeForLanguageTagAndLayoutType("de-Latn", "qwertz")))
        Mockito.doReturn(imeInfos).`when`(keyboardLayoutManager).imeInfoListForLayoutMapping
        keyboardLayoutManager.onInputDeviceAdded(englishQwertyKeyboardDevice.id)
        ExtendedMockito.verify {
            FrameworkStatsLog.write(
                    ArgumentMatchers.eq(FrameworkStatsLog.KEYBOARD_CONFIGURED),
                    ArgumentMatchers.anyBoolean(),
                    ArgumentMatchers.eq(englishQwertyKeyboardDevice.vendorId),
                    ArgumentMatchers.eq(englishQwertyKeyboardDevice.productId),
                    ArgumentMatchers.eq(
                        createByteArray(
                            "en",
                            LAYOUT_TYPE_QWERTY,
                            ENGLISH_US_LAYOUT_NAME,
                            KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEVICE,
                            "de-Latn",
                            LAYOUT_TYPE_QWERTZ
                        )
                    ),
                    ArgumentMatchers.eq(keyboardDevice.deviceBus),
            )
        }
    }

    @Test
    fun testConfigurationLogged_onInputDeviceAdded_DefaultSelection() {
        val imeInfos = listOf(KeyboardLayoutManager.ImeInfo(0, imeInfo, createImeSubtype()))
        Mockito.doReturn(imeInfos).`when`(keyboardLayoutManager).imeInfoListForLayoutMapping
        keyboardLayoutManager.onInputDeviceAdded(keyboardDevice.id)
        ExtendedMockito.verify {
            FrameworkStatsLog.write(
                    ArgumentMatchers.eq(FrameworkStatsLog.KEYBOARD_CONFIGURED),
                    ArgumentMatchers.anyBoolean(),
                    ArgumentMatchers.eq(keyboardDevice.vendorId),
                    ArgumentMatchers.eq(keyboardDevice.productId),
                    ArgumentMatchers.eq(
                        createByteArray(
                            KeyboardMetricsCollector.DEFAULT_LANGUAGE_TAG,
                            LAYOUT_TYPE_DEFAULT,
                            "Default",
                            KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEFAULT,
                            KeyboardMetricsCollector.DEFAULT_LANGUAGE_TAG,
                            LAYOUT_TYPE_DEFAULT
                        ),
                    ),
                    ArgumentMatchers.eq(keyboardDevice.deviceBus),
            )
        }
    }

    @Test
    fun testConfigurationNotLogged_onInputDeviceChanged() {
        val imeInfos = listOf(KeyboardLayoutManager.ImeInfo(0, imeInfo, createImeSubtype()))
        Mockito.doReturn(imeInfos).`when`(keyboardLayoutManager).imeInfoListForLayoutMapping
        keyboardLayoutManager.onInputDeviceChanged(keyboardDevice.id)
        ExtendedMockito.verify({
            FrameworkStatsLog.write(
                    ArgumentMatchers.eq(FrameworkStatsLog.KEYBOARD_CONFIGURED),
                    ArgumentMatchers.anyBoolean(),
                    ArgumentMatchers.anyInt(),
                    ArgumentMatchers.anyInt(),
                    ArgumentMatchers.any(ByteArray::class.java),
                    ArgumentMatchers.anyInt(),
            )
        }, Mockito.times(0))
    }

    @Test
    fun testNotificationShown_onInputDeviceChanged() {
        val imeInfos = listOf(KeyboardLayoutManager.ImeInfo(0, imeInfo, createImeSubtype()))
        Mockito.doReturn(imeInfos).`when`(keyboardLayoutManager).imeInfoListForLayoutMapping
        Mockito.doReturn(false).`when`(keyboardLayoutManager).isVirtualDevice(
            ArgumentMatchers.eq(keyboardDevice.id)
        )
        keyboardLayoutManager.onInputDeviceChanged(keyboardDevice.id)
        ExtendedMockito.verify(
            notificationManager,
            Mockito.times(1)
        ).notifyAsUser(
            ArgumentMatchers.isNull(),
            ArgumentMatchers.anyInt(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any()
        )
    }

    @Test
    fun testNotificationNotShown_onInputDeviceChanged_forVirtualDevice() {
        val imeInfos = listOf(KeyboardLayoutManager.ImeInfo(0, imeInfo, createImeSubtype()))
        Mockito.doReturn(imeInfos).`when`(keyboardLayoutManager).imeInfoListForLayoutMapping
        Mockito.doReturn(true).`when`(keyboardLayoutManager).isVirtualDevice(
            ArgumentMatchers.eq(keyboardDevice.id)
        )
        keyboardLayoutManager.onInputDeviceChanged(keyboardDevice.id)
        ExtendedMockito.verify(
            notificationManager,
            Mockito.never()
        ).notifyAsUser(
            ArgumentMatchers.isNull(),
            ArgumentMatchers.anyInt(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any()
        )
    }

    private fun assertCorrectLayout(
        device: InputDevice,
        imeSubtype: InputMethodSubtype,
        expectedLayout: String
    ) {
        val result = keyboardLayoutManager.getKeyboardLayoutForInputDevice(
            device.identifier, USER_ID, imeInfo, imeSubtype
        )
        assertEquals(
            "getDefaultKeyboardLayoutForInputDevice should return $expectedLayout",
            expectedLayout,
            result.layoutDescriptor
        )
    }

    private fun createImeSubtype(): InputMethodSubtype =
            createImeSubtypeForLanguageTagAndLayoutType(null, null)

    private fun createImeSubtypeForLanguageTag(languageTag: String): InputMethodSubtype =
            createImeSubtypeForLanguageTagAndLayoutType(languageTag, null)

    private fun createImeSubtypeForLanguageTagAndLayoutType(
            languageTag: String?,
            layoutType: String?
    ): InputMethodSubtype {
        val builder = InputMethodSubtype.InputMethodSubtypeBuilder()
                .setSubtypeId(nextImeSubtypeId++)
                .setIsAuxiliary(false)
                .setSubtypeMode("keyboard")
        if (languageTag != null && layoutType != null) {
            builder.setPhysicalKeyboardHint(ULocale.forLanguageTag(languageTag), layoutType)
        } else if (languageTag != null) {
            builder.setLanguageTag(languageTag)
        }
        return builder.build()
    }

    private fun createByteArray(
            expectedLanguageTag: String,
            expectedLayoutType: Int,
            expectedLayoutName: String,
            expectedCriteria: Int,
            expectedImeLanguageTag: String,
            expectedImeLayoutType: Int
    ): ByteArray {
        val proto = ProtoOutputStream()
        val keyboardLayoutConfigToken = proto.start(
                KeyboardConfiguredProto.RepeatedKeyboardLayoutConfig.KEYBOARD_LAYOUT_CONFIG)
        proto.write(
                KeyboardConfiguredProto.KeyboardLayoutConfig.KEYBOARD_LANGUAGE_TAG,
                expectedLanguageTag
        )
        proto.write(
                KeyboardConfiguredProto.KeyboardLayoutConfig.KEYBOARD_LAYOUT_TYPE,
                expectedLayoutType
        )
        proto.write(
                KeyboardConfiguredProto.KeyboardLayoutConfig.KEYBOARD_LAYOUT_NAME,
                expectedLayoutName
        )
        proto.write(
                KeyboardConfiguredProto.KeyboardLayoutConfig.LAYOUT_SELECTION_CRITERIA,
                expectedCriteria
        )
        proto.write(
                KeyboardConfiguredProto.KeyboardLayoutConfig.IME_LANGUAGE_TAG,
                expectedImeLanguageTag
        )
        proto.write(
                KeyboardConfiguredProto.KeyboardLayoutConfig.IME_LAYOUT_TYPE,
                expectedImeLayoutType
        )
        proto.end(keyboardLayoutConfigToken)
        return proto.bytes
    }

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

    private fun containsLayout(layoutList: Array<KeyboardLayout>, layoutDesc: String): Boolean {
        for (kl in layoutList) {
            if (kl.descriptor.equals(layoutDesc)) {
                return true
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
}
