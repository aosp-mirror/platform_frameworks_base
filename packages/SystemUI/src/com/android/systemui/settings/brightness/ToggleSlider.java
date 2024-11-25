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

import android.annotation.StringRes;
import android.view.MotionEvent;

import com.android.settingslib.RestrictedLockUtils;

public interface ToggleSlider {
    interface Listener {
        void onChanged(boolean tracking, int value, boolean stopTracking);
    }

    void setEnforcedAdmin(RestrictedLockUtils.EnforcedAdmin admin);
    void setMirrorControllerAndMirror(MirrorController c);
    boolean mirrorTouchEvent(MotionEvent ev);

    void setOnChangedListener(Listener l);
    void setMax(int max);
    int getMax();
    void setValue(int value);
    int getValue();

    void showView();
    void hideView();
    void showToast(@StringRes int resId);
    boolean isVisible();
}
