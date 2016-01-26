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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settingslib.BatteryInfo;
import com.android.systemui.BatteryMeterDrawable;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.BatteryController;

import java.text.NumberFormat;

public class BatteryTile extends QSTile<QSTile.State> implements BatteryController.BatteryStateChangeCallback {

    private final BatteryMeterDrawable mDrawable;
    private final BatteryController mBatteryController;
    private final BatteryDetail mBatteryDetail = new BatteryDetail();

    private int mLevel;
    private boolean mPowerSave;
    private boolean mCharging;

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
    public DetailAdapter getDetailAdapter() {
        return mBatteryDetail;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_BATTERY_TILE;
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
    public void setDetailListening(boolean listening) {
        super.setDetailListening(listening);
        if (!listening) {
            mBatteryDetail.mCurrentView = null;
        }
    }

    @Override
    protected void handleClick() {
        showDetail(true);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        int level = (arg != null) ? (Integer) arg : mLevel;
        String percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);

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
        mCharging = charging;
        refreshState((Integer) level);
        if (mBatteryDetail.mCurrentView != null) {
            mBatteryDetail.bindView();
        }
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        mPowerSave = isPowerSave;
        if (mBatteryDetail.mCurrentView != null) {
            mBatteryDetail.bindView();
        }
    }

    private final class BatteryDetail implements DetailAdapter, View.OnClickListener {
        private final BatteryMeterDrawable mDrawable = new BatteryMeterDrawable(mHost.getContext(),
                new Handler(), mHost.getContext().getColor(R.color.batterymeter_frame_color));
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
            bindView();
            return convertView;
        }

        private void bindView() {
            mDrawable.onBatteryLevelChanged(100, false, false);
            mDrawable.onPowerSaveChanged(true);
            ((ImageView) mCurrentView.findViewById(android.R.id.icon)).setImageDrawable(mDrawable);
            Checkable checkbox = (Checkable) mCurrentView.findViewById(android.R.id.toggle);
            checkbox.setChecked(mPowerSave);
            if (mCharging) {
                BatteryInfo.getBatteryInfo(mContext, new BatteryInfo.Callback() {
                    @Override
                    public void onBatteryInfoLoaded(BatteryInfo info) {
                        if (mCurrentView != null && mCharging) {
                            ((TextView) mCurrentView.findViewById(android.R.id.title)).setText(
                                    info.mChargeLabelString);
                        }
                    }
                });
                ((TextView) mCurrentView.findViewById(android.R.id.summary)).setText(
                        R.string.battery_detail_charging_summary);
                mCurrentView.setClickable(false);
                mCurrentView.findViewById(android.R.id.icon).setVisibility(View.INVISIBLE);
                mCurrentView.findViewById(android.R.id.toggle).setVisibility(View.INVISIBLE);
            } else {
                ((TextView) mCurrentView.findViewById(android.R.id.title)).setText(
                        R.string.battery_detail_switch_title);
                ((TextView) mCurrentView.findViewById(android.R.id.summary)).setText(
                        R.string.battery_detail_switch_summary);
                mCurrentView.setClickable(true);
                mCurrentView.findViewById(android.R.id.icon).setVisibility(View.VISIBLE);
                mCurrentView.findViewById(android.R.id.toggle).setVisibility(View.VISIBLE);
                mCurrentView.setOnClickListener(this);
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
    }
}
