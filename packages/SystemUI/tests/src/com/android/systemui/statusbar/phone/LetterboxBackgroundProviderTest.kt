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

package com.android.systemui.statusbar.phone

import android.graphics.Color
import android.testing.AndroidTestingRunner
import android.view.IWindowManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class LetterboxBackgroundProviderTest : SysuiTestCase() {

    private val fakeSystemClock = FakeSystemClock()
    private val fakeExecutor = FakeExecutor(fakeSystemClock)

    @Mock private lateinit var windowManager: IWindowManager
    @Mock private lateinit var dumpManager: DumpManager

    private lateinit var provider: LetterboxBackgroundProvider

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        provider = LetterboxBackgroundProvider(windowManager, fakeExecutor, dumpManager)
    }

    @Test
    fun letterboxBackgroundColor_defaultValue_returnsBlack() {
        assertThat(provider.letterboxBackgroundColor).isEqualTo(Color.BLACK)
    }

    @Test
    fun letterboxBackgroundColor_afterOnStart_executorNotDone_returnsDefaultValue() {
        whenever(windowManager.letterboxBackgroundColorInArgb).thenReturn(Color.RED)

        provider.start()

        assertThat(provider.letterboxBackgroundColor).isEqualTo(Color.BLACK)
    }

    @Test
    fun letterboxBackgroundColor_afterOnStart_executorDone_returnsValueFromWindowManager() {
        whenever(windowManager.letterboxBackgroundColorInArgb).thenReturn(Color.RED)

        provider.start()
        fakeExecutor.runAllReady()

        assertThat(provider.letterboxBackgroundColor).isEqualTo(Color.RED)
    }

    @Test
    fun isLetterboxBackgroundMultiColored_defaultValue_returnsFalse() {
        assertThat(provider.isLetterboxBackgroundMultiColored).isEqualTo(false)
    }
    @Test
    fun isLetterboxBackgroundMultiColored_afterOnStart_executorNotDone_returnsDefaultValue() {
        whenever(windowManager.isLetterboxBackgroundMultiColored).thenReturn(true)

        provider.start()

        assertThat(provider.isLetterboxBackgroundMultiColored).isFalse()
    }

    @Test
    fun isBackgroundMultiColored_afterOnStart_executorDone_returnsValueFromWindowManager() {
        whenever(windowManager.isLetterboxBackgroundMultiColored).thenReturn(true)

        provider.start()
        fakeExecutor.runAllReady()

        assertThat(provider.isLetterboxBackgroundMultiColored).isTrue()
    }
}
