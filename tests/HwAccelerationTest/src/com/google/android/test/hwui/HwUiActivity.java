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

package com.google.android.test.hwui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

import static android.util.Log.d;

public class HwUiActivity extends Activity {
    private static final String LOG_TAG = "HwUi";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new DirtyBitmapView(this));
    }
    
    static int dipToPx(Context c, int dip) {
        return (int) (c.getResources().getDisplayMetrics().density * dip + 0.5f);
    }

    static class DirtyBitmapView extends View {
        private Bitmap mCache;

        DirtyBitmapView(Context c) {
            super(c);

            final int width = dipToPx(c, 100);
            final int height = dipToPx(c, 100);

            mCache = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            logGenerationId("Dirty cache created", mCache);

            Canvas canvas = new Canvas(mCache);
            logGenerationId("Canvas cache created", mCache);

            canvas.drawColor(0xffff0000);
            logGenerationId("Cache filled", mCache);
            
            Paint p = new Paint();
            p.setColor(0xff0000ff);

            canvas.drawRect(width / 2.0f, height / 2.0f, width, height, p);
            logGenerationId("Cache modified", mCache);
        }

        private static void logGenerationId(String message, Bitmap b) {
            d(LOG_TAG, message);
            d(LOG_TAG, "  bitmap id=" + b.getGenerationId());
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            
            canvas.drawBitmap(mCache, 0, 0, null);
            logGenerationId("Cache drawn", mCache);
        }
    }
}
