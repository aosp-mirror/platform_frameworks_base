/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.test.voiceinteraction;

import android.annotation.Nullable;
import android.app.assist.AssistStructure;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;

public class AssistVisualizer extends View {
    static final String TAG = "AssistVisualizer";

    static class TextEntry {
        final Rect bounds;
        final int parentLeft, parentTop;
        final Matrix matrix;
        final String className;
        final CharSequence text;
        final int scrollY;
        final int[] lineCharOffsets;
        final int[] lineBaselines;

        TextEntry(AssistStructure.ViewNode node, int parentLeft, int parentTop, Matrix matrix) {
            int left = parentLeft+node.getLeft();
            int top = parentTop+node.getTop();
            bounds = new Rect(left, top, left+node.getWidth(), top+node.getHeight());
            this.parentLeft = parentLeft;
            this.parentTop = parentTop;
            this.matrix = new Matrix(matrix);
            this.className = node.getClassName();
            this.text = node.getText() != null ? node.getText() : node.getContentDescription();
            this.scrollY = node.getScrollY();
            this.lineCharOffsets = node.getTextLineCharOffsets();
            this.lineBaselines = node.getTextLineBaselines();
        }
    }

    AssistStructure mAssistStructure;
    final Paint mFramePaint = new Paint();
    final Paint mFrameBaselinePaint = new Paint();
    final Paint mFrameNoTransformPaint = new Paint();
    final ArrayList<Matrix> mMatrixStack = new ArrayList<>();
    final ArrayList<TextEntry> mTextRects = new ArrayList<>();
    final int[] mTmpLocation = new int[2];

    public AssistVisualizer(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        mFramePaint.setColor(0xffff0000);
        mFramePaint.setStyle(Paint.Style.STROKE);
        mFramePaint.setStrokeWidth(0);
        mFrameBaselinePaint.setColor(0xa0b0b000);
        mFrameBaselinePaint.setStyle(Paint.Style.STROKE);
        mFrameBaselinePaint.setStrokeWidth(0);
        float density = getResources().getDisplayMetrics().density;
        mFramePaint.setShadowLayer(density, density, density, 0xff000000);
        mFrameNoTransformPaint.setColor(0xff0000ff);
        mFrameNoTransformPaint.setStyle(Paint.Style.STROKE);
        mFrameNoTransformPaint.setStrokeWidth(0);
        mFrameNoTransformPaint.setShadowLayer(density, density, density, 0xff000000);
    }

    public void setAssistStructure(AssistStructure as) {
        mAssistStructure = as;
        mTextRects.clear();
        final int N = as.getWindowNodeCount();
        if (N > 0) {
            for (int i=0; i<N; i++) {
                AssistStructure.WindowNode windowNode = as.getWindowNodeAt(i);
                mMatrixStack.clear();
                Matrix matrix = new Matrix();
                matrix.setTranslate(windowNode.getLeft(), windowNode.getTop());
                mMatrixStack.add(matrix);
                buildTextRects(windowNode.getRootViewNode(), 0, windowNode.getLeft(),
                        windowNode.getTop());
            }
        }
        Log.d(TAG, "Building text rects in " + this + ": found " + mTextRects.size());
        invalidate();
    }

    public void logTree() {
        if (mAssistStructure != null) {
            mAssistStructure.dump();
        }
    }

    public void logText() {
        final int N = mTextRects.size();
        for (int i=0; i<N; i++) {
            TextEntry te = mTextRects.get(i);
            Log.d(TAG, "View " + te.className + " " + te.bounds.toShortString()
                    + " in " + te.parentLeft + "," + te.parentTop
                    + " matrix=" + te.matrix.toShortString() + ": "
                    + te.text);
            if (te.lineCharOffsets != null && te.lineBaselines != null) {
                final int num = te.lineCharOffsets.length < te.lineBaselines.length
                        ? te.lineCharOffsets.length : te.lineBaselines.length;
                for (int j=0; j<num; j++) {
                    Log.d(TAG, "  Line #" + j + ": offset=" + te.lineCharOffsets[j]
                            + " baseline=" + te.lineBaselines[j]);
                }
            }
        }
    }

    public void clearAssistData() {
        mAssistStructure = null;
        mTextRects.clear();
    }

    void buildTextRects(AssistStructure.ViewNode root, int matrixStackIndex,
            int parentLeft, int parentTop) {
        if (root.getVisibility() != View.VISIBLE) {
            return;
        }
        Matrix parentMatrix = mMatrixStack.get(matrixStackIndex);
        matrixStackIndex++;
        Matrix matrix;
        if (mMatrixStack.size() > matrixStackIndex) {
            matrix = mMatrixStack.get(matrixStackIndex);
            matrix.set(parentMatrix);
        } else {
            matrix = new Matrix(parentMatrix);
            mMatrixStack.add(matrix);
        }
        matrix.preTranslate(root.getLeft(), root.getTop());
        int left = parentLeft + root.getLeft();
        int top = parentTop + root.getTop();
        Matrix transform = root.getTransformation();
        if (transform != null) {
            matrix.preConcat(transform);
        }
        if (root.getText() != null || root.getContentDescription() != null) {
            TextEntry te = new TextEntry(root, parentLeft, parentTop, matrix);
            mTextRects.add(te);
        }
        final int N = root.getChildCount();
        if (N > 0) {
            left -= root.getScrollX();
            top -= root.getScrollY();
            matrix.preTranslate(-root.getScrollX(), -root.getScrollY());
            for (int i=0; i<N; i++) {
                AssistStructure.ViewNode child = root.getChildAt(i);
                buildTextRects(child, matrixStackIndex, left, top);
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        getLocationOnScreen(mTmpLocation);
        final int N = mTextRects.size();
        Log.d(TAG, "Drawing text rects in " + this + ": found " + mTextRects.size());
        for (int i=0; i<N; i++) {
            TextEntry te = mTextRects.get(i);
            canvas.drawRect(te.bounds.left - mTmpLocation[0], te.bounds.top - mTmpLocation[1],
                    te.bounds.right - mTmpLocation[0], te.bounds.bottom - mTmpLocation[1],
                    mFrameNoTransformPaint);
        }
        for (int i=0; i<N; i++) {
            TextEntry te = mTextRects.get(i);
            canvas.save();
            canvas.translate(-mTmpLocation[0], -mTmpLocation[1]);
            canvas.concat(te.matrix);
            canvas.drawRect(0, 0, te.bounds.right - te.bounds.left, te.bounds.bottom - te.bounds.top,
                    mFramePaint);
            if (te.lineBaselines != null) {
                for (int j=0; j<te.lineBaselines.length; j++) {
                    canvas.drawLine(0, te.lineBaselines[j] - te.scrollY,
                            te.bounds.right - te.bounds.left, te.lineBaselines[j] - te.scrollY,
                            mFrameBaselinePaint);
                }
            }
            canvas.restore();
        }
    }
}
