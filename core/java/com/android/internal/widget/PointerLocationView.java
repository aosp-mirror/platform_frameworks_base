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

package com.android.internal.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;

public class PointerLocationView extends View {
    public static class PointerState {
        private final ArrayList<Float> mXs = new ArrayList<Float>();
        private final ArrayList<Float> mYs = new ArrayList<Float>();
        private boolean mCurDown;
        private int mCurX;
        private int mCurY;
        private float mCurPressure;
        private float mCurSize;
        private int mCurWidth;
        private VelocityTracker mVelocity;
    }

    private final ViewConfiguration mVC;
    private final Paint mTextPaint;
    private final Paint mTextBackgroundPaint;
    private final Paint mTextLevelPaint;
    private final Paint mPaint;
    private final Paint mTargetPaint;
    private final Paint mPathPaint;
    private final FontMetricsInt mTextMetrics = new FontMetricsInt();
    private int mHeaderBottom;
    private boolean mCurDown;
    private int mCurNumPointers;
    private int mMaxNumPointers;
    private final ArrayList<PointerState> mPointers
             = new ArrayList<PointerState>();
    
    private boolean mPrintCoords = true;
    
    public PointerLocationView(Context c) {
        super(c);
        setFocusable(true);
        mVC = ViewConfiguration.get(c);
        mTextPaint = new Paint();
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(10
                * getResources().getDisplayMetrics().density);
        mTextPaint.setARGB(255, 0, 0, 0);
        mTextBackgroundPaint = new Paint();
        mTextBackgroundPaint.setAntiAlias(false);
        mTextBackgroundPaint.setARGB(128, 255, 255, 255);
        mTextLevelPaint = new Paint();
        mTextLevelPaint.setAntiAlias(false);
        mTextLevelPaint.setARGB(192, 255, 0, 0);
        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setARGB(255, 255, 255, 255);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(2);
        mTargetPaint = new Paint();
        mTargetPaint.setAntiAlias(false);
        mTargetPaint.setARGB(255, 0, 0, 192);
        mPathPaint = new Paint();
        mPathPaint.setAntiAlias(false);
        mPathPaint.setARGB(255, 0, 96, 255);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(1);
        
        PointerState ps = new PointerState();
        ps.mVelocity = VelocityTracker.obtain();
        mPointers.add(ps);
    }

