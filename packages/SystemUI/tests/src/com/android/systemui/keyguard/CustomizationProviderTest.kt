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
 *
 */

package com.android.systemui.keyguard

import android.app.admin.DevicePolicyManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.SurfaceControlViewHost
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SystemUIAppComponentFactoryBase
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dock.DockManagerFake
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceProviderClientFactory
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLegacySettingSyncer
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLocalUserSelectionManager
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceRemoteUserSelectionManager
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordancesMetricsLogger
import com.android.systemui.keyguard.ui.preview.KeyguardPreviewRenderer
import com.android.systemui.keyguard.ui.preview.KeyguardPreviewRendererFactory
import com.android.systemui.keyguard.ui.preview.KeyguardRemotePreviewManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shared.customization.data.content.CustomizationProviderContract as Contract
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class CustomizationProviderTest : SysuiTestCase() {

    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var previewRendererFactory: KeyguardPreviewRendererFactory
    @Mock private lateinit var previewRenderer: KeyguardPreviewRenderer
    @Mock private lateinit var backgroundHandler: Handler
    @Mock private lateinit var previewSurfacePackage: SurfaceControlViewHost.SurfacePackage
    @Mock private lateinit var launchAnimator: DialogTransitionAnimator
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var logger: KeyguardQuickAffordancesMetricsLogger

    private lateinit var dockManager: DockManagerFake
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository

    private lateinit var underTest: CustomizationProvider
    private lateinit var testScope: TestScope

    private val kosmos = testKosmos()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        overrideResource(R.bool.custom_lockscreen_shortcuts_enabled, true)
        whenever(previewRenderer.surfacePackage).thenReturn(previewSurfacePackage)
        whenever(previewRendererFactory.create(any())).thenReturn(previewRenderer)
        whenever(backgroundHandler.looper).thenReturn(TestableLooper.get(this).looper)

        dockManager = DockManagerFake()
        biometricSettingsRepository = FakeBiometricSettingsRepository()

        underTest = CustomizationProvider()
        val testDispatcher = UnconfinedTestDispatcher()
        testScope = TestScope(testDispatcher)
        val localUserSelectionManager =
            KeyguardQuickAffordanceLocalUserSelectionManager(
                context = context,
                userFileManager =
                    mock<UserFileManager>().apply {
                        whenever(
                                getSharedPreferences(
                                    anyString(),
                                    anyInt(),
                                    anyInt(),
                                )
                            )
                            .thenReturn(FakeSharedPreferences())
                    },
                userTracker = userTracker,
                systemSettings = FakeSettings(),
                broadcastDispatcher = fakeBroadcastDispatcher,
            )
        val remoteUserSelectionManager =
            KeyguardQuickAffordanceRemoteUserSelectionManager(
                scope = testScope.backgroundScope,
                userTracker = userTracker,
                clientFactory = FakeKeyguardQuickAffordanceProviderClientFactory(userTracker),
                userHandle = UserHandle.SYSTEM,
            )
        val quickAffordanceRepository =
            KeyguardQuickAffordanceRepository(
                appContext = context,
                scope = testScope.backgroundScope,
                localUserSelectionManager = localUserSelectionManager,
                remoteUserSelectionManager = remoteUserSelectionManager,
                userTracker = userTracker,
                configs =
                    setOf(
                        FakeKeyguardQuickAffordanceConfig(
                            key = AFFORDANCE_1,
                            pickerName = AFFORDANCE_1_NAME,
                            pickerIconResourceId = 1,
                        ),
                        FakeKeyguardQuickAffordanceConfig(
                            key = AFFORDANCE_2,
                            pickerName = AFFORDANCE_2_NAME,
                            pickerIconResourceId = 2,
                        ),
                    ),
                legacySettingSyncer =
                    KeyguardQuickAffordanceLegacySettingSyncer(
                        scope = testScope.backgroundScope,
                        backgroundDispatcher = testDispatcher,
                        secureSettings = FakeSettings(),
                        selectionsManager = localUserSelectionManager,
                    ),
                dumpManager = mock(),
                userHandle = UserHandle.SYSTEM,
            )
        val featureFlags =
            FakeFeatureFlags().apply {
                set(Flags.LOCKSCREEN_CUSTOM_CLOCKS, true)
                set(Flags.WALLPAPER_FULLSCREEN_PREVIEW, true)
            }
        underTest.interactor =
            KeyguardQuickAffordanceInteractor(
                keyguardInteractor =
                    KeyguardInteractorFactory.create(
                            featureFlags = featureFlags,
                            sceneInteractor =
                                mock {
                                    whenever(transitioningTo).thenReturn(MutableStateFlow(null))
                                },
                        )
                        .keyguardInteractor,
                shadeInteractor = kosmos.shadeInteractor,
                lockPatternUtils = lockPatternUtils,
                keyguardStateController = keyguardStateController,
                userTracker = userTracker,
                activityStarter = activityStarter,
                featureFlags = featureFlags,
                repository = { quickAffordanceRepository },
                launchAnimator = launchAnimator,
                logger = logger,
                devicePolicyManager = devicePolicyManager,
                dockManager = dockManager,
                biometricSettingsRepository = biometricSettingsRepository,
                backgroundDispatcher = testDispatcher,
                appContext = mContext,
                sceneInteractor = { kosmos.sceneInteractor },
            )
        underTest.previewManager =
            KeyguardRemotePreviewManager(
                applicationScope = testScope.backgroundScope,
                previewRendererFactory = previewRendererFactory,
                mainDispatcher = testDispatcher,
                backgroundHandler = backgroundHandler,
            )
        underTest.mainDispatcher = UnconfinedTestDispatcher()

        underTest.attachInfoForTesting(
            context,
            ProviderInfo().apply { authority = Contract.AUTHORITY },
        )
        context.contentResolver.addProvider(Contract.AUTHORITY, underTest)
        context.testablePermissions.setPermission(
            Contract.PERMISSION,
            PackageManager.PERMISSION_GRANTED,
        )
    }

    @After
    fun tearDown() {
        mContext
            .getOrCreateTestableResources()
            .removeOverride(R.bool.custom_lockscreen_shortcuts_enabled)
    }

    @Test
    fun onAttachInfo_reportsContext() {
        val callback: SystemUIAppComponentFactoryBase.ContextAvailableCallback = mock()
        underTest.setContextAvailableCallback(callback)

        underTest.attachInfo(context, null)

        verify(callback).onContextAvailable(context)
    }

    @Test
    fun getType() {
        assertThat(underTest.getType(Contract.LockScreenQuickAffordances.AffordanceTable.URI))
            .isEqualTo(
                "vnd.android.cursor.dir/vnd." +
                    "${Contract.AUTHORITY}." +
                    Contract.LockScreenQuickAffordances.qualifiedTablePath(
                        Contract.LockScreenQuickAffordances.AffordanceTable.TABLE_NAME
                    )
            )
        assertThat(underTest.getType(Contract.LockScreenQuickAffordances.SlotTable.URI))
            .isEqualTo(
                "vnd.android.cursor.dir/vnd.${Contract.AUTHORITY}." +
                    Contract.LockScreenQuickAffordances.qualifiedTablePath(
                        Contract.LockScreenQuickAffordances.SlotTable.TABLE_NAME
                    )
            )
        assertThat(underTest.getType(Contract.LockScreenQuickAffordances.SelectionTable.URI))
            .isEqualTo(
                "vnd.android.cursor.dir/vnd." +
                    "${Contract.AUTHORITY}." +
                    Contract.LockScreenQuickAffordances.qualifiedTablePath(
                        Contract.LockScreenQuickAffordances.SelectionTable.TABLE_NAME
                    )
            )
        assertThat(underTest.getType(Contract.FlagsTable.URI))
            .isEqualTo(
                "vnd.android.cursor.dir/vnd." +
                    "${Contract.AUTHORITY}." +
                    Contract.FlagsTable.TABLE_NAME
            )
    }

    @Test
    fun insertAndQuerySelection() =
        testScope.runTest {
            val slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START
            val affordanceId = AFFORDANCE_2
            val affordanceName = AFFORDANCE_2_NAME

            insertSelection(
                slotId = slotId,
                affordanceId = affordanceId,
            )

            assertThat(querySelections())
                .isEqualTo(
                    listOf(
                        Selection(
                            slotId = slotId,
                            affordanceId = affordanceId,
                            affordanceName = affordanceName,
                        )
                    )
                )
        }

    @Test
    fun querySlotsProvidesTwoSlots() =
        testScope.runTest {
            assertThat(querySlots())
                .isEqualTo(
                    listOf(
                        Slot(
                            id = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                            capacity = 1,
                        ),
                        Slot(
                            id = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                            capacity = 1,
                        ),
                    )
                )
        }

    @Test
    fun queryAffordancesProvidesTwoAffordances() =
        testScope.runTest {
            assertThat(queryAffordances())
                .isEqualTo(
                    listOf(
                        Affordance(
                            id = AFFORDANCE_1,
                            name = AFFORDANCE_1_NAME,
                            iconResourceId = 1,
                        ),
                        Affordance(
                            id = AFFORDANCE_2,
                            name = AFFORDANCE_2_NAME,
                            iconResourceId = 2,
                        ),
                    )
                )
        }

    @Test
    fun deleteAndQuerySelection() =
        testScope.runTest {
            insertSelection(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                affordanceId = AFFORDANCE_1,
            )
            insertSelection(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                affordanceId = AFFORDANCE_2,
            )

            context.contentResolver.delete(
                Contract.LockScreenQuickAffordances.SelectionTable.URI,
                "${Contract.LockScreenQuickAffordances.SelectionTable.Columns.SLOT_ID} = ? AND" +
                    " ${Contract.LockScreenQuickAffordances.SelectionTable.Columns.AFFORDANCE_ID}" +
                    " = ?",
                arrayOf(
                    KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                    AFFORDANCE_2,
                ),
            )

            assertThat(querySelections())
                .isEqualTo(
                    listOf(
                        Selection(
                            slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                            affordanceId = AFFORDANCE_1,
                            affordanceName = AFFORDANCE_1_NAME,
                        )
                    )
                )
        }

    @Test
    fun deleteAllSelectionsInAslot() =
        testScope.runTest {
            insertSelection(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                affordanceId = AFFORDANCE_1,
            )
            insertSelection(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                affordanceId = AFFORDANCE_2,
            )

            context.contentResolver.delete(
                Contract.LockScreenQuickAffordances.SelectionTable.URI,
                Contract.LockScreenQuickAffordances.SelectionTable.Columns.SLOT_ID,
                arrayOf(
                    KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                ),
            )

            assertThat(querySelections())
                .isEqualTo(
                    listOf(
                        Selection(
                            slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                            affordanceId = AFFORDANCE_1,
                            affordanceName = AFFORDANCE_1_NAME,
                        )
                    )
                )
        }

    @Test
    fun preview() =
        testScope.runTest {
            val hostToken: IBinder = mock()
            whenever(previewRenderer.hostToken).thenReturn(hostToken)
            val extras = Bundle()

            val result = underTest.call("whatever", "anything", extras)

            verify(previewRenderer).render()
            verify(hostToken).linkToDeath(any(), anyInt())
            assertThat(result!!).isNotNull()
            assertThat(result.get(KeyguardRemotePreviewManager.KEY_PREVIEW_SURFACE_PACKAGE))
                .isEqualTo(previewSurfacePackage)
            assertThat(result.containsKey(KeyguardRemotePreviewManager.KEY_PREVIEW_CALLBACK))
        }

    private fun insertSelection(
        slotId: String,
        affordanceId: String,
    ) {
        context.contentResolver.insert(
            Contract.LockScreenQuickAffordances.SelectionTable.URI,
            ContentValues().apply {
                put(Contract.LockScreenQuickAffordances.SelectionTable.Columns.SLOT_ID, slotId)
                put(
                    Contract.LockScreenQuickAffordances.SelectionTable.Columns.AFFORDANCE_ID,
                    affordanceId
                )
            }
        )
    }

    private fun querySelections(): List<Selection> {
        return context.contentResolver
            .query(
                Contract.LockScreenQuickAffordances.SelectionTable.URI,
                null,
                null,
                null,
                null,
            )
            ?.use { cursor ->
                buildList {
                    val slotIdColumnIndex =
                        cursor.getColumnIndex(
                            Contract.LockScreenQuickAffordances.SelectionTable.Columns.SLOT_ID
                        )
                    val affordanceIdColumnIndex =
                        cursor.getColumnIndex(
                            Contract.LockScreenQuickAffordances.SelectionTable.Columns.AFFORDANCE_ID
                        )
                    val affordanceNameColumnIndex =
                        cursor.getColumnIndex(
                            Contract.LockScreenQuickAffordances.SelectionTable.Columns
                                .AFFORDANCE_NAME
                        )
                    if (
                        slotIdColumnIndex == -1 ||
                            affordanceIdColumnIndex == -1 ||
                            affordanceNameColumnIndex == -1
                    ) {
                        return@buildList
                    }

                    while (cursor.moveToNext()) {
                        add(
                            Selection(
                                slotId = cursor.getString(slotIdColumnIndex),
                                affordanceId = cursor.getString(affordanceIdColumnIndex),
                                affordanceName = cursor.getString(affordanceNameColumnIndex),
                            )
                        )
                    }
                }
            }
            ?: emptyList()
    }

    private fun querySlots(): List<Slot> {
        return context.contentResolver
            .query(
                Contract.LockScreenQuickAffordances.SlotTable.URI,
                null,
                null,
                null,
                null,
            )
            ?.use { cursor ->
                buildList {
                    val idColumnIndex =
                        cursor.getColumnIndex(
                            Contract.LockScreenQuickAffordances.SlotTable.Columns.ID
                        )
                    val capacityColumnIndex =
                        cursor.getColumnIndex(
                            Contract.LockScreenQuickAffordances.SlotTable.Columns.CAPACITY
                        )
                    if (idColumnIndex == -1 || capacityColumnIndex == -1) {
                        return@buildList
                    }

                    while (cursor.moveToNext()) {
                        add(
                            Slot(
                                id = cursor.getString(idColumnIndex),
                                capacity = cursor.getInt(capacityColumnIndex),
                            )
                        )
                    }
                }
            }
            ?: emptyList()
    }

    private fun queryAffordances(): List<Affordance> {
        return context.contentResolver
            .query(
                Contract.LockScreenQuickAffordances.AffordanceTable.URI,
                null,
                null,
                null,
                null,
            )
            ?.use { cursor ->
                buildList {
                    val idColumnIndex =
                        cursor.getColumnIndex(
                            Contract.LockScreenQuickAffordances.AffordanceTable.Columns.ID
                        )
                    val nameColumnIndex =
                        cursor.getColumnIndex(
                            Contract.LockScreenQuickAffordances.AffordanceTable.Columns.NAME
                        )
                    val iconColumnIndex =
                        cursor.getColumnIndex(
                            Contract.LockScreenQuickAffordances.AffordanceTable.Columns.ICON
                        )
                    if (idColumnIndex == -1 || nameColumnIndex == -1 || iconColumnIndex == -1) {
                        return@buildList
                    }

                    while (cursor.moveToNext()) {
                        add(
                            Affordance(
                                id = cursor.getString(idColumnIndex),
                                name = cursor.getString(nameColumnIndex),
                                iconResourceId = cursor.getInt(iconColumnIndex),
                            )
                        )
                    }
                }
            }
            ?: emptyList()
    }

    data class Slot(
        val id: String,
        val capacity: Int,
    )

    data class Affordance(
        val id: String,
        val name: String,
        val iconResourceId: Int,
    )

    data class Selection(
        val slotId: String,
        val affordanceId: String,
        val affordanceName: String,
    )

    companion object {
        private const val AFFORDANCE_1 = "affordance_1"
        private const val AFFORDANCE_2 = "affordance_2"
        private const val AFFORDANCE_1_NAME = "affordance_1_name"
        private const val AFFORDANCE_2_NAME = "affordance_2_name"
    }
}
