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

package com.android.systemui.keyguard.data.quickaffordance

import android.content.pm.UserInfo
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.shared.customization.data.content.FakeCustomizationProviderClient
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardQuickAffordanceRemoteUserSelectionManagerTest : SysuiTestCase() {

    @Mock private lateinit var userHandle: UserHandle

    private lateinit var underTest: KeyguardQuickAffordanceRemoteUserSelectionManager

    private lateinit var clientFactory: FakeKeyguardQuickAffordanceProviderClientFactory
    private lateinit var testScope: TestScope
    private lateinit var testDispatcher: TestDispatcher
    private lateinit var userTracker: FakeUserTracker
    private lateinit var client1: FakeCustomizationProviderClient
    private lateinit var client2: FakeCustomizationProviderClient

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(userHandle.identifier).thenReturn(UserHandle.USER_SYSTEM)
        whenever(userHandle.isSystem).thenReturn(true)
        client1 = FakeCustomizationProviderClient()
        client2 = FakeCustomizationProviderClient()

        userTracker = FakeUserTracker()
        userTracker.set(
            userInfos =
                listOf(
                    UserInfo(
                        UserHandle.USER_SYSTEM,
                        "Primary",
                        /* flags= */ 0,
                    ),
                    UserInfo(
                        OTHER_USER_ID_1,
                        "Secondary 1",
                        /* flags= */ 0,
                    ),
                    UserInfo(
                        OTHER_USER_ID_2,
                        "Secondary 2",
                        /* flags= */ 0,
                    ),
                ),
            selectedUserIndex = 0,
        )

        clientFactory =
            FakeKeyguardQuickAffordanceProviderClientFactory(
                userTracker,
            ) { selectedUserId ->
                when (selectedUserId) {
                    OTHER_USER_ID_1 -> client1
                    OTHER_USER_ID_2 -> client2
                    else -> error("No client set-up for user $selectedUserId!")
                }
            }

        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)

        underTest =
            KeyguardQuickAffordanceRemoteUserSelectionManager(
                scope = testScope.backgroundScope,
                userTracker = userTracker,
                clientFactory = clientFactory,
                userHandle = userHandle,
            )
    }

    @Test
    fun selections_primaryUserProcess() =
        testScope.runTest {
            val values = mutableListOf<Map<String, List<String>>>()
            val job = launch { underTest.selections.toList(values) }

            runCurrent()
            assertThat(values.last()).isEmpty()

            client1.insertSelection(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                affordanceId = FakeCustomizationProviderClient.AFFORDANCE_1,
            )
            client2.insertSelection(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                affordanceId = FakeCustomizationProviderClient.AFFORDANCE_2,
            )

            userTracker.set(
                userInfos = userTracker.userProfiles,
                selectedUserIndex = 1,
            )
            runCurrent()
            assertThat(values.last())
                .isEqualTo(
                    mapOf(
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START to
                            listOf(
                                FakeCustomizationProviderClient.AFFORDANCE_1,
                            ),
                    )
                )

            userTracker.set(
                userInfos = userTracker.userProfiles,
                selectedUserIndex = 2,
            )
            runCurrent()
            assertThat(values.last())
                .isEqualTo(
                    mapOf(
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END to
                            listOf(
                                FakeCustomizationProviderClient.AFFORDANCE_2,
                            ),
                    )
                )

            job.cancel()
        }

    @Test
    fun selections_secondaryUserProcess_alwaysEmpty() =
        testScope.runTest {
            whenever(userHandle.isSystem).thenReturn(false)
            val values = mutableListOf<Map<String, List<String>>>()
            val job = launch { underTest.selections.toList(values) }

            runCurrent()
            assertThat(values.last()).isEmpty()

            client1.insertSelection(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                affordanceId = FakeCustomizationProviderClient.AFFORDANCE_1,
            )
            userTracker.set(
                userInfos = userTracker.userProfiles,
                selectedUserIndex = 1,
            )
            runCurrent()
            assertThat(values.last()).isEmpty()

            job.cancel()
        }

    @Test
    fun setSelections() =
        testScope.runTest {
            userTracker.set(
                userInfos = userTracker.userProfiles,
                selectedUserIndex = 1,
            )
            runCurrent()

            underTest.setSelections(
                slotId = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                affordanceIds = listOf(FakeCustomizationProviderClient.AFFORDANCE_1),
            )
            runCurrent()

            assertThat(underTest.getSelections())
                .isEqualTo(
                    mapOf(
                        KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START to
                            listOf(
                                FakeCustomizationProviderClient.AFFORDANCE_1,
                            ),
                    )
                )
        }

    companion object {
        private const val OTHER_USER_ID_1 = UserHandle.MIN_SECONDARY_USER_ID + 1
        private const val OTHER_USER_ID_2 = UserHandle.MIN_SECONDARY_USER_ID + 2
    }
}
