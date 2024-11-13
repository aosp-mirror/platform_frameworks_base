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
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.os.bundleOf
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.util.genEntryId
import com.android.settingslib.spa.framework.util.genPageId
import com.android.settingslib.spa.tests.testutils.SpaEnvironmentForTest
import com.android.settingslib.spa.tests.testutils.createSettingsPage
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

const val INJECT_ENTRY_NAME_TEST = "INJECT"
const val ROOT_ENTRY_NAME_TEST = "ROOT"

class MacroForTest(private val pageId: String, private val entryId: String) : EntryMacro {
    @Composable
    override fun UiLayout() {
        val entryData = LocalEntryDataProvider.current
        assertThat(entryData.isHighlighted).isFalse()
        assertThat(entryData.pageId).isEqualTo(pageId)
        assertThat(entryData.entryId).isEqualTo(entryId)
    }

    override fun getSearchData(): EntrySearchData {
        return EntrySearchData("myTitle")
    }

    override fun getStatusData(): EntryStatusData {
        return EntryStatusData(isDisabled = true, isSwitchOff = true)
    }
}

@RunWith(AndroidJUnit4::class)
class SettingsEntryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spaEnvironment = SpaEnvironmentForTest(context)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testBuildBasic() {
        val owner = createSettingsPage("mySpp")
        val entry = SettingsEntryBuilder.create(owner, "myEntry").build()
        assertThat(entry.id).isEqualTo(genEntryId("myEntry", owner))
        assertThat(entry.label).isEqualTo("myEntry")
        assertThat(entry.owner.sppName).isEqualTo("mySpp")
        assertThat(entry.owner.displayName).isEqualTo("mySpp")
        assertThat(entry.fromPage).isNull()
        assertThat(entry.toPage).isNull()
        assertThat(entry.isAllowSearch).isFalse()
        assertThat(entry.isSearchDataDynamic).isFalse()
        assertThat(entry.hasMutableStatus).isFalse()
    }

    @Test
    fun testBuildWithLink() {
        val owner = createSettingsPage("mySpp")
        val fromPage = createSettingsPage("fromSpp")
        val toPage = createSettingsPage("toSpp")
        val entryFrom =
            SettingsEntryBuilder.createLinkFrom("myEntry", owner).setLink(toPage = toPage).build()
        assertThat(entryFrom.id).isEqualTo(genEntryId("myEntry", owner, owner, toPage))
        assertThat(entryFrom.label).isEqualTo("myEntry")
        assertThat(entryFrom.fromPage!!.sppName).isEqualTo("mySpp")
        assertThat(entryFrom.toPage!!.sppName).isEqualTo("toSpp")

        val entryTo =
            SettingsEntryBuilder.createLinkTo("myEntry", owner).setLink(fromPage = fromPage).build()
        assertThat(entryTo.id).isEqualTo(genEntryId("myEntry", owner, fromPage, owner))
        assertThat(entryTo.label).isEqualTo("myEntry")
        assertThat(entryTo.fromPage!!.sppName).isEqualTo("fromSpp")
        assertThat(entryTo.toPage!!.sppName).isEqualTo("mySpp")
    }

    @Test
    fun testBuildInject() {
        val owner = createSettingsPage("mySpp")
        val entryInject = SettingsEntryBuilder.createInject(owner).build()
        assertThat(entryInject.id).isEqualTo(
            genEntryId(
                INJECT_ENTRY_NAME_TEST, owner, toPage = owner
            )
        )
        assertThat(entryInject.label).isEqualTo("${INJECT_ENTRY_NAME_TEST}_mySpp")
        assertThat(entryInject.fromPage).isNull()
        assertThat(entryInject.toPage).isNotNull()
    }

    @Test
    fun testBuildRoot() {
        val owner = createSettingsPage("mySpp")
        val entryInject = SettingsEntryBuilder.createRoot(owner, "myRootEntry").build()
        assertThat(entryInject.id).isEqualTo(
            genEntryId(
                ROOT_ENTRY_NAME_TEST, owner, toPage = owner
            )
        )
        assertThat(entryInject.label).isEqualTo("myRootEntry")
        assertThat(entryInject.fromPage).isNull()
        assertThat(entryInject.toPage).isNotNull()
    }

    @Test
    fun testSetAttributes() {
        SpaEnvironmentFactory.reset(spaEnvironment)
        val owner = createSettingsPage("SppHome")
        val entryBuilder =
            SettingsEntryBuilder.create(owner, "myEntry")
                .setLabel("myEntryDisplay")
                .setIsSearchDataDynamic(false)
                .setHasMutableStatus(true)
                .setSearchDataFn { null }
        val entry = entryBuilder.build()
        assertThat(entry.id).isEqualTo(genEntryId("myEntry", owner))
        assertThat(entry.label).isEqualTo("myEntryDisplay")
        assertThat(entry.fromPage).isNull()
        assertThat(entry.toPage).isNull()
        assertThat(entry.isAllowSearch).isTrue()
        assertThat(entry.isSearchDataDynamic).isFalse()
        assertThat(entry.hasMutableStatus).isTrue()

        // Test disabled Spp
        val ownerDisabled = createSettingsPage("SppDisabled")
        val entryBuilderDisabled =
            SettingsEntryBuilder.create(ownerDisabled, "myEntry")
                .setLabel("myEntryDisplay")
                .setIsSearchDataDynamic(false)
                .setHasMutableStatus(true)
                .setSearchDataFn { null }
        val entryDisabled = entryBuilderDisabled.build()
        assertThat(entryDisabled.id).isEqualTo(genEntryId("myEntry", ownerDisabled))
        assertThat(entryDisabled.label).isEqualTo("myEntryDisplay")
        assertThat(entryDisabled.fromPage).isNull()
        assertThat(entryDisabled.toPage).isNull()
        assertThat(entryDisabled.isAllowSearch).isFalse()
        assertThat(entryDisabled.isSearchDataDynamic).isFalse()
        assertThat(entryDisabled.hasMutableStatus).isTrue()

        // Clear search data fn
        val entry2 = entryBuilder.clearSearchDataFn().build()
        assertThat(entry2.isAllowSearch).isFalse()

        // Clear SppHome in spa environment
        SpaEnvironmentFactory.reset()
        val entry3 = entryBuilder.build()
        assertThat(entry3.id).isEqualTo(genEntryId("myEntry", owner))
        assertThat(entry3.label).isEqualTo("myEntryDisplay")
        assertThat(entry3.fromPage).isNull()
        assertThat(entry3.toPage).isNull()
        assertThat(entry3.isAllowSearch).isFalse()
        assertThat(entry3.isSearchDataDynamic).isFalse()
        assertThat(entry3.hasMutableStatus).isTrue()
    }

    @Test
    fun testSetMarco() {
        SpaEnvironmentFactory.reset(spaEnvironment)
        val owner = createSettingsPage("SppHome", arguments = bundleOf("param" to "v1"))
        val entry = SettingsEntryBuilder.create(owner, "myEntry").setMacro {
            assertThat(it?.getString("param")).isEqualTo("v1")
            assertThat(it?.getString("rtParam")).isEqualTo("v2")
            assertThat(it?.getString("unknown")).isNull()
            MacroForTest(genPageId("SppHome"), genEntryId("myEntry", owner))
        }.build()

        val rtArguments = bundleOf("rtParam" to "v2")
        composeTestRule.setContent { entry.UiLayout(rtArguments) }
        assertThat(entry.isAllowSearch).isTrue()
        assertThat(entry.isSearchDataDynamic).isFalse()
        assertThat(entry.hasMutableStatus).isFalse()
        val searchData = entry.getSearchData(rtArguments)
        val statusData = entry.getStatusData(rtArguments)
        assertThat(searchData?.title).isEqualTo("myTitle")
        assertThat(searchData?.keyword).isEmpty()
        assertThat(statusData?.isDisabled).isTrue()
        assertThat(statusData?.isSwitchOff).isTrue()
    }
}
