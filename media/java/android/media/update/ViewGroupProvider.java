/*
 * Copyright 2018 The Android Open Source Project
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

package android.media.update;

import android.annotation.SystemApi;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;

/**
 * Interface for connecting the public API to an updatable implementation.
 *
 * Each instance object is connected to one corresponding updatable object which implements the
 * runtime behavior of that class. There should a corresponding provider method for all public
 * methods.
 *
 * All methods behave as per their namesake in the public API.
 *
 * @see android.view.View
 *
 * @hide
 */
// TODO @SystemApi
public interface ViewGroupProvider {
    // View methods
    void onAttachedToWindow_impl();
    void onDetachedFromWindow_impl();
    CharSequence getAccessibilityClassName_impl();
    boolean onTouchEvent_impl(MotionEvent ev);
    boolean onTrackballEvent_impl(MotionEvent ev);
    void onFinishInflate_impl();
    void setEnabled_impl(boolean enabled);
    void onVisibilityAggregated_impl(boolean isVisible);
    void onLayout_impl(boolean changed, int left, int top, int right, int bottom);
    void onMeasure_impl(int widthMeasureSpec, int heightMeasureSpec);
    int getSuggestedMinimumWidth_impl();
    int getSuggestedMinimumHeight_impl();
    void setMeasuredDimension_impl(int measuredWidth, int measuredHeight);
    boolean dispatchTouchEvent_impl(MotionEvent ev);

    // ViewGroup methods
    boolean checkLayoutParams_impl(LayoutParams p);
    LayoutParams generateDefaultLayoutParams_impl();
    LayoutParams generateLayoutParams_impl(AttributeSet attrs);
    LayoutParams generateLayoutParams_impl(LayoutParams lp);
    boolean shouldDelayChildPressedState_impl();
    void measureChildWithMargins_impl(View child, int parentWidthMeasureSpec, int widthUsed,
        int parentHeightMeasureSpec, int heightUsed);

    // ViewManager methods
    // ViewParent methods
}
