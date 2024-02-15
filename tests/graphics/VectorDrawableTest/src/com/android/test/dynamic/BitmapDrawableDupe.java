/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.test.dynamic;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ScrollView;

import java.text.DecimalFormat;

@SuppressWarnings({"UnusedDeclaration"})
public class BitmapDrawableDupe extends Activity {
    private static final String LOGCAT = "VectorDrawable1";
    protected int[] icon = {
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
            R.drawable.bitmap_drawable01,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scrollView = new ScrollView(this);
        GridLayout container = new GridLayout(this);
        scrollView.addView(container);
        container.setColumnCount(5);
        container.setBackgroundColor(0xFF888888);

        DecimalFormat df = new DecimalFormat("#.##");
        long time =  android.os.SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < icon.length; i++) {
            Button button = new Button(this);
            button.setWidth(200);
            button.setBackgroundResource(icon[i]);
            container.addView(button);
        }

        setContentView(scrollView);
        time =  android.os.SystemClock.elapsedRealtimeNanos()-time;
        TextView t = new TextView(this);
        t.setText("avgS=" + df.format(time / (icon.length * 1000000.)) + " ms");
        container.addView(t);
    }
}
