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
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.GlobalSetting;
import com.android.systemui.qs.QSTile;

/** Quick settings tile: Airplane mode **/
public class AirplaneModeTile extends QSTile<QSTile.BooleanState> {
    private final AnimationIcon mEnable =
            new AnimationIcon(R.drawable.ic_signal_airplane_enable_animation,
                    R.drawable.ic_signal_airplane_disable);
    private final AnimationIcon mDisable =
            new AnimationIcon(R.drawable.ic_signal_airplane_disable_animation,
                    R.drawable.ic_signal_airplane_enable);
    private final GlobalSetting mSetting;

    private boolean mListening;

    public AirplaneModeTile(Host host) {
        super(host);

        mSetting = new GlobalSetting(mContext, mHandler, Global.AIRPLANE_MODE_ON) {
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
        MetricsLogger.action(mContext, getMetricsCategory(), !mState.value);
        setEnabled(!mState.value);
    }

    private void setEnabled(boolean enabled) {
        final ConnectivityManager mgr =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mgr.setAirplaneMode(enabled);
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.airplane_mode);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer)arg : mSetting.getValue();
        final boolean airplaneMode = value != 0;
        state.value = airplaneMode;
        state.label = mContext.getString(R.string.airplane_mode);
        if (airplaneMode) {
            state.icon = mEnable;
        } else {
            state.icon = mDisable;
        }
        state.contentDescription = state.label;
        state.minimalAccessibilityClassName = state.expandedAccessibilityClassName
                = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_AIRPLANEMODE;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_airplane_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_airplane_changed_off);
        }
    }

    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            mContext.registerReceiver(mReceiver, filter);
        } else {
            mContext.unregisterReceiver(mReceiver);
        }
        mSetting.setListening(listening);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction())) {
                refreshState();
            }
        }
    };
}
