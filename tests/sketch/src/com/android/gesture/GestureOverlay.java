/*
 * Copyright (C) 2008-2009 The Android Open Source Project
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

package com.android.gesture;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;

/**
 * A (transparent) overlay for gesture input that can be placed on top of other
 * widgets. The view can also be opaque.
 */

public class GestureOverlay extends View {

    static final float TOUCH_TOLERANCE = 3;

    private static final int TRANSPARENT_BACKGROUND = Color.argb(0, 0, 0, 0);

    private static final float FADING_ALPHA_CHANGE = 0.03f;

    private static final long FADING_REFRESHING_RATE = 100;

    private static final int GESTURE_STROKE_WIDTH = 12;

    private static final boolean GESTURE_RENDERING_ANTIALIAS = true;

    private static final int BLUR_MASK_RADIUS = 1;

    public static final int DEFAULT_GESTURE_COLOR = Color.argb(255, 255, 255, 0);

    // double buffering
    private Paint mGesturePaint;

    private Bitmap mBitmap; // with transparent background

    private Canvas mBitmapCanvas;

    // for rendering immediate ink feedback
    private Path mPath;

    private float mX;

    private float mY;

    // current gesture
    private Gesture mCurrentGesture = null;

    // gesture event handlers
    ArrayList<GestureListener> mGestureListeners = new ArrayList<GestureListener>();

    private ArrayList<GesturePoint> mPointBuffer = null;

    // fading out effect
    private boolean mIsFadingOut = false;

    private float mFadingAlpha = 1;

    private Handler mHandler = new Handler();
    
    private Paint mBitmapPaint = new Paint(Paint.DITHER_FLAG);

    private Runnable mFadingOut = new Runnable() {
        public void run() {
            if (mIsFadingOut) {
                mFadingAlpha -= FADING_ALPHA_CHANGE;
                if (mFadingAlpha <= 0) {
                    mIsFadingOut = false;
                    mPath = null;
                    mCurrentGesture = null;
                    mBitmap.eraseColor(TRANSPARENT_BACKGROUND);
                } else {
                    mHandler.postDelayed(this, FADING_REFRESHING_RATE);
                }
                invalidate();
            }
        }
    };

    public GestureOverlay(Context context) {
        super(context);
        init();
    }

    public GestureOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ArrayList<GesturePoint> getCurrentStroke() {
        return mPointBuffer;
    }

    public Gesture getCurrentGesture() {
        return mCurrentGesture;
    }

    /**
     * Set Gesture color
     * 
     * @param color
     */
    public void setGestureColor(int color) {
        mGesturePaint.setColor(color);
        if (mCurrentGesture != null) {
            mBitmap.eraseColor(TRANSPARENT_BACKGROUND);
            mCurrentGesture.draw(mBitmapCanvas, mGesturePaint);
        }
    }

    /**
     * Set the gesture to be shown in the view
     * 
     * @param gesture
     */
    public void setCurrentGesture(Gesture gesture) {
        if (mCurrentGesture != null) {
            clear(false);
        }

        mCurrentGesture = gesture;

        if (gesture != null) {
            if (mBitmapCanvas != null) {
                gesture.draw(mBitmapCanvas, mGesturePaint);
                invalidate();
            }
        }
    }

