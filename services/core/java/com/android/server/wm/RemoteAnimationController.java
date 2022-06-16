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

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_REMOTE_ANIMATIONS;
import static com.android.server.wm.AnimationAdapterProto.REMOTE;
import static com.android.server.wm.RemoteAnimationAdapterWrapperProto.TARGET;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
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
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLogImpl;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.FastPrintWriter;
import com.android.server.wm.SurfaceAnimator.AnimationType;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Helper class to run app animations in a remote process.
 */
class RemoteAnimationController implements DeathRecipient {
    private static final String TAG = TAG_WITH_CLASS_NAME
            ? "RemoteAnimationController" : TAG_WM;
    private static final long TIMEOUT_MS = 10000;

    private final WindowManagerService mService;
    private final DisplayContent mDisplayContent;
    private final RemoteAnimationAdapter mRemoteAnimationAdapter;
    private final ArrayList<RemoteAnimationRecord> mPendingAnimations = new ArrayList<>();
    private final ArrayList<WallpaperAnimationAdapter> mPendingWallpaperAnimations =
            new ArrayList<>();
    @VisibleForTesting
    final ArrayList<NonAppWindowAnimationAdapter> mPendingNonAppAnimations = new ArrayList<>();
    private final Handler mHandler;
    private final Runnable mTimeoutRunnable = () -> cancelAnimation("timeoutRunnable");

    private FinishedCallback mFinishedCallback;
    private final boolean mIsActivityEmbedding;
    private boolean mCanceled;
    private boolean mLinkedToDeathOfRunner;
    @Nullable
    private Runnable mOnRemoteAnimationReady;

    RemoteAnimationController(WindowManagerService service, DisplayContent displayContent,
            RemoteAnimationAdapter remoteAnimationAdapter, Handler handler,
            boolean isActivityEmbedding) {
        mService = service;
        mDisplayContent = displayContent;
        mRemoteAnimationAdapter = remoteAnimationAdapter;
        mHandler = handler;
        mIsActivityEmbedding = isActivityEmbedding;
    }

