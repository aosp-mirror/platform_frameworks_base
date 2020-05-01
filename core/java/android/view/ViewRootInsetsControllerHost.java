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

package android.view;

import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_APPEARANCE_CONTROLLED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_BEHAVIOR_CONTROLLED;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

import java.util.List;

/**
 * Implements {@link InsetsController.Host} for {@link ViewRootImpl}s.
 * @hide
 */
public class ViewRootInsetsControllerHost implements InsetsController.Host {

    private final String TAG = "VRInsetsControllerHost";

    private final ViewRootImpl mViewRoot;
    private SyncRtSurfaceTransactionApplier mApplier;

    public ViewRootInsetsControllerHost(ViewRootImpl viewRoot) {
        mViewRoot = viewRoot;
    }

    @Override
    public Handler getHandler() {
        return mViewRoot.mHandler;
    }

    @Override
    public void notifyInsetsChanged() {
        mViewRoot.notifyInsetsChanged();
    }

    @Override
    public void addOnPreDrawRunnable(Runnable r) {
        if (mViewRoot.mView == null) {
            return;
        }
        mViewRoot.mView.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        mViewRoot.mView.getViewTreeObserver().removeOnPreDrawListener(this);
                        r.run();
                        return true;
                    }
                });
        mViewRoot.mView.invalidate();
    }

    @Override
    public void dispatchWindowInsetsAnimationPrepare(@NonNull WindowInsetsAnimation animation) {
        if (mViewRoot.mView == null) {
            return;
        }
        mViewRoot.mView.dispatchWindowInsetsAnimationPrepare(animation);
    }

    @Override
    public WindowInsetsAnimation.Bounds dispatchWindowInsetsAnimationStart(
            @NonNull WindowInsetsAnimation animation,
            @NonNull WindowInsetsAnimation.Bounds bounds) {
        if (mViewRoot.mView == null) {
            return null;
        }
        return mViewRoot.mView.dispatchWindowInsetsAnimationStart(animation, bounds);
    }

    @Override
    public WindowInsets dispatchWindowInsetsAnimationProgress(@NonNull WindowInsets insets,
            @NonNull List<WindowInsetsAnimation> runningAnimations) {
        if (mViewRoot.mView == null) {
            // The view has already detached from window.
            return null;
        }
        return mViewRoot.mView.dispatchWindowInsetsAnimationProgress(insets, runningAnimations);
    }

    @Override
    public void dispatchWindowInsetsAnimationEnd(@NonNull WindowInsetsAnimation animation) {
        mViewRoot.mView.dispatchWindowInsetsAnimationEnd(animation);
    }

    @Override
    public void applySurfaceParams(SyncRtSurfaceTransactionApplier.SurfaceParams... params) {
        if (mApplier == null) {
            if (mViewRoot.mView == null) {
                throw new IllegalStateException("View of the ViewRootImpl is not initiated.");
            }
            mApplier = new SyncRtSurfaceTransactionApplier(mViewRoot.mView);
        }
        if (mViewRoot.mView.isHardwareAccelerated()) {
            mApplier.scheduleApply(false /* earlyWakeup */, params);
        } else {
            // Window doesn't support hardware acceleration, no synchronization for now.
            // TODO(b/149342281): use mViewRoot.mSurface.getNextFrameNumber() to sync on every
            //  frame instead.
            mApplier.applyParams(new SurfaceControl.Transaction(), -1 /* frame */,
                    false /* earlyWakeup */, params);
        }
    }

    @Override
    public void postInsetsAnimationCallback(Runnable r) {
        mViewRoot.mChoreographer.postCallback(Choreographer.CALLBACK_INSETS_ANIMATION, r,
                null /* token */);
    }

    @Override
    public void updateCompatSysUiVisibility(int type, boolean visible, boolean hasControl) {
        mViewRoot.updateCompatSysUiVisibility(type, visible, hasControl);
    }

    @Override
    public void onInsetsModified(InsetsState insetsState) {
        try {
            mViewRoot.mWindowSession.insetsModified(mViewRoot.mWindow, insetsState);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call insetsModified", e);
        }
    }

    @Override
    public boolean hasAnimationCallbacks() {
        if (mViewRoot.mView == null) {
            return false;
        }
        return mViewRoot.mView.hasWindowInsetsAnimationCallback();
    }

    @Override
    public void setSystemBarsAppearance(int appearance, int mask) {
        mViewRoot.mWindowAttributes.privateFlags |= PRIVATE_FLAG_APPEARANCE_CONTROLLED;
        final InsetsFlags insetsFlags = mViewRoot.mWindowAttributes.insetsFlags;
        if (insetsFlags.appearance != appearance) {
            insetsFlags.appearance = (insetsFlags.appearance & ~mask) | (appearance & mask);
            mViewRoot.mWindowAttributesChanged = true;
            mViewRoot.scheduleTraversals();
        }
    }

    @Override
    public int getSystemBarsAppearance() {
        if ((mViewRoot.mWindowAttributes.privateFlags & PRIVATE_FLAG_APPEARANCE_CONTROLLED) == 0) {
            // We only return the requested appearance, not the implied one.
            return 0;
        }
        return mViewRoot.mWindowAttributes.insetsFlags.appearance;
    }

    @Override
    public void setSystemBarsBehavior(int behavior) {
        mViewRoot.mWindowAttributes.privateFlags |= PRIVATE_FLAG_BEHAVIOR_CONTROLLED;
        if (mViewRoot.mWindowAttributes.insetsFlags.behavior != behavior) {
            mViewRoot.mWindowAttributes.insetsFlags.behavior = behavior;
            mViewRoot.mWindowAttributesChanged = true;
            mViewRoot.scheduleTraversals();
        }
    }

    @Override
    public int getSystemBarsBehavior() {
        if ((mViewRoot.mWindowAttributes.privateFlags & PRIVATE_FLAG_BEHAVIOR_CONTROLLED) == 0) {
            // We only return the requested behavior, not the implied one.
            return 0;
        }
        return mViewRoot.mWindowAttributes.insetsFlags.behavior;
    }

    @Override
    public void releaseSurfaceControlFromRt(SurfaceControl surfaceControl) {

         // At the time we receive new leashes (e.g. InsetsSourceConsumer is processing
         // setControl) we need to release the old leash. But we may have already scheduled
         // a SyncRtSurfaceTransaction applier to use it from the RenderThread. To avoid
         // synchronization issues we also release from the RenderThread so this release
         // happens after any existing items on the work queue.

        if (mViewRoot.mView != null && mViewRoot.mView.isHardwareAccelerated()) {
            mViewRoot.registerRtFrameCallback(frame -> {
                surfaceControl.release();
            });
            // Make sure a frame gets scheduled.
            mViewRoot.mView.invalidate();
        } else {
            surfaceControl.release();
        }
    }

    @Override
    public InputMethodManager getInputMethodManager() {
        return mViewRoot.mContext.getSystemService(InputMethodManager.class);
    }
}
