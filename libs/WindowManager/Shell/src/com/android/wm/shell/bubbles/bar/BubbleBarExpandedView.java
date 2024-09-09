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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.wm.shell.R;
import com.android.wm.shell.bubbles.Bubble;
import com.android.wm.shell.bubbles.BubbleExpandedViewManager;
import com.android.wm.shell.bubbles.BubbleOverflowContainerView;
import com.android.wm.shell.bubbles.BubblePositioner;
import com.android.wm.shell.bubbles.BubbleTaskView;
import com.android.wm.shell.bubbles.BubbleTaskViewHelper;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.bubbles.RegionSamplingProvider;
import com.android.wm.shell.shared.bubbles.BubbleBarLocation;
import com.android.wm.shell.shared.handles.RegionSamplingHelper;
import com.android.wm.shell.taskview.TaskView;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Expanded view of a bubble when it's part of the bubble bar. */
public class BubbleBarExpandedView extends FrameLayout implements BubbleTaskViewHelper.Listener {
    /**
     * The expanded view listener notifying the {@link BubbleBarLayerView} about the internal
     * actions and events
     */
    public interface Listener {
        /** Called when the task view task is first created. */
        void onTaskCreated();
        /** Called when expanded view needs to un-bubble the given conversation */
        void onUnBubbleConversation(String bubbleKey);
        /** Called when expanded view task view back button pressed */
        void onBackPressed();
    }

    /**
     * A property wrapper around corner radius for the expanded view, handled by
     * {@link #setCornerRadius(float)} and {@link #getCornerRadius()} methods.
     */
    public static final FloatProperty<BubbleBarExpandedView> CORNER_RADIUS = new FloatProperty<>(
            "cornerRadius") {
        @Override
        public void setValue(BubbleBarExpandedView bbev, float radius) {
            bbev.setCornerRadius(radius);
        }

        @Override
        public Float get(BubbleBarExpandedView bbev) {
            return bbev.getCornerRadius();
        }
    };

    private static final String TAG = BubbleBarExpandedView.class.getSimpleName();
    private static final int INVALID_TASK_ID = -1;

    private Bubble mBubble;
    private BubbleExpandedViewManager mManager;
    private BubblePositioner mPositioner;
    private boolean mIsOverflow;
    private BubbleTaskViewHelper mBubbleTaskViewHelper;
    private BubbleBarMenuViewController mMenuViewController;
    @Nullable
    private Supplier<Rect> mLayerBoundsSupplier;
    @Nullable
    private Listener mListener;

    private BubbleBarHandleView mHandleView;
    @Nullable
    private TaskView mTaskView;
    @Nullable
    private BubbleOverflowContainerView mOverflowView;

    /**
     * The handle shown in the caption area is tinted based on the background color of the area.
     * This can vary so we sample the caption region and update the handle color based on that.
     * If we're showing the overflow, the helper and executors will be null.
     */
    @Nullable
    private RegionSamplingHelper mRegionSamplingHelper;
    @Nullable
    private RegionSamplingProvider mRegionSamplingProvider;
    @Nullable
    private Executor mMainExecutor;
    @Nullable
    private Executor mBackgroundExecutor;
    private final Rect mSampleRect = new Rect();
    private final int[] mLoc = new int[2];

    /** Height of the caption inset at the top of the TaskView */
    private int mCaptionHeight;
    /** Corner radius used when view is resting */
    private float mRestingCornerRadius = 0f;
    /** Corner radius applied while dragging */
    private float mDraggedCornerRadius = 0f;
    /** Current corner radius */
    private float mCurrentCornerRadius = 0f;

