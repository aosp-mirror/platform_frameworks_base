/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.player.platform;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;


/**
 * Implementation for the click handling
 */
class ClickAreaView extends View {
    private int mId;
    private String mMetadata;
    Paint mPaint = new Paint();

    private boolean mDebug;

    ClickAreaView(Context context, boolean debug, int id,
                         String contentDescription, String metadata) {
        super(context);
        this.mId = id;
        this.mMetadata = metadata;
        this.mDebug = debug;
        setContentDescription(contentDescription);
    }


    public void setDebug(boolean value) {
        if (mDebug != value) {
            mDebug = value;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mDebug) {
            mPaint.setARGB(200, 200, 0, 0);
            mPaint.setStrokeWidth(3f);
            canvas.drawLine(0, 0, getWidth(), 0, mPaint);
            canvas.drawLine(getWidth(), 0, getWidth(), getHeight(), mPaint);
            canvas.drawLine(getWidth(), getHeight(), 0, getHeight(), mPaint);
            canvas.drawLine(0, getHeight(), 0, 0, mPaint);

            mPaint.setTextSize(20f);
            canvas.drawText("id: " + mId + " : " + mMetadata, 4, 22, mPaint);
        }
    }
}
