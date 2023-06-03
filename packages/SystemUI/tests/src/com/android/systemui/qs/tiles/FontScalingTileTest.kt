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
package com.android.systemui.qs.tiles

import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSTileHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class FontScalingTileTest : SysuiTestCase() {
    @Mock private lateinit var qsHost: QSTileHost
    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var qsLogger: QSLogger
    @Mock private lateinit var dialogLaunchAnimator: DialogLaunchAnimator
    @Mock private lateinit var uiEventLogger: UiEventLogger

    private lateinit var testableLooper: TestableLooper
    private lateinit var fontScalingTile: FontScalingTile

    val featureFlags = FakeFeatureFlags()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        `when`(qsHost.getContext()).thenReturn(mContext)
        `when`(qsHost.uiEventLogger).thenReturn(uiEventLogger)

        fontScalingTile =
            FontScalingTile(
                qsHost,
                testableLooper.looper,
                Handler(testableLooper.looper),
                FalsingManagerFake(),
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                dialogLaunchAnimator,
                FakeSettings(),
                featureFlags
            )
        fontScalingTile.initialize()
        testableLooper.processAllMessages()
    }

    @After
    fun tearDown() {
        fontScalingTile.destroy()
        testableLooper.processAllMessages()
    }

    @Test
    fun isAvailable_whenFlagIsFalse_returnsFalse() {
        featureFlags.set(Flags.ENABLE_FONT_SCALING_TILE, false)

        val isAvailable = fontScalingTile.isAvailable()

        assertThat(isAvailable).isFalse()
    }

    @Test
    fun isAvailable_whenFlagIsTrue_returnsTrue() {
        featureFlags.set(Flags.ENABLE_FONT_SCALING_TILE, true)

        val isAvailable = fontScalingTile.isAvailable()

        assertThat(isAvailable).isTrue()
    }

    @Test
    fun clickTile_showDialog() {
        val view = View(context)
        fontScalingTile.click(view)
        testableLooper.processAllMessages()

        verify(dialogLaunchAnimator).showFromView(any(), eq(view), nullable(), anyBoolean())
    }
}
