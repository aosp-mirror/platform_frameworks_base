/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.privacy

import android.support.test.filters.SmallTest
import android.support.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class PrivacyDialogBuilderTest : SysuiTestCase() {

    companion object {
        val MILLIS_IN_MINUTE: Long = 1000 * 60
        val NOW = 4 * MILLIS_IN_MINUTE
    }

    @Test
    fun testGenerateText_multipleApps() {
        val bar2 = PrivacyItem(Privacy.TYPE_CAMERA, PrivacyApplication(
                "Bar", context), 2 * MILLIS_IN_MINUTE)
        val bar3 = PrivacyItem(Privacy.TYPE_LOCATION, PrivacyApplication(
                "Bar", context), 3 * MILLIS_IN_MINUTE)
        val foo0 = PrivacyItem(Privacy.TYPE_CAMERA, PrivacyApplication(
                "Foo", context), 0)
        val baz1 = PrivacyItem(Privacy.TYPE_CAMERA, PrivacyApplication(
                "Baz", context), 1 * MILLIS_IN_MINUTE)

        val items = listOf(bar2, foo0, baz1, bar3)

        val textBuilder = PrivacyDialogBuilder(context, items)

        val textList = textBuilder.generateText(NOW)
        assertEquals(2, textList.size)
        assertEquals("Bar, Foo, Baz are using your camera", textList[0])
        assertEquals("Bar is using your location for the last 1 min", textList[1])
    }

    @Test
    fun testGenerateText_singleApp() {
        val bar2 = PrivacyItem(Privacy.TYPE_CAMERA, PrivacyApplication(
                "Bar", context), 0)
        val bar1 = PrivacyItem(Privacy.TYPE_LOCATION, PrivacyApplication(
                "Bar", context), 0)

        val items = listOf(bar2, bar1)

        val textBuilder = PrivacyDialogBuilder(context, items)
        val textList = textBuilder.generateText(NOW)
        assertEquals(1, textList.size)
        assertEquals("Bar is using your camera, location", textList[0])
    }

    @Test
    fun testGenerateText_singleApp_singleType() {
        val bar2 = PrivacyItem(Privacy.TYPE_CAMERA, PrivacyApplication(
                "Bar", context), 2 * MILLIS_IN_MINUTE)
        val items = listOf(bar2)
        val textBuilder = PrivacyDialogBuilder(context, items)
        val textList = textBuilder.generateText(NOW)
        assertEquals(1, textList.size)
        assertEquals("Bar is using your camera for the last 2 min", textList[0])
    }
}