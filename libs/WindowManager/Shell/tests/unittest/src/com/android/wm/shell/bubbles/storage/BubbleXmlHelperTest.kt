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

package com.android.wm.shell.bubbles.storage

import android.app.ActivityTaskManager.INVALID_TASK_ID
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@SmallTest
@RunWith(AndroidTestingRunner::class)
class BubbleXmlHelperTest : ShellTestCase() {

    private val bubbles = listOf(
            // user, package, shortcut, notification key, height, res-height, title, taskId, locusId
            BubbleEntity(0, "com.example.messenger", "shortcut-1", "k1", 120, 0, null, 1),
            BubbleEntity(10, "com.example.chat", "alice and bob", "k2", 0, 16537428, "title",
                    2, null),
            BubbleEntity(0, "com.example.messenger", "shortcut-2", "k3", 120, 0, null,
                    INVALID_TASK_ID, "l3")
    )

    @Test
    fun testWriteXml() {
        val expectedEntries = """
<bb uid="0" pkg="com.example.messenger" sid="shortcut-1" key="k1" h="120" hid="0" tid="1" />
<bb uid="10" pkg="com.example.chat" sid="alice and bob" key="k2" h="0" hid="16537428" t="title" tid="2" />
<bb uid="0" pkg="com.example.messenger" sid="shortcut-2" key="k3" h="120" hid="0" tid="-1" l="l3" />
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
<bs v="1">
<bb uid="0" pkg="com.example.messenger" sid="shortcut-1" key="k1" h="120" hid="0" tid="1" />
<bb uid="10" pkg="com.example.chat" sid="alice and bob" key="k2" h="0" hid="16537428" t="title" tid="2" />
<bb uid="0" pkg="com.example.messenger" sid="shortcut-2" key="k3" h="120" hid="0" tid="-1" l="l3" />
</bs>
        """.trimIndent()
        val actual = readXml(ByteArrayInputStream(src.toByteArray(Charsets.UTF_8)))
        assertEquals("failed parsing bubbles from xml\n$src", bubbles, actual)
    }

    // TODO: We should handle upgrades gracefully but this is v1
    @Test
    fun testUpgradeDropsPreviousData() {
        val src = """
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<bs>
<bb uid="0" pkg="com.example.messenger" sid="shortcut-1" key="k1" h="120" hid="0" />
<bb uid="10" pkg="com.example.chat" sid="alice and bob" key="k2" h="0" hid="16537428" t="title" />
<bb uid="0" pkg="com.example.messenger" sid="shortcut-2" key="k3" h="120" hid="0" />
</bs>
        """.trimIndent()
        val actual = readXml(ByteArrayInputStream(src.toByteArray(Charsets.UTF_8)))
        assertEquals("failed parsing bubbles from xml\n$src", emptyList<BubbleEntity>(), actual)
    }

    /**
     * In S we changed the XML to include a taskId, version didn't increase because we can set a
     * reasonable default for taskId (INVALID_TASK_ID) if it wasn't in the XML previously, this
     * tests that that works.
     */
    @Test
    fun testReadXMLWithoutTaskId() {
        val expectedBubbles = listOf(
                BubbleEntity(0, "com.example.messenger", "shortcut-1", "k1", 120, 0, null,
                        INVALID_TASK_ID),
                BubbleEntity(10, "com.example.chat", "alice and bob", "k2", 0, 16537428, "title",
                        INVALID_TASK_ID),
                BubbleEntity(0, "com.example.messenger", "shortcut-2", "k3", 120, 0, null,
                        INVALID_TASK_ID)
        )
        val src = """
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<bs v="1">
<bb uid="0" pkg="com.example.messenger" sid="shortcut-1" key="k1" h="120" hid="0" />
<bb uid="10" pkg="com.example.chat" sid="alice and bob" key="k2" h="0" hid="16537428" t="title" />
<bb uid="0" pkg="com.example.messenger" sid="shortcut-2" key="k3" h="120" hid="0" />
</bs>
        """.trimIndent()
        val actual = readXml(ByteArrayInputStream(src.toByteArray(Charsets.UTF_8)))
        assertEquals("failed parsing bubbles from xml\n$src", expectedBubbles, actual)
    }

    /**
     * LocusId is optional so it can be added without a version change, this test makes sure that
     * works.
     */
    @Test
    fun testXMLWithoutLocusToLocus() {
        val expectedBubbles = listOf(
            BubbleEntity(0, "com.example.messenger", "shortcut-1", "k1", 120, 0, null,
                    INVALID_TASK_ID, null),
            BubbleEntity(10, "com.example.chat", "alice and bob", "k2", 0, 16537428, "title",
                    INVALID_TASK_ID, null),
            BubbleEntity(0, "com.example.messenger", "shortcut-2", "k3", 120, 0, null,
                    INVALID_TASK_ID, null)
        )
        val src = """
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<bs v="1">
<bb uid="0" pkg="com.example.messenger" sid="shortcut-1" key="k1" h="120" hid="0" />
<bb uid="10" pkg="com.example.chat" sid="alice and bob" key="k2" h="0" hid="16537428" t="title" />
<bb uid="0" pkg="com.example.messenger" sid="shortcut-2" key="k3" h="120" hid="0" />
</bs>
        """.trimIndent()
        val actual = readXml(ByteArrayInputStream(src.toByteArray(Charsets.UTF_8)))
        assertEquals("failed parsing bubbles from xml\n$src", expectedBubbles, actual)
    }
}