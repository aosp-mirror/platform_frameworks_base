/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.graphics;

import com.android.layoutlib.bridge.DelegateManager;

/**
 * Delegate implementing the native methods of android.graphics.DashPathEffect
 *
 * Through the layoutlib_create tool, the original native methods of DashPathEffect have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original DashPathEffect class.
 *
 * Because this extends {@link PathEffect_Delegate}, there's no need to use a
 * {@link DelegateManager}, as all the PathEffect classes will be added to the manager owned by
 * {@link PathEffect_Delegate}.
 *
 */
public class DashPathEffect_Delegate extends PathEffect_Delegate {

    // ---- delegate data ----

    private final float[] mIntervals;
    private final float mPhase;

    // ---- Public Helper methods ----

    public float[] getIntervals() {
        return mIntervals;
    }

    public float getPhase() {
        return mPhase;
    }

    // ---- native methods ----

    /*package*/ static int nativeCreate(float intervals[], float phase) {
        DashPathEffect_Delegate newDelegate = new DashPathEffect_Delegate(intervals, phase);
        return sManager.addDelegate(newDelegate);
    }

    // ---- Private delegate/helper methods ----

    private DashPathEffect_Delegate(float intervals[], float phase) {
        mIntervals = new float[intervals.length];
        System.arraycopy(intervals, 0, mIntervals, 0, intervals.length);
        mPhase = phase;
    }
}

