/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.internal.policy.PhoneWindow;
import com.android.systemui.clipboardoverlay.dagger.ClipboardOverlayModule.OverlayWindowContext;
import com.android.systemui.screenshot.FloatingWindowUtil;

import java.util.function.BiConsumer;

import javax.inject.Inject;

/**
 * Handles attaching the window and the window insets for the clipboard overlay.
 */
public class ClipboardOverlayWindow extends PhoneWindow
        implements ViewRootImpl.ActivityConfigCallback {
    private static final String TAG = "ClipboardOverlayWindow";

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final ViewCaptureAwareWindowManager mViewCaptureAwareWindowManager;
    private final WindowManager.LayoutParams mWindowLayoutParams;

    private boolean mKeyboardVisible;
    private final int mOrientation;
    private BiConsumer<WindowInsets, Integer> mOnKeyboardChangeListener;
    private Runnable mOnOrientationChangeListener;

    @Inject
    ClipboardOverlayWindow(@OverlayWindowContext Context context,
            @OverlayWindowContext ViewCaptureAwareWindowManager viewCaptureAwareWindowManager,
            @OverlayWindowContext WindowManager windowManager) {
        super(context);
        mContext = context;
        mOrientation = mContext.getResources().getConfiguration().orientation;

        // Setup the window that we are going to use
        requestFeature(Window.FEATURE_NO_TITLE);
        requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS);
        setBackgroundDrawableResource(android.R.color.transparent);
        mWindowManager = windowManager;
        mViewCaptureAwareWindowManager = viewCaptureAwareWindowManager;
        mWindowLayoutParams = FloatingWindowUtil.getFloatingWindowParams();
        mWindowLayoutParams.setTitle("ClipboardOverlay");
        setWindowManager(windowManager, null, null);
        setWindowFocusable(false);
    }

    /**
     * Set callbacks for keyboard state change and orientation change and attach the window
     *
     * @param onKeyboardChangeListener callback for IME visibility changes
     * @param onOrientationChangeListener callback for device orientation changes
     */
    public void init(@NonNull BiConsumer<WindowInsets, Integer> onKeyboardChangeListener,
            @NonNull Runnable onOrientationChangeListener) {
        mOnKeyboardChangeListener = onKeyboardChangeListener;
        mOnOrientationChangeListener = onOrientationChangeListener;

        attach();
        withWindowAttached(() -> {
            WindowInsets currentInsets = mWindowManager.getCurrentWindowMetrics()
                    .getWindowInsets();
            mKeyboardVisible = currentInsets.isVisible(WindowInsets.Type.ime());
            peekDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                WindowInsets insets = mWindowManager.getCurrentWindowMetrics()
                        .getWindowInsets();
                boolean keyboardVisible = insets.isVisible(WindowInsets.Type.ime());
                if (keyboardVisible != mKeyboardVisible) {
                    mKeyboardVisible = keyboardVisible;
                    mOnKeyboardChangeListener.accept(insets, mOrientation);
                }
            });
            peekDecorView().getViewRootImpl().setActivityConfigCallback(this);
        });
    }

    @Override // ViewRootImpl.ActivityConfigCallback
    public void onConfigurationChanged(Configuration overrideConfig, int newDisplayId) {
        if (mContext.getResources().getConfiguration().orientation != mOrientation) {
            mOnOrientationChangeListener.run();
        }
    }

    void remove() {
        final View decorView = peekDecorView();
        if (decorView != null && decorView.isAttachedToWindow()) {
            mViewCaptureAwareWindowManager.removeViewImmediate(decorView);
        }
    }

    WindowInsets getWindowInsets() {
        return mWindowManager.getCurrentWindowMetrics().getWindowInsets();
    }

    void withWindowAttached(Runnable action) {
        View decorView = getDecorView();
        if (decorView.isAttachedToWindow()) {
            action.run();
        } else {
            decorView.getViewTreeObserver().addOnWindowAttachListener(
                    new ViewTreeObserver.OnWindowAttachListener() {
                        @Override
                        public void onWindowAttached() {
                            decorView.getViewTreeObserver().removeOnWindowAttachListener(this);
                            action.run();
                        }

                        @Override
                        public void onWindowDetached() {
                        }
                    });
        }
    }

    @MainThread
    private void attach() {
        View decorView = getDecorView();
        if (decorView.isAttachedToWindow()) {
            return;
        }
        mViewCaptureAwareWindowManager.addView(decorView, mWindowLayoutParams);
        decorView.requestApplyInsets();
    }

    /**
     * Updates the window focusability.  If the window is already showing, then it updates the
     * window immediately, otherwise the layout params will be applied when the window is next
     * shown.
     */
    private void setWindowFocusable(boolean focusable) {
        int flags = mWindowLayoutParams.flags;
        if (focusable) {
            mWindowLayoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            mWindowLayoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        if (mWindowLayoutParams.flags == flags) {
            return;
        }
        final View decorView = peekDecorView();
        if (decorView != null && decorView.isAttachedToWindow()) {
            mViewCaptureAwareWindowManager.updateViewLayout(decorView, mWindowLayoutParams);
        }
    }
}
