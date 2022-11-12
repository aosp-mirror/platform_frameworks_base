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

import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SystemUIAppComponentFactoryBase
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.quickaffordance.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceSelectionManager
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.KeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.keyguard.data.content.KeyguardQuickAffordanceProviderClient as Client
import com.android.systemui.shared.keyguard.data.content.KeyguardQuickAffordanceProviderContract as Contract
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardQuickAffordanceProviderTest : SysuiTestCase() {

    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var activityStarter: ActivityStarter

    private lateinit var underTest: KeyguardQuickAffordanceProvider

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest = KeyguardQuickAffordanceProvider()
        val quickAffordanceRepository =
            KeyguardQuickAffordanceRepository(
                scope = CoroutineScope(IMMEDIATE),
                backgroundDispatcher = IMMEDIATE,
                selectionManager = KeyguardQuickAffordanceSelectionManager(),
                configs =
                    setOf(
                        FakeKeyguardQuickAffordanceConfig(
                            key = AFFORDANCE_1,
                            pickerIconResourceId = 1,
                        ),
                        FakeKeyguardQuickAffordanceConfig(
                            key = AFFORDANCE_2,
                            pickerIconResourceId = 2,
                        ),
                    ),
            )
        underTest.interactor =
            KeyguardQuickAffordanceInteractor(
                keyguardInteractor =
                    KeyguardInteractor(
                        repository = FakeKeyguardRepository(),
                    ),
                registry = mock(),
                lockPatternUtils = lockPatternUtils,
                keyguardStateController = keyguardStateController,
                userTracker = userTracker,
                activityStarter = activityStarter,
                featureFlags =
                    FakeFeatureFlags().apply {
                        set(Flags.CUSTOMIZABLE_LOCK_SCREEN_QUICK_AFFORDANCES, true)
                    },
                repository = { quickAffordanceRepository },
            )

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

    @Test
    fun `onAttachInfo - reportsContext`() {
        val callback: SystemUIAppComponentFactoryBase.ContextAvailableCallback = mock()
        underTest.setContextAvailableCallback(callback)

        underTest.attachInfo(context, null)

        verify(callback).onContextAvailable(context)
    }

    @Test
    fun getType() {
        assertThat(underTest.getType(Contract.AffordanceTable.URI))
            .isEqualTo(
                "vnd.android.cursor.dir/vnd." +
                    "${Contract.AUTHORITY}.${Contract.AffordanceTable.TABLE_NAME}"
            )
        assertThat(underTest.getType(Contract.SlotTable.URI))
            .isEqualTo(
                "vnd.android.cursor.dir/vnd.${Contract.AUTHORITY}.${Contract.SlotTable.TABLE_NAME}"
            )
        assertThat(underTest.getType(Contract.SelectionTable.URI))
            .isEqualTo(
                "vnd.android.cursor.dir/vnd." +
                    "${Contract.AUTHORITY}.${Contract.SelectionTable.TABLE_NAME}"
            )
    }

    @Test
    fun `insert and query selection`() =
        runBlocking(IMMEDIATE) {
            val slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START
            val affordanceId = AFFORDANCE_2

            Client.insertSelection(
                context = context,
                slotId = slotId,
                affordanceId = affordanceId,
                dispatcher = IMMEDIATE,
            )

            assertThat(
                    Client.querySelections(
                        context = context,
                        dispatcher = IMMEDIATE,
                    )
                )
                .isEqualTo(
                    listOf(
                        Client.Selection(
                            slotId = slotId,
                            affordanceId = affordanceId,
                        )
                    )
                )
        }

    @Test
    fun `query slots`() =
        runBlocking(IMMEDIATE) {
            assertThat(
                    Client.querySlots(
                        context = context,
                        dispatcher = IMMEDIATE,
                    )
                )
                .isEqualTo(
                    listOf(
                        Client.Slot(
                            id = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                            capacity = 1,
                        ),
                        Client.Slot(
                            id = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                            capacity = 1,
                        ),
                    )
                )
        }

    @Test
    fun `query affordances`() =
        runBlocking(IMMEDIATE) {
            assertThat(
                    Client.queryAffordances(
                        context = context,
                        dispatcher = IMMEDIATE,
                    )
                )
                .isEqualTo(
                    listOf(
                        Client.Affordance(
                            id = AFFORDANCE_1,
                            name = AFFORDANCE_1,
                            iconResourceId = 1,
                        ),
                        Client.Affordance(
                            id = AFFORDANCE_2,
                            name = AFFORDANCE_2,
                            iconResourceId = 2,
                        ),
                    )
                )
        }

    @Test
    fun `delete and query selection`() =
        runBlocking(IMMEDIATE) {
            Client.insertSelection(
                context = context,
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                affordanceId = AFFORDANCE_1,
                dispatcher = IMMEDIATE,
            )
            Client.insertSelection(
                context = context,
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                affordanceId = AFFORDANCE_2,
                dispatcher = IMMEDIATE,
            )

            Client.deleteSelection(
                context = context,
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                affordanceId = AFFORDANCE_2,
                dispatcher = IMMEDIATE,
            )

            assertThat(
                    Client.querySelections(
                        context = context,
                        dispatcher = IMMEDIATE,
                    )
                )
                .isEqualTo(
                    listOf(
                        Client.Selection(
                            slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                            affordanceId = AFFORDANCE_1,
                        )
                    )
                )
        }

    @Test
    fun `delete all selections in a slot`() =
        runBlocking(IMMEDIATE) {
            Client.insertSelection(
                context = context,
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                affordanceId = AFFORDANCE_1,
                dispatcher = IMMEDIATE,
            )
            Client.insertSelection(
                context = context,
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                affordanceId = AFFORDANCE_2,
                dispatcher = IMMEDIATE,
            )

            Client.deleteAllSelections(
                context = context,
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                dispatcher = IMMEDIATE,
            )

            assertThat(
                    Client.querySelections(
                        context = context,
                        dispatcher = IMMEDIATE,
                    )
                )
                .isEqualTo(
                    listOf(
                        Client.Selection(
                            slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                            affordanceId = AFFORDANCE_1,
                        )
                    )
                )
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
        private const val AFFORDANCE_1 = "affordance_1"
        private const val AFFORDANCE_2 = "affordance_2"
    }
}
