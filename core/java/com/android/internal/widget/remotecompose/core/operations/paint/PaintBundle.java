/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.paint;

import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.Utils;

import java.util.Arrays;

/**
 * Paint Bundle represents a delta of changes to a paint object
 */
public class PaintBundle {
    int[] mArray = new int[200];
    int[] mOutArray = null;
    int mPos = 0;

    /**
     * Apply changes to a PaintChanges interface
     * @param paintContext
     * @param p
     */
    public void applyPaintChange(PaintContext paintContext, PaintChanges p) {
        int i = 0;
        int mask = 0;
        if (mOutArray == null) {
            mOutArray = mArray;
        }
        while (i < mPos) {
            int cmd = mOutArray[i++];
            mask = mask | (1 << (cmd - 1));
            switch (cmd & 0xFFFF) {
                case TEXT_SIZE: {
                    p.setTextSize(Float.intBitsToFloat(mOutArray[i++]));
                    break;
                }
                case TYPEFACE:
                    int style = (cmd >> 16);
                    int weight = style & 0x3ff;
                    boolean italic = (style >> 10) > 0;
                    int font_type = mOutArray[i++];

                    p.setTypeFace(font_type, weight, italic);
                    break;
                case COLOR_ID: // mOutArray should have already decoded it
                case COLOR: {
                    p.setColor(mOutArray[i++]);
                    break;
                }
                case STROKE_WIDTH: {
                    p.setStrokeWidth(Float.intBitsToFloat(mOutArray[i++]));
                    break;
                }
                case STROKE_MITER: {
                    p.setStrokeMiter(Float.intBitsToFloat(mOutArray[i++]));
                    break;
                }
                case STROKE_CAP: {
                    p.setStrokeCap(cmd >> 16);
                    break;
                }
                case STYLE: {
                    p.setStyle(cmd >> 16);
                    break;
                }
                case SHADER: {
                    p.setShader(mOutArray[i++]);
                    break;
                }
                case STROKE_JOIN: {
                    p.setStrokeJoin(cmd >> 16);
                    break;
                }
                case IMAGE_FILTER_QUALITY: {
                    p.setImageFilterQuality(cmd >> 16);
                    break;
                }
                case BLEND_MODE: {
                    p.setBlendMode(cmd >> 16);
                    break;
                }
                case FILTER_BITMAP: {
                    p.setFilterBitmap(!((cmd >> 16) == 0));
                    break;
                }
                case GRADIENT: {
                    i = callSetGradient(cmd, mOutArray, i, p);
                    break;
                }
                case COLOR_FILTER: {
                    p.setColorFilter(mOutArray[i++], cmd >> 16);
                    break;
                }
                case ALPHA: {
                    p.setAlpha(Float.intBitsToFloat(mOutArray[i++]));
                    break;
                }
            }
        }

        mask = (~mask) & PaintChanges.VALID_BITS;

        p.clear(mask);
    }

    private String toName(int id) {
        switch (id) {
            case TEXT_SIZE:
                return "TEXT_SIZE";
            case COLOR:
                return "COLOR";
            case STROKE_WIDTH:
                return "STROKE_WIDTH";
            case STROKE_MITER:
                return "STROKE_MITER";
            case TYPEFACE:
                return "TYPEFACE";
            case STROKE_CAP:
                return "CAP";
            case STYLE:
                return "STYLE";
            case SHADER:
                return "SHADER";
            case IMAGE_FILTER_QUALITY:
                return "IMAGE_FILTER_QUALITY";
            case BLEND_MODE:
                return "BLEND_MODE";
            case FILTER_BITMAP:
                return "FILTER_BITMAP";
            case GRADIENT:
                return "GRADIENT_LINEAR";
            case ALPHA:
                return "ALPHA";
            case COLOR_FILTER:
                return "COLOR_FILTER";
        }
        return "????" + id + "????";
    }

    private static String colorInt(int color) {
        String str = "000000000000" + Integer.toHexString(color);
        return "0x" + str.substring(str.length() - 8);
    }

    private static String colorInt(int[] color) {
        String str = "[";
        for (int i = 0; i < color.length; i++) {
            if (i > 0) {
                str += ", ";
            }
            str += colorInt(color[i]);
        }
        return str + "]";
    }

