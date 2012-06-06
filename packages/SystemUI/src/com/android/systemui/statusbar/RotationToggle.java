package com.android.systemui.statusbar;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.CompoundButton;

import com.android.systemui.statusbar.policy.AutoRotateController;

public class RotationToggle extends CompoundButton
        implements AutoRotateController.RotationLockCallbacks {
    private AutoRotateController mRotater;

    public RotationToggle(Context context) {
        super(context);
    }

    public RotationToggle(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RotationToggle(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mRotater = new AutoRotateController(getContext(), this, this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mRotater != null) {
            mRotater.release();
            mRotater = null;
        }
    }

    @Override
    public void setRotationLockControlVisibility(boolean show) {
        setVisibility(show ? VISIBLE : GONE);
    }
}
