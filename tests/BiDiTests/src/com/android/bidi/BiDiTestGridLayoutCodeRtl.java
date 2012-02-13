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

package com.android.bidi;

import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.*;

import static android.text.InputType.*;
import static android.widget.GridLayout.*;

public class BiDiTestGridLayoutCodeRtl extends Fragment {

    private FrameLayout currentView;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        currentView = (FrameLayout) inflater.inflate(R.layout.grid_layout_code, container, false);
        return currentView;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        currentView.addView(create(currentView.getContext()));
    }

    public static View create(Context context) {
        GridLayout layout = new GridLayout(context);
        layout.setUseDefaultMargins(true);
        layout.setAlignmentMode(ALIGN_BOUNDS);
        layout.setRowOrderPreserved(false);
        layout.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        Spec row1 = spec(0);
        Spec row2 = spec(1);
        Spec row3 = spec(2, BASELINE);
        Spec row4 = spec(3, BASELINE);
        Spec row5 = spec(2, 3, FILL); // allow the last two rows to overlap the middle two
        Spec row6 = spec(5);
        Spec row7 = spec(6);

        Spec col1a = spec(0, 4, CENTER);
        Spec col1b = spec(0, 4, LEFT);
        Spec col1c = spec(0, RIGHT);
        Spec col2 = spec(1, START);
        Spec col3 = spec(2, FILL);
        Spec col4a = spec(3);
        Spec col4b = spec(3, FILL);

        {
            TextView c = new TextView(context);
            c.setTextSize(32);
            c.setText("Email setup");
            layout.addView(c, new GridLayout.LayoutParams(row1, col1a));
        }
        {
            TextView c = new TextView(context);
            c.setTextSize(16);
            c.setText("You can configure email in just a few steps:");
            layout.addView(c, new GridLayout.LayoutParams(row2, col1b));
        }
        {
            TextView c = new TextView(context);
            c.setText("Email address:");
            layout.addView(c, new GridLayout.LayoutParams(row3, col1c));
        }
        {
            EditText c = new EditText(context);
            c.setEms(10);
            c.setInputType(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            layout.addView(c, new GridLayout.LayoutParams(row3, col2));
        }
        {
            TextView c = new TextView(context);
            c.setText("Password:");
            layout.addView(c, new GridLayout.LayoutParams(row4, col1c));
        }
        {
            TextView c = new EditText(context);
            c.setEms(8);
            c.setInputType(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD);
            layout.addView(c, new GridLayout.LayoutParams(row4, col2));
        }
        {
            Space c = new Space(context);
            layout.addView(c, new GridLayout.LayoutParams(row5, col3));
        }
        {
            Button c = new Button(context);
            c.setText("Manual setup");
            layout.addView(c, new GridLayout.LayoutParams(row6, col4a));
        }
        {
            Button c = new Button(context);
            c.setText("Next");
            layout.addView(c, new GridLayout.LayoutParams(row7, col4b));
        }

        return layout;
    }
}