    private static String asFloatStr(int value) {
        float fValue = Float.intBitsToFloat(value);
        if (Float.isNaN(fValue)) {
            return "[" + Utils.idFromNan(fValue) + "]";
        }
        return Float.toString(fValue);
    }

    @Override
    public String toString() {
        StringBuilder ret = new StringBuilder("\n");
        int i = 0;
        while (i < mPos) {
            int cmd = mArray[i++];
            int type = cmd & 0xFFFF;
            switch (type) {

                case TEXT_SIZE: {
                    ret.append("    TextSize("
                            + asFloatStr(mArray[i++]));
                }

                break;
                case TYPEFACE: {
                    int style = (cmd >> 16);
                    int weight = style & 0x3ff;
                    boolean italic = (style >> 10) > 0;
                    int font_type = mArray[i++];
                    ret.append("    TypeFace(" + (font_type + ", "
                            + weight + ", " + italic));
                }
                break;
                case COLOR: {
                    ret.append("    Color(" + colorInt(mArray[i++]));
                }
                break;
                case COLOR_ID: {
                    ret.append("    ColorId([" + mArray[i++] + "]");
                }
                break;
                case STROKE_WIDTH: {
                    ret.append("    StrokeWidth("
                            + (asFloatStr(mArray[i++])));
                }
                break;
                case STROKE_MITER: {
                    ret.append("    StrokeMiter("
                            + (asFloatStr(mArray[i++])));
                }
                break;
                case STROKE_CAP: {
                    ret.append("    StrokeCap("
                            + (cmd >> 16));
                }
                break;
                case STYLE: {
                    ret.append("    Style(" + (cmd >> 16));
                }
                break;
                case COLOR_FILTER: {
                    ret.append("    ColorFilter(color="
                            + colorInt(mArray[i++])
                            + ", mode=" + blendModeString(cmd >> 16));
                }
                break;
                case SHADER: {
                    ret.append("    Shader(" + mArray[i++]);
                }
                break;
                case ALPHA: {
                    ret.append("    Alpha("
                            + (asFloatStr(mArray[i++])));
                }
                break;
                case IMAGE_FILTER_QUALITY: {
                    ret.append("    ImageFilterQuality(" + (cmd >> 16));
                }
                break;
                case BLEND_MODE: {
                    ret.append("    BlendMode(" + blendModeString(cmd >> 16));
                }
                break;
                case FILTER_BITMAP: {
                    ret.append("    FilterBitmap("
                            + (!((cmd >> 16) == 0)));
                }
                break;
                case STROKE_JOIN: {
                    ret.append("    StrokeJoin(" + (cmd >> 16));
                }
                break;
                case ANTI_ALIAS: {
                    ret.append("    AntiAlias(" + (cmd >> 16));
                }
                break;
                case GRADIENT: {
                    i = callPrintGradient(cmd, mArray, i, ret);
                }
            }
            ret.append("),\n");
        }
        return ret.toString();
    }

