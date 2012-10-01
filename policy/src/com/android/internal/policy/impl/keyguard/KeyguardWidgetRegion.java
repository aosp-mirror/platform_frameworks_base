/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.LinearLayout;

import com.android.internal.R;

public class KeyguardWidgetRegion extends LinearLayout {
    KeyguardGlowStripView mLeftStrip;
    KeyguardGlowStripView mRightStrip;
    KeyguardWidgetPager mPager;

    public KeyguardWidgetRegion(Context context) {
        this(context, null, 0);
    }

    public KeyguardWidgetRegion(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardWidgetRegion(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mLeftStrip = (KeyguardGlowStripView) findViewById(R.id.left_strip);
        mRightStrip = (KeyguardGlowStripView) findViewById(R.id.right_strip);
        mPager = (KeyguardWidgetPager) findViewById(R.id.app_widget_container);

        setSoundEffectsEnabled(false);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showPagingFeedback();
            }
        });
    }

    public void showPagingFeedback() {
        mLeftStrip.makeEmGo();
        mRightStrip.makeEmGo();
    }
}
