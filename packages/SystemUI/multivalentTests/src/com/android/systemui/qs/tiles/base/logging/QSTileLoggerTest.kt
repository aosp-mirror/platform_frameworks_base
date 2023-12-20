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

package com.android.systemui.qs.tiles.base.logging

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.log.LogcatEchoTrackerAlways
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class QSTileLoggerTest : SysuiTestCase() {

    @Mock private lateinit var statusBarController: StatusBarStateController
    @Mock private lateinit var logBufferFactory: LogBufferFactory

    private val chattyLogBuffer = LogBuffer("TestChatty", 5, LogcatEchoTrackerAlways())
    private val logBuffer = LogBuffer("Test", 1, LogcatEchoTrackerAlways())

    private lateinit var underTest: QSTileLogger

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(logBufferFactory.create(any(), any(), any())).thenReturn(logBuffer)
        val tileSpec: TileSpec = TileSpec.create("chatty_tile")
        underTest =
            QSTileLogger(mapOf(tileSpec to chattyLogBuffer), logBufferFactory, statusBarController)
    }

    @Test
    fun testChattyLog() {
        underTest.logUserActionRejectedByFalsing(
            QSTileUserAction.Click(null),
            TileSpec.create("chatty_tile"),
        )
        underTest.logUserActionRejectedByFalsing(
            QSTileUserAction.Click(null),
            TileSpec.create("chatty_tile"),
        )

        val logs = chattyLogBuffer.getStringBuffer().lines().filter { it.isNotBlank() }
        assertThat(logs).hasSize(2)
        logs.forEach { assertThat(it).contains("tile click: rejected by falsing") }
    }

    @Test
    fun testLogUserAction() {
        underTest.logUserAction(
            QSTileUserAction.Click(null),
            TileSpec.create("test_spec"),
            hasData = false,
            hasTileState = false,
        )

        assertThat(logBuffer.getStringBuffer())
            .contains("tile click: statusBarState=SHADE, hasState=false, hasData=false")
    }

    @Test
    fun testLogUserActionRejectedByFalsing() {
        underTest.logUserActionRejectedByFalsing(
            QSTileUserAction.Click(null),
            TileSpec.create("test_spec"),
        )

        assertThat(logBuffer.getStringBuffer()).contains("tile click: rejected by falsing")
    }

    @Test
    fun testLogUserActionRejectedByPolicy() {
        underTest.logUserActionRejectedByPolicy(
            QSTileUserAction.Click(null),
            TileSpec.create("test_spec"),
        )

        assertThat(logBuffer.getStringBuffer()).contains("tile click: rejected by policy")
    }

    @Test
    fun testLogUserActionPipeline() {
        underTest.logUserActionPipeline(
            TileSpec.create("test_spec"),
            QSTileUserAction.Click(null),
            QSTileState.build({ Icon.Resource(0, ContentDescription.Resource(0)) }, "") {},
            "test_data",
        )

        assertThat(logBuffer.getStringBuffer())
            .contains(
                "tile click pipeline: " +
                    "statusBarState=SHADE, " +
                    "state=[" +
                    "label=, " +
                    "state=INACTIVE, " +
                    "s_label=null, " +
                    "cd=null, " +
                    "sd=null, " +
                    "svi=None, " +
                    "enabled=ENABLED, " +
                    "a11y=android.widget.Switch" +
                    "], " +
                    "data=test_data"
            )
    }

    @Test
    fun testLogStateUpdate() {
        underTest.logStateUpdate(
            TileSpec.create("test_spec"),
            QSTileState.build({ Icon.Resource(0, ContentDescription.Resource(0)) }, "") {},
            "test_data",
        )

        assertThat(logBuffer.getStringBuffer())
            .contains(
                "tile state update: " +
                    "state=[label=, " +
                    "state=INACTIVE, " +
                    "s_label=null, " +
                    "cd=null, " +
                    "sd=null, " +
                    "svi=None, " +
                    "enabled=ENABLED, " +
                    "a11y=android.widget.Switch], " +
                    "data=test_data"
            )
    }

    @Test
    fun testLogForceUpdate() {
        underTest.logForceUpdate(
            TileSpec.create("test_spec"),
        )

        assertThat(logBuffer.getStringBuffer()).contains("tile data force update")
    }

    @Test
    fun testLogInitialUpdate() {
        underTest.logInitialRequest(
            TileSpec.create("test_spec"),
        )

        assertThat(logBuffer.getStringBuffer()).contains("tile data initial update")
    }

    private fun LogBuffer.getStringBuffer(): String {
        val stringWriter = StringWriter()
        dump(PrintWriter(stringWriter), 0)
        return stringWriter.buffer.toString()
    }
}
