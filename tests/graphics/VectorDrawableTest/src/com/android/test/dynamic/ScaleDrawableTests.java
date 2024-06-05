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
import android.os.Bundle;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.GridLayout;
import android.widget.ScrollView;

@SuppressWarnings({"UnusedDeclaration"})
public class ScaleDrawableTests extends Activity {
    private static final String LOGCAT = "VectorDrawable1";

    private String[] scaleTypes = {
            "MATRIX      (0)",
            "FIT_XY      (1)",
            "FIT_START   (2)",
            "FIT_CENTER  (3)",
            "FIT_END     (4)",
            "CENTER      (5)",
            "CENTER_CROP (6)",
            "CENTER_INSIDE (7)"
    };

    protected int icon = R.drawable.bitmap_drawable01;
    protected int vector_icon = R.drawable.vector_drawable16;
    protected int animated_vector_icon = R.drawable.ic_hourglass_animation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scrollView = new ScrollView(this);
        GridLayout container = new GridLayout(this);
        scrollView.addView(container);
        container.setColumnCount(4);
        container.setBackgroundColor(0xFF888888);

        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        params.width = 300;
        params.height = 200;

        for (int i = 0; i < scaleTypes.length; i++) {
            TextView t = new TextView(this);
            t.setText(scaleTypes[i]);
            container.addView(t);

            ImageView.ScaleType scaleType = ImageView.ScaleType.values()[i];

            ImageView png_view = new ImageView(this);
            png_view.setLayoutParams(params);
            png_view.setScaleType(scaleType);
            png_view.setImageResource(icon);
            container.addView(png_view);

            ImageView view = new ImageView(this);
            view.setLayoutParams(params);
            view.setScaleType(scaleType);
            view.setImageResource(vector_icon);
            container.addView(view);

            ImageView avd_view = new ImageView(this);
            avd_view.setLayoutParams(params);
            avd_view.setScaleType(scaleType);
            avd_view.setImageResource(animated_vector_icon);
            container.addView(avd_view);

        }

        setContentView(scrollView);
    }
}
