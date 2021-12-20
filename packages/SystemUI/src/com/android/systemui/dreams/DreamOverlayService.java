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

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.PhoneWindow;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.dagger.DreamOverlayComponent;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * The {@link DreamOverlayService} is responsible for placing an overlay on top of a dream. The
 * dream reaches directly out to the service with a Window reference (via LayoutParams), which the
 * service uses to insert its own child Window into the dream's parent Window.
 */
public class DreamOverlayService extends android.service.dreams.DreamOverlayService {
    private static final String TAG = "DreamOverlayService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // The Context is used to construct the hosting constraint layout and child overlay views.
    private final Context mContext;
    // The Executor ensures actions and ui updates happen on the same thread.
    private final Executor mExecutor;
    // The state controller informs the service of updates to the complications present.
    private final DreamOverlayStateController mStateController;
    // The component used to resolve dream overlay dependencies.
    private final DreamOverlayComponent mDreamOverlayComponent;

    // The dream overlay's content view, which is located below the status bar (in z-order) and is
    // the space into which widgets are placed.
    private ViewGroup mDreamOverlayContentView;

    private final DreamOverlayStateController.Callback mOverlayStateCallback =
            new DreamOverlayStateController.Callback() {
                @Override
                public void onComplicationsChanged() {
                    mExecutor.execute(() -> reloadComplicationsLocked());
                }
            };

    // The service listens to view changes in order to declare that input occurring in areas outside
    // the overlay should be passed through to the dream underneath.
    private final View.OnAttachStateChangeListener mRootViewAttachListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    v.getViewTreeObserver()
                            .addOnComputeInternalInsetsListener(mOnComputeInternalInsetsListener);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    v.getViewTreeObserver()
                            .removeOnComputeInternalInsetsListener(
                                    mOnComputeInternalInsetsListener);
                }
            };

    // A hook into the internal inset calculation where we declare the complications as the only
    // touchable regions.
    private final ViewTreeObserver.OnComputeInternalInsetsListener
            mOnComputeInternalInsetsListener =
            new ViewTreeObserver.OnComputeInternalInsetsListener() {
                @Override
                public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
                    if (mDreamOverlayContentView != null) {
                        inoutInfo.setTouchableInsets(
                                ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
                        final Region region = new Region();
                        for (int i = 0; i < mDreamOverlayContentView.getChildCount(); i++) {
                            View child = mDreamOverlayContentView.getChildAt(i);
                            final Rect rect = new Rect();
                            child.getGlobalVisibleRect(rect);
                            region.op(rect, Region.Op.UNION);
                        }

                        inoutInfo.touchableRegion.set(region);
                    }
                }
            };

    @Inject
    public DreamOverlayService(
            Context context,
            @Main Executor executor,
            DreamOverlayStateController overlayStateController,
            DreamOverlayComponent.Factory dreamOverlayComponentFactory) {
        mContext = context;
        mExecutor = executor;
        mStateController = overlayStateController;
        mDreamOverlayComponent = dreamOverlayComponentFactory.create();

        mStateController.addCallback(mOverlayStateCallback);
    }

    @Override
    public void onStartDream(@NonNull WindowManager.LayoutParams layoutParams) {
        mExecutor.execute(() -> addOverlayWindowLocked(layoutParams));
    }

    private void reloadComplicationsLocked() {
        if (mDreamOverlayContentView == null) {
            return;
        }
        mDreamOverlayContentView.removeAllViews();
        for (ComplicationProvider complicationProvider : mStateController.getComplications()) {
            addComplication(complicationProvider);
        }
    }

    /**
     * Inserts {@link Window} to host the dream overlay into the dream's parent window. Must be
     * called from the main executing thread. The window attributes closely mirror those that are
     * set by the {@link android.service.dreams.DreamService} on the dream Window.
     * @param layoutParams The {@link android.view.WindowManager.LayoutParams} which allow inserting
     *                     into the dream window.
     */
    private void addOverlayWindowLocked(WindowManager.LayoutParams layoutParams) {
        final PhoneWindow window = new PhoneWindow(mContext);
        window.setAttributes(layoutParams);
        window.setWindowManager(null, layoutParams.token, "DreamOverlay", true);

        window.setBackgroundDrawable(new ColorDrawable(0));

        window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.requestFeature(Window.FEATURE_NO_TITLE);
        // Hide all insets when the dream is showing
        window.getDecorView().getWindowInsetsController().hide(WindowInsets.Type.systemBars());
        window.setDecorFitsSystemWindows(false);

        if (DEBUG) {
            Log.d(TAG, "adding overlay window to dream");
        }

        window.setContentView(mDreamOverlayComponent.getDreamOverlayContainerView());

        mDreamOverlayContentView = mDreamOverlayComponent.getDreamOverlayContentView();
        mDreamOverlayContentView.addOnAttachStateChangeListener(mRootViewAttachListener);

        mDreamOverlayComponent.getDreamOverlayStatusBarViewController().init();

        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        windowManager.addView(window.getDecorView(), window.getAttributes());
        mExecutor.execute(this::reloadComplicationsLocked);
    }

    @VisibleForTesting
    protected void addComplication(ComplicationProvider provider) {
        provider.onCreateComplication(mContext,
                (view, layoutParams) -> {
                    // Always move UI related work to the main thread.
                    mExecutor.execute(() -> {
                        if (mDreamOverlayContentView == null) {
                            return;
                        }

                        mDreamOverlayContentView.addView(view, layoutParams);
                    });
                },
                () -> {
                    // The Callback is set on the main thread.
                    mExecutor.execute(() -> {
                        requestExit();
                    });
                });
    }

    @Override
    public void onDestroy() {
        mStateController.removeCallback(mOverlayStateCallback);
        super.onDestroy();
    }
}
