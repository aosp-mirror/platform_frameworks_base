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
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.MetricsLogger
import com.android.systemui.R
import com.android.systemui.accessibility.fontscaling.FontScalingDialog
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SystemSettings
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject

class FontScalingTile
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
    private val dialogLaunchAnimator: DialogLaunchAnimator,
    private val systemSettings: SystemSettings,
    private val secureSettings: SecureSettings,
    private val systemClock: SystemClock,
    private val featureFlags: FeatureFlags,
    @Background private val backgroundDelayableExecutor: DelayableExecutor
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
        qsLogger
    ) {
    private val icon = ResourceIcon.get(R.drawable.ic_qs_font_scaling)

    override fun isAvailable(): Boolean {
        return featureFlags.isEnabled(Flags.ENABLE_FONT_SCALING_TILE)
    }

    override fun newTileState(): QSTile.State {
        return QSTile.State()
    }

    override fun handleClick(view: View?) {
        mUiHandler.post {
            val dialog: SystemUIDialog =
                FontScalingDialog(
                    mContext,
                    systemSettings,
                    secureSettings,
                    systemClock,
                    mainHandler,
                    backgroundDelayableExecutor
                )
            if (view != null) {
                dialogLaunchAnimator.showFromView(
                    dialog,
                    view,
                    DialogCuj(InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, INTERACTION_JANK_TAG)
                )
            } else {
                dialog.show()
            }
        }
    }

    override fun handleUpdateState(state: QSTile.State?, arg: Any?) {
        state?.label = mContext.getString(R.string.quick_settings_font_scaling_label)
        state?.icon = icon
    }

    override fun getLongClickIntent(): Intent? {
        return Intent(Settings.ACTION_TEXT_READING_SETTINGS)
    }

    override fun getTileLabel(): CharSequence {
        return mContext.getString(R.string.quick_settings_font_scaling_label)
    }

    companion object {
        const val TILE_SPEC = "font_scaling"
        private const val INTERACTION_JANK_TAG = "font_scaling"
    }
}
