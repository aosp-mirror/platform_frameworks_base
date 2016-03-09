/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.settingslib.graph;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.Path;
import android.graphics.Shader.TileMode;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.View;
import com.android.settingslib.R;

public class UsageGraph extends View {

    private static final int PATH_DELIM = -1;

    private final Paint mLinePaint;
    private final Paint mFillPaint;
    private final Paint mDottedPaint;

    private final Drawable mDivider;
    private final Drawable mTintedDivider;
    private final int mDividerSize;

    private final Path mPath = new Path();

    // Paths in coordinates they are passed in.
    private final SparseIntArray mPaths = new SparseIntArray();
    // Paths in local coordinates for drawing.
    private final SparseIntArray mLocalPaths = new SparseIntArray();
    private final int mCornerRadius;

    private int mAccentColor;
    private boolean mShowProjection;
    private boolean mProjectUp;

    private float mMaxX = 100;
    private float mMaxY = 100;

    private float mMiddleDividerLoc = .5f;
    private int mMiddleDividerTint = -1;
    private int mTopDividerTint = -1;

    public UsageGraph(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        final Resources resources = context.getResources();

        mLinePaint = new Paint();
        mLinePaint.setStyle(Style.STROKE);
        mLinePaint.setStrokeCap(Cap.ROUND);
        mLinePaint.setStrokeJoin(Join.ROUND);
        mLinePaint.setAntiAlias(true);
        mCornerRadius = resources.getDimensionPixelSize(R.dimen.usage_graph_line_corner_radius);
        mLinePaint.setPathEffect(new CornerPathEffect(mCornerRadius));
        mLinePaint.setStrokeWidth(resources.getDimensionPixelSize(R.dimen.usage_graph_line_width));

        mFillPaint = new Paint(mLinePaint);
        mFillPaint.setStyle(Style.FILL);

        mDottedPaint = new Paint(mLinePaint);
        mDottedPaint.setStyle(Style.STROKE);
        float dots = resources.getDimensionPixelSize(R.dimen.usage_graph_dot_size);
        float interval = resources.getDimensionPixelSize(R.dimen.usage_graph_dot_interval);
        mDottedPaint.setStrokeWidth(dots * 3);
        mDottedPaint.setPathEffect(new DashPathEffect(new float[] {dots, interval}, 0));
        mDottedPaint.setColor(context.getColor(R.color.usage_graph_dots));

        TypedValue v = new TypedValue();
        context.getTheme().resolveAttribute(com.android.internal.R.attr.listDivider, v, true);
        mDivider = context.getDrawable(v.resourceId);
        mTintedDivider = context.getDrawable(v.resourceId);
        mDividerSize = resources.getDimensionPixelSize(R.dimen.usage_graph_divider_size);
    }

    void clearPaths() {
        mPaths.clear();
    }

    void setMax(int maxX, int maxY) {
        mMaxX = maxX;
        mMaxY = maxY;
    }

    void setDividerLoc(int height) {
        mMiddleDividerLoc = 1 - height / mMaxY;
    }

    void setDividerColors(int middleColor, int topColor) {
        mMiddleDividerTint = middleColor;
        mTopDividerTint = topColor;
    }

    public void addPath(SparseIntArray points) {
        for (int i = 0; i < points.size(); i++) {
            mPaths.put(points.keyAt(i), points.valueAt(i));
        }
        mPaths.put(points.keyAt(points.size() - 1) + 1, PATH_DELIM);
        calculateLocalPaths();
        postInvalidate();
    }

    void setAccentColor(int color) {
        mAccentColor = color;
        mLinePaint.setColor(mAccentColor);
        updateGradient();
        postInvalidate();
    }

    void setShowProjection(boolean showProjection, boolean projectUp) {
        mShowProjection = showProjection;
        mProjectUp = projectUp;
        postInvalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateGradient();
        calculateLocalPaths();
    }