    int callPrintGradient(int cmd, int[] array, int i, StringBuilder p) {
        int ret = i;
        int type = (cmd >> 16);
        switch (type) {

            case 0: {
                p.append("    LinearGradient(\n");
                int len = array[ret++];
                int[] colors = null;
                if (len > 0) {
                    colors = new int[len];
                    for (int j = 0; j < colors.length; j++) {
                        colors[j] = array[ret++];
                    }
                }
                len = array[ret++];
                String[] stops = null;
                if (len > 0) {
                    stops = new String[len];
                    for (int j = 0; j < stops.length; j++) {
                        stops[j] = asFloatStr(array[ret++]);
                    }
                }

                p.append("      colors = " + colorInt(colors) + ",\n");
                p.append("      stops = " + Arrays.toString(stops) + ",\n");
                p.append("      start = ");
                p.append("[" + asFloatStr(array[ret++]));
                p.append(", " + asFloatStr(array[ret++]) + "],\n");
                p.append("      end = ");
                p.append("[" + asFloatStr(array[ret++]));
                p.append(", " + asFloatStr(array[ret++]) + "],\n");
                int tileMode = array[ret++];
                p.append("      tileMode = " + tileMode + "\n    ");
            }

            break;
            case 1: {
                p.append("    RadialGradient(\n");
                int len = array[ret++];
                int[] colors = null;
                if (len > 0) {
                    colors = new int[len];
                    for (int j = 0; j < colors.length; j++) {
                        colors[j] = array[ret++];

                    }
                }
                len = array[ret++];
                String[] stops = null;
                if (len > 0) {
                    stops = new String[len];
                    for (int j = 0; j < stops.length; j++) {
                        stops[j] = asFloatStr(array[ret++]);
                    }
                }

                p.append("      colors = " + colorInt(colors) + ",\n");
                p.append("      stops = " + Arrays.toString(stops) + ",\n");
                p.append("      center = ");
                p.append("[" + asFloatStr(array[ret++]));
                p.append(", " + asFloatStr(array[ret++]) + "],\n");
                p.append("      radius =");
                p.append(" " + asFloatStr(array[ret++]) + ",\n");
                int tileMode = array[ret++];
                p.append("      tileMode = " + tileMode + "\n    ");
            }

            break;
            case 2: {
                p.append("    SweepGradient(\n");
                int len = array[ret++];
                int[] colors = null;
                if (len > 0) {
                    colors = new int[len];
                    for (int j = 0; j < colors.length; j++) {
                        colors[j] = array[ret++];

                    }
                }
                len = array[ret++];
                String[] stops = null;
                if (len > 0) {
                    stops = new String[len];
                    for (int j = 0; j < stops.length; j++) {
                        stops[j] = asFloatStr(array[ret++]);
                    }
                }
                p.append("      colors = " + colorInt(colors) + ",\n");
                p.append("      stops = " + Arrays.toString(stops) + ",\n");
                p.append("      center = ");
                p.append("[" + asFloatStr(array[ret++]));
                p.append(", "
                        + asFloatStr(array[ret++]) + "],\n    ");
            }
            break;
            default: {
                p.append("GRADIENT_??????!!!!");
            }
        }

        return ret;
    }

    int callSetGradient(int cmd, int[] array, int i, PaintChanges p) {
        int ret = i;
        int gradientType = (cmd >> 16);

        int len = array[ret++];
        int[] colors = null;
        if (len > 0) {
            colors = new int[len];
            for (int j = 0; j < colors.length; j++) {
                colors[j] = array[ret++];
            }
        }
        len = array[ret++];
        float[] stops = null;
        if (len > 0) {
            stops = new float[len];
            for (int j = 0; j < colors.length; j++) {
                stops[j] = Float.intBitsToFloat(array[ret++]);
            }
        }

        if (colors == null) {
            return ret;
        }

        switch (gradientType) {

            case LINEAR_GRADIENT: {
                float startX = Float.intBitsToFloat(array[ret++]);
                float startY = Float.intBitsToFloat(array[ret++]);
                float endX = Float.intBitsToFloat(array[ret++]);
                float endY = Float.intBitsToFloat(array[ret++]);
                int tileMode = array[ret++];
                p.setLinearGradient(colors, stops, startX,
                        startY, endX, endY, tileMode);
            }

            break;
            case RADIAL_GRADIENT: {
                float centerX = Float.intBitsToFloat(array[ret++]);
                float centerY = Float.intBitsToFloat(array[ret++]);
                float radius = Float.intBitsToFloat(array[ret++]);
                int tileMode = array[ret++];
                p.setRadialGradient(colors, stops, centerX, centerY,
                        radius, tileMode);
            }
            break;
            case SWEEP_GRADIENT: {
                float centerX = Float.intBitsToFloat(array[ret++]);
                float centerY = Float.intBitsToFloat(array[ret++]);
                p.setSweepGradient(colors, stops, centerX, centerY);
            }
        }

        return ret;
    }

    public void writeBundle(WireBuffer buffer) {
        buffer.writeInt(mPos);
        for (int index = 0; index < mPos; index++) {
            buffer.writeInt(mArray[index]);
        }
    }

    public void readBundle(WireBuffer buffer) {
        int len = buffer.readInt();
        if (len <= 0 || len > 1024) {
            throw new RuntimeException("buffer corrupt paint len = " + len);
        }
        mArray = new int[len];
        for (int i = 0; i < mArray.length; i++) {
            mArray[i] = buffer.readInt();
        }
        mPos = len;
    }

    public static final int TEXT_SIZE = 1;  // float

