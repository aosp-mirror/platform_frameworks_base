/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.graphics.drawable.Drawable;
import android.os.Handler;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.BatteryMeterDrawable;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.BatteryController;

import java.text.NumberFormat;

public class BatteryTile extends QSTile<QSTile.State> implements BatteryController.BatteryStateChangeCallback {

    private final BatteryMeterDrawable mDrawable;
    private final BatteryController mBatteryController;

    private int mLevel;

    public BatteryTile(Host host) {
        super(host);
        mBatteryController = host.getBatteryController();
        mDrawable = new BatteryMeterDrawable(host.getContext(), new Handler(),
                host.getContext().getColor(R.color.batterymeter_frame_color));
        mDrawable.setBatteryController(mBatteryController);
    }

    @Override
    protected State newTileState() {
        return new QSTile.State();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.QS_BATTERY_TILE;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mDrawable.startListening();
            mBatteryController.addStateChangedCallback(this);
        } else {
            mDrawable.stopListening();
            mBatteryController.removeStateChangedCallback(this);
        }
    }

    @Override
    protected void handleClick() {
        mHost.startActivityDismissingKeyguard(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY));
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        int level = (arg != null) ? (Integer) arg : mLevel;
        String percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);

        state.visible = true;
        state.icon = new Icon() {
            @Override
            public Drawable getDrawable(Context context) {
                return mDrawable;
            }
        };
        state.label = percentage;
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mLevel = level;
        refreshState((Integer) level);
    }

    @Override
    public void onPowerSaveChanged() {

    }
}