    public void setPrintCoords(boolean state) {
        mPrintCoords = state;
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mTextPaint.getFontMetricsInt(mTextMetrics);
        mHeaderBottom = -mTextMetrics.ascent+mTextMetrics.descent+2;
        if (false) {
            Log.i("foo", "Metrics: ascent=" + mTextMetrics.ascent
                    + " descent=" + mTextMetrics.descent
                    + " leading=" + mTextMetrics.leading
                    + " top=" + mTextMetrics.top
                    + " bottom=" + mTextMetrics.bottom);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        synchronized (mPointers) {
            final int w = getWidth();
            final int itemW = w/7;
            final int base = -mTextMetrics.ascent+1;
            final int bottom = mHeaderBottom;
            
            final int NP = mPointers.size();
            
            if (NP > 0) {
                final PointerState ps = mPointers.get(0);
                canvas.drawRect(0, 0, itemW-1, bottom,mTextBackgroundPaint);
                canvas.drawText("P: " + mCurNumPointers + " / " + mMaxNumPointers,
                        1, base, mTextPaint);
                
                final int N = ps.mXs.size();
                if ((mCurDown && ps.mCurDown) || N == 0) {
                    canvas.drawRect(itemW, 0, (itemW * 2) - 1, bottom, mTextBackgroundPaint);
                    canvas.drawText("X: " + ps.mCurX, 1 + itemW, base, mTextPaint);
                    canvas.drawRect(itemW * 2, 0, (itemW * 3) - 1, bottom, mTextBackgroundPaint);
                    canvas.drawText("Y: " + ps.mCurY, 1 + itemW * 2, base, mTextPaint);
                } else {
                    float dx = ps.mXs.get(N-1) - ps.mXs.get(0);
                    float dy = ps.mYs.get(N-1) - ps.mYs.get(0);
                    canvas.drawRect(itemW, 0, (itemW * 2) - 1, bottom,
                            Math.abs(dx) < mVC.getScaledTouchSlop()
                            ? mTextBackgroundPaint : mTextLevelPaint);
                    canvas.drawText("dX: " + String.format("%.1f", dx), 1 + itemW, base, mTextPaint);
                    canvas.drawRect(itemW * 2, 0, (itemW * 3) - 1, bottom,
                            Math.abs(dy) < mVC.getScaledTouchSlop()
                            ? mTextBackgroundPaint : mTextLevelPaint);
                    canvas.drawText("dY: " + String.format("%.1f", dy), 1 + itemW * 2, base, mTextPaint);
                }
                
                canvas.drawRect(itemW * 3, 0, (itemW * 4) - 1, bottom, mTextBackgroundPaint);
                int velocity = ps.mVelocity == null ? 0 : (int) (ps.mVelocity.getXVelocity() * 1000);
                canvas.drawText("Xv: " + velocity, 1 + itemW * 3, base, mTextPaint);
                
                canvas.drawRect(itemW * 4, 0, (itemW * 5) - 1, bottom, mTextBackgroundPaint);
                velocity = ps.mVelocity == null ? 0 : (int) (ps.mVelocity.getYVelocity() * 1000);
                canvas.drawText("Yv: " + velocity, 1 + itemW * 4, base, mTextPaint);
                
                canvas.drawRect(itemW * 5, 0, (itemW * 6) - 1, bottom, mTextBackgroundPaint);
                canvas.drawRect(itemW * 5, 0, (itemW * 5) + (ps.mCurPressure * itemW) - 1,
                        bottom, mTextLevelPaint);
                canvas.drawText("Prs: " + String.format("%.2f", ps.mCurPressure), 1 + itemW * 5,
                        base, mTextPaint);
                
                canvas.drawRect(itemW * 6, 0, w, bottom, mTextBackgroundPaint);
                canvas.drawRect(itemW * 6, 0, (itemW * 6) + (ps.mCurSize * itemW) - 1,
                        bottom, mTextLevelPaint);
                canvas.drawText("Size: " + String.format("%.2f", ps.mCurSize), 1 + itemW * 6,
                        base, mTextPaint);
            }
            
            for (int p=0; p<NP; p++) {
                final PointerState ps = mPointers.get(p);
                
                if (mCurDown && ps.mCurDown) {
                    canvas.drawLine(0, (int)ps.mCurY, getWidth(), (int)ps.mCurY, mTargetPaint);
                    canvas.drawLine((int)ps.mCurX, 0, (int)ps.mCurX, getHeight(), mTargetPaint);
                    int pressureLevel = (int)(ps.mCurPressure*255);
                    mPaint.setARGB(255, pressureLevel, 128, 255-pressureLevel);
                    canvas.drawPoint(ps.mCurX, ps.mCurY, mPaint);
                    canvas.drawCircle(ps.mCurX, ps.mCurY, ps.mCurWidth, mPaint);
                }
            }
            
            for (int p=0; p<NP; p++) {
                final PointerState ps = mPointers.get(p);
                
                final int N = ps.mXs.size();
                float lastX=0, lastY=0;
                boolean haveLast = false;
                boolean drawn = false;
                mPaint.setARGB(255, 128, 255, 255);
                for (int i=0; i<N; i++) {
                    float x = ps.mXs.get(i);
                    float y = ps.mYs.get(i);
                    if (Float.isNaN(x)) {
                        haveLast = false;
                        continue;
                    }
                    if (haveLast) {
                        canvas.drawLine(lastX, lastY, x, y, mPathPaint);
                        canvas.drawPoint(lastX, lastY, mPaint);
                        drawn = true;
                    }
                    lastX = x;
                    lastY = y;
                    haveLast = true;
                }
                
                if (drawn) {
                    if (ps.mVelocity != null) {
                        mPaint.setARGB(255, 255, 64, 128);
                        float xVel = ps.mVelocity.getXVelocity() * (1000/60);
                        float yVel = ps.mVelocity.getYVelocity() * (1000/60);
                        canvas.drawLine(lastX, lastY, lastX+xVel, lastY+yVel, mPaint);
                    } else {
                        canvas.drawPoint(lastX, lastY, mPaint);
                    }
                }
            }
        }
    }

    public void addTouchEvent(MotionEvent event) {
        synchronized (mPointers) {
            int action = event.getAction();
            
            //Log.i("Pointer", "Motion: action=0x" + Integer.toHexString(action)
            //        + " pointers=" + event.getPointerCount());
            
            int NP = mPointers.size();
            
            //mRect.set(0, 0, getWidth(), mHeaderBottom+1);
            //invalidate(mRect);
            //if (mCurDown) {
            //    mRect.set(mCurX-mCurWidth-3, mCurY-mCurWidth-3,
            //            mCurX+mCurWidth+3, mCurY+mCurWidth+3);
            //} else {
            //    mRect.setEmpty();
            //}
            if (action == MotionEvent.ACTION_DOWN) {
                for (int p=0; p<NP; p++) {
                    final PointerState ps = mPointers.get(p);
                    ps.mXs.clear();
                    ps.mYs.clear();
                    ps.mVelocity = VelocityTracker.obtain();
                    ps.mCurDown = false;
                }
                mPointers.get(0).mCurDown = true;
                mMaxNumPointers = 0;
                if (mPrintCoords) {
                    Log.i("Pointer", "Pointer 1: DOWN");
                }
            }
            
            if ((action&MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN) {
                final int index = (action&MotionEvent.ACTION_POINTER_INDEX_MASK)
                        >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                final int id = event.getPointerId(index);
                while (NP <= id) {
                    PointerState ps = new PointerState();
                    ps.mVelocity = VelocityTracker.obtain();
                    mPointers.add(ps);
                    NP++;
                }
                final PointerState ps = mPointers.get(id);
                ps.mVelocity = VelocityTracker.obtain();
                ps.mCurDown = true;
                if (mPrintCoords) {
                    Log.i("Pointer", "Pointer " + (id+1) + ": DOWN");
                }
            }
            
            final int NI = event.getPointerCount();
            
            mCurDown = action != MotionEvent.ACTION_UP
                    && action != MotionEvent.ACTION_CANCEL;
            mCurNumPointers = mCurDown ? NI : 0;
            if (mMaxNumPointers < mCurNumPointers) {
                mMaxNumPointers = mCurNumPointers;
            }
            
            for (int i=0; i<NI; i++) {
                final int id = event.getPointerId(i);
                final PointerState ps = mPointers.get(id);
                ps.mVelocity.addMovement(event);
                ps.mVelocity.computeCurrentVelocity(1);
                final int N = event.getHistorySize();
                for (int j=0; j<N; j++) {
                    if (mPrintCoords) {
                        Log.i("Pointer", "Pointer " + (id+1) + ": ("
                                + event.getHistoricalX(i, j)
                                + ", " + event.getHistoricalY(i, j) + ")"
                                + " Prs=" + event.getHistoricalPressure(i, j)
                                + " Size=" + event.getHistoricalSize(i, j));
                    }
                    ps.mXs.add(event.getHistoricalX(i, j));
                    ps.mYs.add(event.getHistoricalY(i, j));
                }
                if (mPrintCoords) {
                    Log.i("Pointer", "Pointer " + (id+1) + ": ("
                            + event.getX(i) + ", " + event.getY(i) + ")"
                            + " Prs=" + event.getPressure(i)
                            + " Size=" + event.getSize(i));
                }
                ps.mXs.add(event.getX(i));
                ps.mYs.add(event.getY(i));
                ps.mCurX = (int)event.getX(i);
                ps.mCurY = (int)event.getY(i);
                //Log.i("Pointer", "Pointer #" + p + ": (" + ps.mCurX
                //        + "," + ps.mCurY + ")");
                ps.mCurPressure = event.getPressure(i);
                ps.mCurSize = event.getSize(i);
                ps.mCurWidth = (int)(ps.mCurSize*(getWidth()/3));
            }
            
            if ((action&MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP) {
                final int index = (action&MotionEvent.ACTION_POINTER_INDEX_MASK)
                        >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
                final int id = event.getPointerId(index);
                final PointerState ps = mPointers.get(id);
                ps.mXs.add(Float.NaN);
                ps.mYs.add(Float.NaN);
                ps.mCurDown = false;
                if (mPrintCoords) {
                    Log.i("Pointer", "Pointer " + (id+1) + ": UP");
                }
            }
            
            if (action == MotionEvent.ACTION_UP) {
                for (int i=0; i<NI; i++) {
                    final int id = event.getPointerId(i);
                    final PointerState ps = mPointers.get(id);
                    if (ps.mCurDown) {
                        ps.mCurDown = false;
                        if (mPrintCoords) {
                            Log.i("Pointer", "Pointer " + (id+1) + ": UP");
                        }
                    }
                }
            }
            
            //if (mCurDown) {
            //    mRect.union(mCurX-mCurWidth-3, mCurY-mCurWidth-3,
            //            mCurX+mCurWidth+3, mCurY+mCurWidth+3);
            //}
            //invalidate(mRect);
            postInvalidate();
        }
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        addTouchEvent(event);
        return true;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        Log.i("Pointer", "Trackball: " + event);
        return super.onTrackballEvent(event);
    }
    
}
