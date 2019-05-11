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

import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.input.InputManager;
import android.hardware.input.InputManager.InputDeviceListener;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Slog;
import android.view.ISystemGestureExclusionListener;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerPolicyConstants.PointerEventListener;

import java.util.ArrayList;

public class PointerLocationView extends View implements InputDeviceListener,
        PointerEventListener {
    private static final String TAG = "Pointer";

    // The system property key used to specify an alternate velocity tracker strategy
    // to plot alongside the default one.  Useful for testing and comparison purposes.
    private static final String ALT_STRATEGY_PROPERY_KEY = "debug.velocitytracker.alt";

    public static class PointerState {
        // Trace of previous points.
        private float[] mTraceX = new float[32];
        private float[] mTraceY = new float[32];
        private boolean[] mTraceCurrent = new boolean[32];
        private int mTraceCount;
        
        // True if the pointer is down.
        @UnsupportedAppUsage
        private boolean mCurDown;
        
        // Most recent coordinates.
        private PointerCoords mCoords = new PointerCoords();
        private int mToolType;
        
        // Most recent velocity.
        private float mXVelocity;
        private float mYVelocity;
        private float mAltXVelocity;
        private float mAltYVelocity;

        // Current bounding box, if any
        private boolean mHasBoundingBox;
        private float mBoundingLeft;
        private float mBoundingTop;
        private float mBoundingRight;
        private float mBoundingBottom;

        // Position estimator.
        private VelocityTracker.Estimator mEstimator = new VelocityTracker.Estimator();
        private VelocityTracker.Estimator mAltEstimator = new VelocityTracker.Estimator();

        public void clearTrace() {
            mTraceCount = 0;
        }
        
        public void addTrace(float x, float y, boolean current) {
            int traceCapacity = mTraceX.length;
            if (mTraceCount == traceCapacity) {
                traceCapacity *= 2;
                float[] newTraceX = new float[traceCapacity];
                System.arraycopy(mTraceX, 0, newTraceX, 0, mTraceCount);
                mTraceX = newTraceX;
                
                float[] newTraceY = new float[traceCapacity];
                System.arraycopy(mTraceY, 0, newTraceY, 0, mTraceCount);
                mTraceY = newTraceY;

                boolean[] newTraceCurrent = new boolean[traceCapacity];
                System.arraycopy(mTraceCurrent, 0, newTraceCurrent, 0, mTraceCount);
                mTraceCurrent= newTraceCurrent;
            }
            
            mTraceX[mTraceCount] = x;
            mTraceY[mTraceCount] = y;
            mTraceCurrent[mTraceCount] = current;
            mTraceCount += 1;
        }
    }

    private final InputManager mIm;

    private final ViewConfiguration mVC;
    private final Paint mTextPaint;
    private final Paint mTextBackgroundPaint;
    private final Paint mTextLevelPaint;
    private final Paint mPaint;
    private final Paint mCurrentPointPaint;
    private final Paint mTargetPaint;
    private final Paint mPathPaint;
    private final FontMetricsInt mTextMetrics = new FontMetricsInt();
    private int mHeaderBottom;
    private int mHeaderPaddingTop = 0;
    @UnsupportedAppUsage
    private boolean mCurDown;
    @UnsupportedAppUsage
    private int mCurNumPointers;
    @UnsupportedAppUsage
    private int mMaxNumPointers;
    private int mActivePointerId;
    @UnsupportedAppUsage
    private final ArrayList<PointerState> mPointers = new ArrayList<PointerState>();
    private final PointerCoords mTempCoords = new PointerCoords();

    private final Region mSystemGestureExclusion = new Region();
    private final Path mSystemGestureExclusionPath = new Path();
    private final Paint mSystemGestureExclusionPaint;

    private final VelocityTracker mVelocity;
    private final VelocityTracker mAltVelocity;

    private final FasterStringBuilder mText = new FasterStringBuilder();

    @UnsupportedAppUsage
    private boolean mPrintCoords = true;
    
    public PointerLocationView(Context c) {
        super(c);
        setFocusableInTouchMode(true);

        mIm = c.getSystemService(InputManager.class);

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
        mCurrentPointPaint = new Paint();
        mCurrentPointPaint.setAntiAlias(true);
        mCurrentPointPaint.setARGB(255, 255, 0, 0);
        mCurrentPointPaint.setStyle(Paint.Style.STROKE);
        mCurrentPointPaint.setStrokeWidth(2);
        mTargetPaint = new Paint();
        mTargetPaint.setAntiAlias(false);
        mTargetPaint.setARGB(255, 0, 0, 192);
        mPathPaint = new Paint();
        mPathPaint.setAntiAlias(false);
        mPathPaint.setARGB(255, 0, 96, 255);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(1);

        mSystemGestureExclusionPaint = new Paint();
        mSystemGestureExclusionPaint.setARGB(25, 255, 0, 0);
        mSystemGestureExclusionPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        PointerState ps = new PointerState();
        mPointers.add(ps);
        mActivePointerId = 0;
        
        mVelocity = VelocityTracker.obtain();

        String altStrategy = SystemProperties.get(ALT_STRATEGY_PROPERY_KEY);
        if (altStrategy.length() != 0) {
            Log.d(TAG, "Comparing default velocity tracker strategy with " + altStrategy);
            mAltVelocity = VelocityTracker.obtain(altStrategy);
        } else {
            mAltVelocity = null;
        }
    }

    public void setPrintCoords(boolean state) {
        mPrintCoords = state;
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (insets.getDisplayCutout() != null) {
            mHeaderPaddingTop = insets.getDisplayCutout().getSafeInsetTop();
        } else {
            mHeaderPaddingTop = 0;
        }
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mTextPaint.getFontMetricsInt(mTextMetrics);
        mHeaderBottom = mHeaderPaddingTop-mTextMetrics.ascent+mTextMetrics.descent+2;
        if (false) {
            Log.i("foo", "Metrics: ascent=" + mTextMetrics.ascent
                    + " descent=" + mTextMetrics.descent
                    + " leading=" + mTextMetrics.leading
                    + " top=" + mTextMetrics.top
                    + " bottom=" + mTextMetrics.bottom);
        }
    }
    
    // Draw an oval.  When angle is 0 radians, orients the major axis vertically,
    // angles less than or greater than 0 radians rotate the major axis left or right.
    private RectF mReusableOvalRect = new RectF();
    private void drawOval(Canvas canvas, float x, float y, float major, float minor,
            float angle, Paint paint) {
        canvas.save(Canvas.MATRIX_SAVE_FLAG);
        canvas.rotate((float) (angle * 180 / Math.PI), x, y);
        mReusableOvalRect.left = x - minor / 2;
        mReusableOvalRect.right = x + minor / 2;
        mReusableOvalRect.top = y - major / 2;
        mReusableOvalRect.bottom = y + major / 2;
        canvas.drawOval(mReusableOvalRect, paint);
        canvas.restore();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int itemW = w/7;
        final int base = mHeaderPaddingTop-mTextMetrics.ascent+1;
        final int bottom = mHeaderBottom;

        final int NP = mPointers.size();

        if (!mSystemGestureExclusion.isEmpty()) {
            mSystemGestureExclusionPath.reset();
            mSystemGestureExclusion.getBoundaryPath(mSystemGestureExclusionPath);
            canvas.drawPath(mSystemGestureExclusionPath, mSystemGestureExclusionPaint);
        }

        // Labels
        if (mActivePointerId >= 0) {
            final PointerState ps = mPointers.get(mActivePointerId);
            
            canvas.drawRect(0, mHeaderPaddingTop, itemW-1, bottom,mTextBackgroundPaint);
            canvas.drawText(mText.clear()
                    .append("P: ").append(mCurNumPointers)
                    .append(" / ").append(mMaxNumPointers)
                    .toString(), 1, base, mTextPaint);

            final int N = ps.mTraceCount;
            if ((mCurDown && ps.mCurDown) || N == 0) {
                canvas.drawRect(itemW, mHeaderPaddingTop, (itemW * 2) - 1, bottom,
                        mTextBackgroundPaint);
                canvas.drawText(mText.clear()
                        .append("X: ").append(ps.mCoords.x, 1)
                        .toString(), 1 + itemW, base, mTextPaint);
                canvas.drawRect(itemW * 2, mHeaderPaddingTop, (itemW * 3) - 1, bottom,
                        mTextBackgroundPaint);
                canvas.drawText(mText.clear()
                        .append("Y: ").append(ps.mCoords.y, 1)
                        .toString(), 1 + itemW * 2, base, mTextPaint);
            } else {
                float dx = ps.mTraceX[N - 1] - ps.mTraceX[0];
                float dy = ps.mTraceY[N - 1] - ps.mTraceY[0];
                canvas.drawRect(itemW, mHeaderPaddingTop, (itemW * 2) - 1, bottom,
                        Math.abs(dx) < mVC.getScaledTouchSlop()
                        ? mTextBackgroundPaint : mTextLevelPaint);
                canvas.drawText(mText.clear()
                        .append("dX: ").append(dx, 1)
                        .toString(), 1 + itemW, base, mTextPaint);
                canvas.drawRect(itemW * 2, mHeaderPaddingTop, (itemW * 3) - 1, bottom,
                        Math.abs(dy) < mVC.getScaledTouchSlop()
                        ? mTextBackgroundPaint : mTextLevelPaint);
                canvas.drawText(mText.clear()
                        .append("dY: ").append(dy, 1)
                        .toString(), 1 + itemW * 2, base, mTextPaint);
            }

            canvas.drawRect(itemW * 3, mHeaderPaddingTop, (itemW * 4) - 1, bottom,
                    mTextBackgroundPaint);
            canvas.drawText(mText.clear()
                    .append("Xv: ").append(ps.mXVelocity, 3)
                    .toString(), 1 + itemW * 3, base, mTextPaint);

            canvas.drawRect(itemW * 4, mHeaderPaddingTop, (itemW * 5) - 1, bottom,
                    mTextBackgroundPaint);
            canvas.drawText(mText.clear()
                    .append("Yv: ").append(ps.mYVelocity, 3)
                    .toString(), 1 + itemW * 4, base, mTextPaint);

            canvas.drawRect(itemW * 5, mHeaderPaddingTop, (itemW * 6) - 1, bottom,
                    mTextBackgroundPaint);
            canvas.drawRect(itemW * 5, mHeaderPaddingTop,
                    (itemW * 5) + (ps.mCoords.pressure * itemW) - 1, bottom, mTextLevelPaint);
            canvas.drawText(mText.clear()
                    .append("Prs: ").append(ps.mCoords.pressure, 2)
                    .toString(), 1 + itemW * 5, base, mTextPaint);

            canvas.drawRect(itemW * 6, mHeaderPaddingTop, w, bottom, mTextBackgroundPaint);
            canvas.drawRect(itemW * 6, mHeaderPaddingTop,
                    (itemW * 6) + (ps.mCoords.size * itemW) - 1, bottom, mTextLevelPaint);
            canvas.drawText(mText.clear()
                    .append("Size: ").append(ps.mCoords.size, 2)
                    .toString(), 1 + itemW * 6, base, mTextPaint);
        }

        // Pointer trace.
        for (int p = 0; p < NP; p++) {
            final PointerState ps = mPointers.get(p);

            // Draw path.
            final int N = ps.mTraceCount;
            float lastX = 0, lastY = 0;
            boolean haveLast = false;
            boolean drawn = false;
            mPaint.setARGB(255, 128, 255, 255);
            for (int i=0; i < N; i++) {
                float x = ps.mTraceX[i];
                float y = ps.mTraceY[i];
                if (Float.isNaN(x)) {
                    haveLast = false;
                    continue;
                }
                if (haveLast) {
                    canvas.drawLine(lastX, lastY, x, y, mPathPaint);
                    final Paint paint = ps.mTraceCurrent[i] ? mCurrentPointPaint : mPaint;
                    canvas.drawPoint(lastX, lastY, paint);
                    drawn = true;
                }
                lastX = x;
                lastY = y;
                haveLast = true;
            }

            if (drawn) {
                // Draw velocity vector.
                mPaint.setARGB(255, 255, 64, 128);
                float xVel = ps.mXVelocity * (1000 / 60);
                float yVel = ps.mYVelocity * (1000 / 60);
                canvas.drawLine(lastX, lastY, lastX + xVel, lastY + yVel, mPaint);

                // Draw velocity vector using an alternate VelocityTracker strategy.
                if (mAltVelocity != null) {
                    mPaint.setARGB(255, 64, 255, 128);
                    xVel = ps.mAltXVelocity * (1000 / 60);
                    yVel = ps.mAltYVelocity * (1000 / 60);
                    canvas.drawLine(lastX, lastY, lastX + xVel, lastY + yVel, mPaint);
                }
            }

            if (mCurDown && ps.mCurDown) {
                // Draw crosshairs.
                canvas.drawLine(0, ps.mCoords.y, getWidth(), ps.mCoords.y, mTargetPaint);
                canvas.drawLine(ps.mCoords.x, 0, ps.mCoords.x, getHeight(), mTargetPaint);

                // Draw current point.
                int pressureLevel = (int)(ps.mCoords.pressure * 255);
                mPaint.setARGB(255, pressureLevel, 255, 255 - pressureLevel);
                canvas.drawPoint(ps.mCoords.x, ps.mCoords.y, mPaint);

                // Draw current touch ellipse.
                mPaint.setARGB(255, pressureLevel, 255 - pressureLevel, 128);
                drawOval(canvas, ps.mCoords.x, ps.mCoords.y, ps.mCoords.touchMajor,
                        ps.mCoords.touchMinor, ps.mCoords.orientation, mPaint);

                // Draw current tool ellipse.
                mPaint.setARGB(255, pressureLevel, 128, 255 - pressureLevel);
                drawOval(canvas, ps.mCoords.x, ps.mCoords.y, ps.mCoords.toolMajor,
                        ps.mCoords.toolMinor, ps.mCoords.orientation, mPaint);

                // Draw the orientation arrow.
                float arrowSize = ps.mCoords.toolMajor * 0.7f;
                if (arrowSize < 20) {
                    arrowSize = 20;
                }
                mPaint.setARGB(255, pressureLevel, 255, 0);
                float orientationVectorX = (float) (Math.sin(ps.mCoords.orientation)
                        * arrowSize);
                float orientationVectorY = (float) (-Math.cos(ps.mCoords.orientation)
                        * arrowSize);
                if (ps.mToolType == MotionEvent.TOOL_TYPE_STYLUS
                        || ps.mToolType == MotionEvent.TOOL_TYPE_ERASER) {
                    // Show full circle orientation.
                    canvas.drawLine(ps.mCoords.x, ps.mCoords.y,
                            ps.mCoords.x + orientationVectorX,
                            ps.mCoords.y + orientationVectorY,
                            mPaint);
                } else {
                    // Show half circle orientation.
                    canvas.drawLine(
                            ps.mCoords.x - orientationVectorX,
                            ps.mCoords.y - orientationVectorY,
                            ps.mCoords.x + orientationVectorX,
                            ps.mCoords.y + orientationVectorY,
                            mPaint);
                }

                // Draw the tilt point along the orientation arrow.
                float tiltScale = (float) Math.sin(
                        ps.mCoords.getAxisValue(MotionEvent.AXIS_TILT));
                canvas.drawCircle(
                        ps.mCoords.x + orientationVectorX * tiltScale,
                        ps.mCoords.y + orientationVectorY * tiltScale,
                        3.0f, mPaint);

                // Draw the current bounding box
                if (ps.mHasBoundingBox) {
                    canvas.drawRect(ps.mBoundingLeft, ps.mBoundingTop,
                            ps.mBoundingRight, ps.mBoundingBottom, mPaint);
                }
            }
        }
    }

    private void logMotionEvent(String type, MotionEvent event) {
        final int action = event.getAction();
        final int N = event.getHistorySize();
        final int NI = event.getPointerCount();
        for (int historyPos = 0; historyPos < N; historyPos++) {
            for (int i = 0; i < NI; i++) {
                final int id = event.getPointerId(i);
                event.getHistoricalPointerCoords(i, historyPos, mTempCoords);
                logCoords(type, action, i, mTempCoords, id, event);
            }
        }
        for (int i = 0; i < NI; i++) {
            final int id = event.getPointerId(i);
            event.getPointerCoords(i, mTempCoords);
            logCoords(type, action, i, mTempCoords, id, event);
        }
    }

    private void logCoords(String type, int action, int index,
            MotionEvent.PointerCoords coords, int id, MotionEvent event) {
        final int toolType = event.getToolType(index);
        final int buttonState = event.getButtonState();
        final String prefix;
        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                prefix = "DOWN";
                break;
            case MotionEvent.ACTION_UP:
                prefix = "UP";
                break;
            case MotionEvent.ACTION_MOVE:
                prefix = "MOVE";
                break;
            case MotionEvent.ACTION_CANCEL:
                prefix = "CANCEL";
                break;
            case MotionEvent.ACTION_OUTSIDE:
                prefix = "OUTSIDE";
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (index == ((action & MotionEvent.ACTION_POINTER_INDEX_MASK)
                        >> MotionEvent.ACTION_POINTER_INDEX_SHIFT)) {
                    prefix = "DOWN";
                } else {
                    prefix = "MOVE";
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (index == ((action & MotionEvent.ACTION_POINTER_INDEX_MASK)
                        >> MotionEvent.ACTION_POINTER_INDEX_SHIFT)) {
                    prefix = "UP";
                } else {
                    prefix = "MOVE";
                }
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                prefix = "HOVER MOVE";
                break;
            case MotionEvent.ACTION_HOVER_ENTER:
                prefix = "HOVER ENTER";
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                prefix = "HOVER EXIT";
                break;
            case MotionEvent.ACTION_SCROLL:
                prefix = "SCROLL";
                break;
            default:
                prefix = Integer.toString(action);
                break;
        }

        Log.i(TAG, mText.clear()
                .append(type).append(" id ").append(id + 1)
                .append(": ")
                .append(prefix)
                .append(" (").append(coords.x, 3).append(", ").append(coords.y, 3)
                .append(") Pressure=").append(coords.pressure, 3)
                .append(" Size=").append(coords.size, 3)
                .append(" TouchMajor=").append(coords.touchMajor, 3)
                .append(" TouchMinor=").append(coords.touchMinor, 3)
                .append(" ToolMajor=").append(coords.toolMajor, 3)
                .append(" ToolMinor=").append(coords.toolMinor, 3)
                .append(" Orientation=").append((float)(coords.orientation * 180 / Math.PI), 1)
                .append("deg")
                .append(" Tilt=").append((float)(
                        coords.getAxisValue(MotionEvent.AXIS_TILT) * 180 / Math.PI), 1)
                .append("deg")
                .append(" Distance=").append(coords.getAxisValue(MotionEvent.AXIS_DISTANCE), 1)
                .append(" VScroll=").append(coords.getAxisValue(MotionEvent.AXIS_VSCROLL), 1)
                .append(" HScroll=").append(coords.getAxisValue(MotionEvent.AXIS_HSCROLL), 1)
                .append(" BoundingBox=[(")
                .append(event.getAxisValue(MotionEvent.AXIS_GENERIC_1), 3)
                .append(", ").append(event.getAxisValue(MotionEvent.AXIS_GENERIC_2), 3).append(")")
                .append(", (").append(event.getAxisValue(MotionEvent.AXIS_GENERIC_3), 3)
                .append(", ").append(event.getAxisValue(MotionEvent.AXIS_GENERIC_4), 3)
                .append(")]")
                .append(" ToolType=").append(MotionEvent.toolTypeToString(toolType))
                .append(" ButtonState=").append(MotionEvent.buttonStateToString(buttonState))
                .toString());
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        final int action = event.getAction();
        int NP = mPointers.size();

        if (action == MotionEvent.ACTION_DOWN
                || (action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_DOWN) {
            final int index = (action & MotionEvent.ACTION_POINTER_INDEX_MASK)
                    >> MotionEvent.ACTION_POINTER_INDEX_SHIFT; // will be 0 for down
            if (action == MotionEvent.ACTION_DOWN) {
                for (int p=0; p<NP; p++) {
                    final PointerState ps = mPointers.get(p);
                    ps.clearTrace();
                    ps.mCurDown = false;
                }
                mCurDown = true;
                mCurNumPointers = 0;
                mMaxNumPointers = 0;
                mVelocity.clear();
                if (mAltVelocity != null) {
                    mAltVelocity.clear();
                }
            }

            mCurNumPointers += 1;
            if (mMaxNumPointers < mCurNumPointers) {
                mMaxNumPointers = mCurNumPointers;
            }

            final int id = event.getPointerId(index);
            while (NP <= id) {
                PointerState ps = new PointerState();
                mPointers.add(ps);
                NP++;
            }

            if (mActivePointerId < 0 ||
                    !mPointers.get(mActivePointerId).mCurDown) {
                mActivePointerId = id;
            }

            final PointerState ps = mPointers.get(id);
            ps.mCurDown = true;
            InputDevice device = InputDevice.getDevice(event.getDeviceId());
            ps.mHasBoundingBox = device != null &&
                    device.getMotionRange(MotionEvent.AXIS_GENERIC_1) != null;
        }

        final int NI = event.getPointerCount();

        mVelocity.addMovement(event);
        mVelocity.computeCurrentVelocity(1);
        if (mAltVelocity != null) {
            mAltVelocity.addMovement(event);
            mAltVelocity.computeCurrentVelocity(1);
        }

        final int N = event.getHistorySize();
        for (int historyPos = 0; historyPos < N; historyPos++) {
            for (int i = 0; i < NI; i++) {
                final int id = event.getPointerId(i);
                final PointerState ps = mCurDown ? mPointers.get(id) : null;
                final PointerCoords coords = ps != null ? ps.mCoords : mTempCoords;
                event.getHistoricalPointerCoords(i, historyPos, coords);
                if (mPrintCoords) {
                    logCoords("Pointer", action, i, coords, id, event);
                }
                if (ps != null) {
                    ps.addTrace(coords.x, coords.y, false);
                }
            }
        }
        for (int i = 0; i < NI; i++) {
            final int id = event.getPointerId(i);
            final PointerState ps = mCurDown ? mPointers.get(id) : null;
            final PointerCoords coords = ps != null ? ps.mCoords : mTempCoords;
            event.getPointerCoords(i, coords);
            if (mPrintCoords) {
                logCoords("Pointer", action, i, coords, id, event);
            }
            if (ps != null) {
                ps.addTrace(coords.x, coords.y, true);
                ps.mXVelocity = mVelocity.getXVelocity(id);
                ps.mYVelocity = mVelocity.getYVelocity(id);
                mVelocity.getEstimator(id, ps.mEstimator);
                if (mAltVelocity != null) {
                    ps.mAltXVelocity = mAltVelocity.getXVelocity(id);
                    ps.mAltYVelocity = mAltVelocity.getYVelocity(id);
                    mAltVelocity.getEstimator(id, ps.mAltEstimator);
                }
                ps.mToolType = event.getToolType(i);

                if (ps.mHasBoundingBox) {
                    ps.mBoundingLeft = event.getAxisValue(MotionEvent.AXIS_GENERIC_1, i);
                    ps.mBoundingTop = event.getAxisValue(MotionEvent.AXIS_GENERIC_2, i);
                    ps.mBoundingRight = event.getAxisValue(MotionEvent.AXIS_GENERIC_3, i);
                    ps.mBoundingBottom = event.getAxisValue(MotionEvent.AXIS_GENERIC_4, i);
                }
            }
        }

        if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL
                || (action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_POINTER_UP) {
            final int index = (action & MotionEvent.ACTION_POINTER_INDEX_MASK)
                    >> MotionEvent.ACTION_POINTER_INDEX_SHIFT; // will be 0 for UP

            final int id = event.getPointerId(index);
            if (id >= NP) {
                Slog.wtf(TAG, "Got pointer ID out of bounds: id=" + id + " arraysize="
                        + NP + " pointerindex=" + index
                        + " action=0x" + Integer.toHexString(action));
                return;
            }
            final PointerState ps = mPointers.get(id);
            ps.mCurDown = false;

            if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                mCurDown = false;
                mCurNumPointers = 0;
            } else {
                mCurNumPointers -= 1;
                if (mActivePointerId == id) {
                    mActivePointerId = event.getPointerId(index == 0 ? 1 : 0);
                }
                ps.addTrace(Float.NaN, Float.NaN, false);
            }
        }

        invalidate();
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        onPointerEvent(event);

        if (event.getAction() == MotionEvent.ACTION_DOWN && !isFocused()) {
            requestFocus();
        }
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        final int source = event.getSource();
        if ((source & InputDevice.SOURCE_CLASS_POINTER) != 0) {
            onPointerEvent(event);
        } else if ((source & InputDevice.SOURCE_CLASS_JOYSTICK) != 0) {
            logMotionEvent("Joystick", event);
        } else if ((source & InputDevice.SOURCE_CLASS_POSITION) != 0) {
            logMotionEvent("Position", event);
        } else {
            logMotionEvent("Generic", event);
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (shouldLogKey(keyCode)) {
            final int repeatCount = event.getRepeatCount();
            if (repeatCount == 0) {
                Log.i(TAG, "Key Down: " + event);
            } else {
                Log.i(TAG, "Key Repeat #" + repeatCount + ": " + event);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (shouldLogKey(keyCode)) {
            Log.i(TAG, "Key Up: " + event);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private static boolean shouldLogKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return true;
            default:
                return KeyEvent.isGamepadButton(keyCode)
                    || KeyEvent.isModifierKey(keyCode);
        }
    }

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        logMotionEvent("Trackball", event);
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mIm.registerInputDeviceListener(this, getHandler());
        if (shouldShowSystemGestureExclusion()) {
            try {
                WindowManagerGlobal.getWindowManagerService()
                        .registerSystemGestureExclusionListener(mSystemGestureExclusionListener,
                                mContext.getDisplayId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } else {
            mSystemGestureExclusion.setEmpty();
        }
        logInputDevices();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mIm.unregisterInputDeviceListener(this);
        try {
            WindowManagerGlobal.getWindowManagerService().unregisterSystemGestureExclusionListener(
                    mSystemGestureExclusionListener, mContext.getDisplayId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        logInputDeviceState(deviceId, "Device Added");
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        logInputDeviceState(deviceId, "Device Changed");
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        logInputDeviceState(deviceId, "Device Removed");
    }

    private void logInputDevices() {
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int i = 0; i < deviceIds.length; i++) {
            logInputDeviceState(deviceIds[i], "Device Enumerated");
        }
    }

    private void logInputDeviceState(int deviceId, String state) {
        InputDevice device = mIm.getInputDevice(deviceId);
        if (device != null) {
            Log.i(TAG, state + ": " + device);
        } else {
            Log.i(TAG, state + ": " + deviceId);
        }
    }

    private static boolean shouldShowSystemGestureExclusion() {
        return SystemProperties.getBoolean("debug.pointerlocation.showexclusion", false);
    }

    // HACK
    // A quick and dirty string builder implementation optimized for GC.
    // Using String.format causes the application grind to a halt when
    // more than a couple of pointers are down due to the number of
    // temporary objects allocated while formatting strings for drawing or logging.
    private static final class FasterStringBuilder {
        private char[] mChars;
        private int mLength;
        
        public FasterStringBuilder() {
            mChars = new char[64];
        }
        
        public FasterStringBuilder clear() {
            mLength = 0;
            return this;
        }
        
        public FasterStringBuilder append(String value) {
            final int valueLength = value.length();
            final int index = reserve(valueLength);
            value.getChars(0, valueLength, mChars, index);
            mLength += valueLength;
            return this;
        }
        
        public FasterStringBuilder append(int value) {
            return append(value, 0);
        }
        
        public FasterStringBuilder append(int value, int zeroPadWidth) {
            final boolean negative = value < 0;
            if (negative) {
                value = - value;
                if (value < 0) {
                    append("-2147483648");
                    return this;
                }
            }
            
            int index = reserve(11);
            final char[] chars = mChars;
            
            if (value == 0) {
                chars[index++] = '0';
                mLength += 1;
                return this;
            }
            
            if (negative) {
                chars[index++] = '-';
            }

            int divisor = 1000000000;
            int numberWidth = 10;
            while (value < divisor) {
                divisor /= 10;
                numberWidth -= 1;
                if (numberWidth < zeroPadWidth) {
                    chars[index++] = '0';
                }
            }
            
            do {
                int digit = value / divisor;
                value -= digit * divisor;
                divisor /= 10;
                chars[index++] = (char) (digit + '0');
            } while (divisor != 0);
            
            mLength = index;
            return this;
        }
        
        public FasterStringBuilder append(float value, int precision) {
            int scale = 1;
            for (int i = 0; i < precision; i++) {
                scale *= 10;
            }
            value = (float) (Math.rint(value * scale) / scale);

            // Corner case: (int)-0.1 will become zero, so the negative sign gets lost
            if ((int) value == 0 && value < 0) {
                append("-");
            }
            append((int) value);

            if (precision != 0) {
                append(".");
                value = Math.abs(value);
                value -= Math.floor(value);
                append((int) (value * scale), precision);
            }
            
            return this;
        }
        
        @Override
        public String toString() {
            return new String(mChars, 0, mLength);
        }
        
        private int reserve(int length) {
            final int oldLength = mLength;
            final int newLength = mLength + length;
            final char[] oldChars = mChars;
            final int oldCapacity = oldChars.length;
            if (newLength > oldCapacity) {
                final int newCapacity = oldCapacity * 2;
                final char[] newChars = new char[newCapacity];
                System.arraycopy(oldChars, 0, newChars, 0, oldLength);
                mChars = newChars;
            }
            return oldLength;
        }
    }

    private ISystemGestureExclusionListener mSystemGestureExclusionListener =
            new ISystemGestureExclusionListener.Stub() {
        @Override
        public void onSystemGestureExclusionChanged(int displayId, Region systemGestureExclusion) {
            Region exclusion = Region.obtain(systemGestureExclusion);
            Handler handler = getHandler();
            if (handler != null) {
                handler.post(() -> {
                    mSystemGestureExclusion.set(exclusion);
                    exclusion.recycle();
                    invalidate();
                });
            }
        }
    };
}
