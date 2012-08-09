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
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import static android.widget.LinearLayout.LayoutParams;

public class GlyphCacheActivity extends Activity {

    private static final String mCharacterSet = "abcdefghijklmnopqrstuvwxyz" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "0123456789" + "~!@#$%^&*()_+-={}[]:\";'<>?,./";
    private int mTotalChars = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollView.addView(layout);

        while (mTotalChars < 10000) {
            layout.addView(createTextView());
        }
        setContentView(scrollView);
    }

    private TextView createTextView() {
        TextView textview = new TextView(this);
        textview.setTextSize(6 + (int) (Math.random() * 5) * 10);
        textview.setTextColor(0xff << 24 | (int) (Math.random() * 255) << 16 |
                (int) (Math.random() * 255) << 8 | (int) (Math.random() * 255) << 16);
        textview.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int numChars = 5 + (int) (Math.random() * 10);
        mTotalChars += numChars;
        textview.setText(createString(numChars));

        return textview;
    }

    private String createString(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(mCharacterSet.charAt((int)(Math.random() * mCharacterSet.length())));
        }
        return sb.toString();
    }
}
