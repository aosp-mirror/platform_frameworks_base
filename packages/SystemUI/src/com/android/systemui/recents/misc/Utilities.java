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

import android.animation.Animator;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewParent;

/* Common code */
public class Utilities {

    /**
     * @return the first parent walking up the view hierarchy that has the given class type.
     *
     * @param parentClass must be a class derived from {@link View}
     */
    public static <T extends View> T findParent(View v, Class<T> parentClass) {
        ViewParent parent = v.getParent();
        while (parent != null) {
            if (parent.getClass().equals(parentClass)) {
                return (T) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    /** Scales a rect about its centroid */
    public static void scaleRectAboutCenter(RectF r, float scale) {
        if (scale != 1.0f) {
            float cx = r.centerX();
            float cy = r.centerY();
            r.offset(-cx, -cy);
            r.left *= scale;
            r.top *= scale;
            r.right *= scale;
            r.bottom *= scale;
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

    /**
     * Cancels an animation ensuring that if it has listeners, onCancel and onEnd
     * are not called.
     */
    public static void cancelAnimationWithoutCallbacks(Animator animator) {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
        }
    }
}
