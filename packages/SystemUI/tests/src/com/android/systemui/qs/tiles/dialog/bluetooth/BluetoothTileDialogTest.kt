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
import android.view.View.VISIBLE
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.filters.SmallTest
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class BluetoothTileDialogTest : SysuiTestCase() {
    companion object {
        const val DEVICE_NAME = "device"
        const val DEVICE_CONNECTION_SUMMARY = "active"
    }

    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var deviceItemOnClickCallback: DeviceItemOnClickCallback

    @Mock private lateinit var callback: DeviceItemOnClickCallback

    @Mock private lateinit var cachedBluetoothDevice: CachedBluetoothDevice

    @Mock private lateinit var drawable: Drawable

    private lateinit var icon: Pair<Drawable, String>
    private lateinit var bluetoothTileDialog: BluetoothTileDialog

    @Before
    fun setUp() {
        bluetoothTileDialog = BluetoothTileDialog(emptyList(), deviceItemOnClickCallback, mContext)
        icon = Pair(drawable, DEVICE_NAME)
    }

    @Test
    fun testShowDialog_createRecyclerViewWithAdapter() {
        bluetoothTileDialog.show()

        val recyclerView = bluetoothTileDialog.findViewById<RecyclerView>(R.id.device_list)

        assertThat(bluetoothTileDialog.isShowing).isTrue()
        assertThat(recyclerView).isNotNull()
        assertThat(recyclerView?.visibility).isEqualTo(VISIBLE)
        assertThat(recyclerView?.adapter).isNotNull()
        assertThat(recyclerView?.layoutManager is LinearLayoutManager).isTrue()
    }

    @Test
    fun testShowDialog_displayBluetoothDevice() {
        bluetoothTileDialog =
            BluetoothTileDialog(
                listOf(
                    DeviceItem(
                        type = DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
                        cachedBluetoothDevice = cachedBluetoothDevice,
                        deviceName = DEVICE_NAME,
                        connectionSummary = DEVICE_CONNECTION_SUMMARY,
                        iconWithDescription = icon,
                        background = null
                    )
                ),
                callback,
                mContext
            )

        bluetoothTileDialog.show()

        val recyclerView = bluetoothTileDialog.findViewById<RecyclerView>(R.id.device_list)
        val adapter = recyclerView?.adapter as BluetoothTileDialog.Adapter
        assertThat(adapter.itemCount).isEqualTo(1)
        assertThat(adapter.getItem(0).deviceName).isEqualTo(DEVICE_NAME)
        assertThat(adapter.getItem(0).connectionSummary).isEqualTo(DEVICE_CONNECTION_SUMMARY)
        assertThat(adapter.getItem(0).iconWithDescription).isEqualTo(icon)
    }
}
