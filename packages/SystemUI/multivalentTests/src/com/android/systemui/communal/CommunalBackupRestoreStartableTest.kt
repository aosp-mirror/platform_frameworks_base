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
import android.os.Handler
import android.os.fakeExecutorHandler
import android.provider.Settings.Secure.USER_SETUP_COMPLETE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.FakeBroadcastDispatcher
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.widgets.CommunalWidgetModule
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.testKosmos
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalBackupRestoreStartableTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    @Mock private lateinit var communalInteractor: CommunalInteractor

    private val mapCaptor = argumentCaptor<Map<Int, Int>>()

    private lateinit var context: Context
    private lateinit var broadcastDispatcher: FakeBroadcastDispatcher
    private lateinit var secureSettings: SecureSettings
    private lateinit var handler: Handler
    private lateinit var fakeExecutor: FakeExecutor
    private lateinit var underTest: CommunalBackupRestoreStartable

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        context = kosmos.mockedContext
        broadcastDispatcher = kosmos.broadcastDispatcher
        secureSettings = kosmos.fakeSettings
        handler = kosmos.fakeExecutorHandler
        fakeExecutor = kosmos.fakeExecutor

        secureSettings.putInt(USER_SETUP_COMPLETE, 0)

        underTest =
            CommunalBackupRestoreStartable(
                broadcastDispatcher,
                communalInteractor,
                logcatLogBuffer("CommunalBackupRestoreStartable"),
                secureSettings,
                handler,
            )
    }

    @Test
    fun restoreWidgets_userSetUpComplete_performRestore() =
        testScope.runTest {
            // User set up complete
            secureSettings.putInt(USER_SETUP_COMPLETE, 1)

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
            val oldToNewWidgetIdMap = mapCaptor.firstValue
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
    fun restoreWidgets_userSetUpNotComplete_restoreWhenUserSetupComplete() =
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

            // Verify restore widgets not called because user setup not complete
            verify(communalInteractor, never()).restoreWidgets(any())

            // User setup complete
            secureSettings.putInt(USER_SETUP_COMPLETE, 1)
            fakeExecutor.runAllReady()

            // Verify restore widgets called
            verify(communalInteractor).restoreWidgets(mapCaptor.capture())
            val oldToNewWidgetIdMap = mapCaptor.firstValue
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
    fun restoreWidgets_broadcastNotForCommunalWidgetHost_doNotPerformRestore() =
        testScope.runTest {
            // User set up complete
            secureSettings.putInt(USER_SETUP_COMPLETE, 1)

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
    fun restoreWidgets_oldToNewIdsMappingInvalid_abortRestore() =
        testScope.runTest {
            // User set up complete
            secureSettings.putInt(USER_SETUP_COMPLETE, 1)

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
