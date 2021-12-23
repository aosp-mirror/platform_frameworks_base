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

package com.android.systemui.dreams;

import android.graphics.Rect;
import android.graphics.Region;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;
import com.android.systemui.dreams.dagger.DreamOverlayModule;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * View controller for {@link DreamOverlayContainerView}.
 */
@DreamOverlayComponent.DreamOverlayScope
public class DreamOverlayContainerViewController extends ViewController<DreamOverlayContainerView> {
    // The height of the area at the top of the dream overlay to allow dragging down the
    // notifications shade.
    private final int mDreamOverlayNotificationsDragAreaHeight;
    private final DreamOverlayStatusBarViewController mStatusBarViewController;

    // The dream overlay's content view, which is located below the status bar (in z-order) and is
    // the space into which widgets are placed.
    private final ViewGroup mDreamOverlayContentView;

    // A hook into the internal inset calculation where we declare the overlays as the only
    // touchable regions.
    private final ViewTreeObserver.OnComputeInternalInsetsListener
            mOnComputeInternalInsetsListener =
            new ViewTreeObserver.OnComputeInternalInsetsListener() {
                @Override
                public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
                    inoutInfo.setTouchableInsets(
                            ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
                    final Region region = new Region();
                    final Rect rect = new Rect();
                    final int childCount = mDreamOverlayContentView.getChildCount();
                    for (int i = 0; i < childCount; i++) {
                        View child = mDreamOverlayContentView.getChildAt(i);
                        if (child.getGlobalVisibleRect(rect)) {
                            region.op(rect, Region.Op.UNION);
                        }
                    }

                    // Add the notifications drag area to the tap region (otherwise the
                    // notifications shade can't be dragged down).
                    if (mDreamOverlayContentView.getGlobalVisibleRect(rect)) {
                        rect.bottom = rect.top + mDreamOverlayNotificationsDragAreaHeight;
                        region.op(rect, Region.Op.UNION);
                    }

                    inoutInfo.touchableRegion.set(region);
                }
            };

    @Inject
    public DreamOverlayContainerViewController(
            DreamOverlayContainerView containerView,
            @Named(DreamOverlayModule.DREAM_OVERLAY_CONTENT_VIEW) ViewGroup contentView,
            DreamOverlayStatusBarViewController statusBarViewController) {
        super(containerView);
        mDreamOverlayContentView = contentView;
        mStatusBarViewController = statusBarViewController;
        mDreamOverlayNotificationsDragAreaHeight =
                mView.getResources().getDimensionPixelSize(
                        R.dimen.dream_overlay_notifications_drag_area_height);
    }

    @Override
    protected void onInit() {
        mStatusBarViewController.init();
    }

    @Override
    protected void onViewAttached() {
        mView.getViewTreeObserver()
                .addOnComputeInternalInsetsListener(mOnComputeInternalInsetsListener);
    }

    @Override
    protected void onViewDetached() {
        mView.getViewTreeObserver()
                .removeOnComputeInternalInsetsListener(mOnComputeInternalInsetsListener);
    }

    void addOverlay(View overlayView, ConstraintLayout.LayoutParams layoutParams) {
        mDreamOverlayContentView.addView(overlayView, layoutParams);
    }

    View getContainerView() {
        return mView;
    }

    void removeAllOverlays() {
        mDreamOverlayContentView.removeAllViews();
    }

    @VisibleForTesting
    int getDreamOverlayNotificationsDragAreaHeight() {
        return mDreamOverlayNotificationsDragAreaHeight;
    }
}
