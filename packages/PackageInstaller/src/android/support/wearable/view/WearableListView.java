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
 * limitations under the License
 */

package androidx.wear.ble.view;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.PointF;
import android.os.Build;
import android.os.Handler;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Property;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Scroller;

import java.util.ArrayList;
import java.util.List;

/**
 * An alternative version of ListView that is optimized for ease of use on small screen wearable
 * devices. It displays a vertically scrollable list of items, and automatically snaps to the
 * nearest item when the user stops scrolling.
 *
 * <p>
 * For a quick start, you will need to implement a subclass of {@link .Adapter},
 * which will create and bind your views to the {@link .ViewHolder} objects. If you want to add
 * more visual treatment to your views when they become the central items of the
 * WearableListView, have them implement the {@link .OnCenterProximityListener} interface.
 * </p>
 */
@TargetApi(Build.VERSION_CODES.KITKAT_WATCH)
public class WearableListView extends RecyclerView {
    @SuppressWarnings("unused")
    private static final String TAG = "WearableListView";

    private static final long FLIP_ANIMATION_DURATION_MS = 150;
    private static final long CENTERING_ANIMATION_DURATION_MS = 150;

    private static final float TOP_TAP_REGION_PERCENTAGE = .33f;
    private static final float BOTTOM_TAP_REGION_PERCENTAGE = .33f;

    // Each item will occupy one third of the height.
    private static final int THIRD = 3;

    private final int mMinFlingVelocity;
    private final int mMaxFlingVelocity;

    private boolean mMaximizeSingleItem;
    private boolean mCanClick = true;
    // WristGesture navigation signals are delivered as KeyEvents. Allow developer to disable them
    // for this specific View. It might be cleaner to simply have users re-implement onKeyDown().
    // TOOD: Finalize the disabling mechanism here.
    private boolean mGestureNavigationEnabled = true;
    private int mTapPositionX;
    private int mTapPositionY;
    private ClickListener mClickListener;

    private Animator mScrollAnimator;
    // This is a little hacky due to the fact that animator provides incremental values instead of
    // deltas and scrolling code requires deltas. We animate WearableListView directly and use this
    // field to calculate deltas. Obviously this means that only one scrolling algorithm can run at
    // a time, but I don't think it would be wise to have more than one running.
    private int mLastScrollChange;

    private SetScrollVerticallyProperty mSetScrollVerticallyProperty =
            new SetScrollVerticallyProperty();

    private final List<OnScrollListener> mOnScrollListeners = new ArrayList<OnScrollListener>();

    private final List<OnCentralPositionChangedListener> mOnCentralPositionChangedListeners =
            new ArrayList<OnCentralPositionChangedListener>();

    private OnOverScrollListener mOverScrollListener;

    private boolean mGreedyTouchMode;

    private float mStartX;

    private float mStartY;

    private float mStartFirstTop;

    private final int mTouchSlop;

    private boolean mPossibleVerticalSwipe;

    private int mInitialOffset = 0;

    private Scroller mScroller;

    // Top and bottom boundaries for tap checking.  Need to recompute by calling computeTapRegions
    // before referencing.
    private final float[] mTapRegions = new float[2];

    private boolean mGestureDirectionLocked;
    private int mPreviousCentral = 0;

    // Temp variable for storing locations on screen.
    private final int[] mLocation = new int[2];

    // TODO: Consider clearing this when underlying data set changes. If the data set changes, you
    // can't safely assume that this pressed view is in the same place as it was before and it will
    // receive setPressed(false) unnecessarily. In theory it should be fine, but in practice we
    // have places like this: mIconView.setCircleColor(pressed ? mPressedColor : mSelectedColor);
    // This might set selected color on non selected item. Our logic should be: if you change
    // underlying data set, all best are off and you need to preserve the state; we will clear
    // this field. However, I am not willing to introduce this so late in C development.
    private View mPressedView = null;

    private final Runnable mPressedRunnable = new Runnable() {
        @Override
        public void run() {
            if (getChildCount() > 0) {
                mPressedView = getChildAt(findCenterViewIndex());
                mPressedView.setPressed(true);
            } else {
                Log.w(TAG, "mPressedRunnable: the children were removed, skipping.");
            }
        }
    };

    private final Runnable mReleasedRunnable = new Runnable() {
        @Override
        public void run() {
            releasePressedItem();
        }
    };

    private Runnable mNotifyChildrenPostLayoutRunnable = new Runnable() {
        @Override
        public void run() {
            notifyChildrenAboutProximity(false);
        }
    };

