/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.printspooler;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

public class PrintDialogFrame extends FrameLayout {

    public final int mMaxWidth;

    public PrintDialogFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        mMaxWidth = context.getResources().getDimensionPixelSize(
                R.dimen.print_dialog_frame_max_width_dip);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        if (widthMode == MeasureSpec.AT_MOST) {
            final int receivedWidth = MeasureSpec.getSize(widthMeasureSpec);
            final int computedWidth = Math.min(mMaxWidth, receivedWidth);
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(computedWidth,
                    MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
