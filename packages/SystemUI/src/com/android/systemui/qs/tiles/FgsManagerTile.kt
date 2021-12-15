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
import android.provider.DeviceConfig
import android.view.View
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.internal.logging.MetricsLogger
import com.android.systemui.DejankUtils
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.fgsmanager.FgsManagerDialogFactory
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.statusbar.policy.RunningFgsController
import com.android.systemui.statusbar.policy.RunningFgsController.UserPackageTime
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Quicksettings tile for the foreground services manager (task manager)
 */
class FgsManagerTile @Inject constructor(
    host: QSHost?,
    @Background backgroundLooper: Looper?,
    @Background private val backgroundExecutor: Executor?,
    @Main mainHandler: Handler?,
    falsingManager: FalsingManager?,
    metricsLogger: MetricsLogger?,
    statusBarStateController: StatusBarStateController?,
    activityStarter: ActivityStarter?,
    qsLogger: QSLogger?,
    private val fgsManagerDialogFactory: FgsManagerDialogFactory,
    private val runningFgsController: RunningFgsController
) : QSTileImpl<QSTile.State>(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
        statusBarStateController, activityStarter, qsLogger), RunningFgsController.Callback {

    override fun handleInitialize() {
        super.handleInitialize()
        mUiHandler.post { runningFgsController.observe(lifecycle, this) }
    }

    override fun isAvailable(): Boolean {
        return DejankUtils.whitelistIpcs<Boolean> {
            DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                    SystemUiDeviceConfigFlags.TASK_MANAGER_ENABLED, false)
        }
    }

    override fun newTileState(): QSTile.State {
        return QSTile.State()
    }

    override fun handleClick(view: View?) {
        mUiHandler.post { fgsManagerDialogFactory.create(view) }
    }

    override fun handleUpdateState(state: QSTile.State?, arg: Any?) {
        state?.label = tileLabel
        state?.secondaryLabel = runningFgsController.getPackagesWithFgs().size.toString()
        state?.handlesLongClick = false
        state?.icon = ResourceIcon.get(R.drawable.ic_list)
    }

    override fun getMetricsCategory(): Int = 0

    override fun getLongClickIntent(): Intent? = null

    // Inline the string so we don't waste translator time since this isn't used in the mocks.
    // TODO If mocks change need to remember to move this to strings.xml
    override fun getTileLabel(): CharSequence = "Active apps"

    override fun onFgsPackagesChanged(packages: List<UserPackageTime>) = refreshState()
}