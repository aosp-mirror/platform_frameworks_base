/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import android.animation.Animator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.systemui.R;
import com.android.systemui.screenshot.SwipeDismissHandler;

import java.util.function.Consumer;

/**
 * ConstraintLayout that is draggable when touched in a specific region
 */
public class DraggableConstraintLayout extends ConstraintLayout {
    private final SwipeDismissHandler mSwipeDismissHandler;
    private final GestureDetector mSwipeDetector;
    private Consumer<Animator> mOnDismissInitiated;
    private Runnable mOnDismissComplete;
    private Runnable mOnInteraction;

    public DraggableConstraintLayout(Context context) {
        this(context, null);
    }

    public DraggableConstraintLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DraggableConstraintLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mSwipeDismissHandler = new SwipeDismissHandler(mContext, this,
                new SwipeDismissHandler.SwipeDismissCallbacks() {
                    @Override
                    public void onInteraction() {
                        if (mOnInteraction != null) {
                            mOnInteraction.run();
                        }
                    }

                    @Override
                    public void onSwipeDismissInitiated(Animator animator) {
                        if (mOnDismissInitiated != null) {
                            mOnDismissInitiated.accept(animator);
                        }
                    }

                    @Override
                    public void onDismissComplete() {
                        if (mOnDismissComplete != null) {
                            mOnDismissComplete.run();
                        }
                    }
                });
        setOnTouchListener(mSwipeDismissHandler);

        mSwipeDetector = new GestureDetector(mContext,
                new GestureDetector.SimpleOnGestureListener() {
                    final Rect mActionsRect = new Rect();

                    @Override
                    public boolean onScroll(
                            MotionEvent ev1, MotionEvent ev2, float distanceX, float distanceY) {
                        View actionsContainer = findViewById(R.id.actions_container);
                        actionsContainer.getBoundsOnScreen(mActionsRect);
                        // return true if we aren't in the actions bar, or if we are but it isn't
                        // scrollable in the direction of movement
                        return !mActionsRect.contains((int) ev2.getRawX(), (int) ev2.getRawY())
                                || !actionsContainer.canScrollHorizontally((int) distanceX);
                    }
                });
        mSwipeDetector.setIsLongpressEnabled(false);
    }

    @Override // View
    protected void onFinishInflate() {

    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mSwipeDismissHandler.onTouch(this, ev);
        }

        return mSwipeDetector.onTouchEvent(ev);
    }

    /**
     * Dismiss the view, with animation controlled by SwipeDismissHandler
     */
    public void dismiss() {
        mSwipeDismissHandler.dismiss();
    }

    /**
     * Set the callback to be run after view is dismissed (before animation; receives animator as
     * input)
     */
    public void setOnDismissStartCallback(Consumer<Animator> callback) {
        mOnDismissInitiated = callback;
    }

    /**
     * Set the callback to be run after view is dismissed
     */
    public void setOnDismissEndCallback(Runnable callback) {
        mOnDismissComplete = callback;
    }

    /**
     * Set the callback to be run when the view is interacted with (e.g. tapped)
     */
    public void setOnInteractionCallback(Runnable callback) {
        mOnInteraction = callback;
    }
}
