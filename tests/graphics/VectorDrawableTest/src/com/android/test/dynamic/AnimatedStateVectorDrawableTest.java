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
import android.widget.CheckBox;
import android.widget.GridLayout;
import android.widget.ScrollView;

public class AnimatedStateVectorDrawableTest extends Activity {
    private static final String LOGCAT = "AnimatedStateVectorDrawableTest";

    protected int[] icon = {
            // These shows pairs of ASLD , the left side set the reversible to true.
            // the right side set to false.
            R.drawable.state_animation_vector_drawable01,
            R.drawable.state_animation_vector_drawable01_false,
            R.drawable.state_animation_vector_drawable02,
            R.drawable.state_animation_vector_drawable02_false,
            R.drawable.state_animation_vector_drawable03,
            R.drawable.state_animation_vector_drawable03_false,
            R.drawable.state_animation_drawable04,
            R.drawable.state_animation_drawable04_false,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        GridLayout container = new GridLayout(this);
        scrollView.addView(container);
        container.setColumnCount(2);

        for (int i = 0; i < icon.length; i++) {
            CheckBox button = new CheckBox(this);
            button.setWidth(400);
            button.setHeight(400);
            button.setBackgroundResource(icon[i]);
            container.addView(button);
        }

        setContentView(scrollView);
    }
}
