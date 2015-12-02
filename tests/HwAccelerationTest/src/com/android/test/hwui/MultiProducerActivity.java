/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.DisplayListCanvas;
import android.view.ThreadedRenderer;
import android.view.RenderNode;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AbsoluteLayout;
import android.widget.AbsoluteLayout.LayoutParams;

public class MultiProducerActivity extends Activity implements OnClickListener {
    private static final int DURATION = 800;
    private View mBackgroundTarget = null;
    private View mFrameTarget = null;
    private View mContent = null;
    // The width & height of our "output drawing".
    private final int WIDTH = 900;
    private final int HEIGHT = 600;
    // A border width around the drawing.
    private static final int BORDER_WIDTH = 20;
    // The Gap between the content and the frame which should get filled on the right and bottom
    // side by the backdrop.
    final int CONTENT_GAP = 100;

    // For debug purposes - disable drawing of frame / background.
    private final boolean USE_FRAME = true;
    private final boolean USE_BACK = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // To make things simple - we do a quick and dirty absolute layout.
        final AbsoluteLayout layout = new AbsoluteLayout(this);

        // Create the outer frame
        if (USE_FRAME) {
            mFrameTarget = new View(this);
            LayoutParams frameLP = new LayoutParams(WIDTH, HEIGHT, 0, 0);
            layout.addView(mFrameTarget, frameLP);
        }

        // Create the background which fills the gap between content and frame.
        if (USE_BACK) {
            mBackgroundTarget = new View(this);
            LayoutParams backgroundLP = new LayoutParams(
                    WIDTH - 2 * BORDER_WIDTH, HEIGHT - 2 * BORDER_WIDTH,
                    BORDER_WIDTH, BORDER_WIDTH);
            layout.addView(mBackgroundTarget, backgroundLP);
        }

        // Create the content
        // Note: We reduce the size by CONTENT_GAP pixels on right and bottom, so that they get
        // drawn by the backdrop.
        mContent = new View(this);
        mContent.setBackground(new ColorPulse(0xFFF44336, 0xFF9C27B0, null));
        mContent.setOnClickListener(this);
        LayoutParams contentLP = new LayoutParams(WIDTH - 2 * BORDER_WIDTH - CONTENT_GAP,
                HEIGHT - 2 * BORDER_WIDTH - CONTENT_GAP, BORDER_WIDTH, BORDER_WIDTH);
        layout.addView(mContent, contentLP);

