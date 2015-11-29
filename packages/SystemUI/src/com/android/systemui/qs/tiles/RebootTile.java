/*
 * Copyright (C) 2013 Slimroms
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
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.internal.logging.MetricsProto.MetricsEvent;

public class RebootTile extends QSTile<QSTile.BooleanState> {

    private boolean mRebootToRecovery = false;

    public RebootTile(Host host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        mRebootToRecovery = !mRebootToRecovery;
        refreshState();
    }

    @Override
    protected void handleLongClick() {
        mHost.collapsePanels();
        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            public void run() {
                PowerManager pm =
                    (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                pm.reboot(mRebootToRecovery ? "recovery" : "");
            }
        }, 500);
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
        return MetricsEvent.DISPLAY;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mRebootToRecovery) {
            state.label = mContext.getString(R.string.quick_settings_reboot_recovery_label);
            state.icon = ResourceIcon.get(R.drawable.ic_qs_reboot_recovery);
        } else {
            state.label = mContext.getString(R.string.quick_settings_reboot_label);
            state.icon = ResourceIcon.get(R.drawable.ic_qs_reboot);
        }
    }

    @Override
    public void setListening(boolean listening) {
    }
}
