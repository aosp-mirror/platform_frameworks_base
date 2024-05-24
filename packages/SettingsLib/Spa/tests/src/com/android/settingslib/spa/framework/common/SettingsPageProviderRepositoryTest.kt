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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.tests.testutils.createSettingsPage
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPageProviderRepositoryTest {
    @Test
    fun rootPages_empty() {
        val sppRepoEmpty = SettingsPageProviderRepository(emptyList())

        assertThat(sppRepoEmpty.getDefaultStartPage()).isEqualTo("")
        assertThat(sppRepoEmpty.getAllRootPages()).isEmpty()
    }

    @Test
    fun rootPages_single() {
        val nullPage = NullPageProvider.createSettingsPage()

        val sppRepoNull = SettingsPageProviderRepository(
            allPageProviders = emptyList(),
            rootPages = listOf(nullPage),
        )

        assertThat(sppRepoNull.getDefaultStartPage()).isEqualTo("NULL")
        assertThat(sppRepoNull.getAllRootPages()).containsExactly(nullPage)
    }

    @Test
    fun rootPages_twoPages() {
        val rootPage1 = createSettingsPage(sppName = "Spp1", displayName = "Spp1")
        val rootPage2 = createSettingsPage(sppName = "Spp2", displayName = "Spp2")

        val sppRepo = SettingsPageProviderRepository(
            allPageProviders = emptyList(),
            rootPages = listOf(rootPage1, rootPage2),
        )

        assertThat(sppRepo.getDefaultStartPage()).isEqualTo("Spp1")
        assertThat(sppRepo.getAllRootPages()).containsExactly(rootPage1, rootPage2)
    }

    @Test
    fun getProviderOrNull_empty() {
        val sppRepoEmpty = SettingsPageProviderRepository(emptyList())
        assertThat(sppRepoEmpty.getAllProviders()).isEmpty()
        assertThat(sppRepoEmpty.getProviderOrNull("Spp")).isNull()
    }

    @Test
    fun getProviderOrNull_single() {
        val sppRepo = SettingsPageProviderRepository(listOf(
            object : SettingsPageProvider {
                override val name = "Spp"
            }
        ))
        assertThat(sppRepo.getAllProviders()).hasSize(1)
        assertThat(sppRepo.getProviderOrNull("Spp")).isNotNull()
        assertThat(sppRepo.getProviderOrNull("SppUnknown")).isNull()
    }
}
