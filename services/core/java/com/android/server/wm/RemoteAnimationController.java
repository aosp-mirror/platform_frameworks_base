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
    private final ArrayList<RemoteAnimationRecord> mPendingAnimations = new ArrayList<>();
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
     * Creates an animation record for each individual {@link AppWindowToken}.
     *
     * @param appWindowToken The app to animate.
     * @param position The position app bounds, in screen coordinates.
     * @param stackBounds The stack bounds of the app relative to position.
     * @param startBounds The stack bounds before the transition, in screen coordinates
     * @return The record representing animation(s) to run on the app.
     */
    RemoteAnimationRecord createRemoteAnimationRecord(AppWindowToken appWindowToken,
            Point position, Rect stackBounds, Rect startBounds) {
        if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "createAnimationAdapter(): token="
                + appWindowToken);
        final RemoteAnimationRecord adapters =
                new RemoteAnimationRecord(appWindowToken, position, stackBounds, startBounds);
        mPendingAnimations.add(adapters);
        return adapters;
    }

    /**
     * Called when the transition is ready to be started, and all leashes have been set up.
     */
    void goodToGo() {
        if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "goodToGo()");
        if (mPendingAnimations.isEmpty() || mCanceled) {
            if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "goodToGo(): Animation finished already,"
                    + " canceled=" + mCanceled
                    + " mPendingAnimations=" + mPendingAnimations.size());
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
                writeStartDebugStatement();
            }
        });
        sendRunningRemoteAnimation(true);
    }

    void cancelAnimation(String reason) {
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
            mPendingAnimations.get(i).mAdapter.dump(pw, "");
        }
        pw.close();
        Slog.i(TAG, sw.toString());
    }

    private RemoteAnimationTarget[] createAnimations() {
        if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "createAnimations()");
        final ArrayList<RemoteAnimationTarget> targets = new ArrayList<>();
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final RemoteAnimationRecord wrappers = mPendingAnimations.get(i);
            final RemoteAnimationTarget target = wrappers.createRemoteAnimationTarget();
            if (target != null) {
                if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "\tAdd token=" + wrappers.mAppWindowToken);
                targets.add(target);
            } else {
                if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "\tRemove token="
                        + wrappers.mAppWindowToken);

                // We can't really start an animation but we still need to make sure to finish the
                // pending animation that was started by SurfaceAnimator
                if (wrappers.mAdapter != null
                        && wrappers.mAdapter.mCapturedFinishCallback != null) {
                    wrappers.mAdapter.mCapturedFinishCallback
                            .onAnimationFinished(wrappers.mAdapter);
                }
                if (wrappers.mThumbnailAdapter != null
                        && wrappers.mThumbnailAdapter.mCapturedFinishCallback != null) {
                    wrappers.mThumbnailAdapter.mCapturedFinishCallback
                            .onAnimationFinished(wrappers.mThumbnailAdapter);
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
        synchronized (mService.mGlobalLock) {
            unlinkToDeathOfRunner();
            releaseFinishedCallback();
            mService.openSurfaceTransaction();
            try {
                if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG,
                        "onAnimationFinished(): Notify animation finished:");
                for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
                    final RemoteAnimationRecord adapters = mPendingAnimations.get(i);
                    if (adapters.mAdapter != null) {
                        adapters.mAdapter.mCapturedFinishCallback
                                .onAnimationFinished(adapters.mAdapter);
                    }
                    if (adapters.mThumbnailAdapter != null) {
                        adapters.mThumbnailAdapter.mCapturedFinishCallback
                                .onAnimationFinished(adapters.mThumbnailAdapter);
                    }
                    if (DEBUG_REMOTE_ANIMATIONS) Slog.d(TAG, "\t" + adapters.mAppWindowToken);
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

    /**
     * Contains information about a remote-animation for one AppWindowToken. This keeps track of,
     * potentially, multiple animating surfaces (AdapterWrappers) associated with one
     * Window/Transition. For example, a change transition has an adapter controller for the
     * main window and an adapter controlling the start-state snapshot.
     * <p>
     * This can be thought of as a bridge between the information that the remote animator sees (via
     * {@link RemoteAnimationTarget}) and what the server sees (the
     * {@link RemoteAnimationAdapterWrapper}(s) interfacing with the moving surfaces).
     */
    public class RemoteAnimationRecord {
        RemoteAnimationAdapterWrapper mAdapter;
        RemoteAnimationAdapterWrapper mThumbnailAdapter = null;
        RemoteAnimationTarget mTarget;
        final AppWindowToken mAppWindowToken;
        final Rect mStartBounds;

        RemoteAnimationRecord(AppWindowToken appWindowToken, Point endPos, Rect endBounds,
                Rect startBounds) {
            mAppWindowToken = appWindowToken;
            mAdapter = new RemoteAnimationAdapterWrapper(this, endPos, endBounds);
            if (startBounds != null) {
                mStartBounds = new Rect(startBounds);
                mTmpRect.set(startBounds);
                mTmpRect.offsetTo(0, 0);
                if (mRemoteAnimationAdapter.getChangeNeedsSnapshot()) {
                    mThumbnailAdapter =
                            new RemoteAnimationAdapterWrapper(this, new Point(0, 0), mTmpRect);
                }
            } else {
                mStartBounds = null;
            }
        }

        RemoteAnimationTarget createRemoteAnimationTarget() {
            final Task task = mAppWindowToken.getTask();
            final WindowState mainWindow = mAppWindowToken.findMainWindow();
            if (task == null || mainWindow == null || mAdapter == null
                    || mAdapter.mCapturedFinishCallback == null
                    || mAdapter.mCapturedLeash == null) {
                return null;
            }
            final Rect insets = new Rect();
            mainWindow.getContentInsets(insets);
            InsetUtils.addInsets(insets, mAppWindowToken.getLetterboxInsets());
            mTarget = new RemoteAnimationTarget(task.mTaskId, getMode(),
                    mAdapter.mCapturedLeash, !mAppWindowToken.fillsParent(),
                    mainWindow.mWinAnimator.mLastClipRect, insets,
                    mAppWindowToken.getPrefixOrderIndex(), mAdapter.mPosition,
                    mAdapter.mStackBounds, task.getWindowConfiguration(), false /*isNotInRecents*/,
                    mThumbnailAdapter != null ? mThumbnailAdapter.mCapturedLeash : null,
                    mStartBounds);
            return mTarget;
        }

        private int getMode() {
            final DisplayContent dc = mAppWindowToken.getDisplayContent();
            if (dc.mOpeningApps.contains(mAppWindowToken)) {
                return RemoteAnimationTarget.MODE_OPENING;
            } else if (dc.mChangingApps.contains(mAppWindowToken)) {
                return RemoteAnimationTarget.MODE_CHANGING;
            } else {
                return RemoteAnimationTarget.MODE_CLOSING;
            }
        }
    }

    private class RemoteAnimationAdapterWrapper implements AnimationAdapter {
        private final RemoteAnimationRecord mRecord;
        SurfaceControl mCapturedLeash;
        private OnAnimationFinishedCallback mCapturedFinishCallback;
        private final Point mPosition = new Point();
        private final Rect mStackBounds = new Rect();

        RemoteAnimationAdapterWrapper(RemoteAnimationRecord record, Point position,
                Rect stackBounds) {
            mRecord = record;
            mPosition.set(position.x, position.y);
            mStackBounds.set(stackBounds);
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
            t.setLayer(animationLeash, mRecord.mAppWindowToken.getPrefixOrderIndex());
            t.setPosition(animationLeash, mPosition.x, mPosition.y);
            mTmpRect.set(mStackBounds);
            mTmpRect.offsetTo(0, 0);
            t.setWindowCrop(animationLeash, mTmpRect);
            mCapturedLeash = animationLeash;
            mCapturedFinishCallback = finishCallback;
        }

        @Override
        public void onAnimationCancelled(SurfaceControl animationLeash) {
            if (mRecord.mAdapter == this) {
                mRecord.mAdapter = null;
            } else {
                mRecord.mThumbnailAdapter = null;
            }
            if (mRecord.mAdapter == null && mRecord.mThumbnailAdapter == null) {
                mPendingAnimations.remove(mRecord);
            }
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
            pw.print(prefix); pw.print("token="); pw.println(mRecord.mAppWindowToken);
            if (mRecord.mTarget != null) {
                pw.print(prefix); pw.println("Target:");
                mRecord.mTarget.dump(pw, prefix + "  ");
            } else {
                pw.print(prefix); pw.println("Target: null");
            }
        }

        @Override
        public void writeToProto(ProtoOutputStream proto) {
            final long token = proto.start(REMOTE);
            if (mRecord.mTarget != null) {
                mRecord.mTarget.writeToProto(proto, TARGET);
            }
            proto.end(token);
        }
    }
}
