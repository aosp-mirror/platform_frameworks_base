/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;

public class SettingsPanelView extends PanelView {
    public SettingsPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void fling(float vel, boolean always) {
        ((PhoneStatusBarView) mBar).mBar.getGestureRecorder().tag(
            "fling " + ((vel > 0) ? "open" : "closed"),
            "settings,v=" + vel);
        super.fling(vel, always);
    }
}
