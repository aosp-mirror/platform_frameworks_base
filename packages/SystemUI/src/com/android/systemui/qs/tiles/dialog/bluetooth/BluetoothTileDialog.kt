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

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Dialog for showing active, connected and saved bluetooth devices. */
@SysUISingleton
internal class BluetoothTileDialog
constructor(
    private val bluetoothToggleInitialValue: Boolean,
    private val bluetoothTileDialogCallback: BluetoothTileDialogCallback,
    context: Context,
) : SystemUIDialog(context, DEFAULT_THEME, DEFAULT_DISMISS_ON_DEVICE_LOCK) {

    private val mutableBluetoothStateSwitchedFlow: MutableStateFlow<Boolean?> =
        MutableStateFlow(null)
    internal val bluetoothStateSwitchedFlow
        get() = mutableBluetoothStateSwitchedFlow.asStateFlow()

    private val mutableClickedFlow: MutableSharedFlow<Pair<DeviceItem, Int>> =
        MutableSharedFlow(extraBufferCapacity = 1)
    internal val deviceItemClickedFlow
        get() = mutableClickedFlow.asSharedFlow()

    private val deviceItemAdapter: Adapter = Adapter()

    private lateinit var toggleView: Switch
    private lateinit var doneButton: View
    private lateinit var seeAllView: View
    private lateinit var deviceListView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(LayoutInflater.from(context).inflate(R.layout.bluetooth_tile_dialog, null))

        toggleView = requireViewById(R.id.bluetooth_toggle)
        doneButton = requireViewById(R.id.done_button)
        seeAllView = requireViewById(R.id.see_all_layout)
        deviceListView = requireViewById<RecyclerView>(R.id.device_list)

        setupToggle()
        setupRecyclerView()

        doneButton.setOnClickListener { dismiss() }
    }

    internal fun onDeviceItemUpdated(deviceItem: List<DeviceItem>, showSeeAll: Boolean) {
        seeAllView.visibility = if (showSeeAll) VISIBLE else GONE
        deviceItemAdapter.refreshDeviceItemList(deviceItem)
    }

    internal fun onDeviceItemUpdatedAtPosition(deviceItem: DeviceItem, position: Int) {
        deviceItemAdapter.refreshDeviceItem(deviceItem, position)
    }

    internal fun onBluetoothStateUpdated(isEnabled: Boolean) {
        toggleView.isChecked = isEnabled
    }

    private fun setupToggle() {
        toggleView.isChecked = bluetoothToggleInitialValue
        toggleView.setOnCheckedChangeListener { _, isChecked ->
            mutableBluetoothStateSwitchedFlow.value = isChecked
        }
    }

    private fun setupRecyclerView() {
        deviceListView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = deviceItemAdapter
        }
    }

    internal inner class Adapter : RecyclerView.Adapter<Adapter.DeviceItemViewHolder>() {

        init {
            setHasStableIds(true)
        }

        private val deviceItem: MutableList<DeviceItem> = mutableListOf()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceItemViewHolder {
            val view =
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.bluetooth_device_item, parent, false)
            return DeviceItemViewHolder(view)
        }

        override fun getItemCount() = deviceItem.size

        override fun getItemId(position: Int) = position.toLong()

        override fun onBindViewHolder(holder: DeviceItemViewHolder, position: Int) {
            val item = getItem(position)
            holder.bind(item, position)
        }

        internal fun getItem(position: Int) = deviceItem[position]

        internal fun refreshDeviceItemList(updated: List<DeviceItem>) {
            deviceItem.clear()
            deviceItem.addAll(updated)
            notifyDataSetChanged()
        }

        internal fun refreshDeviceItem(updated: DeviceItem, position: Int) {
            deviceItem[position] = updated
            notifyItemChanged(position)
        }

        internal inner class DeviceItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val container = view.requireViewById<View>(R.id.bluetooth_device)
            private val nameView = view.requireViewById<TextView>(R.id.bluetooth_device_name)
            private val summaryView = view.requireViewById<TextView>(R.id.bluetooth_device_summary)
            private val iconView = view.requireViewById<ImageView>(R.id.bluetooth_device_icon)

            internal fun bind(item: DeviceItem, position: Int) {
                container.apply {
                    isEnabled = item.isEnabled
                    alpha = item.alpha
                    background = item.background
                    setOnClickListener { mutableClickedFlow.tryEmit(Pair(item, position)) }
                }
                nameView.text = item.deviceName
                summaryView.text = item.connectionSummary
                iconView.apply {
                    item.iconWithDescription?.let {
                        setImageDrawable(it.first)
                        contentDescription = it.second
                    }
                }
            }
        }
    }

    internal companion object {
        const val ENABLED_ALPHA = 1.0f
        const val DISABLED_ALPHA = 0.3f
        const val MAX_DEVICE_ITEM_ENTRY = 3
    }
}
