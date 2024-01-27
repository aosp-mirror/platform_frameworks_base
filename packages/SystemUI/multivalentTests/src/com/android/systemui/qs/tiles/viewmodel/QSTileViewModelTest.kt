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

import android.os.UserHandle
import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectValues
import com.android.systemui.qs.tiles.base.analytics.QSTileAnalytics
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.FakeDisabledByPolicyInteractor
import com.android.systemui.qs.tiles.base.interactor.FakeQSTileDataInteractor
import com.android.systemui.qs.tiles.base.interactor.FakeQSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.base.logging.QSTileLogger
import com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelImpl
import com.android.systemui.user.data.repository.FakeUserRepository
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
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@MediumTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class QSTileViewModelTest : SysuiTestCase() {

    @Mock private lateinit var qsTileLogger: QSTileLogger
    @Mock private lateinit var qsTileAnalytics: QSTileAnalytics

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
    fun stateReceivedForTheData() =
        testScope.runTest {
            val testTileData = "test_tile_data"
            val states = collectValues(underTest.state)
            runCurrent()

            tileDataInteractor.emitData(testTileData)
            runCurrent()

            assertThat(states()).isNotEmpty()
            assertThat(states().first().label).isEqualTo(testTileData)
            verify(qsTileLogger).logInitialRequest(eq(tileConfig.tileSpec))
        }

    @Test
    fun doesntListenDataIfStateIsntListened() =
        testScope.runTest {
            assertThat(tileDataInteractor.dataSubscriptionCount.value).isEqualTo(0)

            underTest.state.launchIn(backgroundScope)
            runCurrent()

            assertThat(tileDataInteractor.dataSubscriptionCount.value).isEqualTo(1)
        }

    @Test
    fun doesntListenAvailabilityIfAvailabilityIsntListened() =
        testScope.runTest {
            assertThat(tileDataInteractor.availabilitySubscriptionCount.value).isEqualTo(0)

            underTest.isAvailable.launchIn(backgroundScope)
            runCurrent()

            assertThat(tileDataInteractor.availabilitySubscriptionCount.value).isEqualTo(1)
        }

    @Test
    fun doesntListedDataAfterDestroy() =
        testScope.runTest {
            underTest.state.launchIn(backgroundScope)
            underTest.isAvailable.launchIn(backgroundScope)
            runCurrent()

            underTest.destroy()
            runCurrent()

            assertThat(tileDataInteractor.dataSubscriptionCount.value).isEqualTo(0)
            assertThat(tileDataInteractor.availabilitySubscriptionCount.value).isEqualTo(0)
        }

    @Test
    fun forceUpdateTriggersData() =
        testScope.runTest {
            underTest.state.launchIn(backgroundScope)
            runCurrent()

            underTest.forceUpdate()
            runCurrent()

            assertThat(tileDataInteractor.triggers.last())
                .isInstanceOf(DataUpdateTrigger.ForceUpdate::class.java)
            verify(qsTileLogger).logForceUpdate(eq(tileConfig.tileSpec))
        }

    @Test
    fun userChangeUpdatesData() =
        testScope.runTest {
            underTest.state.launchIn(backgroundScope)
            runCurrent()

            underTest.onUserChanged(USER)
            runCurrent()

            assertThat(tileDataInteractor.dataRequests.last())
                .isEqualTo(FakeQSTileDataInteractor.DataRequest(USER))
        }

    @Test
    fun userChangeUpdatesAvailability() =
        testScope.runTest {
            underTest.isAvailable.launchIn(backgroundScope)
            runCurrent()

            underTest.onUserChanged(USER)
            runCurrent()

            assertThat(tileDataInteractor.availabilityRequests.last())
                .isEqualTo(FakeQSTileDataInteractor.AvailabilityRequest(USER))
        }

    private fun createViewModel(
        scope: TestScope,
        config: QSTileConfig = tileConfig,
    ): QSTileViewModel =
        QSTileViewModelImpl(
            config,
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

    private companion object {

        val USER = UserHandle.of(1)!!
    }
}
