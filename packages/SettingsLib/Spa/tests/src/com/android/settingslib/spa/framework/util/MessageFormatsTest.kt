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

package com.android.settingslib.spa.framework.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.test.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageFormatsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun formatString_one() {
        val message = context.formatString(R.string.test_quantity_strings, "count" to 1)

        assertThat(message).isEqualTo("There is one song found.")
    }

    @Test
    fun formatString_other() {
        val message = context.formatString(R.string.test_quantity_strings, "count" to 2)

        assertThat(message).isEqualTo("There are 2 songs found.")
    }

    @Test
    fun formatString_withParam_one() {
        val message = context.formatString(
            R.string.test_quantity_strings_with_param,
            "count" to 1,
            "place" to "phone",
        )

        assertThat(message).isEqualTo("There is one song found in phone.")
    }

    @Test
    fun formatString_withParam_other() {
        val message = context.formatString(
            R.string.test_quantity_strings_with_param,
            "count" to 2,
            "place" to "phone",
        )

        assertThat(message).isEqualTo("There are 2 songs found in phone.")
    }
}
