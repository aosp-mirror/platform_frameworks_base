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
import android.graphics.drawable.VectorDrawable;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ScrollView;

import java.text.DecimalFormat;

@SuppressWarnings({"UnusedDeclaration"})
public class VectorDrawablePerformance extends Activity {
    private static final String LOGCAT = "VectorDrawable1";
    protected int[] icon = {
            R.drawable.vector_drawable01,
            R.drawable.vector_drawable02,
            R.drawable.vector_drawable03,
            R.drawable.vector_drawable04,
            R.drawable.vector_drawable05,
            R.drawable.vector_drawable06,
            R.drawable.vector_drawable07,
            R.drawable.vector_drawable08,
            R.drawable.vector_drawable09,
            R.drawable.vector_drawable10,
            R.drawable.vector_drawable11,
            R.drawable.vector_drawable12,
            R.drawable.vector_drawable13,
            R.drawable.vector_drawable14,
            R.drawable.vector_drawable15,
            R.drawable.vector_drawable16,
            R.drawable.vector_drawable17,
            R.drawable.vector_drawable18,
            R.drawable.vector_drawable19,
            R.drawable.vector_drawable20,
            R.drawable.vector_drawable21,
            R.drawable.vector_drawable22,
            R.drawable.vector_drawable23,
            R.drawable.vector_drawable24,
            R.drawable.vector_drawable25,
            R.drawable.vector_drawable26,
            R.drawable.vector_drawable27,
            R.drawable.vector_drawable28,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scrollView = new ScrollView(this);
        GridLayout container = new GridLayout(this);
        scrollView.addView(container);
        container.setColumnCount(5);
        Resources res = this.getResources();
        container.setBackgroundColor(0xFF888888);
        VectorDrawable []d = new VectorDrawable[icon.length];
        long time =  android.os.SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < icon.length; i++) {
             d[i] = VectorDrawable.create(res,icon[i]);
        }
        time =  android.os.SystemClock.elapsedRealtimeNanos()-time;
        TextView t = new TextView(this);
        DecimalFormat df = new DecimalFormat("#.##");
        t.setText("avgL=" + df.format(time / (icon.length * 1000000.)) + " ms");
        container.addView(t);
        time =  android.os.SystemClock.elapsedRealtimeNanos();
        for (int i = 0; i < icon.length; i++) {
            Button button = new Button(this);
            button.setWidth(200);
            button.setBackgroundResource(icon[i]);
            container.addView(button);
        }
        setContentView(scrollView);
        time =  android.os.SystemClock.elapsedRealtimeNanos()-time;
        t = new TextView(this);
        t.setText("avgS=" + df.format(time / (icon.length * 1000000.)) + " ms");
        container.addView(t);
    }
}