    /**
     * Whether we want the {@code TaskView}'s content to be visible (alpha = 1f). If
     * {@link #mIsAnimating} is true, this may not reflect the {@code TaskView}'s actual alpha
     * value until the animation ends.
     */
    private boolean mIsContentVisible = false;
    private boolean mIsAnimating;
    private boolean mIsDragging;

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
        mCaptionHeight = context.getResources().getDimensionPixelSize(
                R.dimen.bubble_bar_expanded_view_caption_height);
        mHandleView = findViewById(R.id.bubble_bar_handle_view);
        applyThemeAttrs();
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCurrentCornerRadius);
            }
        });
        // Set a touch sink to ensure that clicks on the caption area do not propagate to the parent
        setOnTouchListener((v, event) -> true);
    }

    /** Initializes the view, must be called before doing anything else. */
    public void initialize(BubbleExpandedViewManager expandedViewManager,
            BubblePositioner positioner,
            boolean isOverflow,
            @Nullable BubbleTaskView bubbleTaskView,
            @Nullable Executor mainExecutor,
            @Nullable Executor backgroundExecutor,
            @Nullable RegionSamplingProvider regionSamplingProvider) {
        mManager = expandedViewManager;
        mPositioner = positioner;
        mIsOverflow = isOverflow;
        mMainExecutor = mainExecutor;
        mBackgroundExecutor = backgroundExecutor;
        mRegionSamplingProvider = regionSamplingProvider;

        if (mIsOverflow) {
            mOverflowView = (BubbleOverflowContainerView) LayoutInflater.from(getContext()).inflate(
                    R.layout.bubble_overflow_container, null /* root */);
            mOverflowView.initialize(expandedViewManager, positioner);
            addView(mOverflowView);
            // Don't show handle for overflow
            mHandleView.setVisibility(View.GONE);
        } else {
            mTaskView = bubbleTaskView.getTaskView();
            mBubbleTaskViewHelper = new BubbleTaskViewHelper(mContext, expandedViewManager,
                    /* listener= */ this, bubbleTaskView,
                    /* viewParent= */ this);
            if (mTaskView.getParent() != null) {
                ((ViewGroup) mTaskView.getParent()).removeView(mTaskView);
            }
            FrameLayout.LayoutParams lp =
                    new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
            addView(mTaskView, lp);
            mTaskView.setEnableSurfaceClipping(true);
            mTaskView.setCornerRadius(mCurrentCornerRadius);
            mTaskView.setVisibility(VISIBLE);
            mTaskView.setCaptionInsets(Insets.of(0, mCaptionHeight, 0, 0));

            // Handle view needs to draw on top of task view.
            bringChildToFront(mHandleView);

            mHandleView.setAccessibilityDelegate(new HandleViewAccessibilityDelegate());
        }
        mMenuViewController = new BubbleBarMenuViewController(mContext, this);
        mMenuViewController.setListener(new BubbleBarMenuViewController.Listener() {
            @Override
            public void onMenuVisibilityChanged(boolean visible) {
                setObscured(visible);
                if (visible) {
                    mHandleView.setFocusable(false);
                    mHandleView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                } else {
                    mHandleView.setFocusable(true);
                    mHandleView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                }
            }

            @Override
            public void onUnBubbleConversation(Bubble bubble) {
                if (mListener != null) {
                    mListener.onUnBubbleConversation(bubble.getKey());
                }
            }

            @Override
            public void onOpenAppSettings(Bubble bubble) {
                mManager.collapseStack();
                mContext.startActivityAsUser(bubble.getSettingsIntent(mContext), bubble.getUser());
            }

            @Override
            public void onDismissBubble(Bubble bubble) {
                mManager.dismissBubble(bubble, Bubbles.DISMISS_USER_GESTURE);
            }

            @Override
            public void onMoveToFullscreen(Bubble bubble) {
                if (mTaskView != null) {
                    mTaskView.moveToFullscreen();
                }
            }
        });
        mHandleView.setOnClickListener(view -> {
            mMenuViewController.showMenu(true /* animated */);
        });
    }

    public BubbleBarHandleView getHandleView() {
        return mHandleView;
    }

    /** Updates the view based on the current theme. */
    public void applyThemeAttrs() {
        mCaptionHeight = getResources().getDimensionPixelSize(
                R.dimen.bubble_bar_expanded_view_caption_height);
        mRestingCornerRadius = getResources().getDimensionPixelSize(
                R.dimen.bubble_bar_expanded_view_corner_radius);
        mDraggedCornerRadius = getResources().getDimensionPixelSize(
                R.dimen.bubble_bar_expanded_view_corner_radius_dragged);

        mCurrentCornerRadius = mRestingCornerRadius;

        if (mTaskView != null) {
            mTaskView.setCornerRadius(mCurrentCornerRadius);
            mTaskView.setCaptionInsets(Insets.of(0, mCaptionHeight, 0, 0));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Hide manage menu when view disappears
        mMenuViewController.hideMenu(false /* animated */);
        if (mRegionSamplingHelper != null) {
            mRegionSamplingHelper.stopAndDestroy();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        recreateRegionSamplingHelper();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mTaskView != null) {
            int height = MeasureSpec.getSize(heightMeasureSpec);
            measureChild(mTaskView, widthMeasureSpec, MeasureSpec.makeMeasureSpec(height,
                    MeasureSpec.getMode(heightMeasureSpec)));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (mTaskView != null) {
            mTaskView.layout(l, t, r, t + mTaskView.getMeasuredHeight());
        }
    }

    @Override
    public void onTaskCreated() {
        setContentVisibility(true);
        if (mListener != null) {
            mListener.onTaskCreated();
        }
    }

    @Override
    public void onContentVisibilityChanged(boolean visible) {
        setContentVisibility(visible);
    }

    @Override
    public void onTaskRemovalStarted() {
        if (mRegionSamplingHelper != null) {
            mRegionSamplingHelper.stopAndDestroy();
        }
    }

    @Override
    public void onBackPressed() {
        if (mListener == null) return;
        mListener.onBackPressed();
    }

    /**
     * Set whether this view is currently being dragged.
     *
     * When dragging, the handle is hidden and content shouldn't be sampled. When dragging has
     * ended we should start again.
     */
    public void setDragging(boolean isDragging) {
        if (isDragging != mIsDragging) {
            mIsDragging = isDragging;
            updateSamplingState();
        }
    }

    /** Returns whether region sampling should be enabled, i.e. if task view content is visible. */
    private boolean shouldSampleRegion() {
        return mTaskView != null
                && mTaskView.getTaskInfo() != null
                && !mIsDragging
                && !mIsAnimating
                && mIsContentVisible;
    }

    /**
     * Handles starting or stopping the region sampling helper based on
     * {@link #shouldSampleRegion()}.
     */
    private void updateSamplingState() {
        if (mRegionSamplingHelper == null) return;
        boolean shouldSample = shouldSampleRegion();
        if (shouldSample) {
            mRegionSamplingHelper.start(getCaptionSampleRect());
        } else {
            mRegionSamplingHelper.stop();
        }
    }

    /** Returns the current area of the caption bar, in screen coordinates. */
    Rect getCaptionSampleRect() {
        if (mTaskView == null) return null;
        mTaskView.getLocationOnScreen(mLoc);
        mSampleRect.set(mLoc[0], mLoc[1],
                mLoc[0] + mTaskView.getWidth(),
                mLoc[1] + mCaptionHeight);
        return mSampleRect;
    }

    @VisibleForTesting
    @Nullable
    public RegionSamplingHelper getRegionSamplingHelper() {
        return mRegionSamplingHelper;
    }

    /** Cleans up the expanded view, should be called when the bubble is no longer active. */
    public void cleanUpExpandedState() {
        mMenuViewController.hideMenu(false /* animated */);
    }

    /**
     * Hides the current modal menu if it is visible
     * @return {@code true} if menu was visible and is hidden
     */
    public boolean hideMenuIfVisible() {
        if (mMenuViewController.isMenuVisible()) {
            mMenuViewController.hideMenu(true /* animated */);
            return true;
        }
        return false;
    }

    /**
     * Hides the IME if it is visible
     * @return {@code true} if IME was visible
     */
    public boolean hideImeIfVisible() {
        if (mPositioner.isImeVisible()) {
            mManager.hideCurrentInputMethod();
            return true;
        }
        return false;
    }

    /** Updates the bubble shown in the expanded view. */
    public void update(Bubble bubble) {
        mBubble = bubble;
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

    /** Sets expanded view listener */
    void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    /** Sets whether the view is obscured by some modal view */
    void setObscured(boolean obscured) {
        if (mTaskView == null || mLayerBoundsSupplier == null) return;
        // Updates the obscured touchable region for the task surface.
        mTaskView.setObscuredTouchRect(obscured ? mLayerBoundsSupplier.get() : null);
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
            if (mRegionSamplingHelper != null) {
                mRegionSamplingHelper.setWindowVisible(visible);
            }
            updateSamplingState();
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
        if (mIsAnimating) {
            // Stop sampling while animating -- when animating is done setContentVisibility will
            // re-trigger sampling if we're visible.
            updateSamplingState();
        }
        // If we're done animating, apply the correct visibility.
        if (!animating) {
            setContentVisibility(mIsContentVisible);
        }
    }

    /**
     * Check whether the view is animating
     */
    public boolean isAnimating() {
        return mIsAnimating;
    }

    /** @return corner radius that should be applied while view is in rest */
    public float getRestingCornerRadius() {
        return mRestingCornerRadius;
    }

    /** @return corner radius that should be applied while view is being dragged */
    public float getDraggedCornerRadius() {
        return mDraggedCornerRadius;
    }

    /** @return current corner radius */
    public float getCornerRadius() {
        return mCurrentCornerRadius;
    }

    /** Update corner radius */
    public void setCornerRadius(float cornerRadius) {
        if (mCurrentCornerRadius != cornerRadius) {
            mCurrentCornerRadius = cornerRadius;
            if (mTaskView != null) {
                mTaskView.setCornerRadius(cornerRadius);
            }
            invalidateOutline();
        }
    }

    private void recreateRegionSamplingHelper() {
        if (mRegionSamplingHelper != null) {
            mRegionSamplingHelper.stopAndDestroy();
        }
        if (mMainExecutor == null || mBackgroundExecutor == null
                || mRegionSamplingProvider == null) {
            // Null when it's the overflow / don't need sampling then.
            return;
        }
        mRegionSamplingHelper = mRegionSamplingProvider.createHelper(this,
                new RegionSamplingHelper.SamplingCallback() {
                    @Override
                    public void onRegionDarknessChanged(boolean isRegionDark) {
                        if (mHandleView != null) {
                            mHandleView.updateHandleColor(isRegionDark,
                                    true /* animated */);
                        }
                    }

                    @Override
                    public Rect getSampledRegion(View sampledView) {
                        return getCaptionSampleRect();
                    }

                    @Override
                    public boolean isSamplingEnabled() {
                        return shouldSampleRegion();
                    }
                }, mMainExecutor, mBackgroundExecutor);
    }

    private class HandleViewAccessibilityDelegate extends AccessibilityDelegate {
        @Override
        public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                @NonNull AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK, getResources().getString(
                    R.string.bubble_accessibility_action_expand_menu)));
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
            if (mPositioner.isBubbleBarOnLeft()) {
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.action_move_bubble_bar_right, getResources().getString(
                        R.string.bubble_accessibility_action_move_bar_right)));
            } else {
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.action_move_bubble_bar_left, getResources().getString(
                        R.string.bubble_accessibility_action_move_bar_left)));
            }
        }

        @Override
        public boolean performAccessibilityAction(@NonNull View host, int action,
                @Nullable Bundle args) {
            if (super.performAccessibilityAction(host, action, args)) {
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_COLLAPSE) {
                mManager.collapseStack();
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_DISMISS) {
                mManager.dismissBubble(mBubble, Bubbles.DISMISS_USER_GESTURE);
                return true;
            }
            if (action == R.id.action_move_bubble_bar_left) {
                mManager.updateBubbleBarLocation(BubbleBarLocation.LEFT);
                return true;
            }
            if (action == R.id.action_move_bubble_bar_right) {
                mManager.updateBubbleBarLocation(BubbleBarLocation.RIGHT);
                return true;
            }
            return false;
        }
    }
}
