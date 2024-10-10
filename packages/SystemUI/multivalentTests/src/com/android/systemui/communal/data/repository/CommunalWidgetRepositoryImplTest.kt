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

package com.android.systemui.communal.data.repository

import android.app.backup.BackupManager
import android.appwidget.AppWidgetProviderInfo
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL
import android.appwidget.AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE
import android.content.ComponentName
import android.content.applicationContext
import android.graphics.Bitmap
import android.os.UserHandle
import android.os.userManager
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_COMMUNAL_WIDGET_RESIZING
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.data.repository.fakePackageChangeRepository
import com.android.systemui.common.shared.model.PackageInstallSession
import com.android.systemui.communal.data.backup.CommunalBackupUtils
import com.android.systemui.communal.data.db.CommunalItemRank
import com.android.systemui.communal.data.db.CommunalWidgetDao
import com.android.systemui.communal.data.db.CommunalWidgetItem
import com.android.systemui.communal.data.db.defaultWidgetPopulation
import com.android.systemui.communal.nano.CommunalHubState
import com.android.systemui.communal.proto.toByteArray
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.communal.widgets.CommunalAppWidgetHost
import com.android.systemui.communal.widgets.CommunalWidgetHost
import com.android.systemui.communal.widgets.widgetConfiguratorFail
import com.android.systemui.communal.widgets.widgetConfiguratorSuccess
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalWidgetRepositoryImplTest : SysuiTestCase() {
    @Mock private lateinit var appWidgetHost: CommunalAppWidgetHost
    @Mock private lateinit var providerInfoA: AppWidgetProviderInfo
    @Mock private lateinit var providerInfoB: AppWidgetProviderInfo
    @Mock private lateinit var providerInfoC: AppWidgetProviderInfo
    @Mock private lateinit var communalWidgetHost: CommunalWidgetHost
    @Mock private lateinit var communalWidgetDao: CommunalWidgetDao
    @Mock private lateinit var backupManager: BackupManager

    private val communalHubStateCaptor = argumentCaptor<CommunalHubState>()
    private val componentNameCaptor = argumentCaptor<ComponentName>()

    private lateinit var backupUtils: CommunalBackupUtils
    private lateinit var logBuffer: LogBuffer
    private lateinit var fakeWidgets: MutableStateFlow<Map<CommunalItemRank, CommunalWidgetItem>>
    private lateinit var fakeProviders: MutableStateFlow<Map<Int, AppWidgetProviderInfo?>>

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val packageChangeRepository = kosmos.fakePackageChangeRepository
    private val userManager = kosmos.userManager

    private val mainUser = UserHandle(0)
    private val workProfile = UserHandle(10)

    private val fakeAllowlist =
        listOf(
            "com.android.fake/WidgetProviderA",
            "com.android.fake/WidgetProviderB",
            "com.android.fake/WidgetProviderC",
        )

    private lateinit var underTest: CommunalWidgetRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        fakeWidgets = MutableStateFlow(emptyMap())
        fakeProviders = MutableStateFlow(emptyMap())
        logBuffer = logcatLogBuffer(name = "CommunalWidgetRepoImplTest")
        backupUtils = CommunalBackupUtils(kosmos.applicationContext)

        setAppWidgetIds(emptyList())

        overrideResource(R.array.config_communalWidgetAllowlist, fakeAllowlist.toTypedArray())

        whenever(communalWidgetDao.getWidgets()).thenReturn(fakeWidgets)
        whenever(communalWidgetHost.appWidgetProviders).thenReturn(fakeProviders)
        whenever(userManager.mainUser).thenReturn(mainUser)

        restoreUser(mainUser)

        underTest =
            CommunalWidgetRepositoryImpl(
                appWidgetHost,
                testScope.backgroundScope,
                kosmos.testDispatcher,
                communalWidgetHost,
                communalWidgetDao,
                logBuffer,
                backupManager,
                backupUtils,
                packageChangeRepository,
                userManager,
                kosmos.defaultWidgetPopulation,
            )
    }

    @Test
    fun communalWidgets_queryWidgetsFromDb() =
        testScope.runTest {
            val communalItemRankEntry = CommunalItemRank(uid = 1L, rank = 1)
            val communalWidgetItemEntry =
                CommunalWidgetItem(uid = 1L, 1, "pk_name/cls_name", 1L, 0, 3)
            fakeWidgets.value = mapOf(communalItemRankEntry to communalWidgetItemEntry)
            fakeProviders.value = mapOf(1 to providerInfoA)

            val communalWidgets by collectLastValue(underTest.communalWidgets)
            verify(communalWidgetDao).getWidgets()
            assertThat(communalWidgets)
                .containsExactly(
                    CommunalWidgetContentModel.Available(
                        appWidgetId = communalWidgetItemEntry.widgetId,
                        providerInfo = providerInfoA,
                        rank = communalItemRankEntry.rank,
                        spanY = communalWidgetItemEntry.spanY,
                    )
                )

            // Verify backup not requested
            verify(backupManager, never()).dataChanged()
        }

    @Test
    fun communalWidgets_widgetsWithoutMatchingProvidersAreSkipped() =
        testScope.runTest {
            // Set up 4 widgets, but widget 3 and 4 don't have matching providers
            fakeWidgets.value =
                mapOf(
                    CommunalItemRank(uid = 1L, rank = 1) to
                        CommunalWidgetItem(uid = 1L, 1, "pk_1/cls_1", 1L, 0, 3),
                    CommunalItemRank(uid = 2L, rank = 2) to
                        CommunalWidgetItem(uid = 2L, 2, "pk_2/cls_2", 2L, 0, 3),
                    CommunalItemRank(uid = 3L, rank = 3) to
                        CommunalWidgetItem(uid = 3L, 3, "pk_3/cls_3", 3L, 0, 3),
                    CommunalItemRank(uid = 4L, rank = 4) to
                        CommunalWidgetItem(uid = 4L, 4, "pk_4/cls_4", 4L, 0, 3),
                )
            fakeProviders.value = mapOf(1 to providerInfoA, 2 to providerInfoB)

            // Expect to see only widget 1 and 2
            val communalWidgets by collectLastValue(underTest.communalWidgets)
            assertThat(communalWidgets)
                .containsExactly(
                    CommunalWidgetContentModel.Available(
                        appWidgetId = 1,
                        providerInfo = providerInfoA,
                        rank = 1,
                        spanY = 3,
                    ),
                    CommunalWidgetContentModel.Available(
                        appWidgetId = 2,
                        providerInfo = providerInfoB,
                        rank = 2,
                        spanY = 3,
                    ),
                )
        }

    @Test
    fun communalWidgets_updatedWhenProvidersUpdate() =
        testScope.runTest {
            // Set up widgets and providers
            fakeWidgets.value =
                mapOf(
                    CommunalItemRank(uid = 1L, rank = 1) to
                        CommunalWidgetItem(uid = 1L, 1, "pk_1/cls_1", 1L, 0, 3),
                    CommunalItemRank(uid = 2L, rank = 2) to
                        CommunalWidgetItem(uid = 2L, 2, "pk_2/cls_2", 2L, 0, 3),
                )
            fakeProviders.value = mapOf(1 to providerInfoA, 2 to providerInfoB)

            // Expect two widgets
            val communalWidgets by collectLastValue(underTest.communalWidgets)
            assertThat(communalWidgets).isNotNull()
            assertThat(communalWidgets)
                .containsExactly(
                    CommunalWidgetContentModel.Available(
                        appWidgetId = 1,
                        providerInfo = providerInfoA,
                        rank = 1,
                        spanY = 3,
                    ),
                    CommunalWidgetContentModel.Available(
                        appWidgetId = 2,
                        providerInfo = providerInfoB,
                        rank = 2,
                        spanY = 3,
                    ),
                )

            // Provider info updated for widget 1
            fakeProviders.value = mapOf(1 to providerInfoC, 2 to providerInfoB)
            runCurrent()

            assertThat(communalWidgets)
                .containsExactly(
                    CommunalWidgetContentModel.Available(
                        appWidgetId = 1,
                        // Verify that provider info updated
                        providerInfo = providerInfoC,
                        rank = 1,
                        spanY = 3,
                    ),
                    CommunalWidgetContentModel.Available(
                        appWidgetId = 2,
                        providerInfo = providerInfoB,
                        rank = 2,
                        spanY = 3,
                    ),
                )
        }

    @Test
    fun addWidget_allocateId_bindWidget_andAddToDb() =
        testScope.runTest {
            val provider = ComponentName("pkg_name", "cls_name")
            val id = 1
            val rank = 1
            whenever(communalWidgetHost.getAppWidgetInfo(id))
                .thenReturn(PROVIDER_INFO_REQUIRES_CONFIGURATION)
            whenever(
                    communalWidgetHost.allocateIdAndBindWidget(
                        any<ComponentName>(),
                        any<UserHandle>(),
                    )
                )
                .thenReturn(id)
            underTest.addWidget(provider, mainUser, rank, kosmos.widgetConfiguratorSuccess)
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider, mainUser)
            verify(communalWidgetDao).addWidget(id, provider, rank, testUserSerialNumber(mainUser))

            // Verify backup requested
            verify(backupManager).dataChanged()
        }

    @Test
    fun addWidget_configurationFails_doNotAddWidgetToDb() =
        testScope.runTest {
            val provider = ComponentName("pkg_name", "cls_name")
            val id = 1
            val rank = 1
            whenever(communalWidgetHost.getAppWidgetInfo(id))
                .thenReturn(PROVIDER_INFO_REQUIRES_CONFIGURATION)
            whenever(
                    communalWidgetHost.allocateIdAndBindWidget(
                        any<ComponentName>(),
                        any<UserHandle>(),
                    )
                )
                .thenReturn(id)
            underTest.addWidget(provider, mainUser, rank, kosmos.widgetConfiguratorFail)
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider, mainUser)
            verify(communalWidgetDao, never())
                .addWidget(anyInt(), any<ComponentName>(), anyInt(), anyInt(), anyInt())
            verify(appWidgetHost).deleteAppWidgetId(id)

            // Verify backup not requested
            verify(backupManager, never()).dataChanged()
        }

    @Test
    fun addWidget_configurationThrowsError_doNotAddWidgetToDb() =
        testScope.runTest {
            val provider = ComponentName("pkg_name", "cls_name")
            val id = 1
            val rank = 1
            whenever(communalWidgetHost.getAppWidgetInfo(id))
                .thenReturn(PROVIDER_INFO_REQUIRES_CONFIGURATION)
            whenever(
                    communalWidgetHost.allocateIdAndBindWidget(
                        any<ComponentName>(),
                        any<UserHandle>(),
                    )
                )
                .thenReturn(id)
            underTest.addWidget(provider, mainUser, rank) {
                throw IllegalStateException("some error")
            }
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider, mainUser)
            verify(communalWidgetDao, never())
                .addWidget(anyInt(), any<ComponentName>(), anyInt(), anyInt(), anyInt())
            verify(appWidgetHost).deleteAppWidgetId(id)

            // Verify backup not requested
            verify(backupManager, never()).dataChanged()
        }

    @Test
    fun addWidget_configurationNotRequired_doesNotConfigure_addWidgetToDb() =
        testScope.runTest {
            val provider = ComponentName("pkg_name", "cls_name")
            val id = 1
            val rank = 1
            whenever(communalWidgetHost.getAppWidgetInfo(id))
                .thenReturn(PROVIDER_INFO_CONFIGURATION_OPTIONAL)
            whenever(
                    communalWidgetHost.allocateIdAndBindWidget(
                        any<ComponentName>(),
                        any<UserHandle>(),
                    )
                )
                .thenReturn(id)
            underTest.addWidget(provider, mainUser, rank, kosmos.widgetConfiguratorFail)
            runCurrent()

            verify(communalWidgetHost).allocateIdAndBindWidget(provider, mainUser)
            verify(communalWidgetDao).addWidget(id, provider, rank, testUserSerialNumber(mainUser))

            // Verify backup requested
            verify(backupManager).dataChanged()
        }

    @Test
    fun deleteWidget_deleteFromDbTrue_alsoDeleteFromHost() =
        testScope.runTest {
            val id = 1
            whenever(communalWidgetDao.deleteWidgetById(eq(id))).thenReturn(true)
            underTest.deleteWidget(id)
            runCurrent()

            verify(communalWidgetDao).deleteWidgetById(id)
            verify(appWidgetHost).deleteAppWidgetId(id)

            // Verify backup requested
            verify(backupManager).dataChanged()
        }

    @Test
    fun deleteWidget_deleteFromDbFalse_doesNotDeleteFromHost() =
        testScope.runTest {
            val id = 1
            whenever(communalWidgetDao.deleteWidgetById(eq(id))).thenReturn(false)
            underTest.deleteWidget(id)
            runCurrent()

            verify(communalWidgetDao).deleteWidgetById(id)
            verify(appWidgetHost, never()).deleteAppWidgetId(id)

            // Verify backup not requested
            verify(backupManager, never()).dataChanged()
        }

    @Test
    fun reorderWidgets_queryDb() =
        testScope.runTest {
            val widgetIdToRankMap = mapOf(104 to 1, 103 to 2, 101 to 3)
            underTest.updateWidgetOrder(widgetIdToRankMap)
            runCurrent()

            verify(communalWidgetDao).updateWidgetOrder(widgetIdToRankMap)

            // Verify backup requested
            verify(backupManager).dataChanged()
        }

    @Test
    fun restoreWidgets_deleteStateFileIfRestoreFails() =
        testScope.runTest {
            // Write a state file that is invalid, and verify it is written
            backupUtils.writeBytesToDisk(byteArrayOf(1, 2, 3, 4, 5, 6))
            assertThat(backupUtils.fileExists()).isTrue()

            // Try to restore widgets
            underTest.restoreWidgets(emptyMap())
            runCurrent()

            // The restore should fail, and verify that the file is deleted
            assertThat(backupUtils.fileExists()).isFalse()
        }

    @Test
    fun restoreWidgets_deleteStateFileAfterWidgetsRestored() =
        testScope.runTest {
            // Write a state file, and verify it is written
            backupUtils.writeBytesToDisk(fakeState.toByteArray())
            assertThat(backupUtils.fileExists()).isTrue()

            // Set up app widget host with widget ids
            setAppWidgetIds(listOf(11, 12))

            // Restore widgets
            underTest.restoreWidgets(mapOf(Pair(1, 11), Pair(2, 12)))
            runCurrent()

            // Verify state restored
            verify(communalWidgetDao).restoreCommunalHubState(any())

            // Verify state file deleted
            assertThat(backupUtils.fileExists()).isFalse()
        }

    @Test
    fun restoreWidgets_restoredWidgetsNotRegisteredWithHostAreSkipped() =
        testScope.runTest {
            // Write fake state to file
            backupUtils.writeBytesToDisk(fakeState.toByteArray())

            // Set up app widget host with widget ids. Widget 12 (previously 2) is absent.
            setAppWidgetIds(listOf(11))

            // Restore widgets.
            underTest.restoreWidgets(mapOf(Pair(1, 11), Pair(2, 12)))
            runCurrent()

            // Verify state restored, and widget 2 skipped
            verify(communalWidgetDao).restoreCommunalHubState(communalHubStateCaptor.capture())
            val restoredWidgets = communalHubStateCaptor.firstValue.widgets.toList()
            assertThat(restoredWidgets).hasSize(1)

            val restoredWidget = restoredWidgets.first()
            val expectedWidget = fakeState.widgets.first()

            // Verify widget id is updated, and the rest remain the same as expected
            assertThat(restoredWidget.widgetId).isEqualTo(11)
            assertThat(restoredWidget.componentName).isEqualTo(expectedWidget.componentName)
            assertThat(restoredWidget.rank).isEqualTo(expectedWidget.rank)
        }

    @Test
    fun restoreWidgets_registeredWidgetsNotRestoredAreRemoved() =
        testScope.runTest {
            // Write fake state to file
            backupUtils.writeBytesToDisk(fakeState.toByteArray())

            // Set up app widget host with widget ids. Widget 13 will not be restored.
            setAppWidgetIds(listOf(11, 12, 13))

            // Restore widgets.
            underTest.restoreWidgets(mapOf(Pair(1, 11), Pair(2, 12)))
            runCurrent()

            // Verify widget 1 and 2 are restored, and are now 11 and 12.
            verify(communalWidgetDao).restoreCommunalHubState(communalHubStateCaptor.capture())
            val restoredWidgets = communalHubStateCaptor.firstValue.widgets.toList()
            assertThat(restoredWidgets).hasSize(2)

            val restoredWidget1 = restoredWidgets[0]
            val expectedWidget1 = fakeState.widgets[0]
            assertThat(restoredWidget1.widgetId).isEqualTo(11)
            assertThat(restoredWidget1.componentName).isEqualTo(expectedWidget1.componentName)
            assertThat(restoredWidget1.rank).isEqualTo(expectedWidget1.rank)

            val restoredWidget2 = restoredWidgets[1]
            val expectedWidget2 = fakeState.widgets[1]
            assertThat(restoredWidget2.widgetId).isEqualTo(12)
            assertThat(restoredWidget2.componentName).isEqualTo(expectedWidget2.componentName)
            assertThat(restoredWidget2.rank).isEqualTo(expectedWidget2.rank)

            // Verify widget 13 removed since it is not restored
            verify(appWidgetHost).deleteAppWidgetId(13)
        }

    @Test
    fun restoreWidgets_onlySomeWidgetsGotNewIds() =
        testScope.runTest {
            // Write fake state to file
            backupUtils.writeBytesToDisk(fakeState.toByteArray())

            // Set up app widget host with widget ids. Widget 2 gets a new id: 12, but widget 1 does
            // not.
            setAppWidgetIds(listOf(1, 12))

            // Restore widgets.
            underTest.restoreWidgets(mapOf(Pair(2, 12)))
            runCurrent()

            // Verify widget 1 and 2 are restored, and are now 1 and 12.
            verify(communalWidgetDao).restoreCommunalHubState(communalHubStateCaptor.capture())
            val restoredWidgets = communalHubStateCaptor.firstValue.widgets.toList()
            assertThat(restoredWidgets).hasSize(2)

            val restoredWidget1 = restoredWidgets[0]
            val expectedWidget1 = fakeState.widgets[0]
            assertThat(restoredWidget1.widgetId).isEqualTo(1)
            assertThat(restoredWidget1.componentName).isEqualTo(expectedWidget1.componentName)
            assertThat(restoredWidget1.rank).isEqualTo(expectedWidget1.rank)

            val restoredWidget2 = restoredWidgets[1]
            val expectedWidget2 = fakeState.widgets[1]
            assertThat(restoredWidget2.widgetId).isEqualTo(12)
            assertThat(restoredWidget2.componentName).isEqualTo(expectedWidget2.componentName)
            assertThat(restoredWidget2.rank).isEqualTo(expectedWidget2.rank)
        }

    @Test
    fun restoreWidgets_undefinedUser_restoredAsMain() =
        testScope.runTest {
            // Write two widgets to file, both of which have user serial number undefined.
            val fakeState =
                CommunalHubState().apply {
                    widgets =
                        listOf(
                                CommunalHubState.CommunalWidgetItem().apply {
                                    widgetId = 1
                                    componentName = "pk_name/fake_widget_1"
                                    rank = 1
                                    userSerialNumber =
                                        CommunalWidgetItem.USER_SERIAL_NUMBER_UNDEFINED
                                },
                                CommunalHubState.CommunalWidgetItem().apply {
                                    widgetId = 2
                                    componentName = "pk_name/fake_widget_2"
                                    rank = 2
                                    userSerialNumber =
                                        CommunalWidgetItem.USER_SERIAL_NUMBER_UNDEFINED
                                },
                            )
                            .toTypedArray()
                }
            backupUtils.writeBytesToDisk(fakeState.toByteArray())

            // Set up app widget host with widget ids.
            setAppWidgetIds(listOf(11, 12))

            // Restore widgets.
            underTest.restoreWidgets(mapOf(Pair(1, 11), Pair(2, 12)))
            runCurrent()

            // Verify widget 1 and 2 are restored with the main user.
            verify(communalWidgetDao).restoreCommunalHubState(communalHubStateCaptor.capture())
            val restoredWidgets = communalHubStateCaptor.firstValue.widgets.toList()
            assertThat(restoredWidgets).hasSize(2)

            val restoredWidget1 = restoredWidgets[0]
            assertThat(restoredWidget1.widgetId).isEqualTo(11)
            assertThat(restoredWidget1.userSerialNumber).isEqualTo(testUserSerialNumber(mainUser))

            val restoredWidget2 = restoredWidgets[1]
            assertThat(restoredWidget2.widgetId).isEqualTo(12)
            assertThat(restoredWidget2.userSerialNumber).isEqualTo(testUserSerialNumber(mainUser))
        }

    @Test
    fun restoreWidgets_workProfileNotRestored_widgetSkipped() =
        testScope.runTest {
            // Write fake state to file
            backupUtils.writeBytesToDisk(fakeStateWithWorkProfile.toByteArray())

            // Set up app widget host with widget ids.
            // (b/349852237) It's possible that the platform restores widgets even though their user
            // is not restored.
            setAppWidgetIds(listOf(11, 12))

            // Restore widgets.
            underTest.restoreWidgets(mapOf(Pair(1, 11), Pair(2, 12)))
            runCurrent()

            // Verify only widget 1 is restored. Widget 2 is skipped because it belongs to a work
            // profile, which is not restored.
            verify(communalWidgetDao).restoreCommunalHubState(communalHubStateCaptor.capture())
            val restoredWidgets = communalHubStateCaptor.firstValue.widgets.toList()
            assertThat(restoredWidgets).hasSize(1)

            val restoredWidget = restoredWidgets[0]
            assertThat(restoredWidget.widgetId).isEqualTo(11)
            assertThat(restoredWidget.userSerialNumber).isEqualTo(testUserSerialNumber(mainUser))
        }

    @Test
    fun restoreWidgets_workProfileRestored_manuallyBindWidget() =
        testScope.runTest {
            // Write fake state to file
            backupUtils.writeBytesToDisk(fakeStateWithWorkProfile.toByteArray())

            // Set up app widget host with widget ids.
            // (b/349852237) It's possible that the platform restores widgets even though their user
            // is not restored.
            setAppWidgetIds(listOf(11, 12))

            // Restore work profile.
            restoreUser(workProfile)

            val newWidgetId = 13
            whenever(communalWidgetHost.allocateIdAndBindWidget(any(), any()))
                .thenReturn(newWidgetId)

            // Restore widgets.
            underTest.restoreWidgets(mapOf(Pair(1, 11), Pair(2, 12)))
            runCurrent()

            // Verify widget 1 is restored.
            verify(communalWidgetDao).restoreCommunalHubState(communalHubStateCaptor.capture())
            val restoredWidgets = communalHubStateCaptor.firstValue.widgets.toList()
            assertThat(restoredWidgets).hasSize(1)

            val restoredWidget = restoredWidgets[0]
            assertThat(restoredWidget.widgetId).isEqualTo(11)
            assertThat(restoredWidget.userSerialNumber).isEqualTo(testUserSerialNumber(mainUser))

            // Verify widget 2 (now 12) is removed from platform
            verify(appWidgetHost).deleteAppWidgetId(12)

            // Verify work profile widget is manually bound
            verify(communalWidgetDao)
                .addWidget(
                    eq(newWidgetId),
                    componentNameCaptor.capture(),
                    eq(2),
                    eq(testUserSerialNumber(workProfile)),
                    anyInt(),
                )

            assertThat(componentNameCaptor.firstValue)
                .isEqualTo(ComponentName("pk_name", "fake_widget_2"))
        }

    @Test
    fun pendingWidgets() =
        testScope.runTest {
            fakeWidgets.value =
                mapOf(
                    CommunalItemRank(uid = 1L, rank = 1) to
                        CommunalWidgetItem(uid = 1L, 1, "pk_1/cls_1", 1L, 0, 3),
                    CommunalItemRank(uid = 2L, rank = 2) to
                        CommunalWidgetItem(uid = 2L, 2, "pk_2/cls_2", 2L, 0, 3),
                )

            // Widget 1 is installed
            fakeProviders.value = mapOf(1 to providerInfoA)

            // Widget 2 is pending install
            val fakeIcon = mock<Bitmap>()
            packageChangeRepository.setInstallSessions(
                listOf(
                    PackageInstallSession(
                        sessionId = 1,
                        packageName = "pk_2",
                        icon = fakeIcon,
                        user = mainUser,
                    )
                )
            )

            val communalWidgets by collectLastValue(underTest.communalWidgets)
            assertThat(communalWidgets)
                .containsExactly(
                    CommunalWidgetContentModel.Available(
                        appWidgetId = 1,
                        providerInfo = providerInfoA,
                        rank = 1,
                        spanY = 3,
                    ),
                    CommunalWidgetContentModel.Pending(
                        appWidgetId = 2,
                        rank = 2,
                        componentName = ComponentName("pk_2", "cls_2"),
                        icon = fakeIcon,
                        user = mainUser,
                        spanY = 3,
                    ),
                )
        }

    @Test
    fun pendingWidgets_pendingWidgetBecomesAvailableAfterInstall() =
        testScope.runTest {
            fakeWidgets.value =
                mapOf(
                    CommunalItemRank(uid = 1L, rank = 1) to
                        CommunalWidgetItem(uid = 1L, 1, "pk_1/cls_1", 1L, 0, 3)
                )

            // Widget 1 is pending install
            val fakeIcon = mock<Bitmap>()
            packageChangeRepository.setInstallSessions(
                listOf(
                    PackageInstallSession(
                        sessionId = 1,
                        packageName = "pk_1",
                        icon = fakeIcon,
                        user = mainUser,
                    )
                )
            )

            val communalWidgets by collectLastValue(underTest.communalWidgets)
            assertThat(communalWidgets)
                .containsExactly(
                    CommunalWidgetContentModel.Pending(
                        appWidgetId = 1,
                        rank = 1,
                        componentName = ComponentName("pk_1", "cls_1"),
                        icon = fakeIcon,
                        user = mainUser,
                        spanY = 3,
                    )
                )

            // Package for widget 1 finished installing
            packageChangeRepository.setInstallSessions(emptyList())

            // Provider info for widget 1 becomes available
            fakeProviders.value = mapOf(1 to providerInfoA)

            runCurrent()

            assertThat(communalWidgets)
                .containsExactly(
                    CommunalWidgetContentModel.Available(
                        appWidgetId = 1,
                        providerInfo = providerInfoA,
                        rank = 1,
                        spanY = 3,
                    )
                )
        }

    @Test
    @EnableFlags(FLAG_COMMUNAL_WIDGET_RESIZING)
    fun updateWidgetSpanY_updatesWidgetInDaoAndRequestsBackup() =
        testScope.runTest {
            val widgetId = 1
            val newSpanY = 6
            val widgetIdToRankMap = emptyMap<Int, Int>()

            underTest.resizeWidget(widgetId, newSpanY, widgetIdToRankMap)
            runCurrent()

            verify(communalWidgetDao).resizeWidget(widgetId, newSpanY, widgetIdToRankMap)
            verify(backupManager).dataChanged()
        }

    private fun setAppWidgetIds(ids: List<Int>) {
        whenever(appWidgetHost.appWidgetIds).thenReturn(ids.toIntArray())
    }

    // Commonly the user id and user serial number are the same, but for testing purposes use a
    // simple algorithm to map a user id to a different user serial number to make sure the correct
    // value is used.
    private fun testUserSerialNumber(user: UserHandle): Int {
        return user.identifier + 100
    }

    private fun restoreUser(user: UserHandle) {
        whenever(backupManager.getUserForAncestralSerialNumber(user.identifier.toLong()))
            .thenReturn(user)
        whenever(userManager.getUserSerialNumber(user.identifier))
            .thenReturn(testUserSerialNumber(user))
    }

    private companion object {
        val PROVIDER_INFO_REQUIRES_CONFIGURATION =
            AppWidgetProviderInfo().apply { configure = ComponentName("test.pkg", "test.cmp") }
        val PROVIDER_INFO_CONFIGURATION_OPTIONAL =
            AppWidgetProviderInfo().apply {
                configure = ComponentName("test.pkg", "test.cmp")
                widgetFeatures =
                    WIDGET_FEATURE_CONFIGURATION_OPTIONAL or WIDGET_FEATURE_RECONFIGURABLE
            }
        val fakeState =
            CommunalHubState().apply {
                widgets =
                    listOf(
                            CommunalHubState.CommunalWidgetItem().apply {
                                widgetId = 1
                                componentName = "pk_name/fake_widget_1"
                                rank = 1
                                userSerialNumber = 0
                            },
                            CommunalHubState.CommunalWidgetItem().apply {
                                widgetId = 2
                                componentName = "pk_name/fake_widget_2"
                                rank = 2
                                userSerialNumber = 0
                            },
                        )
                        .toTypedArray()
            }
        val fakeStateWithWorkProfile =
            CommunalHubState().apply {
                widgets =
                    listOf(
                            CommunalHubState.CommunalWidgetItem().apply {
                                widgetId = 1
                                componentName = "pk_name/fake_widget_1"
                                rank = 1
                                userSerialNumber = 0
                            },
                            CommunalHubState.CommunalWidgetItem().apply {
                                widgetId = 2
                                componentName = "pk_name/fake_widget_2"
                                rank = 2
                                userSerialNumber = 10
                            },
                        )
                        .toTypedArray()
            }
    }
}
