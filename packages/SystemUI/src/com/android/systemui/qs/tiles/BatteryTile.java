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
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Checkable;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settingslib.BatteryInfo;
import com.android.settingslib.graph.UsageView;
import com.android.systemui.BatteryMeterDrawable;
import com.android.systemui.R;
import com.android.systemui.qs.QSIconView;
import com.android.systemui.qs.QSTile;
import com.android.systemui.statusbar.policy.BatteryController;

import java.text.NumberFormat;

public class BatteryTile extends QSTile<QSTile.State> implements BatteryController.BatteryStateChangeCallback {

    private final BatteryController mBatteryController;
    private final BatteryDetail mBatteryDetail = new BatteryDetail();

    private int mLevel;
    private boolean mPowerSave;
    private boolean mCharging;
    private boolean mDetailShown;
    private boolean mPluggedIn;
    private int mBatteryStyle;
    private int mBatteryStyleTile;

    public static final int BATTERY_STYLE_HIDDEN    = 4;
    public static final int BATTERY_STYLE_TEXT      = 6;

    public BatteryTile(Host host) {
        super(host);
        mBatteryController = host.getBatteryController();
        mBatteryStyle = Settings.Secure.getInt(host.getContext().getContentResolver(),
                Settings.Secure.STATUS_BAR_BATTERY_STYLE, 0);
        mBatteryStyleTile = Settings.Secure.getInt(host.getContext().getContentResolver(),
                Settings.Secure.STATUS_BAR_BATTERY_STYLE_TILE, 1);
        if (mBatteryStyle == BATTERY_STYLE_HIDDEN || mBatteryStyle == BATTERY_STYLE_TEXT || mBatteryStyleTile == 0) {
            mBatteryStyle = 0;
        }
    }

    @Override
    public QSIconView createTileView(Context context) {
        QSIconView view = new QSIconView(context);
        // The BatteryMeterDrawable wants to use the clear xfermode,
        // put it on its own layer to not make it clear the background with it.
        view.getIconView().setLayerType(View.LAYER_TYPE_HARDWARE, null);
        return view;
    }

    @Override
    public State newTileState() {
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
            mBatteryController.addStateChangedCallback(this);
        } else {
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
    public Intent getLongClickIntent() {
        return new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
    }

    @Override
    protected void handleClick() {
        showDetail(true);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.battery);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        int level = (arg != null) ? (Integer) arg : mLevel;
        String percentage = NumberFormat.getPercentInstance().format((double) level / 100.0);

        state.icon = new Icon() {
            @Override
            public Drawable getDrawable(Context context) {
                mBatteryStyle = Settings.Secure.getInt(context.getContentResolver(),
                        Settings.Secure.STATUS_BAR_BATTERY_STYLE, 0);
                mBatteryStyleTile = Settings.Secure.getInt(context.getContentResolver(),
                        Settings.Secure.STATUS_BAR_BATTERY_STYLE_TILE, 1);
                if (mBatteryStyle == BATTERY_STYLE_HIDDEN || mBatteryStyle == BATTERY_STYLE_TEXT || mBatteryStyleTile == 0) {
                    mBatteryStyle = 0;
                }
                BatteryMeterDrawable drawable =
                        new BatteryMeterDrawable(context, new Handler(Looper.getMainLooper()),
                        context.getColor(R.color.batterymeter_frame_color), mBatteryStyle, true);
                drawable.onBatteryLevelChanged(mLevel, mPluggedIn, mCharging);
                drawable.onPowerSaveChanged(mPowerSave);
                return drawable;
            }

            @Override
            public int getPadding() {
                return mHost.getContext().getResources().getDimensionPixelSize(
                        R.dimen.qs_battery_padding);
            }
        };
        state.label = percentage;
        state.contentDescription = mContext.getString(R.string.accessibility_quick_settings_battery,
                percentage) + "," +
                (mPowerSave ? mContext.getString(R.string.battery_saver_notification_title)
                        : mCharging ? mContext.getString(R.string.expanded_header_battery_charging)
                                : "")
                + "," + mContext.getString(R.string.accessibility_battery_details);
        state.minimalAccessibilityClassName = state.expandedAccessibilityClassName
                = Button.class.getName();
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        mLevel = level;
        mPluggedIn = pluggedIn;
        mCharging = charging;
        refreshState((Integer) level);
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
        private final BatteryMeterDrawable mDrawable = new BatteryMeterDrawable(mHost.getContext(),
                new Handler(), mHost.getContext().getColor(R.color.batterymeter_frame_color), mBatteryStyle, true);
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
            mDrawable.onBatteryLevelChanged(100, false, false);
            mDrawable.onPowerSaveChanged(true);
            mDrawable.disableShowPercent();
            ((ImageView) mCurrentView.findViewById(android.R.id.icon)).setImageDrawable(mDrawable);
            Checkable checkbox = (Checkable) mCurrentView.findViewById(android.R.id.toggle);
            checkbox.setChecked(mPowerSave);
            BatteryInfo.getBatteryInfo(mContext, new BatteryInfo.Callback() {
                @Override
                public void onBatteryInfoLoaded(BatteryInfo info) {
                    if (mCurrentView != null) {
                        bindBatteryInfo(info);
                    }
                }
            });
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

        private void bindBatteryInfo(BatteryInfo info) {
            SpannableStringBuilder builder = new SpannableStringBuilder();
            builder.append(info.batteryPercentString, new RelativeSizeSpan(2.6f),
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            if (info.remainingLabel != null) {
                if (mContext.getResources().getBoolean(R.bool.quick_settings_wide)) {
                    builder.append(' ');
                } else {
                    builder.append('\n');
                }
                builder.append(info.remainingLabel);
            }
            ((TextView) mCurrentView.findViewById(R.id.charge_and_estimation)).setText(builder);

            info.bindHistory((UsageView) mCurrentView.findViewById(R.id.battery_usage));
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
                        new IntentFilter(Intent.ACTION_TIME_TICK));
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
