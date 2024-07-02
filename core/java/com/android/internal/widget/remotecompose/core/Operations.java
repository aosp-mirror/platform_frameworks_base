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
package com.android.internal.widget.remotecompose.core;

import com.android.internal.widget.remotecompose.core.operations.BitmapData;
import com.android.internal.widget.remotecompose.core.operations.ClickArea;
import com.android.internal.widget.remotecompose.core.operations.ClipPath;
import com.android.internal.widget.remotecompose.core.operations.ClipRect;
import com.android.internal.widget.remotecompose.core.operations.ColorExpression;
import com.android.internal.widget.remotecompose.core.operations.DrawArc;
import com.android.internal.widget.remotecompose.core.operations.DrawBitmap;
import com.android.internal.widget.remotecompose.core.operations.DrawBitmapInt;
import com.android.internal.widget.remotecompose.core.operations.DrawCircle;
import com.android.internal.widget.remotecompose.core.operations.DrawLine;
import com.android.internal.widget.remotecompose.core.operations.DrawOval;
import com.android.internal.widget.remotecompose.core.operations.DrawPath;
import com.android.internal.widget.remotecompose.core.operations.DrawRect;
import com.android.internal.widget.remotecompose.core.operations.DrawRoundRect;
import com.android.internal.widget.remotecompose.core.operations.DrawText;
import com.android.internal.widget.remotecompose.core.operations.DrawTextAnchored;
import com.android.internal.widget.remotecompose.core.operations.DrawTextOnPath;
import com.android.internal.widget.remotecompose.core.operations.DrawTweenPath;
import com.android.internal.widget.remotecompose.core.operations.FloatConstant;
import com.android.internal.widget.remotecompose.core.operations.FloatExpression;
import com.android.internal.widget.remotecompose.core.operations.Header;
import com.android.internal.widget.remotecompose.core.operations.MatrixRestore;
import com.android.internal.widget.remotecompose.core.operations.MatrixRotate;
import com.android.internal.widget.remotecompose.core.operations.MatrixSave;
import com.android.internal.widget.remotecompose.core.operations.MatrixScale;
import com.android.internal.widget.remotecompose.core.operations.MatrixSkew;
import com.android.internal.widget.remotecompose.core.operations.MatrixTranslate;
import com.android.internal.widget.remotecompose.core.operations.PaintData;
import com.android.internal.widget.remotecompose.core.operations.PathData;
import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.core.operations.RootContentDescription;
import com.android.internal.widget.remotecompose.core.operations.ShaderData;
import com.android.internal.widget.remotecompose.core.operations.TextData;
import com.android.internal.widget.remotecompose.core.operations.TextFromFloat;
import com.android.internal.widget.remotecompose.core.operations.TextMerge;
import com.android.internal.widget.remotecompose.core.operations.Theme;
import com.android.internal.widget.remotecompose.core.operations.utilities.IntMap;

/**
 * List of operations supported in a RemoteCompose document
 */
public class Operations {

    ////////////////////////////////////////
    // Protocol
    ////////////////////////////////////////
    public static final int HEADER = 0;
    public static final int LOAD_BITMAP = 4;
    public static final int THEME = 63;
    public static final int CLICK_AREA = 64;
    public static final int ROOT_CONTENT_BEHAVIOR = 65;
    public static final int ROOT_CONTENT_DESCRIPTION = 103;

    ////////////////////////////////////////
    // Draw commands
    ////////////////////////////////////////
    public static final int DRAW_BITMAP = 44;
    public static final int DRAW_BITMAP_INT = 66;
    public static final int DATA_BITMAP = 101;
    public static final int DATA_SHADER = 45;
    public static final int DATA_TEXT = 102;

