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

package com.android.systemui.util.kotlin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.DisposableHandle
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DisposableHandlesTest : SysuiTestCase() {
    @Test
    fun disposeWorksOnce() {
        var handleDisposeCount = 0
        val underTest = DisposableHandles()

        // Add a handle
        underTest += DisposableHandle { handleDisposeCount++ }

        // dispose() calls dispose() on children
        underTest.dispose()
        assertThat(handleDisposeCount).isEqualTo(1)

        // Once disposed, children are not disposed again
        underTest.dispose()
        assertThat(handleDisposeCount).isEqualTo(1)
    }

    @Test
    fun replaceCallsDispose() {
        var handleDisposeCount1 = 0
        var handleDisposeCount2 = 0
        val underTest = DisposableHandles()
        val handle1 = DisposableHandle { handleDisposeCount1++ }
        val handle2 = DisposableHandle { handleDisposeCount2++ }

        // First add handle1
        underTest += handle1

        // replace() calls dispose() on existing children
        underTest.replaceAll(handle2)
        assertThat(handleDisposeCount1).isEqualTo(1)
        assertThat(handleDisposeCount2).isEqualTo(0)

        // Once disposed, replaced children are not disposed again
        underTest.dispose()
        assertThat(handleDisposeCount1).isEqualTo(1)
        assertThat(handleDisposeCount2).isEqualTo(1)
    }
}
