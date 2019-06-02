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
    @ColorInt
    private int mColor;
    private float mOffset;
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
        mOffset = offset;
        mLength = length;
    }

    public EdgeLight(EdgeLight sourceLight) {
        mColor = sourceLight.getColor();
        mOffset = sourceLight.getOffset();
        mLength = sourceLight.getLength();
    }

    /** Returns the current edge light color. */
    @ColorInt
    public int getColor() {
        return mColor;
    }

    /** Sets the edge light color. */
    public void setColor(@ColorInt int color) {
        mColor = color;
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
     * Returns the current offset, in units of the total device perimeter and measured from the
     * bottom-left corner (see class description).
     */
    public float getOffset() {
        return mOffset;
    }

    /**
     * Sets the current offset, in units of the total device perimeter and measured from the
     * bottom-left corner (see class description).
     */
    public void setOffset(float offset) {
        mOffset = offset;
    }

    /** Returns the center, measured from the bottom-left corner (see class description). */
    public float getCenter() {
        return mOffset + (mLength / 2.f);
    }
}
