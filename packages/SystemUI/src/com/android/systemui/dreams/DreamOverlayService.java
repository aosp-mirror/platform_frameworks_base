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
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
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
    // A controller for the dream overlay container view (which contains both the status bar and the
    // content area).
    private final DreamOverlayContainerViewController mDreamOverlayContainerViewController;

    // A reference to the {@link Window} used to hold the dream overlay.
    private Window mWindow;

    private final DreamOverlayStateController.Callback mOverlayStateCallback =
            new DreamOverlayStateController.Callback() {
                @Override
                public void onComplicationsChanged() {
                    mExecutor.execute(() -> reloadComplicationsLocked());
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
        mDreamOverlayContainerViewController =
                dreamOverlayComponentFactory.create().getDreamOverlayContainerViewController();

        mStateController.addCallback(mOverlayStateCallback);
    }

    @Override
    public void onDestroy() {
        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        windowManager.removeView(mWindow.getDecorView());
        mStateController.removeCallback(mOverlayStateCallback);
        super.onDestroy();
    }

    @Override
    public void onStartDream(@NonNull WindowManager.LayoutParams layoutParams) {
        mExecutor.execute(() -> addOverlayWindowLocked(layoutParams));
    }

    private void reloadComplicationsLocked() {
        mDreamOverlayContainerViewController.removeAllOverlays();
        for (ComplicationProvider overlayProvider : mStateController.getComplications()) {
            addComplication(overlayProvider);
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
        mWindow = new PhoneWindow(mContext);
        mWindow.setAttributes(layoutParams);
        mWindow.setWindowManager(null, layoutParams.token, "DreamOverlay", true);

        mWindow.setBackgroundDrawable(new ColorDrawable(0));

        mWindow.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        mWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        // Hide all insets when the dream is showing
        mWindow.getDecorView().getWindowInsetsController().hide(WindowInsets.Type.systemBars());
        mWindow.setDecorFitsSystemWindows(false);

        if (DEBUG) {
            Log.d(TAG, "adding overlay window to dream");
        }

        mDreamOverlayContainerViewController.init();
        mWindow.setContentView(mDreamOverlayContainerViewController.getContainerView());

        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        windowManager.addView(mWindow.getDecorView(), mWindow.getAttributes());
        mExecutor.execute(this::reloadComplicationsLocked);
    }

    @VisibleForTesting
    protected void addComplication(ComplicationProvider provider) {
        provider.onCreateComplication(mContext,
                (view, layoutParams) -> {
                    // Always move UI related work to the main thread.
                    mExecutor.execute(() -> mDreamOverlayContainerViewController
                            .addOverlay(view, layoutParams));
                },
                () -> {
                    // The Callback is set on the main thread.
                    mExecutor.execute(this::requestExit);
                });
    }
}
