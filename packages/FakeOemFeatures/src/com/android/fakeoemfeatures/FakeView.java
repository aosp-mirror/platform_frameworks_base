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

package com.android.fakeoemfeatures;

import java.util.Random;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Message;
import android.view.View;

/**
 * Fake view to emulate stuff an OEM may want to do.
 */
public class FakeView extends View {
    static final long TICK_DELAY = 30*1000; // 30 seconds
    static final int MSG_TICK = 1;

    final Handler mHandler = new Handler() {
        @Override public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_TICK:
                    invalidate();
                    sendEmptyMessageDelayed(MSG_TICK, TICK_DELAY);
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    };

    final Paint mPaint = new Paint();
    final Random mRandom = new Random();

    public FakeView(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mHandler.sendEmptyMessageDelayed(MSG_TICK, TICK_DELAY);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mHandler.removeMessages(MSG_TICK);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xff000000);
        mPaint.setTextSize(mRandom.nextInt(40) + 10);
        mPaint.setColor(0xff000000 + mRandom.nextInt(0x1000000));
        int x = mRandom.nextInt(getWidth()) - (getWidth()/2);
        int y = mRandom.nextInt(getHeight());
        canvas.drawText("abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                x, y, mPaint);
    }
}
