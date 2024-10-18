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

package com.android.systemui.statusbar.policy

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.net.Uri
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.backgroundCoroutineContext
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@SmallTest
@EnableFlags(android.app.Flags.FLAG_MODES_UI)
class ZenModesCleanupStartableTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    @Mock private lateinit var notificationManager: NotificationManager

    private lateinit var underTest: ZenModesCleanupStartable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest =
            ZenModesCleanupStartable(
                testScope.backgroundScope,
                kosmos.backgroundCoroutineContext,
                notificationManager,
            )
    }

    @Test
    fun start_withGamingModeZenRule_deletesIt() =
        testScope.runTest {
            whenever(notificationManager.automaticZenRules)
                .thenReturn(
                    mutableMapOf(
                        Pair(
                            "gaming",
                            AutomaticZenRule.Builder(
                                    "Gaming Mode",
                                    Uri.parse(
                                        "android-app://com.android.systemui/game-mode-dnd-controller"
                                    ),
                                )
                                .setPackage("com.android.systemui")
                                .build(),
                        ),
                        Pair(
                            "other",
                            AutomaticZenRule.Builder("Other Mode", Uri.parse("something-else"))
                                .setPackage("com.other.package")
                                .build(),
                        ),
                    )
                )

            underTest.start()
            runCurrent()

            verify(notificationManager).removeAutomaticZenRule(eq("gaming"))
        }

    @Test
    fun start_withoutGamingModeZenRule_doesNothing() =
        testScope.runTest {
            whenever(notificationManager.automaticZenRules)
                .thenReturn(
                    mutableMapOf(
                        Pair(
                            "other",
                            AutomaticZenRule.Builder("Other Mode", Uri.parse("something-else"))
                                .setPackage("com.android.systemui")
                                .build(),
                        )
                    )
                )

            underTest.start()
            runCurrent()

            verify(notificationManager, never()).removeAutomaticZenRule(any())
        }
}
