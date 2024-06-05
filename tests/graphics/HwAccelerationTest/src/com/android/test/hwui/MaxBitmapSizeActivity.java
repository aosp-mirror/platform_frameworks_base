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
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class MaxBitmapSizeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        final LinearLayout layout = new LinearLayout(this);

        CanvasView view = new CanvasView(this);
        layout.addView(view, new LinearLayout.LayoutParams(200, 200));

        view = new CanvasView(this);
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        layout.addView(view, new LinearLayout.LayoutParams(200, 200));

        setContentView(layout);
    }

    private static class CanvasView extends View {
        public CanvasView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Log.d("Bitmap", "Hw         = " + canvas.isHardwareAccelerated());
            Log.d("Bitmap", "Max width  = " + canvas.getMaximumBitmapWidth());
            Log.d("Bitmap", "Max height = " + canvas.getMaximumBitmapHeight());
        }
    }
}
