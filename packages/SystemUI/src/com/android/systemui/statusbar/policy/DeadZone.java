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

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.android.systemui.R;

public class DeadZone extends View {
    public DeadZone(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DeadZone(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);
    }

    // I made you a touch event
    @Override
    public boolean onTouchEvent (MotionEvent event) {
        return true; // but I eated it
    }
}

