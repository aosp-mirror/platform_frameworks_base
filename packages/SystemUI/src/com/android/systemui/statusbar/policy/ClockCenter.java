/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.os.Handler;
import android.util.AttributeSet;
import android.view.View;

public class ClockCenter extends Clock {

    public ClockCenter(Context context) {
        this(context, null);
    }

    public ClockCenter(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ClockCenter(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    protected void updateClockVisibility() {
        if (mClockStyle == STYLE_CLOCK_CENTER && mShowClock) {
            setVisibility(View.VISIBLE);
        } else {
            setVisibility(View.GONE);
        }
    }
}
