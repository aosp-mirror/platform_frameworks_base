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

package com.android.systemui.shade;

import static androidx.constraintlayout.core.widgets.Optimizer.OPTIMIZATION_GRAPH;

import static com.android.systemui.Flags.migrateClocksToBlueprint;

import android.app.Fragment;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AboveShelfObserver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Consumer;

/**
 * The container with notification stack scroller and quick settings inside.
 */
public class NotificationsQuickSettingsContainer extends ConstraintLayout
        implements FragmentListener, AboveShelfObserver.HasViewAboveShelfChangedListener {

    private View mQsFrame;
    private View mStackScroller;
    private View mKeyguardStatusBar;

    private final ArrayList<View> mDrawingOrderedChildren = new ArrayList<>();
    private final ArrayList<View> mLayoutDrawingOrder = new ArrayList<>();
    private final Comparator<View> mIndexComparator = Comparator.comparingInt(this::indexOfChild);
    private Consumer<WindowInsets> mInsetsChangedListener = insets -> {};
    private Consumer<QS> mQSFragmentAttachedListener = qs -> {};
    private QS mQs;
    private View mQSContainer;
    private int mLastQSPaddingBottom;

    /**
     *  These are used to compute the bounding box containing the shade and the notification scrim,
     *  which is then used to drive the Back gesture animation.
     */
    private final Rect mUpperRect = new Rect();
    private final Rect mBoundingBoxRect = new Rect();

    @Nullable
    private Consumer<Configuration> mConfigurationChangedListener;

    public NotificationsQuickSettingsContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOptimizationLevel(getOptimizationLevel() | OPTIMIZATION_GRAPH);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQsFrame = findViewById(R.id.qs_frame);
        mKeyguardStatusBar = findViewById(R.id.keyguard_header);
    }

    void setStackScroller(View stackScroller) {
        mStackScroller = stackScroller;
    }

    @Override
    public void onFragmentViewCreated(String tag, Fragment fragment) {
        mQs = (QS) fragment;
        mQSFragmentAttachedListener.accept(mQs);
        mQSContainer = mQs.getView().findViewById(R.id.quick_settings_container);
        // We need to restore the bottom padding as the fragment may have been recreated due to
        // some special Configuration change, so we apply the last known padding (this will be
        // correct even if it has changed while the fragment was destroyed and re-created).
        setQSContainerPaddingBottom(mLastQSPaddingBottom);
    }

    @Override
    public void onHasViewsAboveShelfChanged(boolean hasViewsAboveShelf) {
        invalidate();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mConfigurationChangedListener != null) {
            mConfigurationChangedListener.accept(newConfig);
        }
    }

    public void setConfigurationChangedListener(Consumer<Configuration> listener) {
        mConfigurationChangedListener = listener;
    }

    public void setNotificationsMarginBottom(int margin) {
        MarginLayoutParams params = (MarginLayoutParams) mStackScroller.getLayoutParams();
        params.bottomMargin = margin;
        mStackScroller.setLayoutParams(params);
    }

    public void setQSContainerPaddingBottom(int paddingBottom) {
        mLastQSPaddingBottom = paddingBottom;
        if (mQSContainer != null) {
            mQSContainer.setPadding(
                    mQSContainer.getPaddingLeft(),
                    mQSContainer.getPaddingTop(),
                    mQSContainer.getPaddingRight(),
                    paddingBottom
            );
        }
    }

    public void setInsetsChangedListener(Consumer<WindowInsets> onInsetsChangedListener) {
        mInsetsChangedListener = onInsetsChangedListener;
    }

    public void removeOnInsetsChangedListener() {
        mInsetsChangedListener = insets -> {};
    }

    public void setQSFragmentAttachedListener(Consumer<QS> qsFragmentAttachedListener) {
        mQSFragmentAttachedListener = qsFragmentAttachedListener;
        // listener might be attached after fragment is attached
        if (mQs != null) {
            mQSFragmentAttachedListener.accept(mQs);
        }
    }

    public void removeQSFragmentAttachedListener() {
        mQSFragmentAttachedListener = qs -> {};
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mInsetsChangedListener.accept(insets);
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
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return TouchLogger.logDispatchTouch("NotificationsQuickSettingsContainer", ev,
                super.dispatchTouchEvent(ev));
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (migrateClocksToBlueprint()) {
            return super.drawChild(canvas, child, drawingTime);
        }
        int layoutIndex = mLayoutDrawingOrder.indexOf(child);
        if (layoutIndex >= 0) {
            return super.drawChild(canvas, mDrawingOrderedChildren.get(layoutIndex), drawingTime);
        } else {
            return super.drawChild(canvas, child, drawingTime);
        }
    }

    public void applyConstraints(ConstraintSet constraintSet) {
        constraintSet.applyTo(this);
    }

    /**
     *  Scale multiple elements in tandem, for the predictive back animation.
     *  This is how the Shade responds to the Back gesture (by scaling).
     *  Without the common center, individual elements will scale about their respective centers.
     *  Scaling the entire NotificationsQuickSettingsContainer will also resize the shade header
     *  (which we don't want).
     */
    public void applyBackScaling(float scale, boolean usingSplitShade) {
        if (mStackScroller == null || mQSContainer == null) {
            return;
        }

        mQSContainer.getBoundsOnScreen(mUpperRect);
        mStackScroller.getBoundsOnScreen(mBoundingBoxRect);
        mBoundingBoxRect.union(mUpperRect);

        float cx = mBoundingBoxRect.centerX();
        float cy = mBoundingBoxRect.centerY();

        mQSContainer.setPivotX(cx);
        mQSContainer.setPivotY(cy);
        mQSContainer.setScaleX(scale);
        mQSContainer.setScaleY(scale);

        // When in large-screen split-shade mode, the notification stack scroller scales correctly
        // only if the pivot point is at the left edge of the screen (because of its dimensions).
        // When not in large-screen split-shade mode, we can scale correctly via the (cx,cy) above.
        mStackScroller.setPivotX(usingSplitShade ? 0.0f : cx);
        mStackScroller.setPivotY(cy);
        mStackScroller.setScaleX(scale);
        mStackScroller.setScaleY(scale);
    }
}
