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
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.annotation.DimenRes
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.flags.Flags.bluetoothQsTileDialogAutoOnToggle
import com.android.systemui.Prefs
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothTileDialogDelegate.Companion.ACTION_BLUETOOTH_DEVICE_DETAILS
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothTileDialogDelegate.Companion.ACTION_PAIR_NEW_DEVICE
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothTileDialogDelegate.Companion.ACTION_PREVIOUSLY_CONNECTED_DEVICE
import com.android.systemui.qs.tiles.dialog.bluetooth.BluetoothTileDialogDelegate.Companion.MAX_DEVICE_ITEM_ENTRY
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ViewModel for Bluetooth Dialog after clicking on the Bluetooth QS tile. */
@SysUISingleton
internal class BluetoothTileDialogViewModel
@Inject
constructor(
    private val deviceItemInteractor: DeviceItemInteractor,
    private val bluetoothStateInteractor: BluetoothStateInteractor,
    private val bluetoothAutoOnInteractor: BluetoothAutoOnInteractor,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val activityStarter: ActivityStarter,
    private val uiEventLogger: UiEventLogger,
    @Application private val coroutineScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Main private val sharedPreferences: SharedPreferences,
    private val bluetoothDialogDelegateFactory: BluetoothTileDialogDelegate.Factory,
) : BluetoothTileDialogCallback {

    private var job: Job? = null

    /**
     * Shows the dialog.
     *
     * @param context The context in which the dialog is displayed.
     * @param view The view from which the dialog is shown.
     */
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun showDialog(context: Context, view: View?) {
        cancelJob()

        job =
            coroutineScope.launch(mainDispatcher) {
                var updateDeviceItemJob: Job?
                var updateDialogUiJob: Job? = null
                val dialogDelegate = createBluetoothTileDialog(context)
                val dialog = dialogDelegate.createDialog()

                view?.let {
                    dialogTransitionAnimator.showFromView(
                        dialog,
                        it,
                        animateBackgroundBoundsChange = true,
                        cuj =
                            DialogCuj(
                                InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                INTERACTION_JANK_TAG
                            )
                    )
                }
                    ?: dialog.show()

                updateDeviceItemJob = launch {
                    deviceItemInteractor.updateDeviceItems(context, DeviceFetchTrigger.FIRST_LOAD)
                }

                // deviceItemUpdate is emitted when device item list is done fetching, update UI and
                // stop the progress bar.
                deviceItemInteractor.deviceItemUpdate
                    .onEach {
                        updateDialogUiJob?.cancel()
                        updateDialogUiJob = launch {
                            dialogDelegate.apply {
                                onDeviceItemUpdated(
                                    dialog,
                                    it.take(MAX_DEVICE_ITEM_ENTRY),
                                    showSeeAll = it.size > MAX_DEVICE_ITEM_ENTRY,
                                    showPairNewDevice = bluetoothStateInteractor.isBluetoothEnabled
                                )
                                animateProgressBar(dialog, false)
                            }
                        }
                    }
                    .launchIn(this)

                // deviceItemUpdateRequest is emitted when a bluetooth callback is called, re-fetch
                // the device item list and animiate the progress bar.
                deviceItemInteractor.deviceItemUpdateRequest
                    .onEach {
                        dialogDelegate.animateProgressBar(dialog, true)
                        updateDeviceItemJob?.cancel()
                        updateDeviceItemJob = launch {
                            deviceItemInteractor.updateDeviceItems(
                                context,
                                DeviceFetchTrigger.BLUETOOTH_CALLBACK_RECEIVED
                            )
                        }
                    }
                    .launchIn(this)

                // bluetoothStateUpdate is emitted when bluetooth on/off state is changed, re-fetch
                // the device item list.
                bluetoothStateInteractor.bluetoothStateUpdate
                    .filterNotNull()
                    .onEach {
                        dialogDelegate.onBluetoothStateUpdated(
                            dialog,
                            it,
                            UiProperties.build(it, isAutoOnToggleFeatureAvailable())
                        )
                        updateDeviceItemJob?.cancel()
                        updateDeviceItemJob = launch {
                            deviceItemInteractor.updateDeviceItems(
                                context,
                                DeviceFetchTrigger.BLUETOOTH_STATE_CHANGE_RECEIVED
                            )
                        }
                    }
                    .launchIn(this)

                // bluetoothStateToggle is emitted when user toggles the bluetooth state switch,
                // send the new value to the bluetoothStateInteractor and animate the progress bar.
                dialogDelegate.bluetoothStateToggle
                    .onEach {
                        dialogDelegate.animateProgressBar(dialog, true)
                        bluetoothStateInteractor.isBluetoothEnabled = it
                    }
                    .launchIn(this)

                // deviceItemClick is emitted when user clicked on a device item.
                dialogDelegate.deviceItemClick
                    .onEach { deviceItemInteractor.updateDeviceItemOnClick(it) }
                    .launchIn(this)

                // contentHeight is emitted when the dialog is dismissed.
                dialogDelegate.contentHeight
                    .onEach {
                        withContext(backgroundDispatcher) {
                            sharedPreferences.edit().putInt(CONTENT_HEIGHT_PREF_KEY, it).apply()
                        }
                    }
                    .launchIn(this)

                if (isAutoOnToggleFeatureAvailable()) {
                    // bluetoothAutoOnUpdate is emitted when bluetooth auto on on/off state is
                    // changed.
                    bluetoothAutoOnInteractor.isEnabled
                        .onEach { dialogDelegate.onBluetoothAutoOnUpdated(dialog, it) }
                        .launchIn(this)

                    // bluetoothAutoOnToggle is emitted when user toggles the bluetooth auto on
                    // switch, send the new value to the bluetoothAutoOnInteractor.
                    dialogDelegate.bluetoothAutoOnToggle
                        .filterNotNull()
                        .onEach { bluetoothAutoOnInteractor.setEnabled(it) }
                        .launchIn(this)
                }

                produce<Unit> { awaitClose { dialog.cancel() } }
            }
    }

    private suspend fun createBluetoothTileDialog(context: Context): BluetoothTileDialogDelegate {
        val cachedContentHeight =
            withContext(backgroundDispatcher) {
                sharedPreferences.getInt(
                    CONTENT_HEIGHT_PREF_KEY,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

        return bluetoothDialogDelegateFactory.create(
            context,
            UiProperties.build(
                bluetoothStateInteractor.isBluetoothEnabled,
                isAutoOnToggleFeatureAvailable()
            ),
            cachedContentHeight,
            bluetoothStateInteractor.isBluetoothEnabled,
            this@BluetoothTileDialogViewModel,
            { cancelJob() }
        )
    }

    override fun onDeviceItemGearClicked(deviceItem: DeviceItem, view: View) {
        uiEventLogger.log(BluetoothTileDialogUiEvent.DEVICE_GEAR_CLICKED)
        val intent =
            Intent(ACTION_BLUETOOTH_DEVICE_DETAILS).apply {
                putExtra(
                    ":settings:show_fragment_args",
                    Bundle().apply {
                        putString("device_address", deviceItem.cachedBluetoothDevice.address)
                    }
                )
            }
        startSettingsActivity(intent, view)
    }

    override fun onSeeAllClicked(view: View) {
        uiEventLogger.log(BluetoothTileDialogUiEvent.SEE_ALL_CLICKED)
        startSettingsActivity(Intent(ACTION_PREVIOUSLY_CONNECTED_DEVICE), view)
    }

    override fun onPairNewDeviceClicked(view: View) {
        uiEventLogger.log(BluetoothTileDialogUiEvent.PAIR_NEW_DEVICE_CLICKED)
        startSettingsActivity(Intent(ACTION_PAIR_NEW_DEVICE), view)
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
    }

    private fun startSettingsActivity(intent: Intent, view: View) {
        if (job?.isActive == true) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            activityStarter.postStartActivityDismissingKeyguard(
                intent,
                0,
                dialogTransitionAnimator.createActivityTransitionController(view)
            )
        }
    }

    @VisibleForTesting
    internal suspend fun isAutoOnToggleFeatureAvailable() =
        bluetoothQsTileDialogAutoOnToggle() && bluetoothAutoOnInteractor.isValuePresent()

    companion object {
        private const val INTERACTION_JANK_TAG = "bluetooth_tile_dialog"
        private const val CONTENT_HEIGHT_PREF_KEY = Prefs.Key.BLUETOOTH_TILE_DIALOG_CONTENT_HEIGHT
        private fun getSubtitleResId(isBluetoothEnabled: Boolean) =
            if (isBluetoothEnabled) R.string.quick_settings_bluetooth_tile_subtitle
            else R.string.bt_is_off
    }

    internal data class UiProperties(
        @StringRes val subTitleResId: Int,
        val autoOnToggleVisibility: Int,
        @DimenRes val scrollViewMinHeightResId: Int,
    ) {
        companion object {
            internal fun build(
                isBluetoothEnabled: Boolean,
                isAutoOnToggleFeatureAvailable: Boolean
            ) =
                UiProperties(
                    subTitleResId = getSubtitleResId(isBluetoothEnabled),
                    autoOnToggleVisibility =
                        if (isAutoOnToggleFeatureAvailable && !isBluetoothEnabled) VISIBLE
                        else GONE,
                    scrollViewMinHeightResId =
                        if (isAutoOnToggleFeatureAvailable)
                            R.dimen.bluetooth_dialog_scroll_view_min_height_with_auto_on
                        else R.dimen.bluetooth_dialog_scroll_view_min_height
                )
        }
    }
}

interface BluetoothTileDialogCallback {
    fun onDeviceItemGearClicked(deviceItem: DeviceItem, view: View)
    fun onSeeAllClicked(view: View)
    fun onPairNewDeviceClicked(view: View)
}
