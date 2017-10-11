/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.view.animation;

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.graphics.Path;
import android.util.MathUtils;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnticipateInterpolator;
import android.view.animation.AnticipateOvershootInterpolator;
import android.view.animation.BaseInterpolator;
import android.view.animation.BounceInterpolator;
import android.view.animation.CycleInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

/**
 * Delegate used to provide new implementation of a select few methods of {@link
 * NativeInterpolatorFactoryHelper}
 * <p>
 * Through the layoutlib_create tool, the original  methods of NativeInterpolatorFactoryHelper have
 * been replaced by calls to methods of the same name in this delegate class.
 */
@SuppressWarnings("unused")
public class NativeInterpolatorFactoryHelper_Delegate {
    private static final DelegateManager<Interpolator> sManager = new DelegateManager<>
            (Interpolator.class);

    public static Interpolator getDelegate(long nativePtr) {
        return sManager.getDelegate(nativePtr);
    }

    @LayoutlibDelegate
    /*package*/ static long createAccelerateDecelerateInterpolator() {
        return sManager.addNewDelegate(new AccelerateDecelerateInterpolator());
    }

    @LayoutlibDelegate
    /*package*/ static long createAccelerateInterpolator(float factor) {
        return sManager.addNewDelegate(new AccelerateInterpolator(factor));
    }

    @LayoutlibDelegate
    /*package*/ static long createAnticipateInterpolator(float tension) {
        return sManager.addNewDelegate(new AnticipateInterpolator(tension));
    }

    @LayoutlibDelegate
    /*package*/ static long createAnticipateOvershootInterpolator(float tension) {
        return sManager.addNewDelegate(new AnticipateOvershootInterpolator(tension));
    }

    @LayoutlibDelegate
    /*package*/ static long createBounceInterpolator() {
        return sManager.addNewDelegate(new BounceInterpolator());
    }

    @LayoutlibDelegate
    /*package*/ static long createCycleInterpolator(float cycles) {
        return sManager.addNewDelegate(new CycleInterpolator(cycles));
    }

    @LayoutlibDelegate
    /*package*/ static long createDecelerateInterpolator(float factor) {
        return sManager.addNewDelegate(new DecelerateInterpolator(factor));
    }

    @LayoutlibDelegate
    /*package*/ static long createLinearInterpolator() {
        return sManager.addNewDelegate(new LinearInterpolator());
    }

    @LayoutlibDelegate
    /*package*/ static long createOvershootInterpolator(float tension) {
        return sManager.addNewDelegate(new OvershootInterpolator(tension));
    }

    @LayoutlibDelegate
    /*package*/ static long createPathInterpolator(float[] x, float[] y) {
        Path path = new Path();
        path.moveTo(x[0], y[0]);
        for (int i = 1; i < x.length; i++) {
            path.lineTo(x[i], y[i]);
        }
        return sManager.addNewDelegate(new PathInterpolator(path));
    }

    private static class LutInterpolator extends BaseInterpolator {
        private final float[] mValues;
        private final int mSize;

        private LutInterpolator(float[] values) {
            mValues = values;
            mSize = mValues.length;
        }

        @Override
        public float getInterpolation(float input) {
            float lutpos = input * (mSize - 1);
            if (lutpos >= (mSize - 1)) {
                return mValues[mSize - 1];
            }

            int ipart = (int) lutpos;
            float weight = lutpos - ipart;

            int i1 = ipart;
            int i2 = Math.min(i1 + 1, mSize - 1);

            assert i1 >= 0 && i2 >= 0 : "Negatives in the interpolation";

            return MathUtils.lerp(mValues[i1], mValues[i2], weight);
        }
    }

    @LayoutlibDelegate
    /*package*/ static long createLutInterpolator(float[] values) {
        return sManager.addNewDelegate(new LutInterpolator(values));
    }
}
