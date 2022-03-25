/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm.flicker.testapp;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public class DialogThemedActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple);
        getWindow().getDecorView().setBackgroundColor(Color.TRANSPARENT);
        TextView textView = new TextView(this);
        textView.setText("This is a test dialog");
        textView.setTextColor(Color.BLACK);
        LinearLayout layout = new LinearLayout(this);
        layout.setBackgroundColor(Color.GREEN);
        layout.addView(textView);

        // Create a dialog with dialog-themed activity
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(layout)
                .setTitle("Dialog for test")
                .create();
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(MATCH_PARENT,
                MATCH_PARENT);
        attrs.flags = FLAG_DIM_BEHIND | FLAG_ALT_FOCUSABLE_IM;
        dialog.getWindow().getDecorView().setLayoutParams(attrs);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        dialog.setOnDismissListener((d) -> finish());
    }
}
