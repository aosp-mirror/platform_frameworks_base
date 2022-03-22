/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.dialog;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.SeekBar;

/**
 * Customized seekbar used by MediaOutputDialog, which only changes progress when dragging,
 * otherwise performs click.
 */
public class MediaOutputSeekbar extends SeekBar {
    private static final int DRAGGING_THRESHOLD = 20;
    private boolean mIsDragging = false;

    public MediaOutputSeekbar(Context context) {
        super(context);
    }

    public MediaOutputSeekbar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int width = getWidth()
                - getPaddingLeft()
                - getPaddingRight();
        int thumbPos = getPaddingLeft()
                + width
                * getProgress()
                / getMax();
        if (event.getAction() == MotionEvent.ACTION_DOWN
                && Math.abs(event.getX() - thumbPos) < DRAGGING_THRESHOLD) {
            mIsDragging = true;
            super.onTouchEvent(event);
        } else if (event.getAction() == MotionEvent.ACTION_MOVE && mIsDragging) {
            super.onTouchEvent(event);
        } else if (event.getAction() == MotionEvent.ACTION_UP && mIsDragging) {
            mIsDragging = false;
            super.onTouchEvent(event);
        } else if (event.getAction() == MotionEvent.ACTION_UP && !mIsDragging) {
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
