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
import android.graphics.drawable.AnimatedVectorDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ScrollView;

public class AnimatedVectorDrawableTest extends Activity implements View.OnClickListener {
    private static final String LOGCAT = "AnimatedVectorDrawableTest";

    protected int[] icon = {
            R.drawable.animation_vector_linear_progress_bar,
            R.drawable.animation_vector_drawable_grouping_1,
            R.drawable.animation_vector_progress_bar,
            R.drawable.animation_vector_drawable_favorite,
            R.drawable.animation_vector_drawable01,
            // Duplicate to test constant state.
            R.drawable.animation_vector_drawable01,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        GridLayout container = new GridLayout(this);
        scrollView.addView(container);
        container.setColumnCount(1);

        for (int i = 0; i < icon.length; i++) {
            Button button = new Button(this);
            button.setWidth(400);
            button.setHeight(400);
            button.setBackgroundResource(icon[i]);
            container.addView(button);
            button.setOnClickListener(this);
        }

        setContentView(scrollView);
    }

    @Override
    public void onClick(View v) {
        AnimatedVectorDrawable d = (AnimatedVectorDrawable) v.getBackground();
        d.start();
    }
}
