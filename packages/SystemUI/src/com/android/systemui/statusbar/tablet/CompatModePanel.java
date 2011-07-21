/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.android.systemui.R;

public class CompatModePanel extends FrameLayout implements StatusBarPanel,
        View.OnClickListener {
    private static final boolean DEBUG = TabletStatusBar.DEBUG;
    private static final String TAG = "CompatModePanel";

    private ActivityManager mAM;

    private boolean mAttached = false;
    private Context mContext;
    private RadioButton mOnButton, mOffButton;

    private View mTrigger;
//    private InputMethodButton mInputMethodSwitchButton;

    public CompatModePanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        mAM = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    }

    @Override
    public void onFinishInflate() {
        mOnButton  = (RadioButton) findViewById(R.id.compat_mode_on_radio);
        mOffButton = (RadioButton) findViewById(R.id.compat_mode_off_radio);
        mOnButton.setOnClickListener(this);
        mOffButton.setOnClickListener(this);

        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mAttached = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
        }
    }

    @Override
    public void onClick(View v) {
        if (v == mOnButton) {
            mAM.setFrontActivityScreenCompatMode(ActivityManager.COMPAT_MODE_ENABLED);
        } else if (v == mOffButton) {
            mAM.setFrontActivityScreenCompatMode(ActivityManager.COMPAT_MODE_DISABLED);
        }
    }

    @Override
    public boolean isInContentArea(int x, int y) {
        return false;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // Ignore hover events outside of this panel bounds since such events
        // generate spurious accessibility events with the panel content when
        // tapping outside of it, thus confusing the user.
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        if (x >= 0 && x < getWidth() && y >= 0 && y < getHeight()) {
            return super.dispatchHoverEvent(event);
        }
        return true;
    }

    public void setTrigger(View v) {
        mTrigger = v;
    }

    public void openPanel() {
        setVisibility(View.VISIBLE);
        if (mTrigger != null) mTrigger.setSelected(true);
        refresh();
    }

    public void closePanel() {
        setVisibility(View.GONE);
        if (mTrigger != null) mTrigger.setSelected(false);
    }

    private void refresh() {
        int mode = mAM.getFrontActivityScreenCompatMode();
        if (mode == ActivityManager.COMPAT_MODE_ALWAYS
                || mode == ActivityManager.COMPAT_MODE_NEVER) {
            // No longer have something to switch.
            closePanel();
            return;
        }
        final boolean on = (mode == ActivityManager.COMPAT_MODE_ENABLED);
        mOnButton.setChecked(on);
        mOffButton.setChecked(!on);
    }
}
