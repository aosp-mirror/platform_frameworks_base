/*
 * Copyright (C) 2017 The ABC rom
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

import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.Settings;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.Prefs;
import com.android.systemui.qs.QSTile;
import com.android.systemui.R;

/** Quick settings tile: Ambient and LiftToWake mode **/
public class AmbientLiftToWakeTile extends QSTile<QSTile.BooleanState> {

    private boolean isSomethingEnabled() {
        int DozeValue = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_ENABLED, 1);
        int DozePickupValue = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_PULSE_ON_PICK_UP, 1);
        if (DozeValue == 1 || DozePickupValue == 1) {
            return true;
        }
        return false;
    }

    public AmbientLiftToWakeTile(Host host) {
        super(host);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        if (isSomethingEnabled()) {
            getUserDozeValue();
            getUserDozePickUpValue();
            setDisabled();
        } else {
            setUserValues();
        }
    }

    private void setDisabled() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_ENABLED, 0);
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_PULSE_ON_PICK_UP, 0);
    }

    private void setUserValues() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_ENABLED, Prefs.getInt(mContext, Prefs.Key.QS_AMBIENT_DOZE, 1));
        Settings.Secure.putInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_PULSE_ON_PICK_UP, Prefs.getInt(mContext, Prefs.Key.QS_AMBIENT_PICKUP, 1));
    }

    private void getUserDozeValue() {
        int getUserDozeValue = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_ENABLED, 1);
        Prefs.putInt(mContext, Prefs.Key.QS_AMBIENT_DOZE, getUserDozeValue);
    }

    private void getUserDozePickUpValue() {
        int getUserDozePickUpValue =  Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.DOZE_PULSE_ON_PICK_UP, 1);
        Prefs.putInt(mContext, Prefs.Key.QS_AMBIENT_PICKUP, getUserDozePickUpValue);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_DISPLAY_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_doze_notifications_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (isSomethingEnabled()) {
            getUserDozeValue();
            getUserDozePickUpValue();
            state.label = mContext.getString(R.string.quick_settings_doze_notifications_user);
            state.icon = ResourceIcon.get(R.drawable.ic_qs_ambient_on);
            state.contentDescription =  mContext.getString(
                    R.string.quick_settings_doze_notifications_user);
        } else {
            state.label = mContext.getString(R.string.quick_settings_doze_notifications_off);
            state.icon = ResourceIcon.get(R.drawable.ic_qs_ambient_off);
            state.contentDescription =  mContext.getString(
                    R.string.quick_settings_doze_notifications_off);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.FLASH;
    }

    private ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            //doing refreshState here when the user manually changes
            //one of the two options, so it will call the handleUpdateState
            //refreshing the tile and getting the new user values
            refreshState();
        }
    };

    @Override
    public void destroy() {
        mContext.getContentResolver().unregisterContentObserver(mObserver);
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DOZE_ENABLED),
                    false, mObserver);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DOZE_PULSE_ON_PICK_UP),
                    false, mObserver);
        } else {
            mContext.getContentResolver().unregisterContentObserver(mObserver);
        }
    }
}
