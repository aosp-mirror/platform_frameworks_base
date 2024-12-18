/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.settings.brightness;

import static com.android.systemui.Flags.brightnessSliderFocusState;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

import com.android.systemui.res.R;

public class ToggleSeekBar extends SeekBar {
    private String mAccessibilityLabel;

    private AdminBlocker mAdminBlocker;

    public ToggleSeekBar(Context context) {
        super(context);
    }

    public ToggleSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ToggleSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mAdminBlocker != null && mAdminBlocker.block()) {
            return true;
        }
        if (!isEnabled()) {
            setEnabled(true);
        }

        return super.onTouchEvent(event);
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
            setHovered(true);
        } else if (event.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
            setHovered(false);
        }
        return true;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (brightnessSliderFocusState()) {
            setBackground(mContext.getDrawable(R.drawable.brightness_slider_focus_bg));
        }
    }

    public void setAccessibilityLabel(String label) {
        mAccessibilityLabel = label;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (mAccessibilityLabel != null) {
            info.setText(mAccessibilityLabel);
        }
    }

    void setAdminBlocker(AdminBlocker blocker) {
        mAdminBlocker = blocker;
        setEnabled(blocker == null);
    }

    interface AdminBlocker {
        boolean block();
    }
}
