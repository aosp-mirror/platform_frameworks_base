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

package com.android.systemui.statusbar;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.android.systemui.R;

import java.util.ArrayList;

/**
 * An abstract view for expandable views.
 */
public abstract class ExpandableView extends FrameLayout {

    private final int mMaxNotificationHeight;

    private OnHeightChangedListener mOnHeightChangedListener;
    protected int mActualHeight;
    protected int mClipTopAmount;
    private boolean mActualHeightInitialized;
    private ArrayList<View> mMatchParentViews = new ArrayList<View>();

    public ExpandableView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mMaxNotificationHeight = getResources().getDimensionPixelSize(
                R.dimen.notification_max_height);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int ownMaxHeight = mMaxNotificationHeight;
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        boolean hasFixedHeight = heightMode == MeasureSpec.EXACTLY;
        boolean isHeightLimited = heightMode == MeasureSpec.AT_MOST;
        if (hasFixedHeight || isHeightLimited) {
            int size = MeasureSpec.getSize(heightMeasureSpec);
            ownMaxHeight = Math.min(ownMaxHeight, size);
        }
        int newHeightSpec = MeasureSpec.makeMeasureSpec(ownMaxHeight, MeasureSpec.AT_MOST);
        int maxChildHeight = 0;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            int childHeightSpec = newHeightSpec;
            ViewGroup.LayoutParams layoutParams = child.getLayoutParams();
            if (layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                if (layoutParams.height >= 0) {
                    // An actual height is set
                    childHeightSpec = layoutParams.height > ownMaxHeight
                        ? MeasureSpec.makeMeasureSpec(ownMaxHeight, MeasureSpec.EXACTLY)
                        : MeasureSpec.makeMeasureSpec(layoutParams.height, MeasureSpec.EXACTLY);
                }
                child.measure(
                        getChildMeasureSpec(widthMeasureSpec, 0 /* padding */, layoutParams.width),
                        childHeightSpec);
                int childHeight = child.getMeasuredHeight();
                maxChildHeight = Math.max(maxChildHeight, childHeight);
            } else {
                mMatchParentViews.add(child);
            }
        }
        int ownHeight = hasFixedHeight ? ownMaxHeight : maxChildHeight;
        newHeightSpec = MeasureSpec.makeMeasureSpec(ownHeight, MeasureSpec.EXACTLY);
        for (View child : mMatchParentViews) {
            child.measure(getChildMeasureSpec(
                    widthMeasureSpec, 0 /* padding */, child.getLayoutParams().width),
                    newHeightSpec);
        }
        mMatchParentViews.clear();
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, ownHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!mActualHeightInitialized && mActualHeight == 0) {
            setActualHeight(getInitialHeight());
        }
    }

    protected int getInitialHeight() {
        return getHeight();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (filterMotionEvent(ev)) {
            return super.dispatchTouchEvent(ev);
        }
        return false;
    }

    private boolean filterMotionEvent(MotionEvent event) {
        return event.getActionMasked() != MotionEvent.ACTION_DOWN
                || event.getY() > mClipTopAmount && event.getY() < mActualHeight;
    }

    /**
     * Sets the actual height of this notification. This is different than the laid out
     * {@link View#getHeight()}, as we want to avoid layouting during scrolling and expanding.
     *
     * @param actualHeight The height of this notification.
     * @param notifyListeners Whether the listener should be informed about the change.
     */
    public void setActualHeight(int actualHeight, boolean notifyListeners) {
        mActualHeightInitialized = true;
        mActualHeight = actualHeight;
        if (notifyListeners) {
            notifyHeightChanged();
        }
    }

    public void setActualHeight(int actualHeight) {
        setActualHeight(actualHeight, true);
    }

    /**
     * See {@link #setActualHeight}.
     *
     * @return The current actual height of this notification.
     */
    public int getActualHeight() {
        return mActualHeight;
    }

    /**
     * @return The maximum height of this notification.
     */
    public int getMaxHeight() {
        return getHeight();
    }

    /**
     * @return The minimum height of this notification.
     */
    public int getMinHeight() {
        return getHeight();
    }

    /**
     * Sets the notification as dimmed. The default implementation does nothing.
     *
     * @param dimmed Whether the notification should be dimmed.
     * @param fade Whether an animation should be played to change the state.
     */
    public void setDimmed(boolean dimmed, boolean fade) {
    }

    /**
     * Sets the notification as dark. The default implementation does nothing.
     *
     * @param dark Whether the notification should be dark.
     * @param fade Whether an animation should be played to change the state.
     */
    public void setDark(boolean dark, boolean fade) {
    }

    /**
     * See {@link #setHideSensitive}. This is a variant which notifies this view in advance about
     * the upcoming state of hiding sensitive notifications. It gets called at the very beginning
     * of a stack scroller update such that the updated intrinsic height (which is dependent on
     * whether private or public layout is showing) gets taken into account into all layout
     * calculations.
     */
    public void setHideSensitiveForIntrinsicHeight(boolean hideSensitive) {
    }

    /**
     * Sets whether the notification should hide its private contents if it is sensitive.
     */
    public void setHideSensitive(boolean hideSensitive, boolean animated, long delay,
            long duration) {
    }

    /**
     * @return The desired notification height.
     */
    public int getIntrinsicHeight() {
        return getHeight();
    }

    /**
     * Sets the amount this view should be clipped from the top. This is used when an expanded
     * notification is scrolling in the top or bottom stack.
     *
     * @param clipTopAmount The amount of pixels this view should be clipped from top.
     */
    public void setClipTopAmount(int clipTopAmount) {
        mClipTopAmount = clipTopAmount;
    }

    public int getClipTopAmount() {
        return mClipTopAmount;
    }

    public void setOnHeightChangedListener(OnHeightChangedListener listener) {
        mOnHeightChangedListener = listener;
    }

    /**
     * @return Whether we can expand this views content.
     */
    public boolean isContentExpandable() {
        return false;
    }

    public void notifyHeightChanged() {
        if (mOnHeightChangedListener != null) {
            mOnHeightChangedListener.onHeightChanged(this);
        }
    }

    public boolean isTransparent() {
        return false;
    }

    /**
     * Perform a remove animation on this view.
     *
     * @param duration The duration of the remove animation.
     * @param translationDirection The direction value from [-1 ... 1] indicating in which the
     *                             animation should be performed. A value of -1 means that The
     *                             remove animation should be performed upwards,
     *                             such that the  child appears to be going away to the top. 1
     *                             Should mean the opposite.
     * @param onFinishedRunnable A runnable which should be run when the animation is finished.
     */
    public abstract void performRemoveAnimation(long duration, float translationDirection,
            Runnable onFinishedRunnable);

    public abstract void performAddAnimation(long delay, long duration);

    public abstract void setScrimAmount(float scrimAmount);

    public void setBelowSpeedBump(boolean below) {
    }

    public void onHeightReset() {
        if (mOnHeightChangedListener != null) {
            mOnHeightChangedListener.onReset(this);
        }
    }

    /**
     * A listener notifying when {@link #getActualHeight} changes.
     */
    public interface OnHeightChangedListener {

        /**
         * @param view the view for which the height changed, or {@code null} if just the top
         *             padding or the padding between the elements changed
         */
        void onHeightChanged(ExpandableView view);

        /**
         * Called when the view is reset and therefore the height will change abruptly
         *
         * @param view The view which was reset.
         */
        void onReset(ExpandableView view);
    }
}
