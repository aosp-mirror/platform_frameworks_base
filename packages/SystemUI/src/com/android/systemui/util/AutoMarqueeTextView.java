/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.util;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;

/**
 * TextView that changes its ellipsize value with its visibility.
 *
 * The View responds to changes in user-visibility to change its ellipsize from MARQUEE to END
 * and back. Useful for TextView that need to marquee forever.
 */
public class AutoMarqueeTextView extends SafeMarqueeTextView {

    private boolean mAggregatedVisible = false;

    public AutoMarqueeTextView(Context context) {
        super(context);
    }

    public AutoMarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AutoMarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public AutoMarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        onVisibilityAggregated(isVisibleToUser());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setSelected(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        setSelected(false);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible == mAggregatedVisible) return;

        mAggregatedVisible = isVisible;
        if (mAggregatedVisible) {
            setEllipsize(TextUtils.TruncateAt.MARQUEE);
        } else {
            setEllipsize(TextUtils.TruncateAt.END);
        }
    }
}
