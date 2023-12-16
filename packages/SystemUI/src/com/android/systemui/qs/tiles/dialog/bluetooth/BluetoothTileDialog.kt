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
import android.view.View.AccessibilityDelegate
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Dialog for showing active, connected and saved bluetooth devices. */
@SysUISingleton
internal class BluetoothTileDialog
constructor(
    private val bluetoothToggleInitialValue: Boolean,
    private val subtitleResIdInitialValue: Int,
    private val cachedContentHeight: Int,
    private val bluetoothTileDialogCallback: BluetoothTileDialogCallback,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val systemClock: SystemClock,
    private val uiEventLogger: UiEventLogger,
    private val logger: BluetoothTileDialogLogger,
    context: Context,
) : SystemUIDialog(context, DEFAULT_THEME, DEFAULT_DISMISS_ON_DEVICE_LOCK) {

    private val mutableBluetoothStateToggle: MutableStateFlow<Boolean> =
        MutableStateFlow(bluetoothToggleInitialValue)
    internal val bluetoothStateToggle
        get() = mutableBluetoothStateToggle.asStateFlow()

    private val mutableDeviceItemClick: MutableSharedFlow<DeviceItem> =
        MutableSharedFlow(extraBufferCapacity = 1)
    internal val deviceItemClick
        get() = mutableDeviceItemClick.asSharedFlow()

    private val mutableContentHeight: MutableSharedFlow<Int> =
        MutableSharedFlow(extraBufferCapacity = 1)
    internal val contentHeight
        get() = mutableContentHeight.asSharedFlow()

    private val deviceItemAdapter: Adapter = Adapter(bluetoothTileDialogCallback)

    private var lastUiUpdateMs: Long = -1

    private var lastItemRow: Int = -1

    private lateinit var toggleView: Switch
    private lateinit var subtitleTextView: TextView
    private lateinit var doneButton: View
    private lateinit var seeAllButton: View
    private lateinit var pairNewDeviceButton: View
    private lateinit var deviceListView: RecyclerView
    private lateinit var scrollViewContent: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiEventLogger.log(BluetoothTileDialogUiEvent.BLUETOOTH_TILE_DIALOG_SHOWN)

        LayoutInflater.from(context).inflate(R.layout.bluetooth_tile_dialog, null).apply {
            accessibilityPaneTitle = context.getText(R.string.accessibility_desc_quick_settings)
            setContentView(this)
        }

        toggleView = requireViewById(R.id.bluetooth_toggle)
        subtitleTextView = requireViewById(R.id.bluetooth_tile_dialog_subtitle) as TextView
        doneButton = requireViewById(R.id.done_button)
        seeAllButton = requireViewById(R.id.see_all_button)
        pairNewDeviceButton = requireViewById(R.id.pair_new_device_button)
        deviceListView = requireViewById<RecyclerView>(R.id.device_list)

        setupToggle()
        setupRecyclerView()

        subtitleTextView.text = context.getString(subtitleResIdInitialValue)
        doneButton.setOnClickListener { dismiss() }
        seeAllButton.setOnClickListener { bluetoothTileDialogCallback.onSeeAllClicked(it) }
        pairNewDeviceButton.setOnClickListener {
            bluetoothTileDialogCallback.onPairNewDeviceClicked(it)
        }
        requireViewById<View>(R.id.scroll_view).apply {
            scrollViewContent = this
            layoutParams.height = cachedContentHeight
        }
    }

    override fun start() {
        lastUiUpdateMs = systemClock.elapsedRealtime()
    }

    override fun dismiss() {
        if (::scrollViewContent.isInitialized) {
            mutableContentHeight.tryEmit(scrollViewContent.measuredHeight)
        }
        super.dismiss()
    }

    internal suspend fun onDeviceItemUpdated(
        deviceItem: List<DeviceItem>,
        showSeeAll: Boolean,
        showPairNewDevice: Boolean
    ) {
        withContext(mainDispatcher) {
            val start = systemClock.elapsedRealtime()
            val itemRow = deviceItem.size + showSeeAll.toInt() + showPairNewDevice.toInt()
            // If not the first load, add a slight delay for smoother dialog height change
            if (itemRow != lastItemRow && lastItemRow != -1) {
                delay(MIN_HEIGHT_CHANGE_INTERVAL_MS - (start - lastUiUpdateMs))
            }
            if (isActive) {
                deviceItemAdapter.refreshDeviceItemList(deviceItem) {
                    seeAllButton.visibility = if (showSeeAll) VISIBLE else GONE
                    pairNewDeviceButton.visibility = if (showPairNewDevice) VISIBLE else GONE
                    // Update the height after data is updated
                    scrollViewContent.layoutParams.height = WRAP_CONTENT
                    lastUiUpdateMs = systemClock.elapsedRealtime()
                    lastItemRow = itemRow
                    logger.logDeviceUiUpdate(lastUiUpdateMs - start)
                }
            }
        }
    }

    internal fun onBluetoothStateUpdated(isEnabled: Boolean, subtitleResId: Int) {
        toggleView.apply {
            isChecked = isEnabled
            setEnabled(true)
            alpha = ENABLED_ALPHA
        }
        subtitleTextView.text = context.getString(subtitleResId)
    }

    private fun setupToggle() {
        toggleView.isChecked = bluetoothToggleInitialValue
        toggleView.setOnCheckedChangeListener { view, isChecked ->
            mutableBluetoothStateToggle.value = isChecked
            view.apply {
                isEnabled = false
                alpha = DISABLED_ALPHA
            }
            logger.logBluetoothState(BluetoothStateStage.USER_TOGGLED, isChecked.toString())
            uiEventLogger.log(BluetoothTileDialogUiEvent.BLUETOOTH_TOGGLE_CLICKED)
        }
    }

    private fun setupRecyclerView() {
        deviceListView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = deviceItemAdapter
        }
    }

    internal inner class Adapter(private val onClickCallback: BluetoothTileDialogCallback) :
        RecyclerView.Adapter<Adapter.DeviceItemViewHolder>() {

        private val diffUtilCallback =
            object : DiffUtil.ItemCallback<DeviceItem>() {
                override fun areItemsTheSame(
                    deviceItem1: DeviceItem,
                    deviceItem2: DeviceItem
                ): Boolean {
                    return deviceItem1.cachedBluetoothDevice == deviceItem2.cachedBluetoothDevice
                }

                override fun areContentsTheSame(
                    deviceItem1: DeviceItem,
                    deviceItem2: DeviceItem
                ): Boolean {
                    return deviceItem1.type == deviceItem2.type &&
                        deviceItem1.cachedBluetoothDevice == deviceItem2.cachedBluetoothDevice &&
                        deviceItem1.deviceName == deviceItem2.deviceName &&
                        deviceItem1.connectionSummary == deviceItem2.connectionSummary &&
                        // Ignored the icon drawable
                        deviceItem1.iconWithDescription?.second ==
                            deviceItem2.iconWithDescription?.second &&
                        deviceItem1.background == deviceItem2.background &&
                        deviceItem1.isEnabled == deviceItem2.isEnabled &&
                        deviceItem1.actionAccessibilityLabel == deviceItem2.actionAccessibilityLabel
                }
            }

        private val asyncListDiffer = AsyncListDiffer(this, diffUtilCallback)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceItemViewHolder {
            val view =
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.bluetooth_device_item, parent, false)
            return DeviceItemViewHolder(view)
        }

        override fun getItemCount() = asyncListDiffer.currentList.size

        override fun onBindViewHolder(holder: DeviceItemViewHolder, position: Int) {
            val item = getItem(position)
            holder.bind(item, onClickCallback)
        }

        internal fun getItem(position: Int) = asyncListDiffer.currentList[position]

        internal fun refreshDeviceItemList(updated: List<DeviceItem>, callback: () -> Unit) {
            asyncListDiffer.submitList(updated, callback)
        }

        internal inner class DeviceItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val container = view.requireViewById<View>(R.id.bluetooth_device_row)
            private val nameView = view.requireViewById<TextView>(R.id.bluetooth_device_name)
            private val summaryView = view.requireViewById<TextView>(R.id.bluetooth_device_summary)
            private val iconView = view.requireViewById<ImageView>(R.id.bluetooth_device_icon)
            private val gearView = view.requireViewById<View>(R.id.gear_icon)

            internal fun bind(
                item: DeviceItem,
                deviceItemOnClickCallback: BluetoothTileDialogCallback
            ) {
                container.apply {
                    isEnabled = item.isEnabled
                    background = item.background?.let { context.getDrawable(it) }
                    setOnClickListener {
                        mutableDeviceItemClick.tryEmit(item)
                        uiEventLogger.log(BluetoothTileDialogUiEvent.DEVICE_CLICKED)
                    }
                    accessibilityDelegate =
                        object : AccessibilityDelegate() {
                            override fun onInitializeAccessibilityNodeInfo(
                                host: View,
                                info: AccessibilityNodeInfo
                            ) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                info.addAction(
                                    AccessibilityAction(
                                        AccessibilityAction.ACTION_CLICK.id,
                                        item.actionAccessibilityLabel
                                    )
                                )
                            }
                        }
                }
                nameView.text = item.deviceName
                summaryView.text = item.connectionSummary
                iconView.apply {
                    item.iconWithDescription?.let {
                        setImageDrawable(it.first)
                        contentDescription = it.second
                    }
                }
                gearView.setOnClickListener {
                    deviceItemOnClickCallback.onDeviceItemGearClicked(item, it)
                }
            }
        }
    }

    internal companion object {
        const val MIN_HEIGHT_CHANGE_INTERVAL_MS = 800L
        const val MAX_DEVICE_ITEM_ENTRY = 3
        const val ACTION_BLUETOOTH_DEVICE_DETAILS =
            "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS"
        const val ACTION_PREVIOUSLY_CONNECTED_DEVICE =
            "com.android.settings.PREVIOUSLY_CONNECTED_DEVICE"
        const val ACTION_PAIR_NEW_DEVICE = "android.settings.BLUETOOTH_PAIRING_SETTINGS"
        const val DISABLED_ALPHA = 0.3f
        const val ENABLED_ALPHA = 1f

        private fun Boolean.toInt(): Int {
            return if (this) 1 else 0
        }
    }
}
