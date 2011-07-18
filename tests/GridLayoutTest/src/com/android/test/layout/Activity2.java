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
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.Space;
import android.widget.TextView;

import static android.text.InputType.TYPE_CLASS_TEXT;
import static android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
import static android.view.inputmethod.EditorInfo.TYPE_TEXT_VARIATION_PASSWORD;
import static android.widget.GridLayout.*;

public class Activity2 extends Activity {

    public static View create(Context context) {
        GridLayout vg = new GridLayout(context);
        vg.setUseDefaultMargins(true);
        vg.setAlignmentMode(ALIGN_BOUNDS);

        Spec row1 = spec(0, CENTER);
        Spec row2 = spec(1, CENTER);
        Spec row3 = spec(2, BASELINE);
        Spec row4 = spec(3, BASELINE);
        Spec row5 = spec(4, FILL, CAN_STRETCH);
        Spec row6 = spec(5, CENTER);
        Spec row7 = spec(6, CENTER);

        Spec col1a = spec(0, 4, CENTER);
        Spec col1b = spec(0, 4, LEFT);
        Spec col1c = spec(0, RIGHT);
        Spec col2 = spec(1, LEFT);
        Spec col3 = spec(2, FILL, CAN_STRETCH);
        Spec col4 = spec(3, FILL);

        {
            TextView v = new TextView(context);
            v.setTextSize(48);
            v.setText("Email setup");
            vg.addView(v, new LayoutParams(row1, col1a));
        }
        {
            TextView v = new TextView(context);
            v.setTextSize(20);
            v.setText("You can configure email in just a few steps:");
            vg.addView(v, new LayoutParams(row2, col1b));
        }
        {
            TextView v = new TextView(context);
            v.setText("Email address:");
            vg.addView(v, new LayoutParams(row3, col1c));
        }
        {
            EditText v = new EditText(context);
            v.setInputType(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            {
                LayoutParams lp = new LayoutParams(row3, col2);
                lp.width = (int) v.getPaint().measureText("Frederick.W.Flintstone@bedrock.com    ");
                vg.addView(v, lp);
            }
        }
        {
            TextView v = new TextView(context);
            v.setText("Password:");
            vg.addView(v, new LayoutParams(row4, col1c));
        }
        {
            TextView v = new EditText(context);
            v.setInputType(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD);
            {
                LayoutParams lp = new LayoutParams(row4, col2);
                lp.width = (int) v.getPaint().measureText("************");
                vg.addView(v, lp);
            }
        }
        {
            Space v = new Space(context);
            vg.addView(v, new LayoutParams(row5, col3));
        }
        {
            Button v = new Button(context);
            v.setText("Manual setup");
            vg.addView(v, new LayoutParams(row6, col4));
        }
        {
            Button v = new Button(context);
            v.setText("Next");
            vg.addView(v, new LayoutParams(row7, col4));
        }

        return vg;
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(create(getBaseContext()));
    }

}