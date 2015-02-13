/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.test.voiceinteraction;

import android.annotation.Nullable;
import android.app.AssistData;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;

public class AssistVisualizer extends View {
    static final String TAG = "AssistVisualizer";

    AssistData mAssistData;
    final Paint mFramePaint = new Paint();
    final ArrayList<Rect> mTextRects = new ArrayList<>();
    final int[] mTmpLocation = new int[2];

    public AssistVisualizer(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        mFramePaint.setColor(0xffff0000);
        mFramePaint.setStyle(Paint.Style.STROKE);
        mFramePaint.setStrokeWidth(0);
    }

    public void setAssistData(AssistData ad) {
        mAssistData = ad;
        mTextRects.clear();
        final int N = ad.getWindowCount();
        if (N > 0) {
            AssistData.ViewNode window = new AssistData.ViewNode();
            for (int i=0; i<N; i++) {
                ad.getWindowAt(i, window);
                buildTextRects(window, 0, 0);
            }
        }
    }

    void buildTextRects(AssistData.ViewNode root, int parentLeft, int parentTop) {
        if (root.getVisibility() != View.VISIBLE) {
            return;
        }
        int left = parentLeft+root.getLeft();
        int top = parentTop+root.getTop();
        Log.d(TAG, "View " + root.getClassName() + ": " + left + ", " + top);
        if (root.getText() != null) {
            Rect r = new Rect(left, top, left+root.getWidth(), top+root.getHeight());
            Log.d(TAG, "Text Rect " + r.toShortString() + ": " + root.getText());
            mTextRects.add(r);
        }
        final int N = root.getChildCount();
        if (N > 0) {
            left -= root.getScrollX();
            top -= root.getScrollY();
            AssistData.ViewNode child = new AssistData.ViewNode();
            for (int i=0; i<N; i++) {
                root.getChildAt(i, child);
                buildTextRects(child, left, top);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        getLocationOnScreen(mTmpLocation);
        final int N = mTextRects.size();
        for (int i=0; i<N; i++) {
            Rect r = mTextRects.get(i);
            canvas.drawRect(r.left-mTmpLocation[0], r.top-mTmpLocation[1],
                    r.right-mTmpLocation[0], r.bottom-mTmpLocation[1], mFramePaint);
        }
    }
}
