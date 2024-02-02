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

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public class VectorDrawableAnimation extends Activity {
    private static final String LOGCAT = "VectorDrawableAnimation";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Button button = new Button(this);
        button.setBackgroundResource(R.drawable.animation_drawable_vector);

        button.setOnClickListener(new View.OnClickListener() {
                @Override
            public void onClick(View v) {
                AnimationDrawable frameAnimation = (AnimationDrawable) v.getBackground();
                // Start the animation (looped playback by default).
                frameAnimation.start();
            }
        });

        setContentView(button);
    }

}
