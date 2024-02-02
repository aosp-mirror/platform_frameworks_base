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
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class OpaqueActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final OpaqueView view = new OpaqueView(this);
        setContentView(view, new FrameLayout.LayoutParams(100, 100, Gravity.CENTER));
    }

    public static class OpaqueView extends View {
        public OpaqueView(Context c) {
            super(c);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    invalidate();
                    Log.d("OpaqueView", "Invalidate");
                }
            });
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(0xffff0000, PorterDuff.Mode.SRC);
        }

        @Override
        public boolean isOpaque() {
            return true;
        }
    }
}
