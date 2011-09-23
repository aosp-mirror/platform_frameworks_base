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

package com.android.systemui.recent;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

public class StatusBarTouchProxy extends FrameLayout {

    private View mStatusBar;

    public StatusBarTouchProxy(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setStatusBar(View statusBar) {
        mStatusBar = statusBar;
    }

    public boolean onTouchEvent (MotionEvent event) {
        return mStatusBar.dispatchTouchEvent(event);
    }
}
