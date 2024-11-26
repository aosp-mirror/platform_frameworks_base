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
package com.android.internal.widget.remotecompose.core.operations;

import android.annotation.NonNull;

/** Utilities to be used across all core operations */
public class Utils {
    /**
     * Convert an integer id into a float
     *
     * @param v the integer id to convert
     * @return the id as an float
     */
    public static float asNan(int v) {
        return Float.intBitsToFloat(v | -0x800000);
    }

    /**
     * convert a float into an integer id
     *
     * @param value the float id to convert
     * @return the id as an integer
     */
    public static int idFromNan(float value) {
        int b = Float.floatToRawIntBits(value);
        return b & 0x3FFFFF;
    }

    /**
     * convert a long into an ID
     *
     * @param v the long to convert
     * @return the id still as a long
     */
    public static long idFromLong(long v) {
        return v - 0x100000000L;
    }

    /**
     * convert a float id and turn it into a string
     *
     * @param value float to convert
     * @return string form of an id
     */
    @NonNull
    public static String idStringFromNan(float value) {
        int b = Float.floatToRawIntBits(value) & 0x3FFFFF;
        return idString(b);
    }

    /**
     * print an id as a string
     *
     * @param b the id
     * @return the id as a string
     */
    @NonNull
    public static String idString(int b) {
        return (b > 0xFFFFF) ? "A_" + (b & 0xFFFFF) : "" + b;
    }

    /**
     * trim a string to n characters if needing to trim end in "..."
     *
     * @param str
     * @param n
     * @return
     */
    @NonNull
    public static String trimString(@NonNull String str, int n) {
        if (str.length() > n) {
            str = str.substring(0, n - 3) + "...";
        }
        return str;
    }

    /**
     * print the id and the value of a float
     *
     * @param idvalue
     * @param value
     * @return
     */
    public static @NonNull String floatToString(float idvalue, float value) {
        if (Float.isNaN(idvalue)) {
            if (idFromNan(value) == 0) {
                return "NaN";
            }
            return "[" + idFromNan(idvalue) + "]" + floatToString(value);
        }
        return floatToString(value);
    }

    /**
     * Convert float to string but render nan id in brackets [n]
     *
     * @param value
     * @return
     */
    public static @NonNull String floatToString(float value) {
        if (Float.isNaN(value)) {
            if (idFromNan(value) == 0) {
                return "NaN";
            }
            return "[" + idFromNan(value) + "]";
        }
        return Float.toString(value);
    }

    /**
     * Debugging util to print a message and include the file/line it came from
     *
     * @param str
     */
    public static void log(@NonNull String str) {
        StackTraceElement s = new Throwable().getStackTrace()[1];
        System.out.println(
                "("
                        + s.getFileName()
                        + ":"
                        + s.getLineNumber()
                        + "). "
                        + s.getMethodName()
                        + "() "
                        + str);
    }

    /**
     * Debugging util to print the stack
     *
     * @param str
     * @param n
     */
    public static void logStack(@NonNull String str, int n) {
        StackTraceElement[] st = new Throwable().getStackTrace();
        for (int i = 1; i < n + 1; i++) {
            StackTraceElement s = st[i];
            String space = new String(new char[i]).replace('\0', ' ');
            System.out.println(
                    space + "(" + s.getFileName() + ":" + s.getLineNumber() + ")." + str);
        }
    }

    /**
     * Is a variable Allowed int calculation and references.
     *
     * @param v
     * @return
     */
    public static boolean isVariable(float v) {
        if (Float.isNaN(v)) {
            int id = idFromNan(v);
            if (id == 0) return false;
            return id > 40 || id < 10;
        }
        return false;
    }

    /**
     * print a color in the familiar 0xAARRGGBB pattern
     *
     * @param color
     * @return
     */
    @NonNull
    public static String colorInt(int color) {
        String str = "000000000000" + Integer.toHexString(color);
        return "0x" + str.substring(str.length() - 8);
    }

    /**
     * Interpolate two colors. gamma corrected colors are interpolated in the form c1 * (1-t) + c2 *
     * t
     *
     * @param c1
     * @param c2
     * @param t
     * @return
     */
    public static int interpolateColor(int c1, int c2, float t) {
        if (Float.isNaN(t) || t == 0.0f) {
            return c1;
        } else if (t == 1.0f) {
            return c2;
        }
        int a = 0xFF & (c1 >> 24);
        int r = 0xFF & (c1 >> 16);
        int g = 0xFF & (c1 >> 8);
        int b = 0xFF & c1;
        float f_r = (float) Math.pow(r / 255.0f, 2.2);
        float f_g = (float) Math.pow(g / 255.0f, 2.2);
        float f_b = (float) Math.pow(b / 255.0f, 2.2);
        float c1fr = f_r;
        float c1fg = f_g;
        float c1fb = f_b;
        float c1fa = a / 255f;

        a = 0xFF & (c2 >> 24);
        r = 0xFF & (c2 >> 16);
        g = 0xFF & (c2 >> 8);
        b = 0xFF & c2;
        f_r = (float) Math.pow(r / 255.0f, 2.2);
        f_g = (float) Math.pow(g / 255.0f, 2.2);
        f_b = (float) Math.pow(b / 255.0f, 2.2);
        float c2fr = f_r;
        float c2fg = f_g;
        float c2fb = f_b;
        float c2fa = a / 255f;
        f_r = c1fr + t * (c2fr - c1fr);
        f_g = c1fg + t * (c2fg - c1fg);
        f_b = c1fb + t * (c2fb - c1fb);
        float f_a = c1fa + t * (c2fa - c1fa);

        int outr = clamp((int) ((float) Math.pow(f_r, 1.0 / 2.2) * 255.0f));
        int outg = clamp((int) ((float) Math.pow(f_g, 1.0 / 2.2) * 255.0f));
        int outb = clamp((int) ((float) Math.pow(f_b, 1.0 / 2.2) * 255.0f));
        int outa = clamp((int) (f_a * 255.0f));

        return (outa << 24 | outr << 16 | outg << 8 | outb);
    }

    /**
     * Efficient clamping function
     *
     * @param c
     * @return number between 0 and 255
     */
    public static int clamp(int c) {
        int n = 255;
        c &= ~(c >> 31);
        c -= n;
        c &= (c >> 31);
        c += n;
        return c;
    }

    /**
     * convert hue saturation and value to RGB
     *
     * @param hue 0..1
     * @param saturation 0..1 0=on the gray scale
     * @param value 0..1 0=black
     * @return
     */
    public static int hsvToRgb(float hue, float saturation, float value) {
        int h = (int) (hue * 6);
        float f = hue * 6 - h;
        int p = (int) (0.5f + 255 * value * (1 - saturation));
        int q = (int) (0.5f + 255 * value * (1 - f * saturation));
        int t = (int) (0.5f + 255 * value * (1 - (1 - f) * saturation));
        int v = (int) (0.5f + 255 * value);
        switch (h) {
            case 0:
                return 0XFF000000 | (v << 16) + (t << 8) + p;
            case 1:
                return 0XFF000000 | (q << 16) + (v << 8) + p;
            case 2:
                return 0XFF000000 | (p << 16) + (v << 8) + t;
            case 3:
                return 0XFF000000 | (p << 16) + (q << 8) + v;
            case 4:
                return 0XFF000000 | (t << 16) + (p << 8) + v;
            case 5:
                return 0XFF000000 | (v << 16) + (p << 8) + q;
        }
        return 0;
    }
}
