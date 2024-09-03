/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.input.debug;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class TouchpadDebugView extends LinearLayout {

    /**
     * Input device ID for the touchpad that this debug view is displaying.
     */
    private final int mTouchpadId;

    public TouchpadDebugView(Context context, int touchpadId) {
        super(context);
        mTouchpadId = touchpadId;
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setBackgroundColor(Color.TRANSPARENT);

        // TODO(b/286551975): Replace this content with the touchpad debug view.

        TextView textView1 = new TextView(context);
        textView1.setBackgroundColor(Color.parseColor("#FFFF0000"));
        textView1.setTextSize(20);
        textView1.setText("Touchpad Debug View 1");
        textView1.setGravity(Gravity.CENTER);
        textView1.setTextColor(Color.WHITE);

        textView1.setLayoutParams(new LayoutParams(1000, 200));

        TextView textView2 = new TextView(context);
        textView2.setBackgroundColor(Color.BLUE);
        textView2.setTextSize(20);
        textView2.setText("Touchpad Debug View 2");
        textView2.setGravity(Gravity.CENTER);
        textView2.setTextColor(Color.WHITE);
        textView2.setLayoutParams(new LayoutParams(1000, 200));

        addView(textView1);
        addView(textView2);
    }

    public int getTouchpadId() {
        return mTouchpadId;
    }
}