    /**
     * Creates an animation record for each individual {@link WindowContainer}.
     *
     * @param windowContainer The windows to animate.
     * @param position        The position app bounds relative to its parent.
     * @param localBounds     The bounds of the app relative to its parent.
     * @param endBounds       The end bounds after the transition, in screen coordinates.
     * @param startBounds     The start bounds before the transition, in screen coordinates.
     * @param showBackdrop    To show background behind a window during animation.
     * @return The record representing animation(s) to run on the app.
     */
    RemoteAnimationRecord createRemoteAnimationRecord(WindowContainer windowContainer,
            Point position, Rect localBounds, Rect endBounds, Rect startBounds,
            boolean showBackdrop) {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "createAnimationAdapter(): container=%s",
                windowContainer);
        final RemoteAnimationRecord adapters = new RemoteAnimationRecord(windowContainer, position,
                localBounds, endBounds, startBounds, showBackdrop);
        mPendingAnimations.add(adapters);
        return adapters;
    }

    /** Sets callback to run before starting remote animation. */
    void setOnRemoteAnimationReady(@Nullable Runnable onRemoteAnimationReady) {
        mOnRemoteAnimationReady = onRemoteAnimationReady;
    }

    /**
     * We use isFromActivityEmbedding() in the server process to tell if we're running an
     * Activity Embedding type remote animation, where animations are driven by the client.
     * This is currently supporting features like showBackdrop where we need to load App XML.
     */
    public boolean isFromActivityEmbedding() {
        return mIsActivityEmbedding;
    }

    /**
     * Called when the transition is ready to be started, and all leashes have been set up.
     */
    void goodToGo(@WindowManager.TransitionOldType int transit) {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "goodToGo()");
        if (mCanceled) {
            ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS,
                    "goodToGo(): Animation canceled already");
            onAnimationFinished();
            invokeAnimationCancelled("already_cancelled");
            return;
        }

        // Scale the timeout with the animator scale the controlling app is using.
        mHandler.postDelayed(mTimeoutRunnable,
                (long) (TIMEOUT_MS * mService.getCurrentAnimatorScale()));
        mFinishedCallback = new FinishedCallback(this);

        // Create the app targets
        final RemoteAnimationTarget[] appTargets = createAppAnimations();
        if (appTargets.length == 0 && !AppTransition.isKeyguardOccludeTransitOld(transit)) {
            // Keyguard occlude transition can be executed before the occluding activity becomes
            // visible. Even in this case, KeyguardService expects to receive binder call, so we
            // don't cancel remote animation.
            ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS,
                    "goodToGo(): No apps to animate, mPendingAnimations=%d",
                    mPendingAnimations.size());
            onAnimationFinished();
            invokeAnimationCancelled("no_app_targets");
            return;
        }

        if (mOnRemoteAnimationReady != null) {
            mOnRemoteAnimationReady.run();
            mOnRemoteAnimationReady = null;
        }

        // Create the remote wallpaper animation targets (if any)
        final RemoteAnimationTarget[] wallpaperTargets = createWallpaperAnimations();

        // Create the remote non app animation targets (if any)
        final RemoteAnimationTarget[] nonAppTargets = createNonAppWindowAnimations(transit);

        mService.mAnimator.addAfterPrepareSurfacesRunnable(() -> {
            try {
                linkToDeathOfRunner();
                ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "goodToGo(): onAnimationStart,"
                                + " transit=%s, apps=%d, wallpapers=%d, nonApps=%d",
                        AppTransition.appTransitionOldToString(transit), appTargets.length,
                        wallpaperTargets.length, nonAppTargets.length);
                mRemoteAnimationAdapter.getRunner().onAnimationStart(transit, appTargets,
                        wallpaperTargets, nonAppTargets, mFinishedCallback);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to start remote animation", e);
                onAnimationFinished();
            }
            if (ProtoLogImpl.isEnabled(WM_DEBUG_REMOTE_ANIMATIONS)) {
                ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "startAnimation(): Notify animation start:");
                writeStartDebugStatement();
            }
        });
        setRunningRemoteAnimation(true);
    }

    void cancelAnimation(String reason) {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "cancelAnimation(): reason=%s", reason);
        synchronized (mService.getWindowManagerLock()) {
            if (mCanceled) {
                return;
            }
            mCanceled = true;
        }
        onAnimationFinished();
        invokeAnimationCancelled(reason);
    }

    private void writeStartDebugStatement() {
        ProtoLog.i(WM_DEBUG_REMOTE_ANIMATIONS, "Starting remote animation");
        final StringWriter sw = new StringWriter();
        final FastPrintWriter pw = new FastPrintWriter(sw);
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            mPendingAnimations.get(i).mAdapter.dump(pw, "");
        }
        pw.close();
        ProtoLog.i(WM_DEBUG_REMOTE_ANIMATIONS, "%s", sw.toString());
    }

    private RemoteAnimationTarget[] createAppAnimations() {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "createAppAnimations()");
        final ArrayList<RemoteAnimationTarget> targets = new ArrayList<>();
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            final RemoteAnimationRecord wrappers = mPendingAnimations.get(i);
            final RemoteAnimationTarget target = wrappers.createRemoteAnimationTarget();
            if (target != null) {
                ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "\tAdd container=%s",
                        wrappers.mWindowContainer);
                targets.add(target);
            } else {
                ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "\tRemove container=%s",
                        wrappers.mWindowContainer);

                // We can't really start an animation but we still need to make sure to finish the
                // pending animation that was started by SurfaceAnimator
                if (wrappers.mAdapter != null
                        && wrappers.mAdapter.mCapturedFinishCallback != null) {
                    wrappers.mAdapter.mCapturedFinishCallback
                            .onAnimationFinished(wrappers.mAdapter.mAnimationType,
                                    wrappers.mAdapter);
                }
                if (wrappers.mThumbnailAdapter != null
                        && wrappers.mThumbnailAdapter.mCapturedFinishCallback != null) {
                    wrappers.mThumbnailAdapter.mCapturedFinishCallback
                            .onAnimationFinished(wrappers.mThumbnailAdapter.mAnimationType,
                                    wrappers.mThumbnailAdapter);
                }
                mPendingAnimations.remove(i);
            }
        }
        return targets.toArray(new RemoteAnimationTarget[targets.size()]);
    }

    private RemoteAnimationTarget[] createWallpaperAnimations() {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "createWallpaperAnimations()");
        return WallpaperAnimationAdapter.startWallpaperAnimations(mDisplayContent,
                mRemoteAnimationAdapter.getDuration(),
                mRemoteAnimationAdapter.getStatusBarTransitionDelay(),
                adapter -> {
                    synchronized (mService.mGlobalLock) {
                        // If the wallpaper animation is canceled, continue with the app animation
                        mPendingWallpaperAnimations.remove(adapter);
                    }
                }, mPendingWallpaperAnimations);
    }

    private RemoteAnimationTarget[] createNonAppWindowAnimations(
            @WindowManager.TransitionOldType int transit) {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "createNonAppWindowAnimations()");
        return NonAppWindowAnimationAdapter.startNonAppWindowAnimations(mService,
                mDisplayContent,
                transit,
                mRemoteAnimationAdapter.getDuration(),
                mRemoteAnimationAdapter.getStatusBarTransitionDelay(),
                mPendingNonAppAnimations);
    }

    private void onAnimationFinished() {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "onAnimationFinished(): mPendingAnimations=%d",
                mPendingAnimations.size());
        mHandler.removeCallbacks(mTimeoutRunnable);
        synchronized (mService.mGlobalLock) {
            unlinkToDeathOfRunner();
            releaseFinishedCallback();
            mService.openSurfaceTransaction();
            try {
                ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS,
                        "onAnimationFinished(): Notify animation finished:");
                for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
                    final RemoteAnimationRecord adapters = mPendingAnimations.get(i);
                    if (adapters.mAdapter != null) {
                        adapters.mAdapter.mCapturedFinishCallback
                                .onAnimationFinished(adapters.mAdapter.mAnimationType,
                                        adapters.mAdapter);
                    }
                    if (adapters.mThumbnailAdapter != null) {
                        adapters.mThumbnailAdapter.mCapturedFinishCallback
                                .onAnimationFinished(adapters.mThumbnailAdapter.mAnimationType,
                                        adapters.mThumbnailAdapter);
                    }
                    mPendingAnimations.remove(i);
                    ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "\tcontainer=%s",
                            adapters.mWindowContainer);
                }

                for (int i = mPendingWallpaperAnimations.size() - 1; i >= 0; i--) {
                    final WallpaperAnimationAdapter adapter = mPendingWallpaperAnimations.get(i);
                    adapter.getLeashFinishedCallback().onAnimationFinished(
                            adapter.getLastAnimationType(), adapter);
                    mPendingWallpaperAnimations.remove(i);
                    ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "\twallpaper=%s", adapter.getToken());
                }

                for (int i = mPendingNonAppAnimations.size() - 1; i >= 0; i--) {
                    final NonAppWindowAnimationAdapter adapter = mPendingNonAppAnimations.get(i);
                    adapter.getLeashFinishedCallback().onAnimationFinished(
                            adapter.getLastAnimationType(), adapter);
                    mPendingNonAppAnimations.remove(i);
                    ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "\tnonApp=%s",
                            adapter.getWindowContainer());
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to finish remote animation", e);
                throw e;
            } finally {
                mService.closeSurfaceTransaction("RemoteAnimationController#finished");
            }
        }
        // Reset input for all activities when the remote animation is finished.
        final Consumer<ActivityRecord> updateActivities =
                activity -> activity.setDropInputForAnimation(false);
        mDisplayContent.forAllActivities(updateActivities);
        setRunningRemoteAnimation(false);
        ProtoLog.i(WM_DEBUG_REMOTE_ANIMATIONS, "Finishing remote animation");
    }

    private void invokeAnimationCancelled(String reason) {
        ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "cancelAnimation(): reason=%s", reason);
        final boolean isKeyguardOccluded = mDisplayContent.isKeyguardOccluded();

        try {
            mRemoteAnimationAdapter.getRunner().onAnimationCancelled(isKeyguardOccluded);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to notify cancel", e);
        }
        mOnRemoteAnimationReady = null;
    }

    private void releaseFinishedCallback() {
        if (mFinishedCallback != null) {
            mFinishedCallback.release();
            mFinishedCallback = null;
        }
    }

    private void setRunningRemoteAnimation(boolean running) {
        final int pid = mRemoteAnimationAdapter.getCallingPid();
        final int uid = mRemoteAnimationAdapter.getCallingUid();

        if (pid == 0) {
            throw new RuntimeException("Calling pid of remote animation was null");
        }
        final WindowProcessController wpc = mService.mAtmService.getProcessController(pid, uid);
        if (wpc == null) {
            Slog.w(TAG, "Unable to find process with pid=" + pid + " uid=" + uid);
            return;
        }
        wpc.setRunningRemoteAnimation(running);
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
            ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "app-onAnimationFinished(): mOuter=%s", mOuter);
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
            ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "app-release(): mOuter=%s", mOuter);
            mOuter = null;
        }
    };

    /**
     * Contains information about a remote-animation for one WindowContainer. This keeps track of,
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
        final WindowContainer mWindowContainer;
        final Rect mStartBounds;
        final boolean mShowBackdrop;
        @ColorInt int mBackdropColor = 0;
        private @RemoteAnimationTarget.Mode int mMode = RemoteAnimationTarget.MODE_CHANGING;

        RemoteAnimationRecord(WindowContainer windowContainer, Point endPos, Rect localBounds,
                Rect endBounds, Rect startBounds, boolean showBackdrop) {
            mWindowContainer = windowContainer;
            mShowBackdrop = showBackdrop;
            if (startBounds != null) {
                mStartBounds = new Rect(startBounds);
                mAdapter = new RemoteAnimationAdapterWrapper(this, endPos, localBounds, endBounds,
                        mStartBounds, mShowBackdrop);
                if (mRemoteAnimationAdapter.getChangeNeedsSnapshot()) {
                    final Rect thumbnailLocalBounds = new Rect(startBounds);
                    thumbnailLocalBounds.offsetTo(0, 0);
                    // Snapshot is located at (0,0) of the animation leash. It doesn't have size
                    // change, so the startBounds is its end bounds, and no start bounds for it.
                    mThumbnailAdapter = new RemoteAnimationAdapterWrapper(this, new Point(0, 0),
                            thumbnailLocalBounds, startBounds, new Rect(), mShowBackdrop);
                }
            } else {
                mAdapter = new RemoteAnimationAdapterWrapper(this, endPos, localBounds, endBounds,
                        new Rect(), mShowBackdrop);
                mStartBounds = null;
            }
        }

        void setBackDropColor(@ColorInt int backdropColor) {
            mBackdropColor = backdropColor;
        }

        RemoteAnimationTarget createRemoteAnimationTarget() {
            if (mAdapter == null
                    || mAdapter.mCapturedFinishCallback == null
                    || mAdapter.mCapturedLeash == null) {
                return null;
            }
            mTarget = mWindowContainer.createRemoteAnimationTarget(this);
            return mTarget;
        }

        void setMode(@RemoteAnimationTarget.Mode int mode) {
            mMode = mode;
        }

        int getMode() {
            return mMode;
        }

        /** Whether its parent is also an animation target in the same transition. */
        boolean hasAnimatingParent() {
            // mOpeningApps and mClosingApps are only activities, so only need to check
            // mChangingContainers.
            for (int i = mDisplayContent.mChangingContainers.size() - 1; i >= 0; i--) {
                if (mWindowContainer.isDescendantOf(
                        mDisplayContent.mChangingContainers.valueAt(i))) {
                    return true;
                }
            }
            return false;
        }
    }

    class RemoteAnimationAdapterWrapper implements AnimationAdapter {
        private final RemoteAnimationRecord mRecord;
        SurfaceControl mCapturedLeash;
        private OnAnimationFinishedCallback mCapturedFinishCallback;
        private @AnimationType int mAnimationType;
        final Point mPosition = new Point();
        final Rect mLocalBounds;
        final Rect mEndBounds = new Rect();
        final Rect mStartBounds = new Rect();
        final boolean mShowBackdrop;

        RemoteAnimationAdapterWrapper(RemoteAnimationRecord record, Point position,
                Rect localBounds, Rect endBounds, Rect startBounds, boolean showBackdrop) {
            mRecord = record;
            mPosition.set(position.x, position.y);
            mLocalBounds = localBounds;
            mEndBounds.set(endBounds);
            mStartBounds.set(startBounds);
            mShowBackdrop = showBackdrop;
        }

        @Override
        @ColorInt
        public int getBackgroundColor() {
            return mRecord.mBackdropColor;
        }

        @Override
        public boolean getShowBackground() {
            return mShowBackdrop;
        }

        @Override
        public boolean getShowWallpaper() {
            return false;
        }

        @Override
        public void startAnimation(SurfaceControl animationLeash, Transaction t,
                @AnimationType int type, @NonNull OnAnimationFinishedCallback finishCallback) {
            ProtoLog.d(WM_DEBUG_REMOTE_ANIMATIONS, "startAnimation");

            if (mStartBounds.isEmpty()) {
                // Restore position and stack crop until client has a chance to modify it.
                t.setPosition(animationLeash, mPosition.x, mPosition.y);
                t.setWindowCrop(animationLeash, mEndBounds.width(), mEndBounds.height());
            } else {
                // Offset the change animation leash to the relative start position in parent.
                // (mPosition) is the relative end position in parent container.
                // (mStartBounds - mEndBounds) is the position difference between start and end.
                // (mPosition + mStartBounds - mEndBounds) will be the relative start position.
                t.setPosition(animationLeash, mPosition.x + mStartBounds.left - mEndBounds.left,
                        mPosition.y + mStartBounds.top - mEndBounds.top);
                t.setWindowCrop(animationLeash, mStartBounds.width(), mStartBounds.height());
            }
            mCapturedLeash = animationLeash;
            mCapturedFinishCallback = finishCallback;
            mAnimationType = type;
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
                cancelAnimation("allAppAnimationsCanceled");
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
            pw.print(prefix); pw.print("container="); pw.println(mRecord.mWindowContainer);
            if (mRecord.mTarget != null) {
                pw.print(prefix); pw.println("Target:");
                mRecord.mTarget.dump(pw, prefix + "  ");
            } else {
                pw.print(prefix); pw.println("Target: null");
            }
        }

        @Override
        public void dumpDebug(ProtoOutputStream proto) {
            final long token = proto.start(REMOTE);
            if (mRecord.mTarget != null) {
                mRecord.mTarget.dumpDebug(proto, TARGET);
            }
            proto.end(token);
        }
    }
}
