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
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class AnimatedVectorDrawableTest extends Activity implements View.OnClickListener {
    private static final String LOGCAT = "AnimatedVectorDrawableTest";

    protected int[] icon = {
            R.drawable.btn_radio_on_to_off_bundle,
            R.drawable.ic_rotate_2_portrait_v2_animation,
            R.drawable.ic_signal_airplane_v2_animation,
            R.drawable.ic_hourglass_animation,
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
        final int[] layerTypes = {View.LAYER_TYPE_SOFTWARE, View.LAYER_TYPE_HARDWARE};
        final boolean[] forceOnUi = {false, true};
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        GridLayout container = new GridLayout(this);
        scrollView.addView(container);
        container.setColumnCount(layerTypes.length * forceOnUi.length);

        for (int j = 0; j < layerTypes.length; j++) {
            for (int k = 0; k < forceOnUi.length; k++) {
                TextView textView = new TextView(this);
                String category = "Layer:"
                        + (layerTypes[j] == View.LAYER_TYPE_SOFTWARE ? "SW" : "HW")
                        + (forceOnUi[k] == true ? ",forceUI" : "");
                textView.setText(category);
                container.addView(textView);
            }
        }
        for (int i = 0; i < icon.length; i++) {
            for (int j = 0; j < layerTypes.length; j++) {
                for (int k = 0; k < forceOnUi.length; k++) {
                    Button button = new Button(this);
                    button.setWidth(300);
                    button.setHeight(300);
                    button.setLayerType(layerTypes[j], null);
                    button.setBackgroundResource(icon[i]);
                    AnimatedVectorDrawable d = (AnimatedVectorDrawable) button.getBackground();
                    if (forceOnUi[k] == true) {
                        d.forceAnimationOnUI();
                    }
                    d.registerAnimationCallback(new Animatable2.AnimationCallback() {
                        @Override
                        public void onAnimationStart(Drawable drawable) {
                            Log.v(LOGCAT, "Animator start");
                        }

                        @Override
                        public void onAnimationEnd(Drawable drawable) {
                            Log.v(LOGCAT, "Animator end");
                        }
                    });

                    container.addView(button);
                    button.setOnClickListener(this);
                }
            }
        }

        setContentView(scrollView);
    }

    @Override
    public void onClick(View v) {
        AnimatedVectorDrawable d = (AnimatedVectorDrawable) v.getBackground();
        d.start();
    }
}
