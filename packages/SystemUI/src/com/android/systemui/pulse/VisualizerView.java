/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.pulse;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public class VisualizerView extends FrameLayout {

    private boolean mAttached;

    public VisualizerView(@NonNull Context context) {
        super(context);
    }

    public VisualizerView(@NonNull Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VisualizerView(@NonNull Context context, @Nullable AttributeSet attrs,
            @AttrRes int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void onAttachedToWindow() {
        mAttached = true;
        super.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        mAttached = false;
        super.onDetachedFromWindow();
    }

    public boolean isAttached() {
        return mAttached;
    }
}
