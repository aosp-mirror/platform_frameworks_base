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

package com.android.systemui.temporarydisplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TemporaryViewUiEventLoggerTest : SysuiTestCase() {
    private lateinit var uiEventLoggerFake: UiEventLoggerFake
    private lateinit var logger: TemporaryViewUiEventLogger

    @Before
    fun setup() {
        uiEventLoggerFake = UiEventLoggerFake()
        logger = TemporaryViewUiEventLogger(uiEventLoggerFake)
    }

    @Test
    fun testViewAdded() {
        logger.logViewAdded(InstanceId.fakeInstanceId(123))

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(TemporaryViewUiEvent.TEMPORARY_VIEW_ADDED.id)
    }

    @Test
    fun testMultipleViewsAdded_differentInstanceIds() {
        logger.logViewAdded(logger.getNewInstanceId())
        logger.logViewAdded(logger.getNewInstanceId())

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(2)
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(TemporaryViewUiEvent.TEMPORARY_VIEW_ADDED.id)
        assertThat(uiEventLoggerFake.eventId(1))
            .isEqualTo(TemporaryViewUiEvent.TEMPORARY_VIEW_ADDED.id)
        assertThat(uiEventLoggerFake.logs[0].instanceId.id)
            .isNotEqualTo(uiEventLoggerFake.logs[1].instanceId.id)
    }

    @Test
    fun testViewManuallyDismissed() {
        logger.logViewManuallyDismissed(InstanceId.fakeInstanceId(123))

        assertThat(uiEventLoggerFake.numLogs()).isEqualTo(1)
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(TemporaryViewUiEvent.TEMPORARY_VIEW_MANUALLY_DISMISSED.id)
    }
}
