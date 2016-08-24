/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.classifier;

import java.util.ArrayList;

/**
 * Contains data about a stroke (a single trace, all the events from a given id from the
 * DOWN/POINTER_DOWN event till the UP/POINTER_UP/CANCEL event.)
 */
public class Stroke {
    private final float NANOS_TO_SECONDS = 1e9f;

    private ArrayList<Point> mPoints = new ArrayList<>();
    private long mStartTimeNano;
    private long mEndTimeNano;
    private float mLength;
    private final float mDpi;

    public Stroke(long eventTimeNano, float dpi) {
        mDpi = dpi;
        mStartTimeNano = mEndTimeNano = eventTimeNano;
    }

    public void addPoint(float x, float y, long eventTimeNano) {
        mEndTimeNano = eventTimeNano;
        Point point = new Point(x / mDpi, y / mDpi, eventTimeNano - mStartTimeNano);
        if (!mPoints.isEmpty()) {
            mLength += mPoints.get(mPoints.size() - 1).dist(point);
        }
        mPoints.add(point);
    }

    public int getCount() {
        return mPoints.size();
    }

    public float getTotalLength() {
        return mLength;
    }

    public float getEndPointLength() {
        return mPoints.get(0).dist(mPoints.get(mPoints.size() - 1));
    }

    public long getDurationNanos() {
        return mEndTimeNano - mStartTimeNano;
    }

    public float getDurationSeconds() {
        return (float) getDurationNanos() / NANOS_TO_SECONDS;
    }

    public ArrayList<Point> getPoints() {
        return mPoints;
    }
}
