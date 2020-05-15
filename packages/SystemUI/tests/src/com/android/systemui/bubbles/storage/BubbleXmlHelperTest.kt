/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.bubbles.storage

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@SmallTest
@RunWith(AndroidTestingRunner::class)
class BubbleXmlHelperTest : SysuiTestCase() {

    private val bubbles = listOf(
        BubbleEntity(0, "com.example.messenger", "shortcut-1"),
        BubbleEntity(10, "com.example.chat", "alice and bob"),
        BubbleEntity(0, "com.example.messenger", "shortcut-2")
    )

    @Test
    fun testWriteXml() {
        val expectedEntries = """
            <bb uid="0" pkg="com.example.messenger" sid="shortcut-1" />
            <bb uid="10" pkg="com.example.chat" sid="alice and bob" />
            <bb uid="0" pkg="com.example.messenger" sid="shortcut-2" />
        """.trimIndent()
        ByteArrayOutputStream().use {
            writeXml(it, bubbles)
            val actual = it.toString()
            assertTrue("cannot find expected entry in \n$actual",
                    actual.contains(expectedEntries))
        }
    }

    @Test
    fun testReadXml() {
        val src = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <bs>
            <bb uid="0" pkg="com.example.messenger" sid="shortcut-1" />
            <bb uid="10" pkg="com.example.chat" sid="alice and bob" />
            <bb uid="0" pkg="com.example.messenger" sid="shortcut-2" />
            </bs>
        """.trimIndent()
        val actual = readXml(ByteArrayInputStream(src.toByteArray(Charsets.UTF_8)))
        assertEquals("failed parsing bubbles from xml\n$src", bubbles, actual)
    }
}