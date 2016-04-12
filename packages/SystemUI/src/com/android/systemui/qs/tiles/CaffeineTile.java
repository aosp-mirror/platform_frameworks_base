/*
 * Copyright (C) 2016 The CyanogenMod Project
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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.qs.QSTile;
import com.android.systemui.R;

/** Quick settings tile: Caffeine **/
public class CaffeineTile extends QSTile<QSTile.BooleanState> {

    private final PowerManager.WakeLock mWakeLock;
    private final Receiver mReceiver = new Receiver();

    public CaffeineTile(Host host) {
        super(host);
        mWakeLock = ((PowerManager) mContext.getSystemService(Context.POWER_SERVICE)).newWakeLock(
                PowerManager.FULL_WAKE_LOCK, "CaffeineTile");
        mReceiver.init();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mReceiver.destroy();
      if (mWakeLock.isHeld()) {
            mWakeLock.release();
      }
    }

    @Override
    public void setListening(boolean listening) {
    }

    @Override
    public void handleClick() {
        // toggle
        if (mWakeLock.isHeld()) {
            mWakeLock.release();
        } else {
            mWakeLock.acquire();
        }
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$DisplaySettingsActivity"));
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_caffeine_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.DISPLAY;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.value = mWakeLock.isHeld();
        state.label = mContext.getString(R.string.quick_settings_caffeine_label);
        if (state.value) {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_caffeine_on);
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_caffeine_on);
        } else {
            state.icon = ResourceIcon.get(R.drawable.ic_qs_caffeine_off);
            state.contentDescription =  mContext.getString(
                    R.string.accessibility_quick_settings_caffeine_off);
        }
    }

    private final class Receiver extends BroadcastReceiver {
        public void init() {
            // Register for Intent broadcasts for...
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            mContext.registerReceiver(this, filter, null, mHandler);
        }

        public void destroy() {
            mContext.unregisterReceiver(this);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // disable caffeine if user force off (power button)
                if (mWakeLock.isHeld())
                    mWakeLock.release();
                refreshState();
            }
        }
    }
}
