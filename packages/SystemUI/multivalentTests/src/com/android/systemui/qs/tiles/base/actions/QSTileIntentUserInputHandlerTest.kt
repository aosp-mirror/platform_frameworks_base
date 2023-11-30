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

package com.android.systemui.qs.tiles.base.actions

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class QSTileIntentUserInputHandlerTest : SysuiTestCase() {

    @Mock private lateinit var activityStarted: ActivityStarter

    lateinit var underTest: QSTileIntentUserInputHandler

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest = QSTileIntentUserInputHandlerImpl(activityStarted)
    }

    @Test
    fun testPassesIntentToStarter() {
        val intent = Intent("test.ACTION")

        underTest.handle(null, intent)

        verify(activityStarted).postStartActivityDismissingKeyguard(eq(intent), eq(0), any())
    }
}
