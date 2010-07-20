/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.widget;

import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

/**
 * Base class for a {@link AdapterView} that will perform animations
 * when switching between its views.
 *
 * @attr ref android.R.styleable#AdapterViewAnimator_inAnimation
 * @attr ref android.R.styleable#AdapterViewAnimator_outAnimation
 * @attr ref android.R.styleable#AdapterViewAnimator_animateFirstView
 */
public class AdapterViewAnimator extends AdapterView<Adapter> implements RemoteViewsAdapter.RemoteAdapterConnectionCallback{
    private static final String TAG = "RemoteViewAnimator";

    int mWhichChild = 0;
    boolean mFirstTime = true;
    boolean mAnimateFirstTime = true;

    AdapterDataSetObserver mDataSetObserver;

    View mPreviousView;
    View mCurrentView;

    Animation mInAnimation;
    Animation mOutAnimation;
    Adapter mAdapter;
    RemoteViewsAdapter mRemoteViewsAdapter;
    private Handler mMainQueue;

    public AdapterViewAnimator(Context context) {
        super(context);
        initViewAnimator(context, null);
    }

    public AdapterViewAnimator(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.ViewAnimator);
        int resource = a.getResourceId(com.android.internal.R.styleable.ViewAnimator_inAnimation, 0);
        if (resource > 0) {
            setInAnimation(context, resource);
        }

        resource = a.getResourceId(com.android.internal.R.styleable.ViewAnimator_outAnimation, 0);
        if (resource > 0) {
            setOutAnimation(context, resource);
        }

        boolean flag = a.getBoolean(com.android.internal.R.styleable.ViewAnimator_animateFirstView, true);
        setAnimateFirstView(flag);

        a.recycle();

