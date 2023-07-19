/*
 * Copyright (C) 2013 Slimroms
 * Copyright (C) 2018 The Dirty Unicorns Project
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

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.PowerManager;
import android.service.quicksettings.Tile;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

public class RebootTile extends SecureQSTile<BooleanState> {

    private int mRebootToRecovery = 0;

    @Inject
    public RebootTile(            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            KeyguardStateController keyguardStateController
    ) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger, keyguardStateController);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable View view, boolean keyguardShowing) {
        if (checkKeyguard(view, keyguardShowing)) {
            return;
        }
        switch (mRebootToRecovery) {
            default:
                mRebootToRecovery = 0; // Reboot
                break;
            case 0:
                mRebootToRecovery = 1; // Recovery
                break;
            case 1:
                mRebootToRecovery = 2; // Bootloader
                break;
            case 2:
                mRebootToRecovery = 3; // Power off
                break;
        }
        refreshState();
    }

    @Override
    protected void handleLongClick(@Nullable View view) {
        mHost.collapsePanels();
        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            public void run() {
                RebootToRecovery();
            }
        }, 500);
    }

    private void RebootToRecovery() {
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        switch (mRebootToRecovery) {
            case 0: // Reboot
                pm.reboot("");
                break;
            case 1: // Recovery
                pm.reboot(PowerManager.REBOOT_RECOVERY);
                break;
            case 2: // Bootloader
                pm.reboot(PowerManager.REBOOT_BOOTLOADER);
                break;
            case 3: // Power off
                pm.shutdown(false, pm.SHUTDOWN_USER_REQUESTED, false);
                break;
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_reboot_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.XTENDED;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.state = Tile.STATE_INACTIVE;
        switch (mRebootToRecovery) {
            case 0: // Reboot
                state.label = mContext.getString(R.string.quick_settings_reboot_label);
                state.icon = ResourceIcon.get(R.drawable.ic_qs_reboot);
                break;
            case 1: // Recovery
                state.label = mContext.getString(R.string.quick_settings_reboot_recovery_label);
                state.icon = ResourceIcon.get(R.drawable.ic_qs_reboot_recovery);
                break;
            case 2: // Bootloader
                state.label = mContext.getString(R.string.quick_settings_reboot_bootloader_label);
                state.icon = ResourceIcon.get(R.drawable.ic_qs_reboot_bootloader);
                break;
            case 3: // Power off
                state.label = mContext.getString(R.string.quick_settings_poweroff_label);
                state.icon = ResourceIcon.get(R.drawable.ic_qs_poweroff);
                break;
        }
    }

    @Override
    public void handleSetListening(boolean listening) {
    }
}