    private final AdapterDataObserver mObserver = new AdapterDataObserver() {
        @Override
        public void onChanged() {
            WearableListView.this.addOnLayoutChangeListener(new OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    WearableListView.this.removeOnLayoutChangeListener(this);
                    if (WearableListView.this.getChildCount() > 0) {
                        WearableListView.this.animateToCenter();
                    }
                }
            });
        }
    };

    public WearableListView(Context context) {
        this(context, null);
    }

    public WearableListView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WearableListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setHasFixedSize(true);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setLayoutManager(new LayoutManager());

        final RecyclerView.OnScrollListener onScrollListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && getChildCount() > 0) {
                    handleTouchUp(null, newState);
                }
                for (OnScrollListener listener : mOnScrollListeners) {
                    listener.onScrollStateChanged(newState);
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                onScroll(dy);
            }
        };
        setOnScrollListener(onScrollListener);

        final ViewConfiguration vc = ViewConfiguration.get(context);
        mTouchSlop = vc.getScaledTouchSlop();

        mMinFlingVelocity = vc.getScaledMinimumFlingVelocity();
        mMaxFlingVelocity = vc.getScaledMaximumFlingVelocity();
    }

    @Override
    public void setAdapter(RecyclerView.Adapter adapter) {
        RecyclerView.Adapter currentAdapter = getAdapter();
        if (currentAdapter != null) {
            currentAdapter.unregisterAdapterDataObserver(mObserver);
        }

        super.setAdapter(adapter);

        if (adapter != null) {
            adapter.registerAdapterDataObserver(mObserver);
        }
    }

    /**
     * @return the position of the center child's baseline; -1 if no center child exists or if
     *      the center child does not return a valid baseline.
     */
    @Override
    public int getBaseline() {
        // No children implies there is no center child for which a baseline can be computed.
        if (getChildCount() == 0) {
            return super.getBaseline();
        }

        // Compute the baseline of the center child.
        final int centerChildIndex = findCenterViewIndex();
        final int centerChildBaseline = getChildAt(centerChildIndex).getBaseline();

        // If the center child has no baseline, neither does this list view.
        if (centerChildBaseline == -1) {
            return super.getBaseline();
        }

        return getCentralViewTop() + centerChildBaseline;
    }

    /**
     * @return true if the list is scrolled all the way to the top.
     */
    public boolean isAtTop() {
        if (getChildCount() == 0) {
            return true;
        }

        int centerChildIndex = findCenterViewIndex();
        View centerView = getChildAt(centerChildIndex);
        return getChildAdapterPosition(centerView) == 0 &&
                getScrollState() == RecyclerView.SCROLL_STATE_IDLE;
    }

    /**
     * Clears the state of the layout manager that positions list items.
     */
    public void resetLayoutManager() {
        setLayoutManager(new LayoutManager());
    }

    /**
     * Controls whether WearableListView should intercept all touch events and also prevent the
     * parent from receiving them.
     * @param greedy If true it will intercept all touch events.
     */
    public void setGreedyTouchMode(boolean greedy) {
        mGreedyTouchMode = greedy;
    }

    /**
     * By default the first element of the list is initially positioned in the center of the screen.
     * This method allows the developer to specify a different offset, e.g. to hide the
     * WearableListView before the user is allowed to use it.
     *
     * @param top How far the elements should be pushed down.
     */
    public void setInitialOffset(int top) {
        mInitialOffset = top;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }

        if (mGreedyTouchMode && getChildCount() > 0) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                mStartX = event.getX();
                mStartY = event.getY();
                mStartFirstTop = getChildCount() > 0 ? getChildAt(0).getTop() : 0;
                mPossibleVerticalSwipe = true;
                mGestureDirectionLocked = false;
            } else if (action == MotionEvent.ACTION_MOVE && mPossibleVerticalSwipe) {
                handlePossibleVerticalSwipe(event);
            }
            getParent().requestDisallowInterceptTouchEvent(mPossibleVerticalSwipe);
        }
        return super.onInterceptTouchEvent(event);
    }

    private boolean handlePossibleVerticalSwipe(MotionEvent event) {
        if (mGestureDirectionLocked) {
            return mPossibleVerticalSwipe;
        }
        float deltaX = Math.abs(mStartX - event.getX());
        float deltaY = Math.abs(mStartY - event.getY());
        float distance = (deltaX * deltaX) + (deltaY * deltaY);
        // Verify that the distance moved in the combined x/y direction is at
        // least touch slop before determining the gesture direction.
        if (distance > (mTouchSlop * mTouchSlop)) {
            if (deltaX > deltaY) {
                mPossibleVerticalSwipe = false;
            }
            mGestureDirectionLocked = true;
        }
        return mPossibleVerticalSwipe;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }

        // super.onTouchEvent can change the state of the scroll, keep a copy so that handleTouchUp
        // can exit early if scrollState != IDLE when the touch event started.
        int scrollState = getScrollState();
        boolean result = super.onTouchEvent(event);
        if (getChildCount() > 0) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                handleTouchDown(event);
            } else if (action == MotionEvent.ACTION_UP) {
                handleTouchUp(event, scrollState);
                getParent().requestDisallowInterceptTouchEvent(false);
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (Math.abs(mTapPositionX - (int) event.getX()) >= mTouchSlop ||
                        Math.abs(mTapPositionY - (int) event.getY()) >= mTouchSlop) {
                    releasePressedItem();
                    mCanClick = false;
                }
                result |= handlePossibleVerticalSwipe(event);
                getParent().requestDisallowInterceptTouchEvent(mPossibleVerticalSwipe);
            } else if (action == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
                mCanClick = true;
            }
        }
        return result;
    }

    private void releasePressedItem() {
        if (mPressedView != null) {
            mPressedView.setPressed(false);
            mPressedView = null;
        }
        Handler handler = getHandler();
        if (handler != null) {
            handler.removeCallbacks(mPressedRunnable);
        }
    }

    private void onScroll(int dy) {
        for (OnScrollListener listener : mOnScrollListeners) {
            listener.onScroll(dy);
        }
        notifyChildrenAboutProximity(true);
    }

    /**
     * Adds a listener that will be called when the content of the list view is scrolled.
     */
    public void addOnScrollListener(OnScrollListener listener) {
        mOnScrollListeners.add(listener);
    }

    /**
     * Removes listener for scroll events.
     */
    public void removeOnScrollListener(OnScrollListener listener) {
        mOnScrollListeners.remove(listener);
    }

    /**
     * Adds a listener that will be called when the central item of the list changes.
     */
    public void addOnCentralPositionChangedListener(OnCentralPositionChangedListener listener) {
        mOnCentralPositionChangedListeners.add(listener);
    }

    /**
     * Removes a listener that would be called when the central item of the list changes.
     */
    public void removeOnCentralPositionChangedListener(OnCentralPositionChangedListener listener) {
        mOnCentralPositionChangedListeners.remove(listener);
    }

    /**
     * Determines if navigation of list with wrist gestures is enabled.
     */
    public boolean isGestureNavigationEnabled() {
        return mGestureNavigationEnabled;
    }

    /**
     * Sets whether navigation of list with wrist gestures is enabled.
     */
    public void setEnableGestureNavigation(boolean enabled) {
        mGestureNavigationEnabled = enabled;
    }

    @Override /* KeyEvent.Callback */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Respond to keycodes (at least originally generated and injected by wrist gestures).
        if (mGestureNavigationEnabled) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
                    fling(0, -mMinFlingVelocity);
                    return true;
                case KeyEvent.KEYCODE_NAVIGATE_NEXT:
                    fling(0, mMinFlingVelocity);
                    return true;
                case KeyEvent.KEYCODE_NAVIGATE_IN:
                    return tapCenterView();
                case KeyEvent.KEYCODE_NAVIGATE_OUT:
                    // Returing false leaves the action to the container of this WearableListView
                    // (e.g. finishing the activity containing this WearableListView).
                    return false;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Simulate tapping the child view at the center of this list.
     */
    private boolean tapCenterView() {
        if (!isEnabled() || getVisibility() != View.VISIBLE) {
            return false;
        }
        int index = findCenterViewIndex();
        View view = getChildAt(index);
        ViewHolder holder = getChildViewHolder(view);
        if (mClickListener != null) {
            mClickListener.onClick(holder);
            return true;
        }
        return false;
    }

    private boolean checkForTap(MotionEvent event) {
        // No taps are accepted if this view is disabled.
        if (!isEnabled()) {
            return false;
        }

        float rawY = event.getRawY();
        int index = findCenterViewIndex();
        View view = getChildAt(index);
        ViewHolder holder = getChildViewHolder(view);
        computeTapRegions(mTapRegions);
        if (rawY > mTapRegions[0] && rawY < mTapRegions[1]) {
            if (mClickListener != null) {
                mClickListener.onClick(holder);
            }
            return true;
        }
        if (index > 0 && rawY <= mTapRegions[0]) {
            animateToMiddle(index - 1, index);
            return true;
        }
        if (index < getChildCount() - 1 && rawY >= mTapRegions[1]) {
            animateToMiddle(index + 1, index);
            return true;
        }
        if (index == 0 && rawY <= mTapRegions[0] && mClickListener != null) {
            // Special case: if the top third of the screen is empty and the touch event happens
            // there, we don't want to immediately disallow the parent from using it. We tell
            // parent to disallow intercept only after we locked a gesture. Before that he
            // might do something with the action.
            mClickListener.onTopEmptyRegionClick();
            return true;
        }
        return false;
    }

    private void animateToMiddle(int newCenterIndex, int oldCenterIndex) {
        if (newCenterIndex == oldCenterIndex) {
            throw new IllegalArgumentException(
                    "newCenterIndex must be different from oldCenterIndex");
        }
        List<Animator> animators = new ArrayList<Animator>();
        View child = getChildAt(newCenterIndex);
        int scrollToMiddle = getCentralViewTop() - child.getTop();
        startScrollAnimation(animators, scrollToMiddle, FLIP_ANIMATION_DURATION_MS);
    }

    private void startScrollAnimation(List<Animator> animators, int scroll, long duration) {
        startScrollAnimation(animators, scroll, duration, 0);
    }

    private void startScrollAnimation(List<Animator> animators, int scroll, long duration,
            long  delay) {
        startScrollAnimation(animators, scroll, duration, delay, null);
    }

    private void startScrollAnimation(
            int scroll, long duration, long  delay, Animator.AnimatorListener listener) {
        startScrollAnimation(null, scroll, duration, delay, listener);
    }

    private void startScrollAnimation(List<Animator> animators, int scroll, long duration,
            long  delay, Animator.AnimatorListener listener) {
        if (mScrollAnimator != null) {
            mScrollAnimator.cancel();
        }

        mLastScrollChange = 0;
        ObjectAnimator scrollAnimator = ObjectAnimator.ofInt(this, mSetScrollVerticallyProperty,
                0, -scroll);

        if (animators != null) {
            animators.add(scrollAnimator);
            AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.playTogether(animators);
            mScrollAnimator = animatorSet;
        } else {
            mScrollAnimator = scrollAnimator;
        }
        mScrollAnimator.setDuration(duration);
        if (listener != null) {
            mScrollAnimator.addListener(listener);
        }
        if (delay > 0) {
            mScrollAnimator.setStartDelay(delay);
        }
        mScrollAnimator.start();
    }

    @Override
    public boolean fling(int velocityX, int velocityY) {
        if (getChildCount() == 0) {
            return false;
        }
        // If we are flinging towards empty space (before first element or after last), we reuse
        // original flinging mechanism.
        final int index = findCenterViewIndex();
        final View child = getChildAt(index);
        int currentPosition = getChildPosition(child);
        if ((currentPosition == 0 && velocityY < 0) ||
                (currentPosition == getAdapter().getItemCount() - 1 && velocityY > 0)) {
            return super.fling(velocityX, velocityY);
        }

        if (Math.abs(velocityY) < mMinFlingVelocity) {
            return false;
        }
        velocityY = Math.max(Math.min(velocityY, mMaxFlingVelocity), -mMaxFlingVelocity);

        if (mScroller == null) {
            mScroller = new Scroller(getContext(), null, true);
        }
        mScroller.fling(0, 0, 0, velocityY, Integer.MIN_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        int finalY = mScroller.getFinalY();
        int delta = finalY / (getPaddingTop() + getAdjustedHeight() / 2);
        if (delta == 0) {
            // If the fling would not be enough to change position, we increase it to satisfy user's
            // intent of switching current position.
            delta = velocityY > 0 ? 1 : -1;
        }
        int finalPosition = Math.max(
                0, Math.min(getAdapter().getItemCount() - 1, currentPosition + delta));
        smoothScrollToPosition(finalPosition);
        return true;
    }

    public void smoothScrollToPosition(int position, RecyclerView.SmoothScroller smoothScroller) {
        LayoutManager layoutManager = (LayoutManager) getLayoutManager();
        layoutManager.setCustomSmoothScroller(smoothScroller);
        smoothScrollToPosition(position);
        layoutManager.clearCustomSmoothScroller();
    }

    @Override
    public ViewHolder getChildViewHolder(View child) {
        return (ViewHolder) super.getChildViewHolder(child);
    }

    /**
     * Adds a listener that will be called when the user taps on the WearableListView or its items.
     */
    public void setClickListener(ClickListener clickListener) {
        mClickListener = clickListener;
    }

    /**
     * Adds a listener that will be called when the user drags the top element below its allowed
     * bottom position.
     *
     * @hide
     */
    public void setOverScrollListener(OnOverScrollListener listener) {
        mOverScrollListener = listener;
    }

    private int findCenterViewIndex() {
        // TODO(gruszczy): This could be easily optimized, so that we stop looking when we the
        // distance starts growing again, instead of finding the closest. It would safe half of
        // the loop.
        int count = getChildCount();
        int index = -1;
        int closest = Integer.MAX_VALUE;
        int centerY = getCenterYPos(this);
        for (int i = 0; i < count; ++i) {
            final View child = getChildAt(i);
            int childCenterY = getTop() + getCenterYPos(child);
            final int distance = Math.abs(centerY - childCenterY);
            if (distance < closest) {
                closest = distance;
                index = i;
            }
        }
        if (index == -1) {
            throw new IllegalStateException("Can't find central view.");
        }
        return index;
    }

    private static int getCenterYPos(View v) {
        return v.getTop() + v.getPaddingTop() + getAdjustedHeight(v) / 2;
    }

    private void handleTouchUp(MotionEvent event, int scrollState) {
        if (mCanClick && event != null && checkForTap(event)) {
            Handler handler = getHandler();
            if (handler != null) {
                handler.postDelayed(mReleasedRunnable, ViewConfiguration.getTapTimeout());
            }
            return;
        }

        if (scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            // We are flinging, so let's not start animations just yet. Instead we will start them
            // when the fling finishes.
            return;
        }

        if (isOverScrolling()) {
            mOverScrollListener.onOverScroll();
        } else {
            animateToCenter();
        }
    }

    private boolean isOverScrolling() {
        return getChildCount() > 0
                // If first view top was below the central top, it means it was never centered.
                // Don't allow overscroll, otherwise a simple touch (instead of a drag) will be
                // enough to trigger overscroll.
                && mStartFirstTop <= getCentralViewTop()
                && getChildAt(0).getTop() >= getTopViewMaxTop()
                && mOverScrollListener != null;
    }

    private int getTopViewMaxTop() {
        return getHeight() / 2;
    }

    private int getItemHeight() {
        // Round up so that the screen is fully occupied by 3 items.
        return getAdjustedHeight() / THIRD + 1;
    }

    /**
     * Returns top of the central {@code View} in the list when such view is fully centered.
     *
     * This is a more or a less a static value that you can use to align other views with the
     * central one.
     */
    public int getCentralViewTop() {
        return getPaddingTop() + getItemHeight();
    }

    /**
     * Automatically starts an animation that snaps the list to center on the element closest to the
     * middle.
     */
    public void animateToCenter() {
        final int index = findCenterViewIndex();
        final View child = getChildAt(index);
        final int scrollToMiddle = getCentralViewTop() - child.getTop();
        startScrollAnimation(scrollToMiddle, CENTERING_ANIMATION_DURATION_MS, 0,
                new SimpleAnimatorListener() {
                    @Override
                    public void onAnimationEnd(Animator animator) {
                        if (!wasCanceled()) {
                            mCanClick = true;
                        }
                    }
                });
    }

    /**
     * Animate the list so that the first view is back to its initial position.
     * @param endAction Action to execute when the animation is done.
     * @hide
     */
    public void animateToInitialPosition(final Runnable endAction) {
        final View child = getChildAt(0);
        final int scrollToMiddle = getCentralViewTop() + mInitialOffset - child.getTop();
        startScrollAnimation(scrollToMiddle, CENTERING_ANIMATION_DURATION_MS, 0,
                new SimpleAnimatorListener() {
                    @Override
                    public void onAnimationEnd(Animator animator) {
                        if (endAction != null) {
                            endAction.run();
                        }
                    }
                });
    }

    private void handleTouchDown(MotionEvent event) {
        if (mCanClick) {
            mTapPositionX = (int) event.getX();
            mTapPositionY = (int) event.getY();
            float rawY = event.getRawY();
            computeTapRegions(mTapRegions);
            if (rawY > mTapRegions[0] && rawY < mTapRegions[1]) {
                View view = getChildAt(findCenterViewIndex());
                if (view instanceof OnCenterProximityListener) {
                    Handler handler = getHandler();
                    if (handler != null) {
                        handler.removeCallbacks(mReleasedRunnable);
                        handler.postDelayed(mPressedRunnable, ViewConfiguration.getTapTimeout());
                    }
                }
            }
        }
    }

    private void setScrollVertically(int scroll) {
        scrollBy(0, scroll - mLastScrollChange);
        mLastScrollChange = scroll;
    }

    private int getAdjustedHeight() {
        return getAdjustedHeight(this);
    }

    private static int getAdjustedHeight(View v) {
        return v.getHeight() - v.getPaddingBottom() - v.getPaddingTop();
    }

    private void computeTapRegions(float[] tapRegions) {
        mLocation[0] = mLocation[1] = 0;
        getLocationOnScreen(mLocation);
        int mScreenTop = mLocation[1];
        int height = getHeight();
        tapRegions[0] = mScreenTop + height * TOP_TAP_REGION_PERCENTAGE;
        tapRegions[1] = mScreenTop + height * (1 - BOTTOM_TAP_REGION_PERCENTAGE);
    }

    /**
     * Determines if, when there is only one item in the WearableListView, that the single item
     * is laid out so that it's height fills the entire WearableListView.
     */
    public boolean getMaximizeSingleItem() {
        return mMaximizeSingleItem;
    }

    /**
     * When set to true, if there is only one item in the WearableListView, it will fill the entire
     * WearableListView. When set to false, the default behavior will be used and the single item
     * will fill only a third of the screen.
     */
    public void setMaximizeSingleItem(boolean maximizeSingleItem) {
        mMaximizeSingleItem = maximizeSingleItem;
    }

    private void notifyChildrenAboutProximity(boolean animate) {
        LayoutManager layoutManager = (LayoutManager) getLayoutManager();
        int count = layoutManager.getChildCount();

        if (count == 0) {
            return;
        }

        int index = layoutManager.findCenterViewIndex();
        for (int i = 0; i < count; ++i) {
            final View view = layoutManager.getChildAt(i);
            ViewHolder holder = getChildViewHolder(view);
            holder.onCenterProximity(i == index, animate);
        }
        final int position = getChildViewHolder(getChildAt(index)).getPosition();
        if (position != mPreviousCentral) {
            for (OnScrollListener listener : mOnScrollListeners) {
                listener.onCentralPositionChanged(position);
            }
            for (OnCentralPositionChangedListener listener :
                    mOnCentralPositionChangedListeners) {
                listener.onCentralPositionChanged(position);
            }
            mPreviousCentral = position;
        }
    }

    // TODO: Move this to a separate class, so it can't directly interact with the WearableListView.
    private class LayoutManager extends RecyclerView.LayoutManager {
        private int mFirstPosition;

        private boolean mPushFirstHigher;

        private int mAbsoluteScroll;

        private boolean mUseOldViewTop = true;

        private boolean mWasZoomedIn = false;

        private RecyclerView.SmoothScroller mSmoothScroller;

        private RecyclerView.SmoothScroller mDefaultSmoothScroller;

        // We need to have another copy of the same method, because this one uses
        // LayoutManager.getChildCount/getChildAt instead of View.getChildCount/getChildAt and
        // they return different values.
        private int findCenterViewIndex() {
            // TODO(gruszczy): This could be easily optimized, so that we stop looking when we the
            // distance starts growing again, instead of finding the closest. It would safe half of
            // the loop.
            int count = getChildCount();
            int index = -1;
            int closest = Integer.MAX_VALUE;
            int centerY = getCenterYPos(WearableListView.this);
            for (int i = 0; i < count; ++i) {
                final View child = getLayoutManager().getChildAt(i);
                int childCenterY = getTop() + getCenterYPos(child);
                final int distance = Math.abs(centerY - childCenterY);
                if (distance < closest) {
                    closest = distance;
                    index = i;
                }
            }
            if (index == -1) {
                throw new IllegalStateException("Can't find central view.");
            }
            return index;
        }

        @Override
        public void onLayoutChildren(RecyclerView.Recycler recycler, State state) {
            final int parentBottom = getHeight() - getPaddingBottom();
            // By default we assume this is the first run and the first element will be centered
            // with optional initial offset.
            int oldTop = getCentralViewTop() + mInitialOffset;
            // Here we handle any other situation where we relayout or we want to achieve a
            // specific layout of children.
            if (mUseOldViewTop && getChildCount() > 0) {
                // We are performing a relayout after we already had some children, because e.g. the
                // contents of an adapter has changed. First we want to check, if the central item
                // from before the layout is still here, because we want to preserve it.
                int index = findCenterViewIndex();
                int position = getPosition(getChildAt(index));
                if (position == NO_POSITION) {
                    // Central item was removed. Let's find the first surviving item and use it
                    // as an anchor.
                    for (int i = 0, N = getChildCount(); index + i < N || index - i >= 0; ++i) {
                        View child = getChildAt(index + i);
                        if (child != null) {
                            position = getPosition(child);
                            if (position != NO_POSITION) {
                                index = index + i;
                                break;
                            }
                        }
                        child = getChildAt(index - i);
                        if (child != null) {
                            position = getPosition(child);
                            if (position != NO_POSITION) {
                                index = index - i;
                                break;
                            }
                        }
                    }
                }
                if (position == NO_POSITION) {
                    // None of the children survives the relayout, let's just use the top of the
                    // first one.
                    oldTop = getChildAt(0).getTop();
                    int count = state.getItemCount();
                    // Lets first make sure that the first position is not above the last element,
                    // which can happen if elements were removed.
                    while (mFirstPosition >= count && mFirstPosition > 0) {
                        mFirstPosition--;
                    }
                } else {
                    // Some of the children survived the relayout. We will keep it in its place,
                    // but go through previous children and maybe add them.
                    if (!mWasZoomedIn) {
                        // If we were previously zoomed-in on a single item, ignore this and just
                        // use the default value set above. Reasoning: if we are still zoomed-in,
                        // oldTop will be ignored when laying out the single child element. If we
                        // are no longer zoomed in, then we want to position items using the top
                        // of the single item as if the single item was not zoomed in, which is
                        // equal to the default value.
                        oldTop = getChildAt(index).getTop();
                    }
                    while (oldTop > getPaddingTop() && position > 0) {
                        position--;
                        oldTop -= getItemHeight();
                    }
                    if (position == 0 && oldTop > getCentralViewTop()) {
                        // We need to handle special case where the first, central item was removed
                        // and now the first element is hanging below, instead of being nicely
                        // centered.
                        oldTop = getCentralViewTop();
                    }
                    mFirstPosition = position;
                }
            } else if (mPushFirstHigher) {
                // We are trying to position elements ourselves, so we force position of the first
                // one.
                oldTop = getCentralViewTop() - getItemHeight();
            }

            performLayoutChildren(recycler, state, parentBottom, oldTop);

            // Since the content might have changed, we need to adjust the absolute scroll in case
            // some elements have disappeared or were added.
            if (getChildCount() == 0) {
                setAbsoluteScroll(0);
            } else {
                View child = getChildAt(findCenterViewIndex());
                setAbsoluteScroll(child.getTop() - getCentralViewTop() + getPosition(child) *
                        getItemHeight());
            }

            mUseOldViewTop = true;
            mPushFirstHigher = false;
        }

        private void performLayoutChildren(Recycler recycler, State state, int parentBottom,
                                           int top) {
            detachAndScrapAttachedViews(recycler);

            if (mMaximizeSingleItem && state.getItemCount() == 1) {
                performLayoutOneChild(recycler, parentBottom);
                mWasZoomedIn = true;
            } else {
                performLayoutMultipleChildren(recycler, state, parentBottom, top);
                mWasZoomedIn = false;
            }

            if (getChildCount() > 0) {
                post(mNotifyChildrenPostLayoutRunnable);
            }
        }

        private void performLayoutOneChild(Recycler recycler, int parentBottom) {
            final int right = getWidth() - getPaddingRight();
            View v = recycler.getViewForPosition(getFirstPosition());
            addView(v, 0);
            measureZoomView(v);
            v.layout(getPaddingLeft(), getPaddingTop(), right, parentBottom);
        }

        private void performLayoutMultipleChildren(Recycler recycler, State state, int parentBottom,
                                                   int top) {
            int bottom;
            final int left = getPaddingLeft();
            final int right = getWidth() - getPaddingRight();
            final int count = state.getItemCount();
            // If we are laying out children with center element being different than the first, we
            // need to start with previous child which appears half visible at the top.
            for (int i = 0; getFirstPosition() + i < count; i++, top = bottom) {
                if (top >= parentBottom) {
                    break;
                }
                View v = recycler.getViewForPosition(getFirstPosition() + i);
                addView(v, i);
                measureThirdView(v);
                bottom = top + getItemHeight();
                v.layout(left, top, right, bottom);
            }
        }

        private void setAbsoluteScroll(int absoluteScroll) {
            mAbsoluteScroll = absoluteScroll;
            for (OnScrollListener listener : mOnScrollListeners) {
                listener.onAbsoluteScrollChange(mAbsoluteScroll);
            }
        }

        private void measureView(View v, int height) {
            final LayoutParams lp = (LayoutParams) v.getLayoutParams();
            final int widthSpec = getChildMeasureSpec(getWidth(),
                getPaddingLeft() + getPaddingRight() + lp.leftMargin + lp.rightMargin, lp.width,
                canScrollHorizontally());
            final int heightSpec = getChildMeasureSpec(getHeight(),
                getPaddingTop() + getPaddingBottom() + lp.topMargin + lp.bottomMargin,
                height, canScrollVertically());
            v.measure(widthSpec, heightSpec);
        }

        private void measureThirdView(View v) {
            measureView(v, (int) (1 + (float) getHeight() / THIRD));
        }

        private void measureZoomView(View v) {
            measureView(v, getHeight());
        }

        @Override
        public RecyclerView.LayoutParams generateDefaultLayoutParams() {
            return new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        @Override
        public boolean canScrollVertically() {
            // Disable vertical scrolling when zoomed.
            return getItemCount() != 1 || !mWasZoomedIn;
        }

        @Override
        public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler, State state) {
            // TODO(gruszczy): This code is shit, needs to be rewritten.
            if (getChildCount() == 0) {
                return 0;
            }
            int scrolled = 0;
            final int left = getPaddingLeft();
            final int right = getWidth() - getPaddingRight();
            if (dy < 0) {
                while (scrolled > dy) {
                    final View topView = getChildAt(0);
                    if (getFirstPosition() > 0) {
                        final int hangingTop = Math.max(-topView.getTop(), 0);
                        final int scrollBy = Math.min(scrolled - dy, hangingTop);
                        scrolled -= scrollBy;
                        offsetChildrenVertical(scrollBy);
                        if (getFirstPosition() > 0 && scrolled > dy) {
                            mFirstPosition--;
                            View v = recycler.getViewForPosition(getFirstPosition());
                            addView(v, 0);
                            measureThirdView(v);
                            final int bottom = topView.getTop();
                            final int top = bottom - getItemHeight();
                            v.layout(left, top, right, bottom);
                        } else {
                            break;
                        }
                    } else {
                        mPushFirstHigher = false;
                        int maxScroll = mOverScrollListener!= null ?
                                getHeight() : getTopViewMaxTop();
                        final int scrollBy = Math.min(-dy + scrolled, maxScroll - topView.getTop());
                        scrolled -= scrollBy;
                        offsetChildrenVertical(scrollBy);
                        break;
                    }
                }
            } else if (dy > 0) {
                final int parentHeight = getHeight();
                while (scrolled < dy) {
                    final View bottomView = getChildAt(getChildCount() - 1);
                    if (state.getItemCount() > mFirstPosition + getChildCount()) {
                        final int hangingBottom =
                                Math.max(bottomView.getBottom() - parentHeight, 0);
                        final int scrollBy = -Math.min(dy - scrolled, hangingBottom);
                        scrolled -= scrollBy;
                        offsetChildrenVertical(scrollBy);
                        if (scrolled < dy) {
                            View v = recycler.getViewForPosition(mFirstPosition + getChildCount());
                            final int top = getChildAt(getChildCount() - 1).getBottom();
                            addView(v);
                            measureThirdView(v);
                            final int bottom = top + getItemHeight();
                            v.layout(left, top, right, bottom);
                        } else {
                            break;
                        }
                    } else {
                        final int scrollBy =
                                Math.max(-dy + scrolled, getHeight() / 2 - bottomView.getBottom());
                        scrolled -= scrollBy;
                        offsetChildrenVertical(scrollBy);
                        break;
                    }
                }
            }
            recycleViewsOutOfBounds(recycler);
            setAbsoluteScroll(mAbsoluteScroll + scrolled);
            return scrolled;
        }

        @Override
        public void scrollToPosition(int position) {
            mUseOldViewTop = false;
            if (position > 0) {
                mFirstPosition = position - 1;
                mPushFirstHigher = true;
            } else {
                mFirstPosition = position;
                mPushFirstHigher = false;
            }
            requestLayout();
        }

        public void setCustomSmoothScroller(RecyclerView.SmoothScroller smoothScroller) {
            mSmoothScroller = smoothScroller;
        }

        public void clearCustomSmoothScroller() {
            mSmoothScroller = null;
        }

        public RecyclerView.SmoothScroller getDefaultSmoothScroller(RecyclerView recyclerView) {
            if (mDefaultSmoothScroller == null) {
                mDefaultSmoothScroller = new SmoothScroller(
                        recyclerView.getContext(), this);
            }
            return mDefaultSmoothScroller;
        }
        @Override
        public void smoothScrollToPosition(RecyclerView recyclerView, State state,
                int position) {
            RecyclerView.SmoothScroller scroller = mSmoothScroller;
            if (scroller == null) {
                scroller = getDefaultSmoothScroller(recyclerView);
            }
            scroller.setTargetPosition(position);
            startSmoothScroll(scroller);
        }

        private void recycleViewsOutOfBounds(RecyclerView.Recycler recycler) {
            final int childCount = getChildCount();
            final int parentWidth = getWidth();
            // Here we want to use real height, so we don't remove views that are only visible in
            // padded section.
            final int parentHeight = getHeight();
            boolean foundFirst = false;
            int first = 0;
            int last = 0;
            for (int i = 0; i < childCount; i++) {
                final View v = getChildAt(i);
                if (v.hasFocus() || (v.getRight() >= 0 && v.getLeft() <= parentWidth &&
                        v.getBottom() >= 0 && v.getTop() <= parentHeight)) {
                    if (!foundFirst) {
                        first = i;
                        foundFirst = true;
                    }
                    last = i;
                }
            }
            for (int i = childCount - 1; i > last; i--) {
                removeAndRecycleViewAt(i, recycler);
            }
            for (int i = first - 1; i >= 0; i--) {
                removeAndRecycleViewAt(i, recycler);
            }
            if (getChildCount() == 0) {
                mFirstPosition = 0;
            } else if (first > 0) {
                mPushFirstHigher = true;
                mFirstPosition += first;
            }
        }

        public int getFirstPosition() {
            return mFirstPosition;
        }

        @Override
        public void onAdapterChanged(RecyclerView.Adapter oldAdapter,
                RecyclerView.Adapter newAdapter) {
            removeAllViews();
        }
    }

    /**
     * Interface for receiving callbacks when WearableListView children become or cease to be the
     * central item.
     */
    public interface OnCenterProximityListener {
        /**
         * Called when this view becomes central item of the WearableListView.
         *
         * @param animate Whether you should animate your transition of the View to become the
         *                central item. If false, this is the initial setting and you should
         *                transition immediately.
         */
        void onCenterPosition(boolean animate);

        /**
         * Called when this view stops being the central item of the WearableListView.
         * @param animate Whether you should animate your transition of the View to being
         *                non central item. If false, this is the initial setting and you should
         *                transition immediately.
         */
        void onNonCenterPosition(boolean animate);
    }

    /**
     * Interface for listening for click events on WearableListView.
     */
    public interface ClickListener {
        /**
         * Called when the central child of the WearableListView is tapped.
         * @param view View that was clicked.
         */
        public void onClick(ViewHolder view);

        /**
         * Called when the user taps the top third of the WearableListView and no item is present
         * there. This can happen when you are in initial state and the first, top-most item of the
         * WearableListView is centered.
         */
        public void onTopEmptyRegionClick();
    }

    /**
     * @hide
     */
    public interface OnOverScrollListener {
        public void onOverScroll();
    }

    /**
     * Interface for listening to WearableListView content scrolling.
     */
    public interface OnScrollListener {
        /**
         * Called when the content is scrolled, reporting the relative scroll value.
         * @param scroll Amount the content was scrolled. This is a delta from the previous
         *               position to the new position.
         */
        public void onScroll(int scroll);

        /**
         * Called when the content is scrolled, reporting the absolute scroll value.
         *
         * @deprecated BE ADVISED DO NOT USE THIS This might provide wrong values when contents
         * of a RecyclerView change.
         *
         * @param scroll Absolute scroll position of the content inside the WearableListView.
         */
        @Deprecated
        public void onAbsoluteScrollChange(int scroll);

        /**
         * Called when WearableListView's scroll state changes.
         *
         * @param scrollState The updated scroll state. One of {@link #SCROLL_STATE_IDLE},
         *                    {@link #SCROLL_STATE_DRAGGING} or {@link #SCROLL_STATE_SETTLING}.
         */
        public void onScrollStateChanged(int scrollState);

        /**
         * Called when the central item of the WearableListView changes.
         *
         * @param centralPosition Position of the item in the Adapter.
         */
        public void onCentralPositionChanged(int centralPosition);
    }

    /**
     * A listener interface that can be added to the WearableListView to get notified when the
     * central item is changed.
     */
    public interface OnCentralPositionChangedListener {
        /**
         * Called when the central item of the WearableListView changes.
         *
         * @param centralPosition Position of the item in the Adapter.
         */
        void onCentralPositionChanged(int centralPosition);
    }

    /**
     * Base class for adapters providing data for the WearableListView. For details refer to
     * RecyclerView.Adapter.
     */
    public static abstract class Adapter extends RecyclerView.Adapter<ViewHolder> {
    }

    private static class SmoothScroller extends LinearSmoothScroller {

        private static final float MILLISECONDS_PER_INCH = 100f;

        private final LayoutManager mLayoutManager;

        public SmoothScroller(Context context, WearableListView.LayoutManager manager) {
            super(context);
            mLayoutManager = manager;
        }

        @Override
        protected void onStart() {
            super.onStart();
        }

        // TODO: (mindyp): when flinging, return the dydt that triggered the fling.
        @Override
        protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
            return MILLISECONDS_PER_INCH / displayMetrics.densityDpi;
        }

        @Override
        public int calculateDtToFit(int viewStart, int viewEnd, int boxStart, int boxEnd, int
                snapPreference) {
            // Snap to center.
            return (boxStart + boxEnd) / 2 - (viewStart + viewEnd) / 2;
        }

        @Override
        public PointF computeScrollVectorForPosition(int targetPosition) {
            if (targetPosition < mLayoutManager.getFirstPosition()) {
                return new PointF(0, -1);
            } else {
                return new PointF(0, 1);
            }
        }
    }

    /**
     * Wrapper around items displayed in the list view. {@link .Adapter} must return objects that
     * are instances of this class. Consider making the wrapped View implement
     * {@link .OnCenterProximityListener} if you want to receive a callback when it becomes or
     * ceases to be the central item in the WearableListView.
     */
    public static class ViewHolder extends RecyclerView.ViewHolder {
        public ViewHolder(View itemView) {
            super(itemView);
        }

        /**
         * Called when the wrapped view is becoming or ceasing to be the central item of the
         * WearableListView.
         *
         * Retained as protected for backwards compatibility.
         *
         * @hide
         */
        protected void onCenterProximity(boolean isCentralItem, boolean animate) {
            if (!(itemView instanceof OnCenterProximityListener)) {
                return;
            }
            OnCenterProximityListener item = (OnCenterProximityListener) itemView;
            if (isCentralItem) {
                item.onCenterPosition(animate);
            } else {
                item.onNonCenterPosition(animate);
            }
        }
    }

    private class SetScrollVerticallyProperty extends Property<WearableListView, Integer> {
        public SetScrollVerticallyProperty() {
            super(Integer.class, "scrollVertically");
        }

        @Override
        public Integer get(WearableListView wearableListView) {
            return wearableListView.mLastScrollChange;
        }

        @Override
        public void set(WearableListView wearableListView, Integer value) {
            wearableListView.setScrollVertically(value);
        }
    }
}
