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
import com.android.systemui.communal.nano.CommunalHubState
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
    fun deleteWidget_notInDb_returnsFalse() =
        testScope.runTest {
            val (widgetId, provider, priority) = widgetInfo1
            communalWidgetDao.addWidget(
                widgetId = widgetId,
                provider = provider,
                priority = priority,
            )
            assertThat(communalWidgetDao.deleteWidgetById(widgetId = 123)).isFalse()
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

    @Test
    fun reorderWidget_emitsWidgetsInNewOrder() =
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
                    communalItemRankEntry2,
                    communalWidgetItemEntry2,
                    communalItemRankEntry1,
                    communalWidgetItemEntry1,
                )
                .inOrder()

            // swapped priorities
            val widgetIdsToPriorityMap = mapOf(widgetInfo1.widgetId to 2, widgetInfo2.widgetId to 1)
            communalWidgetDao.updateWidgetOrder(widgetIdsToPriorityMap)
            assertThat(widgets())
                .containsExactly(
                    communalItemRankEntry1.copy(rank = 2),
                    communalWidgetItemEntry1,
                    communalItemRankEntry2.copy(rank = 1),
                    communalWidgetItemEntry2
                )
                .inOrder()
        }

    @Test
    fun addNewWidgetWithReorder_emitsWidgetsInNewOrder() =
        testScope.runTest {
            val existingWidgets = listOf(widgetInfo1, widgetInfo2)
            val widgets = collectLastValue(communalWidgetDao.getWidgets())

            existingWidgets.forEach {
                val (widgetId, provider, priority) = it
                communalWidgetDao.addWidget(
                    widgetId = widgetId,
                    provider = provider,
                    priority = priority,
                )
            }
            assertThat(widgets())
                .containsExactly(
                    communalItemRankEntry2,
                    communalWidgetItemEntry2,
                    communalItemRankEntry1,
                    communalWidgetItemEntry1,
                )
                .inOrder()

            // map with no item in the middle at index 1
            val widgetIdsToIndexMap = mapOf(widgetInfo1.widgetId to 1, widgetInfo2.widgetId to 3)
            communalWidgetDao.updateWidgetOrder(widgetIdsToIndexMap)
            assertThat(widgets())
                .containsExactly(
                    communalItemRankEntry2.copy(rank = 3),
                    communalWidgetItemEntry2,
                    communalItemRankEntry1.copy(rank = 1),
                    communalWidgetItemEntry1,
                )
                .inOrder()
            // add the new middle item that we left space for.
            communalWidgetDao.addWidget(
                widgetId = widgetInfo3.widgetId,
                provider = widgetInfo3.provider,
                priority = 2,
            )
            assertThat(widgets())
                .containsExactly(
                    communalItemRankEntry2.copy(rank = 3),
                    communalWidgetItemEntry2,
                    communalItemRankEntry3.copy(rank = 2),
                    communalWidgetItemEntry3,
                    communalItemRankEntry1.copy(rank = 1),
                    communalWidgetItemEntry1,
                )
                .inOrder()
        }

    @Test
    fun restoreCommunalHubState() =
        testScope.runTest {
            // Set up db
            listOf(widgetInfo1, widgetInfo2, widgetInfo3).forEach { addWidget(it) }

            // Restore db to fake state
            communalWidgetDao.restoreCommunalHubState(fakeState)

            // Verify db matches new state
            val expected = mutableMapOf<CommunalItemRank, CommunalWidgetItem>()
            fakeState.widgets.forEachIndexed { index, fakeWidget ->
                // Auto-generated uid continues after the initial 3 widgets and starts at 4
                val uid = index + 4L
                val rank = CommunalItemRank(uid = uid, rank = fakeWidget.rank)
                val widget =
                    CommunalWidgetItem(
                        uid = uid,
                        widgetId = fakeWidget.widgetId,
                        componentName = fakeWidget.componentName,
                        itemId = rank.uid,
                    )
                expected[rank] = widget
            }
            val widgets by collectLastValue(communalWidgetDao.getWidgets())
            assertThat(widgets).containsExactlyEntriesIn(expected)
        }

    private fun addWidget(metadata: FakeWidgetMetadata, priority: Int? = null) {
        communalWidgetDao.addWidget(
            widgetId = metadata.widgetId,
            provider = metadata.provider,
            priority = priority ?: metadata.priority,
        )
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
        val widgetInfo3 =
            FakeWidgetMetadata(
                widgetId = 3,
                provider = ComponentName("pk_name", "cls_name_3"),
                priority = 3
            )
        val communalItemRankEntry1 = CommunalItemRank(uid = 1L, rank = widgetInfo1.priority)
        val communalItemRankEntry2 = CommunalItemRank(uid = 2L, rank = widgetInfo2.priority)
        val communalItemRankEntry3 = CommunalItemRank(uid = 3L, rank = widgetInfo3.priority)
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
        val communalWidgetItemEntry3 =
            CommunalWidgetItem(
                uid = 3L,
                widgetId = widgetInfo3.widgetId,
                componentName = widgetInfo3.provider.flattenToString(),
                itemId = communalItemRankEntry3.uid,
            )
        val fakeState =
            CommunalHubState().apply {
                widgets =
                    listOf(
                            CommunalHubState.CommunalWidgetItem().apply {
                                widgetId = 1
                                componentName = "pk_name/fake_widget_1"
                                rank = 1
                            },
                            CommunalHubState.CommunalWidgetItem().apply {
                                widgetId = 2
                                componentName = "pk_name/fake_widget_2"
                                rank = 2
                            },
                        )
                        .toTypedArray()
            }
    }
}
