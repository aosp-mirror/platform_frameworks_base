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

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

public class ViewLayerInvalidationActivity extends Activity {

    int currentColor = Color.WHITE;
    boolean nestedLayersOn = false;
    ArrayList<LinearLayout> linearLayouts = new ArrayList<LinearLayout>();
    ArrayList<LinearLayout> topLayouts = new ArrayList<LinearLayout>();
    ArrayList<TextView> textViews = new ArrayList<TextView>();
    LinearLayout container = null;
    boolean randomInvalidates = false;
    TextView nestedStatusTV, invalidateStatusTV;
    static final String NO_NESTING = "Nested Layer: NO   ";
    static final String NESTING = "Nested Layers: YES   ";
    static final String NO_INVALIDATING = "Random Invalidating: NO   ";
    static final String INVALIDATING = "Random Invalidating: YES   ";
    static final int TEXT_COLOR_INTERVAL = 400;
    static final int INVALIDATING_INTERVAL = 1000;
    static final int NESTING_INTERVAL = 2000;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.view_layer_invalidation);

        container = (LinearLayout) findViewById(R.id.container);
        final LinearLayout container1 = (LinearLayout) findViewById(R.id.container1);
        final LinearLayout container2 = (LinearLayout) findViewById(R.id.container2);
        final LinearLayout container3 = (LinearLayout) findViewById(R.id.container3);
        nestedStatusTV = (TextView) findViewById(R.id.nestedStatus);
        invalidateStatusTV = (TextView) findViewById(R.id.invalidateStatus);
        final TextView tva = (TextView) findViewById(R.id.textviewa);

        topLayouts.add(container1);
        topLayouts.add(container2);
        topLayouts.add(container3);

        collectLinearLayouts(container);
        collectTextViews(container);

        nestedStatusTV.setText(NO_NESTING);
        invalidateStatusTV.setText(NO_INVALIDATING);

        tva.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        container1.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        container2.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        container3.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        container.postDelayed(textColorSetter, TEXT_COLOR_INTERVAL);
        container.postDelayed(nestedLayerSetter, NESTING_INTERVAL);
        container.postDelayed(randomInvalidatesSetter, INVALIDATING_INTERVAL);
    }

    private Runnable textColorSetter = new Runnable() {
        @Override
        public void run() {
            currentColor = (currentColor == Color.WHITE) ? Color.RED : Color.WHITE;
            for (TextView tv : textViews) {
                tv.setTextColor(currentColor);
            }
            if (randomInvalidates) {
                randomInvalidator(container);
            }
            container.postDelayed(textColorSetter, TEXT_COLOR_INTERVAL);
        }
    };

    private Runnable randomInvalidatesSetter = new Runnable() {
        @Override
        public void run() {
            randomInvalidates = !randomInvalidates;
            invalidateStatusTV.setText(randomInvalidates ? INVALIDATING : NO_INVALIDATING);
            container.postDelayed(randomInvalidatesSetter, INVALIDATING_INTERVAL);
        }
    };

    private Runnable nestedLayerSetter = new Runnable() {
        @Override
        public void run() {
            nestedLayersOn = !nestedLayersOn;
            nestedStatusTV.setText(nestedLayersOn ? NESTING : NO_NESTING);
            for (LinearLayout layout : linearLayouts) {
                layout.setLayerType(nestedLayersOn ?
                        View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
            }
            if (!nestedLayersOn) {
                for (LinearLayout layout : topLayouts) {
                    layout.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                }
            }
            container.postDelayed(nestedLayerSetter, NESTING_INTERVAL);
        }
    };

    /**
     * Invalidates views based on random chance (50%). This is meant to test
     * invalidating several items in the hierarchy at the same time, which can cause artifacts
     * if our invalidation-propagation logic is not sound.
     */
    private void randomInvalidator(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); ++i) {
            View child = parent.getChildAt(i);
            if (Math.random() < .5) {
                child.invalidate();
            }
            if (child instanceof ViewGroup) {
                randomInvalidator((ViewGroup) child);
            }
        }
    }

    private void collectLinearLayouts(View view) {
        if (!(view instanceof LinearLayout)) {
            return;
        }
        LinearLayout parent = (LinearLayout) view;
        linearLayouts.add(parent);
        for (int i = 0; i < parent.getChildCount(); ++i) {
            collectLinearLayouts(parent.getChildAt(i));
        }
    }

    private void collectTextViews(View view) {
        if (view instanceof TextView) {
            textViews.add((TextView) view);
            return;
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) view;
        for (int i = 0; i < parent.getChildCount(); ++i) {
            collectTextViews(parent.getChildAt(i));
        }
    }
}
