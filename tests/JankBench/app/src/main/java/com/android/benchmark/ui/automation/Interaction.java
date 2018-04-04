/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the
 * License.
 *
 */

package com.android.benchmark.ui.automation;

import android.os.SystemClock;
import androidx.annotation.IntDef;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes a UI interaction as a series of MotionEvents
 */
public class Interaction {
    private static final int STEP_COUNT = 20;
    // TODO: scale to device display density
    private static final int DEFAULT_FLING_SIZE_PX = 500;
    private static final int DEFAULT_FLING_DURATION_MS = 20;
    private static final int DEFAULT_TAP_DURATION_MS = 20;
    private List<MotionEvent> mEvents;

    // Interaction parameters
    private final float[] mXPositions;
    private final float[] mYPositions;
    private final long mDuration;
    private final int[] mKeyCodes;
    private final @Interaction.Type int mType;

    @IntDef({
            Interaction.Type.TAP,
            Interaction.Type.FLING,
            Interaction.Type.PINCH,
            Interaction.Type.KEY_EVENT})
    public @interface Type {
        int TAP = 0;
        int FLING = 1;
        int PINCH = 2;
        int KEY_EVENT = 3;
    }

    public static Interaction newFling(float startX, float startY,
                                       float endX, float endY, long duration) {
       return new Interaction(Interaction.Type.FLING, new float[]{startX, endX},
               new float[]{startY, endY}, duration);
    }

    public static Interaction newFlingDown(float startX, float startY) {
        return new Interaction(Interaction.Type.FLING,
                new float[]{startX, startX},
                new float[]{startY, startY + DEFAULT_FLING_SIZE_PX}, DEFAULT_FLING_DURATION_MS);
    }

    public static Interaction newFlingUp(float startX, float startY) {
        return new Interaction(Interaction.Type.FLING,
                new float[]{startX, startX}, new float[]{startY, startY - DEFAULT_FLING_SIZE_PX},
                        DEFAULT_FLING_DURATION_MS);
    }

    public static Interaction newTap(float startX, float startY) {
        return new Interaction(Interaction.Type.TAP,
                new float[]{startX, startX}, new float[]{startY, startY},
                DEFAULT_FLING_DURATION_MS);
    }

    public static Interaction newKeyInput(int[] keyCodes) {
        return new Interaction(keyCodes);
    }

    public List<MotionEvent> getEvents() {
        switch (mType) {
            case Type.FLING:
                mEvents = createInterpolatedEventList(mXPositions, mYPositions, mDuration);
                break;
            case Type.PINCH:
                break;
            case Type.TAP:
                mEvents = createInterpolatedEventList(mXPositions, mYPositions, mDuration);
                break;
        }

        return mEvents;
    }

    public int getType() {
        return mType;
    }

    public int[] getKeyCodes() {
        return mKeyCodes;
    }

    private static List<MotionEvent> createInterpolatedEventList(
            float[] xPos, float[] yPos, long duration) {
        long startTime = SystemClock.uptimeMillis() + 100;
        List<MotionEvent> result = new ArrayList<>();

        float startX = xPos[0];
        float startY = yPos[0];

        MotionEvent downEvent = MotionEvent.obtain(
                startTime, startTime, MotionEvent.ACTION_DOWN, startX, startY, 0);
        result.add(downEvent);

        for (int i = 1; i < xPos.length; i++) {
            float endX = xPos[i];
            float endY = yPos[i];
            float stepX = (endX - startX) / STEP_COUNT;
            float stepY = (endY - startY) / STEP_COUNT;
            float stepT = duration / STEP_COUNT;

            for (int j = 0; j < STEP_COUNT; j++) {
                long deltaT = Math.round(j * stepT);
                long deltaX = Math.round(j * stepX);
                long deltaY = Math.round(j * stepY);
                MotionEvent moveEvent = MotionEvent.obtain(startTime, startTime + deltaT,
                        MotionEvent.ACTION_MOVE, startX + deltaX, startY + deltaY, 0);
                result.add(moveEvent);
            }

            startX = endX;
            startY = endY;
        }

        float lastX = xPos[xPos.length - 1];
        float lastY = yPos[yPos.length - 1];
        MotionEvent lastEvent = MotionEvent.obtain(startTime, startTime + duration,
                MotionEvent.ACTION_UP, lastX, lastY, 0);
        result.add(lastEvent);

        return result;
    }

    private Interaction(@Interaction.Type int type,
                        float[] xPos, float[] yPos, long duration) {
        mType = type;
        mXPositions = xPos;
        mYPositions = yPos;
        mDuration = duration;
        mKeyCodes = null;
    }

    private Interaction(int[] codes) {
        mKeyCodes = codes;
        mType = Type.KEY_EVENT;
        mYPositions = null;
        mXPositions = null;
        mDuration = 0;
    }

    private Interaction(@Interaction.Type int type,
                        List<Float> xPositions, List<Float> yPositions, long duration) {
        if (xPositions.size() != yPositions.size()) {
            throw new IllegalArgumentException("must have equal number of x and y positions");
        }

        int current = 0;
        mXPositions = new float[xPositions.size()];
        for (float p : xPositions) {
            mXPositions[current++] = p;
        }

        current = 0;
        mYPositions = new float[yPositions.size()];
        for (float p : xPositions) {
            mXPositions[current++] = p;
        }

        mType = type;
        mDuration = duration;
        mKeyCodes = null;
    }
}
