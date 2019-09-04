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
import android.graphics.RenderNode;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

@SuppressWarnings({"UnusedDeclaration"})
public class PositionListenerActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        ProgressBar spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        layout.addView(spinner);

        ScrollView scrollingThing = new ScrollView(this);
        scrollingThing.addView(new MyPositionReporter(this));
        layout.addView(scrollingThing);

        setContentView(layout);
    }

    static class MyPositionReporter extends TextView implements RenderNode.PositionUpdateListener {
        RenderNode mNode;
        int mCurrentCount = 0;
        int mTranslateY = 0;

        MyPositionReporter(Context c) {
            super(c);
            mNode = new RenderNode("positionListener");
            mNode.addPositionUpdateListener(this);
            setTextAlignment(TEXT_ALIGNMENT_VIEW_START);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            setMeasuredDimension(getMeasuredWidth(), 10000);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            mNode.setLeftTopRightBottom(left, top, right, bottom);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            ScrollView parent = (ScrollView) getParent();
            canvas.translate(0, parent.getScrollY());
            super.onDraw(canvas);
            canvas.translate(0, -parent.getScrollY());
            // Inject our listener proxy
            canvas.drawRenderNode(mNode);
        }

        @Override
        public void positionChanged(long frameNumber, int left, int top, int right, int bottom) {
            post(() -> {
                mCurrentCount++;
                setText(String.format("%d: Position [%d, %d, %d, %d]", mCurrentCount,
                        left, top, right, bottom));
            });
        }

        @Override
        public void positionLost(long frameNumber) {
            post(() -> {
                mCurrentCount++;
                setText(mCurrentCount + " No position");
            });
        }
    }
}
