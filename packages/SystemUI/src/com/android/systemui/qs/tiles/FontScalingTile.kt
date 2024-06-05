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
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.MetricsLogger
import com.android.systemui.accessibility.fontscaling.FontScalingDialogDelegate
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
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
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import javax.inject.Provider

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
    private val keyguardStateController: KeyguardStateController,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val fontScalingDialogDelegateProvider: Provider<FontScalingDialogDelegate>
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

    override fun newTileState(): QSTile.State {
        return QSTile.State()
    }

    override fun handleClick(expandable: Expandable?) {
        // We animate from the touched view only if we are not on the keyguard
        val animateFromExpandable: Boolean =
            expandable != null && !keyguardStateController.isShowing

        val runnable = Runnable {
            val dialog: SystemUIDialog = fontScalingDialogDelegateProvider.get().createDialog()
            if (animateFromExpandable) {
                val controller =
                    expandable?.dialogTransitionController(
                        DialogCuj(
                            InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                            INTERACTION_JANK_TAG
                        )
                    )
                controller?.let { dialogTransitionAnimator.show(dialog, controller) }
                    ?: dialog.show()
            } else {
                dialog.show()
            }
        }

        mainHandler.post {
            mActivityStarter.executeRunnableDismissingKeyguard(
                runnable,
                /* cancelAction= */ null,
                /* dismissShade= */ true,
                /* afterKeyguardGone= */ true,
                /* deferred= */ false
            )
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
