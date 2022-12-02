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

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.os.bundleOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.tests.testutils.getUniqueEntryId
import com.android.settingslib.spa.tests.testutils.getUniquePageId
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

const val INJECT_ENTRY_NAME = "INJECT"
const val ROOT_ENTRY_NAME = "ROOT"

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
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testBuildBasic() {
        val owner = SettingsPage.create("mySpp")
        val entry = SettingsEntryBuilder.create(owner, "myEntry").build()
        assertThat(entry.id).isEqualTo(getUniqueEntryId("myEntry", owner))
        assertThat(entry.displayName).isEqualTo("myEntry")
        assertThat(entry.owner.sppName).isEqualTo("mySpp")
        assertThat(entry.owner.displayName).isEqualTo("mySpp")
        assertThat(entry.fromPage).isNull()
        assertThat(entry.toPage).isNull()
        assertThat(entry.isAllowSearch).isFalse()
        assertThat(entry.isSearchDataDynamic).isFalse()
        assertThat(entry.hasMutableStatus).isFalse()
        assertThat(entry.hasSliceSupport).isFalse()
    }

    @Test
    fun testBuildWithLink() {
        val owner = SettingsPage.create("mySpp")
        val fromPage = SettingsPage.create("fromSpp")
        val toPage = SettingsPage.create("toSpp")
        val entryFrom = SettingsEntryBuilder.createLinkFrom("myEntry", owner)
            .setLink(toPage = toPage).build()
        assertThat(entryFrom.id).isEqualTo(getUniqueEntryId("myEntry", owner, owner, toPage))
        assertThat(entryFrom.displayName).isEqualTo("myEntry")
        assertThat(entryFrom.fromPage!!.sppName).isEqualTo("mySpp")
        assertThat(entryFrom.toPage!!.sppName).isEqualTo("toSpp")

        val entryTo = SettingsEntryBuilder.createLinkTo("myEntry", owner)
            .setLink(fromPage = fromPage).build()
        assertThat(entryTo.id).isEqualTo(getUniqueEntryId("myEntry", owner, fromPage, owner))
        assertThat(entryTo.displayName).isEqualTo("myEntry")
        assertThat(entryTo.fromPage!!.sppName).isEqualTo("fromSpp")
        assertThat(entryTo.toPage!!.sppName).isEqualTo("mySpp")
    }

    @Test
    fun testBuildInject() {
        val owner = SettingsPage.create("mySpp")
        val entryInject = SettingsEntryBuilder.createInject(owner).build()
        assertThat(entryInject.id).isEqualTo(
            getUniqueEntryId(
                INJECT_ENTRY_NAME,
                owner,
                toPage = owner
            )
        )
        assertThat(entryInject.displayName).isEqualTo("${INJECT_ENTRY_NAME}_mySpp")
        assertThat(entryInject.fromPage).isNull()
        assertThat(entryInject.toPage).isNotNull()
    }

    @Test
    fun testBuildRoot() {
        val owner = SettingsPage.create("mySpp")
        val entryInject = SettingsEntryBuilder.createRoot(owner, "myRootEntry").build()
        assertThat(entryInject.id).isEqualTo(
            getUniqueEntryId(
                ROOT_ENTRY_NAME,
                owner,
                toPage = owner
            )
        )
        assertThat(entryInject.displayName).isEqualTo("myRootEntry")
        assertThat(entryInject.fromPage).isNull()
        assertThat(entryInject.toPage).isNotNull()
    }

    @Test
    fun testSetAttributes() {
        val owner = SettingsPage.create("mySpp")
        val entryBuilder = SettingsEntryBuilder.create(owner, "myEntry")
            .setDisplayName("myEntryDisplay")
            .setIsSearchDataDynamic(false)
            .setHasMutableStatus(true)
            .setSearchDataFn { null }
            .setSliceDataFn { _, _ -> null }
        val entry = entryBuilder.build()
        assertThat(entry.id).isEqualTo(getUniqueEntryId("myEntry", owner))
        assertThat(entry.displayName).isEqualTo("myEntryDisplay")
        assertThat(entry.fromPage).isNull()
        assertThat(entry.toPage).isNull()
        assertThat(entry.isAllowSearch).isTrue()
        assertThat(entry.isSearchDataDynamic).isFalse()
        assertThat(entry.hasMutableStatus).isTrue()
        assertThat(entry.hasSliceSupport).isTrue()

        val entry2 = entryBuilder.clearSearchDataFn().build()
        assertThat(entry2.isAllowSearch).isFalse()
    }

    @Test
    fun testSetMarco() {
        val owner = SettingsPage.create("mySpp", arguments = bundleOf("param" to "v1"))
        val entry = SettingsEntryBuilder.create(owner, "myEntry")
            .setMacro {
                assertThat(it?.getString("param")).isEqualTo("v1")
                assertThat(it?.getString("rtParam")).isEqualTo("v2")
                assertThat(it?.getString("unknown")).isNull()
                MacroForTest(getUniquePageId("mySpp"), getUniqueEntryId("myEntry", owner))
            }
            .build()

        val rtArguments = bundleOf("rtParam" to "v2")
        composeTestRule.setContent { entry.UiLayout(rtArguments) }
        assertThat(entry.isAllowSearch).isTrue()
        assertThat(entry.isSearchDataDynamic).isFalse()
        assertThat(entry.hasMutableStatus).isFalse()
        assertThat(entry.hasSliceSupport).isFalse()
        val searchData = entry.getSearchData(rtArguments)
        val statusData = entry.getStatusData(rtArguments)
        assertThat(searchData?.title).isEqualTo("myTitle")
        assertThat(searchData?.keyword).isEmpty()
        assertThat(statusData?.isDisabled).isTrue()
        assertThat(statusData?.isSwitchOff).isTrue()
    }
}
