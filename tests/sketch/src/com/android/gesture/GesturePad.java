/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * A view for rendering and processing gestures
 */

public class GesturePad extends View {

    public static final float TOUCH_TOLERANCE = 4;
    public static final int default_foreground = Color.argb(255, 255, 255, 0);
    private int         background = Color.argb(0, 0, 0, 0);
    private int         foreground = default_foreground;
    private int         uncertain_foreground = Color.argb(55, 255, 255, 0);
    private Bitmap      mBitmap;
    private Canvas      mCanvas;
    private Path        mPath;
    private Paint       mBitmapPaint;
    private Paint       mPaint;
    private Paint       mDebugPaint;
    private float       mX, mY;
    private boolean     mEnableInput = true; 
    private boolean     mEnableRendering = true;
    private boolean     mCacheGesture = true;
    private Gesture       mCurrentGesture = null;
    ArrayList<GestureListener> mGestureListeners = new ArrayList<GestureListener>();

    private boolean     mShouldFadingOut = true;
    private boolean     mIsFadingOut = false;
    private float       mFadingAlpha = 1;
    
    private boolean     reconstruct = false;
    
    private ArrayList<Path> debug = new ArrayList<Path>();
    private Handler mHandler = new Handler();
    
    private Runnable mFadingOut = new Runnable() {
      public void run() {
          mFadingAlpha -= 0.03f;
          if (mFadingAlpha <= 0) {
              mIsFadingOut = false;
              mPath.reset();
          } else {
              mHandler.postDelayed(this, 100);
          }
          invalidate();
      }
   };

    public GesturePad(Context context) {
        super(context);
        init();
    }
  
    public GesturePad(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public boolean isEnableRendering() {
        return this.mEnableRendering;
    }
    
    public Gesture getCurrentGesture() {
        return mCurrentGesture;
    }
    
    public Paint getPaint() {
        return mPaint;
    }
    
    public void setColor(int c) {
        this.foreground = c;
    }
    
    public void setFadingAlpha(float f) {
        mFadingAlpha = f;
    }
    
    public void setCurrentGesture(Gesture stk) {
        this.mCurrentGesture = stk;
        reconstruct = true;
    }
    
    private void init() {
        mDebugPaint = new Paint();
        mDebugPaint.setColor(Color.WHITE);
        mDebugPaint.setStrokeWidth(4);
        mDebugPaint.setAntiAlias(true);
        mDebugPaint.setStyle(Paint.Style.STROKE);
        
        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setDither(true);
        mPaint.setColor(foreground);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeWidth(12);
        
        mBitmapPaint = new Paint(Paint.DITHER_FLAG);
        mPath = new Path();
        
        reconstruct = false;
    }

    public void cacheGesture(boolean b) {
        mCacheGesture = b;
    }
      
    public void enableRendering(boolean b) {
        mEnableRendering = b;
    }
  
    
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // TODO Auto-generated method stub
        super.onSizeChanged(w, h, oldw, oldh);
        
        if (w <=0 || h <=0)
            return;
        
        int width = w>oldw? w : oldw;
        int height = h>oldh? h : oldh;
        Bitmap newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        mCanvas = new Canvas(newBitmap);
        
        if (mBitmap != null) {
            mCanvas.drawColor(background);
            mCanvas.drawBitmap(mBitmap, 0, 0, mBitmapPaint);
            mCanvas.drawPath(mPath, mPaint);
        }
        
        mBitmap = newBitmap;
    }

    public void addGestureListener(GestureListener l) {
        this.mGestureListeners.add(l);
    }
  
    public void removeGestureListener(GestureListener l) {
        this.mGestureListeners.remove(l);
    }
  
    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(background);
        
        if (mCacheGesture)
            canvas.drawBitmap(mBitmap, 0, 0, mBitmapPaint);
        
        if (mIsFadingOut) {
            int color = foreground;
            int alpha = (int)(Color.alpha(color) * mFadingAlpha);
            mPaint.setColor(Color.argb(alpha, 
                Color.red(color), 
                Color.green(color), 
                Color.blue(color)));
        } else if (mEnableRendering == false) {
            mPaint.setColor(uncertain_foreground);
        } else {
            mPaint.setColor(foreground);
        }
        
