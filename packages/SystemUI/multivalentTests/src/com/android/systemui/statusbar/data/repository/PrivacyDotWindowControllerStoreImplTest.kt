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

package com.android.systemui.statusbar.data.repository

import android.platform.test.annotations.EnableFlags
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.testKosmos
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
class PrivacyDotWindowControllerStoreImplTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val underTest by lazy { kosmos.privacyDotWindowControllerStoreImpl }

    @Before
    fun installDisplays() = runBlocking {
        kosmos.displayRepository.addDisplay(displayId = Display.DEFAULT_DISPLAY)
        kosmos.displayRepository.addDisplay(displayId = Display.DEFAULT_DISPLAY + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun forDisplay_defaultDisplay_throws() {
        underTest.forDisplay(displayId = Display.DEFAULT_DISPLAY)
    }

    @Test
    fun forDisplay_nonDefaultDisplay_doesNotThrow() {
        underTest.forDisplay(displayId = Display.DEFAULT_DISPLAY + 1)
    }
}
