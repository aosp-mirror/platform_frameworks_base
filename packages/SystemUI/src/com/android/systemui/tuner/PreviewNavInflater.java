/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.tuner;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import com.android.systemui.Dependency;
import com.android.systemui.statusbar.phone.NavigationBarInflaterView;

public class PreviewNavInflater extends NavigationBarInflaterView {

    public PreviewNavInflater(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Immediately remove tuner listening, since this is a preview, all values will be injected
        // manually.
        Dependency.get(TunerService.class).removeTunable(this);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Only a preview, not interactable.
        return true;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (NAV_BAR_VIEWS.equals(key)) {
            // Since this is a preview we might get a bunch of random stuff, validate before sending
            // for inflation.
            if (isValidLayout(newValue)) {
                super.onTuningChanged(key, newValue);
            }
        } else {
            super.onTuningChanged(key, newValue);
        }
    }

    private boolean isValidLayout(String newValue) {
        if (newValue == null) {
            return true;
        }
        int separatorCount = 0;
        int lastGravitySeparator = 0;
        for (int i = 0; i < newValue.length(); i++) {
            if (newValue.charAt(i) == GRAVITY_SEPARATOR.charAt(0)) {
                if (i == 0 || (i - lastGravitySeparator) == 1) {
                    return false;
                }
                lastGravitySeparator = i;
                separatorCount++;
            }
        }
        return separatorCount == 2 && (newValue.length() - lastGravitySeparator) != 1;
    }
}
