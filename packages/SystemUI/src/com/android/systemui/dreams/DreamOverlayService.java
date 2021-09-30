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
import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.PhoneWindow;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * The {@link DreamOverlayService} is responsible for placing overlays on top of a dream. The
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

    // The window is populated once the dream informs the service it has begun dreaming.
    private Window mWindow;
    private ConstraintLayout mLayout;

    // The service listens to view changes in order to declare that input occurring in areas outside
    // the overlay should be passed through to the dream underneath.
    private View.OnAttachStateChangeListener mRootViewAttachListener =
            new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(View v) {
            v.getViewTreeObserver()
                    .addOnComputeInternalInsetsListener(mOnComputeInternalInsetsListener);
        }

        @Override
        public void onViewDetachedFromWindow(View v) {
            v.getViewTreeObserver()
                    .removeOnComputeInternalInsetsListener(mOnComputeInternalInsetsListener);
        }
    };

    // A hook into the internal inset calculation where we declare the overlays as the only
    // touchable regions.
    private ViewTreeObserver.OnComputeInternalInsetsListener mOnComputeInternalInsetsListener  =
            new ViewTreeObserver.OnComputeInternalInsetsListener() {
        @Override
        public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
            if (mLayout != null) {
                inoutInfo.setTouchableInsets(
                        ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
                final Region region = new Region();
                for (int i = 0; i < mLayout.getChildCount(); i++) {
                    View child = mLayout.getChildAt(i);
                    final Rect rect = new Rect();
                    child.getGlobalVisibleRect(rect);
                    region.op(rect, Region.Op.UNION);
                }

                inoutInfo.touchableRegion.set(region);
            }
        }
    };

    @Override
    public void onStartDream(@NonNull WindowManager.LayoutParams layoutParams) {
        mExecutor.execute(() -> addOverlayWindowLocked(layoutParams));
    }

    /**
     * Inserts {@link Window} to host dream overlays into the dream's parent window. Must be called
     * from the main executing thread. The window attributes closely mirror those that are set by
     * the {@link android.service.dreams.DreamService} on the dream Window.
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

        mLayout = new ConstraintLayout(mContext);
        mLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mLayout.addOnAttachStateChangeListener(mRootViewAttachListener);
        mWindow.setContentView(mLayout);

        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        windowManager.addView(mWindow.getDecorView(), mWindow.getAttributes());
    }

    @VisibleForTesting
    protected void addOverlay(OverlayProvider provider) {
        provider.onCreateOverlay(mContext,
                (view, layoutParams) -> {
                    // Always move UI related work to the main thread.
                    mExecutor.execute(() -> {
                        if (mLayout == null) {
                            return;
                        }

                        mLayout.addView(view, layoutParams);
                    });
                },
                () -> {
                    // The Callback is set on the main thread.
                    mExecutor.execute(() -> {
                        requestExit();
                    });
                });
    }

    @Inject
    public DreamOverlayService(Context context, @Main Executor executor) {
        mContext = context;
        mExecutor = executor;
    }
}
