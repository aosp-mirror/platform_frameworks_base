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
package com.android.test.hwui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;

public class TransformsAndAnimationsActivity extends Activity {
    Button button1;
    Button button2;
    Button button3;
    Button button1a;
    Button button2a;
    Button button3a;
    Button button1b;
    Button button2b;
    Button button3b;
    Button button4;
    Button button5;
    Button button6;
    Button button7;
    Button button8;
    CheckBox layersNoneCB;
    CheckBox layersHardwareCB;
    CheckBox layersSoftwareCB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transforms_and_animations);

        button1 = (Button) findViewById(R.id.button1);
        button2 = (Button) findViewById(R.id.button2);
        button3 = (Button) findViewById(R.id.button3);
        button1a = (Button) findViewById(R.id.button1a);
        button2a = (Button) findViewById(R.id.button2a);
        button3a = (Button) findViewById(R.id.button3a);
        button1b = (Button) findViewById(R.id.button1b);
        button2b = (Button) findViewById(R.id.button2b);
        button3b = (Button) findViewById(R.id.button3b);
        button4 = (Button) findViewById(R.id.button4);
        button5 = (Button) findViewById(R.id.button5);
        button6 = (Button) findViewById(R.id.button6);
        button7 = (Button) findViewById(R.id.button7);
        button8 = (Button) findViewById(R.id.button8);
        layersNoneCB = (CheckBox) findViewById(R.id.layersNoneCB);
        layersHardwareCB = (CheckBox) findViewById(R.id.layersHwCB);
        layersSoftwareCB = (CheckBox) findViewById(R.id.layersSwCB);

        layersNoneCB.setOnCheckedChangeListener(new CheckBox.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    setLayerType(View.LAYER_TYPE_NONE);
                    layersHardwareCB.setChecked(false);
                    layersSoftwareCB.setChecked(false);
                }
            }
        });

        layersSoftwareCB.setOnCheckedChangeListener(new CheckBox.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    setLayerType(View.LAYER_TYPE_SOFTWARE);
                    layersHardwareCB.setChecked(false);
                    layersNoneCB.setChecked(false);
                }
            }
        });

        layersHardwareCB.setOnCheckedChangeListener(new CheckBox.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    setLayerType(View.LAYER_TYPE_HARDWARE);
                    layersNoneCB.setChecked(false);
                    layersSoftwareCB.setChecked(false);
                }
            }
        });

        button1a.setAlpha(.5f);
        button2a.setAlpha(.5f);
        button3a.setAlpha(.5f);
        button3.setTranslationX(50);
        button7.setTranslationX(50);
        button8.setTranslationX(50);

        final AlphaAnimation alphaAnim = new AlphaAnimation(1, 0);
        alphaAnim.setDuration(1000);
        alphaAnim.setRepeatCount(Animation.INFINITE);
        alphaAnim.setRepeatMode(Animation.REVERSE);

        final TranslateAnimation transAnim = new TranslateAnimation(0, -50, 0, 0);
        transAnim.setDuration(1000);
        transAnim.setRepeatCount(Animation.INFINITE);
        transAnim.setRepeatMode(Animation.REVERSE);
        
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                button1.startAnimation(alphaAnim);
                button2.startAnimation(alphaAnim);
                button3.startAnimation(alphaAnim);

                button1a.startAnimation(alphaAnim);
                button2a.startAnimation(alphaAnim);
                button3a.startAnimation(alphaAnim);

                button1b.startAnimation(alphaAnim);
                button2b.startAnimation(alphaAnim);
                button3b.startAnimation(alphaAnim);
                startAnimator(button1b);
                startAnimator(button2b);
                startAnimator(button3b);

                button7.startAnimation(transAnim);
                button8.startAnimation(transAnim);
            }
        }, 2000);
    }

    private void setLayerType(int layerType) {
        button1.setLayerType(layerType, null);
        button2.setLayerType(layerType, null);
        button3.setLayerType(layerType, null);
        button1a.setLayerType(layerType, null);
        button2a.setLayerType(layerType, null);
        button3a.setLayerType(layerType, null);
        button1b.setLayerType(layerType, null);
        button2b.setLayerType(layerType, null);
        button3b.setLayerType(layerType, null);
        button4.setLayerType(layerType, null);
        button5.setLayerType(layerType, null);
        button6.setLayerType(layerType, null);
        button7.setLayerType(layerType, null);
        button8.setLayerType(layerType, null);
    }

    private void startAnimator(View target) {
        ObjectAnimator anim1b = ObjectAnimator.ofFloat(target, View.ALPHA, 0);
        anim1b.setRepeatCount(ValueAnimator.INFINITE);
        anim1b.setRepeatMode(ValueAnimator.REVERSE);
        anim1b.setDuration(1000);
        anim1b.start();
    }

    public static class MyLayout extends LinearLayout {

        public MyLayout(Context context) {
            super(context);
            setStaticTransformationsEnabled(true);
        }

        public MyLayout(Context context, AttributeSet attrs) {
            super(context, attrs);
            setStaticTransformationsEnabled(true);
        }

        public MyLayout(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
            setStaticTransformationsEnabled(true);
        }

        @Override
        protected boolean getChildStaticTransformation(View child, Transformation t) {
            t.clear();
            t.setAlpha(.35f);

            return true;
        }
    }
}

