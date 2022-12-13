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
import android.os.Bundle
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.common.createSettingsPage
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
    private val pageOwner = spaEnvironment.createPage("SppForSearch")

    @Test
    fun testQueryColumnSetup() {
        Truth.assertThat(QueryEnum.SEARCH_STATIC_DATA_QUERY.columnNames)
            .containsExactlyElementsIn(QueryEnum.SEARCH_DYNAMIC_DATA_QUERY.columnNames)
        Truth.assertThat(QueryEnum.SEARCH_MUTABLE_STATUS_DATA_QUERY.columnNames)
            .containsExactlyElementsIn(QueryEnum.SEARCH_IMMUTABLE_STATUS_DATA_QUERY.columnNames)
        Truth.assertThat(QueryEnum.SEARCH_STATIC_ROW_QUERY.columnNames)
            .containsExactlyElementsIn(QueryEnum.SEARCH_DYNAMIC_ROW_QUERY.columnNames)
    }

    @Test
    fun testQuerySearchStatusData() {
        SpaEnvironmentFactory.reset(spaEnvironment)

        val immutableStatus = searchProvider.querySearchImmutableStatusData()
        Truth.assertThat(immutableStatus.count).isEqualTo(1)
        immutableStatus.moveToFirst()
        immutableStatus.checkValue(
            QueryEnum.SEARCH_IMMUTABLE_STATUS_DATA_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchDynamicWithImmutableStatus")
        )
        immutableStatus.checkValue(
            QueryEnum.SEARCH_IMMUTABLE_STATUS_DATA_QUERY, ColumnEnum.ENTRY_DISABLED, true.toString()
        )

        val mutableStatus = searchProvider.querySearchMutableStatusData()
        Truth.assertThat(mutableStatus.count).isEqualTo(2)
        mutableStatus.moveToFirst()
        mutableStatus.checkValue(
            QueryEnum.SEARCH_MUTABLE_STATUS_DATA_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchStaticWithMutableStatus")
        )
        mutableStatus.checkValue(
            QueryEnum.SEARCH_MUTABLE_STATUS_DATA_QUERY, ColumnEnum.ENTRY_DISABLED, false.toString()
        )

        mutableStatus.moveToNext()
        mutableStatus.checkValue(
            QueryEnum.SEARCH_MUTABLE_STATUS_DATA_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchDynamicWithMutableStatus")
        )
        mutableStatus.checkValue(
            QueryEnum.SEARCH_MUTABLE_STATUS_DATA_QUERY, ColumnEnum.ENTRY_DISABLED, true.toString()
        )
    }

    @Test
    fun testQuerySearchIndexData() {
        SpaEnvironmentFactory.reset(spaEnvironment)

        val staticData = searchProvider.querySearchStaticData()
        Truth.assertThat(staticData.count).isEqualTo(2)
        staticData.moveToFirst()
        staticData.checkValue(
            QueryEnum.SEARCH_STATIC_DATA_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchStaticWithNoStatus")
        )
        staticData.checkValue(
            QueryEnum.SEARCH_STATIC_DATA_QUERY, ColumnEnum.SEARCH_TITLE, "SearchStaticWithNoStatus"
        )
        staticData.checkValue(
            QueryEnum.SEARCH_STATIC_DATA_QUERY, ColumnEnum.SEARCH_KEYWORD, listOf("").toString()
        )
        staticData.checkValue(
            QueryEnum.SEARCH_STATIC_DATA_QUERY,
            ColumnEnum.SEARCH_PATH,
            listOf("SearchStaticWithNoStatus", "SppForSearch").toString()
        )
        staticData.checkValue(
            QueryEnum.SEARCH_STATIC_DATA_QUERY,
            ColumnEnum.INTENT_TARGET_PACKAGE,
            spaEnvironment.appContext.packageName
        )
        staticData.checkValue(
            QueryEnum.SEARCH_STATIC_DATA_QUERY,
            ColumnEnum.INTENT_TARGET_CLASS,
            "com.android.settingslib.spa.tests.testutils.BlankActivity"
        )

        // Check extras in intent
        val bundle =
            staticData.getExtras(QueryEnum.SEARCH_STATIC_DATA_QUERY, ColumnEnum.INTENT_EXTRAS)
        Truth.assertThat(bundle).isNotNull()
        Truth.assertThat(bundle!!.size()).isEqualTo(3)
        Truth.assertThat(bundle.getString("spaActivityDestination")).isEqualTo("SppForSearch")
        Truth.assertThat(bundle.getString("highlightEntry"))
            .isEqualTo(pageOwner.getEntryId("SearchStaticWithNoStatus"))
        Truth.assertThat(bundle.getString("sessionSource")).isEqualTo("search")

        staticData.moveToNext()
        staticData.checkValue(
            QueryEnum.SEARCH_STATIC_DATA_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchStaticWithMutableStatus")
        )

        val dynamicData = searchProvider.querySearchDynamicData()
        Truth.assertThat(dynamicData.count).isEqualTo(2)
        dynamicData.moveToFirst()
        dynamicData.checkValue(
            QueryEnum.SEARCH_DYNAMIC_DATA_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchDynamicWithMutableStatus")
        )

        dynamicData.moveToNext()
        dynamicData.checkValue(
            QueryEnum.SEARCH_DYNAMIC_DATA_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchDynamicWithImmutableStatus")
        )
        dynamicData.checkValue(
            QueryEnum.SEARCH_DYNAMIC_DATA_QUERY,
            ColumnEnum.SEARCH_KEYWORD,
            listOf("kw1", "kw2").toString()
        )
    }

    @Test
    fun testQuerySearchIndexRow() {
        SpaEnvironmentFactory.reset(spaEnvironment)

        val staticRow = searchProvider.querySearchStaticRow()
        Truth.assertThat(staticRow.count).isEqualTo(1)
        staticRow.moveToFirst()
        staticRow.checkValue(
            QueryEnum.SEARCH_STATIC_ROW_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchStaticWithNoStatus")
        )
        staticRow.checkValue(
            QueryEnum.SEARCH_STATIC_ROW_QUERY, ColumnEnum.SEARCH_TITLE, "SearchStaticWithNoStatus"
        )
        staticRow.checkValue(
            QueryEnum.SEARCH_STATIC_ROW_QUERY, ColumnEnum.SEARCH_KEYWORD, listOf("").toString()
        )
        staticRow.checkValue(
            QueryEnum.SEARCH_STATIC_ROW_QUERY,
            ColumnEnum.SEARCH_PATH,
            listOf("SearchStaticWithNoStatus", "SppForSearch").toString()
        )
        staticRow.checkValue(
            QueryEnum.SEARCH_STATIC_ROW_QUERY,
            ColumnEnum.INTENT_TARGET_PACKAGE,
            spaEnvironment.appContext.packageName
        )
        staticRow.checkValue(
            QueryEnum.SEARCH_STATIC_ROW_QUERY,
            ColumnEnum.INTENT_TARGET_CLASS,
            "com.android.settingslib.spa.tests.testutils.BlankActivity"
        )

        // Check extras in intent
        val bundle =
            staticRow.getExtras(QueryEnum.SEARCH_STATIC_ROW_QUERY, ColumnEnum.INTENT_EXTRAS)
        Truth.assertThat(bundle).isNotNull()
        Truth.assertThat(bundle!!.size()).isEqualTo(3)
        Truth.assertThat(bundle.getString("spaActivityDestination")).isEqualTo("SppForSearch")
        Truth.assertThat(bundle.getString("highlightEntry"))
            .isEqualTo(pageOwner.getEntryId("SearchStaticWithNoStatus"))
        Truth.assertThat(bundle.getString("sessionSource")).isEqualTo("search")

        Truth.assertThat(
            staticRow.getString(
                QueryEnum.SEARCH_STATIC_ROW_QUERY.columnNames.indexOf(
                    ColumnEnum.ENTRY_DISABLED
                )
            )
        ).isNull()

        val dynamicRow = searchProvider.querySearchDynamicRow()
        Truth.assertThat(dynamicRow.count).isEqualTo(3)
        dynamicRow.moveToFirst()
        dynamicRow.checkValue(
            QueryEnum.SEARCH_DYNAMIC_ROW_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchStaticWithMutableStatus")
        )
        dynamicRow.checkValue(
            QueryEnum.SEARCH_DYNAMIC_ROW_QUERY, ColumnEnum.ENTRY_DISABLED, false.toString()
        )

        dynamicRow.moveToNext()
        dynamicRow.checkValue(
            QueryEnum.SEARCH_DYNAMIC_ROW_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchDynamicWithMutableStatus")
        )
        dynamicRow.checkValue(
            QueryEnum.SEARCH_DYNAMIC_ROW_QUERY, ColumnEnum.ENTRY_DISABLED, true.toString()
        )


        dynamicRow.moveToNext()
        dynamicRow.checkValue(
            QueryEnum.SEARCH_DYNAMIC_ROW_QUERY,
            ColumnEnum.ENTRY_ID,
            pageOwner.getEntryId("SearchDynamicWithImmutableStatus")
        )
        dynamicRow.checkValue(
            QueryEnum.SEARCH_DYNAMIC_ROW_QUERY,
            ColumnEnum.SEARCH_KEYWORD,
            listOf("kw1", "kw2").toString()
        )
        dynamicRow.checkValue(
            QueryEnum.SEARCH_DYNAMIC_ROW_QUERY, ColumnEnum.ENTRY_DISABLED, true.toString()
        )
    }
}

private fun Cursor.checkValue(query: QueryEnum, column: ColumnEnum, value: String) {
    Truth.assertThat(getString(query.columnNames.indexOf(column))).isEqualTo(value)
}

private fun Cursor.getExtras(query: QueryEnum, column: ColumnEnum): Bundle? {
    val extrasByte = getBlob(query.columnNames.indexOf(column)) ?: return null
    val parcel = Parcel.obtain()
    parcel.unmarshall(extrasByte, 0, extrasByte.size)
    parcel.setDataPosition(0)
    val bundle = Bundle()
    bundle.readFromParcel(parcel)
    return bundle
}

private fun SettingsPage.getEntryId(name: String): String {
    return SettingsEntryBuilder.create(this, name).build().id
}
