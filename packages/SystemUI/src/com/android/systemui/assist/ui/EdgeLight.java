/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.assist.ui;

import android.util.Log;

import androidx.annotation.ColorInt;

/**
 * Represents a line drawn on the perimeter of the display.
 *
 * Offsets and lengths are both normalized to the perimeter of the display – ex. a length of 1
 * is equal to the perimeter of the display. Positions move counter-clockwise as values increase.
 *
 * If there is no bottom corner radius, the origin is the bottom-left corner.
 * If there is a bottom corner radius, the origin is immediately after the bottom corner radius,
 * counter-clockwise.
 */
public final class EdgeLight {

    private static final String TAG = "EdgeLight";

    @ColorInt
    private int mColor;
    private float mStart;
    private float mLength;

    /** Copies a list of EdgeLights. */
    public static EdgeLight[] copy(EdgeLight[] array) {
        EdgeLight[] copy = new EdgeLight[array.length];
        for (int i = 0; i < array.length; i++) {
            copy[i] = new EdgeLight(array[i]);
        }
        return copy;
    }

    public EdgeLight(@ColorInt int color, float offset, float length) {
        mColor = color;
        mStart = offset;
        mLength = length;
    }

    public EdgeLight(EdgeLight sourceLight) {
        mColor = sourceLight.getColor();
        mStart = sourceLight.getStart();
        mLength = sourceLight.getLength();
    }

    /** Returns the current edge light color. */
    @ColorInt
    public int getColor() {
        return mColor;
    }

    /** Sets the edge light color. */
    public boolean setColor(@ColorInt int color) {
        boolean changed = mColor != color;
        mColor = color;
        return changed;
    }

    /** Returns the edge light length, in units of the total device perimeter. */
    public float getLength() {
        return mLength;
    }

    /** Sets the edge light length, in units of the total device perimeter. */
    public void setLength(float length) {
        mLength = length;
    }

    /**
     * Sets the endpoints of the edge light, both measured from the bottom-left corner (see class
     * description). This is a convenience method to avoid separate setStart and setLength calls.
     */
    public void setEndpoints(float start, float end) {
        if (start > end) {
            Log.e(TAG, String.format("Endpoint must be >= start (add 1 if necessary). Got [%f, %f]",
                    start, end));
            return;
        }
        mStart = start;
        mLength = end - start;
    }

    /**
     * Returns the current starting position, in units of the total device perimeter and measured
     * from the bottom-left corner (see class description).
     */
    public float getStart() {
        return mStart;
    }

    /**
     * Sets the current offset, in units of the total device perimeter and measured from the
     * bottom-left corner (see class description).
     */
    public void setStart(float start) {
        mStart = start;
    }

    public float getEnd() {
        return mStart + mLength;
    }

    /** Returns the center, measured from the bottom-left corner (see class description). */
    public float getCenter() {
        return mStart + (mLength / 2.f);
    }
}
