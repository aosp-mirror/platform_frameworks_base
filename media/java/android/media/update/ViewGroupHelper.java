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

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Helper class for connecting the public API to an updatable implementation.
 *
 * @see ViewGroupProvider
 *
 * @hide
 */
public abstract class ViewGroupHelper<T extends ViewGroupProvider> extends ViewGroup {
    /** @hide */
    final public T mProvider;

    /** @hide */
    public ViewGroupHelper(ProviderCreator<T> creator,
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mProvider = creator.createProvider(this, new SuperProvider(),
                new PrivateProvider());
    }

    /** @hide */
    // TODO @SystemApi
    public T getProvider() {
        return mProvider;
    }

    @Override
    protected void onAttachedToWindow() {
        mProvider.onAttachedToWindow_impl();
    }

    @Override
    protected void onDetachedFromWindow() {
        mProvider.onDetachedFromWindow_impl();
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return mProvider.getAccessibilityClassName_impl();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return mProvider.onTouchEvent_impl(ev);
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev) {
        return mProvider.onTrackballEvent_impl(ev);
    }

    @Override
    public void onFinishInflate() {
        mProvider.onFinishInflate_impl();
    }

    @Override
    public void setEnabled(boolean enabled) {
        mProvider.setEnabled_impl(enabled);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        mProvider.onVisibilityAggregated_impl(isVisible);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        mProvider.onLayout_impl(changed, left, top, right, bottom);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        mProvider.onMeasure_impl(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected int getSuggestedMinimumWidth() {
        return mProvider.getSuggestedMinimumWidth_impl();
    }

    @Override
    protected int getSuggestedMinimumHeight() {
        return mProvider.getSuggestedMinimumHeight_impl();
    }

    // setMeasuredDimension is final

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return mProvider.dispatchTouchEvent_impl(ev);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams p) {
        return mProvider.checkLayoutParams_impl(p);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return mProvider.generateDefaultLayoutParams_impl();
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return mProvider.generateLayoutParams_impl(attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams lp) {
        return mProvider.generateLayoutParams_impl(lp);
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return mProvider.shouldDelayChildPressedState_impl();
    }

    @Override
    protected void measureChildWithMargins(View child, int parentWidthMeasureSpec, int widthUsed,
            int parentHeightMeasureSpec, int heightUsed) {
        mProvider.measureChildWithMargins_impl(child,
                parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed);
    }

    /** @hide */
    public class SuperProvider implements ViewGroupProvider {
        @Override
        public CharSequence getAccessibilityClassName_impl() {
            return ViewGroupHelper.super.getAccessibilityClassName();
        }

        @Override
        public boolean onTouchEvent_impl(MotionEvent ev) {
            return ViewGroupHelper.super.onTouchEvent(ev);
        }

        @Override
        public boolean onTrackballEvent_impl(MotionEvent ev) {
            return ViewGroupHelper.super.onTrackballEvent(ev);
        }

        @Override
        public void onFinishInflate_impl() {
            ViewGroupHelper.super.onFinishInflate();
        }

        @Override
        public void setEnabled_impl(boolean enabled) {
            ViewGroupHelper.super.setEnabled(enabled);
        }

        @Override
        public void onAttachedToWindow_impl() {
            ViewGroupHelper.super.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow_impl() {
            ViewGroupHelper.super.onDetachedFromWindow();
        }

        @Override
        public void onVisibilityAggregated_impl(boolean isVisible) {
            ViewGroupHelper.super.onVisibilityAggregated(isVisible);
        }

        @Override
        public void onLayout_impl(boolean changed, int left, int top, int right, int bottom) {
            // abstract method; no super
        }

        @Override
        public void onMeasure_impl(int widthMeasureSpec, int heightMeasureSpec) {
            ViewGroupHelper.super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        @Override
        public int getSuggestedMinimumWidth_impl() {
            return ViewGroupHelper.super.getSuggestedMinimumWidth();
        }

        @Override
        public int getSuggestedMinimumHeight_impl() {
            return ViewGroupHelper.super.getSuggestedMinimumHeight();
        }

        @Override
        public void setMeasuredDimension_impl(int measuredWidth, int measuredHeight) {
            ViewGroupHelper.super.setMeasuredDimension(measuredWidth, measuredHeight);
        }

        @Override
        public boolean dispatchTouchEvent_impl(MotionEvent ev) {
            return ViewGroupHelper.super.dispatchTouchEvent(ev);
        }

        @Override
        public boolean checkLayoutParams_impl(LayoutParams p) {
            return ViewGroupHelper.super.checkLayoutParams(p);
        }

        @Override
        public LayoutParams generateDefaultLayoutParams_impl() {
            return ViewGroupHelper.super.generateDefaultLayoutParams();
        }

        @Override
        public LayoutParams generateLayoutParams_impl(AttributeSet attrs) {
            return ViewGroupHelper.super.generateLayoutParams(attrs);
        }

        @Override
        public LayoutParams generateLayoutParams_impl(LayoutParams lp) {
            return ViewGroupHelper.super.generateLayoutParams(lp);
        }

        @Override
        public boolean shouldDelayChildPressedState_impl() {
            return ViewGroupHelper.super.shouldDelayChildPressedState();
        }

        @Override
        public void measureChildWithMargins_impl(View child,
                int parentWidthMeasureSpec, int widthUsed,
                int parentHeightMeasureSpec, int heightUsed) {
            ViewGroupHelper.super.measureChildWithMargins(child,
                    parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed);
        }
    }

    /** @hide */
    public class PrivateProvider implements ViewGroupProvider {
        @Override
        public CharSequence getAccessibilityClassName_impl() {
            return ViewGroupHelper.this.getAccessibilityClassName();
        }

        @Override
        public boolean onTouchEvent_impl(MotionEvent ev) {
            return ViewGroupHelper.this.onTouchEvent(ev);
        }

        @Override
        public boolean onTrackballEvent_impl(MotionEvent ev) {
            return ViewGroupHelper.this.onTrackballEvent(ev);
        }

        @Override
        public void onFinishInflate_impl() {
            ViewGroupHelper.this.onFinishInflate();
        }

        @Override
        public void setEnabled_impl(boolean enabled) {
            ViewGroupHelper.this.setEnabled(enabled);
        }

        @Override
        public void onAttachedToWindow_impl() {
            ViewGroupHelper.this.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow_impl() {
            ViewGroupHelper.this.onDetachedFromWindow();
        }

        @Override
        public void onVisibilityAggregated_impl(boolean isVisible) {
            ViewGroupHelper.this.onVisibilityAggregated(isVisible);
        }

        @Override
        public void onLayout_impl(boolean changed, int left, int top, int right, int bottom) {
            ViewGroupHelper.this.onLayout(changed, left, top, right, bottom);
        }

        @Override
        public void onMeasure_impl(int widthMeasureSpec, int heightMeasureSpec) {
            ViewGroupHelper.this.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        @Override
        public int getSuggestedMinimumWidth_impl() {
            return ViewGroupHelper.this.getSuggestedMinimumWidth();
        }

        @Override
        public int getSuggestedMinimumHeight_impl() {
            return ViewGroupHelper.this.getSuggestedMinimumHeight();
        }

        @Override
        public void setMeasuredDimension_impl(int measuredWidth, int measuredHeight) {
            ViewGroupHelper.this.setMeasuredDimension(measuredWidth, measuredHeight);
        }

        @Override
        public boolean dispatchTouchEvent_impl(MotionEvent ev) {
            return ViewGroupHelper.this.dispatchTouchEvent(ev);
        }

        @Override
        public boolean checkLayoutParams_impl(LayoutParams p) {
            return ViewGroupHelper.this.checkLayoutParams(p);
        }

        @Override
        public LayoutParams generateDefaultLayoutParams_impl() {
            return ViewGroupHelper.this.generateDefaultLayoutParams();
        }

        @Override
        public LayoutParams generateLayoutParams_impl(AttributeSet attrs) {
            return ViewGroupHelper.this.generateLayoutParams(attrs);
        }

        @Override
        public LayoutParams generateLayoutParams_impl(LayoutParams lp) {
            return ViewGroupHelper.this.generateLayoutParams(lp);
        }

        @Override
        public boolean shouldDelayChildPressedState_impl() {
            return ViewGroupHelper.this.shouldDelayChildPressedState();
        }

        @Override
        public void measureChildWithMargins_impl(View child,
                int parentWidthMeasureSpec, int widthUsed,
                int parentHeightMeasureSpec, int heightUsed) {
            ViewGroupHelper.this.measureChildWithMargins(child,
                    parentWidthMeasureSpec, widthUsed, parentHeightMeasureSpec, heightUsed);
        }
    }

        /** @hide */
    @FunctionalInterface
    public interface ProviderCreator<T extends ViewGroupProvider> {
        T createProvider(ViewGroupHelper<T> instance, ViewGroupProvider superProvider,
                ViewGroupProvider privateProvider);
    }
}
