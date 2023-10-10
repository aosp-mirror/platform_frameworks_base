/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.google.android.test.handwritingime;

import static com.google.android.test.handwritingime.HandwritingIme.BOUNDS_INFO_EDITOR_BOUNDS;
import static com.google.android.test.handwritingime.HandwritingIme.BOUNDS_INFO_NONE;
import static com.google.android.test.handwritingime.HandwritingIme.BOUNDS_INFO_VISIBLE_LINE_BOUNDS;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorBoundsInfo;

import androidx.annotation.Nullable;

import com.android.internal.graphics.ColorUtils;

import java.util.List;

public class BoundsInfoDrawHelper {
    private static final Paint sPaint = new Paint();
    private static final int EDITOR_BOUNDS_COLOR =
            ColorUtils.setAlphaComponent(Color.DKGRAY, 128);
    private static final int HANDWRITING_BOUNDS_COLOR =
            ColorUtils.setAlphaComponent(Color.BLUE, 128);
    private static final int VISIBLE_LINE_BOUNDS_COLOR =
            ColorUtils.setAlphaComponent(Color.MAGENTA, 128);

    public static void draw(Canvas canvas, View inkView, int boundsInfoMode,
            CursorAnchorInfo cursorAnchorInfo) {
        if (boundsInfoMode == BOUNDS_INFO_NONE || cursorAnchorInfo == null) {
            return;
        }

        // The matrix in CursorAnchorInfo transforms the editor coordinates to on-screen
        // coordinates. We then transform the matrix from the on-screen coordinates to the
        // inkView's coordinates. So the result matrix transforms the editor coordinates
        // to the inkView coordinates.
        final Matrix matrix = cursorAnchorInfo.getMatrix();
        inkView.transformMatrixToLocal(matrix);

        if ((boundsInfoMode & BOUNDS_INFO_EDITOR_BOUNDS) != 0) {
            drawEditorBoundsInfo(canvas, matrix, cursorAnchorInfo.getEditorBoundsInfo());
        }

        if ((boundsInfoMode & BOUNDS_INFO_VISIBLE_LINE_BOUNDS) != 0) {
            drawVisibleLineBounds(canvas, matrix, cursorAnchorInfo.getVisibleLineBounds());
        }
    }

    private static void setPaintForEditorBoundsInfo() {
        sPaint.reset();
        sPaint.setStyle(Paint.Style.STROKE);
        sPaint.setStrokeWidth(5f);
    }

    private static void drawEditorBoundsInfo(Canvas canvas, Matrix matrix,
            @Nullable EditorBoundsInfo editorBoundsInfo) {
        if (editorBoundsInfo == null) {
            return;
        }
        final RectF editorBounds = editorBoundsInfo.getEditorBounds();
        setPaintForEditorBoundsInfo();
        if (editorBounds != null) {
            final RectF localEditorBounds = new RectF(editorBounds);
            matrix.mapRect(localEditorBounds);
            sPaint.setColor(EDITOR_BOUNDS_COLOR);
            canvas.drawRect(localEditorBounds, sPaint);
        }

        final RectF handwritingBounds = editorBoundsInfo.getHandwritingBounds();
        if (handwritingBounds != null) {
            final RectF localHandwritingBounds = new RectF(handwritingBounds);
            matrix.mapRect(localHandwritingBounds);
            sPaint.setColor(HANDWRITING_BOUNDS_COLOR);
            canvas.drawRect(localHandwritingBounds, sPaint);
        }
    }

    private static void setPaintForVisibleLineBounds() {
        sPaint.reset();
        sPaint.setStyle(Paint.Style.STROKE);
        sPaint.setStrokeWidth(2f);
        sPaint.setColor(VISIBLE_LINE_BOUNDS_COLOR);
    }

    private static void drawVisibleLineBounds(Canvas canvas, Matrix matrix,
            List<RectF> visibleLineBounds) {
        if (visibleLineBounds.isEmpty()) {
            return;
        }
        setPaintForVisibleLineBounds();
        for (RectF lineBound : visibleLineBounds) {
            matrix.mapRect(lineBound);
            canvas.drawRect(lineBound, sPaint);
        }
    }
}
