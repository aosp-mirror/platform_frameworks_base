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
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.systemui.Prefs
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.bluetooth.qsdialog.BluetoothDetailsContentManager.Companion.ACTION_AUDIO_SHARING
import com.android.systemui.bluetooth.qsdialog.BluetoothDetailsContentManager.Companion.ACTION_PAIR_NEW_DEVICE
import com.android.systemui.bluetooth.qsdialog.BluetoothDetailsContentManager.Companion.ACTION_PREVIOUSLY_CONNECTED_DEVICE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * ViewModel for Bluetooth Dialog or Bluetooth Details View after clicking on the Bluetooth QS tile.
 *
 * TODO: b/378513956 Rename this class to BluetoothDetailsContentViewModel, since it's not only used
 *   by the dialog view.
 */
@SysUISingleton
internal class BluetoothTileDialogViewModel
@Inject
constructor(
    private val deviceItemInteractor: DeviceItemInteractor,
    private val deviceItemActionInteractor: DeviceItemActionInteractor,
    private val bluetoothStateInteractor: BluetoothStateInteractor,
    private val bluetoothAutoOnInteractor: BluetoothAutoOnInteractor,
    private val audioSharingInteractor: AudioSharingInteractor,
    private val audioSharingButtonViewModelFactory: AudioSharingButtonViewModel.Factory,
    private val bluetoothDeviceMetadataInteractor: BluetoothDeviceMetadataInteractor,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val activityStarter: ActivityStarter,
    private val uiEventLogger: UiEventLogger,
    private val logger: BluetoothTileDialogLogger,
    @Application private val coroutineScope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Main private val sharedPreferences: SharedPreferences,
    private val bluetoothDialogDelegateFactory: BluetoothTileDialogDelegate.Factory,
    private val bluetoothDetailsContentManagerFactory: BluetoothDetailsContentManager.Factory,
) : BluetoothTileDialogCallback {

    lateinit var contentManager: BluetoothDetailsContentManager
    private var job: Job? = null

    /**
     * Shows the details content.
     *
     * @param view The view from which the dialog is shown. If view is null, it should show the
     *   bluetooth tile details view.
     *
     * TODO: b/378513956 Refactor this method into 2. One is called by the dialog to show the
     *   dialog, another is called by the details view model to bind the view.
     */
    fun showDetailsContent(expandable: Expandable?, view: View?) {
        cancelJob()

        job =
            coroutineScope.launch(context = mainDispatcher) {
                var updateDeviceItemJob: Job?
                var updateDialogUiJob: Job? = null
                val dialog: SystemUIDialog?
                val context: Context

                if (view == null) {
                    // Render with dialog
                    val dialogDelegate = createBluetoothTileDialog()
                    dialog = dialogDelegate.createDialog()
                    context = dialog.context

                    val controller =
                        expandable?.dialogTransitionController(
                            DialogCuj(
                                InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                INTERACTION_JANK_TAG,
                            )
                        )
                    controller?.let {
                        dialogTransitionAnimator.show(
                            dialog,
                            it,
                            animateBackgroundBoundsChange = true,
                        )
                    } ?: dialog.show()
                    // contentManager is created after dialog.show
                    contentManager = dialogDelegate.contentManager
                } else {
                    // Render with tile details view
                    dialog = null
                    context = view.context
                    contentManager = createContentManager()
                    contentManager.bind(view)
                    contentManager.start()
                }

                updateDeviceItemJob = launch {
                    deviceItemInteractor.updateDeviceItems(context, DeviceFetchTrigger.FIRST_LOAD)
                }

                // deviceItemUpdate is emitted when device item list is done fetching, update UI and
                // stop the progress bar.
                combine(
                        deviceItemInteractor.deviceItemUpdate,
                        deviceItemInteractor.showSeeAllUpdate,
                    ) { deviceItem, showSeeAll ->
                        updateDialogUiJob?.cancel()
                        updateDialogUiJob = launch {
                            contentManager.apply {
                                onDeviceItemUpdated(
                                    deviceItem,
                                    showSeeAll,
                                    showPairNewDevice =
                                        bluetoothStateInteractor.isBluetoothEnabled(),
                                )
                                animateProgressBar(false)
                            }
                        }
                    }
                    .launchIn(this)

                // deviceItemUpdateRequest is emitted when a bluetooth callback is called, re-fetch
                // the device item list and animate the progress bar.
                merge(
                        deviceItemInteractor.deviceItemUpdateRequest,
                        bluetoothDeviceMetadataInteractor.metadataUpdate,
                        if (
                            audioSharingInteractor.audioSharingAvailable() &&
                                audioSharingInteractor.qsDialogImprovementAvailable()
                        ) {
                            audioSharingInteractor.audioSourceStateUpdate
                        } else {
                            emptyFlow()
                        },
                    )
                    .onEach {
                        contentManager.animateProgressBar(true)
                        updateDeviceItemJob?.cancel()
                        updateDeviceItemJob = launch {
                            deviceItemInteractor.updateDeviceItems(
                                context,
                                DeviceFetchTrigger.BLUETOOTH_CALLBACK_RECEIVED,
                            )
                        }
                    }
                    .launchIn(this)

                if (audioSharingInteractor.audioSharingAvailable()) {
                    if (audioSharingInteractor.qsDialogImprovementAvailable()) {
                        launch { audioSharingInteractor.handleAudioSourceWhenReady() }
                    }

                    audioSharingButtonViewModelFactory.create().run {
                        audioSharingButtonStateUpdate
                            .onEach {
                                when (it) {
                                    is AudioSharingButtonState.Visible -> {
                                        contentManager.onAudioSharingButtonUpdated(
                                            VISIBLE,
                                            context.getString(it.resId),
                                            it.isActive,
                                        )
                                    }
                                    is AudioSharingButtonState.Gone -> {
                                        contentManager.onAudioSharingButtonUpdated(
                                            GONE,
                                            label = null,
                                            isActive = false,
                                        )
                                    }
                                }
                            }
                            .launchIn(this@launch)
                        launch { activate() }
                    }
                }

                // bluetoothStateUpdate is emitted when bluetooth on/off state is changed, re-fetch
                // the device item list.
                bluetoothStateInteractor.bluetoothStateUpdate
                    .onEach {
                        contentManager.onBluetoothStateUpdated(
                            it,
                            UiProperties.build(it, isAutoOnToggleFeatureAvailable()),
                        )
                        updateDeviceItemJob?.cancel()
                        updateDeviceItemJob = launch {
                            deviceItemInteractor.updateDeviceItems(
                                context,
                                DeviceFetchTrigger.BLUETOOTH_STATE_CHANGE_RECEIVED,
                            )
                        }
                    }
                    .launchIn(this)

                // bluetoothStateToggle is emitted when user toggles the bluetooth state switch,
                // send the new value to the bluetoothStateInteractor and animate the progress bar.
                contentManager.bluetoothStateToggle
                    .filterNotNull()
                    .onEach {
                        contentManager.animateProgressBar(true)
                        bluetoothStateInteractor.setBluetoothEnabled(it)
                    }
                    .launchIn(this)

                // deviceItemClick is emitted when user clicked on a device item.
                contentManager.deviceItemClick
                    .filterNotNull()
                    .onEach {
                        when (it.target) {
                            DeviceItemClick.Target.ENTIRE_ROW -> {
                                deviceItemActionInteractor.onClick(it.deviceItem, dialog)
                                logger.logDeviceClick(
                                    it.deviceItem.cachedBluetoothDevice.address,
                                    it.deviceItem.type,
                                )
                            }

                            DeviceItemClick.Target.ACTION_ICON -> {
                                deviceItemActionInteractor.onActionIconClick(it.deviceItem) { intent
                                    ->
                                    startSettingsActivity(intent, it.clickedView)
                                }
                            }
                        }
                    }
                    .launchIn(this)

                // contentHeight is emitted when the dialog is dismissed.
                contentManager.contentHeight
                    .filterNotNull()
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
                        .onEach {
                            contentManager.onBluetoothAutoOnUpdated(
                                it,
                                if (it) R.string.turn_on_bluetooth_auto_info_enabled
                                else R.string.turn_on_bluetooth_auto_info_disabled,
                            )
                        }
                        .launchIn(this)

                    // bluetoothAutoOnToggle is emitted when user toggles the bluetooth auto on
                    // switch, send the new value to the bluetoothAutoOnInteractor.
                    contentManager.bluetoothAutoOnToggle
                        .filterNotNull()
                        .onEach { bluetoothAutoOnInteractor.setEnabled(it) }
                        .launchIn(this)
                }

                produce<Unit> { awaitClose { dialog?.cancel() } }
            }
    }

    private suspend fun createBluetoothTileDialog(): BluetoothTileDialogDelegate {
        return bluetoothDialogDelegateFactory.create(
            getUiProperties(),
            getCachedContentHeight(),
            this@BluetoothTileDialogViewModel,
            { cancelJob() },
        )
    }

    private suspend fun createContentManager(): BluetoothDetailsContentManager {
        return bluetoothDetailsContentManagerFactory.create(
            getUiProperties(),
            getCachedContentHeight(),
            this@BluetoothTileDialogViewModel,
            /* isInDialog= */ false,
            /* doneButtonCallback= */ fun() {},
        )
    }

    private suspend fun getUiProperties(): UiProperties {
        return UiProperties.build(
            bluetoothStateInteractor.isBluetoothEnabled(),
            isAutoOnToggleFeatureAvailable(),
        )
    }

    private suspend fun getCachedContentHeight(): Int {
        return withContext(backgroundDispatcher) {
            sharedPreferences.getInt(CONTENT_HEIGHT_PREF_KEY, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onSeeAllClicked(view: View) {
        uiEventLogger.log(BluetoothTileDialogUiEvent.SEE_ALL_CLICKED)
        startSettingsActivity(Intent(ACTION_PREVIOUSLY_CONNECTED_DEVICE), view)
    }

    override fun onPairNewDeviceClicked(view: View) {
        uiEventLogger.log(BluetoothTileDialogUiEvent.PAIR_NEW_DEVICE_CLICKED)
        startSettingsActivity(Intent(ACTION_PAIR_NEW_DEVICE), view)
    }

    override fun onAudioSharingButtonClicked(view: View) {
        uiEventLogger.log(BluetoothTileDialogUiEvent.BLUETOOTH_AUDIO_SHARING_BUTTON_CLICKED)
        val intent =
            Intent(ACTION_AUDIO_SHARING).apply {
                putExtra(
                    EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                    Bundle().apply {
                        putBoolean(LocalBluetoothLeBroadcast.EXTRA_START_LE_AUDIO_SHARING, true)
                    },
                )
            }
        startSettingsActivity(intent, view)
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
    }

    private fun startSettingsActivity(intent: Intent, view: View) {
        if (job?.isActive == true) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val controller = dialogTransitionAnimator.createActivityTransitionController(view)
            // The controller will be null when the screen is locked and going to show the
            // primary bouncer. In this case we dismiss the dialog manually.
            if (controller == null) {
                cancelJob()
            }
            activityStarter.postStartActivityDismissingKeyguard(intent, 0, controller)
        }
    }

    @VisibleForTesting
    internal suspend fun isAutoOnToggleFeatureAvailable() =
        bluetoothAutoOnInteractor.isAutoOnSupported()

    companion object {
        private const val INTERACTION_JANK_TAG = "bluetooth_tile_dialog"
        private const val CONTENT_HEIGHT_PREF_KEY = Prefs.Key.BLUETOOTH_TILE_DIALOG_CONTENT_HEIGHT
        private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

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
                isAutoOnToggleFeatureAvailable: Boolean,
            ) =
                UiProperties(
                    subTitleResId = getSubtitleResId(isBluetoothEnabled),
                    autoOnToggleVisibility =
                        if (isAutoOnToggleFeatureAvailable && !isBluetoothEnabled) VISIBLE
                        else GONE,
                    scrollViewMinHeightResId =
                        if (isAutoOnToggleFeatureAvailable)
                            R.dimen.bluetooth_dialog_scroll_view_min_height_with_auto_on
                        else R.dimen.bluetooth_dialog_scroll_view_min_height,
                )
        }
    }
}

interface BluetoothTileDialogCallback {
    fun onSeeAllClicked(view: View)

    fun onPairNewDeviceClicked(view: View)

    fun onAudioSharingButtonClicked(view: View)
}
