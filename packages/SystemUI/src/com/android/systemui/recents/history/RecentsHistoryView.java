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

package com.android.systemui.recents.history;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.ClearHistoryEvent;
import com.android.systemui.recents.events.activity.PackagesChangedEvent;
import com.android.systemui.recents.events.ui.ResetBackgroundScrimEvent;
import com.android.systemui.recents.events.ui.UpdateBackgroundScrimEvent;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.views.AnimateableViewBounds;
import com.android.systemui.recents.views.TaskStackLayoutAlgorithm;

/**
 * A list of the recent tasks that are not in the stack.
 */
public class RecentsHistoryView extends LinearLayout
        implements ValueAnimator.AnimatorUpdateListener {

    private static final float TRANSLATION_Y_PCT = 0.25f;
    private static final float BG_SCRIM_ALPHA = 0.625f;

    private RecyclerView mRecyclerView;
    private RecentsHistoryAdapter mAdapter;
    private RecentsHistoryItemTouchCallbacks mItemTouchHandler;
    private AnimateableViewBounds mViewBounds;
    private TaskStackLayoutAlgorithm mLayoutAlgorithm;
    private boolean mIsVisible;
    private Rect mSystemInsets = new Rect();
    private int mHeaderHeight;

    private int mHistoryTransitionDuration;

    public RecentsHistoryView(Context context) {
        super(context);
    }

    public RecentsHistoryView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsHistoryView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RecentsHistoryView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        Resources res = context.getResources();
        mAdapter = new RecentsHistoryAdapter(context);
        mItemTouchHandler = new RecentsHistoryItemTouchCallbacks(context, mAdapter);
        mHistoryTransitionDuration = res.getInteger(R.integer.recents_history_transition_duration);
        mViewBounds = new AnimateableViewBounds(this, 0);
        setOutlineProvider(mViewBounds);
    }

    /**
     * Updates this history view with the recent tasks, and then shows it.
     */
    public void show(TaskStack stack, int stackHeight, View clearAllButton) {
        setVisibility(View.VISIBLE);
        setAlpha(0f);
        setTranslationY(-stackHeight * TRANSLATION_Y_PCT);
        animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(mHistoryTransitionDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setUpdateListener(this)
                .start();
        clearAllButton.setVisibility(View.VISIBLE);
        clearAllButton.setAlpha(0f);
        clearAllButton.animate()
                .alpha(1f)
                .setDuration(mHistoryTransitionDuration)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .withLayer()
                .start();
        mAdapter.updateTasks(getContext(), stack);
        mIsVisible = true;
        EventBus.getDefault().send(new UpdateBackgroundScrimEvent(BG_SCRIM_ALPHA));

        MetricsLogger.visible(mRecyclerView.getContext(), MetricsEvent.OVERVIEW_HISTORY);
    }

    /**
     * Hides this history view.
     */
    public void hide(boolean animate, int stackHeight, final View clearAllButton) {
        if (animate) {
            animate()
                    .alpha(0f)
                    .translationY(-stackHeight * TRANSLATION_Y_PCT)
                    .setDuration(mHistoryTransitionDuration)
                    .setInterpolator(Interpolators.FAST_OUT_LINEAR_IN)
                    .setUpdateListener(this)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            setVisibility(View.INVISIBLE);
                        }
                    })
                    .start();
            clearAllButton.animate()
                    .alpha(0f)
                    .translationY(0f)
                    .setDuration(mHistoryTransitionDuration)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            clearAllButton.setVisibility(View.INVISIBLE);
                        }
                    })
                    .withLayer()
                    .start();
        } else {
            setAlpha(0f);
            setVisibility(View.INVISIBLE);
            clearAllButton.setAlpha(0f);
            clearAllButton.setVisibility(View.INVISIBLE);
        }
        mIsVisible = false;
        EventBus.getDefault().send(new ResetBackgroundScrimEvent());

        MetricsLogger.hidden(mRecyclerView.getContext(), MetricsEvent.OVERVIEW_HISTORY);
    }

    /**
     * Updates the system insets of this history view to the provided values.
     */
    public void setSystemInsets(Rect systemInsets) {
        mSystemInsets.set(systemInsets);
        requestLayout();
    }

    /**
     * Updates the the stack layout and header height to account for the history button bar.
     */
    public void update(TaskStackLayoutAlgorithm layoutAlgorithm, int headerHeight) {
        mLayoutAlgorithm = layoutAlgorithm;
        mHeaderHeight = headerHeight;
        requestLayout();
    }

    /**
     * Returns whether this view is visible.
     */
    public boolean isVisible() {
        return mIsVisible;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mRecyclerView = (RecyclerView) findViewById(R.id.list);
        mRecyclerView.setAdapter(mAdapter);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mRecyclerView.getItemAnimator().setRemoveDuration(100);
        ItemTouchHelper touchHelper = new ItemTouchHelper(mItemTouchHandler);
        touchHelper.attachToRecyclerView(mRecyclerView);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        // Pad the view to align the history with the stack layout
        Rect taskStackBounds = new Rect();
        mLayoutAlgorithm.getTaskStackBounds(new Rect(0, 0, width, height),
                mSystemInsets.top, mSystemInsets.right, taskStackBounds);
        int stackTopPadding = TaskStackLayoutAlgorithm.getDimensionForDevice(
                getResources(),
                R.dimen.recents_layout_top_margin_phone,
                R.dimen.recents_layout_top_margin_tablet,
                R.dimen.recents_layout_top_margin_tablet_xlarge
        );
        mRecyclerView.setPadding(taskStackBounds.left,
                taskStackBounds.top + stackTopPadding + mHeaderHeight,
                taskStackBounds.right, mSystemInsets.bottom);

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onAttachedToWindow() {
        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        setSystemInsets(insets.getSystemWindowInsets());
        return insets;
    }

    @Override
    public void onAnimationUpdate(ValueAnimator animation) {
        // Clip the top of the view by the header bar height
        int top = Math.max(0, (int) -getTranslationY()) + mSystemInsets.top + mHeaderHeight;
        mViewBounds.setClipTop(top);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /**** EventBus Events ****/

    public final void onBusEvent(PackagesChangedEvent event) {
        mAdapter.removeTasks(event.packageName, event.userId);
    }

    public final void onBusEvent(ClearHistoryEvent event) {
        mAdapter.removeAllTasks();
    }
}
