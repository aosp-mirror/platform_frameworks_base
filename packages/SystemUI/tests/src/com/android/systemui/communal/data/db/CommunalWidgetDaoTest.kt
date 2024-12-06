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
import com.android.systemui.communal.shared.model.SpanValue
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
            val (widgetId, provider, rank, userSerialNumber, spanY) = widgetInfo1
            communalWidgetDao.addWidget(
                widgetId = widgetId,
                provider = provider,
                rank = rank,
                userSerialNumber = userSerialNumber,
                spanY = spanY,
            )
            val entry = communalWidgetDao.getWidgetByIdNow(id = 1)
            assertThat(entry).isEqualTo(communalWidgetItemEntry1)
        }

    @Test
    fun deleteWidget_notInDb_returnsFalse() =
        testScope.runTest {
            val (widgetId, provider, rank, userSerialNumber, spanY) = widgetInfo1
            communalWidgetDao.addWidget(
                widgetId = widgetId,
                provider = provider,
                rank = rank,
                userSerialNumber = userSerialNumber,
                spanY = spanY,
            )
            assertThat(communalWidgetDao.deleteWidgetById(widgetId = 123)).isFalse()
        }

    @Test
    fun addWidget_emitsActiveWidgetsInDb(): Unit =
        testScope.runTest {
            val widgetsToAdd = listOf(widgetInfo1, widgetInfo2)
            val widgets = collectLastValue(communalWidgetDao.getWidgets())
            widgetsToAdd.forEach {
                val (widgetId, provider, rank, userSerialNumber, spanY) = it
                communalWidgetDao.addWidget(
                    widgetId = widgetId,
                    provider = provider,
                    rank = rank,
                    userSerialNumber = userSerialNumber,
                    spanY = spanY,
                )
            }
            assertThat(widgets())
                .containsExactly(
                    communalItemRankEntry1,
                    communalWidgetItemEntry1,
                    communalItemRankEntry2,
                    communalWidgetItemEntry2,
                )
        }

    @Test
    fun addWidget_rankNotSpecified_widgetAddedAtTheEnd(): Unit =
        testScope.runTest {
            val widgets by collectLastValue(communalWidgetDao.getWidgets())

            // Verify database is empty
            assertThat(widgets).isEmpty()

            // Add widgets one by one without specifying rank
            val widgetsToAdd = listOf(widgetInfo1, widgetInfo2, widgetInfo3)
            widgetsToAdd.forEach {
                val (widgetId, provider, _, userSerialNumber, spanY) = it
                communalWidgetDao.addWidget(
                    widgetId = widgetId,
                    provider = provider,
                    userSerialNumber = userSerialNumber,
                    spanY = spanY,
                )
            }

            // Verify new each widget is added at the end
            assertThat(widgets)
                .containsExactly(
                    communalItemRankEntry1,
                    communalWidgetItemEntry1,
                    communalItemRankEntry2,
                    communalWidgetItemEntry2,
                    communalItemRankEntry3,
                    communalWidgetItemEntry3,
                )
        }

    @Test
    fun deleteWidget_emitsActiveWidgetsInDb() =
        testScope.runTest {
            val widgetsToAdd = listOf(widgetInfo1, widgetInfo2)
            val widgets = collectLastValue(communalWidgetDao.getWidgets())

            widgetsToAdd.forEach {
                val (widgetId, provider, rank, userSerialNumber, spanY) = it
                communalWidgetDao.addWidget(
                    widgetId = widgetId,
                    provider = provider,
                    rank = rank,
                    userSerialNumber = userSerialNumber,
                    spanY = spanY,
                )
            }
            assertThat(widgets())
                .containsExactly(
                    communalItemRankEntry1,
                    communalWidgetItemEntry1,
                    communalItemRankEntry2,
                    communalWidgetItemEntry2,
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
                val (widgetId, provider, rank, userSerialNumber, spanY) = it
                communalWidgetDao.addWidget(
                    widgetId = widgetId,
                    provider = provider,
                    rank = rank,
                    userSerialNumber = userSerialNumber,
                    spanY = spanY,
                )
            }
            assertThat(widgets())
                .containsExactly(
                    communalItemRankEntry1,
                    communalWidgetItemEntry1,
                    communalItemRankEntry2,
                    communalWidgetItemEntry2,
                )
                .inOrder()

            // swapped ranks
            val widgetIdsToRankMap = mapOf(widgetInfo1.widgetId to 1, widgetInfo2.widgetId to 0)
            communalWidgetDao.updateWidgetOrder(widgetIdsToRankMap)
            assertThat(widgets())
                .containsExactly(
                    communalItemRankEntry2.copy(rank = 0),
                    communalWidgetItemEntry2,
                    communalItemRankEntry1.copy(rank = 1),
                    communalWidgetItemEntry1,
                )
                .inOrder()
        }

    @Test
    fun addNewWidgetWithReorder_emitsWidgetsInNewOrder() =
        testScope.runTest {
            val existingWidgets = listOf(widgetInfo1, widgetInfo2, widgetInfo3)
            val widgets = collectLastValue(communalWidgetDao.getWidgets())

            existingWidgets.forEach {
                val (widgetId, provider, rank, userSerialNumber, spanY) = it
                communalWidgetDao.addWidget(
                    widgetId = widgetId,
                    provider = provider,
                    rank = rank,
                    userSerialNumber = userSerialNumber,
                    spanY = spanY,
                )
            }
            assertThat(widgets())
                .containsExactly(
                    communalItemRankEntry1,
                    communalWidgetItemEntry1,
                    communalItemRankEntry2,
                    communalWidgetItemEntry2,
                    communalItemRankEntry3,
                    communalWidgetItemEntry3,
                )
                .inOrder()

            // add a new widget at rank 1.
            communalWidgetDao.addWidget(
                widgetId = 4,
                provider = ComponentName("pk_name", "cls_name_4"),
                rank = 1,
                userSerialNumber = 0,
                spanY = SpanValue.Responsive(1),
            )

            val newRankEntry = CommunalItemRank(uid = 4L, rank = 1)
            val newWidgetEntry =
                CommunalWidgetItem(
                    uid = 4L,
                    widgetId = 4,
                    componentName = "pk_name/cls_name_4",
                    itemId = 4L,
                    userSerialNumber = 0,
                    spanY = 3,
                    spanYNew = 1,
                )
            assertThat(widgets())
                .containsExactly(
                    communalItemRankEntry1.copy(rank = 0),
                    communalWidgetItemEntry1,
                    newRankEntry,
                    newWidgetEntry,
                    communalItemRankEntry2.copy(rank = 2),
                    communalWidgetItemEntry2,
                    communalItemRankEntry3.copy(rank = 3),
                    communalWidgetItemEntry3,
                )
                .inOrder()
        }

    @Test
    fun addWidget_withDifferentSpanY_readsCorrectValuesInDb() =
        testScope.runTest {
            val widgets = collectLastValue(communalWidgetDao.getWidgets())

            // Add widgets with different spanY values
            communalWidgetDao.addWidget(
                widgetId = 1,
                provider = ComponentName("pkg_name", "cls_name_1"),
                rank = 0,
                userSerialNumber = 0,
                spanY = SpanValue.Responsive(1),
            )
            communalWidgetDao.addWidget(
                widgetId = 2,
                provider = ComponentName("pkg_name", "cls_name_2"),
                rank = 1,
                userSerialNumber = 0,
                spanY = SpanValue.Responsive(2),
            )
            communalWidgetDao.addWidget(
                widgetId = 3,
                provider = ComponentName("pkg_name", "cls_name_3"),
                rank = 2,
                userSerialNumber = 0,
                spanY = SpanValue.Fixed(3),
            )

            // Verify that the widgets have the correct spanY values
            assertThat(widgets())
                .containsExactly(
                    CommunalItemRank(uid = 1L, rank = 0),
                    CommunalWidgetItem(
                        uid = 1L,
                        widgetId = 1,
                        componentName = "pkg_name/cls_name_1",
                        itemId = 1L,
                        userSerialNumber = 0,
                        spanY = 3,
                        spanYNew = 1,
                    ),
                    CommunalItemRank(uid = 2L, rank = 1),
                    CommunalWidgetItem(
                        uid = 2L,
                        widgetId = 2,
                        componentName = "pkg_name/cls_name_2",
                        itemId = 2L,
                        userSerialNumber = 0,
                        spanY = 6,
                        spanYNew = 2,
                    ),
                    CommunalItemRank(uid = 3L, rank = 2),
                    CommunalWidgetItem(
                        uid = 3L,
                        widgetId = 3,
                        componentName = "pkg_name/cls_name_3",
                        itemId = 3L,
                        userSerialNumber = 0,
                        spanY = 3,
                        spanYNew = 1,
                    ),
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
                        userSerialNumber = fakeWidget.userSerialNumber,
                        spanY = fakeWidget.spanY.coerceAtLeast(3),
                        spanYNew = fakeWidget.spanYNew.coerceAtLeast(1),
                    )
                expected[rank] = widget
            }
            val widgets by collectLastValue(communalWidgetDao.getWidgets())
            assertThat(widgets).containsExactlyEntriesIn(expected)
        }

    private fun addWidget(metadata: FakeWidgetMetadata, rank: Int? = null) {
        communalWidgetDao.addWidget(
            widgetId = metadata.widgetId,
            provider = metadata.provider,
            rank = rank ?: metadata.rank,
            userSerialNumber = metadata.userSerialNumber,
            spanY = metadata.spanY,
        )
    }

    data class FakeWidgetMetadata(
        val widgetId: Int,
        val provider: ComponentName,
        val rank: Int,
        val userSerialNumber: Int,
        val spanY: SpanValue,
    )

    companion object {
        val widgetInfo1 =
            FakeWidgetMetadata(
                widgetId = 1,
                provider = ComponentName("pk_name", "cls_name_1"),
                rank = 0,
                userSerialNumber = 0,
                spanY = SpanValue.Responsive(1),
            )
        val widgetInfo2 =
            FakeWidgetMetadata(
                widgetId = 2,
                provider = ComponentName("pk_name", "cls_name_2"),
                rank = 1,
                userSerialNumber = 0,
                spanY = SpanValue.Responsive(1),
            )
        val widgetInfo3 =
            FakeWidgetMetadata(
                widgetId = 3,
                provider = ComponentName("pk_name", "cls_name_3"),
                rank = 2,
                userSerialNumber = 10,
                spanY = SpanValue.Responsive(1),
            )
        val communalItemRankEntry1 = CommunalItemRank(uid = 1L, rank = widgetInfo1.rank)
        val communalItemRankEntry2 = CommunalItemRank(uid = 2L, rank = widgetInfo2.rank)
        val communalItemRankEntry3 = CommunalItemRank(uid = 3L, rank = widgetInfo3.rank)
        val communalWidgetItemEntry1 =
            CommunalWidgetItem(
                uid = 1L,
                widgetId = widgetInfo1.widgetId,
                componentName = widgetInfo1.provider.flattenToString(),
                itemId = communalItemRankEntry1.uid,
                userSerialNumber = widgetInfo1.userSerialNumber,
                spanY = 3,
                spanYNew = 1,
            )
        val communalWidgetItemEntry2 =
            CommunalWidgetItem(
                uid = 2L,
                widgetId = widgetInfo2.widgetId,
                componentName = widgetInfo2.provider.flattenToString(),
                itemId = communalItemRankEntry2.uid,
                userSerialNumber = widgetInfo2.userSerialNumber,
                spanY = 3,
                spanYNew = 1,
            )
        val communalWidgetItemEntry3 =
            CommunalWidgetItem(
                uid = 3L,
                widgetId = widgetInfo3.widgetId,
                componentName = widgetInfo3.provider.flattenToString(),
                itemId = communalItemRankEntry3.uid,
                userSerialNumber = widgetInfo3.userSerialNumber,
                spanY = 3,
                spanYNew = 1,
            )
        val fakeState =
            CommunalHubState().apply {
                widgets =
                    listOf(
                            CommunalHubState.CommunalWidgetItem().apply {
                                widgetId = 1
                                componentName = "pk_name/fake_widget_1"
                                rank = 1
                                userSerialNumber = 0
                                spanY = 3
                            },
                            CommunalHubState.CommunalWidgetItem().apply {
                                widgetId = 2
                                componentName = "pk_name/fake_widget_2"
                                rank = 2
                                userSerialNumber = 10
                                spanYNew = 1
                            },
                        )
                        .toTypedArray()
            }
    }
}