    public static final int COLOR = 4;  // int
    public static final int STROKE_WIDTH = 5; // float
    public static final int STROKE_MITER = 6;
    public static final int STROKE_CAP = 7; //  int
    public static final int STYLE = 8; // int
    public static final int SHADER = 9; // int
    public static final int IMAGE_FILTER_QUALITY = 10; // int
    public static final int GRADIENT = 11;
    public static final int ALPHA = 12;
    public static final int COLOR_FILTER = 13;
    public static final int ANTI_ALIAS = 14;
    public static final int STROKE_JOIN = 15;
    public static final int TYPEFACE = 16;
    public static final int FILTER_BITMAP = 17;
    public static final int BLEND_MODE = 18;
    public static final int COLOR_ID = 19;  // int

    public static final int BLEND_MODE_CLEAR = 0;
    public static final int BLEND_MODE_SRC = 1;
    public static final int BLEND_MODE_DST = 2;
    public static final int BLEND_MODE_SRC_OVER = 3;
    public static final int BLEND_MODE_DST_OVER = 4;
    public static final int BLEND_MODE_SRC_IN = 5;
    public static final int BLEND_MODE_DST_IN = 6;
    public static final int BLEND_MODE_SRC_OUT = 7;
    public static final int BLEND_MODE_DST_OUT = 8;
    public static final int BLEND_MODE_SRC_ATOP = 9;
    public static final int BLEND_MODE_DST_ATOP = 10;
    public static final int BLEND_MODE_XOR = 11;
    public static final int BLEND_MODE_PLUS = 12;
    public static final int BLEND_MODE_MODULATE = 13;
    public static final int BLEND_MODE_SCREEN = 14;
    public static final int BLEND_MODE_OVERLAY = 15;
    public static final int BLEND_MODE_DARKEN = 16;
    public static final int BLEND_MODE_LIGHTEN = 17;
    public static final int BLEND_MODE_COLOR_DODGE = 18;
    public static final int BLEND_MODE_COLOR_BURN = 19;
    public static final int BLEND_MODE_HARD_LIGHT = 20;
    public static final int BLEND_MODE_SOFT_LIGHT = 21;
    public static final int BLEND_MODE_DIFFERENCE = 22;
    public static final int BLEND_MODE_EXCLUSION = 23;
    public static final int BLEND_MODE_MULTIPLY = 24;
    public static final int BLEND_MODE_HUE = 25;
    public static final int BLEND_MODE_SATURATION = 26;
    public static final int BLEND_MODE_COLOR = 27;
    public static final int BLEND_MODE_LUMINOSITY = 28;
    public static final int BLEND_MODE_NULL = 29;
    public static final int PORTER_MODE_ADD = 30;

    public static final int FONT_NORMAL = 0;
    public static final int FONT_BOLD = 1;
    public static final int FONT_ITALIC = 2;
    public static final int FONT_BOLD_ITALIC = 3;

    public static final int FONT_TYPE_DEFAULT = 0;
    public static final int FONT_TYPE_SANS_SERIF = 1;
    public static final int FONT_TYPE_SERIF = 2;
    public static final int FONT_TYPE_MONOSPACE = 3;

    public static final int STYLE_FILL = 0;
    public static final int STYLE_STROKE = 1;
    public static final int STYLE_FILL_AND_STROKE = 2;
    public static final int LINEAR_GRADIENT = 0;
    public static final int RADIAL_GRADIENT = 1;
    public static final int SWEEP_GRADIENT = 2;

    /**
     * sets a shader that draws a linear gradient along a line.
     *
     * @param startX   The x-coordinate for the start of the gradient line
     * @param startY   The y-coordinate for the start of the gradient line
     * @param endX     The x-coordinate for the end of the gradient line
     * @param endY     The y-coordinate for the end of the gradient line
     * @param colors   The sRGB colors to be distributed along the gradient line
     * @param stops    May be null. The relative positions [0..1] of
     *                 each corresponding color in the colors array. If this is null,
     *                 the colors are distributed evenly along the gradient line.
     * @param tileMode The Shader tiling mode
     */
    public void setLinearGradient(int[] colors,
                                  float[] stops,
                                  float startX,
                                  float startY,
                                  float endX,
                                  float endY,
                                  int tileMode) {
        int startPos = mPos;
        int len;
        mArray[mPos++] = GRADIENT | (LINEAR_GRADIENT << 16);
        mArray[mPos++] = len = (colors == null) ? 0 : colors.length;
        for (int i = 0; i < len; i++) {
            mArray[mPos++] = colors[i];
        }

        mArray[mPos++] = len = (stops == null) ? 0 : stops.length;
        for (int i = 0; i < len; i++) {
            mArray[mPos++] = Float.floatToRawIntBits(stops[i]);
        }
        mArray[mPos++] = Float.floatToRawIntBits(startX);
        mArray[mPos++] = Float.floatToRawIntBits(startY);
        mArray[mPos++] = Float.floatToRawIntBits(endX);
        mArray[mPos++] = Float.floatToRawIntBits(endY);
        mArray[mPos++] = tileMode;
    }

