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

package com.android.systemui.bluetooth.qsdialog

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
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.internal.R as InternalR
import com.android.internal.logging.UiEventLogger
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.res.R
import com.android.systemui.util.time.SystemClock
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

data class DeviceItemClick(val deviceItem: DeviceItem, val clickedView: View, val target: Target) {
    enum class Target {
        ENTIRE_ROW,
        ACTION_ICON,
    }
}

/** View content manager for showing active, connected and saved bluetooth devices. */
class BluetoothDetailsContentManager
@AssistedInject
internal constructor(
    @Assisted private val initialUiProperties: BluetoothTileDialogViewModel.UiProperties,
    @Assisted private val cachedContentHeight: Int,
    @Assisted private val bluetoothTileDialogCallback: BluetoothTileDialogCallback,
    @Assisted private val isInDialog: Boolean,
    @Assisted private val doneButtonCallback: () -> Unit,
    @Main private val mainDispatcher: CoroutineDispatcher,
    private val systemClock: SystemClock,
    private val uiEventLogger: UiEventLogger,
    private val logger: BluetoothTileDialogLogger,
) {

    private val mutableBluetoothStateToggle: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    internal val bluetoothStateToggle
        get() = mutableBluetoothStateToggle.asStateFlow()

    private val mutableBluetoothAutoOnToggle: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    internal val bluetoothAutoOnToggle
        get() = mutableBluetoothAutoOnToggle.asStateFlow()

    private val mutableDeviceItemClick: MutableStateFlow<DeviceItemClick?> = MutableStateFlow(null)
    internal val deviceItemClick
        get() = mutableDeviceItemClick.asStateFlow()

    private val mutableContentHeight: MutableStateFlow<Int?> = MutableStateFlow(null)
    internal val contentHeight
        get() = mutableContentHeight.asStateFlow()

    private val deviceItemAdapter: Adapter = Adapter()

    private var lastUiUpdateMs: Long = -1

    private var lastItemRow: Int = -1

    // UI Components
    private lateinit var contentView: View
    private lateinit var doneButton: Button
    private lateinit var bluetoothToggle: Switch
    private lateinit var subtitleTextView: TextView
    private lateinit var seeAllButton: View
    private lateinit var pairNewDeviceButton: View
    private lateinit var deviceListView: RecyclerView
    private lateinit var autoOnToggle: Switch
    private lateinit var autoOnToggleLayout: View
    private lateinit var autoOnToggleInfoTextView: TextView
    private lateinit var audioSharingButton: Button
    private lateinit var progressBarAnimation: ProgressBar
    private lateinit var progressBarBackground: View
    private lateinit var scrollViewContent: View

    @AssistedFactory
    internal interface Factory {
        fun create(
            initialUiProperties: BluetoothTileDialogViewModel.UiProperties,
            cachedContentHeight: Int,
            dialogCallback: BluetoothTileDialogCallback,
            isInDialog: Boolean,
            doneButtonCallback: () -> Unit,
        ): BluetoothDetailsContentManager
    }

    fun bind(contentView: View) {
        this.contentView = contentView

        doneButton = contentView.requireViewById(R.id.done_button)
        bluetoothToggle = contentView.requireViewById(R.id.bluetooth_toggle)
        subtitleTextView = contentView.requireViewById(R.id.bluetooth_tile_dialog_subtitle)
        seeAllButton = contentView.requireViewById(R.id.see_all_button)
        pairNewDeviceButton = contentView.requireViewById(R.id.pair_new_device_button)
        deviceListView = contentView.requireViewById(R.id.device_list)
        autoOnToggle = contentView.requireViewById(R.id.bluetooth_auto_on_toggle)
        autoOnToggleLayout = contentView.requireViewById(R.id.bluetooth_auto_on_toggle_layout)
        autoOnToggleInfoTextView =
            contentView.requireViewById(R.id.bluetooth_auto_on_toggle_info_text)
        audioSharingButton = contentView.requireViewById(R.id.audio_sharing_button)
        progressBarAnimation =
            contentView.requireViewById(R.id.bluetooth_tile_dialog_progress_animation)
        progressBarBackground =
            contentView.requireViewById(R.id.bluetooth_tile_dialog_progress_background)
        scrollViewContent = contentView.requireViewById(R.id.scroll_view)

        setupToggle()
        setupRecyclerView()
        setupDoneButton()

        subtitleTextView.text = contentView.context.getString(initialUiProperties.subTitleResId)
        seeAllButton.setOnClickListener { bluetoothTileDialogCallback.onSeeAllClicked(it) }
        pairNewDeviceButton.setOnClickListener {
            bluetoothTileDialogCallback.onPairNewDeviceClicked(it)
        }
        audioSharingButton.apply {
            setOnClickListener { bluetoothTileDialogCallback.onAudioSharingButtonClicked(it) }
            accessibilityDelegate =
                object : AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfo,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.addAction(
                            AccessibilityAction(
                                AccessibilityAction.ACTION_CLICK.id,
                                contentView.context.getString(
                                    R.string
                                        .quick_settings_bluetooth_audio_sharing_button_accessibility
                                ),
                            )
                        )
                    }
                }
        }
        scrollViewContent.apply {
            minimumHeight =
                resources.getDimensionPixelSize(initialUiProperties.scrollViewMinHeightResId)
            layoutParams.height = maxOf(cachedContentHeight, minimumHeight)
        }
    }

    fun start() {
        lastUiUpdateMs = systemClock.elapsedRealtime()
    }

    fun releaseView() {
        mutableContentHeight.value = scrollViewContent.measuredHeight
    }

    internal suspend fun animateProgressBar(animate: Boolean) {
        withContext(mainDispatcher) {
            if (animate) {
                showProgressBar()
            } else {
                delay(PROGRESS_BAR_ANIMATION_DURATION_MS)
                hideProgressBar()
            }
        }
    }

    internal suspend fun onDeviceItemUpdated(
        deviceItem: List<DeviceItem>,
        showSeeAll: Boolean,
        showPairNewDevice: Boolean,
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

    internal fun onBluetoothStateUpdated(
        isEnabled: Boolean,
        uiProperties: BluetoothTileDialogViewModel.UiProperties,
    ) {
        bluetoothToggle.apply {
            isChecked = isEnabled
            setEnabled(true)
            alpha = ENABLED_ALPHA
        }
        subtitleTextView.text = contentView.context.getString(uiProperties.subTitleResId)
        autoOnToggleLayout.visibility = uiProperties.autoOnToggleVisibility
    }

    internal fun onBluetoothAutoOnUpdated(isEnabled: Boolean, @StringRes infoResId: Int) {
        autoOnToggle.isChecked = isEnabled
        autoOnToggleInfoTextView.text = contentView.context.getString(infoResId)
    }

    internal fun onAudioSharingButtonUpdated(visibility: Int, label: String?, isActive: Boolean) {
        audioSharingButton.apply {
            this.visibility = visibility
            label?.let { text = it }
            this.isActivated = isActive
        }
    }

    private fun setupToggle() {
        bluetoothToggle.setOnCheckedChangeListener { view, isChecked ->
            mutableBluetoothStateToggle.value = isChecked
            view.apply {
                isEnabled = false
                alpha = DISABLED_ALPHA
            }
            logger.logBluetoothState(BluetoothStateStage.USER_TOGGLED, isChecked.toString())
            uiEventLogger.log(BluetoothTileDialogUiEvent.BLUETOOTH_TOGGLE_CLICKED)
        }

        autoOnToggleLayout.visibility = initialUiProperties.autoOnToggleVisibility
        autoOnToggle.setOnCheckedChangeListener { _, isChecked ->
            mutableBluetoothAutoOnToggle.value = isChecked
            uiEventLogger.log(BluetoothTileDialogUiEvent.BLUETOOTH_AUTO_ON_TOGGLE_CLICKED)
        }
    }

    private fun setupDoneButton() {
        if (isInDialog) {
            doneButton.setOnClickListener { doneButtonCallback() }
        } else {
            doneButton.visibility = GONE
        }
    }

    private fun setupRecyclerView() {
        deviceListView.apply {
            layoutManager = LinearLayoutManager(contentView.context)
            adapter = deviceItemAdapter
        }
    }

    private fun showProgressBar() {
        if (progressBarAnimation.visibility != VISIBLE) {
            progressBarAnimation.visibility = VISIBLE
            progressBarBackground.visibility = INVISIBLE
        }
    }

    private fun hideProgressBar() {
        if (progressBarAnimation.visibility != INVISIBLE) {
            progressBarAnimation.visibility = INVISIBLE
            progressBarBackground.visibility = VISIBLE
        }
    }

    internal inner class Adapter : RecyclerView.Adapter<Adapter.DeviceItemViewHolder>() {

        private val diffUtilCallback =
            object : DiffUtil.ItemCallback<DeviceItem>() {
                override fun areItemsTheSame(
                    deviceItem1: DeviceItem,
                    deviceItem2: DeviceItem,
                ): Boolean {
                    return deviceItem1.cachedBluetoothDevice == deviceItem2.cachedBluetoothDevice
                }

                override fun areContentsTheSame(
                    deviceItem1: DeviceItem,
                    deviceItem2: DeviceItem,
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
            holder.bind(item)
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
            private val actionIcon = view.requireViewById<ImageView>(R.id.gear_icon_image)
            private val actionIconView = view.requireViewById<View>(R.id.gear_icon)
            private val divider = view.requireViewById<View>(R.id.divider)

            internal fun bind(item: DeviceItem) {
                container.apply {
                    isEnabled = item.isEnabled
                    background = item.background?.let { context.getDrawable(it) }
                    setOnClickListener {
                        mutableDeviceItemClick.value =
                            DeviceItemClick(item, it, DeviceItemClick.Target.ENTIRE_ROW)
                        uiEventLogger.log(BluetoothTileDialogUiEvent.DEVICE_CLICKED)
                    }

                    // updating icon colors
                    val tintColor =
                        context.getColor(
                            if (item.isActive) InternalR.color.materialColorOnPrimaryContainer
                            else InternalR.color.materialColorOnSurface
                        )

                    // update icons
                    iconView.apply {
                        item.iconWithDescription?.let {
                            setImageDrawable(it.first)
                            contentDescription = it.second
                        }
                    }

                    actionIcon.setImageResource(item.actionIconRes)
                    actionIcon.drawable?.setTint(tintColor)

                    divider.setBackgroundColor(tintColor)

                    // update text styles
                    nameView.setTextAppearance(
                        if (item.isActive) R.style.TextAppearance_BluetoothTileDialog_Active
                        else R.style.TextAppearance_BluetoothTileDialog
                    )
                    summaryView.setTextAppearance(
                        if (item.isActive) R.style.TextAppearance_BluetoothTileDialog_Active
                        else R.style.TextAppearance_BluetoothTileDialog
                    )

                    accessibilityDelegate =
                        object : AccessibilityDelegate() {
                            override fun onInitializeAccessibilityNodeInfo(
                                host: View,
                                info: AccessibilityNodeInfo,
                            ) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                info.addAction(
                                    AccessibilityAction(
                                        AccessibilityAction.ACTION_CLICK.id,
                                        item.actionAccessibilityLabel,
                                    )
                                )
                            }
                        }
                }
                nameView.text = item.deviceName
                summaryView.text = item.connectionSummary

                actionIconView.setOnClickListener {
                    mutableDeviceItemClick.value =
                        DeviceItemClick(item, it, DeviceItemClick.Target.ACTION_ICON)
                }
            }
        }
    }

    internal companion object {
        const val MIN_HEIGHT_CHANGE_INTERVAL_MS = 800L
        const val ACTION_BLUETOOTH_DEVICE_DETAILS =
            "com.android.settings.BLUETOOTH_DEVICE_DETAIL_SETTINGS"
        const val ACTION_PREVIOUSLY_CONNECTED_DEVICE =
            "com.android.settings.PREVIOUSLY_CONNECTED_DEVICE"
        const val ACTION_PAIR_NEW_DEVICE = "android.settings.BLUETOOTH_PAIRING_SETTINGS"
        const val ACTION_AUDIO_SHARING = "com.android.settings.BLUETOOTH_AUDIO_SHARING_SETTINGS"
        const val DISABLED_ALPHA = 0.3f
        const val ENABLED_ALPHA = 1f
        const val PROGRESS_BAR_ANIMATION_DURATION_MS = 1500L

        private fun Boolean.toInt(): Int {
            return if (this) 1 else 0
        }
    }
}
