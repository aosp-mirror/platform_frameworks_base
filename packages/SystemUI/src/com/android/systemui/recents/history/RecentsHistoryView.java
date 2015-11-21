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

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.model.TaskStack;

/**
 * A list of the recent tasks that are not in the stack.
 */
public class RecentsHistoryView extends LinearLayout {

    private static final String TAG = "RecentsHistoryView";
    private static final boolean DEBUG = false;

    private RecyclerView mRecyclerView;
    private boolean mIsVisible;

    private Interpolator mFastOutSlowInInterpolator;
    private Interpolator mFastOutLinearInInterpolator;
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
        mHistoryTransitionDuration = res.getInteger(R.integer.recents_history_transition_duration);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
        mFastOutLinearInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_linear_in);
    }

    /**
     * Updates this history view with the recent tasks, and then shows it.
     */
    public void show(TaskStack stack) {
        setVisibility(View.VISIBLE);
        setAlpha(0f);
        animate()
                .alpha(1f)
                .setDuration(mHistoryTransitionDuration)
                .setInterpolator(mFastOutSlowInInterpolator)
                .withLayer()
                .start();

        mRecyclerView.setAdapter(new RecentsHistoryAdapter(getContext(), stack));
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mIsVisible = true;
    }

    /**
     * Hides this history view.
     */
    public void hide(boolean animate, final ReferenceCountedTrigger postAnimationTrigger) {
        if (animate) {
            animate()
                    .alpha(0f)
                    .setDuration(mHistoryTransitionDuration)
                    .setInterpolator(mFastOutLinearInInterpolator)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            setVisibility(View.INVISIBLE);
                            if (postAnimationTrigger != null) {
                                postAnimationTrigger.decrement();
                            }
                        }
                    })
                    .withLayer()
                    .start();
            if (postAnimationTrigger != null) {
                postAnimationTrigger.increment();
            }
        } else {
            setAlpha(0f);
            setVisibility(View.INVISIBLE);
        }
        mIsVisible = false;
    }

    /**
     * Updates the system insets of this history view to the provided values.
     */
    public void setSystemInsets(Rect systemInsets) {
        setPadding(systemInsets.left, systemInsets.top, systemInsets.right, systemInsets.bottom);
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
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        setSystemInsets(insets.getSystemWindowInsets());
        return insets;
    }
}
