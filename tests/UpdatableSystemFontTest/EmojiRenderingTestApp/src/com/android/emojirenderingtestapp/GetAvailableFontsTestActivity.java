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

package com.android.emojirenderingtestapp;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Activity;
import android.graphics.fonts.Font;
import android.graphics.fonts.SystemFonts;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

public class GetAvailableFontsTestActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String emojiFontPath = "<Not found>";
        for (Font font : SystemFonts.getAvailableFonts()) {
            // Calls font attribute getters to make sure that they don't open font files.
            font.getAxes();
            font.getFile();
            font.getLocaleList();
            font.getStyle();
            font.getTtcIndex();
            if ("NotoColorEmoji.ttf".equals(font.getFile().getName())) {
                emojiFontPath = font.getFile().getAbsolutePath();
            }
        }
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        TextView textView = new TextView(this);
        textView.setText(emojiFontPath);
        container.addView(textView, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        setContentView(container);
    }
}
