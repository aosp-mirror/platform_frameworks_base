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

import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.MetricsLogger
import com.android.systemui.R
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.dagger.ControlsComponent.Visibility.AVAILABLE
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.ControlsActivity
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.statusbar.policy.KeyguardStateController
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

class DeviceControlsTile @Inject constructor(
    host: QSHost,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val controlsComponent: ControlsComponent,
    private val keyguardStateController: KeyguardStateController
) : QSTileImpl<QSTile.State>(
        host,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger
) {

    private var hasControlsApps = AtomicBoolean(false)

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    val icon: QSTile.Icon
        get() = ResourceIcon.get(controlsComponent.getTileImageId())

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
        return controlsComponent.getControlsController().isPresent
    }

    override fun newTileState(): QSTile.State {
        return QSTile.State().also {
            it.state = Tile.STATE_UNAVAILABLE // Start unavailable matching `hasControlsApps`
            it.handlesLongClick = false
        }
    }

    override fun handleClick(view: View?) {
        if (state.state == Tile.STATE_UNAVAILABLE) {
            return
        }

        val intent = Intent().apply {
            component = ComponentName(mContext, ControlsActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ControlsUiController.EXTRA_ANIMATE, true)
        }
        val animationController = view?.let {
            ActivityLaunchAnimator.Controller.fromView(
                    it, InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE)
        }

        mUiHandler.post {
            val showOverLockscreenWhenLocked = state.state == Tile.STATE_ACTIVE
            mActivityStarter.startActivity(
                intent, true /* dismissShade */, animationController, showOverLockscreenWhenLocked)
        }
    }

    override fun handleUpdateState(state: QSTile.State, arg: Any?) {
        state.label = tileLabel
        state.contentDescription = state.label
        state.icon = icon
        if (controlsComponent.isEnabled() && hasControlsApps.get()) {
            if (controlsComponent.getVisibility() == AVAILABLE) {
                val structure = controlsComponent
                    .getControlsController().get().getPreferredStructure().structure
                state.state = Tile.STATE_ACTIVE
                state.secondaryLabel = if (structure == tileLabel) null else structure
            } else {
                state.state = Tile.STATE_INACTIVE
                state.secondaryLabel = mContext.getText(R.string.controls_tile_locked)
            }
            state.stateDescription = state.secondaryLabel
        } else {
            state.state = Tile.STATE_UNAVAILABLE
        }
    }

    override fun getMetricsCategory(): Int {
        return 0
    }

    override fun getLongClickIntent(): Intent? {
        return null
    }

    override fun handleLongClick(view: View?) {}

    override fun getTileLabel(): CharSequence {
        return mContext.getText(controlsComponent.getTileTitleId())
    }
}
