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

package com.android.systemui.qs.tiles

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.statusbar.policy.ZenModeController
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.settings.SecureSettings
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import java.io.File

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class DndTileTest : SysuiTestCase() {

    companion object {
        private const val DEFAULT_USER = 0
        private const val KEY = Settings.Secure.ZEN_DURATION
    }

    @Mock
    private lateinit var qsHost: QSHost
    @Mock
    private lateinit var metricsLogger: MetricsLogger
    @Mock
    private lateinit var statusBarStateController: StatusBarStateController
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var qsLogger: QSLogger
    @Mock
    private lateinit var uiEventLogger: UiEventLogger
    @Mock
    private lateinit var zenModeController: ZenModeController
    @Mock
    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var secureSettings: SecureSettings
    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: DndTile

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        secureSettings = FakeSettings()

        Mockito.`when`(qsHost.userId).thenReturn(DEFAULT_USER)
        Mockito.`when`(qsHost.uiEventLogger).thenReturn(uiEventLogger)

        val wrappedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(file: File?, mode: Int): SharedPreferences {
                return sharedPreferences
            }
        }
        Mockito.`when`(qsHost.context).thenReturn(wrappedContext)

        tile = DndTile(
            qsHost,
            testableLooper.looper,
            Handler(testableLooper.looper),
            FalsingManagerFake(),
            metricsLogger,
            statusBarStateController,
            activityStarter,
            qsLogger,
            zenModeController,
            sharedPreferences,
            secureSettings
        )
    }

    @After
    fun tearDown() {
        tile.handleSetListening(false)
    }

    @Test
    fun testForceExpandIcon_durationAskAlways_true() {
        secureSettings.putIntForUser(KEY, Settings.Secure.ZEN_DURATION_PROMPT, DEFAULT_USER)

        tile.refreshState()
        testableLooper.processAllMessages()

        assertThat(tile.state.forceExpandIcon).isTrue()
    }

    @Test
    fun testForceExpandIcon_durationNotAskAlways_false() {
        secureSettings.putIntForUser(KEY, 60, DEFAULT_USER)

        tile.refreshState()
        testableLooper.processAllMessages()

        assertThat(tile.state.forceExpandIcon).isFalse()
    }

    @Test
    fun testForceExpandIcon_changeWhileListening() {
        secureSettings.putIntForUser(KEY, 60, DEFAULT_USER)

        tile.refreshState()
        testableLooper.processAllMessages()

        assertThat(tile.state.forceExpandIcon).isFalse()

        tile.handleSetListening(true)

        secureSettings.putIntForUser(KEY, Settings.Secure.ZEN_DURATION_PROMPT, DEFAULT_USER)
        testableLooper.processAllMessages()

        assertThat(tile.state.forceExpandIcon).isTrue()
    }
}