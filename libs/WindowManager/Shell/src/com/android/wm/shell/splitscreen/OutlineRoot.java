/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.splitscreen;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.R;

/** Root layout for holding split outline. */
public class OutlineRoot extends FrameLayout {
    public OutlineRoot(@NonNull Context context) {
        super(context);
    }

    public OutlineRoot(@NonNull Context context,
            @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public OutlineRoot(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public OutlineRoot(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    private OutlineView mOutlineView;

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mOutlineView = findViewById(R.id.split_outline);
    }

    void updateOutlineBounds(Rect bounds, int color) {
        mOutlineView.updateOutlineBounds(bounds, color);
    }
}
