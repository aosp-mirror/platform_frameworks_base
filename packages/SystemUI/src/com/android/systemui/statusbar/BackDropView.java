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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * A view who contains media artwork.
 */
public class BackDropView extends FrameLayout
{
    private Runnable mOnVisibilityChangedRunnable;

    public BackDropView(Context context) {
        super(context);
    }

    public BackDropView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public BackDropView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public BackDropView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && mOnVisibilityChangedRunnable != null) {
            mOnVisibilityChangedRunnable.run();
        }
    }

    public void setOnVisibilityChangedRunnable(Runnable runnable) {
        mOnVisibilityChangedRunnable = runnable;
    }

}
