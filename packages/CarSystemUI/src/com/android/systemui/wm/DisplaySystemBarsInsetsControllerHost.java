/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.wm;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.IDisplayWindowInsetsController;
import android.view.InsetsController;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SyncRtSurfaceTransactionApplier;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;

import java.util.List;

/**
 * Implements {@link InsetsController.Host} for usage by
 * {@link DisplaySystemBarsController.PerDisplay} instances in {@link DisplaySystemBarsController}.
 * @hide
 */
public class DisplaySystemBarsInsetsControllerHost implements InsetsController.Host {

    private static final String TAG = DisplaySystemBarsInsetsControllerHost.class.getSimpleName();

    private final Handler mHandler;
    private final IDisplayWindowInsetsController mController;
    private final float[] mTmpFloat9 = new float[9];

    public DisplaySystemBarsInsetsControllerHost(
            Handler handler, IDisplayWindowInsetsController controller) {
        mHandler = handler;
        mController = controller;
    }

    @Override
    public Handler getHandler() {
        return mHandler;
    }

    @Override
    public void notifyInsetsChanged() {
        // no-op
    }

    @Override
    public void dispatchWindowInsetsAnimationPrepare(@NonNull WindowInsetsAnimation animation) {
        // no-op
    }

    @Override
    public WindowInsetsAnimation.Bounds dispatchWindowInsetsAnimationStart(
            @NonNull WindowInsetsAnimation animation,
            @NonNull WindowInsetsAnimation.Bounds bounds) {
        return null;
    }

    @Override
    public WindowInsets dispatchWindowInsetsAnimationProgress(@NonNull WindowInsets insets,
            @NonNull List<WindowInsetsAnimation> runningAnimations) {
        return null;
    }

    @Override
    public void dispatchWindowInsetsAnimationEnd(@NonNull WindowInsetsAnimation animation) {
        // no-op
    }

    @Override
    public void applySurfaceParams(final SyncRtSurfaceTransactionApplier.SurfaceParams... params) {
        for (int i = params.length - 1; i >= 0; i--) {
            SyncRtSurfaceTransactionApplier.applyParams(
                    new SurfaceControl.Transaction(), params[i], mTmpFloat9);
        }

    }

    @Override
    public void updateCompatSysUiVisibility(
            @InsetsState.InternalInsetsType int type, boolean visible, boolean hasControl) {
        // no-op
    }

    @Override
    public void onInsetsModified(InsetsState insetsState) {
        try {
            mController.insetsChanged(insetsState);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to send insets to controller");
        }
    }

    @Override
    public boolean hasAnimationCallbacks() {
        return false;
    }

    @Override
    public void setSystemBarsAppearance(
            @WindowInsetsController.Appearance int appearance,
            @WindowInsetsController.Appearance int mask) {
        // no-op
    }

    @Override
    public @WindowInsetsController.Appearance int getSystemBarsAppearance() {
        return 0;
    }

    @Override
    public void setSystemBarsBehavior(@WindowInsetsController.Behavior int behavior) {
        // no-op
    }

    @Override
    public @WindowInsetsController.Behavior int getSystemBarsBehavior() {
        return 0;
    }

    @Override
    public void releaseSurfaceControlFromRt(SurfaceControl surfaceControl) {
        surfaceControl.release();
    }

    @Override
    public void addOnPreDrawRunnable(Runnable r) {
        mHandler.post(r);
    }

    @Override
    public void postInsetsAnimationCallback(Runnable r) {
        mHandler.post(r);
    }

    @Override
    public InputMethodManager getInputMethodManager() {
        return null;
    }

    @Override
    public String getRootViewTitle() {
        return null;
    }

    @Override
    public int dipToPx(int dips) {
        return 0;
    }

    @Override
    public IBinder getWindowToken() {
        return null;
    }
}
