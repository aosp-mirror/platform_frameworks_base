/*
 * Copyright (C) 2015 The CyanogenMod Project
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

import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import android.provider.Settings.Global;

import com.android.internal.logging.MetricsProto.MetricsEvent;

import com.android.systemui.qs.GlobalSetting;
import com.android.systemui.qs.QSTile;
import com.android.systemui.R;

/** Quick settings tile: Heads up **/
public class HeadsUpTile extends QSTile<QSTile.BooleanState> {

    private final GlobalSetting mSetting;

    public HeadsUpTile(Host host) {
        super(host);

        mSetting = new GlobalSetting(mContext, mHandler, Global.HEADS_UP_NOTIFICATIONS_ENABLED) {
            @Override
            protected void handleValueChanged(int value) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        setEnabled(!mState.value);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$HeadsUpSettingsActivity"));
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_heads_up_label);
    }

    private void setEnabled(boolean enabled) {
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                enabled ? 1 : 0);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer)arg : mSetting.getValue();
        final boolean headsUp = value != 0;
        state.value = headsUp;
        state.label = mContext.getString(R.string.quick_settings_heads_up_label);
        if (headsUp) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_heads_up_on);
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_heads_up_on);
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_heads_up_off);
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_heads_up_off);
        }
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(
                    R.string.accessibility_quick_settings_heads_up_changed_on);
        } else {
            return mContext.getString(
                    R.string.accessibility_quick_settings_heads_up_changed_off);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.NOTIFICATION_APP_NOTIFICATION;
    }

    @Override
    public void setListening(boolean listening) {
        // Do nothing
    }
}
