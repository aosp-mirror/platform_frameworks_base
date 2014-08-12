/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.misc;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import com.android.systemui.recents.RecentsConfiguration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/* Common code */
public class Utilities {

    // Reflection methods for altering shadows
    private static Method sPropertyMethod;
    static {
        try {
            Class<?> c = Class.forName("android.view.GLES20Canvas");
            sPropertyMethod = c.getDeclaredMethod("setProperty", String.class, String.class);
            if (!sPropertyMethod.isAccessible()) sPropertyMethod.setAccessible(true);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
    }

    /**
     * Calculates a consistent animation duration (ms) for all animations depending on the movement
     * of the object being animated.
     */
    public static int calculateTranslationAnimationDuration(int distancePx) {
        return calculateTranslationAnimationDuration(distancePx, 100);
    }
    public static int calculateTranslationAnimationDuration(int distancePx, int minDuration) {
        RecentsConfiguration config = RecentsConfiguration.getInstance();
        return Math.max(minDuration, (int) (1000f /* ms/s */ *
                (Math.abs(distancePx) / config.animationPxMovementPerSecond)));
    }

    /** Scales a rect about its centroid */
    public static void scaleRectAboutCenter(Rect r, float scale) {
        if (scale != 1.0f) {
            int cx = r.centerX();
            int cy = r.centerY();
            r.offset(-cx, -cy);
            r.left = (int) (r.left * scale + 0.5f);
            r.top = (int) (r.top * scale + 0.5f);
            r.right = (int) (r.right * scale + 0.5f);
            r.bottom = (int) (r.bottom * scale + 0.5f);
            r.offset(cx, cy);
        }
    }

    /** Calculates the constrast between two colors, using the algorithm provided by the WCAG v2. */
    public static float computeContrastBetweenColors(int bg, int fg) {
        float bgR = Color.red(bg) / 255f;
        float bgG = Color.green(bg) / 255f;
        float bgB = Color.blue(bg) / 255f;
        bgR = (bgR < 0.03928f) ? bgR / 12.92f : (float) Math.pow((bgR + 0.055f) / 1.055f, 2.4f);
        bgG = (bgG < 0.03928f) ? bgG / 12.92f : (float) Math.pow((bgG + 0.055f) / 1.055f, 2.4f);
        bgB = (bgB < 0.03928f) ? bgB / 12.92f : (float) Math.pow((bgB + 0.055f) / 1.055f, 2.4f);
        float bgL = 0.2126f * bgR + 0.7152f * bgG + 0.0722f * bgB;
        
        float fgR = Color.red(fg) / 255f;
        float fgG = Color.green(fg) / 255f;
        float fgB = Color.blue(fg) / 255f;
        fgR = (fgR < 0.03928f) ? fgR / 12.92f : (float) Math.pow((fgR + 0.055f) / 1.055f, 2.4f);
        fgG = (fgG < 0.03928f) ? fgG / 12.92f : (float) Math.pow((fgG + 0.055f) / 1.055f, 2.4f);
        fgB = (fgB < 0.03928f) ? fgB / 12.92f : (float) Math.pow((fgB + 0.055f) / 1.055f, 2.4f);
        float fgL = 0.2126f * fgR + 0.7152f * fgG + 0.0722f * fgB;

        return Math.abs((fgL + 0.05f) / (bgL + 0.05f));
    }

    /** Returns the base color overlaid with another overlay color with a specified alpha. */
    public static int getColorWithOverlay(int baseColor, int overlayColor, float overlayAlpha) {
        return Color.rgb(
            (int) (overlayAlpha * Color.red(baseColor) +
                    (1f - overlayAlpha) * Color.red(overlayColor)),
            (int) (overlayAlpha * Color.green(baseColor) +
                    (1f - overlayAlpha) * Color.green(overlayColor)),
            (int) (overlayAlpha * Color.blue(baseColor) +
                    (1f - overlayAlpha) * Color.blue(overlayColor)));
    }

    /** Sets some private shadow properties. */
    public static void setShadowProperty(String property, String value)
            throws IllegalAccessException, InvocationTargetException {
        sPropertyMethod.invoke(null, property, value);
    }

    /** Returns whether the specified intent is a document. */
    public static boolean isDocument(Intent intent) {
        int flags = intent.getFlags();
        return (flags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) == Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
    }
}
