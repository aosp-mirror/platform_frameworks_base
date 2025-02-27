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

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import com.android.internal.logging.MetricsLogger
import com.android.systemui.animation.Expandable
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
import com.android.systemui.qs.tiles.impl.notes.domain.NotesTileMapper
import com.android.systemui.qs.tiles.impl.notes.domain.interactor.NotesTileDataInteractor
import com.android.systemui.qs.tiles.impl.notes.domain.interactor.NotesTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.notes.domain.model.NotesTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfigProvider
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import javax.inject.Inject

/** Quick settings tile: Notes */
class NotesTile
@Inject
constructor(
    private val host: QSHost,
    private val uiEventLogger: QsEventLogger,
    @Background private val backgroundLooper: Looper,
    @Main private val mainHandler: Handler,
    private val falsingManager: FalsingManager,
    private val metricsLogger: MetricsLogger,
    private val statusBarStateController: StatusBarStateController,
    private val activityStarter: ActivityStarter,
    private val qsLogger: QSLogger,
    private val qsTileConfigProvider: QSTileConfigProvider,
    private val dataInteractor: NotesTileDataInteractor,
    private val tileMapper: NotesTileMapper,
    private val userActionInteractor: NotesTileUserActionInteractor,
) :
    QSTileImpl<QSTile.State?>(
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

    private lateinit var tileState: QSTileState
    private val config = qsTileConfigProvider.getConfig(TILE_SPEC)

    override fun getTileLabel(): CharSequence = mContext.getString(config.uiConfig.labelRes)

    override fun newTileState(): QSTile.State? {
        return QSTile.State().apply { state = Tile.STATE_INACTIVE }
    }

    override fun handleClick(expandable: Expandable?) {
        userActionInteractor.handleClick()
    }

    override fun getLongClickIntent(): Intent = userActionInteractor.longClickIntent

    override fun handleUpdateState(state: QSTile.State?, arg: Any?) {
        val model = if (arg is NotesTileModel) arg else dataInteractor.getCurrentTileModel()
        tileState = tileMapper.map(config, model)

        state?.apply {
            this.state = tileState.activationState.legacyState
            icon = maybeLoadResourceIcon(tileState.iconRes ?: R.drawable.ic_qs_notes)
            label = tileState.label
            contentDescription = tileState.contentDescription
            expandedAccessibilityClassName = tileState.expandedAccessibilityClassName
        }
    }

    override fun isAvailable(): Boolean {
        return dataInteractor.isAvailable()
    }

    companion object {
        const val TILE_SPEC = "notes"
    }
}
