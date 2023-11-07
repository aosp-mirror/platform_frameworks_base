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

package com.android.systemui.qs.tiles.viewmodel

import androidx.test.filters.MediumTest
import com.android.settingslib.RestrictedLockUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.tiles.base.analytics.QSTileAnalytics
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.DisabledByPolicyInteractor
import com.android.systemui.qs.tiles.base.interactor.FakeDisabledByPolicyInteractor
import com.android.systemui.qs.tiles.base.interactor.FakeQSTileDataInteractor
import com.android.systemui.qs.tiles.base.interactor.FakeQSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.base.logging.QSTileLogger
import com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelImpl
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameter
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/** Tests all possible [QSTileUserAction]s. If you need */
@MediumTest
@RunWith(Parameterized::class)
@OptIn(ExperimentalCoroutinesApi::class)
class QSTileViewModelUserInputTest : SysuiTestCase() {

    @Mock private lateinit var qsTileLogger: QSTileLogger
    @Mock private lateinit var qsTileAnalytics: QSTileAnalytics

    @Parameter lateinit var userAction: QSTileUserAction

    private val tileConfig =
        QSTileConfigTestBuilder.build { policy = QSTilePolicy.Restricted("test_restriction") }

    private val userRepository = FakeUserRepository()
    private val tileDataInteractor = FakeQSTileDataInteractor<String>()
    private val tileUserActionInteractor = FakeQSTileUserActionInteractor<String>()
    private val disabledByPolicyInteractor = FakeDisabledByPolicyInteractor()
    private val falsingManager = FalsingManagerFake()

    private val testCoroutineDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testCoroutineDispatcher)

    private lateinit var underTest: QSTileViewModel

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest = createViewModel(testScope)
    }

    @Test
    fun userInputTriggersData() =
        testScope.runTest {
            tileDataInteractor.emitData("initial_data")
            underTest.state.launchIn(backgroundScope)
            runCurrent()

            underTest.onActionPerformed(userAction)
            runCurrent()

            assertThat(tileDataInteractor.triggers.last())
                .isInstanceOf(DataUpdateTrigger.UserInput::class.java)
            verify(qsTileLogger)
                .logUserAction(eq(userAction), eq(tileConfig.tileSpec), eq(true), eq(true))
            verify(qsTileLogger)
                .logUserActionPipeline(
                    eq(tileConfig.tileSpec),
                    eq(userAction),
                    any(),
                    eq("initial_data")
                )
            verify(qsTileAnalytics).trackUserAction(eq(tileConfig), eq(userAction))
        }

    @Test
    fun disabledByPolicyUserInputIsSkipped() =
        testScope.runTest {
            underTest.state.launchIn(backgroundScope)
            disabledByPolicyInteractor.policyResult =
                DisabledByPolicyInteractor.PolicyResult.TileDisabled(
                    RestrictedLockUtils.EnforcedAdmin()
                )
            runCurrent()

            underTest.onActionPerformed(userAction)
            runCurrent()

            assertThat(tileDataInteractor.triggers.last())
                .isNotInstanceOf(DataUpdateTrigger.UserInput::class.java)
            verify(qsTileLogger)
                .logUserActionRejectedByPolicy(eq(userAction), eq(tileConfig.tileSpec))
            verify(qsTileAnalytics, never()).trackUserAction(any(), any())
        }

    @Test
    fun falsedUserInputIsSkipped() =
        testScope.runTest {
            underTest.state.launchIn(backgroundScope)
            falsingManager.setFalseLongTap(true)
            falsingManager.setFalseTap(true)
            runCurrent()

            underTest.onActionPerformed(userAction)
            runCurrent()

            assertThat(tileDataInteractor.triggers.last())
                .isNotInstanceOf(DataUpdateTrigger.UserInput::class.java)
            verify(qsTileLogger)
                .logUserActionRejectedByFalsing(eq(userAction), eq(tileConfig.tileSpec))
            verify(qsTileAnalytics, never()).trackUserAction(any(), any())
        }

    @Test
    fun userInputIsThrottled() =
        testScope.runTest {
            val inputCount = 100
            underTest.state.launchIn(backgroundScope)

            repeat(inputCount) { underTest.onActionPerformed(userAction) }
            runCurrent()

            assertThat(tileDataInteractor.triggers.size).isLessThan(inputCount)
        }

    private fun createViewModel(scope: TestScope): QSTileViewModel =
        QSTileViewModelImpl(
            tileConfig,
            { tileUserActionInteractor },
            { tileDataInteractor },
            {
                object : QSTileDataToStateMapper<String> {
                    override fun map(config: QSTileConfig, data: String): QSTileState =
                        QSTileState.build(
                            { Icon.Resource(0, ContentDescription.Resource(0)) },
                            data
                        ) {}
                }
            },
            disabledByPolicyInteractor,
            userRepository,
            falsingManager,
            qsTileAnalytics,
            qsTileLogger,
            FakeSystemClock(),
            testCoroutineDispatcher,
            scope.backgroundScope,
        )

    companion object {

        @JvmStatic
        @Parameterized.Parameters
        fun data(): Iterable<QSTileUserAction> =
            listOf(
                QSTileUserAction.Click(null),
                QSTileUserAction.LongClick(null),
            )
    }
}
