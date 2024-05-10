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

package com.android.internal.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

/** ScrollView that includes scaling and fading animations */
public class FadingWearableScrollView extends ScrollView {
    private ViewGroupFader mFader;

    public FadingWearableScrollView(Context context) {
        this(context, null);
    }

    public FadingWearableScrollView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.scrollViewStyle);
    }

    public FadingWearableScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public FadingWearableScrollView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        mFader = createFader(this);
    }

    /**
     * Creates a simple ViewGroupFader that animates views from both top and bottom.
     */
    private ViewGroupFader createFader(ViewGroup container) {
        return new ViewGroupFader(
                container,
                new ViewGroupFader.AnimationCallback() {
                    @Override
                    public boolean shouldFadeFromTop(View view) {
                        return true;
                    }

                    @Override
                    public boolean shouldFadeFromBottom(View view) {
                        return true;
                    }

                    @Override
                    public void viewHasBecomeFullSize(View view) {
                    }
                },
                new ViewGroupFader.GlobalVisibleViewBoundsProvider());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mFader.updateFade();
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        mFader.updateFade();
    }
}
