/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.communal

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.mockedContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.FakeBroadcastDispatcher
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.widgets.CommunalWidgetModule
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalBackupRestoreStartableTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    @Mock private lateinit var communalInteractor: CommunalInteractor

    private val mapCaptor = kotlinArgumentCaptor<Map<Int, Int>>()

    private lateinit var context: Context
    private lateinit var broadcastDispatcher: FakeBroadcastDispatcher
    private lateinit var underTest: CommunalBackupRestoreStartable

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        context = kosmos.mockedContext
        broadcastDispatcher = kosmos.broadcastDispatcher

        underTest =
            CommunalBackupRestoreStartable(
                broadcastDispatcher,
                communalInteractor,
                logcatLogBuffer("CommunalBackupRestoreStartable"),
            )
    }

    @Test
    fun testRestoreWidgetsUponHostRestored() =
        testScope.runTest {
            underTest.start()

            // Verify restore widgets not called
            verify(communalInteractor, never()).restoreWidgets(any())

            // Trigger app widget host restored
            val intent =
                Intent().apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED
                    putExtra(
                        AppWidgetManager.EXTRA_HOST_ID,
                        CommunalWidgetModule.APP_WIDGET_HOST_ID
                    )
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS, intArrayOf(1, 2, 3))
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(7, 8, 9))
                }
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(context, intent)

            // Verify restore widgets called
            verify(communalInteractor).restoreWidgets(mapCaptor.capture())
            val oldToNewWidgetIdMap = mapCaptor.value
            assertThat(oldToNewWidgetIdMap)
                .containsExactlyEntriesIn(
                    mapOf(
                        Pair(1, 7),
                        Pair(2, 8),
                        Pair(3, 9),
                    )
                )
        }

    @Test
    fun testDoNotRestoreWidgetsIfNotForCommunalWidgetHost() =
        testScope.runTest {
            underTest.start()

            // Trigger app widget host restored, but for another host
            val hostId = CommunalWidgetModule.APP_WIDGET_HOST_ID + 1
            val intent =
                Intent().apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED
                    putExtra(AppWidgetManager.EXTRA_HOST_ID, hostId)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS, intArrayOf(1, 2, 3))
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(7, 8, 9))
                }
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(context, intent)

            // Verify restore widgets not called
            verify(communalInteractor, never()).restoreWidgets(any())
        }

    @Test
    fun testAbortRestoreWidgetsIfOldToNewIdsMappingInvalid() =
        testScope.runTest {
            underTest.start()

            // Trigger app widget host restored, but new ids list is one too many for old ids
            val oldIds = intArrayOf(1, 2, 3)
            val newIds = intArrayOf(6, 7, 8, 9)
            val intent =
                Intent().apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED
                    putExtra(
                        AppWidgetManager.EXTRA_HOST_ID,
                        CommunalWidgetModule.APP_WIDGET_HOST_ID
                    )
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS, oldIds)
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, newIds)
                }
            broadcastDispatcher.sendIntentToMatchingReceiversOnly(context, intent)

            // Verify restore widgets aborted
            verify(communalInteractor).abortRestoreWidgets()
            verify(communalInteractor, never()).restoreWidgets(any())
        }
}