    /**
     * Set a shader that draws a sweep gradient around a center point.
     *
     * @param centerX The x-coordinate of the center
     * @param centerY The y-coordinate of the center
     * @param colors  The sRGB colors to be distributed around the center.
     *                There must be at least 2 colors in the array.
     * @param stops   May be NULL. The relative position of
     *                each corresponding color in the colors array, beginning
     *                with 0 and ending with 1.0. If the values are not
     *                monotonic, the drawing may produce unexpected results.
     *                If positions is NULL, then the colors are automatically
     *                spaced evenly.
     */
    public void setSweepGradient(int[] colors, float[] stops, float centerX, float centerY) {
        int startPos = mPos;
        int len;
        mArray[mPos++] = GRADIENT | (SWEEP_GRADIENT << 16);
        mArray[mPos++] = len = (colors == null) ? 0 : colors.length;
        for (int i = 0; i < len; i++) {
            mArray[mPos++] = colors[i];
        }

        mArray[mPos++] = len = (stops == null) ? 0 : stops.length;
        for (int i = 0; i < len; i++) {
            mArray[mPos++] = Float.floatToRawIntBits(stops[i]);
        }
        mArray[mPos++] = Float.floatToRawIntBits(centerX);
        mArray[mPos++] = Float.floatToRawIntBits(centerY);
    }

    /**
     * Sets a shader that draws a radial gradient given the center and radius.
     *
     * @param centerX  The x-coordinate of the center of the radius
     * @param centerY  The y-coordinate of the center of the radius
     * @param radius   Must be positive. The radius of the gradient.
     * @param colors   The sRGB colors distributed between the center and edge
     * @param stops    May be <code>null</code>.
     *                 Valid values are between <code>0.0f</code> and
     *                 <code>1.0f</code>. The relative position of each
     *                 corresponding color in
     *                 the colors array. If <code>null</code>, colors are
     *                 distributed evenly
     *                 between the center and edge of the circle.
     * @param tileMode The Shader tiling mode
     */
    public void setRadialGradient(int[] colors,
                                  float[] stops,
                                  float centerX,
                                  float centerY,
                                  float radius,
                                  int tileMode) {
        int startPos = mPos;
        int len;
        mArray[mPos++] = GRADIENT | (RADIAL_GRADIENT << 16);
        mArray[mPos++] = len = (colors == null) ? 0 : colors.length;
        for (int i = 0; i < len; i++) {
            mArray[mPos++] = colors[i];
        }
        mArray[mPos++] = len = (stops == null) ? 0 : stops.length;

        for (int i = 0; i < len; i++) {
            mArray[mPos++] = Float.floatToRawIntBits(stops[i]);
        }
        mArray[mPos++] = Float.floatToRawIntBits(centerX);
        mArray[mPos++] = Float.floatToRawIntBits(centerY);
        mArray[mPos++] = Float.floatToRawIntBits(radius);
        mArray[mPos++] = tileMode;

    }

    /**
     * Create a color filter that uses the specified color and Porter-Duff mode.
     *
     * @param color The ARGB source color used with the Porter-Duff mode
     * @param mode  The porter-duff mode that is applied
     */
    public void setColorFilter(int color, int mode) {
        mArray[mPos] = COLOR_FILTER | (mode << 16);
        mPos++;
        mArray[mPos++] = color;
    }

    /**
     * Set the paint's text size. This value must be > 0
     *
     * @param size set the paint's text size in pixel units.
     */
    public void setTextSize(float size) {
        int p = mPos;
        mArray[mPos] = TEXT_SIZE;
        mPos++;
        mArray[mPos] = Float.floatToRawIntBits(size);
        mPos++;
    }

    /**
     * @param fontType 0 = default 1 = sans serif 2 = serif 3 = monospace
     * @param weight   100-1000
     * @param italic   tur
     */
    public void setTextStyle(int fontType, int weight, boolean italic) {
        int style = (weight & 0x3FF) | (italic ? 2048 : 0);  // pack the weight and italic
        mArray[mPos++] = TYPEFACE | (style << 16);
        mArray[mPos++] = fontType;
    }

