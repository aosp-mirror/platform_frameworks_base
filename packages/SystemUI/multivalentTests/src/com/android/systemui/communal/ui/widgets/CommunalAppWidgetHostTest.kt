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

package com.android.systemui.communal.ui.widgets

import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.communal.widgets.CommunalAppWidgetHostView
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class CommunalAppWidgetHostTest : SysuiTestCase() {

    private lateinit var testableLooper: TestableLooper
    private lateinit var underTest: CommunalAppWidgetHost

    @Before
    fun setUp() {
        testableLooper = TestableLooper.get(this)
        underTest =
            CommunalAppWidgetHost(
                context = context,
                hostId = 116,
                interactionHandler = mock(),
                looper = testableLooper.looper
            )
    }

    @Test
    fun createViewForCommunal_returnCommunalAppWidgetView() {
        val appWidgetId = 789
        val view =
            underTest.createViewForCommunal(
                context = context,
                appWidgetId = appWidgetId,
                appWidget = null
            )
        testableLooper.processAllMessages()

        assertThat(view).isInstanceOf(CommunalAppWidgetHostView::class.java)
        assertThat(view).isNotNull()
        assertThat(view.appWidgetId).isEqualTo(appWidgetId)
    }
}
