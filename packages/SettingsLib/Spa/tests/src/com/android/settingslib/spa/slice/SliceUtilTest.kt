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

package com.android.settingslib.spa.slice

import android.net.Uri
import androidx.core.os.bundleOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SliceUtilTest {
    @Test
    fun sliceUriTest() {
        assertThat(Uri.EMPTY.getEntryId()).isNull()

        // valid slice uri
        val dest = "myRoute"
        val entryId = "myEntry"
        val sliceUriWithoutParams = Uri.Builder().appendSpaParams(dest, entryId).build()
        assertThat(sliceUriWithoutParams.getEntryId()).isEqualTo(entryId)

        val sliceUriWithParams =
            Uri.Builder().appendSpaParams(dest, entryId, bundleOf("p1" to "v1")).build()
        assertThat(sliceUriWithParams.getEntryId()).isEqualTo(entryId)
    }
}