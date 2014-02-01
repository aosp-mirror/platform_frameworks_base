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
 * limitations under the License
 */

package com.android.keyguard.analytics;

import android.graphics.RectF;
import android.util.FloatMath;
import android.util.SparseArray;
import android.view.MotionEvent;

import java.util.HashMap;
import java.util.Map;

import static com.android.keyguard.analytics.KeyguardAnalyticsProtos.Session.TouchEvent.BoundingBox;

/**
 * Takes motion events and tracks the length and bounding box of each pointer gesture as well as
 * the bounding box of the whole gesture.
 */
public class PointerTracker {
    private SparseArray<Pointer> mPointerInfoMap = new SparseArray<Pointer>();
    private RectF mTotalBoundingBox = new RectF();

    public void addMotionEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            float x = ev.getX();
            float y = ev.getY();
            mTotalBoundingBox.set(x, y, x, y);
        }
        for (int i = 0; i < ev.getPointerCount(); i++) {
            int id = ev.getPointerId(i);
            Pointer pointer = getPointer(id);
            float x = ev.getX(i);
            float y = ev.getY(i);
            boolean down = ev.getActionMasked() == MotionEvent.ACTION_DOWN
                    || (ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                            && ev.getActionIndex() == i);
            pointer.addPoint(x, y, down);
            mTotalBoundingBox.union(x, y);
        }
    }

    public float getPointerLength(int id) {
        return getPointer(id).length;
    }

    public BoundingBox getBoundingBox() {
        return boundingBoxFromRect(mTotalBoundingBox);
    }

    public BoundingBox getPointerBoundingBox(int id) {
        return boundingBoxFromRect(getPointer(id).boundingBox);
    }

    private BoundingBox boundingBoxFromRect(RectF f) {
        BoundingBox bb = new BoundingBox();
        bb.setHeight(f.height());
        bb.setWidth(f.width());
        return bb;
    }

    private Pointer getPointer(int id) {
        Pointer p = mPointerInfoMap.get(id);
        if (p == null) {
            p = new Pointer();
            mPointerInfoMap.put(id, p);
        }
        return p;
    }

    private static class Pointer {
        public float length;
        public final RectF boundingBox = new RectF();

        private float mLastX;
        private float mLastY;

        public void addPoint(float x, float y, boolean down) {
            float deltaX;
            float deltaY;
            if (down) {
                boundingBox.set(x, y, x, y);
                length = 0f;
                deltaX = 0;
                deltaY = 0;
            } else {
                deltaX = x - mLastX;
                deltaY = y - mLastY;
            }
            mLastX = x;
            mLastY = y;
            length += FloatMath.sqrt(deltaX * deltaX + deltaY * deltaY);
            boundingBox.union(x, y);
        }
    }
}
