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
package com.android.systemui.qs.tiles.dialog

import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSTileHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tiles.FontScalingTile
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
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

    private lateinit var testableLooper: TestableLooper
    private lateinit var fontScalingTile: FontScalingTile

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        `when`(qsHost.getContext()).thenReturn(mContext)
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
                dialogLaunchAnimator
            )
        fontScalingTile.initialize()
    }

    @Test
    fun isNotAvailable_whenNotSupportedDevice_returnsFalse() {
        val isAvailable = fontScalingTile.isAvailable()

        assertThat(isAvailable).isFalse()
    }
}
