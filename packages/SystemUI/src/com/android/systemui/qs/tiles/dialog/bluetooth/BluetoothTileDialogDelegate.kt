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
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.time.SystemClock
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Dialog for showing active, connected and saved bluetooth devices. */
class BluetoothTileDialogDelegate
@AssistedInject
internal constructor(
    @Assisted private val context: Context,
    @Assisted private val initialUiProperties: BluetoothTileDialogViewModel.UiProperties,
    @Assisted private val cachedContentHeight: Int,
    @Assisted private val bluetoothToggleInitialValue: Boolean,
    @Assisted private val bluetoothTileDialogCallback: BluetoothTileDialogCallback,
    @Assisted private val dismissListener: Runnable,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val systemClock: SystemClock,
    private val uiEventLogger: UiEventLogger,
    private val logger: BluetoothTileDialogLogger,
    private val systemuiDialogFactory: SystemUIDialog.Factory,
    mainLayoutInflater: LayoutInflater,
) : SystemUIDialog.Delegate {

    private val layoutInflater = mainLayoutInflater.cloneInContext(context)

    private val mutableBluetoothStateToggle: MutableStateFlow<Boolean> =
        MutableStateFlow(bluetoothToggleInitialValue)
    internal val bluetoothStateToggle
        get() = mutableBluetoothStateToggle.asStateFlow()

    private val mutableBluetoothAutoOnToggle: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    internal val bluetoothAutoOnToggle
        get() = mutableBluetoothAutoOnToggle.asStateFlow()

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

    @AssistedFactory
    internal interface Factory {
        fun create(
            context: Context,
            initialUiProperties: BluetoothTileDialogViewModel.UiProperties,
            cachedContentHeight: Int,
            bluetoothEnabled: Boolean,
            dialogCallback: BluetoothTileDialogCallback,
            dimissListener: Runnable
        ): BluetoothTileDialogDelegate
    }

    override fun createDialog(): SystemUIDialog {
        val dialog = systemuiDialogFactory.create(this, context)

        return dialog
    }

    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        SystemUIDialog.registerDismissListener(dialog, dismissListener)
        uiEventLogger.log(BluetoothTileDialogUiEvent.BLUETOOTH_TILE_DIALOG_SHOWN)

        layoutInflater.inflate(R.layout.bluetooth_tile_dialog, null).apply {
            accessibilityPaneTitle = context.getText(R.string.accessibility_desc_quick_settings)
            dialog.setContentView(this)
        }

        setupToggle(dialog)
        setupRecyclerView(dialog)

        getSubtitleTextView(dialog).text = context.getString(initialUiProperties.subTitleResId)
        dialog.requireViewById<View>(R.id.done_button).setOnClickListener { dialog.dismiss() }
        getSeeAllButton(dialog).setOnClickListener {
            bluetoothTileDialogCallback.onSeeAllClicked(it)
        }
        getPairNewDeviceButton(dialog).setOnClickListener {
            bluetoothTileDialogCallback.onPairNewDeviceClicked(it)
        }
        getScrollViewContent(dialog).apply {
            minimumHeight =
                resources.getDimensionPixelSize(initialUiProperties.scrollViewMinHeightResId)
            layoutParams.height = maxOf(cachedContentHeight, minimumHeight)
        }
    }

    override fun onStart(dialog: SystemUIDialog) {
        lastUiUpdateMs = systemClock.elapsedRealtime()
    }

    override fun onStop(dialog: SystemUIDialog) {
        mutableContentHeight.tryEmit(getScrollViewContent(dialog).measuredHeight)
    }

    internal suspend fun animateProgressBar(dialog: SystemUIDialog, animate: Boolean) {
        withContext(mainDispatcher) {
            if (animate) {
                showProgressBar(dialog)
            } else {
                delay(PROGRESS_BAR_ANIMATION_DURATION_MS)
                hideProgressBar(dialog)
            }
        }
    }

    internal suspend fun onDeviceItemUpdated(
        dialog: SystemUIDialog,
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
                    getSeeAllButton(dialog).visibility = if (showSeeAll) VISIBLE else GONE
                    getPairNewDeviceButton(dialog).visibility =
                        if (showPairNewDevice) VISIBLE else GONE
                    // Update the height after data is updated
                    getScrollViewContent(dialog).layoutParams.height = WRAP_CONTENT
                    lastUiUpdateMs = systemClock.elapsedRealtime()
                    lastItemRow = itemRow
                    logger.logDeviceUiUpdate(lastUiUpdateMs - start)
                }
            }
        }
    }

    internal fun onBluetoothStateUpdated(
        dialog: SystemUIDialog,
        isEnabled: Boolean,
        uiProperties: BluetoothTileDialogViewModel.UiProperties
    ) {
        getToggleView(dialog).apply {
            isChecked = isEnabled
            setEnabled(true)
            alpha = ENABLED_ALPHA
        }
        getSubtitleTextView(dialog).text = context.getString(uiProperties.subTitleResId)
        getAutoOnToggleView(dialog).visibility = uiProperties.autoOnToggleVisibility
    }

    internal fun onBluetoothAutoOnUpdated(dialog: SystemUIDialog, isEnabled: Boolean) {
        getAutoOnToggle(dialog).apply {
            isChecked = isEnabled
            setEnabled(true)
            alpha = ENABLED_ALPHA
        }
    }

    private fun setupToggle(dialog: SystemUIDialog) {
        val toggleView = getToggleView(dialog)
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

        getAutoOnToggleView(dialog).visibility = initialUiProperties.autoOnToggleVisibility
        getAutoOnToggle(dialog).setOnCheckedChangeListener { view, isChecked ->
            mutableBluetoothAutoOnToggle.value = isChecked
            view.apply {
                isEnabled = false
                alpha = DISABLED_ALPHA
            }
            uiEventLogger.log(BluetoothTileDialogUiEvent.BLUETOOTH_AUTO_ON_TOGGLE_CLICKED)
        }
    }

    private fun getToggleView(dialog: SystemUIDialog): Switch {
        return dialog.requireViewById(R.id.bluetooth_toggle)
    }

    private fun getSubtitleTextView(dialog: SystemUIDialog): TextView {
        return dialog.requireViewById(R.id.bluetooth_tile_dialog_subtitle)
    }

    private fun getSeeAllButton(dialog: SystemUIDialog): View {
        return dialog.requireViewById(R.id.see_all_button)
    }

    private fun getPairNewDeviceButton(dialog: SystemUIDialog): View {
        return dialog.requireViewById(R.id.pair_new_device_button)
    }

    private fun getDeviceListView(dialog: SystemUIDialog): RecyclerView {
        return dialog.requireViewById(R.id.device_list)
    }

    private fun getAutoOnToggle(dialog: SystemUIDialog): Switch {
        return dialog.requireViewById(R.id.bluetooth_auto_on_toggle)
    }

    private fun getAutoOnToggleView(dialog: SystemUIDialog): View {
        return dialog.requireViewById(R.id.bluetooth_auto_on_toggle_layout)
    }

    private fun getProgressBarAnimation(dialog: SystemUIDialog): ProgressBar {
        return dialog.requireViewById(R.id.bluetooth_tile_dialog_progress_animation)
    }

    private fun getProgressBarBackground(dialog: SystemUIDialog): View {
        return dialog.requireViewById(R.id.bluetooth_tile_dialog_progress_animation)
    }

    private fun getScrollViewContent(dialog: SystemUIDialog): View {
        return dialog.requireViewById(R.id.scroll_view)
    }

    private fun setupRecyclerView(dialog: SystemUIDialog) {
        getDeviceListView(dialog).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = deviceItemAdapter
        }
    }

    private fun showProgressBar(dialog: SystemUIDialog) {
        val progressBarAnimation = getProgressBarAnimation(dialog)
        val progressBarBackground = getProgressBarBackground(dialog)
        if (progressBarAnimation.visibility != VISIBLE) {
            progressBarAnimation.visibility = VISIBLE
            progressBarBackground.visibility = INVISIBLE
        }
    }

    private fun hideProgressBar(dialog: SystemUIDialog) {
        val progressBarAnimation = getProgressBarAnimation(dialog)
        val progressBarBackground = getProgressBarBackground(dialog)
        if (progressBarAnimation.visibility != INVISIBLE) {
            progressBarAnimation.visibility = INVISIBLE
            progressBarBackground.visibility = VISIBLE
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
            val view = layoutInflater.inflate(R.layout.bluetooth_device_item, parent, false)
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
        const val PROGRESS_BAR_ANIMATION_DURATION_MS = 1500L

        private fun Boolean.toInt(): Int {
            return if (this) 1 else 0
        }
    }
}