    /////////////////////////////=====================
    public static final int CLIP_PATH = 38;
    public static final int CLIP_RECT = 39;
    public static final int PAINT_VALUES = 40;
    public static final int DRAW_RECT = 42;
    public static final int DRAW_TEXT_RUN = 43;
    public static final int DRAW_CIRCLE = 46;
    public static final int DRAW_LINE = 47;
    public static final int DRAW_ROUND_RECT = 51;
    public static final int DRAW_ARC = 52;
    public static final int DRAW_TEXT_ON_PATH = 53;
    public static final int DRAW_OVAL = 56;
    public static final int DATA_PATH = 123;
    public static final int DRAW_PATH = 124;
    public static final int DRAW_TWEEN_PATH = 125;
    public static final int MATRIX_SCALE = 126;
    public static final int MATRIX_TRANSLATE = 127;
    public static final int MATRIX_SKEW = 128;
    public static final int MATRIX_ROTATE = 129;
    public static final int MATRIX_SAVE = 130;
    public static final int MATRIX_RESTORE = 131;
    public static final int MATRIX_SET = 132;
    public static final int DATA_FLOAT = 80;
    public static final int ANIMATED_FLOAT = 81;
    public static final int DRAW_TEXT_ANCHOR = 133;
    public static final int COLOR_EXPRESSIONS = 134;
    public static final int TEXT_FROM_FLOAT = 135;
    public static final int TEXT_MERGE = 136;

    /////////////////////////////////////////======================
    public static IntMap<CompanionOperation> map = new IntMap<>();

    static {
        map.put(HEADER, Header.COMPANION);
        map.put(DRAW_BITMAP_INT, DrawBitmapInt.COMPANION);
        map.put(DATA_BITMAP, BitmapData.COMPANION);
        map.put(DATA_TEXT, TextData.COMPANION);
        map.put(THEME, Theme.COMPANION);
        map.put(CLICK_AREA, ClickArea.COMPANION);
        map.put(ROOT_CONTENT_BEHAVIOR, RootContentBehavior.COMPANION);
        map.put(ROOT_CONTENT_DESCRIPTION, RootContentDescription.COMPANION);

        map.put(DRAW_ARC, DrawArc.COMPANION);
        map.put(DRAW_BITMAP, DrawBitmap.COMPANION);
        map.put(DRAW_CIRCLE, DrawCircle.COMPANION);
        map.put(DRAW_LINE, DrawLine.COMPANION);
        map.put(DRAW_OVAL, DrawOval.COMPANION);
        map.put(DRAW_PATH, DrawPath.COMPANION);
        map.put(DRAW_RECT, DrawRect.COMPANION);
        map.put(DRAW_ROUND_RECT, DrawRoundRect.COMPANION);
        map.put(DRAW_TEXT_ON_PATH, DrawTextOnPath.COMPANION);
        map.put(DRAW_TEXT_RUN, DrawText.COMPANION);
        map.put(DRAW_TWEEN_PATH, DrawTweenPath.COMPANION);
        map.put(DATA_PATH, PathData.COMPANION);
        map.put(PAINT_VALUES, PaintData.COMPANION);
        map.put(MATRIX_RESTORE, MatrixRestore.COMPANION);
        map.put(MATRIX_ROTATE, MatrixRotate.COMPANION);
        map.put(MATRIX_SAVE, MatrixSave.COMPANION);
        map.put(MATRIX_SCALE, MatrixScale.COMPANION);
        map.put(MATRIX_SKEW, MatrixSkew.COMPANION);
        map.put(MATRIX_TRANSLATE, MatrixTranslate.COMPANION);
        map.put(CLIP_PATH, ClipPath.COMPANION);
        map.put(CLIP_RECT, ClipRect.COMPANION);
        map.put(DATA_SHADER, ShaderData.COMPANION);
        map.put(DATA_FLOAT, FloatConstant.COMPANION);
        map.put(ANIMATED_FLOAT, FloatExpression.COMPANION);
        map.put(DRAW_TEXT_ANCHOR, DrawTextAnchored.COMPANION);
        map.put(COLOR_EXPRESSIONS, ColorExpression.COMPANION);
        map.put(TEXT_FROM_FLOAT, TextFromFloat.COMPANION);
        map.put(TEXT_MERGE, TextMerge.COMPANION);

    }

}
