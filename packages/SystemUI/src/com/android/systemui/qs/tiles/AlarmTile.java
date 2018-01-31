/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.tiles;

import static android.service.quicksettings.Tile.STATE_ACTIVE;
import static android.service.quicksettings.Tile.STATE_UNAVAILABLE;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.QS_ALARM;
import static com.android.systemui.keyguard.KeyguardSliceProvider.formatNextAlarm;

import android.app.AlarmManager.AlarmClockInfo;
import android.app.PendingIntent;
import android.content.Intent;
import android.provider.AlarmClock;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NextAlarmController.NextAlarmChangeCallback;

public class AlarmTile extends QSTileImpl implements NextAlarmChangeCallback {
    private final NextAlarmController mController;
    private String mNextAlarm;
    private PendingIntent mIntent;

    public AlarmTile(QSTileHost host) {
        super(host);
        mController = Dependency.get(NextAlarmController.class);
    }

    @Override
    public State newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        if (mIntent != null) {
            Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(mIntent);
        }
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.state = mNextAlarm != null ? STATE_ACTIVE : STATE_UNAVAILABLE;
        state.label = getTileLabel();
        state.secondaryLabel = mNextAlarm;
        state.icon = ResourceIcon.get(R.drawable.stat_sys_alarm);
        ((BooleanState) state).value = mNextAlarm != null;
    }

    @Override
    public void onNextAlarmChanged(AlarmClockInfo nextAlarm) {
        if (nextAlarm != null) {
            mNextAlarm = formatNextAlarm(mContext, nextAlarm);
            mIntent = nextAlarm.getShowIntent();
        } else {
            mNextAlarm = null;
            mIntent = null;
        }
        refreshState();
    }

    @Override
    public int getMetricsCategory() {
        return QS_ALARM;
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(AlarmClock.ACTION_SET_ALARM);
    }

    @Override
    protected void handleSetListening(boolean listening) {
        if (listening) {
            mController.addCallback(this);
        } else {
            mController.removeCallback(this);
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.status_bar_alarm);
    }
}