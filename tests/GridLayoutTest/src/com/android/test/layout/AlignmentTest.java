/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.test.layout;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.GridLayout.Alignment;
import android.widget.GridLayout.LayoutParams;
import android.widget.TextView;

import static android.widget.GridLayout.BASELINE;
import static android.widget.GridLayout.BOTTOM;
import static android.widget.GridLayout.CENTER;
import static android.widget.GridLayout.FILL;
import static android.widget.GridLayout.LEFT;
import static android.widget.GridLayout.RIGHT;
import static android.widget.GridLayout.TOP;
import static android.widget.GridLayout.spec;

public class AlignmentTest extends Activity {

    public static final String[] HORIZONTAL_NAMES = {"LEFT", "center", "east", "fill"};
    public static final Alignment[] HORIZONTAL_ALIGNMENTS = {LEFT, CENTER, RIGHT, FILL};
    public static final String[] VERTICAL_NAMES = {"north", "center", "baseline", "south", "fill"};
    public static final Alignment[] VERTICAL_ALIGNMENTS = {TOP, CENTER, BASELINE, BOTTOM, FILL};
    private static Context CONTEXT;

    public static interface ViewFactory {
        View create(String name, int size);
    }

    public static final ViewFactory BUTTON_FACTORY = new ViewFactory() {
        public View create(String name, int size) {
            Button result = new Button(CONTEXT);
            result.setText(name);
           result.setOnClickListener(new OnClickListener() {
               @Override
               public void onClick(View v) {
                    animate(v);
               }
           });
            return result;
        }
    };

    public static final ViewFactory LABEL_FACTORY = new ViewFactory() {
        public View create(String name, int size) {
            TextView result = new TextView(CONTEXT);
            result.setText(name);
            result.setTextSize(40);
            return result;
        }
    };

    public static final ViewFactory TEXT_FIELD_FACTORY = new ViewFactory() {
        public View create(String name, int size) {
            EditText result = new EditText(CONTEXT);
            result.setText(name);
            return result;
        }
    };

    public static final ViewFactory[] FACTORIES =
                            {BUTTON_FACTORY, LABEL_FACTORY, TEXT_FIELD_FACTORY};

    public static ViewGroup create(Context context1) {
        CONTEXT = context1;
        GridLayout container = new GridLayout(context1);
        container.setUseDefaultMargins(true);

        for (int i = 0; i < VERTICAL_ALIGNMENTS.length; i++) {
            Alignment va = VERTICAL_ALIGNMENTS[i];
            for (int j = 0; j < HORIZONTAL_ALIGNMENTS.length; j++) {
                Alignment ha = HORIZONTAL_ALIGNMENTS[j];
                LayoutParams layoutParams = new LayoutParams(spec(i, va), spec(j, ha));
                String name = VERTICAL_NAMES[i] + "-" + HORIZONTAL_NAMES[j];
                ViewFactory factory = FACTORIES[(i + j) % FACTORIES.length];
                container.addView(factory.create(name, 20), layoutParams);
            }
        }

        return container;
    }

    public static void animate(View v) {

        long start = System.currentTimeMillis();
        int N = 1000;
        for (int i = 0; i < N; i++) {
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            lp.width += 1; // width;
            lp.height += 1; // height;
            v.requestLayout();
            GridLayout p = (GridLayout) v.getParent();
            p.layout(0, 0, 1000 + (i % 2), 500 + (i % 2));
        }
        float time = (float) (System.currentTimeMillis() - start) / N * 1000;
        System.out.println("Time: " + time + "mics");
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(create(getBaseContext()));
    }

}