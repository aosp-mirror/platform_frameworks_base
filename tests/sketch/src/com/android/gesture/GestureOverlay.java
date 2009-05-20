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
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
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

    // TODO: Move all these values into XML attributes
    private static final int TRANSPARENT_BACKGROUND = 0x00000000;

    private static final float FADING_ALPHA_CHANGE = 0.03f;
    private static final long FADING_REFRESHING_RATE = 100;

    private static final int GESTURE_STROKE_WIDTH = 12;
    private static final boolean GESTURE_RENDERING_ANTIALIAS = true;

    private static final boolean DITHER_FLAG = true;

    public static final int DEFAULT_GESTURE_COLOR = 0xFFFFFF00;

    private static final int REFRESH_RANGE = 10;

    private static final BlurMaskFilter BLUR_MASK_FILTER =
            new BlurMaskFilter(1, BlurMaskFilter.Blur.NORMAL);
    

    // double buffering
    private Paint mGesturePaint;

    private final Paint mBitmapPaint = new Paint(Paint.DITHER_FLAG);
    private Bitmap mBitmap; // with transparent background
    private Canvas mBitmapCanvas;

    // for rendering immediate ink feedback
    private Rect mInvalidRect = new Rect();

    private Path mPath;

    private float mX;
    private float mY;
    
    private float mCurveEndX;
    private float mCurveEndY;

    // current gesture
    private Gesture mCurrentGesture = null;

    // TODO: Make this a list of WeakReferences
    private final ArrayList<GestureListener> mGestureListeners = new ArrayList<GestureListener>();
    private ArrayList<GesturePoint> mPointBuffer = null;

    // fading out effect
    private boolean mIsFadingOut = false;
    private float mFadingAlpha = 1;

    private Handler mHandler = new Handler();

    private final Runnable mFadingOut = new Runnable() {
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

        final Paint gesturePaint = mGesturePaint;
        gesturePaint.setAntiAlias(GESTURE_RENDERING_ANTIALIAS);
        gesturePaint.setColor(DEFAULT_GESTURE_COLOR);
        gesturePaint.setStyle(Paint.Style.STROKE);
        gesturePaint.setStrokeJoin(Paint.Join.ROUND);
        gesturePaint.setStrokeCap(Paint.Cap.ROUND);
        gesturePaint.setStrokeWidth(GESTURE_STROKE_WIDTH);
        gesturePaint.setDither(DITHER_FLAG);

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

        if (mBitmap != null) mBitmap.recycle();

        mBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        if (mBitmapCanvas != null) {
            mBitmapCanvas.setBitmap(mBitmap);
        } else {
            mBitmapCanvas = new Canvas(mBitmap);
        }
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
                Rect rect = touchStart(event);
                invalidate(rect);
                break;
            case MotionEvent.ACTION_MOVE:
                rect = touchMove(event);
                if (rect != null) {
                    invalidate(rect);
                }
                break;
            case MotionEvent.ACTION_UP:
                touchUp(event);
                invalidate();
                break;
        }

        return true;
    }

    private Rect touchStart(MotionEvent event) {
        // pass the event to handlers
        final ArrayList<GestureListener> listeners = mGestureListeners;
        final int count = listeners.size();
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

        mInvalidRect.set((int) x - REFRESH_RANGE, (int) y - REFRESH_RANGE,
                (int) x + REFRESH_RANGE, (int) y + REFRESH_RANGE);
        
        mCurveEndX = x;
        mCurveEndY = y;
        
        return mInvalidRect;
    }

    private Rect touchMove(MotionEvent event) {
        Rect areaToRefresh = null;
        
        float x = event.getX();
        float y = event.getY();

        float dx = Math.abs(x - mX);
        float dy = Math.abs(y - mY);
        
        if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
            
            // start with the curve end
            mInvalidRect.set((int) mCurveEndX - REFRESH_RANGE, (int) mCurveEndY - REFRESH_RANGE,
                    (int) mCurveEndX + REFRESH_RANGE, (int) mCurveEndY + REFRESH_RANGE);
            
            mCurveEndX  = (x + mX) / 2;
            mCurveEndY = (y + mY) / 2;
            mPath.quadTo(mX, mY, mCurveEndX, mCurveEndY);
            
            // union with the control point of the new curve
            mInvalidRect.union((int) mX - REFRESH_RANGE, (int) mY - REFRESH_RANGE,
                    (int) mX + REFRESH_RANGE, (int) mY + REFRESH_RANGE);
            
            // union with the end point of the new curve
            mInvalidRect.union((int) mCurveEndX - REFRESH_RANGE, (int) mCurveEndY - REFRESH_RANGE,
                    (int) mCurveEndX + REFRESH_RANGE, (int) mCurveEndY + REFRESH_RANGE);

            areaToRefresh = mInvalidRect;
            
            mX = x;
            mY = y;
        }
        

        mPointBuffer.add(new GesturePoint(x, y, event.getEventTime()));

        // pass the event to handlers
        final ArrayList<GestureListener> listeners = mGestureListeners;
        final int count = listeners.size();
        for (int i = 0; i < count; i++) {
            listeners.get(i).onGesture(this, event);
        }
        
        return areaToRefresh;
    }

    private void touchUp(MotionEvent event) {
        // add the stroke to the current gesture
        mCurrentGesture.addStroke(new GestureStroke(mPointBuffer));

        // add the stroke to the double buffer
        mGesturePaint.setMaskFilter(BLUR_MASK_FILTER);
        mBitmapCanvas.drawPath(mPath, mGesturePaint);
        mGesturePaint.setMaskFilter(null);
        
        // pass the event to handlers
        final ArrayList<GestureListener> listeners = mGestureListeners;
        final int count = listeners.size();
        for (int i = 0; i < count; i++) {
            listeners.get(i).onFinishGesture(this, event);
        }
        
        mPath = null;        
        mPointBuffer = null;
    }

}
