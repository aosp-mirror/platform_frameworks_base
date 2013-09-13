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
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import static android.view.Gravity.BOTTOM;
import static android.view.Gravity.CENTER;
import static android.view.Gravity.FILL;
import static android.view.Gravity.LEFT;
import static android.view.Gravity.NO_GRAVITY;
import static android.view.Gravity.RIGHT;
import static android.view.Gravity.TOP;

public abstract class AbstractLayoutTest extends Activity {

    public static final String[] HORIZONTAL_NAMES = { "LEFT", "CENTER", "RIGHT", "FILL" };
    public static final int[] HORIZONTAL_ALIGNMENTS = { LEFT, CENTER, RIGHT, FILL };
    public static final String[] VERTICAL_NAMES = { "TOP", "CENTER", "BASELINE", "BOTTOM", "FILL" };
    public static final int[] VERTICAL_ALIGNMENTS = { TOP, CENTER, NO_GRAVITY, BOTTOM, FILL };

    public View create(Context context, String name, int size) {
        Button result = new Button(context);
        result.setText(name);
        result.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                animate(v);
            }
        });
        return result;
    }

    public abstract ViewGroup create(Context context);
    public abstract String tag();

    public void animate(View v) {
        long start = System.currentTimeMillis();
        int N = 1000;
        for (int i = 0; i < N; i++) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            lp.topMargin = (lp.topMargin + 1) % 31;
            lp.leftMargin = (lp.leftMargin + 1) % 31;

            v.requestLayout();
            v.invalidate();
            ViewGroup p = (ViewGroup) v.getParent();
            p.layout(0, 0, 1000 + (i % 2), 500 + (i % 2));
        }
        Log.d(tag(), "Time: " + (float) (System.currentTimeMillis() - start) / N * 1000 + "mics");
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(create(getBaseContext()));
    }

}