        if (reconstruct) {
            
            if (this.mCurrentGesture != null) {
                float xedge = 30;
                float yedge = 30;
                float w = this.getWidth() - 2 * xedge;
                float h = this.getHeight() - 2 * yedge;
                float sx =  w / this.mCurrentGesture.getBBX().width();
                float sy = h / mCurrentGesture.getBBX().height();
                float scale = sx>sy?sy:sx;
                convertFromStroke(mCurrentGesture);
                Matrix matrix = new Matrix();
                matrix.preTranslate(-mCurrentGesture.getBBX().centerX(), -mCurrentGesture.getBBX().centerY());
                matrix.postScale(scale, scale);
                matrix.postTranslate(this.getWidth()/2, this.getHeight()/2);
                this.mPath.transform(matrix);
            } else {
                mPath.reset();
            }
            
            reconstruct = false;
        }
        
        canvas.drawPath(mPath, mPaint);
        
        Iterator<Path> it = debug.iterator();
        while (it.hasNext()) {
            Path path = it.next();
            canvas.drawPath(path, mDebugPaint);
        }
    }
    
    public void clearDebugPath() {
        debug.clear();
    }
    
    public void addDebugPath(Path path) {
        debug.add(path);
    }
    
    public void addDebugPath(ArrayList<Path> paths) {
        debug.addAll(paths);
    }
    
    public void clear() {
        mPath = new Path();
        this.mCurrentGesture = null;
        mCanvas.drawColor(background);
        this.invalidate();
    }
    
    private void convertFromStroke(Gesture stk) {
        mPath = null;
        Iterator it = stk.getPoints().iterator();
        while (it.hasNext()) {
            PointF p = (PointF) it.next();
            if (mPath == null) {
                mPath = new Path();
                mPath.moveTo(p.x, p.y);
                mX = p.x;
                mY = p.y;
            } else {
                float dx = Math.abs(p.x - mX);
                float dy = Math.abs(p.y - mY);
                if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
                    mPath.quadTo(mX, mY, (p.x + mX)/2, (p.y + mY)/2);
                    mX = p.x;
                    mY = p.y;
                }
            }
        }
        mPath.lineTo(mX, mY);
    }
    
    public void setEnableInput(boolean b) {
        mEnableInput = b;
    }
    
    public boolean isEnableInput() {
        return mEnableInput;
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
      
        if(mEnableInput == false) 
            return true;
        
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
        mIsFadingOut = false;
        mHandler.removeCallbacks(mFadingOut);
      
        float x = event.getX();
        float y = event.getY();

        mCurrentGesture = new Gesture();
        mCurrentGesture.addPoint(x, y);
        
        mPath.reset();
        mPath.moveTo(x, y);
        mX = x;
        mY = y;
        
        Iterator<GestureListener> it = mGestureListeners.iterator();
        while (it.hasNext()) {
            it.next().onStartGesture(this, event);
        }
    }
    
    private void touch_move(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        float dx = Math.abs(x - mX);
        float dy = Math.abs(y - mY);
        if (dx >= TOUCH_TOLERANCE || dy >= TOUCH_TOLERANCE) {
            mPath.quadTo(mX, mY, (x + mX)/2, (y + mY)/2);
            mX = x;
            mY = y;
        }
        
        mCurrentGesture.addPoint(x, y);
        
        Iterator<GestureListener> it = mGestureListeners.iterator();
        while (it.hasNext()) {
            it.next().onGesture(this, event);
        }
    }
    
    public void setFadingOut(boolean b) {
        mShouldFadingOut = b;
        mIsFadingOut = false;
    }
    
    public boolean shouldFadingOut() {
        return mShouldFadingOut;
    }
    
    private void touch_up(MotionEvent event) {
        mPath.lineTo(mX, mY);
        
        if (mCacheGesture)
            mCanvas.drawPath(mPath, mPaint);
        
        // kill this so we don't double draw
        if (shouldFadingOut()) {
            mFadingAlpha = 1;
            mIsFadingOut = true;
            mHandler.removeCallbacks(mFadingOut);
            mHandler.postDelayed(mFadingOut, 100);
        }
        
        Iterator<GestureListener> it = mGestureListeners.iterator();
        while (it.hasNext()) {
            it.next().onFinishGesture(this, event);
        }
    }

}
