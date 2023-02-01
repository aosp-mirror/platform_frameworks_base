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

import androidx.core.os.bundleOf
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParameterTest {
    @Test
    fun navRouteTest() {
        val navArguments = listOf(
            navArgument("string_param") { type = NavType.StringType },
            navArgument("int_param") { type = NavType.IntType },
        )

        val route = navArguments.navRoute()
        assertThat(route).isEqualTo("/{string_param}/{int_param}")
    }

    @Test
    fun navLinkTest() {
        val navArguments = listOf(
            navArgument("string_param") { type = NavType.StringType },
            navArgument("int_param") { type = NavType.IntType },
        )

        val unsetAllLink = navArguments.navLink()
        assertThat(unsetAllLink).isEqualTo("/[unset]/[unset]")

        val setAllLink = navArguments.navLink(
            bundleOf(
                "string_param" to "myStr",
                "int_param" to 10,
            )
        )
        assertThat(setAllLink).isEqualTo("/myStr/10")

        val setUnknownLink = navArguments.navLink(
            bundleOf(
                "string_param" to "myStr",
                "int_param" to 10,
                "unknown_param" to "unknown",
            )
        )
        assertThat(setUnknownLink).isEqualTo("/myStr/10")

        val setWrongTypeLink = navArguments.navLink(
            bundleOf(
                "string_param" to "myStr",
                "int_param" to "wrongStr",
            )
        )
        assertThat(setWrongTypeLink).isEqualTo("/myStr/0")
    }

    @Test
    fun normalizeTest() {
        val emptyArguments = emptyList<NamedNavArgument>()
        assertThat(emptyArguments.normalize()).isNull()

        val navArguments = listOf(
            navArgument("string_param") { type = NavType.StringType },
            navArgument("int_param") { type = NavType.IntType },
            navArgument("rt_param") { type = NavType.StringType },
        )

        val emptyParam = navArguments.normalize()
        assertThat(emptyParam).isNotNull()
        assertThat(emptyParam.toString()).isEqualTo(
            "Bundle[{unset_rt_param=null, unset_string_param=null, unset_int_param=null}]"
        )

        val setPartialParam = navArguments.normalize(
            bundleOf(
                "string_param" to "myStr",
                "rt_param" to "rtStr",
            )
        )
        assertThat(setPartialParam).isNotNull()
        assertThat(setPartialParam.toString()).isEqualTo(
            "Bundle[{rt_param=rtStr, string_param=myStr, unset_int_param=null}]"
        )

        val setAllParam = navArguments.normalize(
            bundleOf(
                "string_param" to "myStr",
                "int_param" to 10,
                "rt_param" to "rtStr",
            ),
            eraseRuntimeValues = true
        )
        assertThat(setAllParam).isNotNull()
        assertThat(setAllParam.toString()).isEqualTo(
            "Bundle[{rt_param=null, int_param=10, string_param=myStr}]"
        )
    }

    @Test
    fun getArgTest() {
        val navArguments = listOf(
            navArgument("string_param") { type = NavType.StringType },
            navArgument("int_param") { type = NavType.IntType },
        )

        assertThat(
            navArguments.getStringArg(
                "string_param", bundleOf(
                    "string_param" to "myStr",
                )
            )
        ).isEqualTo("myStr")

        assertThat(
            navArguments.getStringArg(
                "string_param", bundleOf(
                    "string_param" to 10,
                )
            )
        ).isNull()

        assertThat(
            navArguments.getStringArg(
                "unknown_param", bundleOf(
                    "string_param" to "myStr",
                )
            )
        ).isNull()

        assertThat(navArguments.getStringArg("string_param")).isNull()

        assertThat(
            navArguments.getIntArg(
                "int_param", bundleOf(
                    "int_param" to 10,
                )
            )
        ).isEqualTo(10)

        assertThat(
            navArguments.getIntArg(
                "int_param", bundleOf(
                    "int_param" to "10",
                )
            )
        ).isEqualTo(0)

        assertThat(
            navArguments.getIntArg(
                "unknown_param", bundleOf(
                    "int_param" to 10,
                )
            )
        ).isNull()

        assertThat(navArguments.getIntArg("int_param")).isNull()
    }

    @Test
    fun isRuntimeParamTest() {
        val regularParam = navArgument("regular_param") { type = NavType.StringType }
        val rtParam = navArgument("rt_param") { type = NavType.StringType }
        assertThat(regularParam.isRuntimeParam()).isFalse()
        assertThat(rtParam.isRuntimeParam()).isTrue()
    }
}
