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

package com.android.systemui.communal.data.db

import android.content.ComponentName
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.lifecycle.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalWidgetDaoTest : SysuiTestCase() {
    @JvmField @Rule val instantTaskExecutor = InstantTaskExecutorRule()

    private lateinit var db: CommunalDatabase
    private lateinit var communalWidgetDao: CommunalWidgetDao

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    @Throws(IOException::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        db =
            Room.inMemoryDatabaseBuilder(context, CommunalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        communalWidgetDao = db.communalWidgetDao()
    }

    @After
    @Throws(IOException::class)
    fun teardown() {
        db.close()
    }

    @Test
    fun addWidget_readValueInDb() =
        testScope.runTest {
            val (widgetId, provider, priority) = widgetInfo1
            communalWidgetDao.addWidget(
                widgetId = widgetId,
                provider = provider,
                priority = priority,
            )
            val entry = communalWidgetDao.getWidgetByIdNow(id = 1)
            assertThat(entry).isEqualTo(communalWidgetItemEntry1)
        }

    @Test
    fun addWidget_emitsActiveWidgetsInDb(): Unit =
        testScope.runTest {
            val widgetsToAdd = listOf(widgetInfo1, widgetInfo2)
            val widgets = collectLastValue(communalWidgetDao.getWidgets())
            widgetsToAdd.forEach {
                val (widgetId, provider, priority) = it
                communalWidgetDao.addWidget(
                    widgetId = widgetId,
                    provider = provider,
                    priority = priority,
                )
            }
            assertThat(widgets())
                .containsExactly(
                    communalItemRankEntry1,
                    communalWidgetItemEntry1,
                    communalItemRankEntry2,
                    communalWidgetItemEntry2
                )
        }

    @Test
    fun deleteWidget_emitsActiveWidgetsInDb() =
        testScope.runTest {
            val widgetsToAdd = listOf(widgetInfo1, widgetInfo2)
            val widgets = collectLastValue(communalWidgetDao.getWidgets())

            widgetsToAdd.forEach {
                val (widgetId, provider, priority) = it
                communalWidgetDao.addWidget(
                    widgetId = widgetId,
                    provider = provider,
                    priority = priority,
                )
            }
            assertThat(widgets())
                .containsExactly(
                    communalItemRankEntry1,
                    communalWidgetItemEntry1,
                    communalItemRankEntry2,
                    communalWidgetItemEntry2
                )

            communalWidgetDao.deleteWidgetById(communalWidgetItemEntry1.widgetId)
            assertThat(widgets()).containsExactly(communalItemRankEntry2, communalWidgetItemEntry2)
        }

    data class FakeWidgetMetadata(
        val widgetId: Int,
        val provider: ComponentName,
        val priority: Int
    )

    companion object {
        val widgetInfo1 =
            FakeWidgetMetadata(
                widgetId = 1,
                provider = ComponentName("pk_name", "cls_name_1"),
                priority = 1
            )
        val widgetInfo2 =
            FakeWidgetMetadata(
                widgetId = 2,
                provider = ComponentName("pk_name", "cls_name_2"),
                priority = 2
            )
        val communalItemRankEntry1 = CommunalItemRank(uid = 1L, rank = widgetInfo1.priority)
        val communalItemRankEntry2 = CommunalItemRank(uid = 2L, rank = widgetInfo2.priority)
        val communalWidgetItemEntry1 =
            CommunalWidgetItem(
                uid = 1L,
                widgetId = widgetInfo1.widgetId,
                componentName = widgetInfo1.provider.flattenToString(),
                itemId = communalItemRankEntry1.uid,
            )
        val communalWidgetItemEntry2 =
            CommunalWidgetItem(
                uid = 2L,
                widgetId = widgetInfo2.widgetId,
                componentName = widgetInfo2.provider.flattenToString(),
                itemId = communalItemRankEntry2.uid,
            )
    }
}
