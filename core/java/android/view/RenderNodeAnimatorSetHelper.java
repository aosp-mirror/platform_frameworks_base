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
package android.view;

import android.animation.TimeInterpolator;
import com.android.internal.view.animation.FallbackLUTInterpolator;
import com.android.internal.view.animation.NativeInterpolatorFactory;
import com.android.internal.view.animation.NativeInterpolatorFactoryHelper;

/**
 * This is a helper class to get access to methods and fields needed for RenderNodeAnimatorSet
 * that are internal or package private to android.view package.
 *
 * @hide
 */
public class RenderNodeAnimatorSetHelper {

    public static RenderNode getTarget(DisplayListCanvas recordingCanvas) {
        return recordingCanvas.mNode;
    }

    public static long createNativeInterpolator(TimeInterpolator interpolator, long
            duration) {
        if (interpolator == null) {
            // create LinearInterpolator
            return NativeInterpolatorFactoryHelper.createLinearInterpolator();
        } else if (RenderNodeAnimator.isNativeInterpolator(interpolator)) {
            return ((NativeInterpolatorFactory)interpolator).createNativeInterpolator();
        } else {
            return FallbackLUTInterpolator.createNativeInterpolator(interpolator, duration);
        }
    }

}
