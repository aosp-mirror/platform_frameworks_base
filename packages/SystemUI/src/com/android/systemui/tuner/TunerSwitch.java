package com.android.systemui.tuner;

import android.content.Context;
import android.content.res.TypedArray;
import android.provider.Settings;
import android.util.AttributeSet;

import androidx.preference.SwitchPreference;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.tuner.TunerService.Tunable;

public class TunerSwitch extends SwitchPreference implements Tunable {

    private final boolean mDefault;
    private final int mAction;

    public TunerSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TunerSwitch);
        mDefault = a.getBoolean(R.styleable.TunerSwitch_defValue, false);
        mAction = a.getInt(R.styleable.TunerSwitch_metricsAction, -1);
        a.recycle();
    }

    @Override
    public void onAttached() {
        super.onAttached();
        Dependency.get(TunerService.class).addTunable(this, getKey().split(","));
    }

    @Override
    public void onDetached() {
        Dependency.get(TunerService.class).removeTunable(this);
        super.onDetached();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        setChecked(TunerService.parseIntegerSwitch(newValue, mDefault));
    }

    @Override
    protected void onClick() {
        super.onClick();
        if (mAction != -1) {
            MetricsLogger.action(getContext(), mAction, isChecked());
        }
    }

    @Override
    protected boolean persistBoolean(boolean value) {
        for (String key : getKey().split(",")) {
            Settings.Secure.putString(getContext().getContentResolver(), key, value ? "1" : "0");
        }
        return true;
    }

}
