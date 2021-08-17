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

package com.android.systemui.statusbar.phone;

import android.app.Fragment;
import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.systemui.R;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.statusbar.notification.AboveShelfObserver;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * The container with notification stack scroller and quick settings inside.
 */
public class NotificationsQuickSettingsContainer extends ConstraintLayout
        implements FragmentListener, AboveShelfObserver.HasViewAboveShelfChangedListener {

    private FrameLayout mQsFrame;
    private NotificationStackScrollLayout mStackScroller;
    private View mKeyguardStatusBar;
    private boolean mQsExpanded;
    private boolean mCustomizerAnimating;
    private boolean mCustomizing;
    private boolean mDetailShowing;

    private int mBottomPadding;
    private int mStackScrollerMargin;
    private boolean mHasViewsAboveShelf;
    private ArrayList<View> mDrawingOrderedChildren = new ArrayList<>();
    private ArrayList<View> mLayoutDrawingOrder = new ArrayList<>();
    private final Comparator<View> mIndexComparator = Comparator.comparingInt(this::indexOfChild);

    public NotificationsQuickSettingsContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQsFrame = findViewById(R.id.qs_frame);
        mStackScroller = findViewById(R.id.notification_stack_scroller);
        mStackScrollerMargin = ((LayoutParams) mStackScroller.getLayoutParams()).bottomMargin;
        mKeyguardStatusBar = findViewById(R.id.keyguard_header);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        FragmentHostManager.get(this).addTagListener(QS.TAG, this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        FragmentHostManager.get(this).removeTagListener(QS.TAG, this);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mBottomPadding = insets.getStableInsetBottom();
        setPadding(0, 0, 0, mBottomPadding);
        return insets;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        mDrawingOrderedChildren.clear();
        mLayoutDrawingOrder.clear();
        if (mKeyguardStatusBar.getVisibility() == View.VISIBLE) {
            mDrawingOrderedChildren.add(mKeyguardStatusBar);
            mLayoutDrawingOrder.add(mKeyguardStatusBar);
        }
        if (mQsFrame.getVisibility() == View.VISIBLE) {
            mDrawingOrderedChildren.add(mQsFrame);
            mLayoutDrawingOrder.add(mQsFrame);
        }
        if (mStackScroller.getVisibility() == View.VISIBLE) {
            mDrawingOrderedChildren.add(mStackScroller);
            mLayoutDrawingOrder.add(mStackScroller);
        }

        // Let's now find the order that the view has when drawing regularly by sorting
        mLayoutDrawingOrder.sort(mIndexComparator);
        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        int layoutIndex = mLayoutDrawingOrder.indexOf(child);
        if (layoutIndex >= 0) {
            return super.drawChild(canvas, mDrawingOrderedChildren.get(layoutIndex), drawingTime);
        } else {
            return super.drawChild(canvas, child, drawingTime);
        }
    }

    @Override
    public void onFragmentViewCreated(String tag, Fragment fragment) {
        QS container = (QS) fragment;
        container.setContainer(this);
    }

    public void setQsExpanded(boolean expanded) {
        if (mQsExpanded != expanded) {
            mQsExpanded = expanded;
            invalidate();
        }
    }

    public void setCustomizerAnimating(boolean isAnimating) {
        if (mCustomizerAnimating != isAnimating) {
            mCustomizerAnimating = isAnimating;
            invalidate();
        }
    }

    public void setCustomizerShowing(boolean isShowing) {
        mCustomizing = isShowing;
        updateBottomMargin();
        mStackScroller.setQsCustomizerShowing(isShowing);
    }

    public void setDetailShowing(boolean isShowing) {
        mDetailShowing = isShowing;
        updateBottomMargin();
    }

    private void updateBottomMargin() {
        if (mCustomizing || mDetailShowing) {
            // Clear out bottom paddings/margins so the qs customization can be full height.
            setPadding(0, 0, 0, 0);
            setBottomMargin(mStackScroller, 0);
        } else {
            setPadding(0, 0, 0, mBottomPadding);
            setBottomMargin(mStackScroller, mStackScrollerMargin);
        }
    }

    private void setBottomMargin(View v, int bottomMargin) {
        LayoutParams params = (LayoutParams) v.getLayoutParams();
        params.bottomMargin = bottomMargin;
        v.setLayoutParams(params);
    }

    @Override
    public void onHasViewsAboveShelfChanged(boolean hasViewsAboveShelf) {
        mHasViewsAboveShelf = hasViewsAboveShelf;
        invalidate();
    }
}
