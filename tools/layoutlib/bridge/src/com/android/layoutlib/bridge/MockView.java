/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.layoutlib.bridge;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Base class for mocked views.
 *
 * TODO: implement onDraw and draw a rectangle in a random color with the name of the class
 * (or better the id of the view).
 */
public class MockView extends TextView {

    public MockView(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs, defStyle, 0);
    }

    public MockView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        setText(this.getClass().getSimpleName());
        setTextColor(0xFF000000);
        setGravity(Gravity.CENTER);
    }

    @Override
    public void onDraw(Canvas canvas) {
        canvas.drawARGB(0xFF, 0x7F, 0x7F, 0x7F);

        super.onDraw(canvas);
    }
}
