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

package android.os;

import android.annotation.SuppressLint;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.MergedConfiguration;
import android.view.InsetsSource;
import android.view.InsetsState;

import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;

/**
 * Benchmark of read/write large Parcelable class. This also shows the performance of different
 * implementations for nested Parcelable class:
 * <ul>
 *   <li>Well-written read/writeFromParcel (direct access)</li>
 *   <li>read/writeTypedObject (object creation + addition int to indicate nullity)</li>
 *   <li>read/writeParcelable (object creation + addition type String)</li>
 * </ul>
 */
public class ParcelableBenchmark {
    private Parcel mParcel;

    @BeforeExperiment
    protected void setUp() {
        mParcel = Parcel.obtain();
    }

    @AfterExperiment
    protected void tearDown() {
        mParcel.recycle();
        mParcel = null;
    }

    public void timeReadWriteMergedConfiguration(int reps) {
        final MergedConfiguration mergedConfiguration = new MergedConfiguration();
        for (int i = 0; i < reps; i++) {
            mergedConfiguration.writeToParcel(mParcel, 0);
            mParcel.setDataPosition(0);
            mergedConfiguration.readFromParcel(mParcel);
        }
    }

    public void timeReadWriteInsetsState(int reps) {
        final InsetsState insetsState = new InsetsState();
        for (int i = 0; i < InsetsState.SIZE; i++) {
            insetsState.addSource(new InsetsSource(i));
        }
        for (int i = 0; i < reps; i++) {
            insetsState.writeToParcel(mParcel, 0);
            mParcel.setDataPosition(0);
            insetsState.readFromParcel(mParcel);
        }
    }

    public void timeReadWritePointArray(int reps) {
        final PointArray pointArray = new PointArray();
        for (int i = 0; i < reps; i++) {
            pointArray.writeToParcel(mParcel, 0);
            mParcel.setDataPosition(0);
            pointArray.readFromParcel(mParcel);
        }
    }

    public void timeReadWritePointArrayFast(int reps) {
        final PointArrayFast pointArray = new PointArrayFast();
        for (int i = 0; i < reps; i++) {
            pointArray.writeToParcel(mParcel, 0);
            mParcel.setDataPosition(0);
            pointArray.readFromParcel(mParcel);
        }
    }

    @SuppressLint("ParcelCreator")
    private static class PointArray implements Parcelable {
        Rect mBounds = new Rect();
        Point[] mPoints = new Point[10];
        {
            for (int i = 0; i < mPoints.length; i++) {
                mPoints[i] = new Point();
            }
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeParcelable(mBounds, flags);
            dest.writeParcelableArray(mPoints, flags);
        }

        void readFromParcel(Parcel in) {
            mBounds = in.readParcelable(Rect.class.getClassLoader());
            mPoints = in.readParcelableArray(Point.class.getClassLoader(), Point.class);
        }

        @Override
        public int describeContents() {
            return 0;
        }
    }

    @SuppressLint("ParcelCreator")
    private static class PointArrayFast extends PointArray {

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mBounds.writeToParcel(dest, flags);
            dest.writeTypedArray(mPoints, flags);
        }

        @Override
        void readFromParcel(Parcel in) {
            mBounds.readFromParcel(in);
            in.readTypedArray(mPoints, Point.CREATOR);
        }
    }
}
