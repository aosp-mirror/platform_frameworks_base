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

import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.wm.shell.R;
import com.android.wm.shell.bubbles.Bubble;
import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.BubbleOverflowContainerView;
import com.android.wm.shell.bubbles.BubbleTaskViewHelper;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.taskview.TaskView;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Expanded view of a bubble when it's part of the bubble bar.
 *
 * {@link BubbleController#isShowingAsBubbleBar()}
 */
public class BubbleBarExpandedView extends FrameLayout implements BubbleTaskViewHelper.Listener {

    private static final String TAG = BubbleBarExpandedView.class.getSimpleName();
    private static final int INVALID_TASK_ID = -1;

    private BubbleController mController;
    private boolean mIsOverflow;
    private BubbleTaskViewHelper mBubbleTaskViewHelper;
    private BubbleBarMenuViewController mMenuViewController;
    private @Nullable Supplier<Rect> mLayerBoundsSupplier;
    private @Nullable Consumer<String> mUnBubbleConversationCallback;

    private BubbleBarHandleView mHandleView = new BubbleBarHandleView(getContext());
    private @Nullable TaskView mTaskView;
    private @Nullable BubbleOverflowContainerView mOverflowView;

    private int mHandleHeight;
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
        mHandleHeight = context.getResources().getDimensionPixelSize(
                R.dimen.bubble_bar_expanded_view_handle_size);
        addView(mHandleView);
        applyThemeAttrs();
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCornerRadius);
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Hide manage menu when view disappears
        mMenuViewController.hideMenu(false /* animated */);
    }

    /** Set the BubbleController on the view, must be called before doing anything else. */
    public void initialize(BubbleController controller, boolean isOverflow) {
        mController = controller;
        mIsOverflow = isOverflow;

        if (mIsOverflow) {
            mOverflowView = (BubbleOverflowContainerView) LayoutInflater.from(getContext()).inflate(
                    R.layout.bubble_overflow_container, null /* root */);
            mOverflowView.setBubbleController(mController);
            addView(mOverflowView);
        } else {

            mBubbleTaskViewHelper = new BubbleTaskViewHelper(mContext, mController,
                    /* listener= */ this,
                    /* viewParent= */ this);
            mTaskView = mBubbleTaskViewHelper.getTaskView();
            addView(mTaskView);
            mTaskView.setEnableSurfaceClipping(true);
            mTaskView.setCornerRadius(mCornerRadius);
        }
        mMenuViewController = new BubbleBarMenuViewController(mContext, this);
        mMenuViewController.setListener(new BubbleBarMenuViewController.Listener() {
            @Override
            public void onMenuVisibilityChanged(boolean visible) {
                if (mTaskView == null || mLayerBoundsSupplier == null) return;
                // Updates the obscured touchable region for the task surface.
                mTaskView.setObscuredTouchRect(visible ? mLayerBoundsSupplier.get() : null);
            }

            @Override
            public void onUnBubbleConversation(Bubble bubble) {
                if (mUnBubbleConversationCallback != null) {
                    mUnBubbleConversationCallback.accept(bubble.getKey());
                }
            }

            @Override
            public void onOpenAppSettings(Bubble bubble) {
                mController.collapseStack();
                mContext.startActivityAsUser(bubble.getSettingsIntent(mContext), bubble.getUser());
            }

            @Override
            public void onDismissBubble(Bubble bubble) {
                mController.dismissBubble(bubble, Bubbles.DISMISS_USER_REMOVED);
            }
        });
        mHandleView.setOnClickListener(view -> {
            mMenuViewController.showMenu(true /* animated */);
        });
    }

    // TODO (b/275087636): call this when theme/config changes
    /** Updates the view based on the current theme. */
    public void applyThemeAttrs() {
        boolean supportsRoundedCorners = ScreenDecorationsUtils.supportsRoundedCornersOnWindows(
                mContext.getResources());
        final TypedArray ta = mContext.obtainStyledAttributes(new int[]{
                android.R.attr.dialogCornerRadius,
                android.R.attr.colorBackgroundFloating});
        mCornerRadius = supportsRoundedCorners ? ta.getDimensionPixelSize(0, 0) : 0;
        mCornerRadius = mCornerRadius / 2f;
        mBackgroundColor = ta.getColor(1, Color.WHITE);

        ta.recycle();

        mHandleHeight = getResources().getDimensionPixelSize(
                R.dimen.bubble_bar_expanded_view_handle_size);

        if (mTaskView != null) {
            mTaskView.setCornerRadius(mCornerRadius);
            updateHandleAndBackgroundColor(true /* animated */);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int menuViewHeight = Math.min(mHandleHeight, height);
        measureChild(mHandleView, widthMeasureSpec, MeasureSpec.makeMeasureSpec(menuViewHeight,
                MeasureSpec.getMode(heightMeasureSpec)));

        if (mTaskView != null) {
            int taskViewHeight = height - menuViewHeight;
            measureChild(mTaskView, widthMeasureSpec, MeasureSpec.makeMeasureSpec(taskViewHeight,
                    MeasureSpec.getMode(heightMeasureSpec)));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // Drag handle above
        final int dragHandleBottom = t + mHandleView.getMeasuredHeight();
        mHandleView.layout(l, t, r, dragHandleBottom);
        if (mTaskView != null) {
            mTaskView.layout(l, dragHandleBottom, r,
                    dragHandleBottom + mTaskView.getMeasuredHeight());
        }
    }

    @Override
    public void onTaskCreated() {
        setContentVisibility(true);
        updateHandleAndBackgroundColor(false /* animated */);
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
        mMenuViewController.hideMenu(false /* animated */);
    }

    /** Updates the bubble shown in the expanded view. */
    public void update(Bubble bubble) {
        mBubbleTaskViewHelper.update(bubble);
        mMenuViewController.updateMenu(bubble);
    }

    /** The task id of the activity shown in the task view, if it exists. */
    public int getTaskId() {
        return mBubbleTaskViewHelper != null ? mBubbleTaskViewHelper.getTaskId() : INVALID_TASK_ID;
    }

    /** Sets layer bounds supplier used for obscured touchable region of task view */
    void setLayerBoundsSupplier(@Nullable Supplier<Rect> supplier) {
        mLayerBoundsSupplier = supplier;
    }

    /** Sets the function to call to un-bubble the given conversation. */
    public void setUnBubbleConversationCallback(
            @Nullable Consumer<String> unBubbleConversationCallback) {
        mUnBubbleConversationCallback = unBubbleConversationCallback;
    }

    /**
     * Call when the location or size of the view has changed to update TaskView.
     */
    public void updateLocation() {
        if (mTaskView != null) {
            mTaskView.onLocationChanged();
        }
    }

    /** Shows the expanded view for the overflow if it exists. */
    void maybeShowOverflow() {
        if (mOverflowView != null) {
            // post this to the looper so that the view has a chance to be laid out before it can
            // calculate row and column sizes correctly.
            post(() -> mOverflowView.show());
        }
    }

    /** Sets the alpha of the task view. */
    public void setContentVisibility(boolean visible) {
        mIsContentVisible = visible;

        if (mTaskView == null) return;

        if (!mIsAnimating) {
            mTaskView.setAlpha(visible ? 1f : 0f);
        }
    }

    /**
     * Updates the background color to match with task view status/bg color, and sets handle color
     * to contrast with the background
     */
    private void updateHandleAndBackgroundColor(boolean animated) {
        if (mTaskView == null) return;
        final int color = getTaskViewColor();
        final boolean isRegionDark = Color.luminance(color) <= 0.5;
        mHandleView.updateHandleColor(isRegionDark, animated);
        setBackgroundColor(color);
    }

    /**
     * Retrieves task view status/nav bar color or background if available
     *
     * TODO (b/283075226): Update with color sampling when
     *                     RegionSamplingHelper or alternative is available
     */
    private @ColorInt int getTaskViewColor() {
        if (mTaskView == null || mTaskView.getTaskInfo() == null) return mBackgroundColor;
        ActivityManager.TaskDescription taskDescription = mTaskView.getTaskInfo().taskDescription;
        if (taskDescription.getStatusBarColor() != Color.TRANSPARENT) {
            return taskDescription.getStatusBarColor();
        } else if (taskDescription.getBackgroundColor() != Color.TRANSPARENT) {
            return taskDescription.getBackgroundColor();
        } else {
            return mBackgroundColor;
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