    /**
     * Set the width for stroking.
     * Pass 0 to stroke in hairline mode.
     * Hairlines always draws a single pixel independent of the canvas's matrix.
     *
     * @param width set the paint's stroke width, used whenever the paint's
     *              style is Stroke or StrokeAndFill.
     */
    public void setStrokeWidth(float width) {
        mArray[mPos] = STROKE_WIDTH;
        mPos++;
        mArray[mPos] = Float.floatToRawIntBits(width);
        mPos++;
    }

    /**
     * Set the Color based on Color
     * @param color
     */
    public void setColor(int color) {
        mArray[mPos] = COLOR;
        mPos++;
        mArray[mPos] = color;
        mPos++;
    }

    /**
     * Set the color based the R,G,B,A values
     * @param r red (0 to 255)
     * @param g green (0 to 255)
     * @param b blue (0 to 255)
     * @param a alpha (0 to 255)
     */
    public void setColor(int r, int g, int b, int a) {
        int color = (a << 24) | (r << 16) | (g << 8) | b;
        setColor(color);
    }

    /**
     * Set the color based the R,G,B,A values
     * @param r red (0.0 to 1.0)
     * @param g green (0.0 to 1.0)
     * @param b blue (0.0 to 1.0)
     * @param a alpha (0.0 to 1.0)
     */
    public void setColor(float r, float g, float b, float a) {
        setColor((int) (r * 255), (int) (g * 255), (int) (b * 255), (int) (a * 255));
    }

    /**
     * Set the Color based on ID
     * @param color
     */
    public void setColorId(int color) {
        mArray[mPos] = COLOR_ID;
        mPos++;
        mArray[mPos] = color;
        mPos++;
    }


    /**
     * Set the paint's Cap.
     *
     * @param cap set the paint's line cap style, used whenever the paint's
     *            style is Stroke or StrokeAndFill.
     */
    public void setStrokeCap(int cap) {
        mArray[mPos] = STROKE_CAP | (cap << 16);
        mPos++;
    }

    /**
     * Set the style STROKE and/or FILL
     * @param style
     */
    public void setStyle(int style) {
        mArray[mPos] = STYLE | (style << 16);
        mPos++;
    }

    /**
     * Set the shader id to use
     * @param shaderId
     */
    public void setShader(int shaderId) {
        mArray[mPos] = SHADER;
        mPos++;
        mArray[mPos] = shaderId;
        mPos++;
    }

    /**
     * Set the Alpha value
     */
    public void setAlpha(float alpha) {
        mArray[mPos] = ALPHA;
        mPos++;
        mArray[mPos] = Float.floatToRawIntBits(alpha);
        mPos++;
    }

    /**
     * Set the paint's stroke miter value. This is used to control the behavior
     * of miter joins when the joins angle is sharp. This value must be >= 0.
     *
     * @param miter set the miter limit on the paint, used whenever the paint's
     *              style is Stroke or StrokeAndFill.
     */
    public void setStrokeMiter(float miter) {
        mArray[mPos] = STROKE_MITER;
        mPos++;
        mArray[mPos] = Float.floatToRawIntBits(miter);
        mPos++;
    }

    /**
     * Set the paint's Join.
     *
     * @param join set the paint's Join, used whenever the paint's style is
     *             Stroke or StrokeAndFill.
     */
    public void setStrokeJoin(int join) {
        mArray[mPos] = STROKE_JOIN | (join << 16);
        mPos++;
    }

    public void setFilterBitmap(boolean filter) {
        mArray[mPos] = FILTER_BITMAP | (filter ? (1 << 16) : 0);
        mPos++;
    }

    /**
     * Set or clear the blend mode. A blend mode defines how source pixels
     * (generated by a drawing command) are composited with the
     * destination pixels
     * (content of the render target).
     *
     * @param blendmode The blend mode to be installed in the paint
     */
    public void setBlendMode(int blendmode) {
        mArray[mPos] = BLEND_MODE | (blendmode << 16);
        mPos++;
    }

