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

import static com.android.server.wm.AnimationAdapterProto.REMOTE;
import static com.android.server.wm.RemoteAnimationAdapterWrapperProto.TARGET;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_REMOTE_ANIMATIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.internal.util.FastPrintWriter;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;
import com.android.server.wm.utils.InsetUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;

/**
 * Helper class to run app animations in a remote process.
 */
class RemoteAnimationController implements DeathRecipient {
    private static final String TAG = TAG_WITH_CLASS_NAME
            || (DEBUG_REMOTE_ANIMATIONS && !DEBUG_APP_TRANSITIONS)
                    ? "RemoteAnimationController" : TAG_WM;
    private static final long TIMEOUT_MS = 2000;

    private final WindowManagerService mService;
    private final RemoteAnimationAdapter mRemoteAnimationAdapter;
    private final ArrayList<RemoteAnimationAdapterWrapper> mPendingAnimations = new ArrayList<>();
    private final Rect mTmpRect = new Rect();
    private final Handler mHandler;
    private final Runnable mTimeoutRunnable = () -> cancelAnimation("timeoutRunnable");

    private FinishedCallback mFinishedCallback;
    private boolean mCanceled;
    private boolean mLinkedToDeathOfRunner;

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
        if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "createAnimationAdapter(): token=" + appWindowToken);
        final RemoteAnimationAdapterWrapper adapter = new RemoteAnimationAdapterWrapper(
                appWindowToken, position, stackBounds);
        mPendingAnimations.add(adapter);
        return adapter;
    }

    /**
     * Called when the transition is ready to be started, and all leashes have been set up.
     */
    void goodToGo() {
        if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "goodToGo()");
        if (mPendingAnimations.isEmpty() || mCanceled) {
            if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "goodToGo(): Animation finished before good to go, canceled="
                    + mCanceled + " mPendingAnimations=" + mPendingAnimations.size());
            onAnimationFinished();
            return;
        }

        // Scale the timeout with the animator scale the controlling app is using.
        mHandler.postDelayed(mTimeoutRunnable,
                (long) (TIMEOUT_MS * mService.getCurrentAnimatorScale()));
        mFinishedCallback = new FinishedCallback(this);

        final RemoteAnimationTarget[] animations = createAnimations();
        if (animations.length == 0) {
            if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "goodToGo(): No apps to animate");
            onAnimationFinished();
            return;
        }
        mService.mAnimator.addAfterPrepareSurfacesRunnable(() -> {
            try {
                linkToDeathOfRunner();
                mRemoteAnimationAdapter.getRunner().onAnimationStart(animations, mFinishedCallback);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to start remote animation", e);
                onAnimationFinished();
            }
            if (DEBUG_REMOTE_ANIMATIONS) {
                Slog.d(TAG, "startAnimation(): Notify animation start:");
                for (int i = 0; i < mPendingAnimations.size(); i++) {
                    Slog.d(TAG, "\t" + mPendingAnimations.get(i).mAppWindowToken);
                }
            } else if (DEBUG_APP_TRANSITIONS) {
                writeStartDebugStatement();
            }
        });
        sendRunningRemoteAnimation(true);
    }

    private void cancelAnimation(String reason) {
        if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "cancelAnimation(): reason=" + reason);
        synchronized (mService.getWindowManagerLock()) {
            if (mCanceled) {
                return;
            }
            mCanceled = true;
        }
        onAnimationFinished();
        invokeAnimationCancelled();
    }

    private void writeStartDebugStatement() {
        Slog.i(TAG, "Starting remote animation");
        final StringWriter sw = new StringWriter();
        final FastPrintWriter pw = new FastPrintWriter(sw);
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            mPendingAnimations.get(i).dump(pw, "");
        }
        pw.close();
        Slog.i(TAG, sw.toString());
    }

    private RemoteAnimationTarget[] createAnimations() {
        if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "createAnimations()");
        final ArrayList<RemoteAnimationTarget> targets = new ArrayList<>();
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final RemoteAnimationAdapterWrapper wrapper = mPendingAnimations.get(i);
            final RemoteAnimationTarget target = wrapper.createRemoteAppAnimation();
            if (target != null) {
                if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "\tAdd token=" + wrapper.mAppWindowToken);
                targets.add(target);
            } else {
                if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "\tRemove token=" + wrapper.mAppWindowToken);

                // We can't really start an animation but we still need to make sure to finish the
                // pending animation that was started by SurfaceAnimator
                if (wrapper.mCapturedFinishCallback != null) {
                    wrapper.mCapturedFinishCallback.onAnimationFinished(wrapper);
                }
                mPendingAnimations.remove(i);
            }
        }
        return targets.toArray(new RemoteAnimationTarget[targets.size()]);
    }

    private void onAnimationFinished() {
        if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "onAnimationFinished(): mPendingAnimations="
                + mPendingAnimations.size());
        mHandler.removeCallbacks(mTimeoutRunnable);
        synchronized (mService.mWindowMap) {
            unlinkToDeathOfRunner();
            releaseFinishedCallback();
            mService.openSurfaceTransaction();
            try {
                if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "onAnimationFinished(): Notify animation finished:");
                for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
                    final RemoteAnimationAdapterWrapper adapter = mPendingAnimations.get(i);
                    adapter.mCapturedFinishCallback.onAnimationFinished(adapter);
                    if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "\t" + adapter.mAppWindowToken);
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to finish remote animation", e);
                throw e;
            } finally {
                mService.closeSurfaceTransaction("RemoteAnimationController#finished");
            }
        }
        sendRunningRemoteAnimation(false);
        if (DEBUG_REMOTE_ANIMATIONS) Slog.i(TAG, "Finishing remote animation");
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

    private void linkToDeathOfRunner() throws RemoteException {
        if (!mLinkedToDeathOfRunner) {
            mRemoteAnimationAdapter.getRunner().asBinder().linkToDeath(this, 0);
            mLinkedToDeathOfRunner = true;
        }
    }

    private void unlinkToDeathOfRunner() {
        if (mLinkedToDeathOfRunner) {
            mRemoteAnimationAdapter.getRunner().asBinder().unlinkToDeath(this, 0);
            mLinkedToDeathOfRunner = false;
        }
    }

    @Override
    public void binderDied() {
        cancelAnimation("binderDied");
    }

    private static final class FinishedCallback extends IRemoteAnimationFinishedCallback.Stub {

        RemoteAnimationController mOuter;

        FinishedCallback(RemoteAnimationController outer) {
            mOuter = outer;
        }

        @Override
        public void onAnimationFinished() throws RemoteException {
            if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "app-onAnimationFinished(): mOuter=" + mOuter);
            final long token = Binder.clearCallingIdentity();
            try {
                if (mOuter != null) {
                    mOuter.onAnimationFinished();

                    // In case the client holds on to the finish callback, make sure we don't leak
                    // RemoteAnimationController which in turn would leak the runner on the client.
                    mOuter = null;
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * Marks this callback as not be used anymore by releasing the reference to the outer class
         * to prevent memory leak.
         */
        void release() {
            if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "app-release(): mOuter=" + mOuter);
            mOuter = null;
        }
    };

    private class RemoteAnimationAdapterWrapper implements AnimationAdapter {

        private final AppWindowToken mAppWindowToken;
        private SurfaceControl mCapturedLeash;
        private OnAnimationFinishedCallback mCapturedFinishCallback;
        private final Point mPosition = new Point();
        private final Rect mStackBounds = new Rect();
        private RemoteAnimationTarget mTarget;

        RemoteAnimationAdapterWrapper(AppWindowToken appWindowToken, Point position,
                Rect stackBounds) {
            mAppWindowToken = appWindowToken;
            mPosition.set(position.x, position.y);
            mStackBounds.set(stackBounds);
        }

        RemoteAnimationTarget createRemoteAppAnimation() {
            final Task task = mAppWindowToken.getTask();
            final WindowState mainWindow = mAppWindowToken.findMainWindow();
            if (task == null || mainWindow == null || mCapturedFinishCallback == null
                    || mCapturedLeash == null) {
                return null;
            }
            final Rect insets = new Rect(mainWindow.mContentInsets);
            InsetUtils.addInsets(insets, mAppWindowToken.getLetterboxInsets());
            mTarget = new RemoteAnimationTarget(task.mTaskId, getMode(),
                    mCapturedLeash, !mAppWindowToken.fillsParent(),
                    mainWindow.mWinAnimator.mLastClipRect, insets,
                    mAppWindowToken.getPrefixOrderIndex(), mPosition, mStackBounds,
                    task.getWindowConfiguration(), false /*isNotInRecents*/);
            return mTarget;
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
        public boolean getShowWallpaper() {
            return false;
        }

        @Override
        public int getBackgroundColor() {
            return 0;
        }

        @Override
        public void startAnimation(SurfaceControl animationLeash, Transaction t,
                OnAnimationFinishedCallback finishCallback) {
            if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "startAnimation");

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

        @Override
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix); pw.print("token="); pw.println(mAppWindowToken);
            if (mTarget != null) {
                pw.print(prefix); pw.println("Target:");
                mTarget.dump(pw, prefix + "  ");
            } else {
                pw.print(prefix); pw.println("Target: null");
            }
        }

        @Override
        public void writeToProto(ProtoOutputStream proto) {
            final long token = proto.start(REMOTE);
            if (mTarget != null) {
                mTarget.writeToProto(proto, TARGET);
            }
            proto.end(token);
        }
    }
}
