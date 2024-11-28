/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.policy.ui.dialog

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.QSModesEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class ModesDialogEventLoggerTest : SysuiTestCase() {

    private val uiEventLogger = UiEventLoggerFake()
    private val underTest = ModesDialogEventLogger(uiEventLogger)

    @Test
    fun testLogModeOn_manual() {
        underTest.logModeOn(TestModeBuilder.MANUAL_DND_INACTIVE)

        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        uiEventLogger[0].match(QSModesEvent.QS_MODES_DND_ON, "android")
    }

    @Test
    fun testLogModeOff_manual() {
        underTest.logModeOff(TestModeBuilder.MANUAL_DND_ACTIVE)

        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        uiEventLogger[0].match(QSModesEvent.QS_MODES_DND_OFF, "android")
    }

    @Test
    fun testLogModeSettings_manual() {
        underTest.logModeSettings(TestModeBuilder.MANUAL_DND_ACTIVE)

        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        uiEventLogger[0].match(QSModesEvent.QS_MODES_DND_SETTINGS, "android")
    }

    @Test
    fun testLogModeOn_automatic() {
        underTest.logModeOn(TestModeBuilder().setActive(true).setPackage("pkg1").build())

        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        uiEventLogger[0].match(QSModesEvent.QS_MODES_MODE_ON, "pkg1")
    }

    @Test
    fun testLogModeOff_automatic() {
        underTest.logModeOff(TestModeBuilder().setActive(false).setPackage("pkg2").build())

        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        uiEventLogger[0].match(QSModesEvent.QS_MODES_MODE_OFF, "pkg2")
    }

    @Test
    fun testLogModeSettings_automatic() {
        underTest.logModeSettings(TestModeBuilder().setPackage("pkg3").build())

        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        uiEventLogger[0].match(QSModesEvent.QS_MODES_MODE_SETTINGS, "pkg3")
    }

    @Test
    fun testLogOpenDurationDialog_manual() {
        underTest.logOpenDurationDialog(TestModeBuilder.MANUAL_DND_INACTIVE)

        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        // package not logged for duration dialog as it only applies to manual mode
        uiEventLogger[0].match(QSModesEvent.QS_MODES_DURATION_DIALOG, null)
    }

    @Test
    fun testLogOpenDurationDialog_automatic_doesNotLog() {
        underTest.logOpenDurationDialog(
            TestModeBuilder().setActive(false).setPackage("mypkg").build()
        )

        // ignore calls to open dialog on something other than the manual rule (shouldn't happen)
        assertThat(uiEventLogger.numLogs()).isEqualTo(0)
    }

    @Test
    fun testLogDialogSettings() {
        underTest.logDialogSettings()
        assertThat(uiEventLogger.numLogs()).isEqualTo(1)
        uiEventLogger[0].match(QSModesEvent.QS_MODES_SETTINGS, null)
    }

    private fun UiEventLoggerFake.FakeUiEvent.match(event: QSModesEvent, modePackage: String?) {
        assertThat(eventId).isEqualTo(event.id)
        assertThat(packageName).isEqualTo(modePackage)
    }
}
