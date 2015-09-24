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

import android.util.SparseArray;
import android.view.MotionEvent;

import java.util.ArrayList;

/**
 * Contains data which is used to classify interaction sequences on the lockscreen. It does, for
 * example, provide information on the current touch state.
 */
public class ClassifierData {
    private SparseArray<Stroke> mCurrentStrokes = new SparseArray<>();
    private ArrayList<Stroke> mEndingStrokes = new ArrayList<>();
    private final float mDpi;

    public ClassifierData(float dpi) {
        mDpi = dpi;
    }

    public void update(MotionEvent event) {
        mEndingStrokes.clear();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mCurrentStrokes.clear();
        }

        for (int i = 0; i < event.getPointerCount(); i++) {
            int id = event.getPointerId(i);
            if (mCurrentStrokes.get(id) == null) {
                mCurrentStrokes.put(id, new Stroke(event.getEventTimeNano(), mDpi));
            }
            mCurrentStrokes.get(id).addPoint(event.getX(i), event.getY(i),
                    event.getEventTimeNano());

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || (action == MotionEvent.ACTION_POINTER_UP && i == event.getActionIndex())) {
                mEndingStrokes.add(getStroke(id));
            }
        }
    }

    public void cleanUp(MotionEvent event) {
        mEndingStrokes.clear();
        int action = event.getActionMasked();
        for (int i = 0; i < event.getPointerCount(); i++) {
            int id = event.getPointerId(i);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || (action == MotionEvent.ACTION_POINTER_UP && i == event.getActionIndex())) {
                mCurrentStrokes.remove(id);
            }
        }
    }

    /**
     * @return the list of Strokes which are ending in the recently added MotionEvent
     */
    public ArrayList<Stroke> getEndingStrokes() {
        return mEndingStrokes;
    }

    /**
     * @param id the id from MotionEvent
     * @return the Stroke assigned to the id
     */
    public Stroke getStroke(int id) {
        return mCurrentStrokes.get(id);
    }
}
