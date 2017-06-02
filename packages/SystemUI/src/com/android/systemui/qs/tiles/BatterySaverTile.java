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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.quicksettings.Tile;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.graph.BatteryMeterDrawableBase;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.policy.BatteryController;

public class BatterySaverTile extends QSTileImpl<BooleanState> implements
        BatteryController.BatteryStateChangeCallback {

    private final BatteryController mBatteryController;
    private final BatteryDetail mBatteryDetail = new BatteryDetail();

    private int mLevel;
    private boolean mPowerSave;
    private boolean mCharging;
    private boolean mDetailShown;
    private boolean mPluggedIn;

    public BatterySaverTile(QSHost host) {
        super(host);
        mBatteryController = Dependency.get(BatteryController.class);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_BATTERY_TILE;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mBatteryController.addCallback(this);
        } else {
            mBatteryController.removeCallback(this);
        }
    }

    @Override
    public void setDetailListening(boolean listening) {
        super.setDetailListening(listening);
        if (!listening) {
            mBatteryDetail.mCurrentView = null;
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
    }

    @Override
    protected void handleClick() {
        mBatteryController.setPowerSaveMode(!mPowerSave);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.battery_detail_switch_title);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.state = mCharging ? Tile.STATE_UNAVAILABLE
                : mPowerSave ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.icon = ResourceIcon.get(R.drawable.ic_qs_battery_saver);
        state.label = mContext.getString(R.string.battery_detail_switch_title);
        state.contentDescription = state.label;
        state.value = mPowerSave;
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mLevel = level;
        mPluggedIn = pluggedIn;
        mCharging = charging;
        refreshState(level);
        if (mDetailShown) {
            mBatteryDetail.postBindView();
        }
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        mPowerSave = isPowerSave;
        refreshState(null);
        if (mDetailShown) {
            mBatteryDetail.postBindView();
        }
    }

    private final class BatteryDetail implements DetailAdapter, OnClickListener,
            OnAttachStateChangeListener {
        private final BatteryMeterDrawableBase mDrawable
                = new BatteryMeterDrawableBase(
                        mHost.getContext(),
                        mHost.getContext().getColor(R.color.meter_background_color));
        private View mCurrentView;

        @Override
        public CharSequence getTitle() {
            return mContext.getString(R.string.battery_panel_title, mLevel);
        }

        @Override
        public Boolean getToggleState() {
            return null;
        }

        @Override
        public View createDetailView(Context context, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(mContext).inflate(R.layout.battery_detail, parent,
                        false);
            }
            mCurrentView = convertView;
            mCurrentView.addOnAttachStateChangeListener(this);
            bindView();
            return convertView;
        }

        private void postBindView() {
            if (mCurrentView == null) return;
            mCurrentView.post(new Runnable() {
                @Override
                public void run() {
                    bindView();
                }
            });
        }

        private void bindView() {
            if (mCurrentView == null) {
                return;
            }
            mDrawable.setBatteryLevel(100);
            mDrawable.setCharging(false);
            mDrawable.setPowerSave(true);
            mDrawable.setShowPercent(false);
            ((ImageView) mCurrentView.findViewById(android.R.id.icon)).setImageDrawable(mDrawable);
            Checkable checkbox = (Checkable) mCurrentView.findViewById(android.R.id.toggle);
            checkbox.setChecked(mPowerSave);
            final TextView batterySaverTitle =
                    (TextView) mCurrentView.findViewById(android.R.id.title);
            final TextView batterySaverSummary =
                    (TextView) mCurrentView.findViewById(android.R.id.summary);
            if (mCharging) {
                mCurrentView.findViewById(R.id.switch_container).setAlpha(.7f);
                batterySaverTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                batterySaverTitle.setText(R.string.battery_detail_charging_summary);
                mCurrentView.findViewById(android.R.id.toggle).setVisibility(View.GONE);
                mCurrentView.findViewById(R.id.switch_container).setClickable(false);
            } else {
                mCurrentView.findViewById(R.id.switch_container).setAlpha(1);
                batterySaverTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                batterySaverTitle.setText(R.string.battery_detail_switch_title);
                batterySaverSummary.setText(R.string.battery_detail_switch_summary);
                mCurrentView.findViewById(android.R.id.toggle).setVisibility(View.VISIBLE);
                mCurrentView.findViewById(R.id.switch_container).setClickable(true);
                mCurrentView.findViewById(R.id.switch_container).setOnClickListener(this);
            }
        }

        @Override
        public void onClick(View v) {
            mBatteryController.setPowerSaveMode(!mPowerSave);
        }

        @Override
        public Intent getSettingsIntent() {
            return new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
        }

        @Override
        public void setToggleState(boolean state) {
            // No toggle state.
        }

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.QS_BATTERY_DETAIL;
        }

        @Override
        public void onViewAttachedToWindow(View v) {
            if (!mDetailShown) {
                mDetailShown = true;
                v.getContext().registerReceiver(mReceiver,
                        new IntentFilter(Intent.ACTION_TIME_TICK), null,
                        Dependency.get(Dependency.TIME_TICK_HANDLER));
            }
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            if (mDetailShown) {
                mDetailShown = false;
                v.getContext().unregisterReceiver(mReceiver);
            }
        }

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                postBindView();
            }
        };
    }
}