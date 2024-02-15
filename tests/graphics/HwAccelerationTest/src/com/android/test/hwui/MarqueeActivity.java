/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.test.hwui;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;

@SuppressWarnings({"UnusedDeclaration"})
public class MarqueeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        
        final TextView text1 = new TextView(this);
        text1.setText("This is a marquee inside a TextView");
        text1.setSingleLine(true);
        text1.setHorizontalFadingEdgeEnabled(true);
        text1.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        linearLayout.addView(text1, new LinearLayout.LayoutParams(
                100, LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView text2 = new TextView(this);
        text2.setText("This is a marquee inside a TextView");
        text2.setSingleLine(true);
        text2.setHorizontalFadingEdgeEnabled(true);
        text2.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                100, LinearLayout.LayoutParams.WRAP_CONTENT);
        linearLayout.addView(text2, params);

        setContentView(linearLayout);
        
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                text2.setVisibility(View.INVISIBLE);
                Animation animation = AnimationUtils.loadAnimation(text2.getContext(),
                        R.anim.slide_off_left);
                animation.setFillEnabled(true);
                animation.setFillAfter(true);
                text2.startAnimation(animation);
            }
        }, 1000);
    }
}
