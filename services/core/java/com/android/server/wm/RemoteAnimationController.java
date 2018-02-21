/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationFinishedCallback.Stub;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Helper class to run app animations in a remote process.
 */
class RemoteAnimationController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "RemoteAnimationController" : TAG_WM;
    private static final long TIMEOUT_MS = 2000;

    private final WindowManagerService mService;
    private final RemoteAnimationAdapter mRemoteAnimationAdapter;
    private final ArrayList<RemoteAnimationAdapterWrapper> mPendingAnimations = new ArrayList<>();
    private final Rect mTmpRect = new Rect();
    private final Handler mHandler;
    private FinishedCallback mFinishedCallback;

    private final Runnable mTimeoutRunnable = () -> {
        onAnimationFinished();
        invokeAnimationCancelled();
    };

    RemoteAnimationController(WindowManagerService service,
            RemoteAnimationAdapter remoteAnimationAdapter, Handler handler) {
        mService = service;
        mRemoteAnimationAdapter = remoteAnimationAdapter;
        mHandler = handler;
    }

    /**
     * Creates an animation for each individual {@link AppWindowToken}.
     *
     * @param appWindowToken The app to animate.
     * @param position The position app bounds, in screen coordinates.
     * @param stackBounds The stack bounds of the app.
     * @return The adapter to be run on the app.
     */
    AnimationAdapter createAnimationAdapter(AppWindowToken appWindowToken, Point position,
            Rect stackBounds) {
        final RemoteAnimationAdapterWrapper adapter = new RemoteAnimationAdapterWrapper(
                appWindowToken, position, stackBounds);
        mPendingAnimations.add(adapter);
        return adapter;
    }

    /**
     * Called when the transition is ready to be started, and all leashes have been set up.
     */
    void goodToGo() {
        if (mPendingAnimations.isEmpty()) {
            onAnimationFinished();
            return;
        }

        // Scale the timeout with the animator scale the controlling app is using.
        mHandler.postDelayed(mTimeoutRunnable,
                (long) (TIMEOUT_MS * mService.getCurrentAnimatorScale()));
        mFinishedCallback = new FinishedCallback(this);

        final RemoteAnimationTarget[] animations = createAnimations();
        mService.mAnimator.addAfterPrepareSurfacesRunnable(() -> {
            try {
                mRemoteAnimationAdapter.getRunner().onAnimationStart(animations,
                        mFinishedCallback);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to start remote animation", e);
                onAnimationFinished();
            }
        });
        sendRunningRemoteAnimation(true);
    }

    private RemoteAnimationTarget[] createAnimations() {
        final ArrayList<RemoteAnimationTarget> targets = new ArrayList<>();
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final RemoteAnimationTarget target =
                    mPendingAnimations.get(i).createRemoteAppAnimation();
            if (target != null) {
                targets.add(target);
            }
        }
        return targets.toArray(new RemoteAnimationTarget[targets.size()]);
    }

    private void onAnimationFinished() {
        mHandler.removeCallbacks(mTimeoutRunnable);
        synchronized (mService.mWindowMap) {
            releaseFinishedCallback();
            mService.openSurfaceTransaction();
            try {
                for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
                    final RemoteAnimationAdapterWrapper adapter = mPendingAnimations.get(i);
                    adapter.mCapturedFinishCallback.onAnimationFinished(adapter);
                }
            } finally {
                mService.closeSurfaceTransaction("RemoteAnimationController#finished");
            }
        }
        sendRunningRemoteAnimation(false);
    }

    private void invokeAnimationCancelled() {
        try {
            mRemoteAnimationAdapter.getRunner().onAnimationCancelled();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to notify cancel", e);
        }
    }

    private void releaseFinishedCallback() {
        if (mFinishedCallback != null) {
            mFinishedCallback.release();
            mFinishedCallback = null;
        }
    }

    private void sendRunningRemoteAnimation(boolean running) {
        final int pid = mRemoteAnimationAdapter.getCallingPid();
        if (pid == 0) {
            throw new RuntimeException("Calling pid of remote animation was null");
        }
        mService.sendSetRunningRemoteAnimation(pid, running);
    }

    private static final class FinishedCallback extends IRemoteAnimationFinishedCallback.Stub {

        RemoteAnimationController mOuter;

        FinishedCallback(RemoteAnimationController outer) {
            mOuter = outer;
        }

        @Override
        public void onAnimationFinished() throws RemoteException {
            if (mOuter != null) {
                mOuter.onAnimationFinished();

                // In case the client holds on to the finish callback, make sure we don't leak
                // RemoteAnimationController which in turn would leak the runner on the client.
                mOuter = null;
            }
        }

        /**
         * Marks this callback as not be used anymore by releasing the reference to the outer class
         * to prevent memory leak.
         */
        void release() {
            mOuter = null;
        }
    };

    private class RemoteAnimationAdapterWrapper implements AnimationAdapter {

        private final AppWindowToken mAppWindowToken;
        private SurfaceControl mCapturedLeash;
        private OnAnimationFinishedCallback mCapturedFinishCallback;
        private final Point mPosition = new Point();
        private final Rect mStackBounds = new Rect();

        RemoteAnimationAdapterWrapper(AppWindowToken appWindowToken, Point position,
                Rect stackBounds) {
            mAppWindowToken = appWindowToken;
            mPosition.set(position.x, position.y);
            mStackBounds.set(stackBounds);
        }

        RemoteAnimationTarget createRemoteAppAnimation() {
            final Task task = mAppWindowToken.getTask();
            final WindowState mainWindow = mAppWindowToken.findMainWindow();
            if (task == null) {
                return null;
            }
            if (mainWindow == null) {
                return null;
            }
            return new RemoteAnimationTarget(task.mTaskId, getMode(),
                    mCapturedLeash, !mAppWindowToken.fillsParent(),
                    mainWindow.mWinAnimator.mLastClipRect, mainWindow.mContentInsets,
                    mAppWindowToken.getPrefixOrderIndex(), mPosition, mStackBounds,
                    task.getWindowConfiguration());
        }

        private int getMode() {
            if (mService.mOpeningApps.contains(mAppWindowToken)) {
                return RemoteAnimationTarget.MODE_OPENING;
            } else {
                return RemoteAnimationTarget.MODE_CLOSING;
            }
        }

        @Override
        public boolean getDetachWallpaper() {
            return false;
        }

        @Override
        public int getBackgroundColor() {
            return 0;
        }

        @Override
        public void startAnimation(SurfaceControl animationLeash, Transaction t,
                OnAnimationFinishedCallback finishCallback) {

            // Restore z-layering, position and stack crop until client has a chance to modify it.
            t.setLayer(animationLeash, mAppWindowToken.getPrefixOrderIndex());
            t.setPosition(animationLeash, mPosition.x, mPosition.y);
            mTmpRect.set(mStackBounds);
            mTmpRect.offsetTo(0, 0);
            t.setWindowCrop(animationLeash, mTmpRect);
            mCapturedLeash = animationLeash;
            mCapturedFinishCallback = finishCallback;
        }

        @Override
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            mPendingAnimations.remove(this);
            if (mPendingAnimations.isEmpty()) {
                mHandler.removeCallbacks(mTimeoutRunnable);
                releaseFinishedCallback();
                invokeAnimationCancelled();
                sendRunningRemoteAnimation(false);
            }
        }

        @Override
        public long getDurationHint() {
            return mRemoteAnimationAdapter.getDuration();
        }

        @Override
        public long getStatusBarTransitionsStartTime() {
            return SystemClock.uptimeMillis()
                    + mRemoteAnimationAdapter.getStatusBarTransitionDelay();
        }
    }
}
