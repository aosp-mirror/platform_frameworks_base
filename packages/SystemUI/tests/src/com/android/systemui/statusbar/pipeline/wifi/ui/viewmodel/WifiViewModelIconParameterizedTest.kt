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
 */

package com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.test.filters.SmallTest
import com.android.settingslib.AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH
import com.android.settingslib.AccessibilityContentDescriptions.WIFI_NO_CONNECTION
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_FULL_ICONS
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_NO_INTERNET_ICONS
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_NO_NETWORK
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModel
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModelImpl
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiConstants
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel.Companion.NO_INTERNET
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(Parameterized::class)
internal class WifiViewModelIconParameterizedTest(private val testCase: TestCase) :
    SysuiTestCase() {

    private lateinit var underTest: WifiViewModel

    @Mock private lateinit var statusBarPipelineFlags: StatusBarPipelineFlags
    @Mock private lateinit var logger: ConnectivityPipelineLogger
    @Mock private lateinit var tableLogBuffer: TableLogBuffer
    @Mock private lateinit var connectivityConstants: ConnectivityConstants
    @Mock private lateinit var wifiConstants: WifiConstants
    private lateinit var airplaneModeRepository: FakeAirplaneModeRepository
    private lateinit var connectivityRepository: FakeConnectivityRepository
    private lateinit var wifiRepository: FakeWifiRepository
    private lateinit var interactor: WifiInteractor
    private lateinit var airplaneModeViewModel: AirplaneModeViewModel
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        airplaneModeRepository = FakeAirplaneModeRepository()
        connectivityRepository = FakeConnectivityRepository()
        wifiRepository = FakeWifiRepository()
        wifiRepository.setIsWifiEnabled(true)
        interactor = WifiInteractorImpl(connectivityRepository, wifiRepository)
        scope = CoroutineScope(IMMEDIATE)
        airplaneModeViewModel =
            AirplaneModeViewModelImpl(
                AirplaneModeInteractor(
                    airplaneModeRepository,
                    connectivityRepository,
                ),
                logger,
                scope,
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun wifiIcon() =
        runBlocking(IMMEDIATE) {
            wifiRepository.setIsWifiEnabled(testCase.enabled)
            wifiRepository.setIsWifiDefault(testCase.isDefault)
            connectivityRepository.setForceHiddenIcons(
                if (testCase.forceHidden) {
                    setOf(ConnectivitySlot.WIFI)
                } else {
                    setOf()
                }
            )
            whenever(wifiConstants.alwaysShowIconIfEnabled)
                .thenReturn(testCase.alwaysShowIconWhenEnabled)
            whenever(connectivityConstants.hasDataCapabilities)
                .thenReturn(testCase.hasDataCapabilities)
            underTest =
                WifiViewModel(
                    airplaneModeViewModel,
                    connectivityConstants,
                    context,
                    logger,
                    tableLogBuffer,
                    interactor,
                    scope,
                    statusBarPipelineFlags,
                    wifiConstants,
                )

            val iconFlow = underTest.home.wifiIcon
            val job = iconFlow.launchIn(this)

            // WHEN we set a certain network
            wifiRepository.setWifiNetwork(testCase.network)
            yield()

            // THEN we get the expected icon
            val actualIcon = iconFlow.value
            when (testCase.expected) {
                null -> {
                    assertThat(actualIcon).isInstanceOf(WifiIcon.Hidden::class.java)
                }
                else -> {
                    assertThat(actualIcon).isInstanceOf(WifiIcon.Visible::class.java)
                    val actualIconVisible = actualIcon as WifiIcon.Visible
                    assertThat(actualIconVisible.icon.res).isEqualTo(testCase.expected.iconResource)
                    val expectedContentDescription =
                        testCase.expected.contentDescription.invoke(context)
                    assertThat(actualIconVisible.contentDescription.loadContentDescription(context))
                        .isEqualTo(expectedContentDescription)
                }
            }

            job.cancel()
        }

    internal data class Expected(
        /** The resource that should be used for the icon. */
        @DrawableRes val iconResource: Int,

        /** A function that, given a context, calculates the correct content description string. */
        val contentDescription: (Context) -> String,

        /** A human-readable description used for the test names. */
        val description: String,
    ) {
        override fun toString() = description
    }

    // Note: We use default values for the boolean parameters to reflect a "typical configuration"
    //   for wifi. This allows each TestCase to only define the parameter values that are critical
    //   for the test function.
    internal data class TestCase(
        val enabled: Boolean = true,
        val forceHidden: Boolean = false,
        val alwaysShowIconWhenEnabled: Boolean = false,
        val hasDataCapabilities: Boolean = true,
        val isDefault: Boolean = false,
        val network: WifiNetworkModel,

        /** The expected output. Null if we expect the output to be hidden. */
        val expected: Expected?
    ) {
        override fun toString(): String {
            return "when INPUT(enabled=$enabled, " +
                "forceHidden=$forceHidden, " +
                "showWhenEnabled=$alwaysShowIconWhenEnabled, " +
                "hasDataCaps=$hasDataCapabilities, " +
                "isDefault=$isDefault, " +
                "network=$network) then " +
                "EXPECTED($expected)"
        }
    }

    companion object {
        @Parameters(name = "{0}") @JvmStatic fun data(): Collection<TestCase> = testData

        private val testData: List<TestCase> =
            listOf(
                // Enabled = false => no networks shown
                TestCase(
                    enabled = false,
                    network =
                        WifiNetworkModel.CarrierMerged(NETWORK_ID, subscriptionId = 1, level = 1),
                    expected = null,
                ),
                TestCase(
                    enabled = false,
                    network = WifiNetworkModel.Inactive,
                    expected = null,
                ),
                TestCase(
                    enabled = false,
                    network = WifiNetworkModel.Active(NETWORK_ID, isValidated = false, level = 1),
                    expected = null,
                ),
                TestCase(
                    enabled = false,
                    network = WifiNetworkModel.Active(NETWORK_ID, isValidated = true, level = 3),
                    expected = null,
                ),

                // forceHidden = true => no networks shown
                TestCase(
                    forceHidden = true,
                    network =
                        WifiNetworkModel.CarrierMerged(NETWORK_ID, subscriptionId = 1, level = 1),
                    expected = null,
                ),
                TestCase(
                    forceHidden = true,
                    network = WifiNetworkModel.Inactive,
                    expected = null,
                ),
                TestCase(
                    enabled = false,
                    network = WifiNetworkModel.Active(NETWORK_ID, isValidated = false, level = 2),
                    expected = null,
                ),
                TestCase(
                    forceHidden = true,
                    network = WifiNetworkModel.Active(NETWORK_ID, isValidated = true, level = 1),
                    expected = null,
                ),

                // alwaysShowIconWhenEnabled = true => all Inactive and Active networks shown
                TestCase(
                    alwaysShowIconWhenEnabled = true,
                    network = WifiNetworkModel.Inactive,
                    expected =
                        Expected(
                            iconResource = WIFI_NO_NETWORK,
                            contentDescription = { context ->
                                "${context.getString(WIFI_NO_CONNECTION)}," +
                                    context.getString(NO_INTERNET)
                            },
                            description = "No network icon",
                        ),
                ),
                TestCase(
                    alwaysShowIconWhenEnabled = true,
                    network = WifiNetworkModel.Active(NETWORK_ID, isValidated = false, level = 4),
                    expected =
                        Expected(
                            iconResource = WIFI_NO_INTERNET_ICONS[4],
                            contentDescription = { context ->
                                "${context.getString(WIFI_CONNECTION_STRENGTH[4])}," +
                                    context.getString(NO_INTERNET)
                            },
                            description = "No internet level 4 icon",
                        ),
                ),
                TestCase(
                    alwaysShowIconWhenEnabled = true,
                    network = WifiNetworkModel.Active(NETWORK_ID, isValidated = true, level = 2),
                    expected =
                        Expected(
                            iconResource = WIFI_FULL_ICONS[2],
                            contentDescription = { context ->
                                context.getString(WIFI_CONNECTION_STRENGTH[2])
                            },
                            description = "Full internet level 2 icon",
                        ),
                ),

                // hasDataCapabilities = false => all Inactive and Active networks shown
                TestCase(
                    hasDataCapabilities = false,
                    network = WifiNetworkModel.Inactive,
                    expected =
                        Expected(
                            iconResource = WIFI_NO_NETWORK,
                            contentDescription = { context ->
                                "${context.getString(WIFI_NO_CONNECTION)}," +
                                    context.getString(NO_INTERNET)
                            },
                            description = "No network icon",
                        ),
                ),
                TestCase(
                    hasDataCapabilities = false,
                    network = WifiNetworkModel.Active(NETWORK_ID, isValidated = false, level = 2),
                    expected =
                        Expected(
                            iconResource = WIFI_NO_INTERNET_ICONS[2],
                            contentDescription = { context ->
                                "${context.getString(WIFI_CONNECTION_STRENGTH[2])}," +
                                    context.getString(NO_INTERNET)
                            },
                            description = "No internet level 2 icon",
                        ),
                ),
                TestCase(
                    hasDataCapabilities = false,
                    network = WifiNetworkModel.Active(NETWORK_ID, isValidated = true, level = 0),
                    expected =
                        Expected(
                            iconResource = WIFI_FULL_ICONS[0],
                            contentDescription = { context ->
                                context.getString(WIFI_CONNECTION_STRENGTH[0])
                            },
                            description = "Full internet level 0 icon",
                        ),
                ),

                // isDefault = true => all Inactive and Active networks shown
                TestCase(
                    isDefault = true,
                    network = WifiNetworkModel.Inactive,
                    expected =
                        Expected(
                            iconResource = WIFI_NO_NETWORK,
                            contentDescription = { context ->
                                "${context.getString(WIFI_NO_CONNECTION)}," +
                                    context.getString(NO_INTERNET)
                            },
                            description = "No network icon",
                        ),
                ),
                TestCase(
                    isDefault = true,
                    network = WifiNetworkModel.Active(NETWORK_ID, isValidated = false, level = 3),
                    expected =
                        Expected(
                            iconResource = WIFI_NO_INTERNET_ICONS[3],
                            contentDescription = { context ->
                                "${context.getString(WIFI_CONNECTION_STRENGTH[3])}," +
                                    context.getString(NO_INTERNET)
                            },
                            description = "No internet level 3 icon",
                        ),
                ),
                TestCase(
                    isDefault = true,
                    network = WifiNetworkModel.Active(NETWORK_ID, isValidated = true, level = 1),
                    expected =
                        Expected(
                            iconResource = WIFI_FULL_ICONS[1],
                            contentDescription = { context ->
                                context.getString(WIFI_CONNECTION_STRENGTH[1])
                            },
                            description = "Full internet level 1 icon",
                        ),
                ),

                // network = CarrierMerged => not shown
                TestCase(
                    network =
                        WifiNetworkModel.CarrierMerged(NETWORK_ID, subscriptionId = 1, level = 1),
                    expected = null,
                ),

                // network = Inactive => not shown
                TestCase(
                    network = WifiNetworkModel.Inactive,
                    expected = null,
                ),

                // network = Unavailable => not shown
                TestCase(
                    network = WifiNetworkModel.Unavailable,
                    expected = null,
                ),

                // network = Active & validated = false => not shown
                TestCase(
                    network = WifiNetworkModel.Active(NETWORK_ID, isValidated = false, level = 3),
                    expected = null,
                ),

                // network = Active & validated = true => shown
                TestCase(
                    network = WifiNetworkModel.Active(NETWORK_ID, isValidated = true, level = 4),
                    expected =
                        Expected(
                            iconResource = WIFI_FULL_ICONS[4],
                            contentDescription = { context ->
                                context.getString(WIFI_CONNECTION_STRENGTH[4])
                            },
                            description = "Full internet level 4 icon",
                        ),
                ),
            )
    }
}

private val IMMEDIATE = Dispatchers.Main.immediate
private const val NETWORK_ID = 789
