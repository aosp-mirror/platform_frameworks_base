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

package com.android.internal.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.AllCapsTransformationMethod;
import android.text.method.TransformationMethod;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class PlatLogoActivity extends Activity {
    FrameLayout mContent;
    int mCount;
    final Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
        Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);

        mContent = new FrameLayout(this);
        
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;

        final ImageView logo = new ImageView(this);
        logo.setImageResource(com.android.internal.R.drawable.platlogo);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logo.setVisibility(View.INVISIBLE);

        final TextView letter = new TextView(this);

        letter.setTypeface(bold);
        letter.setTextSize(300);
        letter.setTextColor(0xFFFFFFFF);
        letter.setGravity(Gravity.CENTER);
        letter.setText(String.valueOf(Build.VERSION.RELEASE).substring(0, 1));

        final int p = (int)(4 * metrics.density);

        final TextView tv = new TextView(this);
        if (light != null) tv.setTypeface(light);
        tv.setTextSize(30);
        tv.setPadding(p, p, p, p);
        tv.setTextColor(0xFFFFFFFF);
        tv.setGravity(Gravity.CENTER);
        tv.setTransformationMethod(new AllCapsTransformationMethod(this));
        tv.setText("Android " + Build.VERSION.RELEASE);
        tv.setVisibility(View.INVISIBLE);

        mContent.addView(letter, lp);
        mContent.addView(logo, lp);

        final FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(lp);
        lp2.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp2.bottomMargin = 10*p;

        mContent.addView(tv, lp2);

        mContent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (logo.getVisibility() != View.VISIBLE) {
                    letter.animate().alpha(0.25f).scaleY(0.75f).scaleX(0.75f).setDuration(2000)
                            .start();
                    logo.setAlpha(0f);
                    logo.setVisibility(View.VISIBLE);
                    logo.animate().alpha(1f).setDuration(1000).setStartDelay(500).start();
                    tv.setAlpha(0f);
                    tv.setVisibility(View.VISIBLE);
                    tv.animate().alpha(1f).setDuration(1000).setStartDelay(1000).start();
                }
            }
        });

        mContent.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_MAIN)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        .addCategory("com.android.internal.category.PLATLOGO"));
                } catch (ActivityNotFoundException ex) {
                    android.util.Log.e("PlatLogoActivity", "Couldn't find a piece of pie.");
                }
                finish();
                return true;
            }
        });
        
        setContentView(mContent);
    }
}
