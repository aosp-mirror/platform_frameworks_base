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
import android.graphics.PointF;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.os.Bundle;
import android.view.MotionEvent;
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

        ScrollView scrollingThing = new ScrollView(this) {
            int setting = 0;
            PointF opts[] = new PointF[] {
                    new PointF(0, 0),
                    new PointF(0, -1f),
                    new PointF(1f, 0),
                    new PointF(0, 1f),
                    new PointF(-1f, 0),
                    new PointF(-1f, 1f),
            };
            {
                setWillNotDraw(false);
            }

            @Override
            public boolean onTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    setting = (setting + 1) % opts.length;
                    invalidate();
                }
                return super.onTouchEvent(ev);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                RenderNode node = ((RecordingCanvas) canvas).mNode;
                PointF dir = opts[setting];
                float maxStretchAmount = 100f;
                // Although we could do this in a single call, the real one won't be - so mimic that
                if (dir.x != 0f) {
                    node.stretch(dir.x, 0f, maxStretchAmount, maxStretchAmount);
                }
                if (dir.y != 0f) {
                    node.stretch(0f, dir.y, maxStretchAmount, maxStretchAmount);
                }
            }
        };
        scrollingThing.addView(new MyPositionReporter(this));
        layout.addView(scrollingThing);

        setContentView(layout);
    }

    static class MyPositionReporter extends TextView implements RenderNode.PositionUpdateListener {
        RenderNode mNode;
        int mCurrentCount = 0;
        int mTranslateY = 0;
        Rect mPosition = new Rect();
        float mWidth = 0f;
        float mHeight = 0f;
        RectF mMappedBounds = new RectF();
        float mStretchX = 0.0f;
        float mStretchY = 0.0f;
        float mStretchMaxX = 0.0f;
        float mStretchMaxY = 0.0f;

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

        void updateText() {
            String posText =
              "%d: Position %s, stretch width %f, height %f, vec %f,%f, amountX %f amountY %f mappedBounds %s";
            setText(String.format(posText,
                    mCurrentCount, mPosition.toShortString(), mWidth, mHeight,
                    mStretchX, mStretchY, mStretchMaxX, mStretchMaxY,
                    mMappedBounds.toShortString()));
        }

        @Override
        public void positionChanged(long frameNumber, int left, int top, int right, int bottom) {
            getHandler().postAtFrontOfQueue(() -> {
                mCurrentCount++;
                mPosition.set(left, top, right, bottom);
                updateText();
            });
        }

        @Override
        public void applyStretch(long frameNumber, float width, float height,
                float vecX, float vecY,
                float maxStretchX, float maxStretchY, float childRelativeLeft,
                float childRelativeTop, float childRelativeRight, float childRelativeBottom) {
            getHandler().postAtFrontOfQueue(() -> {
                mWidth = width;
                mHeight = height;
                mStretchX = vecX;
                mStretchY = vecY;
                mStretchMaxX = maxStretchX;
                mStretchMaxY = maxStretchY;
                mMappedBounds.set(childRelativeLeft, childRelativeTop, childRelativeRight,
                        childRelativeBottom);
                updateText();
            });
        }

        @Override
        public void positionLost(long frameNumber) {
            getHandler().postAtFrontOfQueue(() -> {
                mCurrentCount++;
                setText(mCurrentCount + " No position");
            });
        }
    }
}
