package com.android.systemui.statusbar;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.CompoundButton;

import com.android.systemui.statusbar.policy.AutoRotateController;

public class RotationToggle extends CompoundButton {
    AutoRotateController mRotater;

    public RotationToggle(Context context) {
        super(context);
        mRotater = new AutoRotateController(context, this);
        setClickable(true);
    }

    public RotationToggle(Context context, AttributeSet attrs) {
        super(context, attrs);
        mRotater = new AutoRotateController(context, this);
        setClickable(true);
    }

    public RotationToggle(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mRotater = new AutoRotateController(context, this);
        setClickable(true);
    }

}
