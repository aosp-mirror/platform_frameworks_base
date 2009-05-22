/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.gesture;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.AccelerateDecelerateInterpolator;
import com.android.internal.R;

import java.util.ArrayList;

/**
 * A (transparent) overlay for gesture input that can be placed on top of other
 * widgets.
 *
 * @attr ref android.R.styleable#GestureOverlayView_gestureStrokeWidth
 * @attr ref android.R.styleable#GestureOverlayView_gestureColor
 * @attr ref android.R.styleable#GestureOverlayView_uncertainGestureColor
 * @attr ref android.R.styleable#GestureOverlayView_fadeDuration
 * @attr ref android.R.styleable#GestureOverlayView_fadeOffset
 */
public class GestureOverlayView extends View {
    private static final int TRANSPARENT_BACKGROUND = 0x00000000;
    private static final boolean GESTURE_RENDERING_ANTIALIAS = true;
    private static final boolean DITHER_FLAG = true;

    private Paint mGesturePaint;

    private final Paint mBitmapPaint = new Paint(Paint.DITHER_FLAG);
    private Bitmap mBitmap;
    private Canvas mBitmapCanvas;

    private long mFadeDuration = 300;
    private long mFadeOffset = 300;
    private long mFadingStart;

    private float mGestureStroke = 12.0f;
    private int mCertainGestureColor = 0xFFFFFF00;
    private int mUncertainGestureColor = 0x3CFFFF00;
    private int mInvalidateExtraBorder = 10;

    // for rendering immediate ink feedback
    private final Rect mInvalidRect = new Rect();
    private final Path mPath = new Path();

    private float mX;
    private float mY;
    
    private float mCurveEndX;
    private float mCurveEndY;

    // current gesture
    private Gesture mCurrentGesture = null;

    // TODO: Make this a list of WeakReferences
    private final ArrayList<OnGestureListener> mOnGestureListeners =
            new ArrayList<OnGestureListener>();
    private final ArrayList<GesturePoint> mPointBuffer = new ArrayList<GesturePoint>(100);

    // fading out effect
    private boolean mIsFadingOut = false;
    private float mFadingAlpha = 1;
    private final AccelerateDecelerateInterpolator mInterpolator =
            new AccelerateDecelerateInterpolator();

    private final Runnable mFadingOut = new Runnable() {
        public void run() {
            if (mIsFadingOut) {
                final long now = AnimationUtils.currentAnimationTimeMillis();
                final long duration = now - mFadingStart;

                if (duration > mFadeDuration) {
                    mIsFadingOut = false;
                    mPath.rewind();
                    mCurrentGesture = null;
                    mBitmap.eraseColor(TRANSPARENT_BACKGROUND);
                } else {
                    float interpolatedTime = Math.max(0.0f,
                            Math.min(1.0f, duration / (float) mFadeDuration));
                    mFadingAlpha = 1.0f - mInterpolator.getInterpolation(interpolatedTime);
                    postDelayed(this, 16);
                }
                invalidate();
            }
        }
    };

    public GestureOverlayView(Context context) {
        super(context);
        init();
    }

