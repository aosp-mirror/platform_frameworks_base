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

package com.android.settingslib.spa.framework.util

import androidx.core.os.bundleOf
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.tests.testutils.createSettingsPage
import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UniqueIdTest {
    @Test
    fun testUniquePageId() {
        Truth.assertThat(genPageId("mySpp")).isEqualTo("1byojwa")

        val parameter = listOf(
            navArgument("string_param") { type = NavType.StringType },
            navArgument("int_param") { type = NavType.IntType },
        )
        val arguments = bundleOf(
            "string_param" to "myStr",
            "int_param" to 10,
        )
        Truth.assertThat(genPageId("mySppWithParam", parameter, arguments)).isEqualTo("1sz4pbq")

        val parameter2 = listOf(
            navArgument("string_param") { type = NavType.StringType },
            navArgument("int_param") { type = NavType.IntType },
            navArgument("rt_param") { type = NavType.StringType },
        )
        val arguments2 = bundleOf(
            "string_param" to "myStr",
            "int_param" to 10,
            "rt_param" to "myRtStr",
        )
        Truth.assertThat(genPageId("mySppWithRtParam", parameter2, arguments2)).isEqualTo("ts6d8k")
    }

    @Test
    fun testUniqueEntryId() {
        val owner = createSettingsPage("mySpp")
        val fromPage = createSettingsPage("fromSpp")
        val toPage = createSettingsPage("toSpp")

        Truth.assertThat(genEntryId("myEntry", owner)).isEqualTo("145pppn")
        Truth.assertThat(genEntryId("myEntry", owner, fromPage = fromPage, toPage = toPage))
            .isEqualTo("1m7jzew")
    }
}