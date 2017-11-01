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

package com.android.systemui.volume;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * Specialized layout for zen mode that allows the radio buttons to reside within
 * a RadioGroup, but also makes sure that all the heights off the radio buttons align
 * with the corresponding content in the second child of this view.
 */
public class ZenRadioLayout extends LinearLayout {

    public ZenRadioLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Run 2 measurement passes, 1 that figures out the size of the content, and another
     * that sets the size of the radio buttons to the heights of the corresponding content.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        ViewGroup radioGroup = (ViewGroup) getChildAt(0);
        ViewGroup radioContent = (ViewGroup) getChildAt(1);
        int size = radioGroup.getChildCount();
        if (size != radioContent.getChildCount()) {
            throw new IllegalStateException("Expected matching children");
        }
        boolean hasChanges = false;
        for (int i = 0; i < size; i++) {
            View radio = radioGroup.getChildAt(i);
            View content = radioContent.getChildAt(i);
            if (radio.getLayoutParams().height != content.getMeasuredHeight()) {
                hasChanges = true;
                radio.getLayoutParams().height = content.getMeasuredHeight();
            }
        }
        // Measure again if any heights changed.
        if (hasChanges) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