    public GestureOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.gestureOverlayViewStyle);
    }

    public GestureOverlayView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.GestureOverlayView, defStyle, 0);

        mGestureStroke = a.getFloat(R.styleable.GestureOverlayView_gestureStrokeWidth,
                mGestureStroke);
        mInvalidateExtraBorder = Math.max(1, ((int) mGestureStroke) - 1);
        mCertainGestureColor = a.getColor(R.styleable.GestureOverlayView_gestureColor,
                mCertainGestureColor);
        mUncertainGestureColor = a.getColor(R.styleable.GestureOverlayView_uncertainGestureColor,
                mUncertainGestureColor);
        mFadeDuration = a.getInt(R.styleable.GestureOverlayView_fadeDuration, (int) mFadeDuration);
        mFadeOffset = a.getInt(R.styleable.GestureOverlayView_fadeOffset, (int) mFadeOffset);

        a.recycle();

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
    public void setGestureDrawingColor(int color) {
        mGesturePaint.setColor(color);
        if (mCurrentGesture != null) {
            mBitmap.eraseColor(TRANSPARENT_BACKGROUND);
            mCurrentGesture.draw(mBitmapCanvas, mGesturePaint);
        }
        invalidate();
    }

    public void setGestureColor(int color) {
        mCertainGestureColor = color;
    }

    public void setUncertainGestureColor(int color) {
        mUncertainGestureColor = color;
    }

    public int getUncertainGestureColor() {
        return mUncertainGestureColor;
    }

    public int getGestureColor() {
        return mCertainGestureColor;
    }

    public float getGestureStroke() {
        return mGestureStroke;
    }

    public void setGestureStroke(float gestureStroke) {
        mGestureStroke = gestureStroke;
        mInvalidateExtraBorder = Math.max(1, ((int) mGestureStroke) - 1);
        mGesturePaint.setStrokeWidth(mGestureStroke);
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
        gesturePaint.setColor(mCertainGestureColor);
        gesturePaint.setStyle(Paint.Style.STROKE);
        gesturePaint.setStrokeJoin(Paint.Join.ROUND);
        gesturePaint.setStrokeCap(Paint.Cap.ROUND);
        gesturePaint.setStrokeWidth(mGestureStroke);
        gesturePaint.setDither(DITHER_FLAG);
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

    public void addOnGestureListener(OnGestureListener listener) {
        mOnGestureListeners.add(listener);
    }

    public void removeOnGestureListener(OnGestureListener listener) {
        mOnGestureListeners.remove(listener);
    }

    public void removeAllOnGestureListeners() {
        mOnGestureListeners.clear();
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
        canvas.drawPath(mPath, mGesturePaint);
    }

    /**
     * Clear up the overlay
     * 
     * @param fadeOut whether the gesture on the overlay should fade out
     *        gradually or disappear immediately
     */
    public void clear(boolean fadeOut) {
        if (fadeOut) {
            mFadingAlpha = 1.0f;
            mIsFadingOut = true;
            removeCallbacks(mFadingOut);
            mFadingStart = AnimationUtils.currentAnimationTimeMillis() + mFadeOffset;
            postDelayed(mFadingOut, mFadeOffset);
        } else {
            mPath.rewind();
            mCurrentGesture = null;
            if (mBitmap != null) {
                mBitmap.eraseColor(TRANSPARENT_BACKGROUND);
                invalidate();
            }
        }
    }

    public void cancelFadingOut() {
        mIsFadingOut = false;
        removeCallbacks(mFadingOut);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return true;
        }

        processEvent(event);

        return true;
    }

    public void processEvent(MotionEvent event) {
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
                touchUp(event, false);
                invalidate();
                break;
            case MotionEvent.ACTION_CANCEL:
                touchUp(event, true);
                invalidate();
                break;
        }
    }

    private Rect touchStart(MotionEvent event) {
        // pass the event to handlers
        final ArrayList<OnGestureListener> listeners = mOnGestureListeners;
        final int count = listeners.size();
        for (int i = 0; i < count; i++) {
            OnGestureListener listener = listeners.get(i);
            listener.onGestureStarted(this, event);
        }

        // if there is fading out going on, stop it.
        if (mIsFadingOut) {
            mIsFadingOut = false;
            removeCallbacks(mFadingOut);
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

        mPointBuffer.add(new GesturePoint(x, y, event.getEventTime()));

        mPath.rewind();
        mPath.moveTo(x, y);

        mInvalidRect.set((int) x - mInvalidateExtraBorder, (int) y - mInvalidateExtraBorder,
                (int) x + mInvalidateExtraBorder, (int) y + mInvalidateExtraBorder);
        
        mCurveEndX = x;
        mCurveEndY = y;
        
        return mInvalidRect;
    }

    private Rect touchMove(MotionEvent event) {
        Rect areaToRefresh = null;
        
        final float x = event.getX();
        final float y = event.getY();

        final float previousX = mX;
        final float previousY = mY;

        final float dx = Math.abs(x - previousX);
        final float dy = Math.abs(y - previousY);
        
        if (dx >= GestureStroke.TOUCH_TOLERANCE || dy >= GestureStroke.TOUCH_TOLERANCE) {
            areaToRefresh = mInvalidRect;

            // start with the curve end
            areaToRefresh.set(
                    (int) mCurveEndX - mInvalidateExtraBorder,
                    (int) mCurveEndY - mInvalidateExtraBorder,
                    (int) mCurveEndX + mInvalidateExtraBorder,
                    (int) mCurveEndY + mInvalidateExtraBorder);
            
            mCurveEndX = (x + previousX) / 2;
            mCurveEndY = (y + previousY) / 2;

            mPath.quadTo(previousX, previousY, mCurveEndX, mCurveEndY);
            
            // union with the control point of the new curve
            areaToRefresh.union(
                    (int) previousX - mInvalidateExtraBorder,
                    (int) previousY - mInvalidateExtraBorder,
                    (int) previousX + mInvalidateExtraBorder,
                    (int) previousY + mInvalidateExtraBorder);
            
            // union with the end point of the new curve
            areaToRefresh.union(
                    (int) mCurveEndX - mInvalidateExtraBorder,
                    (int) mCurveEndY - mInvalidateExtraBorder,
                    (int) mCurveEndX + mInvalidateExtraBorder,
                    (int) mCurveEndY + mInvalidateExtraBorder);

            mX = x;
            mY = y;
        }

        mPointBuffer.add(new GesturePoint(x, y, event.getEventTime()));

        // pass the event to handlers
        final ArrayList<OnGestureListener> listeners = mOnGestureListeners;
        final int count = listeners.size();
        for (int i = 0; i < count; i++) {
            listeners.get(i).onGesture(this, event);
        }
        
        return areaToRefresh;
    }

    private void touchUp(MotionEvent event, boolean cancel) {
        // add the stroke to the current gesture
        mCurrentGesture.addStroke(new GestureStroke(mPointBuffer));

        // add the stroke to the double buffer
        mBitmapCanvas.drawPath(mPath, mGesturePaint);

        if (!cancel) {
            // pass the event to handlers
            final ArrayList<OnGestureListener> listeners = mOnGestureListeners;
            final int count = listeners.size();
            for (int i = 0; i < count; i++) {
                listeners.get(i).onGestureEnded(this, event);
            }
        } else {
            // pass the event to handlers
            final ArrayList<OnGestureListener> listeners = mOnGestureListeners;
            final int count = listeners.size();
            for (int i = 0; i < count; i++) {
                listeners.get(i).onGestureCancelled(this, event);
            }
        }

        mPath.rewind();
        mPointBuffer.clear();
    }

    /**
     * An interface for processing gesture events
     */
    public static interface OnGestureListener {
        void onGestureStarted(GestureOverlayView overlay, MotionEvent event);

        void onGesture(GestureOverlayView overlay, MotionEvent event);

        void onGestureEnded(GestureOverlayView overlay, MotionEvent event);

        void onGestureCancelled(GestureOverlayView overlay, MotionEvent event);
    }
}