        initViewAnimator(context, attrs);
    }

    /**
     * Initialize this {@link AdapterViewAnimator}
     */
    private void initViewAnimator(Context context, AttributeSet attrs) {
        mMainQueue = new Handler(Looper.myLooper());
    }

    /**
     * Sets which child view will be displayed.
     *
     * @param whichChild the index of the child view to display
     */
    public void setDisplayedChild(int whichChild) {
        if (mAdapter != null) {
            mWhichChild = whichChild;
            if (whichChild >= mAdapter.getCount()) {
                mWhichChild = 0;
            } else if (whichChild < 0) {
                mWhichChild = mAdapter.getCount() - 1;
            }

            boolean hasFocus = getFocusedChild() != null;
            // This will clear old focus if we had it
            showOnly(mWhichChild);
            if (hasFocus) {
                // Try to retake focus if we had it
                requestFocus(FOCUS_FORWARD);
            }
        }
    }

    /**
     * Return default inAnimation. To be overriden by subclasses.
     */
    public Animation getDefaultInAnimation() {
        return null;
    }

    /**
     * Return default outAnimation. To be overriden by subclasses.
     */
    public Animation getDefaultOutAnimation() {
        return null;
    }

    /**
     * Returns the index of the currently displayed child view.
     */
    public int getDisplayedChild() {
        return mWhichChild;
    }

    /**
     * Manually shows the next child.
     */
    public void showNext() {
        setDisplayedChild(mWhichChild + 1);
    }

    /**
     * Manually shows the previous child.
     */
    public void showPrevious() {
        setDisplayedChild(mWhichChild - 1);
    }

    /**
     * Shows only the specified child. The other displays Views exit the screen,
     * optionally with the with the {@link #getOutAnimation() out animation} and
     * the specified child enters the screen, optionally with the
     * {@link #getInAnimation() in animation}.
     *
     * @param childIndex The index of the child to be shown.
     * @param animate Whether or not to use the in and out animations, defaults
     *            to true.
     */
    void showOnly(int childIndex, boolean animate) {
        showOnly(childIndex, animate, false);
    }

    private LayoutParams makeLayoutParams() {
        int width = mMeasuredWidth - mPaddingLeft - mPaddingRight;
        int height = mMeasuredHeight - mPaddingTop - mPaddingBottom;
        return new LayoutParams(width, height);
    }

    protected void showOnly(int childIndex, boolean animate, boolean onLayout) {
        if (mAdapter != null) {
            // The previous view should be removed from the ViewGroup
            if (mPreviousView != null) {
                mPreviousView.clearAnimation();

                // TODO: this is where we would store the the view for
                // recycling
                removeViewInLayout(mPreviousView);
            }

            // If the current view is still being animated, we should
            // force the animation to end
            if (mCurrentView != null) {
                mCurrentView.clearAnimation();
            }

            // load the new mCurrentView from our adapter
            mPreviousView = mCurrentView;
            mCurrentView = mAdapter.getView(childIndex, null, this);
            if (mPreviousView != mCurrentView) {
                addViewInLayout(mCurrentView, 0, makeLayoutParams(), true);
                mCurrentView.bringToFront();
            }



            // Animate as necessary
            if (mPreviousView != null && mPreviousView != mCurrentView) {
                if (animate && mOutAnimation != null) {
                    mPreviousView.startAnimation(mOutAnimation);
                }
                // This line results in the view becoming invisible *after*
                // the above animation is complete, or, if there is no animation
                // then it becomes invisble immediately
                mPreviousView.setVisibility(View.GONE);
            }

            if (mCurrentView != null && animate && mInAnimation != null) {
                mCurrentView.startAnimation(mInAnimation);
            }

            mFirstTime = false;
            if (!onLayout) {
                requestLayout();
                invalidate();
            } else {
                // If the Adapter tries to layout the current view when we get it using getView above
                // the layout will end up being ignored since we are currently laying out, so
                // we post a delayed requestLayout and invalidate
                mMainQueue.post(new Runnable() {
                    @Override
                    public void run() {
                        mCurrentView.requestLayout();
                        mCurrentView.invalidate();
                    }
                });
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        boolean dataChanged = mDataChanged;
        if (dataChanged) {
            handleDataChanged();

            // if the data changes, mWhichChild might be out of the bounds of the adapter
            // in this case, we reset mWhichChild to the beginning
            if (mWhichChild >= mAdapter.getCount())
                mWhichChild = 0;

            showOnly(mWhichChild, true, true);
        }

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);

            int childRight = mPaddingLeft + child.getMeasuredWidth();
            int childBottom = mPaddingTop + child.getMeasuredHeight();

            child.layout(mPaddingLeft, mPaddingTop, childRight, childBottom);
        }

        mDataChanged = false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int count = getChildCount();

        int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);

        Log.v(TAG, "onMeasure");

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);

            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            lp.width = widthSpecSize - mPaddingLeft - mPaddingRight;
            lp.height = heightSpecSize - mPaddingTop - mPaddingBottom;

            int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(lp.width,
                    MeasureSpec.EXACTLY);
            int childheightMeasureSpec = MeasureSpec.makeMeasureSpec(lp.height,
                    MeasureSpec.EXACTLY);

            child.measure(childWidthMeasureSpec, childheightMeasureSpec);
        }

        setMeasuredDimension(widthSpecSize, heightSpecSize);
    }

    /**
     * Shows only the specified child. The other displays Views exit the screen
     * with the {@link #getOutAnimation() out animation} and the specified child
     * enters the screen with the {@link #getInAnimation() in animation}.
     *
     * @param childIndex The index of the child to be shown.
     */
    void showOnly(int childIndex) {
        final boolean animate = (!mFirstTime || mAnimateFirstTime);
        showOnly(childIndex, animate);
    }

    /**
     * Returns the View corresponding to the currently displayed child.
     *
     * @return The View currently displayed.
     *
     * @see #getDisplayedChild()
     */
    public View getCurrentView() {
        return mCurrentView;
    }

    /**
     * Returns the current animation used to animate a View that enters the screen.
     *
     * @return An Animation or null if none is set.
     *
     * @see #setInAnimation(android.view.animation.Animation)
     * @see #setInAnimation(android.content.Context, int)
     */
    public Animation getInAnimation() {
        return mInAnimation;
    }

    /**
     * Specifies the animation used to animate a View that enters the screen.
     *
     * @param inAnimation The animation started when a View enters the screen.
     *
     * @see #getInAnimation()
     * @see #setInAnimation(android.content.Context, int)
     */
    public void setInAnimation(Animation inAnimation) {
        mInAnimation = inAnimation;
    }

    /**
     * Returns the current animation used to animate a View that exits the screen.
     *
     * @return An Animation or null if none is set.
     *
     * @see #setOutAnimation(android.view.animation.Animation)
     * @see #setOutAnimation(android.content.Context, int)
     */
    public Animation getOutAnimation() {
        return mOutAnimation;
    }

    /**
     * Specifies the animation used to animate a View that exit the screen.
     *
     * @param outAnimation The animation started when a View exit the screen.
     *
     * @see #getOutAnimation()
     * @see #setOutAnimation(android.content.Context, int)
     */
    public void setOutAnimation(Animation outAnimation) {
        mOutAnimation = outAnimation;
    }

    /**
     * Specifies the animation used to animate a View that enters the screen.
     *
     * @param context The application's environment.
     * @param resourceID The resource id of the animation.
     *
     * @see #getInAnimation()
     * @see #setInAnimation(android.view.animation.Animation)
     */
    public void setInAnimation(Context context, int resourceID) {
        setInAnimation(AnimationUtils.loadAnimation(context, resourceID));
    }

    /**
     * Specifies the animation used to animate a View that exit the screen.
     *
     * @param context The application's environment.
     * @param resourceID The resource id of the animation.
     *
     * @see #getOutAnimation()
     * @see #setOutAnimation(android.view.animation.Animation)
     */
    public void setOutAnimation(Context context, int resourceID) {
        setOutAnimation(AnimationUtils.loadAnimation(context, resourceID));
    }

    /**
     * Indicates whether the current View should be animated the first time
     * the ViewAnimation is displayed.
     *
     * @param animate True to animate the current View the first time it is displayed,
     *                false otherwise.
     */
    public void setAnimateFirstView(boolean animate) {
        mAnimateFirstTime = animate;
    }

    @Override
    public int getBaseline() {
        return (getCurrentView() != null) ? getCurrentView().getBaseline() : super.getBaseline();
    }

    @Override
    public Adapter getAdapter() {
        return mAdapter;
    }

    @Override
    public void setAdapter(Adapter adapter) {
        mAdapter = adapter;

        if (mAdapter != null) {
            if (mDataSetObserver != null) {
                mAdapter.unregisterDataSetObserver(mDataSetObserver);
            }

            mDataSetObserver = new AdapterDataSetObserver();
            mAdapter.registerDataSetObserver(mDataSetObserver);
        }
    }

    /**
     * Sets up this AdapterViewAnimator to use a remote views adapter which connects to a RemoteViewsService
     * through the specified intent.
     * @param intent the intent used to identify the RemoteViewsService for the adapter to connect to.
     */
    @android.view.RemotableViewMethod
    public void setRemoteViewsAdapter(Intent intent) {
        mRemoteViewsAdapter = new RemoteViewsAdapter(getContext(), intent, this);
    }

    @Override
    public void setSelection(int position) {
        setDisplayedChild(position);
    }

    @Override
    public View getSelectedView() {
        return mCurrentView;
    }

    /**
     * Called back when the adapter connects to the RemoteViewsService.
     */
    public void onRemoteAdapterConnected() {
        if (mRemoteViewsAdapter != mAdapter) {
            setAdapter(mRemoteViewsAdapter);
        }
    }

    /**
     * Called back when the adapter disconnects from the RemoteViewsService.
     */
    public void onRemoteAdapterDisconnected() {
        if (mRemoteViewsAdapter != mAdapter) {
            mRemoteViewsAdapter = null;
            setAdapter(mRemoteViewsAdapter);
        }
    }
}
