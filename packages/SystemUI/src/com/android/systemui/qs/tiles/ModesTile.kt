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
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.internal.logging.MetricsLogger
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.modes.shared.ModesUi
import com.android.systemui.modes.shared.ModesUiIcons
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.asQSTileIcon
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
import kotlinx.coroutines.runBlocking

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
    private val dataInteractor: ModesTileDataInteractor,
    private val tileMapper: ModesTileMapper,
    private val userActionInteractor: ModesTileUserActionInteractor,
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

    private lateinit var tileState: QSTileState
    private val config = qsTileConfigProvider.getConfig(TILE_SPEC)

    init {
        /* Check if */ ModesUiIcons.isUnexpectedlyInLegacyMode()

        lifecycle.coroutineScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dataInteractor.tileData().collect { refreshState(it) }
            }
        }
    }

    override fun isAvailable(): Boolean = ModesUi.isEnabled

    override fun getTileLabel(): CharSequence = tileState.label

    override fun newTileState(): QSTile.State {
        return QSTile.State().apply {
            label = mContext.getString(R.string.quick_settings_modes_label)
            icon = ResourceIcon.get(ICON_RES_ID)
            state = Tile.STATE_INACTIVE
        }
    }

    override fun handleClick(expandable: Expandable?) = runBlocking {
        userActionInteractor.handleClick(expandable)
    }

    override fun getLongClickIntent(): Intent = userActionInteractor.longClickIntent

    @VisibleForTesting
    public override fun handleUpdateState(state: QSTile.State?, arg: Any?) {
        // This runBlocking() will block @Background. Due to caches, it's expected to be fast.
        val model =
            if (arg is ModesTileModel) arg else runBlocking { dataInteractor.getCurrentTileModel() }

        tileState = tileMapper.map(config, model)
        state?.apply {
            this.state = tileState.activationState.legacyState
            val tileStateIcon = tileState.icon()
            icon = tileStateIcon?.asQSTileIcon() ?: ResourceIcon.get(ICON_RES_ID)
            label = tileLabel
            secondaryLabel = tileState.secondaryLabel
            contentDescription = tileState.contentDescription
            expandedAccessibilityClassName = tileState.expandedAccessibilityClassName
        }
    }

    companion object {
        const val TILE_SPEC = "dnd"
        @DrawableRes val ICON_RES_ID = com.android.internal.R.drawable.ic_zen_priority_modes
    }
}