        setContentView(layout);
    }

    @Override
    protected void onStart() {
        super.onStart();
        View view = mBackgroundTarget != null ? mBackgroundTarget : mFrameTarget;
        if (view != null) {
            view.post(mSetup);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        View view = mBackgroundTarget != null ? mBackgroundTarget : mFrameTarget;
        if (view != null) {
            view.removeCallbacks(mSetup);
        }
        if (mBgRenderer != null) {
            mBgRenderer.destroy();
            mBgRenderer = null;
        }
    }

    @Override
    public void onClick(View view) {
        sBlockThread.run();
    }

    private Runnable mSetup = new Runnable() {
        @Override
        public void run() {
            View view = mBackgroundTarget != null ? mBackgroundTarget : mFrameTarget;
            if (view == null) {
                view.postDelayed(mSetup, 50);
            }
            ThreadedRenderer renderer = view.getHardwareRenderer();
            if (renderer == null || view.getWidth() == 0) {
                view.postDelayed(mSetup, 50);
            }
            ThreadedRenderer threaded = (ThreadedRenderer) renderer;

            mBgRenderer = new FakeFrame(threaded,mFrameTarget, mBackgroundTarget);
            mBgRenderer.start();
        }
    };

    private FakeFrame mBgRenderer;
    private class FakeFrame extends Thread {
        ThreadedRenderer mRenderer;
        volatile boolean mRunning = true;
        View mTargetFrame;
        View mTargetBack;
        Drawable mFrameContent;
        Drawable mBackContent;
        // The Z value where to place this.
        int mZFrame;
        int mZBack;
        String mRenderNodeName;

        FakeFrame(ThreadedRenderer renderer, View targetFrame, View targetBack) {
            mRenderer = renderer;
            mTargetFrame = targetFrame;

            mTargetBack = targetBack;
            mFrameContent = new ColorPulse(0xFF101010, 0xFF707070, new Rect(0, 0, WIDTH, HEIGHT));
            mBackContent = new ColorPulse(0xFF909090, 0xFFe0e0e0, null);
        }

        @Override
        public void run() {
            Rect currentFrameBounds = new Rect();
            Rect currentBackBounds = new Rect();
            Rect newBounds = new Rect();
            int[] surfaceOrigin = new int[2];
            RenderNode nodeFrame = null;
            RenderNode nodeBack = null;

            // Since we are overriding the window painting logic we need to at least fill the
            // surface with some window content (otherwise the world will go black).
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
            }

            if (mTargetBack != null) {
                nodeBack = RenderNode.create("FakeBackdrop", null);
                nodeBack.setClipToBounds(true);
                mRenderer.addRenderNode(nodeBack, true);
            }

            if (mTargetFrame != null) {
                nodeFrame = RenderNode.create("FakeFrame", null);
                nodeFrame.setClipToBounds(true);
                mRenderer.addRenderNode(nodeFrame, false);
            }

            while (mRunning) {
                // Get the surface position to draw to within our surface.
                surfaceOrigin[0] = 0;
                surfaceOrigin[1] = 0;
                // This call should be done while the rendernode's displaylist is produced.
                // For simplicity of this test we do this before we kick off the draw.
                mContent.getLocationInSurface(surfaceOrigin);
                mRenderer.setContentDrawBounds(surfaceOrigin[0], surfaceOrigin[1],
                        surfaceOrigin[0] + mContent.getWidth(),
                        surfaceOrigin[1] + mContent.getHeight());
                // Determine new position for frame.
                if (nodeFrame != null) {
                    surfaceOrigin[0] = 0;
                    surfaceOrigin[1] = 0;
                    mTargetFrame.getLocationInSurface(surfaceOrigin);
                    newBounds.set(surfaceOrigin[0], surfaceOrigin[1],
                            surfaceOrigin[0] + mTargetFrame.getWidth(),
                            surfaceOrigin[1] + mTargetFrame.getHeight());
                    if (!currentFrameBounds.equals(newBounds)) {
                        currentFrameBounds.set(newBounds);
                        nodeFrame.setLeftTopRightBottom(currentFrameBounds.left,
                                currentFrameBounds.top,
                                currentFrameBounds.right, currentFrameBounds.bottom);
                    }

                    // Draw frame
                    DisplayListCanvas canvas = nodeFrame.start(currentFrameBounds.width(),
                            currentFrameBounds.height());
                    mFrameContent.draw(canvas);
                    nodeFrame.end(canvas);
                }

                // Determine new position for backdrop
                if (nodeBack != null) {
                    surfaceOrigin[0] = 0;
                    surfaceOrigin[1] = 0;
                    mTargetBack.getLocationInSurface(surfaceOrigin);
                    newBounds.set(surfaceOrigin[0], surfaceOrigin[1],
                            surfaceOrigin[0] + mTargetBack.getWidth(),
                            surfaceOrigin[1] + mTargetBack.getHeight());
                    if (!currentBackBounds.equals(newBounds)) {
                        currentBackBounds.set(newBounds);
                        nodeBack.setLeftTopRightBottom(currentBackBounds.left,
                                currentBackBounds.top,
                                currentBackBounds.right, currentBackBounds.bottom);
                    }

                    // Draw Backdrop
                    DisplayListCanvas canvas = nodeBack.start(currentBackBounds.width(),
                            currentBackBounds.height());
                    mBackContent.draw(canvas);
                    nodeBack.end(canvas);
                }

                // we need to only render one guy - the rest will happen automatically (I think).
                if (nodeFrame != null) {
                    mRenderer.drawRenderNode(nodeFrame);
                }
                if (nodeBack != null) {
                    mRenderer.drawRenderNode(nodeBack);
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {}
            }
            if (nodeFrame != null) {
                mRenderer.removeRenderNode(nodeFrame);
            }
            if (nodeBack != null) {
                mRenderer.removeRenderNode(nodeBack);
            }
        }

        public void destroy() {
            mRunning = false;
            try {
                join();
            } catch (InterruptedException e) {}
        }
    }

    private final static Runnable sBlockThread = new Runnable() {
        @Override
        public void run() {
            try {
                Thread.sleep(DURATION);
            } catch (InterruptedException e) {
            }
        }
    };

    static class ColorPulse extends Drawable {

        private int mColorStart;
        private int mColorEnd;
        private int mStep;
        private Rect mRect;
        private Paint mPaint = new Paint();

        public ColorPulse(int color1, int color2, Rect rect) {
            mColorStart = color1;
            mColorEnd = color2;
            if (rect != null) {
                mRect = new Rect(rect.left + BORDER_WIDTH / 2, rect.top + BORDER_WIDTH / 2,
                                 rect.right - BORDER_WIDTH / 2, rect.bottom - BORDER_WIDTH / 2);
            }
        }

        static int evaluate(float fraction, int startInt, int endInt) {
            int startA = (startInt >> 24) & 0xff;
            int startR = (startInt >> 16) & 0xff;
            int startG = (startInt >> 8) & 0xff;
            int startB = startInt & 0xff;

            int endA = (endInt >> 24) & 0xff;
            int endR = (endInt >> 16) & 0xff;
            int endG = (endInt >> 8) & 0xff;
            int endB = endInt & 0xff;

            return (int)((startA + (int)(fraction * (endA - startA))) << 24) |
                    (int)((startR + (int)(fraction * (endR - startR))) << 16) |
                    (int)((startG + (int)(fraction * (endG - startG))) << 8) |
                    (int)((startB + (int)(fraction * (endB - startB))));
        }

        @Override
        public void draw(Canvas canvas) {
            float frac = mStep / 50.0f;
            int color = evaluate(frac, mColorStart, mColorEnd);
            if (mRect != null && !mRect.isEmpty()) {
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(BORDER_WIDTH);
                mPaint.setColor(color);
                canvas.drawRect(mRect, mPaint);
            } else {
                canvas.drawColor(color);
            }

            mStep++;
            if (mStep >= 50) {
                mStep = 0;
                int tmp = mColorStart;
                mColorStart = mColorEnd;
                mColorEnd = tmp;
            }
            invalidateSelf();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return mRect == null || mRect.isEmpty() ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
        }

    }
}

