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

package com.android.systemui.settings;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

import com.android.settingslib.RestrictedLockUtils;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.ActivityStarter;

public class ToggleSeekBar extends SeekBar {
    private String mAccessibilityLabel;

    private RestrictedLockUtils.EnforcedAdmin mEnforcedAdmin = null;

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
        if (mEnforcedAdmin != null) {
            Intent intent = RestrictedLockUtils.getShowAdminSupportDetailsIntent(
                    mContext, mEnforcedAdmin);
            Dependency.get(ActivityStarter.class).postStartActivityDismissingKeyguard(intent, 0);
            return true;
        }
        if (!isEnabled()) {
            setEnabled(true);
        }

        return super.onTouchEvent(event);
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

    public void setEnforcedAdmin(RestrictedLockUtils.EnforcedAdmin admin) {
        mEnforcedAdmin = admin;
    }
}
