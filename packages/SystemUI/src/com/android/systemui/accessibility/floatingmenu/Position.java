/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import android.annotation.FloatRange;
import android.text.TextUtils;

/**
 * Stores information about the position, which includes percentage of X-axis of the screen,
 * percentage of Y-axis of the screen.
 */
public class Position {

    private static final char STRING_SEPARATOR = ',';
    private static final TextUtils.SimpleStringSplitter sStringCommaSplitter =
            new TextUtils.SimpleStringSplitter(STRING_SEPARATOR);

    private float mPercentageX;
    private float mPercentageY;

    /**
     * Creates a {@link Position} from a encoded string described in {@link #toString()}.
     *
     * @param positionString A string conform to the format described in {@link #toString()}
     * @return A {@link Position} with the given value retrieved from {@code absolutePositionString}
     * @throws IllegalArgumentException If {@code positionString} does not conform to the format
     *                                  described in {@link #toString()}
     */
    public static Position fromString(String positionString) {
        sStringCommaSplitter.setString(positionString);
        if (sStringCommaSplitter.hasNext()) {
            final float percentageX = Float.parseFloat(sStringCommaSplitter.next());
            final float percentageY = Float.parseFloat(sStringCommaSplitter.next());
            return new Position(percentageX, percentageY);
        }

        throw new IllegalArgumentException(
                "Invalid Position string: " + positionString);
    }

    Position(float percentageX, float percentageY) {
        update(percentageX, percentageY);
    }

    @Override
    public String toString() {
        return mPercentageX + ", " + mPercentageY;
    }

    /**
     * Updates the position with {@code percentageX} and {@code percentageY}.
     *
     * @param percentageX the new percentage of X-axis of the screen, from 0.0 to 1.0.
     * @param percentageY the new percentage of Y-axis of the screen, from 0.0 to 1.0.
     */
    public void update(@FloatRange(from = 0.0, to = 1.0) float percentageX,
            @FloatRange(from = 0.0, to = 1.0) float percentageY) {
        mPercentageX = percentageX;
        mPercentageY = percentageY;
    }

    public float getPercentageX() {
        return mPercentageX;
    }

    public float getPercentageY() {
        return mPercentageY;
    }
}