    /**
     * Helper for setFlags(), setting or clearing the ANTI_ALIAS_FLAG bit
     * AntiAliasing smooths out the edges of what is being drawn, but is has
     * no impact on the interior of the shape. See setDither() and
     * setFilterBitmap() to affect how colors are treated.
     *
     * @param aa true to set the antialias bit in the flags, false to clear it
     */
    public void setAntiAlias(boolean aa) {
        mArray[mPos] = ANTI_ALIAS | (((aa) ? 1 : 0) << 16);
        mPos++;
    }

    public void clear(long mask) { // unused for now
    }

    public void reset() {
        mPos = 0;
    }

    public static String blendModeString(int mode) {
        switch (mode) {
            case PaintBundle.BLEND_MODE_CLEAR:
                return "CLEAR";
            case PaintBundle.BLEND_MODE_SRC:
                return "SRC";
            case PaintBundle.BLEND_MODE_DST:
                return "DST";
            case PaintBundle.BLEND_MODE_SRC_OVER:
                return "SRC_OVER";
            case PaintBundle.BLEND_MODE_DST_OVER:
                return "DST_OVER";
            case PaintBundle.BLEND_MODE_SRC_IN:
                return "SRC_IN";
            case PaintBundle.BLEND_MODE_DST_IN:
                return "DST_IN";
            case PaintBundle.BLEND_MODE_SRC_OUT:
                return "SRC_OUT";
            case PaintBundle.BLEND_MODE_DST_OUT:
                return "DST_OUT";
            case PaintBundle.BLEND_MODE_SRC_ATOP:
                return "SRC_ATOP";
            case PaintBundle.BLEND_MODE_DST_ATOP:
                return "DST_ATOP";
            case PaintBundle.BLEND_MODE_XOR:
                return "XOR";
            case PaintBundle.BLEND_MODE_PLUS:
                return "PLUS";
            case PaintBundle.BLEND_MODE_MODULATE:
                return "MODULATE";
            case PaintBundle.BLEND_MODE_SCREEN:
                return "SCREEN";
            case PaintBundle.BLEND_MODE_OVERLAY:
                return "OVERLAY";
            case PaintBundle.BLEND_MODE_DARKEN:
                return "DARKEN";
            case PaintBundle.BLEND_MODE_LIGHTEN:
                return "LIGHTEN";
            case PaintBundle.BLEND_MODE_COLOR_DODGE:
                return "COLOR_DODGE";
            case PaintBundle.BLEND_MODE_COLOR_BURN:
                return "COLOR_BURN";
            case PaintBundle.BLEND_MODE_HARD_LIGHT:
                return "HARD_LIGHT";
            case PaintBundle.BLEND_MODE_SOFT_LIGHT:
                return "SOFT_LIGHT";
            case PaintBundle.BLEND_MODE_DIFFERENCE:
                return "DIFFERENCE";
            case PaintBundle.BLEND_MODE_EXCLUSION:
                return "EXCLUSION";
            case PaintBundle.BLEND_MODE_MULTIPLY:
                return "MULTIPLY";
            case PaintBundle.BLEND_MODE_HUE:
                return "HUE";
            case PaintBundle.BLEND_MODE_SATURATION:
                return "SATURATION";
            case PaintBundle.BLEND_MODE_COLOR:
                return "COLOR";
            case PaintBundle.BLEND_MODE_LUMINOSITY:
                return "LUMINOSITY";
            case PaintBundle.BLEND_MODE_NULL:
                return "null";
            case PaintBundle.PORTER_MODE_ADD:
                return "ADD";
        }
        return "null";
    }

    /**
     * Check all the floats for Nan(id) floats and call listenTo
     * @param context
     * @param support
     */
    public void registerVars(RemoteContext context, VariableSupport support) {
        int i = 0;
        while (i < mPos) {
            int cmd = mArray[i++];
            int type = cmd & 0xFFFF;
            switch (type) {
                case STROKE_MITER:
                case STROKE_WIDTH:
                case ALPHA:
                case TEXT_SIZE:
                    float v = Float.intBitsToFloat(mArray[i++]);
                    if (Float.isNaN(v)) {
                        context.listensTo(Utils.idFromNan(v), support);
                    }
                    break;
                case COLOR_ID:
                    context.listensTo(mArray[i++], support);
                    break;
                case COLOR:

                case TYPEFACE:
                case SHADER:
                case COLOR_FILTER:
                    i++;
                    break;
                case STROKE_JOIN:
                case FILTER_BITMAP:
                case STROKE_CAP:
                case STYLE:
                case IMAGE_FILTER_QUALITY:
                case BLEND_MODE:
                case ANTI_ALIAS:
                    break;

                case GRADIENT: {
                    // TODO gradients should be handled correctly
                    i = callPrintGradient(cmd, mArray, i, new StringBuilder());
                }
            }
        }
    }

