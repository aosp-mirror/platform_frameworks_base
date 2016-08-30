package com.android.systemui;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;
import com.android.systemui.statusbar.policy.BatteryController;

public class BatteryLevelTextView extends TextView implements
        BatteryController.BatteryStateChangeCallback{
    private BatteryController mBatteryController;
    private boolean mShow;

    private ContentObserver mObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange, Uri uri) {
            loadShowBatteryTextSetting();
            setVisibility(mShow ? View.VISIBLE : View.GONE);
        }
    };

    public BatteryLevelTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        loadShowBatteryTextSetting();
        setVisibility(mShow ? View.VISIBLE : View.GONE);
    }

    private void loadShowBatteryTextSetting() {
        mShow = 0 != Settings.System.getInt(
            getContext().getContentResolver(), Settings.System.STATUS_BAR_SHOW_BATTERY_PERCENT, 0);
    }

    @Override
    public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        setText(getResources().getString(R.string.battery_level_template, level));
    }

    public void setBatteryController(BatteryController batteryController) {
        mBatteryController = batteryController;
        mBatteryController.addStateChangedCallback(this);
    }

    @Override
    public void onPowerSaveChanged() {
        // Not used
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
           Settings.System.STATUS_BAR_SHOW_BATTERY_PERCENT), false, mObserver);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mBatteryController != null) {
            mBatteryController.removeStateChangedCallback(this);
        }
    }
}
