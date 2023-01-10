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

package com.android.systemui.statusbar.pipeline.wifi.ui.view

import android.content.res.ColorStateList
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.testing.ViewUtils
import android.view.View
import android.widget.ImageView
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.StatusBarIconView.STATE_DOT
import com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN
import com.android.systemui.statusbar.StatusBarIconView.STATE_ICON
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModel
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModelImpl
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiConstants
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class ModernStatusBarWifiViewTest : SysuiTestCase() {

    private lateinit var testableLooper: TestableLooper

    @Mock
    private lateinit var statusBarPipelineFlags: StatusBarPipelineFlags
    @Mock
    private lateinit var logger: ConnectivityPipelineLogger
    @Mock
    private lateinit var tableLogBuffer: TableLogBuffer
    @Mock
    private lateinit var connectivityConstants: ConnectivityConstants
    @Mock
    private lateinit var wifiConstants: WifiConstants
    private lateinit var airplaneModeRepository: FakeAirplaneModeRepository
    private lateinit var connectivityRepository: FakeConnectivityRepository
    private lateinit var wifiRepository: FakeWifiRepository
    private lateinit var interactor: WifiInteractor
    private lateinit var viewModel: LocationBasedWifiViewModel
    private lateinit var scope: CoroutineScope
    private lateinit var airplaneModeViewModel: AirplaneModeViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        airplaneModeRepository = FakeAirplaneModeRepository()
        connectivityRepository = FakeConnectivityRepository()
        wifiRepository = FakeWifiRepository()
        wifiRepository.setIsWifiEnabled(true)
        interactor = WifiInteractorImpl(connectivityRepository, wifiRepository)
        scope = CoroutineScope(Dispatchers.Unconfined)
        airplaneModeViewModel = AirplaneModeViewModelImpl(
            AirplaneModeInteractor(
                airplaneModeRepository,
                connectivityRepository,
            ),
            logger,
            scope,
        )
        viewModel = WifiViewModel(
            airplaneModeViewModel,
            connectivityConstants,
            context,
            logger,
            tableLogBuffer,
            interactor,
            scope,
            statusBarPipelineFlags,
            wifiConstants,
        ).home
    }

    // Note: The following tests are more like integration tests, since they stand up a full
    // [WifiViewModel] and test the interactions between the view, view-binder, and view-model.

    @Test
    fun setVisibleState_icon_iconShownDotHidden() {
        val view = ModernStatusBarWifiView.constructAndBind(context, SLOT_NAME, viewModel)

        view.setVisibleState(STATE_ICON, /* animate= */ false)

        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.getIconGroupView().visibility).isEqualTo(View.VISIBLE)
        assertThat(view.getDotView().visibility).isEqualTo(View.GONE)

        ViewUtils.detachView(view)
    }

    @Test
    fun setVisibleState_dot_iconHiddenDotShown() {
        val view = ModernStatusBarWifiView.constructAndBind(context, SLOT_NAME, viewModel)

        view.setVisibleState(STATE_DOT, /* animate= */ false)

        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.getIconGroupView().visibility).isEqualTo(View.GONE)
        assertThat(view.getDotView().visibility).isEqualTo(View.VISIBLE)

        ViewUtils.detachView(view)
    }

    @Test
    fun setVisibleState_hidden_iconAndDotHidden() {
        val view = ModernStatusBarWifiView.constructAndBind(context, SLOT_NAME, viewModel)

        view.setVisibleState(STATE_HIDDEN, /* animate= */ false)

        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.getIconGroupView().visibility).isEqualTo(View.GONE)
        assertThat(view.getDotView().visibility).isEqualTo(View.GONE)

        ViewUtils.detachView(view)
    }

    @Test
    fun isIconVisible_notEnabled_outputsFalse() {
        wifiRepository.setIsWifiEnabled(false)
        wifiRepository.setWifiNetwork(
            WifiNetworkModel.Active(NETWORK_ID, isValidated = true, level = 2)
        )

        val view = ModernStatusBarWifiView.constructAndBind(context, SLOT_NAME, viewModel)

        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.isIconVisible).isFalse()

        ViewUtils.detachView(view)
    }

    @Test
    fun isIconVisible_enabled_outputsTrue() {
        wifiRepository.setIsWifiEnabled(true)
        wifiRepository.setWifiNetwork(
            WifiNetworkModel.Active(NETWORK_ID, isValidated = true, level = 2)
        )

        val view = ModernStatusBarWifiView.constructAndBind(context, SLOT_NAME, viewModel)

        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        assertThat(view.isIconVisible).isTrue()

        ViewUtils.detachView(view)
    }

    @Test
    fun onDarkChanged_iconHasNewColor() {
        whenever(statusBarPipelineFlags.useDebugColoring()).thenReturn(false)
        val view = ModernStatusBarWifiView.constructAndBind(context, SLOT_NAME, viewModel)
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        val color = 0x12345678
        view.onDarkChanged(arrayListOf(), 1.0f, color)
        testableLooper.processAllMessages()

        assertThat(view.getIconView().imageTintList).isEqualTo(ColorStateList.valueOf(color))

        ViewUtils.detachView(view)
    }

    @Test
    fun setStaticDrawableColor_iconHasNewColor() {
        whenever(statusBarPipelineFlags.useDebugColoring()).thenReturn(false)
        val view = ModernStatusBarWifiView.constructAndBind(context, SLOT_NAME, viewModel)
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()

        val color = 0x23456789
        view.setStaticDrawableColor(color)
        testableLooper.processAllMessages()

        assertThat(view.getIconView().imageTintList).isEqualTo(ColorStateList.valueOf(color))

        ViewUtils.detachView(view)
    }

    private fun View.getIconGroupView(): View {
        return this.requireViewById(R.id.wifi_group)
    }

    private fun View.getIconView(): ImageView {
        return this.requireViewById(R.id.wifi_signal)
    }

    private fun View.getDotView(): View {
        return this.requireViewById(R.id.status_bar_dot)
    }
}

private const val SLOT_NAME = "TestSlotName"
private const val NETWORK_ID = 200
