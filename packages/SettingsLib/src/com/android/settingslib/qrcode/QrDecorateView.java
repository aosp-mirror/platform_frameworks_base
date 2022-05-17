/**
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.qrcode;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.android.settingslib.R;

public class QrDecorateView extends View {
    private static final float CORNER_STROKE_WIDTH = 4f;    // 4dp
    private static final float CORNER_LINE_LENGTH = 264f;   // 264dp
    private static final float CORNER_RADIUS = 16f;         // 16dp

    final private int mCornerColor;
    final private int mFocusedCornerColor;
    final private int mBackgroundColor;

    final private Paint mStrokePaint;
    final private Paint mTransparentPaint;
    final private Paint mBackgroundPaint;

    final private float mRadius;
    final private float mInnerRidus;

    private Bitmap mMaskBitmap;
    private Canvas mMaskCanvas;

    private RectF mOuterFrame;
    private RectF mInnerFrame;

    private boolean mFocused;

    public QrDecorateView(Context context) {
        this(context, null);
    }

    public QrDecorateView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QrDecorateView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public QrDecorateView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mFocused = false;
        mRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CORNER_RADIUS,
                getResources().getDisplayMetrics());
        // Inner radius needs to minus stroke width for keeping the width of border consistent.
        mInnerRidus = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                CORNER_RADIUS - CORNER_STROKE_WIDTH, getResources().getDisplayMetrics());

        mCornerColor = context.getResources().getColor(R.color.qr_corner_line_color);
        mFocusedCornerColor = context.getResources().getColor(R.color.qr_focused_corner_line_color);
        mBackgroundColor = context.getResources().getColor(R.color.qr_background_color);

        mStrokePaint = new Paint();
        mStrokePaint.setAntiAlias(true);

        mTransparentPaint = new Paint();
        mTransparentPaint.setAntiAlias(true);
        mTransparentPaint.setColor(getResources().getColor(android.R.color.transparent));
        mTransparentPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        mBackgroundPaint = new Paint();
        mBackgroundPaint.setColor(mBackgroundColor);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if(mMaskBitmap == null) {
            mMaskBitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            mMaskCanvas = new Canvas(mMaskBitmap);
        }

        calculateFramePos();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Set frame line color.
        mStrokePaint.setColor(mFocused ? mFocusedCornerColor : mCornerColor);
        // Draw background color.
        mMaskCanvas.drawColor(mBackgroundColor);
        // Draw outer corner.
        mMaskCanvas.drawRoundRect(mOuterFrame, mRadius, mRadius, mStrokePaint);
        // Draw inner transparent corner.
        mMaskCanvas.drawRoundRect(mInnerFrame, mInnerRidus, mInnerRidus, mTransparentPaint);

        canvas.drawBitmap(mMaskBitmap, 0, 0, mBackgroundPaint);
        super.onDraw(canvas);
    }

    private void calculateFramePos() {
        final int centralX = getWidth() / 2;
        final int centralY = getHeight() / 2;
        final float cornerLineLength = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                CORNER_LINE_LENGTH, getResources().getDisplayMetrics()) / 2;
        final float strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                CORNER_STROKE_WIDTH, getResources().getDisplayMetrics());

        mOuterFrame = new RectF(centralX - cornerLineLength, centralY - cornerLineLength,
                centralX + cornerLineLength, centralY + cornerLineLength);
        mInnerFrame = new RectF(mOuterFrame.left + strokeWidth, mOuterFrame.top + strokeWidth,
                mOuterFrame.right - strokeWidth, mOuterFrame.bottom - strokeWidth);
    }

    // Draws green lines if focused. Otherwise, draws white lines.
    public void setFocused(boolean focused) {
        mFocused = focused;
        invalidate();
    }
}
