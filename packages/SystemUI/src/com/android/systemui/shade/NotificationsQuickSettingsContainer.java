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

import android.app.Fragment;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;

import com.android.systemui.R;
import com.android.systemui.fragments.FragmentHostManager.FragmentListener;
import com.android.systemui.plugins.qs.QS;
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

    @Nullable
    private Consumer<Configuration> mConfigurationChangedListener;

    public NotificationsQuickSettingsContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQsFrame = findViewById(R.id.qs_frame);
        mStackScroller = findViewById(R.id.notification_stack_scroller);
        mKeyguardStatusBar = findViewById(R.id.keyguard_header);
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
        LayoutParams params = (LayoutParams) mStackScroller.getLayoutParams();
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
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
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
}
