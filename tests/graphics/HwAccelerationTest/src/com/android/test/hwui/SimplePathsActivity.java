/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class SimplePathsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout layout = new FrameLayout(this);
        EditText text = new EditText(this);
        layout.addView(text, new FrameLayout.LayoutParams(600, 350, Gravity.CENTER));
        text.setText("This is an example of an EditText widget \n" +
                "using simple paths to create the selection.");
        //text.setSelection(0, text.getText().length());

        setContentView(layout);
    }
}