    /**
     * Update variables if any are float ids
     * @param context
     */
    public void updateVariables(RemoteContext context) {
        if (mOutArray == null) {
            mOutArray = Arrays.copyOf(mArray, mArray.length);
        } else {
            System.arraycopy(mArray, 0, mOutArray, 0, mArray.length);
        }
        int i = 0;
        while (i < mPos) {
            int cmd = mArray[i++];
            int type = cmd & 0xFFFF;
            switch (type) {
                case STROKE_MITER:
                case STROKE_WIDTH:
                case ALPHA:
                case TEXT_SIZE:
                    mOutArray[i] = fixFloatVar(mArray[i], context);
                    i++;
                    break;
                case COLOR_ID:
                    mOutArray[i] = fixColor(mArray[i], context);
                    i++;
                    break;
                case COLOR:
                case TYPEFACE:
                case SHADER:
                case COLOR_FILTER:
                    i++;
                    break;
                case STROKE_JOIN:
                case FILTER_BITMAP:
                case STROKE_CAP:
                case STYLE:
                case IMAGE_FILTER_QUALITY:
                case BLEND_MODE:
                case ANTI_ALIAS:
                    break;

                case GRADIENT: {
                    // TODO gradients should be handled correctly
                    i = updateFloatsInGradient(cmd, mOutArray, mArray, i, context);
                }
            }
        }
    }

    private int fixFloatVar(int val, RemoteContext context) {
        float v = Float.intBitsToFloat(val);
        if (Float.isNaN(v)) {
            int id = Utils.idFromNan(v);
            return Float.floatToRawIntBits(context.getFloat(id));
        }
        return val;
    }

    private int fixColor(int colorId, RemoteContext context) {
        int n = context.getColor(colorId);
        return n;
    }

    int updateFloatsInGradient(int cmd, int[] out, int[] array,
                               int i,
                               RemoteContext context) {
        int ret = i;
        int type = (cmd >> 16);
        switch (type) {
            case 0: {
                int len = array[ret++];
                if (len > 0) {
                    for (int j = 0; j < len; j++) {
                        ret++;
                    }
                }
                len = array[ret++];

                if (len > 0) {
                    for (int j = 0; j < len; j++) {
                        out[ret] = fixFloatVar(array[ret], context);
                        ret++;
                    }
                }

                out[ret] = fixFloatVar(array[ret], context);
                ret++;
                out[ret] = fixFloatVar(array[ret], context);
                ret++;

                //      end
                out[ret] = fixFloatVar(array[ret], context);
                ret++;
                out[ret] = fixFloatVar(array[ret], context);
                ret++;
                ret++; // tileMode
            }

            break;
            case 1: {
                //   RadialGradient
                int len = array[ret++];
                if (len > 0) {
                    for (int j = 0; j < len; j++) {
                        ret++;
                    }
                }
                len = array[ret++];
                if (len > 0) {
                    for (int j = 0; j < len; j++) {
                        out[ret] = fixFloatVar(array[ret], context);
                        ret++;
                    }
                }


                //    center
                out[ret] = fixFloatVar(array[ret], context);
                ret++;
                out[ret] = fixFloatVar(array[ret], context);
                ret++;
                //     radius
                out[ret] = fixFloatVar(array[ret], context);
                ret++;
                ret++; // tileMode

            }

            break;
            case 2: {
                //   SweepGradient
                int len = array[ret++];
                int[] colors = null;
                if (len > 0) {
                    colors = new int[len];
                    for (int j = 0; j < colors.length; j++) {
                        colors[j] = array[ret++];

                    }
                }
                len = array[ret++];
                float[] stops = null;
                if (len > 0) {
                    stops = new float[len];
                    for (int j = 0; j < stops.length; j++) {
                        out[ret] = fixFloatVar(array[ret], context);
                        ret++;
                    }
                }

                //      center
                out[ret] = fixFloatVar(array[ret], context);
                ret++;
                out[ret] = fixFloatVar(array[ret], context);
                ret++;
            }
            break;
            default: {
                System.err.println("gradient type unknown");
            }
        }

        return ret;
    }

}