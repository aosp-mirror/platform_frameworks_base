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
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.android.systemui.R;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;

import java.util.ArrayList;

/**
 * An abstract view for expandable views.
 */
public abstract class ExpandableView extends FrameLayout {

    private final int mBottomDecorHeight;
    protected OnHeightChangedListener mOnHeightChangedListener;
    protected int mMaxViewHeight;
    private int mActualHeight;
    protected int mClipTopAmount;
    private boolean mActualHeightInitialized;
    private boolean mDark;
    private ArrayList<View> mMatchParentViews = new ArrayList<View>();
    private int mClipTopOptimization;
    private static Rect mClipRect = new Rect();
    private boolean mWillBeGone;
    private int mMinClipTopAmount = 0;

    public ExpandableView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mMaxViewHeight = getResources().getDimensionPixelSize(
                R.dimen.notification_max_height);
        mBottomDecorHeight = resolveBottomDecorHeight();
    }

    protected int resolveBottomDecorHeight() {
        return getResources().getDimensionPixelSize(
                R.dimen.notification_bottom_decor_height);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int ownMaxHeight = mMaxViewHeight;
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        boolean hasFixedHeight = heightMode == MeasureSpec.EXACTLY;
        if (hasFixedHeight) {
            // We have a height set in our layout, so we want to be at most as big as given
            ownMaxHeight = Math.min(MeasureSpec.getSize(heightMeasureSpec), ownMaxHeight);
        }
        int newHeightSpec = MeasureSpec.makeMeasureSpec(ownMaxHeight, MeasureSpec.AT_MOST);
        int maxChildHeight = 0;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE || isChildInvisible(child)) {
                continue;
            }
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
        int ownHeight = hasFixedHeight ? ownMaxHeight : Math.min(ownMaxHeight, maxChildHeight);
        newHeightSpec = MeasureSpec.makeMeasureSpec(ownHeight, MeasureSpec.EXACTLY);
        for (View child : mMatchParentViews) {
            child.measure(getChildMeasureSpec(
                    widthMeasureSpec, 0 /* padding */, child.getLayoutParams().width),
                    newHeightSpec);
        }
        mMatchParentViews.clear();
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (canHaveBottomDecor()) {
            // We always account for the expandAction as well.
            ownHeight += mBottomDecorHeight;
        }
        setMeasuredDimension(width, ownHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!mActualHeightInitialized && mActualHeight == 0) {
            int initialHeight = getInitialHeight();
            if (initialHeight != 0) {
                setContentHeight(initialHeight);
            }
        }
        updateClipping();
    }

    /**
     * Resets the height of the view on the next layout pass
     */
    protected void resetActualHeight() {
        mActualHeight = 0;
        mActualHeightInitialized = false;
        requestLayout();
    }

    protected int getInitialHeight() {
        return getHeight();
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (filterMotionEvent(ev)) {
            return super.dispatchGenericMotionEvent(ev);
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (filterMotionEvent(ev)) {
            return super.dispatchTouchEvent(ev);
        }
        return false;
    }

    protected boolean filterMotionEvent(MotionEvent event) {
        return event.getActionMasked() != MotionEvent.ACTION_DOWN
                && event.getActionMasked() != MotionEvent.ACTION_HOVER_ENTER
                && event.getActionMasked() != MotionEvent.ACTION_HOVER_MOVE
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
        updateClipping();
        if (notifyListeners) {
            notifyHeightChanged(false  /* needsAnimation */);
        }
    }

    public void setContentHeight(int contentHeight) {
        setActualHeight(contentHeight + getBottomDecorHeight(), true);
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
     * This view may have a bottom decor which will be placed below the content. If it has one, this
     * view will be layouted higher than just the content by {@link #mBottomDecorHeight}.
     * @return the height of the decor if it currently has one
     */
    public int getBottomDecorHeight() {
        return hasBottomDecor() ? mBottomDecorHeight : 0;
    }

    /**
     * @return whether this view may have a bottom decor at all. This will force the view to layout
     *         itself higher than just it's content
     */
    protected boolean canHaveBottomDecor() {
        return false;
    }

    /**
     * @return whether this view has a decor view below it's content. This will make the intrinsic
     *         height from {@link #getIntrinsicHeight()} higher as well
     */
    protected boolean hasBottomDecor() {
        return false;
    }

    /**
     * @return The maximum height of this notification.
     */
    public int getMaxContentHeight() {
        return getHeight();
    }

    /**
     * @return The minimum content height of this notification.
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
     * @param delay If fading, the delay of the animation.
     */
    public void setDark(boolean dark, boolean fade, long delay) {
        mDark = dark;
    }

    public boolean isDark() {
        return mDark;
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

    public void notifyHeightChanged(boolean needsAnimation) {
        if (mOnHeightChangedListener != null) {
            mOnHeightChangedListener.onHeightChanged(this, needsAnimation);
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

    public void setBelowSpeedBump(boolean below) {
    }

    public void onHeightReset() {
        if (mOnHeightChangedListener != null) {
            mOnHeightChangedListener.onReset(this);
        }
    }

    /**
     * This method returns the drawing rect for the view which is different from the regular
     * drawing rect, since we layout all children in the {@link NotificationStackScrollLayout} at
     * position 0 and usually the translation is neglected. Since we are manually clipping this
     * view,we also need to subtract the clipTopAmount from the top. This is needed in order to
     * ensure that accessibility and focusing work correctly.
     *
     * @param outRect The (scrolled) drawing bounds of the view.
     */
    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        outRect.left += getTranslationX();
        outRect.right += getTranslationX();
        outRect.bottom = (int) (outRect.top + getTranslationY() + getActualHeight());
        outRect.top += getTranslationY() + getClipTopAmount();
    }

    @Override
    public void getBoundsOnScreen(Rect outRect, boolean clipToParent) {
        super.getBoundsOnScreen(outRect, clipToParent);
        outRect.bottom = outRect.top + getActualHeight();
        outRect.top += getClipTopOptimization();
    }

    public int getContentHeight() {
        return mActualHeight - getBottomDecorHeight();
    }

    /**
     * @return whether the given child can be ignored for layouting and measuring purposes
     */
    protected boolean isChildInvisible(View child) {
        return false;
    }

    public boolean areChildrenExpanded() {
        return false;
    }

    private void updateClipping() {
        int top = mClipTopOptimization;
        if (top >= getActualHeight()) {
            top = getActualHeight() - 1;
        }
        mClipRect.set(0, top, getWidth(), getActualHeight());
        setClipBounds(mClipRect);
    }

    public int getClipTopOptimization() {
        return mClipTopOptimization;
    }

    /**
     * Set that the view will be clipped by a given amount from the top. Contrary to
     * {@link #setClipTopAmount} this amount doesn't effect shadows and the background.
     *
     * @param clipTopOptimization the amount to clip from the top
     */
    public void setClipTopOptimization(int clipTopOptimization) {
        mClipTopOptimization = clipTopOptimization;
        updateClipping();
    }

    public boolean willBeGone() {
        return mWillBeGone;
    }

    public void setWillBeGone(boolean willBeGone) {
        mWillBeGone = willBeGone;
    }

    public int getMinClipTopAmount() {
        return mMinClipTopAmount;
    }

    public void setMinClipTopAmount(int minClipTopAmount) {
        mMinClipTopAmount = minClipTopAmount;
    }

    /**
     * A listener notifying when {@link #getActualHeight} changes.
     */
    public interface OnHeightChangedListener {

        /**
         * @param view the view for which the height changed, or {@code null} if just the top
         *             padding or the padding between the elements changed
         * @param needsAnimation whether the view height needs to be animated
         */
        void onHeightChanged(ExpandableView view, boolean needsAnimation);

        /**
         * Called when the view is reset and therefore the height will change abruptly
         *
         * @param view The view which was reset.
         */
        void onReset(ExpandableView view);
    }
}
