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

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.operations.BitmapData;
import com.android.internal.widget.remotecompose.core.operations.ClickArea;
import com.android.internal.widget.remotecompose.core.operations.ClipPath;
import com.android.internal.widget.remotecompose.core.operations.ClipRect;
import com.android.internal.widget.remotecompose.core.operations.ColorConstant;
import com.android.internal.widget.remotecompose.core.operations.ColorExpression;
import com.android.internal.widget.remotecompose.core.operations.ComponentValue;
import com.android.internal.widget.remotecompose.core.operations.DataListFloat;
import com.android.internal.widget.remotecompose.core.operations.DataListIds;
import com.android.internal.widget.remotecompose.core.operations.DataMapIds;
import com.android.internal.widget.remotecompose.core.operations.DataMapLookup;
import com.android.internal.widget.remotecompose.core.operations.DrawArc;
import com.android.internal.widget.remotecompose.core.operations.DrawBitmap;
import com.android.internal.widget.remotecompose.core.operations.DrawBitmapInt;
import com.android.internal.widget.remotecompose.core.operations.DrawBitmapScaled;
import com.android.internal.widget.remotecompose.core.operations.DrawCircle;
import com.android.internal.widget.remotecompose.core.operations.DrawLine;
import com.android.internal.widget.remotecompose.core.operations.DrawOval;
import com.android.internal.widget.remotecompose.core.operations.DrawPath;
import com.android.internal.widget.remotecompose.core.operations.DrawRect;
import com.android.internal.widget.remotecompose.core.operations.DrawRoundRect;
import com.android.internal.widget.remotecompose.core.operations.DrawSector;
import com.android.internal.widget.remotecompose.core.operations.DrawText;
import com.android.internal.widget.remotecompose.core.operations.DrawTextAnchored;
import com.android.internal.widget.remotecompose.core.operations.DrawTextOnPath;
import com.android.internal.widget.remotecompose.core.operations.DrawTweenPath;
import com.android.internal.widget.remotecompose.core.operations.FloatConstant;
import com.android.internal.widget.remotecompose.core.operations.FloatExpression;
import com.android.internal.widget.remotecompose.core.operations.Header;
import com.android.internal.widget.remotecompose.core.operations.IntegerExpression;
import com.android.internal.widget.remotecompose.core.operations.MatrixRestore;
import com.android.internal.widget.remotecompose.core.operations.MatrixRotate;
import com.android.internal.widget.remotecompose.core.operations.MatrixSave;
import com.android.internal.widget.remotecompose.core.operations.MatrixScale;
import com.android.internal.widget.remotecompose.core.operations.MatrixSkew;
import com.android.internal.widget.remotecompose.core.operations.MatrixTranslate;
import com.android.internal.widget.remotecompose.core.operations.NamedVariable;
import com.android.internal.widget.remotecompose.core.operations.PaintData;
import com.android.internal.widget.remotecompose.core.operations.PathAppend;
import com.android.internal.widget.remotecompose.core.operations.PathCreate;
import com.android.internal.widget.remotecompose.core.operations.PathData;
import com.android.internal.widget.remotecompose.core.operations.PathTween;
import com.android.internal.widget.remotecompose.core.operations.RootContentBehavior;
import com.android.internal.widget.remotecompose.core.operations.RootContentDescription;
import com.android.internal.widget.remotecompose.core.operations.ShaderData;
import com.android.internal.widget.remotecompose.core.operations.TextData;
import com.android.internal.widget.remotecompose.core.operations.TextFromFloat;
import com.android.internal.widget.remotecompose.core.operations.TextLength;
import com.android.internal.widget.remotecompose.core.operations.TextLookup;
import com.android.internal.widget.remotecompose.core.operations.TextLookupInt;
import com.android.internal.widget.remotecompose.core.operations.TextMeasure;
import com.android.internal.widget.remotecompose.core.operations.TextMerge;
import com.android.internal.widget.remotecompose.core.operations.Theme;
import com.android.internal.widget.remotecompose.core.operations.TouchExpression;
import com.android.internal.widget.remotecompose.core.operations.layout.CanvasContent;
import com.android.internal.widget.remotecompose.core.operations.layout.ClickModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.ComponentEnd;
import com.android.internal.widget.remotecompose.core.operations.layout.ComponentStart;
import com.android.internal.widget.remotecompose.core.operations.layout.LayoutComponentContent;
import com.android.internal.widget.remotecompose.core.operations.layout.LoopEnd;
import com.android.internal.widget.remotecompose.core.operations.layout.LoopOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.OperationsListEnd;
import com.android.internal.widget.remotecompose.core.operations.layout.RootLayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.TouchCancelModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.TouchDownModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.TouchUpModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.animation.AnimationSpec;
import com.android.internal.widget.remotecompose.core.operations.layout.managers.BoxLayout;
import com.android.internal.widget.remotecompose.core.operations.layout.managers.CanvasLayout;
import com.android.internal.widget.remotecompose.core.operations.layout.managers.ColumnLayout;
import com.android.internal.widget.remotecompose.core.operations.layout.managers.RowLayout;
import com.android.internal.widget.remotecompose.core.operations.layout.managers.StateLayout;
import com.android.internal.widget.remotecompose.core.operations.layout.managers.TextLayout;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.BackgroundModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.BorderModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ClipRectModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ComponentVisibilityOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.GraphicsLayerModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.HeightModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.HostActionOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.HostNamedActionOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.MarqueeModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.OffsetModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.PaddingModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.RippleModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.RoundedClipRectModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ScrollModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ValueFloatChangeActionOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ValueFloatExpressionChangeActionOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ValueIntegerChangeActionOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ValueIntegerExpressionChangeActionOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ValueStringChangeActionOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.WidthModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ZIndexModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.IntMap;
import com.android.internal.widget.remotecompose.core.semantics.CoreSemantics;
import com.android.internal.widget.remotecompose.core.types.BooleanConstant;
import com.android.internal.widget.remotecompose.core.types.IntegerConstant;
import com.android.internal.widget.remotecompose.core.types.LongConstant;

