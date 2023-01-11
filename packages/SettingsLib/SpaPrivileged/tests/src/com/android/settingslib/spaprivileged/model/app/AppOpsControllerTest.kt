/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.model.app

import android.app.AppOpsManager
import android.app.AppOpsManager.MODE_ALLOWED
import android.app.AppOpsManager.MODE_ERRORED
import android.app.AppOpsManager.MODE_IGNORED
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spaprivileged.framework.common.appOpsManager
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@RunWith(AndroidJUnit4::class)
class AppOpsControllerTest {
    @get:Rule val mockito: MockitoRule = MockitoJUnit.rule()

    @Spy private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock private lateinit var appOpsManager: AppOpsManager

    @Before
    fun setUp() {
        whenever(context.appOpsManager).thenReturn(appOpsManager)
    }

    @Test
    fun setAllowed_setToTrue() {
        val controller =
            AppOpsController(
                context = context,
                app = APP,
                op = OP,
            )

        controller.setAllowed(true)

        verify(appOpsManager).setMode(OP, APP.uid, APP.packageName, MODE_ALLOWED)
    }

    @Test
    fun setAllowed_setToFalse() {
        val controller =
            AppOpsController(
                context = context,
                app = APP,
                op = OP,
            )

        controller.setAllowed(false)

        verify(appOpsManager).setMode(OP, APP.uid, APP.packageName, MODE_ERRORED)
    }

    @Test
    fun setAllowed_setToFalseWithModeForNotAllowed() {
        val controller =
            AppOpsController(
                context = context,
                app = APP,
                op = OP,
                modeForNotAllowed = MODE_IGNORED,
            )

        controller.setAllowed(false)

        verify(appOpsManager).setMode(OP, APP.uid, APP.packageName, MODE_IGNORED)
    }

    @Test
    fun setAllowed_setToTrueByUid() {
        val controller =
            AppOpsController(
                context = context,
                app = APP,
                op = OP,
                setModeByUid = true,
            )

        controller.setAllowed(true)

        verify(appOpsManager).setUidMode(OP, APP.uid, MODE_ALLOWED)
    }

    @Test
    fun setAllowed_setToFalseByUid() {
        val controller =
            AppOpsController(
                context = context,
                app = APP,
                op = OP,
                setModeByUid = true,
            )

        controller.setAllowed(false)

        verify(appOpsManager).setUidMode(OP, APP.uid, MODE_ERRORED)
    }

    @Test
    fun getMode() {
        whenever(appOpsManager.checkOpNoThrow(OP, APP.uid, APP.packageName))
            .thenReturn(MODE_ALLOWED)
        val controller =
            AppOpsController(
                context = context,
                app = APP,
                op = OP,
            )

        val mode = controller.getMode()

        assertThat(mode).isEqualTo(MODE_ALLOWED)
    }

    private companion object {
        const val OP = 1
        val APP =
            ApplicationInfo().apply {
                packageName = "package.name"
                uid = 123
            }
    }
}
