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
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.MetricsLogger
import com.android.systemui.animation.Expandable
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.dagger.ControlsComponent.Visibility.AVAILABLE
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.controls.ui.SelectedItem
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
import com.android.systemui.res.R
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

class DeviceControlsTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val controlsComponent: ControlsComponent,
) :
    QSTileImpl<QSTile.State>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
    ) {

    private var hasControlsApps = AtomicBoolean(false)

    private var icon: QSTile.Icon? = null

    private val listingCallback =
        object : ControlsListingController.ControlsListingCallback {
            override fun onServicesUpdated(serviceInfos: List<ControlsServiceInfo>) {
                if (
                    hasControlsApps.compareAndSet(serviceInfos.isEmpty(), serviceInfos.isNotEmpty())
                ) {
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

    override fun handleClick(expandable: Expandable?) {
        if (state.state == Tile.STATE_UNAVAILABLE) {
            return
        }

        val intent =
            Intent().apply {
                component =
                    ComponentName(
                        mContext,
                        controlsComponent.getControlsUiController().get().resolveActivity(),
                    )
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ControlsUiController.EXTRA_ANIMATE, true)
            }
        val animationController =
            expandable?.activityTransitionController(
                InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE
            )

        mUiHandler.post {
            val showOverLockscreenWhenLocked = state.state == Tile.STATE_ACTIVE
            mActivityStarter.startActivity(
                intent,
                true /* dismissShade */,
                animationController,
                showOverLockscreenWhenLocked,
            )
        }
    }

    override fun handleUpdateState(state: QSTile.State, arg: Any?) {
        state.label = tileLabel
        state.contentDescription = state.label
        if (icon == null) {
            icon = maybeLoadResourceIcon(controlsComponent.getTileImageId())
        }
        state.icon = icon
        if (controlsComponent.isEnabled() && hasControlsApps.get()) {
            if (controlsComponent.getVisibility() == AVAILABLE) {
                val selection =
                    controlsComponent.getControlsController().get().getPreferredSelection()
                state.state =
                    if (
                        selection is SelectedItem.StructureItem &&
                            selection.structure.controls.isEmpty()
                    ) {
                        Tile.STATE_INACTIVE
                    } else {
                        Tile.STATE_ACTIVE
                    }
                val label = selection.name
                state.secondaryLabel = if (label == tileLabel) null else label
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

    override fun handleLongClick(expandable: Expandable?) {}

    override fun getTileLabel(): CharSequence {
        return mContext.getText(controlsComponent.getTileTitleId())
    }

    companion object {
        const val TILE_SPEC = "controls"
    }
}
