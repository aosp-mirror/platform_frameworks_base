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

import android.widget.*;

import static android.text.InputType.TYPE_CLASS_TEXT;
import static android.view.inputmethod.EditorInfo.*;
import static android.widget.GridLayout.*;

public class Activity2 extends Activity {

    public static View create(Context context) {
        GridLayout vg = new GridLayout(context);
        vg.setUseDefaultMargins(true);

        Group row1 = new Group(1, CENTER);
        Group row2 = new Group(2, CENTER);
        Group row3 = new Group(3, BASELINE);
        Group row4 = new Group(4, BASELINE);
        Group row5 = new Group(5, FILL);
        Group row6 = new Group(6, CENTER);
        Group row7 = new Group(7, CENTER);

        Group col1a = new Group(1, 5, CENTER);
        Group col1b = new Group(1, 5, LEFT);
        Group col1c = new Group(1, RIGHT);
        Group col2 =  new Group(2, LEFT);
        Group col3 =  new Group(3, FILL);
        Group col4 =  new Group(4, FILL);

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
            {
                LayoutParams lp = new LayoutParams(row5, col3);
                lp.rowWeight = 1;
                lp.columnWeight = 1;
                vg.addView(v, lp);
            }
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