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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.util.genEntryId
import com.android.settingslib.spa.framework.util.genPageId
import com.android.settingslib.spa.tests.testutils.SpaEnvironmentForTest
import com.android.settingslib.spa.tests.testutils.SppDialog
import com.android.settingslib.spa.tests.testutils.SppHome
import com.android.settingslib.spa.tests.testutils.SppLayer1
import com.android.settingslib.spa.tests.testutils.SppLayer2
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsEntryRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spaEnvironment =
        SpaEnvironmentForTest(context, listOf(SppHome.createSettingsPage()))
    private val entryRepository by spaEnvironment.entryRepository

    @Test
    fun testGetPageWithEntry() {
        val pageWithEntry = entryRepository.getAllPageWithEntry()

        assertThat(pageWithEntry).hasSize(4)
        assertThat(entryRepository.getPageWithEntry(genPageId("SppHome"))?.entries)
            .hasSize(2)
        assertThat(entryRepository.getPageWithEntry(genPageId("SppLayer1"))?.entries)
            .hasSize(3)
        assertThat(entryRepository.getPageWithEntry(genPageId("SppLayer2"))?.entries)
            .hasSize(2)
        assertThat(entryRepository.getPageWithEntry(genPageId("SppWithParam"))).isNull()
    }

    @Test
    fun testGetEntry() {
        val entry = entryRepository.getAllEntries()
        assertThat(entry).hasSize(8)
        assertThat(
            entryRepository.getEntry(
                genEntryId(
                    "ROOT",
                    SppHome.createSettingsPage(),
                    NullPageProvider.createSettingsPage(),
                    SppHome.createSettingsPage(),
                )
            )
        ).isNotNull()
        assertThat(
            entryRepository.getEntry(
                genEntryId(
                    "INJECT",
                    SppLayer1.createSettingsPage(),
                    SppHome.createSettingsPage(),
                    SppLayer1.createSettingsPage(),
                )
            )
        ).isNotNull()
        assertThat(
            entryRepository.getEntry(
                genEntryId(
                    "INJECT",
                    SppLayer2.createSettingsPage(),
                    SppLayer1.createSettingsPage(),
                    SppLayer2.createSettingsPage(),
                )
            )
        ).isNotNull()
        assertThat(
            entryRepository.getEntry(
                genEntryId(
                    "INJECT",
                    SppDialog.createSettingsPage(),
                    SppHome.createSettingsPage(),
                    SppDialog.createSettingsPage(),
                )
            )
        ).isNotNull()
        assertThat(
            entryRepository.getEntry(
                genEntryId("Layer1Entry1", SppLayer1.createSettingsPage())
            )
        ).isNotNull()
        assertThat(
            entryRepository.getEntry(
                genEntryId("Layer1Entry2", SppLayer1.createSettingsPage())
            )
        ).isNotNull()
        assertThat(
            entryRepository.getEntry(
                genEntryId("Layer2Entry1", SppLayer2.createSettingsPage())
            )
        ).isNotNull()
        assertThat(
            entryRepository.getEntry(
                genEntryId("Layer2Entry2", SppLayer2.createSettingsPage())
            )
        ).isNotNull()
    }

    @Test
    fun testGetEntryPath() {
        SpaEnvironmentFactory.reset(spaEnvironment)
        assertThat(
            entryRepository.getEntryPathWithLabel(
                genEntryId("Layer2Entry1", SppLayer2.createSettingsPage())
            )
        ).containsExactly("Layer2Entry1", "INJECT_SppLayer2", "INJECT_SppLayer1", "ROOT_SppHome")
            .inOrder()

        assertThat(
            entryRepository.getEntryPathWithTitle(
                genEntryId("Layer2Entry2", SppLayer2.createSettingsPage()),
                "entryTitle"
            )
        ).containsExactly("entryTitle", "SppLayer2", "TitleLayer1", "TitleHome").inOrder()

        assertThat(
            entryRepository.getEntryPathWithLabel(
                genEntryId(
                    "INJECT",
                    SppLayer1.createSettingsPage(),
                    SppHome.createSettingsPage(),
                    SppLayer1.createSettingsPage(),
                )
            )
        ).containsExactly("INJECT_SppLayer1", "ROOT_SppHome").inOrder()

        assertThat(
            entryRepository.getEntryPathWithTitle(
                genEntryId(
                    "INJECT",
                    SppLayer2.createSettingsPage(),
                    SppLayer1.createSettingsPage(),
                    SppLayer2.createSettingsPage(),
                ),
                "defaultTitle"
            )
        ).containsExactly("SppLayer2", "TitleLayer1", "TitleHome").inOrder()
    }
}
