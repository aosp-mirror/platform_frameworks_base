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

package com.android.systemui.qs.tiles.base.analytics

import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.QSEvent
import com.android.systemui.qs.tiles.viewmodel.QSTileConfigTestBuilder
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class QSTileAnalyticsTest : SysuiTestCase() {

    @Mock private lateinit var uiEventLogger: UiEventLogger

    private lateinit var underTest: QSTileAnalytics

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest = QSTileAnalytics(uiEventLogger)
    }

    @Test
    fun testClickIsLogged() {
        underTest.trackUserAction(config, QSTileUserAction.Click(null))

        verify(uiEventLogger)
            .logWithInstanceId(
                eq(QSEvent.QS_ACTION_CLICK),
                eq(0),
                eq("test_spec"),
                eq(InstanceId.fakeInstanceId(0))
            )
    }

    @Test
    fun testLongClickIsLogged() {
        underTest.trackUserAction(config, QSTileUserAction.LongClick(null))

        verify(uiEventLogger)
            .logWithInstanceId(
                eq(QSEvent.QS_ACTION_LONG_PRESS),
                eq(0),
                eq("test_spec"),
                eq(InstanceId.fakeInstanceId(0))
            )
    }

    private companion object {

        val config = QSTileConfigTestBuilder.build()
    }
}
