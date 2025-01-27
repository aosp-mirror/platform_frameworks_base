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

package com.android.systemui.qs.tiles.dialog

import android.content.Intent
import android.os.Handler
import android.os.fakeExecutorHandler
import android.platform.test.annotations.EnableFlags
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.telephonyManager
import android.testing.TestableLooper.RunWithLooper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.annotation.UiThreadTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.wifi.WifiEnterpriseRestrictionUtils
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.android.wifitrackerlib.WifiEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.MockitoSession
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
@EnableSceneContainer
@EnableFlags(Flags.FLAG_QS_TILE_DETAILED_VIEW, Flags.FLAG_DUAL_SHADE)
@UiThreadTest
class InternetDetailsContentManagerTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val handler: Handler = kosmos.fakeExecutorHandler
    private val scope: CoroutineScope = mock<CoroutineScope>()
    private val telephonyManager: TelephonyManager = kosmos.telephonyManager
    private val internetWifiEntry: WifiEntry = mock<WifiEntry>()
    private val wifiEntries: List<WifiEntry> = mock<List<WifiEntry>>()
    private val internetAdapter = mock<InternetAdapter>()
    private val internetDetailsContentController: InternetDetailsContentController =
        mock<InternetDetailsContentController>()
    private val keyguard: KeyguardStateController = mock<KeyguardStateController>()
    private val bgExecutor = FakeExecutor(FakeSystemClock())
    private lateinit var internetDetailsContentManager: InternetDetailsContentManager
    private var ethernet: LinearLayout? = null
    private var mobileDataLayout: LinearLayout? = null
    private var mobileToggleSwitch: Switch? = null
    private var wifiToggle: LinearLayout? = null
    private var wifiToggleSwitch: Switch? = null
    private var wifiToggleSummary: TextView? = null
    private var connectedWifi: LinearLayout? = null
    private var wifiList: RecyclerView? = null
    private var seeAll: LinearLayout? = null
    private var wifiScanNotify: LinearLayout? = null
    private var airplaneModeSummaryText: TextView? = null
    private var mockitoSession: MockitoSession? = null
    private var sharedWifiButton: Button? = null
    private lateinit var contentView: View

    @Before
    fun setUp() {
        whenever(telephonyManager.createForSubscriptionId(ArgumentMatchers.anyInt()))
            .thenReturn(telephonyManager)
        whenever(internetWifiEntry.title).thenReturn(WIFI_TITLE)
        whenever(internetWifiEntry.getSummary(false)).thenReturn(WIFI_SUMMARY)
        whenever(internetWifiEntry.isDefaultNetwork).thenReturn(true)
        whenever(internetWifiEntry.hasInternetAccess()).thenReturn(true)
        whenever(wifiEntries.size).thenReturn(1)
        whenever(internetDetailsContentController.getDialogTitleText()).thenReturn(TITLE)
        whenever(internetDetailsContentController.getMobileNetworkTitle(ArgumentMatchers.anyInt()))
            .thenReturn(MOBILE_NETWORK_TITLE)
        whenever(
                internetDetailsContentController.getMobileNetworkSummary(ArgumentMatchers.anyInt())
            )
            .thenReturn(MOBILE_NETWORK_SUMMARY)
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(true)
        whenever(internetDetailsContentController.activeAutoSwitchNonDdsSubId)
            .thenReturn(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        mockitoSession =
            ExtendedMockito.mockitoSession()
                .spyStatic(WifiEnterpriseRestrictionUtils::class.java)
                .startMocking()
        whenever(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext)).thenReturn(true)
        createView()
    }

    private fun createView() {
        contentView =
            LayoutInflater.from(mContext).inflate(R.layout.internet_connectivity_dialog, null)
        internetDetailsContentManager =
            InternetDetailsContentManager(
                internetDetailsContentController,
                canConfigMobileData = true,
                canConfigWifi = true,
                coroutineScope = scope,
                context = mContext,
                uiEventLogger = mock<UiEventLogger>(),
                handler = handler,
                backgroundExecutor = bgExecutor,
                keyguard = keyguard,
            )

        internetDetailsContentManager.bind(contentView)
        internetDetailsContentManager.adapter = internetAdapter
        internetDetailsContentManager.connectedWifiEntry = internetWifiEntry
        internetDetailsContentManager.wifiEntriesCount = wifiEntries.size

        ethernet = contentView.requireViewById(R.id.ethernet_layout)
        mobileDataLayout = contentView.requireViewById(R.id.mobile_network_layout)
        mobileToggleSwitch = contentView.requireViewById(R.id.mobile_toggle)
        wifiToggle = contentView.requireViewById(R.id.turn_on_wifi_layout)
        wifiToggleSwitch = contentView.requireViewById(R.id.wifi_toggle)
        wifiToggleSummary = contentView.requireViewById(R.id.wifi_toggle_summary)
        connectedWifi = contentView.requireViewById(R.id.wifi_connected_layout)
        wifiList = contentView.requireViewById(R.id.wifi_list_layout)
        seeAll = contentView.requireViewById(R.id.see_all_layout)
        wifiScanNotify = contentView.requireViewById(R.id.wifi_scan_notify_layout)
        airplaneModeSummaryText = contentView.requireViewById(R.id.airplane_mode_summary)
        sharedWifiButton = contentView.requireViewById(R.id.share_wifi_button)
    }

    @After
    fun tearDown() {
        internetDetailsContentManager.unBind()
        mockitoSession!!.finishMocking()
    }

    @Test
    fun createView_setAccessibilityPaneTitleToQuickSettings() {
        assertThat(contentView.accessibilityPaneTitle)
            .isEqualTo(mContext.getText(R.string.accessibility_desc_quick_settings))
    }

    @Test
    fun hideWifiViews_WifiViewsGone() {
        internetDetailsContentManager.hideWifiViews()

        assertThat(internetDetailsContentManager.isProgressBarVisible).isFalse()
        assertThat(wifiToggle!!.visibility).isEqualTo(View.GONE)
        assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
        assertThat(wifiList!!.visibility).isEqualTo(View.GONE)
        assertThat(seeAll!!.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun updateContent_apmOffAndHasEthernet_showEthernet() {
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)
        whenever(internetDetailsContentController.hasEthernet()).thenReturn(true)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(ethernet!!.visibility).isEqualTo(View.VISIBLE)
        }
    }

    @Test
    fun updateContent_apmOffAndNoEthernet_hideEthernet() {
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)
        whenever(internetDetailsContentController.hasEthernet()).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(ethernet!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOnAndHasEthernet_showEthernet() {
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        whenever(internetDetailsContentController.hasEthernet()).thenReturn(true)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(ethernet!!.visibility).isEqualTo(View.VISIBLE)
        }
    }

    @Test
    fun updateContent_apmOnAndNoEthernet_hideEthernet() {
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        whenever(internetDetailsContentController.hasEthernet()).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(ethernet!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOffAndNotCarrierNetwork_mobileDataLayoutGone() {
        // Mobile network should be gone if the list of active subscriptionId is null.
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(false)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOnWithCarrierNetworkAndWifiStatus_mobileDataLayoutVisible() {
        // Carrier network should be visible if airplane mode ON and Wi-Fi is ON.
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(true)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.VISIBLE)
        }
    }

    @Test
    fun updateContent_apmOnWithCarrierNetworkAndWifiStatus_mobileDataLayoutGone() {
        // Carrier network should be gone if airplane mode ON and Wi-Fi is off.
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOnAndNoCarrierNetwork_mobileDataLayoutGone() {
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(false)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOnAndWifiOnHasCarrierNetwork_showAirplaneSummary() {
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        internetDetailsContentManager.connectedWifiEntry = null
        whenever(internetDetailsContentController.activeNetworkIsCellular()).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileDataLayout!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(airplaneModeSummaryText!!.visibility).isEqualTo(View.VISIBLE)
        }
    }

    @Test
    fun updateContent_apmOffAndWifiOnHasCarrierNetwork_notShowApmSummary() {
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)
        internetDetailsContentManager.connectedWifiEntry = null
        whenever(internetDetailsContentController.activeNetworkIsCellular()).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(airplaneModeSummaryText!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOffAndHasCarrierNetwork_notShowApmSummary() {
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(airplaneModeSummaryText!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_apmOnAndNoCarrierNetwork_notShowApmSummary() {
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(false)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(true)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(airplaneModeSummaryText!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_mobileDataIsEnabled_checkMobileDataSwitch() {
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isMobileDataEnabled).thenReturn(true)
        mobileToggleSwitch!!.isChecked = false
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileToggleSwitch!!.isChecked).isTrue()
        }
    }

    @Test
    fun updateContent_mobileDataIsNotChanged_checkMobileDataSwitch() {
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        whenever(internetDetailsContentController.isCarrierNetworkActive).thenReturn(true)
        whenever(internetDetailsContentController.isMobileDataEnabled).thenReturn(false)
        mobileToggleSwitch!!.isChecked = false
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(mobileToggleSwitch!!.isChecked).isFalse()
        }
    }

    @Test
    fun updateContent_wifiOnAndHasInternetWifi_showConnectedWifi() {
        whenever(internetDetailsContentController.activeAutoSwitchNonDdsSubId).thenReturn(1)
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)

        // The preconditions WiFi ON and Internet WiFi are already in setUp()
        whenever(internetDetailsContentController.activeNetworkIsCellular()).thenReturn(false)

        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.VISIBLE)
            val secondaryLayout =
                contentView.requireViewById<LinearLayout>(R.id.secondary_mobile_network_layout)
            assertThat(secondaryLayout.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_wifiOnAndNoConnectedWifi_hideConnectedWifi() {
        // The precondition WiFi ON is already in setUp()
        internetDetailsContentManager.connectedWifiEntry = null
        whenever(internetDetailsContentController.activeNetworkIsCellular()).thenReturn(false)

        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_wifiOnAndNoWifiEntry_showWifiListAndSeeAllArea() {
        // The precondition WiFi ON is already in setUp()
        internetDetailsContentManager.connectedWifiEntry = null
        internetDetailsContentManager.wifiEntriesCount = 0
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
            // Show a blank block to fix the details content height even if there is no WiFi list
            assertThat(wifiList!!.visibility).isEqualTo(View.VISIBLE)
            verify(internetAdapter).setMaxEntriesCount(3)
            assertThat(seeAll!!.visibility).isEqualTo(View.INVISIBLE)
        }
    }

    @Test
    fun updateContent_wifiOnAndOneWifiEntry_showWifiListAndSeeAllArea() {
        // The precondition WiFi ON is already in setUp()
        internetDetailsContentManager.connectedWifiEntry = null
        internetDetailsContentManager.wifiEntriesCount = 1
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
            // Show a blank block to fix the details content height even if there is no WiFi list
            assertThat(wifiList!!.visibility).isEqualTo(View.VISIBLE)
            verify(internetAdapter).setMaxEntriesCount(3)
            assertThat(seeAll!!.visibility).isEqualTo(View.INVISIBLE)
        }
    }

    @Test
    fun updateContent_wifiOnAndHasConnectedWifi_showAllWifiAndSeeAllArea() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        internetDetailsContentManager.wifiEntriesCount = 0
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.VISIBLE)
            // Show a blank block to fix the details content height even if there is no WiFi list
            assertThat(wifiList!!.visibility).isEqualTo(View.VISIBLE)
            verify(internetAdapter).setMaxEntriesCount(2)
            assertThat(seeAll!!.visibility).isEqualTo(View.INVISIBLE)
        }
    }

    @Test
    fun updateContent_wifiOnAndHasMaxWifiList_showWifiListAndSeeAll() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        internetDetailsContentManager.connectedWifiEntry = null
        internetDetailsContentManager.wifiEntriesCount =
            InternetDetailsContentController.MAX_WIFI_ENTRY_COUNT
        internetDetailsContentManager.hasMoreWifiEntries = true
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
            assertThat(wifiList!!.visibility).isEqualTo(View.VISIBLE)
            verify(internetAdapter).setMaxEntriesCount(3)
            assertThat(seeAll!!.visibility).isEqualTo(View.VISIBLE)
        }
    }

    @Test
    fun updateContent_wifiOnAndHasBothWifiEntry_showBothWifiEntryAndSeeAll() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        internetDetailsContentManager.wifiEntriesCount =
            InternetDetailsContentController.MAX_WIFI_ENTRY_COUNT - 1
        internetDetailsContentManager.hasMoreWifiEntries = true
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(connectedWifi!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(wifiList!!.visibility).isEqualTo(View.VISIBLE)
            verify(internetAdapter).setMaxEntriesCount(2)
            assertThat(seeAll!!.visibility).isEqualTo(View.VISIBLE)
        }
    }

    @Test
    fun updateContent_deviceLockedAndNoConnectedWifi_showWifiToggle() {
        // The preconditions WiFi entries are already in setUp()
        whenever(internetDetailsContentController.isDeviceLocked).thenReturn(true)
        internetDetailsContentManager.connectedWifiEntry = null
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            // Show WiFi Toggle without background
            assertThat(wifiToggle!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(wifiToggle!!.background).isNull()
            // Hide Wi-Fi networks and See all
            assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
            assertThat(wifiList!!.visibility).isEqualTo(View.GONE)
            assertThat(seeAll!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_deviceLockedAndHasConnectedWifi_showWifiToggleWithBackground() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        whenever(internetDetailsContentController.isDeviceLocked).thenReturn(true)
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            // Show WiFi Toggle with highlight background
            assertThat(wifiToggle!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(wifiToggle!!.background).isNotNull()
            // Hide Wi-Fi networks and See all
            assertThat(connectedWifi!!.visibility).isEqualTo(View.GONE)
            assertThat(wifiList!!.visibility).isEqualTo(View.GONE)
            assertThat(seeAll!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_disallowChangeWifiState_disableWifiSwitch() {
        whenever(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext))
            .thenReturn(false)
        createView()
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            // Disable Wi-Fi switch and show restriction message in summary.
            assertThat(wifiToggleSwitch!!.isEnabled).isFalse()
            assertThat(wifiToggleSummary!!.visibility).isEqualTo(View.VISIBLE)
            assertThat(wifiToggleSummary!!.text.length).isNotEqualTo(0)
        }
    }

    @Test
    fun updateContent_allowChangeWifiState_enableWifiSwitch() {
        whenever(WifiEnterpriseRestrictionUtils.isChangeWifiStateAllowed(mContext)).thenReturn(true)
        createView()
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            // Enable Wi-Fi switch and hide restriction message in summary.
            assertThat(wifiToggleSwitch!!.isEnabled).isTrue()
            assertThat(wifiToggleSummary!!.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_showSecondaryDataSub() {
        whenever(internetDetailsContentController.activeAutoSwitchNonDdsSubId).thenReturn(1)
        whenever(internetDetailsContentController.hasActiveSubIdOnDds()).thenReturn(true)
        whenever(internetDetailsContentController.isAirplaneModeEnabled).thenReturn(false)

        clearInvocations(internetDetailsContentController)
        internetDetailsContentManager.updateContent(true)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            val primaryLayout =
                contentView.requireViewById<LinearLayout>(R.id.mobile_network_layout)
            val secondaryLayout =
                contentView.requireViewById<LinearLayout>(R.id.secondary_mobile_network_layout)

            verify(internetDetailsContentController).getMobileNetworkSummary(1)
            assertThat(primaryLayout.background).isNotEqualTo(secondaryLayout.background)
        }
    }

    @Test
    fun updateContent_wifiOn_hideWifiScanNotify() {
        // The preconditions WiFi ON and WiFi entries are already in setUp()
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(wifiScanNotify!!.visibility).isEqualTo(View.GONE)
        }

        assertThat(wifiScanNotify!!.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun updateContent_wifiOffAndWifiScanOff_hideWifiScanNotify() {
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(false)
        whenever(internetDetailsContentController.isWifiScanEnabled).thenReturn(false)
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(wifiScanNotify!!.visibility).isEqualTo(View.GONE)
        }

        assertThat(wifiScanNotify!!.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun updateContent_wifiOffAndWifiScanOnAndDeviceLocked_hideWifiScanNotify() {
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(false)
        whenever(internetDetailsContentController.isWifiScanEnabled).thenReturn(true)
        whenever(internetDetailsContentController.isDeviceLocked).thenReturn(true)
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(wifiScanNotify!!.visibility).isEqualTo(View.GONE)
        }

        assertThat(wifiScanNotify!!.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun updateContent_wifiOffAndWifiScanOnAndDeviceUnlocked_showWifiScanNotify() {
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(false)
        whenever(internetDetailsContentController.isWifiScanEnabled).thenReturn(true)
        whenever(internetDetailsContentController.isDeviceLocked).thenReturn(false)
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(wifiScanNotify!!.visibility).isEqualTo(View.VISIBLE)
            val wifiScanNotifyText =
                contentView.requireViewById<TextView>(R.id.wifi_scan_notify_text)
            assertThat(wifiScanNotifyText.text.length).isNotEqualTo(0)
            assertThat(wifiScanNotifyText.movementMethod).isNotNull()
        }
    }

    @Test
    fun updateContent_wifiIsDisabled_uncheckWifiSwitch() {
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(false)
        wifiToggleSwitch!!.isChecked = true
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(wifiToggleSwitch!!.isChecked).isFalse()
        }
    }

    @Test
    @Throws(Exception::class)
    fun updateContent_wifiIsEnabled_checkWifiSwitch() {
        whenever(internetDetailsContentController.isWifiEnabled).thenReturn(true)
        wifiToggleSwitch!!.isChecked = false
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(wifiToggleSwitch!!.isChecked).isTrue()
        }
    }

    @Test
    fun onClickSeeMoreButton_clickSeeAll_verifyLaunchNetworkSetting() {
        seeAll!!.performClick()

        verify(internetDetailsContentController)
            .launchNetworkSetting(contentView.requireViewById(R.id.see_all_layout))
    }

    @Test
    fun onWifiScan_isScanTrue_setProgressBarVisibleTrue() {
        internetDetailsContentManager.isProgressBarVisible = false

        internetDetailsContentManager.internetDetailsCallback.onWifiScan(true)

        assertThat(internetDetailsContentManager.isProgressBarVisible).isTrue()
    }

    @Test
    fun onWifiScan_isScanFalse_setProgressBarVisibleFalse() {
        internetDetailsContentManager.isProgressBarVisible = true

        internetDetailsContentManager.internetDetailsCallback.onWifiScan(false)

        assertThat(internetDetailsContentManager.isProgressBarVisible).isFalse()
    }

    @Test
    fun updateContent_shareWifiIntentNull_hideButton() {
        whenever(
                internetDetailsContentController.getConfiguratorQrCodeGeneratorIntentOrNull(
                    ArgumentMatchers.any()
                )
            )
            .thenReturn(null)
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(sharedWifiButton?.visibility).isEqualTo(View.GONE)
        }
    }

    @Test
    fun updateContent_shareWifiShareable_showButton() {
        whenever(
                internetDetailsContentController.getConfiguratorQrCodeGeneratorIntentOrNull(
                    ArgumentMatchers.any()
                )
            )
            .thenReturn(Intent())
        internetDetailsContentManager.updateContent(false)
        bgExecutor.runAllReady()

        internetDetailsContentManager.internetContentData.observe(
            internetDetailsContentManager.lifecycleOwner!!
        ) {
            assertThat(sharedWifiButton?.visibility).isEqualTo(View.VISIBLE)
        }
    }

    companion object {
        private const val TITLE = "Internet"
        private const val MOBILE_NETWORK_TITLE = "Mobile Title"
        private const val MOBILE_NETWORK_SUMMARY = "Mobile Summary"
        private const val WIFI_TITLE = "Connected Wi-Fi Title"
        private const val WIFI_SUMMARY = "Connected Wi-Fi Summary"
    }
}
