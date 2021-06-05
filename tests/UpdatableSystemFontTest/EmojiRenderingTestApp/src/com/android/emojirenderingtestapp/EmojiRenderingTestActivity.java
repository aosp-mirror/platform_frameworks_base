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
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Test app to render an emoji. */
public class EmojiRenderingTestActivity extends Activity {

    private static final String TEST_NOTO_SERIF = "test-noto-serif";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        TextView emojiTextView = new TextView(this);
        emojiTextView.setText("\uD83E\uDD72"); // 🥲
        container.addView(emojiTextView, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        TextView serifTextView = new TextView(this);
        serifTextView.setTypeface(Typeface.create(TEST_NOTO_SERIF, Typeface.NORMAL));
        serifTextView.setText(TEST_NOTO_SERIF);
        container.addView(serifTextView, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        setContentView(container);
    }
}
