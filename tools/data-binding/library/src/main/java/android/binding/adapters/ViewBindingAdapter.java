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

import android.binding.BindingAdapter;
import android.binding.BindingMethod;
import android.binding.BindingMethods;
import android.os.Build;
import android.view.View;

@BindingMethods({
        @BindingMethod(type = "android.view.View", attribute = "android:backgroundTint", method = "setBackgroundTintList"),
        @BindingMethod(type = "android.view.View", attribute = "android:fadeScrollbars", method = "setScrollbarFadingEnabled"),
        @BindingMethod(type = "android.view.View", attribute = "android:nextFocusForward", method = "setNextFocusForwardId"),
        @BindingMethod(type = "android.view.View", attribute = "android:nextFocusLeft", method = "setNextFocusLeftId"),
        @BindingMethod(type = "android.view.View", attribute = "android:nextFocusRight", method = "setNextFocusRightId"),
        @BindingMethod(type = "android.view.View", attribute = "android:nextFocusUp", method = "setNextFocusUpId"),
        @BindingMethod(type = "android.view.View", attribute = "android:padding", method = "setPaddingRelative"),
        @BindingMethod(type = "android.view.View", attribute = "android:requiresFadingEdge", method = "setVerticalFadingEdgeEnabled"),
        @BindingMethod(type = "android.view.View", attribute = "android:scrollbarDefaultDelayBeforeFade", method = "setScrollBarDefaultDelayBeforeFade"),
        @BindingMethod(type = "android.view.View", attribute = "android:scrollbarFadeDuration", method = "setScrollBarFadeDuration"),
        @BindingMethod(type = "android.view.View", attribute = "android:scrollbarSize", method = "setScrollBarSize"),
        @BindingMethod(type = "android.view.View", attribute = "android:scrollbarStyle", method = "setScrollBarStyle"),
        @BindingMethod(type = "android.view.View", attribute = "android:transformPivotX", method = "setPivotX"),
        @BindingMethod(type = "android.view.View", attribute = "android:transformPivotY", method = "setPivotY"),
})
public class ViewBindingAdapter {

    @BindingAdapter("android:background")
    public static void setBackground(View view, int color) {
        view.setBackgroundColor(color);
    }

    @BindingAdapter("android:padding")
    public static void setPadding(View view, int padding) {
        view.setPadding(padding, padding, padding, padding);
    }

    @BindingAdapter("android:paddingBottom")
    public static void setPaddingBottom(View view, int padding) {
        view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(),
                padding);
    }

    @BindingAdapter("android:paddingEnd")
    public static void setPaddingEnd(View view, int padding) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            view.setPaddingRelative(view.getPaddingStart(), view.getPaddingTop(), padding,
                    view.getPaddingBottom());
        } else {
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), padding,
                    view.getPaddingBottom());
        }
    }

    @BindingAdapter("android:paddingLeft")
    public static void setPaddingLeft(View view, int padding) {
        view.setPadding(padding, view.getPaddingTop(), view.getPaddingRight(),
                view.getPaddingBottom());
    }

    @BindingAdapter("android:paddingRight")
    public static void setPaddingRight(View view, int padding) {
        view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), padding,
                view.getPaddingBottom());
    }

    @BindingAdapter("android:paddingStart")
    public static void setPaddingStart(View view, int padding) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            view.setPaddingRelative(padding, view.getPaddingTop(), view.getPaddingEnd(),
                    view.getPaddingBottom());
        } else {
            view.setPadding(padding, view.getPaddingTop(), view.getPaddingRight(),
                    view.getPaddingBottom());
        }
    }

    @BindingAdapter("android:paddingTop")
    public static void setPaddingTop(View view, int padding) {
        view.setPadding(view.getPaddingLeft(), padding, view.getPaddingRight(),
                view.getPaddingBottom());
    }
}