/** List of operations supported in a RemoteCompose document */
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
    // TODO reorder before submitting
    public static final int ACCESSIBILITY_SEMANTICS = 250;
    //    public static final int ACCESSIBILITY_CUSTOM_ACTION = 251;

    ////////////////////////////////////////
    // Draw commands
    ////////////////////////////////////////
    public static final int DRAW_BITMAP = 44;
    public static final int DRAW_BITMAP_INT = 66;
    public static final int DATA_BITMAP = 101;
    public static final int DATA_SHADER = 45;
    public static final int DATA_TEXT = 102;

    ///////////////////////////// =====================
    public static final int CLIP_PATH = 38;
    public static final int CLIP_RECT = 39;
    public static final int PAINT_VALUES = 40;
    public static final int DRAW_RECT = 42;
    public static final int DRAW_TEXT_RUN = 43;
    public static final int DRAW_CIRCLE = 46;
    public static final int DRAW_LINE = 47;
    public static final int DRAW_ROUND_RECT = 51;
    public static final int DRAW_SECTOR = 52;
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
    public static final int NAMED_VARIABLE = 137;
    public static final int COLOR_CONSTANT = 138;
    public static final int DATA_INT = 140;
    public static final int DATA_BOOLEAN = 143;
    public static final int INTEGER_EXPRESSION = 144;
    public static final int ID_MAP = 145;
    public static final int ID_LIST = 146;
    public static final int FLOAT_LIST = 147;
    public static final int DATA_LONG = 148;
    public static final int DRAW_BITMAP_SCALED = 149;
    public static final int TEXT_LOOKUP = 151;
    public static final int DRAW_ARC = 152;
    public static final int TEXT_LOOKUP_INT = 153;
    public static final int DATA_MAP_LOOKUP = 154;
    public static final int TEXT_MEASURE = 155;
    public static final int TEXT_LENGTH = 156;
    public static final int TOUCH_EXPRESSION = 157;
    public static final int PATH_TWEEN = 158;
    public static final int PATH_CREATE = 159;
    public static final int PATH_ADD = 160;

    ///////////////////////////////////////// ======================

    ////////////////////////////////////////
    // Layout commands
    ////////////////////////////////////////

    public static final int LAYOUT_ROOT = 200;
    public static final int LAYOUT_CONTENT = 201;
    public static final int LAYOUT_BOX = 202;
    public static final int LAYOUT_ROW = 203;
    public static final int LAYOUT_COLUMN = 204;
    public static final int LAYOUT_CANVAS = 205;
    public static final int LAYOUT_CANVAS_CONTENT = 207;
    public static final int LAYOUT_TEXT = 208;
    public static final int LAYOUT_STATE = 217;

    public static final int COMPONENT_START = 2;
    public static final int COMPONENT_END = 3;

    public static final int MODIFIER_WIDTH = 16;
    public static final int MODIFIER_HEIGHT = 67;
    public static final int MODIFIER_BACKGROUND = 55;
    public static final int MODIFIER_BORDER = 107;
    public static final int MODIFIER_PADDING = 58;
    public static final int MODIFIER_CLIP_RECT = 108;
    public static final int MODIFIER_ROUNDED_CLIP_RECT = 54;

    public static final int MODIFIER_CLICK = 59;
    public static final int MODIFIER_TOUCH_DOWN = 219;
    public static final int MODIFIER_TOUCH_UP = 220;
    public static final int MODIFIER_TOUCH_CANCEL = 225;

    public static final int OPERATIONS_LIST_END = 214;

    public static final int MODIFIER_OFFSET = 221;
    public static final int MODIFIER_ZINDEX = 223;
    public static final int MODIFIER_GRAPHICS_LAYER = 224;
    public static final int MODIFIER_SCROLL = 226;
    public static final int MODIFIER_MARQUEE = 228;
    public static final int MODIFIER_RIPPLE = 229;

    public static final int LOOP_START = 215;
    public static final int LOOP_END = 216;

    public static final int MODIFIER_VISIBILITY = 211;
    public static final int HOST_ACTION = 209;
    public static final int HOST_NAMED_ACTION = 210;

    public static final int VALUE_INTEGER_CHANGE_ACTION = 212;
    public static final int VALUE_STRING_CHANGE_ACTION = 213;
    public static final int VALUE_INTEGER_EXPRESSION_CHANGE_ACTION = 218;
    public static final int VALUE_FLOAT_CHANGE_ACTION = 222;
    public static final int VALUE_FLOAT_EXPRESSION_CHANGE_ACTION = 227;

    public static final int ANIMATION_SPEC = 14;

    public static final int COMPONENT_VALUE = 150;

    @NonNull public static UniqueIntMap<CompanionOperation> map = new UniqueIntMap<>();

    static class UniqueIntMap<T> extends IntMap<T> {
        @Override
        public T put(int key, @NonNull T value) {
            assert null == get(key) : "Opcode " + key + " already used in Operations !";
            return super.put(key, value);
        }
    }

    static {
        map.put(HEADER, Header::read);
        map.put(DRAW_BITMAP_INT, DrawBitmapInt::read);
        map.put(DATA_BITMAP, BitmapData::read);
        map.put(DATA_TEXT, TextData::read);
        map.put(THEME, Theme::read);
        map.put(CLICK_AREA, ClickArea::read);
        map.put(ROOT_CONTENT_BEHAVIOR, RootContentBehavior::read);
        map.put(ROOT_CONTENT_DESCRIPTION, RootContentDescription::read);

        map.put(DRAW_SECTOR, DrawSector::read);
        map.put(DRAW_BITMAP, DrawBitmap::read);
        map.put(DRAW_CIRCLE, DrawCircle::read);
        map.put(DRAW_LINE, DrawLine::read);
        map.put(DRAW_OVAL, DrawOval::read);
        map.put(DRAW_PATH, DrawPath::read);
        map.put(DRAW_RECT, DrawRect::read);
        map.put(DRAW_ROUND_RECT, DrawRoundRect::read);
        map.put(DRAW_TEXT_ON_PATH, DrawTextOnPath::read);
        map.put(DRAW_TEXT_RUN, DrawText::read);
        map.put(DRAW_TWEEN_PATH, DrawTweenPath::read);
        map.put(DATA_PATH, PathData::read);
        map.put(PAINT_VALUES, PaintData::read);
        map.put(MATRIX_RESTORE, MatrixRestore::read);
        map.put(MATRIX_ROTATE, MatrixRotate::read);
        map.put(MATRIX_SAVE, MatrixSave::read);
        map.put(MATRIX_SCALE, MatrixScale::read);
        map.put(MATRIX_SKEW, MatrixSkew::read);
        map.put(MATRIX_TRANSLATE, MatrixTranslate::read);
        map.put(CLIP_PATH, ClipPath::read);
        map.put(CLIP_RECT, ClipRect::read);
        map.put(DATA_SHADER, ShaderData::read);
        map.put(DATA_FLOAT, FloatConstant::read);
        map.put(ANIMATED_FLOAT, FloatExpression::read);
        map.put(DRAW_TEXT_ANCHOR, DrawTextAnchored::read);
        map.put(COLOR_EXPRESSIONS, ColorExpression::read);
        map.put(TEXT_FROM_FLOAT, TextFromFloat::read);
        map.put(TEXT_MERGE, TextMerge::read);
        map.put(NAMED_VARIABLE, NamedVariable::read);
        map.put(COLOR_CONSTANT, ColorConstant::read);
        map.put(DATA_INT, IntegerConstant::read);
        map.put(INTEGER_EXPRESSION, IntegerExpression::read);
        map.put(DATA_BOOLEAN, BooleanConstant::read);
        map.put(ID_MAP, DataMapIds::read);
        map.put(ID_LIST, DataListIds::read);
        map.put(FLOAT_LIST, DataListFloat::read);
        map.put(DATA_LONG, LongConstant::read);
        map.put(DRAW_BITMAP_SCALED, DrawBitmapScaled::read);
        map.put(TEXT_LOOKUP, TextLookup::read);
        map.put(TEXT_LOOKUP_INT, TextLookupInt::read);

        map.put(LOOP_START, LoopOperation::read);
        map.put(LOOP_END, LoopEnd::read);

        // Layout

        map.put(COMPONENT_START, ComponentStart::read);
        map.put(COMPONENT_END, ComponentEnd::read);
        map.put(ANIMATION_SPEC, AnimationSpec::read);

        map.put(MODIFIER_WIDTH, WidthModifierOperation::read);
        map.put(MODIFIER_HEIGHT, HeightModifierOperation::read);
        map.put(MODIFIER_PADDING, PaddingModifierOperation::read);
        map.put(MODIFIER_BACKGROUND, BackgroundModifierOperation::read);
        map.put(MODIFIER_BORDER, BorderModifierOperation::read);
        map.put(MODIFIER_ROUNDED_CLIP_RECT, RoundedClipRectModifierOperation::read);
        map.put(MODIFIER_CLIP_RECT, ClipRectModifierOperation::read);
        map.put(MODIFIER_CLICK, ClickModifierOperation::read);
        map.put(MODIFIER_TOUCH_DOWN, TouchDownModifierOperation::read);
        map.put(MODIFIER_TOUCH_UP, TouchUpModifierOperation::read);
        map.put(MODIFIER_TOUCH_CANCEL, TouchCancelModifierOperation::read);
        map.put(MODIFIER_VISIBILITY, ComponentVisibilityOperation::read);
        map.put(MODIFIER_OFFSET, OffsetModifierOperation::read);
        map.put(MODIFIER_ZINDEX, ZIndexModifierOperation::read);
        map.put(MODIFIER_GRAPHICS_LAYER, GraphicsLayerModifierOperation::read);
        map.put(MODIFIER_SCROLL, ScrollModifierOperation::read);
        map.put(MODIFIER_MARQUEE, MarqueeModifierOperation::read);
        map.put(MODIFIER_RIPPLE, RippleModifierOperation::read);

        map.put(OPERATIONS_LIST_END, OperationsListEnd::read);

        map.put(HOST_ACTION, HostActionOperation::read);
        map.put(HOST_NAMED_ACTION, HostNamedActionOperation::read);
        map.put(VALUE_INTEGER_CHANGE_ACTION, ValueIntegerChangeActionOperation::read);
        map.put(
                VALUE_INTEGER_EXPRESSION_CHANGE_ACTION,
                ValueIntegerExpressionChangeActionOperation::read);
        map.put(VALUE_STRING_CHANGE_ACTION, ValueStringChangeActionOperation::read);
        map.put(VALUE_FLOAT_CHANGE_ACTION, ValueFloatChangeActionOperation::read);
        map.put(
                VALUE_FLOAT_EXPRESSION_CHANGE_ACTION,
                ValueFloatExpressionChangeActionOperation::read);

        map.put(LAYOUT_ROOT, RootLayoutComponent::read);
        map.put(LAYOUT_CONTENT, LayoutComponentContent::read);
        map.put(LAYOUT_BOX, BoxLayout::read);
        map.put(LAYOUT_COLUMN, ColumnLayout::read);
        map.put(LAYOUT_ROW, RowLayout::read);
        map.put(LAYOUT_CANVAS, CanvasLayout::read);
        map.put(LAYOUT_CANVAS_CONTENT, CanvasContent::read);
        map.put(LAYOUT_TEXT, TextLayout::read);

        map.put(LAYOUT_STATE, StateLayout::read);

        map.put(COMPONENT_VALUE, ComponentValue::read);
        map.put(DRAW_ARC, DrawArc::read);
        map.put(DATA_MAP_LOOKUP, DataMapLookup::read);
        map.put(TEXT_MEASURE, TextMeasure::read);
        map.put(TEXT_LENGTH, TextLength::read);
        map.put(TOUCH_EXPRESSION, TouchExpression::read);
        map.put(PATH_TWEEN, PathTween::read);
        map.put(PATH_CREATE, PathCreate::read);
        map.put(PATH_ADD, PathAppend::read);

        map.put(ACCESSIBILITY_SEMANTICS, CoreSemantics::read);
        //        map.put(ACCESSIBILITY_CUSTOM_ACTION, CoreSemantics::read);
    }
}
