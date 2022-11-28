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

package com.android.settingslib.spa.search

import android.content.Context
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.common.ColumnEnum
import com.android.settingslib.spa.framework.common.QueryEnum
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.common.getIndex
import com.android.settingslib.spa.tests.testutils.SpaEnvironmentForTest
import com.android.settingslib.spa.tests.testutils.SppForSearch
import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpaSearchProviderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spaEnvironment =
        SpaEnvironmentForTest(context, listOf(SppForSearch.createSettingsPage()))
    private val searchProvider = SpaSearchProvider()

    @Test
    fun testQuerySearchStatusData() {
        SpaEnvironmentFactory.reset(spaEnvironment)
        val pageOwner = spaEnvironment.createPage("SppForSearch")

        val immutableStatus = searchProvider.querySearchImmutableStatusData()
        Truth.assertThat(immutableStatus.count).isEqualTo(1)
        immutableStatus.moveToFirst()
        immutableStatus.checkValue(
            QueryEnum.SEARCH_IMMUTABLE_STATUS_DATA_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchDynamicWithImmutableStatus")
        )

        val mutableStatus = searchProvider.querySearchMutableStatusData()
        Truth.assertThat(mutableStatus.count).isEqualTo(2)
        mutableStatus.moveToFirst()
        mutableStatus.checkValue(
            QueryEnum.SEARCH_IMMUTABLE_STATUS_DATA_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchStaticWithMutableStatus")
        )

        mutableStatus.moveToNext()
        mutableStatus.checkValue(
            QueryEnum.SEARCH_IMMUTABLE_STATUS_DATA_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchDynamicWithMutableStatus")
        )
    }

    @Test
    fun testQuerySearchIndexData() {
        SpaEnvironmentFactory.reset(spaEnvironment)
        val staticData = searchProvider.querySearchStaticData()
        Truth.assertThat(staticData.count).isEqualTo(2)

        val dynamicData = searchProvider.querySearchDynamicData()
        Truth.assertThat(dynamicData.count).isEqualTo(2)
    }
}

private fun Cursor.checkValue(query: QueryEnum, column: ColumnEnum, value: String) {
    Truth.assertThat(getString(query.getIndex(column))).isEqualTo(value)
}

private fun SettingsPage.getEntryId(name: String): String {
    return SettingsEntryBuilder.create(this, name).build().id
}
