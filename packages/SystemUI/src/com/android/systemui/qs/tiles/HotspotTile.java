/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

/** Quick settings tile: Hotspot **/
public class HotspotTile extends QSTile<QSTile.BooleanState> {
    private static final String KEY_LAST_USED_DATE = "lastUsedDate";
    private static final long MILLIS_PER_DAY = 1000 * 60 * 60 * 24;

    private final HotspotController mController;
    private final KeyguardMonitor mKeyguard;
    private final Callback mCallback = new Callback();
    private final long mTimeToShowTile;

    public HotspotTile(Host host) {
        super(host);
        mController = host.getHotspotController();
        mKeyguard = host.getKeyguardMonitor();

        mTimeToShowTile = MILLIS_PER_DAY
                * mContext.getResources().getInteger(R.integer.days_to_show_hotspot);
    }

    @Override
    protected BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addCallback(mCallback);
            mKeyguard.addCallback(mCallback);
        } else {
            mController.removeCallback(mCallback);
            mKeyguard.removeCallback(mCallback);
        }
    }

    @Override
    protected void handleClick() {
        final boolean isEnabled = (Boolean) mState.value;
        mController.setHotspotEnabled(!isEnabled);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.visible = !(mKeyguard.isSecure() && mKeyguard.isShowing())
                && mController.isHotspotSupported() && isHotspotRecentlyUsed();
        state.label = mContext.getString(R.string.quick_settings_hotspot_label);

        state.value = mController.isHotspotEnabled();
        state.iconId = state.visible && state.value ? R.drawable.ic_qs_hotspot_on
                : R.drawable.ic_qs_hotspot_off;
    }

    private boolean isHotspotRecentlyUsed() {
        long lastDay = getSharedPrefs(mContext).getLong(KEY_LAST_USED_DATE, 0);
        return (System.currentTimeMillis() - lastDay) < mTimeToShowTile;
    }

    private static SharedPreferences getSharedPrefs(Context context) {
        return context.getSharedPreferences(context.getPackageName(), 0);
    }

    private final class Callback implements HotspotController.Callback, KeyguardMonitor.Callback {
        @Override
        public void onHotspotChanged(boolean enabled) {
            refreshState();
        }

        @Override
        public void onKeyguardChanged() {
            refreshState();
        }
    };

    /**
     * This will catch broadcasts for changes in hotspot state so we can show
     * the hotspot tile for a number of days after use.
     */
    public static class APChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            long currentTime = System.currentTimeMillis();
            getSharedPrefs(context).edit().putLong(KEY_LAST_USED_DATE, currentTime).commit();
        }
    }
}
