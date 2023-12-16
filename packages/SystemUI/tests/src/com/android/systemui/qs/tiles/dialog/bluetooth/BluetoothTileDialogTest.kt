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

package com.android.systemui.qs.tiles.dialog.bluetooth

import android.graphics.drawable.Drawable
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class BluetoothTileDialogTest : SysuiTestCase() {
    companion object {
        const val DEVICE_NAME = "device"
        const val DEVICE_CONNECTION_SUMMARY = "active"
        const val ENABLED = true
        const val CONTENT_HEIGHT = WRAP_CONTENT
    }

    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var cachedBluetoothDevice: CachedBluetoothDevice

    @Mock private lateinit var bluetoothTileDialogCallback: BluetoothTileDialogCallback

    @Mock private lateinit var drawable: Drawable

    @Mock private lateinit var uiEventLogger: UiEventLogger

    @Mock private lateinit var logger: BluetoothTileDialogLogger

    private val subtitleResId = R.string.quick_settings_bluetooth_tile_subtitle

    private val fakeSystemClock = FakeSystemClock()

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var testScope: TestScope
    private lateinit var icon: Pair<Drawable, String>
    private lateinit var bluetoothTileDialog: BluetoothTileDialog
    private lateinit var deviceItem: DeviceItem

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = UnconfinedTestDispatcher(scheduler)
        testScope = TestScope(dispatcher)
        bluetoothTileDialog =
            BluetoothTileDialog(
                ENABLED,
                subtitleResId,
                CONTENT_HEIGHT,
                bluetoothTileDialogCallback,
                dispatcher,
                fakeSystemClock,
                uiEventLogger,
                logger,
                mContext
            )
        icon = Pair(drawable, DEVICE_NAME)
        deviceItem =
            DeviceItem(
                type = DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = icon,
                background = null
            )
        `when`(cachedBluetoothDevice.isBusy).thenReturn(false)
    }

    @Test
    fun testShowDialog_createRecyclerViewWithAdapter() {
        bluetoothTileDialog.show()

        val recyclerView = bluetoothTileDialog.requireViewById<RecyclerView>(R.id.device_list)

        assertThat(bluetoothTileDialog.isShowing).isTrue()
        assertThat(recyclerView).isNotNull()
        assertThat(recyclerView.visibility).isEqualTo(VISIBLE)
        assertThat(recyclerView.adapter).isNotNull()
        assertThat(recyclerView.layoutManager is LinearLayoutManager).isTrue()
    }

    @Test
    fun testShowDialog_displayBluetoothDevice() {
        testScope.runTest {
            bluetoothTileDialog =
                BluetoothTileDialog(
                    ENABLED,
                    subtitleResId,
                    CONTENT_HEIGHT,
                    bluetoothTileDialogCallback,
                    dispatcher,
                    fakeSystemClock,
                    uiEventLogger,
                    logger,
                    mContext
                )
            bluetoothTileDialog.show()
            fakeSystemClock.setElapsedRealtime(Long.MAX_VALUE)
            bluetoothTileDialog.onDeviceItemUpdated(
                listOf(deviceItem),
                showSeeAll = false,
                showPairNewDevice = false
            )

            val recyclerView = bluetoothTileDialog.requireViewById<RecyclerView>(R.id.device_list)
            val adapter = recyclerView.adapter as BluetoothTileDialog.Adapter
            assertThat(adapter.itemCount).isEqualTo(1)
            assertThat(adapter.getItem(0).deviceName).isEqualTo(DEVICE_NAME)
            assertThat(adapter.getItem(0).connectionSummary).isEqualTo(DEVICE_CONNECTION_SUMMARY)
            assertThat(adapter.getItem(0).iconWithDescription).isEqualTo(icon)
        }
    }

    @Test
    fun testDeviceItemViewHolder_cachedDeviceNotBusy() {
        deviceItem.isEnabled = true

        val view =
            LayoutInflater.from(mContext).inflate(R.layout.bluetooth_device_item, null, false)
        val viewHolder =
            BluetoothTileDialog(
                    ENABLED,
                    subtitleResId,
                    CONTENT_HEIGHT,
                    bluetoothTileDialogCallback,
                    dispatcher,
                    fakeSystemClock,
                    uiEventLogger,
                    logger,
                    mContext
                )
                .Adapter(bluetoothTileDialogCallback)
                .DeviceItemViewHolder(view)
        viewHolder.bind(deviceItem, bluetoothTileDialogCallback)
        val container = view.requireViewById<View>(R.id.bluetooth_device_row)

        assertThat(container).isNotNull()
        assertThat(container.isEnabled).isTrue()
        assertThat(container.hasOnClickListeners()).isTrue()
    }

    @Test
    fun testDeviceItemViewHolder_cachedDeviceBusy() {
        deviceItem.isEnabled = false

        val view =
            LayoutInflater.from(mContext).inflate(R.layout.bluetooth_device_item, null, false)
        val viewHolder =
            BluetoothTileDialog(
                    ENABLED,
                    subtitleResId,
                    CONTENT_HEIGHT,
                    bluetoothTileDialogCallback,
                    dispatcher,
                    fakeSystemClock,
                    uiEventLogger,
                    logger,
                    mContext
                )
                .Adapter(bluetoothTileDialogCallback)
                .DeviceItemViewHolder(view)
        viewHolder.bind(deviceItem, bluetoothTileDialogCallback)
        val container = view.requireViewById<View>(R.id.bluetooth_device_row)

        assertThat(container).isNotNull()
        assertThat(container.isEnabled).isFalse()
        assertThat(container.hasOnClickListeners()).isTrue()
    }

    @Test
    fun testOnDeviceUpdated_hideSeeAll_showPairNew() {
        testScope.runTest {
            bluetoothTileDialog =
                BluetoothTileDialog(
                    ENABLED,
                    subtitleResId,
                    CONTENT_HEIGHT,
                    bluetoothTileDialogCallback,
                    dispatcher,
                    fakeSystemClock,
                    uiEventLogger,
                    logger,
                    mContext
                )
            bluetoothTileDialog.show()
            fakeSystemClock.setElapsedRealtime(Long.MAX_VALUE)
            bluetoothTileDialog.onDeviceItemUpdated(
                listOf(deviceItem),
                showSeeAll = false,
                showPairNewDevice = true
            )

            val seeAllButton = bluetoothTileDialog.requireViewById<View>(R.id.see_all_button)
            val pairNewButton =
                bluetoothTileDialog.requireViewById<View>(R.id.pair_new_device_button)
            val recyclerView = bluetoothTileDialog.requireViewById<RecyclerView>(R.id.device_list)
            val adapter = recyclerView.adapter as BluetoothTileDialog.Adapter
            val scrollViewContent = bluetoothTileDialog.requireViewById<View>(R.id.scroll_view)

            assertThat(seeAllButton).isNotNull()
            assertThat(seeAllButton.visibility).isEqualTo(GONE)
            assertThat(pairNewButton).isNotNull()
            assertThat(pairNewButton.visibility).isEqualTo(VISIBLE)
            assertThat(adapter.itemCount).isEqualTo(1)
            assertThat(scrollViewContent.layoutParams.height).isEqualTo(WRAP_CONTENT)
        }
    }

    @Test
    fun testShowDialog_displayFromCachedHeight() {
        testScope.runTest {
            bluetoothTileDialog =
                BluetoothTileDialog(
                    ENABLED,
                    subtitleResId,
                    MATCH_PARENT,
                    bluetoothTileDialogCallback,
                    dispatcher,
                    fakeSystemClock,
                    uiEventLogger,
                    logger,
                    mContext
                )
            bluetoothTileDialog.show()
            assertThat(
                    bluetoothTileDialog.requireViewById<View>(R.id.scroll_view).layoutParams.height
                )
                .isEqualTo(MATCH_PARENT)
        }
    }
}
