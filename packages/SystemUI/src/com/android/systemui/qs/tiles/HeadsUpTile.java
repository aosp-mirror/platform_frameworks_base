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

import com.android.internal.logging.MetricsProto.MetricsEvent;

import com.android.systemui.qs.QSTile;
import com.android.systemui.R;

/** Quick settings tile: Heads up **/
public class HeadsUpTile extends QSTile<QSTile.BooleanState> {

    public HeadsUpTile(Host host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick() {
        setEnabled();
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

    private boolean getUserHeadsUpState() {
         return Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.HEADS_UP_USER_ENABLED,
                Settings.System.HEADS_UP_USER_ON) != 0;
    }

    private void setEnabled() {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.HEADS_UP_USER_ENABLED,
                getUserHeadsUpState() ? 0 : 1);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = getUserHeadsUpState();
        state.label = mContext.getString(R.string.quick_settings_heads_up_label);
        if (getUserHeadsUpState()) {
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
