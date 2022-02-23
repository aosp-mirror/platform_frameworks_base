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

import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;

import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.complication.ComplicationHostViewController;
import com.android.systemui.dreams.complication.dagger.ComplicationHostViewComponent;
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

    private final ComplicationHostViewController mComplicationHostViewController;

    // The dream overlay's content view, which is located below the status bar (in z-order) and is
    // the space into which widgets are placed.
    private final ViewGroup mDreamOverlayContentView;

    // The maximum translation offset to apply to the overlay container to avoid screen burn-in.
    private final int mMaxBurnInOffset;

    // The interval in milliseconds between burn-in protection updates.
    private final long mBurnInProtectionUpdateInterval;

    // Main thread handler used to schedule periodic tasks (e.g. burn-in protection updates).
    private final Handler mHandler;

    @Inject
    public DreamOverlayContainerViewController(
            DreamOverlayContainerView containerView,
            ComplicationHostViewComponent.Factory complicationHostViewFactory,
            @Named(DreamOverlayModule.DREAM_OVERLAY_CONTENT_VIEW) ViewGroup contentView,
            DreamOverlayStatusBarViewController statusBarViewController,
            @Main Handler handler,
            @Named(DreamOverlayModule.MAX_BURN_IN_OFFSET) int maxBurnInOffset,
            @Named(DreamOverlayModule.BURN_IN_PROTECTION_UPDATE_INTERVAL) long
                    burnInProtectionUpdateInterval) {
        super(containerView);
        mDreamOverlayContentView = contentView;
        mStatusBarViewController = statusBarViewController;
        mDreamOverlayNotificationsDragAreaHeight =
                mView.getResources().getDimensionPixelSize(
                        R.dimen.dream_overlay_notifications_drag_area_height);

        mComplicationHostViewController = complicationHostViewFactory.create().getController();
        final View view = mComplicationHostViewController.getView();

        mDreamOverlayContentView.addView(view,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        mHandler = handler;
        mMaxBurnInOffset = maxBurnInOffset;
        mBurnInProtectionUpdateInterval = burnInProtectionUpdateInterval;
    }

    @Override
    protected void onInit() {
        mStatusBarViewController.init();
        mComplicationHostViewController.init();
    }

    @Override
    protected void onViewAttached() {
        mHandler.postDelayed(this::updateBurnInOffsets, mBurnInProtectionUpdateInterval);
    }

    @Override
    protected void onViewDetached() {
        mHandler.removeCallbacks(this::updateBurnInOffsets);
    }

    View getContainerView() {
        return mView;
    }

    @VisibleForTesting
    int getDreamOverlayNotificationsDragAreaHeight() {
        return mDreamOverlayNotificationsDragAreaHeight;
    }

    private void updateBurnInOffsets() {
        // These translation values change slowly, and the set translation methods are idempotent,
        // so no translation occurs when the values don't change.
        mView.setTranslationX(getBurnInOffset(mMaxBurnInOffset * 2, true)
                - mMaxBurnInOffset);

        mView.setTranslationY(getBurnInOffset(mMaxBurnInOffset * 2, false)
                - mMaxBurnInOffset);

        mHandler.postDelayed(this::updateBurnInOffsets, mBurnInProtectionUpdateInterval);
    }
}