    private void calculateLocalPaths() {
        if (getWidth() == 0) return;
        mLocalPaths.clear();
        int pendingXLoc = 0;
        int pendingYLoc = PATH_DELIM;
        for (int i = 0; i < mPaths.size(); i++) {
            int x = mPaths.keyAt(i);
            int y = mPaths.valueAt(i);
            if (y == PATH_DELIM) {
                if (i == mPaths.size() - 1 && pendingYLoc != PATH_DELIM) {
                    // Connect to the end of the graph.
                    mLocalPaths.put(pendingXLoc, pendingYLoc);
                }
                // Clear out any pending points.
                pendingYLoc = PATH_DELIM;
                mLocalPaths.put(pendingXLoc + 1, PATH_DELIM);
            } else {
                final int lx = getX(x);
                final int ly = getY(y);
                pendingXLoc = lx;
                if (mLocalPaths.size() > 0) {
                    int lastX = mLocalPaths.keyAt(mLocalPaths.size() - 1);
                    int lastY = mLocalPaths.valueAt(mLocalPaths.size() - 1);
                    if (lastY != PATH_DELIM && !hasDiff(lastX, lx) && !hasDiff(lastY, ly)) {
                        pendingYLoc = ly;
                        continue;
                    }
                }
                mLocalPaths.put(lx, ly);
            }
        }
    }

    private boolean hasDiff(int x1, int x2) {
        return Math.abs(x2 - x1) >= mCornerRadius;
    }

    private int getX(float x) {
        return (int) (x / mMaxX * getWidth());
    }

    private int getY(float y) {
        return (int) (getHeight() * (1 - (y / mMaxY)));
    }

    private void updateGradient() {
        mFillPaint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                getColor(mAccentColor, .2f), 0, TileMode.CLAMP));
    }

    private int getColor(int color, float alphaScale) {
        return (color & (((int) (0xff * alphaScale) << 24) | 0xffffff));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Draw lines across the top, middle, and bottom.
        if (mMiddleDividerLoc != 0) {
            drawDivider(0, canvas, mTopDividerTint);
        }
        drawDivider((int) ((canvas.getHeight() - mDividerSize) * mMiddleDividerLoc), canvas,
                mMiddleDividerTint);
        drawDivider(canvas.getHeight() - mDividerSize, canvas, -1);

        if (mLocalPaths.size() == 0) {
            return;
        }
        if (mShowProjection) {
            drawProjection(canvas);
        }
        drawFilledPath(canvas);
        drawLinePath(canvas);
    }

    private void drawProjection(Canvas canvas) {
        mPath.reset();
        int x = mLocalPaths.keyAt(mLocalPaths.size() - 2);
        int y = mLocalPaths.valueAt(mLocalPaths.size() - 2);
        mPath.moveTo(x, y);
        mPath.lineTo(canvas.getWidth(), mProjectUp ? 0 : canvas.getHeight());
        canvas.drawPath(mPath, mDottedPaint);
    }

    private void drawLinePath(Canvas canvas) {
        mPath.reset();
        mPath.moveTo(mLocalPaths.keyAt(0), mLocalPaths.valueAt(0));
        for (int i = 1; i < mLocalPaths.size(); i++) {
            int x = mLocalPaths.keyAt(i);
            int y = mLocalPaths.valueAt(i);
            if (y == PATH_DELIM) {
                if (++i < mLocalPaths.size()) {
                    mPath.moveTo(mLocalPaths.keyAt(i), mLocalPaths.valueAt(i));
                }
            } else {
                mPath.lineTo(x, y);
            }
        }
        canvas.drawPath(mPath, mLinePaint);
    }

    private void drawFilledPath(Canvas canvas) {
        mPath.reset();
        float lastStartX = mLocalPaths.keyAt(0);
        mPath.moveTo(mLocalPaths.keyAt(0), mLocalPaths.valueAt(0));
        for (int i = 1; i < mLocalPaths.size(); i++) {
            int x = mLocalPaths.keyAt(i);
            int y = mLocalPaths.valueAt(i);
            if (y == PATH_DELIM) {
                mPath.lineTo(mLocalPaths.keyAt(i - 1), getHeight());
                mPath.lineTo(lastStartX, getHeight());
                mPath.close();
                if (++i < mLocalPaths.size()) {
                    lastStartX = mLocalPaths.keyAt(i);
                    mPath.moveTo(mLocalPaths.keyAt(i), mLocalPaths.valueAt(i));
                }
            } else {
                mPath.lineTo(x, y);
            }
        }
        canvas.drawPath(mPath, mFillPaint);
    }

    private void drawDivider(int y, Canvas canvas, int tintColor) {
        Drawable d = mDivider;
        if (tintColor != -1) {
            mTintedDivider.setTint(tintColor);
            d = mTintedDivider;
        }
        d.setBounds(0, y, canvas.getWidth(), y + mDividerSize);
        d.draw(canvas);
    }
}
