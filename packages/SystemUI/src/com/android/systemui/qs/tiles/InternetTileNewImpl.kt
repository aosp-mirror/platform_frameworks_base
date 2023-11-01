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

package com.android.systemui.qs.tiles

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Switch
import com.android.internal.logging.MetricsLogger
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.dialog.InternetDialogFactory
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.pipeline.shared.ui.binder.InternetTileBinder
import com.android.systemui.statusbar.pipeline.shared.ui.model.InternetTileModel
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.InternetTileViewModel
import javax.inject.Inject

class InternetTileNewImpl
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    viewModel: InternetTileViewModel,
    private val internetDialogFactory: InternetDialogFactory,
    private val accessPointController: AccessPointController,
) :
    QSTileImpl<QSTile.BooleanState>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger
    ) {
    private var model: InternetTileModel = viewModel.tileModel.value

    init {
        InternetTileBinder.bind(lifecycle, viewModel.tileModel) { newModel ->
            model = newModel
            refreshState()
        }
    }

    override fun getTileLabel(): CharSequence =
        mContext.getString(R.string.quick_settings_internet_label)

    override fun newTileState(): QSTile.BooleanState {
        return QSTile.BooleanState().also { it.forceExpandIcon = true }
    }

    override fun handleClick(view: View?) {
        mainHandler.post {
            internetDialogFactory.create(
                aboveStatusBar = true,
                accessPointController.canConfigMobileData(),
                accessPointController.canConfigWifi(),
                view,
            )
        }
    }

    override fun handleUpdateState(state: QSTile.BooleanState, arg: Any?) {
        state.label = mContext.resources.getString(R.string.quick_settings_internet_label)
        state.expandedAccessibilityClassName = Switch::class.java.name

        model.applyTo(state, mContext)
    }

    override fun getLongClickIntent(): Intent = WIFI_SETTINGS

    companion object {
        private val WIFI_SETTINGS = Intent(Settings.ACTION_WIFI_SETTINGS)
    }
}
