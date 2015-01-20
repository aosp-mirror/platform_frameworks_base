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
package android.binding.adapters;

import android.animation.LayoutTransition;
import android.annotation.TargetApi;
import android.binding.BindingMethod;
import android.binding.BindingMethods;
import android.os.Build;
import android.view.ViewGroup;

@BindingMethods({
        @BindingMethod(type = "android.view.ViewGroup", attribute = "android:alwaysDrawnWithCache", method = "setAlwaysDrawnWithCacheEnabled"),
        @BindingMethod(type = "android.view.ViewGroup", attribute = "android:animationCache", method = "setAnimationCacheEnabled"),
        @BindingMethod(type = "android.view.ViewGroup", attribute = "android:splitMotionEvents", method = "setMotionEventSplittingEnabled"),
})
public class ViewGroupBindingAdapter {

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static void setAnimateLayoutChanges(ViewGroup view, boolean animate) {
        if (animate) {
            view.setLayoutTransition(new LayoutTransition());
        } else {
            view.setLayoutTransition(null);
        }
    }
}
