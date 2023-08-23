/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.systemui.res.R;

/**
 * Layout used for displaying keyboard shortcut items inside an alert dialog.
 * The layout sets the maxWidth of shortcuts keyword textview to 70% of available space.
 */
public class KeyboardShortcutAppItemLayout extends RelativeLayout {

    private static final double MAX_WIDTH_PERCENT_FOR_KEYWORDS = 0.70;

    public KeyboardShortcutAppItemLayout(Context context) {
        super(context);
    }

    public KeyboardShortcutAppItemLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            ImageView shortcutIcon = findViewById(R.id.keyboard_shortcuts_icon);
            TextView shortcutKeyword = findViewById(R.id.keyboard_shortcuts_keyword);
            int totalMeasuredWidth = MeasureSpec.getSize(widthMeasureSpec);
            int totalPadding = getPaddingLeft() + getPaddingRight();
            int availableWidth = totalMeasuredWidth - totalPadding;
            if (shortcutIcon.getVisibility() == View.VISIBLE) {
                availableWidth = availableWidth - shortcutIcon.getMeasuredWidth();
            }
            shortcutKeyword.setMaxWidth((int)
                    Math.round(availableWidth * MAX_WIDTH_PERCENT_FOR_KEYWORDS));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