    private void init() {
        mGesturePaint = new Paint();
        mGesturePaint.setAntiAlias(GESTURE_RENDERING_ANTIALIAS);
        mGesturePaint.setColor(DEFAULT_GESTURE_COLOR);
        mGesturePaint.setStyle(Paint.Style.STROKE);
        mGesturePaint.setStrokeJoin(Paint.Join.ROUND);
        mGesturePaint.setStrokeCap(Paint.Cap.ROUND);
        mGesturePaint.setStrokeWidth(GESTURE_STROKE_WIDTH);
        mGesturePaint
                .setMaskFilter(new BlurMaskFilter(BLUR_MASK_RADIUS, BlurMaskFilter.Blur.NORMAL));

        mPath = null;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) {
            return;
        }
        int targetWidth = width > oldWidth ? width : oldWidth;
        int targetHeight = height > oldHeight ? height : oldHeight;
        mBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        mBitmapCanvas = new Canvas(mBitmap);
        mBitmapCanvas.drawColor(TRANSPARENT_BACKGROUND);
        if (mCurrentGesture != null) {
            mCurrentGesture.draw(mBitmapCanvas, mGesturePaint);
        }
    }

    public void addGestureListener(GestureListener listener) {
        mGestureListeners.add(listener);
    }

    public void removeGestureListener(GestureListener listener) {
        mGestureListeners.remove(listener);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // draw double buffer
        if (mIsFadingOut) {
            mBitmapPaint.setAlpha((int) (255 * mFadingAlpha));
            canvas.drawBitmap(mBitmap, 0, 0, mBitmapPaint);
        } else {
            mBitmapPaint.setAlpha(255);
            canvas.drawBitmap(mBitmap, 0, 0, mBitmapPaint);
        }

        // draw the current stroke
        if (mPath != null) {
            canvas.drawPath(mPath, mGesturePaint);
        }
    }

    /**
     * Clear up the overlay
     * 
     * @param fadeOut whether the gesture on the overlay should fade out
     *            gradually or disappear immediately
     */
    public void clear(boolean fadeOut) {
        if (fadeOut) {
            mFadingAlpha = 1;
            mIsFadingOut = true;
            mHandler.removeCallbacks(mFadingOut);
            mHandler.postDelayed(mFadingOut, FADING_REFRESHING_RATE);
        } else {
            mPath = null;
            mCurrentGesture = null;
            if (mBitmap != null) {
                mBitmap.eraseColor(TRANSPARENT_BACKGROUND);
                invalidate();
            }
        }
    }
    
    public void cancelFadingOut() {
        mIsFadingOut = false;
        mHandler.removeCallbacks(mFadingOut);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {

        if (!isEnabled()) {
            return true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touch_start(event);
                invalidate();
                break;
            case MotionEvent.ACTION_MOVE:
                touch_move(event);
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
                touch_up(event);
                invalidate();
                break;
        }

        return true;
    }

    private void touch_start(MotionEvent event) {
        // pass the event to handlers
        ArrayList<GestureListener> listeners = mGestureListeners;
        int count = listeners.size();
        for (int i = 0; i < count; i++) {
            GestureListener listener = listeners.get(i);
            listener.onStartGesture(this, event);
        }
        
        // if there is fading out going on, stop it.
        if (mIsFadingOut) {
            mIsFadingOut = false;
            mHandler.removeCallbacks(mFadingOut);
            mBitmap.eraseColor(TRANSPARENT_BACKGROUND);
            mCurrentGesture = null;
        }

        float x = event.getX();
        float y = event.getY();

        mX = x;
        mY = y;

        if (mCurrentGesture == null) {
            mCurrentGesture = new Gesture();
        }

        mPointBuffer = new ArrayList<GesturePoint>();
        mPointBuffer.add(new GesturePoint(x, y, event.getEventTime()));

        mPath = new Path();
        mPath.moveTo(x, y);
    }

    private void touch_move(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        float dx = Math.abs(x - mX);
        float dy = Math.abs(y - mY);
        if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
            mPath.quadTo(mX, mY, (x + mX) / 2, (y + mY) / 2);
            mX = x;
            mY = y;
        }

        mPointBuffer.add(new GesturePoint(x, y, event.getEventTime()));

        // pass the event to handlers
        ArrayList<GestureListener> listeners = mGestureListeners;
        int count = listeners.size();
        for (int i = 0; i < count; i++) {
            GestureListener listener = listeners.get(i);
            listener.onGesture(this, event);
        }
    }

    private void touch_up(MotionEvent event) {
        // add the stroke to the current gesture
        mCurrentGesture.addStroke(new GestureStroke(mPointBuffer));
        mPointBuffer = null;

        // add the stroke to the double buffer
        mGesturePaint.setDither(true);
        mBitmapCanvas.drawPath(mPath, mGesturePaint);
        mGesturePaint.setDither(false);
        mPath = null;

        // pass the event to handlers
        ArrayList<GestureListener> listeners = mGestureListeners;
        int count = listeners.size();
        for (int i = 0; i < count; i++) {
            GestureListener listener = listeners.get(i);
            listener.onFinishGesture(this, event);
        }
    }

}
