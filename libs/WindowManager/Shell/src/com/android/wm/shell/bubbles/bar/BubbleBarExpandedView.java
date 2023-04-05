/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.bubbles.bar;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.wm.shell.R;
import com.android.wm.shell.bubbles.Bubble;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.BubbleTaskViewHelper;
import com.android.wm.shell.taskview.TaskView;

/**
 * Expanded view of a bubble when it's part of the bubble bar.
 *
 * {@link BubbleController#isShowingAsBubbleBar()}
 */
public class BubbleBarExpandedView extends FrameLayout implements BubbleTaskViewHelper.Listener {

    private static final String TAG = BubbleBarExpandedView.class.getSimpleName();
    private static final int INVALID_TASK_ID = -1;

    private BubbleController mController;
    private BubbleTaskViewHelper mBubbleTaskViewHelper;

    private HandleView mMenuView;
    private TaskView mTaskView;

    private int mMenuHeight;
    private int mBackgroundColor;
    private float mCornerRadius = 0f;

    /**
     * Whether we want the {@code TaskView}'s content to be visible (alpha = 1f). If
     * {@link #mIsAnimating} is true, this may not reflect the {@code TaskView}'s actual alpha
     * value until the animation ends.
     */
    private boolean mIsContentVisible = false;
    private boolean mIsAnimating;

    public BubbleBarExpandedView(Context context) {
        this(context, null);
    }

    public BubbleBarExpandedView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleBarExpandedView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BubbleBarExpandedView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        Context context = getContext();
        setElevation(getResources().getDimensionPixelSize(R.dimen.bubble_elevation));
        mMenuHeight = context.getResources().getDimensionPixelSize(
                R.dimen.bubblebar_expanded_view_menu_size);
        mMenuView = new HandleView(context);
        addView(mMenuView);

        applyThemeAttrs();
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCornerRadius);
            }
        });
    }

    /** Set the BubbleController on the view, must be called before doing anything else. */
    public void initialize(BubbleController controller) {
        mController = controller;
        mBubbleTaskViewHelper = new BubbleTaskViewHelper(mContext, mController,
                /* listener= */ this,
                /* viewParent= */ this);
        mTaskView = mBubbleTaskViewHelper.getTaskView();
        addView(mTaskView);
        mTaskView.setEnableSurfaceClipping(true);
        mTaskView.setCornerRadius(mCornerRadius);
    }

    // TODO (b/275087636): call this when theme/config changes
    void applyThemeAttrs() {
        boolean supportsRoundedCorners = ScreenDecorationsUtils.supportsRoundedCornersOnWindows(
                mContext.getResources());
        final TypedArray ta = mContext.obtainStyledAttributes(new int[] {
                android.R.attr.dialogCornerRadius,
                android.R.attr.colorBackgroundFloating});
        mCornerRadius = supportsRoundedCorners ? ta.getDimensionPixelSize(0, 0) : 0;
        mCornerRadius = mCornerRadius / 2f;
        mBackgroundColor = ta.getColor(1, Color.WHITE);

        ta.recycle();

        mMenuView.setCornerRadius(mCornerRadius);
        mMenuHeight = getResources().getDimensionPixelSize(
                R.dimen.bubblebar_expanded_view_menu_size);

        if (mTaskView != null) {
            mTaskView.setCornerRadius(mCornerRadius);
            mTaskView.setElevation(150);
            updateMenuColor();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        // Add corner radius here so that the menu extends behind the rounded corners of TaskView.
        int menuViewHeight = Math.min((int) (mMenuHeight + mCornerRadius), height);
        measureChild(mMenuView, widthMeasureSpec, MeasureSpec.makeMeasureSpec(menuViewHeight,
                MeasureSpec.getMode(heightMeasureSpec)));

        if (mTaskView != null) {
            int taskViewHeight = height - menuViewHeight;
            measureChild(mTaskView, widthMeasureSpec, MeasureSpec.makeMeasureSpec(taskViewHeight,
                    MeasureSpec.getMode(heightMeasureSpec)));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // Drag handle above
        final int dragHandleBottom = t + mMenuView.getMeasuredHeight();
        mMenuView.layout(l, t, r, dragHandleBottom);
        if (mTaskView != null) {
            // Subtract radius so that the menu extends behind the rounded corners of TaskView.
            mTaskView.layout(l, (int) (dragHandleBottom - mCornerRadius), r,
                    dragHandleBottom + mTaskView.getMeasuredHeight());
        }
    }

    @Override
    public void onTaskCreated() {
        setContentVisibility(true);
        updateMenuColor();
    }

    @Override
    public void onContentVisibilityChanged(boolean visible) {
        setContentVisibility(visible);
    }

    @Override
    public void onBackPressed() {
        mController.collapseStack();
    }

    /** Cleans up task view, should be called when the bubble is no longer active. */
    public void cleanUpExpandedState() {
        if (mBubbleTaskViewHelper != null) {
            if (mTaskView != null) {
                removeView(mTaskView);
            }
            mBubbleTaskViewHelper.cleanUpTaskView();
        }
    }

    /** Updates the bubble shown in this task view. */
    public void update(Bubble bubble) {
        mBubbleTaskViewHelper.update(bubble);
    }

    /** The task id of the activity shown in the task view, if it exists. */
    public int getTaskId() {
        return mBubbleTaskViewHelper != null ? mBubbleTaskViewHelper.getTaskId() : INVALID_TASK_ID;
    }

    /**
     * Call when the location or size of the view has changed to update TaskView.
     */
    public void updateLocation() {
        if (mTaskView == null) return;
        mTaskView.onLocationChanged();
    }

    /** Sets the alpha of the task view. */
    public void setContentVisibility(boolean visible) {
        mIsContentVisible = visible;

        if (mTaskView == null) return;

        if (!mIsAnimating) {
            mTaskView.setAlpha(visible ? 1f : 0f);
        }
    }

    /** Updates the menu bar to be the status bar color specified by the app. */
    private void updateMenuColor() {
        if (mTaskView == null) return;
        ActivityManager.RunningTaskInfo info = mTaskView.getTaskInfo();
        final int taskBgColor = info.taskDescription.getStatusBarColor();
        final int color = Color.valueOf(taskBgColor == -1 ? Color.WHITE : taskBgColor).toArgb();
        if (color != -1) {
            mMenuView.setBackgroundColor(color);
        } else {
            mMenuView.setBackgroundColor(mBackgroundColor);
        }
    }

    /**
     * Sets the alpha of both this view and the task view.
     */
    public void setTaskViewAlpha(float alpha) {
        if (mTaskView != null) {
            mTaskView.setAlpha(alpha);
        }
        setAlpha(alpha);
    }

    /**
     * Sets whether the surface displaying app content should sit on top. This is useful for
     * ordering surfaces during animations. When content is drawn on top of the app (e.g. bubble
     * being dragged out, the manage menu) this is set to false, otherwise it should be true.
     */
    public void setSurfaceZOrderedOnTop(boolean onTop) {
        if (mTaskView == null) {
            return;
        }
        mTaskView.setZOrderedOnTop(onTop, true /* allowDynamicChange */);
    }

    /**
     * Sets whether the view is animating, in this case we won't change the content visibility
     * until the animation is done.
     */
    public void setAnimating(boolean animating) {
        mIsAnimating = animating;
        // If we're done animating, apply the correct visibility.
        if (!animating) {
            setContentVisibility(mIsContentVisible);
        }
    }
}
