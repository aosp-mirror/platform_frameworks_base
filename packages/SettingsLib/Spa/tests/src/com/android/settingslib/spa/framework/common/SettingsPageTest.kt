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

package com.android.settingslib.spa.framework.common

import android.content.Context
import androidx.core.os.bundleOf
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.util.genPageId
import com.android.settingslib.spa.tests.testutils.SpaEnvironmentForTest
import com.android.settingslib.spa.tests.testutils.createSettingsPage
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spaEnvironment = SpaEnvironmentForTest(context)

    @Test
    fun testNullPage() {
        val page = NullPageProvider.createSettingsPage()
        assertThat(page.id).isEqualTo(genPageId("NULL"))
        assertThat(page.sppName).isEqualTo("NULL")
        assertThat(page.displayName).isEqualTo("NULL")
        assertThat(page.buildRoute()).isEqualTo("NULL")
        assertThat(page.isCreateBy("NULL")).isTrue()
        assertThat(page.isCreateBy("Spp")).isFalse()
        assertThat(page.isBrowsable()).isFalse()
    }

    @Test
    fun testRegularPage() {
        val page = createSettingsPage("mySpp", "SppDisplayName")
        assertThat(page.id).isEqualTo(genPageId("mySpp"))
        assertThat(page.sppName).isEqualTo("mySpp")
        assertThat(page.displayName).isEqualTo("SppDisplayName")
        assertThat(page.buildRoute()).isEqualTo("mySpp")
        assertThat(page.isCreateBy("NULL")).isFalse()
        assertThat(page.isCreateBy("mySpp")).isTrue()
        assertThat(page.isBrowsable()).isTrue()
    }

    @Test
    fun testParamPage() {
        val arguments = bundleOf(
            "string_param" to "myStr",
            "int_param" to 10,
        )
        val page = spaEnvironment.createPage("SppWithParam", arguments)
        assertThat(page.id).isEqualTo(
            genPageId(
                "SppWithParam", listOf(
                    navArgument("string_param") { type = NavType.StringType },
                    navArgument("int_param") { type = NavType.IntType },
                ), arguments
            )
        )
        assertThat(page.sppName).isEqualTo("SppWithParam")
        assertThat(page.displayName).isEqualTo("SppWithParam/myStr/10")
        assertThat(page.buildRoute()).isEqualTo("SppWithParam/myStr/10")
        assertThat(page.isCreateBy("SppWithParam")).isTrue()
        assertThat(page.isBrowsable()).isTrue()
    }

    @Test
    fun testRtParamPage() {
        val arguments = bundleOf(
            "string_param" to "myStr",
            "int_param" to 10,
            "rt_param" to "rtStr",
        )
        val page = spaEnvironment.createPage("SppWithRtParam", arguments)
        assertThat(page.id).isEqualTo(
            genPageId(
                "SppWithRtParam", listOf(
                    navArgument("string_param") { type = NavType.StringType },
                    navArgument("int_param") { type = NavType.IntType },
                    navArgument("rt_param") { type = NavType.StringType },
                ), arguments
            )
        )
        assertThat(page.sppName).isEqualTo("SppWithRtParam")
        assertThat(page.displayName).isEqualTo("SppWithRtParam/myStr/10")
        assertThat(page.buildRoute()).isEqualTo("SppWithRtParam/myStr/10/rtStr")
        assertThat(page.isCreateBy("SppWithRtParam")).isTrue()
        assertThat(page.isBrowsable()).isFalse()
    }
}
