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

package com.android.systemui.qs.tiles

import android.app.Flags
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.internal.logging.MetricsLogger
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile.BooleanState
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.impl.modes.domain.interactor.ModesTileDataInteractor
import com.android.systemui.qs.tiles.impl.modes.domain.interactor.ModesTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.qs.tiles.impl.modes.ui.ModesTileMapper
import com.android.systemui.qs.tiles.viewmodel.QSTileConfigProvider
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.launch

class ModesTile
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
    qsTileConfigProvider: QSTileConfigProvider,
    dataInteractor: ModesTileDataInteractor,
    private val tileMapper: ModesTileMapper,
    private val userActionInteractor: ModesTileUserActionInteractor,
) :
    QSTileImpl<BooleanState>(
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

    private lateinit var tileState: QSTileState
    private val config = qsTileConfigProvider.getConfig(TILE_SPEC)

    init {
        lifecycle.coroutineScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dataInteractor.tileData().collect { refreshState(it) }
            }
        }
    }

    override fun isAvailable(): Boolean = Flags.modesUi()

    override fun getTileLabel(): CharSequence = tileState.label

    override fun newTileState() = BooleanState()

    override fun handleClick(expandable: Expandable?) {
        // TODO(b/346519570) open dialog
    }

    override fun getLongClickIntent(): Intent = userActionInteractor.longClickIntent

    override fun handleUpdateState(booleanState: BooleanState?, arg: Any?) {
        if (arg is ModesTileModel) {
            tileState = tileMapper.map(config, arg)

            booleanState?.apply {
                state = tileState.activationState.legacyState
                icon = ResourceIcon.get(tileState.iconRes ?: R.drawable.qs_dnd_icon_off)
                label = tileLabel
                secondaryLabel = tileState.secondaryLabel
                contentDescription = tileState.contentDescription
            }
        }
    }

    companion object {
        const val TILE_SPEC = "modes"
    }
}
