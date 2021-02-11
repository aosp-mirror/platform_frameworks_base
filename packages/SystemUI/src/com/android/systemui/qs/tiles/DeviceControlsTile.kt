/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import com.android.internal.logging.MetricsLogger
import com.android.systemui.R
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.dagger.ControlsComponent.Visibility.UNAVAILABLE
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.ControlsDialog
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.util.settings.GlobalSettings
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider

class DeviceControlsTile @Inject constructor(
    host: QSHost,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val controlsComponent: ControlsComponent,
    private val featureFlags: FeatureFlags,
    private val dialogProvider: Provider<ControlsDialog>,
    globalSettings: GlobalSettings
) : QSTileImpl<QSTile.State>(
        host,
        backgroundLooper,
        mainHandler,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger
) {

    companion object {
        const val SETTINGS_FLAG = "controls_lockscreen"
    }

    private val controlsLockscreen = globalSettings.getInt(SETTINGS_FLAG, 0) != 0
    private var hasControlsApps = AtomicBoolean(false)
    private val intent = Intent(Settings.ACTION_DEVICE_CONTROLS_SETTINGS)

    private var controlsDialog: ControlsDialog? = null
    private val icon = ResourceIcon.get(R.drawable.ic_device_light)

    private val listingCallback = object : ControlsListingController.ControlsListingCallback {
        override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {
            if (hasControlsApps.compareAndSet(serviceInfos.isEmpty(), serviceInfos.isNotEmpty())) {
                refreshState()
            }
        }
    }

    init {
        controlsComponent.getControlsListingController().ifPresent {
            it.observe(this, listingCallback)
        }
    }

    override fun isAvailable(): Boolean {
        return featureFlags.isKeyguardLayoutEnabled &&
                controlsLockscreen &&
                controlsComponent.getVisibility() != UNAVAILABLE
    }

    override fun newTileState(): QSTile.State {
        return QSTile.State().also {
            it.state = Tile.STATE_UNAVAILABLE // Start unavailable matching `hasControlsApps`
        }
    }

    override fun handleDestroy() {
        dismissDialog()
        super.handleDestroy()
    }

    private fun createDialog() {
        if (controlsDialog?.isShowing != true) {
            controlsDialog = dialogProvider.get()
        }
    }

    private fun dismissDialog() {
        controlsDialog?.dismiss()?.also {
            controlsDialog = null
        }
    }

    override fun handleClick() {
        if (state.state != Tile.STATE_UNAVAILABLE) {
            mUiHandler.post {
                createDialog()
                controlsDialog?.show(controlsComponent.getControlsUiController().get())
            }
        }
    }

    override fun handleUpdateState(state: QSTile.State, arg: Any?) {
        state.label = tileLabel
        state.secondaryLabel = ""
        state.stateDescription = ""
        state.contentDescription = state.label
        state.icon = icon
        if (hasControlsApps.get()) {
            state.state = Tile.STATE_ACTIVE
            if (controlsDialog == null) {
                mUiHandler.post(this::createDialog)
            }
        } else {
            state.state = Tile.STATE_UNAVAILABLE
            dismissDialog()
        }
    }

    override fun getMetricsCategory(): Int {
        return 0
    }

    override fun getLongClickIntent(): Intent {
        return intent
    }

    override fun getTileLabel(): CharSequence {
        return mContext.getText(R.string.quick_controls_title)
    }
}
