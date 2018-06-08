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
 * limitations under the License.
 */

package com.android.systemui.volume;

import android.animation.TimeInterpolator;

public class SystemUIInterpolators {
    public static final class LogDecelerateInterpolator implements TimeInterpolator {
        private final float mBase;
        private final float mDrift;
        private final float mTimeScale;
        private final float mOutputScale;

        public LogDecelerateInterpolator() {
            this(400f, 1.4f, 0);
        }

        private LogDecelerateInterpolator(float base, float timeScale, float drift) {
            mBase = base;
            mDrift = drift;
            mTimeScale = 1f / timeScale;

            mOutputScale = 1f / computeLog(1f);
        }

        private float computeLog(float t) {
            return 1f - (float) Math.pow(mBase, -t * mTimeScale) + (mDrift * t);
        }

        @Override
        public float getInterpolation(float t) {
            return computeLog(t) * mOutputScale;
        }
    }

    public static final class LogAccelerateInterpolator implements TimeInterpolator {
        private final int mBase;
        private final int mDrift;
        private final float mLogScale;

        public LogAccelerateInterpolator() {
            this(100, 0);
        }

        private LogAccelerateInterpolator(int base, int drift) {
            mBase = base;
            mDrift = drift;
            mLogScale = 1f / computeLog(1, mBase, mDrift);
        }

        private static float computeLog(float t, int base, int drift) {
            return (float) -Math.pow(base, -t) + 1 + (drift * t);
        }

        @Override
        public float getInterpolation(float t) {
            return 1 - computeLog(1 - t, mBase, mDrift) * mLogScale;
        }
    }

    public interface Callback {
        void onAnimatingChanged(boolean animating);
    }
}
