/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.external

import android.app.StatusBarManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.InstanceIdSequenceFake
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class TileRequestDialogEventLoggerTest : SysuiTestCase() {

    companion object {
        private const val PACKAGE_NAME = "package"
    }

    private lateinit var uiEventLogger: UiEventLoggerFake
    private val instanceIdSequence =
            InstanceIdSequenceFake(TileRequestDialogEventLogger.MAX_INSTANCE_ID)
    private lateinit var logger: TileRequestDialogEventLogger

    @Before
    fun setUp() {
        uiEventLogger = UiEventLoggerFake()

        logger = TileRequestDialogEventLogger(uiEventLogger, instanceIdSequence)
    }

    @Test
    fun testInstanceIdsFromSequence() {
        (1..10).forEach {
            assertThat(logger.newInstanceId().id).isEqualTo(instanceIdSequence.lastInstanceId)
        }
    }

    @Test
    fun testLogTileAlreadyAdded() {
        val instanceId = instanceIdSequence.newInstanceId()
        logger.logTileAlreadyAdded(PACKAGE_NAME, instanceId)

        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        uiEventLogger[0].match(
                TileRequestDialogEvent.TILE_REQUEST_DIALOG_TILE_ALREADY_ADDED,
                instanceId
        )
    }

    @Test
    fun testLogDialogShown() {
        val instanceId = instanceIdSequence.newInstanceId()
        logger.logDialogShown(PACKAGE_NAME, instanceId)

        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        uiEventLogger[0].match(TileRequestDialogEvent.TILE_REQUEST_DIALOG_SHOWN, instanceId)
    }

    @Test
    fun testLogDialogDismissed() {
        val instanceId = instanceIdSequence.newInstanceId()
        logger.logUserResponse(
                StatusBarManager.TILE_ADD_REQUEST_RESULT_DIALOG_DISMISSED,
                PACKAGE_NAME,
                instanceId
        )

        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        uiEventLogger[0].match(TileRequestDialogEvent.TILE_REQUEST_DIALOG_DISMISSED, instanceId)
    }

    @Test
    fun testLogDialogTileNotAdded() {
        val instanceId = instanceIdSequence.newInstanceId()
        logger.logUserResponse(
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED,
                PACKAGE_NAME,
                instanceId
        )

        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        uiEventLogger[0]
                .match(TileRequestDialogEvent.TILE_REQUEST_DIALOG_TILE_NOT_ADDED, instanceId)
    }

    @Test
    fun testLogDialogTileAdded() {
        val instanceId = instanceIdSequence.newInstanceId()
        logger.logUserResponse(
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                PACKAGE_NAME,
                instanceId
        )

        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        uiEventLogger[0].match(TileRequestDialogEvent.TILE_REQUEST_DIALOG_TILE_ADDED, instanceId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testLogResponseInvalid_throws() {
        val instanceId = instanceIdSequence.newInstanceId()
        logger.logUserResponse(
                -1,
                PACKAGE_NAME,
                instanceId
        )
    }

    private fun UiEventLoggerFake.FakeUiEvent.match(
        event: UiEventLogger.UiEventEnum,
        instanceId: InstanceId
    ) {
        assertThat(eventId).isEqualTo(event.id)
        assertThat(uid).isEqualTo(0)
        assertThat(packageName).isEqualTo(PACKAGE_NAME)
        assertThat(this.instanceId).isEqualTo(instanceId)
    }
}