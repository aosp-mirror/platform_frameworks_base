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

package com.android.server.wm;

import static android.app.ActivityOptions.ANIM_OPEN_CROSS_PROFILE_APPS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.INPUT_CONSUMER_RECENTS_ANIMATION;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_IS_RECENTS;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_LOCKED;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.TransitionFlags;
import static android.view.WindowManager.TransitionType;
import static android.view.WindowManager.transitTypeToString;
import static android.window.TransitionInfo.FLAG_DISPLAY_HAS_ALERT_WINDOWS;
import static android.window.TransitionInfo.FLAG_FILLS_TASK;
import static android.window.TransitionInfo.FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY;
import static android.window.TransitionInfo.FLAG_IS_BEHIND_STARTING_WINDOW;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;
import static android.window.TransitionInfo.FLAG_IS_INPUT_METHOD;
import static android.window.TransitionInfo.FLAG_IS_VOICE_INTERACTION;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;
import static android.window.TransitionInfo.FLAG_NO_ANIMATION;
import static android.window.TransitionInfo.FLAG_OCCLUDES_KEYGUARD;
import static android.window.TransitionInfo.FLAG_SHOW_WALLPAPER;
import static android.window.TransitionInfo.FLAG_TRANSLUCENT;
import static android.window.TransitionInfo.FLAG_WILL_IME_SHOWN;

import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_RECENTS_ANIM;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_SPLASH_SCREEN;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_WINDOWS_DRAWN;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.RemoteTransition;
import android.window.ScreenCapture;
import android.window.TransitionInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.policy.TransitionAnimation;
import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Represents a logical transition.
 * @see TransitionController
 */
class Transition implements BLASTSyncEngine.TransactionReadyListener {
    private static final String TAG = "Transition";
    private static final String TRACE_NAME_PLAY_TRANSITION = "PlayTransition";

    /** The default package for resources */
    private static final String DEFAULT_PACKAGE = "android";

    /** The transition has been created but isn't collecting yet. */
    private static final int STATE_PENDING = -1;

    /** The transition has been created and is collecting, but hasn't formally started. */
    private static final int STATE_COLLECTING = 0;

    /**
     * The transition has formally started. It is still collecting but will stop once all
     * participants are ready to animate (finished drawing).
     */
    private static final int STATE_STARTED = 1;

    /**
     * This transition is currently playing its animation and can no longer collect or be changed.
     */
    private static final int STATE_PLAYING = 2;

    /**
     * This transition is aborting or has aborted. No animation will play nor will anything get
     * sent to the player.
     */
    private static final int STATE_ABORT = 3;

    /**
     * This transition has finished playing successfully.
     */
    private static final int STATE_FINISHED = 4;

    @IntDef(prefix = { "STATE_" }, value = {
            STATE_PENDING,
            STATE_COLLECTING,
            STATE_STARTED,
            STATE_PLAYING,
            STATE_ABORT,
            STATE_FINISHED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TransitionState {}

    final @TransitionType int mType;
    private int mSyncId = -1;
    private @TransitionFlags int mFlags;
    private final TransitionController mController;
    private final BLASTSyncEngine mSyncEngine;
    private final Token mToken;
    private RemoteTransition mRemoteTransition = null;

    /** Only use for clean-up after binder death! */
    private SurfaceControl.Transaction mStartTransaction = null;
    private SurfaceControl.Transaction mFinishTransaction = null;

    /**
     * Contains change infos for both participants and all remote-animatable ancestors. The
     * ancestors can be the promotion candidates so their start-states need to be captured.
     * @see #getAnimatableParent
     */
    final ArrayMap<WindowContainer, ChangeInfo> mChanges = new ArrayMap<>();

    /** The collected participants in the transition. */
    final ArraySet<WindowContainer> mParticipants = new ArraySet<>();

    /** The final animation targets derived from participants after promotion. */
    private ArrayList<ChangeInfo> mTargets;

    /** The displays that this transition is running on. */
    private final ArrayList<DisplayContent> mTargetDisplays = new ArrayList<>();

    /**
     * Set of participating windowtokens (activity/wallpaper) which are visible at the end of
     * the transition animation.
     */
    private final ArraySet<WindowToken> mVisibleAtTransitionEndTokens = new ArraySet<>();

    /**
     * Set of transient activities (lifecycle initially tied to this transition) and their
     * restore-below tasks.
     */
    private ArrayMap<ActivityRecord, Task> mTransientLaunches = null;

    /** Custom activity-level animation options and callbacks. */
    private TransitionInfo.AnimationOptions mOverrideOptions;
    private IRemoteCallback mClientAnimationStartCallback = null;
    private IRemoteCallback mClientAnimationFinishCallback = null;

    private @TransitionState int mState = STATE_PENDING;
    private final ReadyTracker mReadyTracker = new ReadyTracker();

    // TODO(b/188595497): remove when not needed.
    /** @see RecentsAnimationController#mNavigationBarAttachedToApp */
    private boolean mNavBarAttachedToApp = false;
    private int mRecentsDisplayId = INVALID_DISPLAY;

    /** The delay for light bar appearance animation. */
    long mStatusBarTransitionDelay;

    /** @see #setCanPipOnFinish */
    private boolean mCanPipOnFinish = true;

    private boolean mIsSeamlessRotation = false;
    private IContainerFreezer mContainerFreezer = null;

    Transition(@TransitionType int type, @TransitionFlags int flags,
            TransitionController controller, BLASTSyncEngine syncEngine) {
        mType = type;
        mFlags = flags;
        mController = controller;
        mSyncEngine = syncEngine;
        mToken = new Token(this);

        controller.mTransitionTracer.logState(this);
    }

    @Nullable
    static Transition fromBinder(@Nullable IBinder token) {
        if (token == null) return null;
        try {
            return ((Token) token).mTransition.get();
        } catch (ClassCastException e) {
            Slog.w(TAG, "Invalid transition token: " + token, e);
            return null;
        }
    }

    @NonNull
    IBinder getToken() {
        return mToken;
    }

    void addFlag(int flag) {
        mFlags |= flag;
    }

    /** Records an activity as transient-launch. This activity must be already collected. */
    void setTransientLaunch(@NonNull ActivityRecord activity, @Nullable Task restoreBelow) {
        if (mTransientLaunches == null) {
            mTransientLaunches = new ArrayMap<>();
        }
        mTransientLaunches.put(activity, restoreBelow);
        setTransientLaunchToChanges(activity);

        if (restoreBelow != null) {
            final ChangeInfo info = mChanges.get(restoreBelow);
            if (info != null) {
                info.mFlags |= ChangeInfo.FLAG_ABOVE_TRANSIENT_LAUNCH;
            }
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Transition %d: Set %s as "
                + "transient-launch", mSyncId, activity);
    }

    boolean isTransientHide(@NonNull Task task) {
        if (mTransientLaunches == null) return false;
        for (int i = 0; i < mTransientLaunches.size(); ++i) {
            if (mTransientLaunches.valueAt(i) == task) {
                return true;
            }
        }
        return false;
    }

    boolean isTransientLaunch(@NonNull ActivityRecord activity) {
        return mTransientLaunches != null && mTransientLaunches.containsKey(activity);
    }

    Task getTransientLaunchRestoreTarget(@NonNull WindowContainer container) {
        if (mTransientLaunches == null) return null;
        for (int i = 0; i < mTransientLaunches.size(); ++i) {
            if (mTransientLaunches.keyAt(i).isDescendantOf(container)) {
                return mTransientLaunches.valueAt(i);
            }
        }
        return null;
    }

    boolean isOnDisplay(@NonNull DisplayContent dc) {
        return mTargetDisplays.contains(dc);
    }

    /** Set a transition to be a seamless-rotation. */
    void setSeamlessRotation(@NonNull WindowContainer wc) {
        final ChangeInfo info = mChanges.get(wc);
        if (info == null) return;
        info.mFlags = info.mFlags | ChangeInfo.FLAG_SEAMLESS_ROTATION;
        onSeamlessRotating(wc.getDisplayContent());
    }

    /**
     * Called when it's been determined that this is transition is a seamless rotation. This should
     * be called before any WM changes have happened.
     */
    void onSeamlessRotating(@NonNull DisplayContent dc) {
        // Don't need to do anything special if everything is using BLAST sync already.
        if (mSyncEngine.getSyncSet(mSyncId).mSyncMethod == BLASTSyncEngine.METHOD_BLAST) return;
        if (mContainerFreezer == null) {
            mContainerFreezer = new ScreenshotFreezer();
        }
        final WindowState top = dc.getDisplayPolicy().getTopFullscreenOpaqueWindow();
        if (top != null) {
            mIsSeamlessRotation = true;
            top.mSyncMethodOverride = BLASTSyncEngine.METHOD_BLAST;
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Override sync-method for %s "
                    + "because seamless rotating", top.getName());
        }
    }

    /**
     * Only set flag to the parent tasks and activity itself.
     */
    private void setTransientLaunchToChanges(@NonNull WindowContainer wc) {
        for (WindowContainer curr = wc; curr != null && mChanges.containsKey(curr);
                curr = curr.getParent()) {
            if (curr.asTask() == null && curr.asActivityRecord() == null) {
                return;
            }
            final ChangeInfo info = mChanges.get(curr);
            info.mFlags = info.mFlags | ChangeInfo.FLAG_TRANSIENT_LAUNCH;
        }
    }

    /** Only for testing. */
    void setContainerFreezer(IContainerFreezer freezer) {
        mContainerFreezer = freezer;
    }

    @TransitionState
    int getState() {
        return mState;
    }

    @VisibleForTesting
    int getSyncId() {
        return mSyncId;
    }

    @TransitionFlags
    int getFlags() {
        return mFlags;
    }

    @VisibleForTesting
    SurfaceControl.Transaction getStartTransaction() {
        return mStartTransaction;
    }

    @VisibleForTesting
    SurfaceControl.Transaction getFinishTransaction() {
        return mFinishTransaction;
    }

    boolean isCollecting() {
        return mState == STATE_COLLECTING || mState == STATE_STARTED;
    }

    @VisibleForTesting
    void startCollecting(long timeoutMs) {
        startCollecting(timeoutMs, TransitionController.SYNC_METHOD);
    }

    /** Starts collecting phase. Once this starts, all relevant surface operations are sync. */
    void startCollecting(long timeoutMs, int method) {
        if (mState != STATE_PENDING) {
            throw new IllegalStateException("Attempting to re-use a transition");
        }
        mState = STATE_COLLECTING;
        mSyncId = mSyncEngine.startSyncSet(this, timeoutMs, TAG, method);

        mController.mTransitionTracer.logState(this);
    }

    /**
     * Formally starts the transition. Participants can be collected before this is started,
     * but this won't consider itself ready until started -- even if all the participants have
     * drawn.
     */
    void start() {
        if (mState < STATE_COLLECTING) {
            throw new IllegalStateException("Can't start Transition which isn't collecting.");
        } else if (mState >= STATE_STARTED) {
            Slog.w(TAG, "Transition already started: " + mSyncId);
        }
        mState = STATE_STARTED;
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Starting Transition %d",
                mSyncId);
        applyReady();

        mController.mTransitionTracer.logState(this);
    }

    /**
     * Adds wc to set of WindowContainers participating in this transition.
     */
    void collect(@NonNull WindowContainer wc) {
        if (mState < STATE_COLLECTING) {
            throw new IllegalStateException("Transition hasn't started collecting.");
        }
        if (!isCollecting()) {
            // Too late, transition already started playing, so don't collect.
            return;
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Collecting in transition %d: %s",
                mSyncId, wc);
        // "snapshot" all parents (as potential promotion targets). Do this before checking
        // if this is already a participant in case it has since been re-parented.
        for (WindowContainer<?> curr = getAnimatableParent(wc);
                curr != null && !mChanges.containsKey(curr);
                curr = getAnimatableParent(curr)) {
            mChanges.put(curr, new ChangeInfo(curr));
            if (isReadyGroup(curr)) {
                mReadyTracker.addGroup(curr);
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, " Creating Ready-group for"
                                + " Transition %d with root=%s", mSyncId, curr);
            }
        }
        if (mParticipants.contains(wc)) return;
        // Wallpaper is like in a static drawn state unless display may have changes, so exclude
        // the case to reduce transition latency waiting for the unchanged wallpaper to redraw.
        final boolean needSyncDraw = !isWallpaper(wc) || mParticipants.contains(wc.mDisplayContent);
        if (needSyncDraw) {
            mSyncEngine.addToSyncSet(mSyncId, wc);
        }
        ChangeInfo info = mChanges.get(wc);
        if (info == null) {
            info = new ChangeInfo(wc);
            mChanges.put(wc, info);
        }
        mParticipants.add(wc);
        if (wc.getDisplayContent() != null && !mTargetDisplays.contains(wc.getDisplayContent())) {
            mTargetDisplays.add(wc.getDisplayContent());
        }
        if (info.mShowWallpaper) {
            // Collect the wallpaper token (for isWallpaper(wc)) so it is part of the sync set.
            final WindowState wallpaper =
                    wc.getDisplayContent().mWallpaperController.getTopVisibleWallpaper();
            if (wallpaper != null) {
                collect(wallpaper.mToken);
            }
        }
    }

    /**
     * Records wc as changing its state of existence during this transition. For example, a new
     * task is considered an existence change while moving a task to front is not. wc is added
     * to the collection set. Note: Existence is NOT a promotable characteristic.
     *
     * This must be explicitly recorded because there are o number of situations where the actual
     * hierarchy operations don't align with the intent (eg. re-using a task with a new activity
     * or waiting until after the animation to close).
     */
    void collectExistenceChange(@NonNull WindowContainer wc) {
        if (mState >= STATE_PLAYING) {
            // Too late to collect. Don't check too-early here since `collect` will check that.
            return;
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Existence Changed in transition %d:"
                + " %s", mSyncId, wc);
        collect(wc);
        mChanges.get(wc).mExistenceChanged = true;
    }

    /**
     * Records that a particular container is changing visibly (ie. something about it is changing
     * while it remains visible). This only effects windows that are already in the collecting
     * transition.
     */
    void collectVisibleChange(WindowContainer wc) {
        if (mSyncEngine.getSyncSet(mSyncId).mSyncMethod == BLASTSyncEngine.METHOD_BLAST) {
            // All windows are synced already.
            return;
        }
        if (!isInTransition(wc)) return;

        if (mContainerFreezer == null) {
            mContainerFreezer = new ScreenshotFreezer();
        }
        Transition.ChangeInfo change = mChanges.get(wc);
        if (change == null || !change.mVisible || !wc.isVisibleRequested()) return;
        // Note: many more tests have already been done by caller.
        mContainerFreezer.freeze(wc, change.mAbsoluteBounds);
    }

    /**
     * Records that a particular container has been reparented. This only effects windows that have
     * already been collected in the transition. This should be called before reparenting because
     * the old parent may be removed during reparenting, for example:
     * {@link Task#shouldRemoveSelfOnLastChildRemoval}
     */
    void collectReparentChange(@NonNull WindowContainer wc, @NonNull WindowContainer newParent) {
        if (!mChanges.containsKey(wc)) {
            // #collectReparentChange() will be called when the window is reparented. Skip if it is
            // a window that has not been collected, which means we don't care about this window for
            // the current transition.
            return;
        }
        final ChangeInfo change = mChanges.get(wc);
        // Use the current common ancestor if there are multiple reparent, and the original parent
        // has been detached. Otherwise, use the original parent before the transition.
        final WindowContainer prevParent =
                change.mStartParent == null || change.mStartParent.isAttached()
                        ? change.mStartParent
                        : change.mCommonAncestor;
        if (prevParent == null || !prevParent.isAttached()) {
            Slog.w(TAG, "Trying to collect reparenting of a window after the previous parent has"
                    + " been detached: " + wc);
            return;
        }
        if (prevParent == newParent) {
            Slog.w(TAG, "Trying to collect reparenting of a window that has not been reparented: "
                    + wc);
            return;
        }
        if (!newParent.isAttached()) {
            Slog.w(TAG, "Trying to collect reparenting of a window that is not attached after"
                    + " reparenting: " + wc);
            return;
        }
        WindowContainer ancestor = newParent;
        while (prevParent != ancestor && !prevParent.isDescendantOf(ancestor)) {
            ancestor = ancestor.getParent();
        }
        change.mCommonAncestor = ancestor;
    }

    /**
     * @return {@code true} if `wc` is a participant or is a descendant of one.
     */
    boolean isInTransition(WindowContainer wc) {
        for (WindowContainer p = wc; p != null; p = p.getParent()) {
            if (mParticipants.contains(p)) return true;
        }
        return false;
    }

    /**
     * Specifies configuration change explicitly for the window container, so it can be chosen as
     * transition target. This is usually used with transition mode
     * {@link android.view.WindowManager#TRANSIT_CHANGE}.
     */
    void setKnownConfigChanges(WindowContainer<?> wc, @ActivityInfo.Config int changes) {
        final ChangeInfo changeInfo = mChanges.get(wc);
        if (changeInfo != null) {
            changeInfo.mKnownConfigChanges = changes;
        }
    }

    private void sendRemoteCallback(@Nullable IRemoteCallback callback) {
        if (callback == null) return;
        mController.mAtm.mH.sendMessage(PooledLambda.obtainMessage(cb -> {
            try {
                cb.sendResult(null);
            } catch (RemoteException e) { }
        }, callback));
    }

    /**
     * Set animation options for collecting transition by ActivityRecord.
     * @param options AnimationOptions captured from ActivityOptions
     */
    void setOverrideAnimation(TransitionInfo.AnimationOptions options,
            @Nullable IRemoteCallback startCallback, @Nullable IRemoteCallback finishCallback) {
        if (!isCollecting()) return;
        mOverrideOptions = options;
        sendRemoteCallback(mClientAnimationStartCallback);
        mClientAnimationStartCallback = startCallback;
        mClientAnimationFinishCallback = finishCallback;
    }

    /**
     * Call this when all known changes related to this transition have been applied. Until
     * all participants have finished drawing, the transition can still collect participants.
     *
     * If this is called before the transition is started, it will be deferred until start.
     *
     * @param wc A reference point to determine which ready-group to update. For now, each display
     *           has its own ready-group, so this is used to look-up which display to mark ready.
     *           The transition will wait for all groups to be ready.
     */
    void setReady(WindowContainer wc, boolean ready) {
        if (!isCollecting() || mSyncId < 0) return;
        mReadyTracker.setReadyFrom(wc, ready);
        applyReady();
    }

    private void applyReady() {
        if (mState < STATE_STARTED) return;
        final boolean ready = mReadyTracker.allReady();
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                "Set transition ready=%b %d", ready, mSyncId);
        mSyncEngine.setReady(mSyncId, ready);
    }

    /**
     * Sets all possible ready groups to ready.
     * @see ReadyTracker#setAllReady.
     */
    void setAllReady() {
        if (!isCollecting() || mSyncId < 0) return;
        mReadyTracker.setAllReady();
        applyReady();
    }

    @VisibleForTesting
    boolean allReady() {
        return mReadyTracker.allReady();
    }

    /**
     * Build a transaction that "resets" all the re-parenting and layer changes. This is
     * intended to be applied at the end of the transition but before the finish callback. This
     * needs to be passed/applied in shell because until finish is called, shell owns the surfaces.
     * Additionally, this gives shell the ability to better deal with merged transitions.
     */
    private void buildFinishTransaction(SurfaceControl.Transaction t, SurfaceControl rootLeash) {
        final Point tmpPos = new Point();
        // usually only size 1
        final ArraySet<DisplayContent> displays = new ArraySet<>();
        for (int i = mTargets.size() - 1; i >= 0; --i) {
            final WindowContainer target = mTargets.get(i).mContainer;
            if (target.getParent() != null) {
                final SurfaceControl targetLeash = getLeashSurface(target, null /* t */);
                final SurfaceControl origParent = getOrigParentSurface(target);
                // Ensure surfaceControls are re-parented back into the hierarchy.
                t.reparent(targetLeash, origParent);
                t.setLayer(targetLeash, target.getLastLayer());
                target.getRelativePosition(tmpPos);
                t.setPosition(targetLeash, tmpPos.x, tmpPos.y);
                // No need to clip the display in case seeing the clipped content when during the
                // display rotation. No need to clip activities because they rely on clipping on
                // task layers.
                if (target.asTaskFragment() == null) {
                    t.setCrop(targetLeash, null /* crop */);
                } else {
                    // Crop to the resolved override bounds.
                    final Rect clipRect = target.getResolvedOverrideBounds();
                    t.setWindowCrop(targetLeash, clipRect.width(), clipRect.height());
                }
                t.setCornerRadius(targetLeash, 0);
                t.setShadowRadius(targetLeash, 0);
                t.setMatrix(targetLeash, 1, 0, 0, 1);
                t.setAlpha(targetLeash, 1);
                // The bounds sent to the transition is always a real bounds. This means we lose
                // information about "null" bounds (inheriting from parent). Core will fix-up
                // non-organized window surface bounds; however, since Core can't touch organized
                // surfaces, add the "inherit from parent" restoration here.
                if (target.isOrganized() && target.matchParentBounds()) {
                    t.setWindowCrop(targetLeash, -1, -1);
                }
                displays.add(target.getDisplayContent());
            }
        }
        // Remove screenshot layers if necessary
        if (mContainerFreezer != null) {
            mContainerFreezer.cleanUp(t);
        }
        // Need to update layers on involved displays since they were all paused while
        // the animation played. This puts the layers back into the correct order.
        mController.mBuildingFinishLayers = true;
        try {
            for (int i = displays.size() - 1; i >= 0; --i) {
                if (displays.valueAt(i) == null) continue;
                displays.valueAt(i).assignChildLayers(t);
            }
        } finally {
            mController.mBuildingFinishLayers = false;
        }
        if (rootLeash.isValid()) {
            t.reparent(rootLeash, null);
        }
    }

    /**
     * Set whether this transition can start a pip-enter transition when finished. This is usually
     * true, but gets set to false when recents decides that it wants to finish its animation but
     * not actually finish its animation (yeah...).
     */
    void setCanPipOnFinish(boolean canPipOnFinish) {
        mCanPipOnFinish = canPipOnFinish;
    }

    private boolean didCommitTransientLaunch() {
        if (mTransientLaunches == null) return false;
        for (int j = 0; j < mTransientLaunches.size(); ++j) {
            if (mTransientLaunches.keyAt(j).isVisibleRequested()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if pip-entry is possible after finishing and enter-pip if it is.
     *
     * @return true if we are *guaranteed* to enter-pip. This means we return false if there's
     *         a chance we won't thus legacy-entry (via pause+userLeaving) will return false.
     */
    private boolean checkEnterPipOnFinish(@NonNull ActivityRecord ar) {
        if (!mCanPipOnFinish || !ar.isVisible() || ar.getTask() == null) return false;

        if (ar.pictureInPictureArgs != null && ar.pictureInPictureArgs.isAutoEnterEnabled()) {
            if (didCommitTransientLaunch()) {
                // force enable pip-on-task-switch now that we've committed to actually launching
                // to the transient activity.
                ar.supportsEnterPipOnTaskSwitch = true;
            }
            return mController.mAtm.enterPictureInPictureMode(ar, ar.pictureInPictureArgs,
                    false /* fromClient */);
        }

        // Legacy pip-entry (not via isAutoEnterEnabled).
        boolean canPip = ar.getDeferHidingClient();
        if (!canPip && didCommitTransientLaunch()) {
            // force enable pip-on-task-switch now that we've committed to actually launching to the
            // transient activity, and then recalculate whether we can attempt pip.
            ar.supportsEnterPipOnTaskSwitch = true;
            canPip = ar.checkEnterPictureInPictureState(
                    "finishTransition", true /* beforeStopping */)
                    && ar.isState(RESUMED);
        }
        if (!canPip) return false;
        try {
            // Legacy PIP-enter requires pause event with user-leaving.
            mController.mAtm.mTaskSupervisor.mUserLeaving = true;
            ar.getTaskFragment().startPausing(false /* uiSleeping */,
                    null /* resuming */, "finishTransition");
        } finally {
            mController.mAtm.mTaskSupervisor.mUserLeaving = false;
        }
        // Return false anyway because there's no guarantee that the app will enter pip.
        return false;
    }

    /**
     * The transition has finished animating and is ready to finalize WM state. This should not
     * be called directly; use {@link TransitionController#finishTransition} instead.
     */
    void finishTransition() {
        if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
            Trace.asyncTraceEnd(TRACE_TAG_WINDOW_MANAGER, TRACE_NAME_PLAY_TRANSITION,
                    System.identityHashCode(this));
        }
        // Close the transactions now. They were originally copied to Shell in case we needed to
        // apply them due to a remote failure. Since we don't need to apply them anymore, free them
        // immediately.
        if (mStartTransaction != null) mStartTransaction.close();
        if (mFinishTransaction != null) mFinishTransaction.close();
        mStartTransaction = mFinishTransaction = null;
        if (mState < STATE_PLAYING) {
            throw new IllegalStateException("Can't finish a non-playing transition " + mSyncId);
        }

        boolean hasParticipatedDisplay = false;
        // Commit all going-invisible containers
        for (int i = 0; i < mParticipants.size(); ++i) {
            final WindowContainer<?> participant = mParticipants.valueAt(i);
            final ActivityRecord ar = participant.asActivityRecord();
            if (ar != null) {
                boolean visibleAtTransitionEnd = mVisibleAtTransitionEndTokens.contains(ar);
                // We need both the expected visibility AND current requested-visibility to be
                // false. If it is expected-visible but not currently visible, it means that
                // another animation is queued-up to animate this to invisibility, so we can't
                // remove the surfaces yet. If it is currently visible, but not expected-visible,
                // then doing commitVisibility here would actually be out-of-order and leave the
                // activity in a bad state.
                // TODO (b/243755838) Create a screen off transition to correct the visible status
                // of activities.
                final boolean isScreenOff = ar.mDisplayContent == null
                        || ar.mDisplayContent.getDisplayInfo().state == Display.STATE_OFF;
                if ((!visibleAtTransitionEnd || isScreenOff) && !ar.isVisibleRequested()) {
                    final boolean commitVisibility = !checkEnterPipOnFinish(ar);
                    // Avoid commit visibility if entering pip or else we will get a sudden
                    // "flash" / surface going invisible for a split second.
                    if (commitVisibility) {
                        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                                "  Commit activity becoming invisible: %s", ar);
                        final Task task = ar.getTask();
                        if (task != null && !task.isVisibleRequested()
                                && mTransientLaunches != null) {
                            // If transition is transient, then snapshots are taken at end of
                            // transition.
                            mController.mTaskSnapshotController.recordSnapshot(
                                    task, false /* allowSnapshotHome */);
                        }
                        ar.commitVisibility(false /* visible */, false /* performLayout */,
                                true /* fromTransition */);
                    }
                }
                if (mChanges.get(ar).mVisible != visibleAtTransitionEnd) {
                    // Legacy dispatch relies on this (for now).
                    ar.mEnteringAnimation = visibleAtTransitionEnd;
                } else if (mTransientLaunches != null && mTransientLaunches.containsKey(ar)
                        && ar.isVisible()) {
                    // Transient launch was committed, so report enteringAnimation
                    ar.mEnteringAnimation = true;
                }
                continue;
            }
            if (participant.asDisplayContent() != null) {
                hasParticipatedDisplay = true;
                continue;
            }
            final WallpaperWindowToken wt = participant.asWallpaperToken();
            if (wt != null) {
                final boolean visibleAtTransitionEnd = mVisibleAtTransitionEndTokens.contains(wt);
                if (!visibleAtTransitionEnd && !wt.isVisibleRequested()) {
                    ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                            "  Commit wallpaper becoming invisible: %s", wt);
                    wt.commitVisibility(false /* visible */);
                }
            }
        }
        // dispatch legacy callback in a different loop. This is because multiple legacy handlers
        // (fixed-rotation/displaycontent) make global changes, so we want to ensure that we've
        // processed all the participants first (in particular, we want to trigger pip-enter first)
        for (int i = 0; i < mParticipants.size(); ++i) {
            final ActivityRecord ar = mParticipants.valueAt(i).asActivityRecord();
            if (ar != null) {
                mController.dispatchLegacyAppTransitionFinished(ar);
            }
        }

        // Update the input-sink (touch-blocking) state now that the animation is finished.
        SurfaceControl.Transaction inputSinkTransaction = null;
        for (int i = 0; i < mParticipants.size(); ++i) {
            final ActivityRecord ar = mParticipants.valueAt(i).asActivityRecord();
            if (ar == null || !ar.isVisible() || ar.getParent() == null) continue;
            if (inputSinkTransaction == null) {
                inputSinkTransaction = new SurfaceControl.Transaction();
            }
            ar.mActivityRecordInputSink.applyChangesToSurfaceIfChanged(inputSinkTransaction);
        }
        if (inputSinkTransaction != null) inputSinkTransaction.apply();

        // Always schedule stop processing when transition finishes because activities don't
        // stop while they are in a transition thus their stop could still be pending.
        mController.mAtm.mTaskSupervisor
                .scheduleProcessStoppingAndFinishingActivitiesIfNeeded();

        sendRemoteCallback(mClientAnimationFinishCallback);

        legacyRestoreNavigationBarFromApp();

        if (mRecentsDisplayId != INVALID_DISPLAY) {
            // Clean up input monitors (for recents)
            final DisplayContent dc =
                    mController.mAtm.mRootWindowContainer.getDisplayContent(mRecentsDisplayId);
            dc.getInputMonitor().setActiveRecents(null /* activity */, null /* layer */);
        }

        for (int i = 0; i < mTargetDisplays.size(); ++i) {
            final DisplayContent dc = mTargetDisplays.get(i);
            final AsyncRotationController asyncRotationController = dc.getAsyncRotationController();
            if (asyncRotationController != null && containsChangeFor(dc, mTargets)) {
                asyncRotationController.onTransitionFinished();
            }
            if (mTransientLaunches != null) {
                InsetsControlTarget prevImeTarget = dc.getImeTarget(
                        DisplayContent.IME_TARGET_CONTROL);
                InsetsControlTarget newImeTarget = null;
                // Transient-launch activities cannot be IME target (WindowState#canBeImeTarget),
                // so re-compute in case the IME target is changed after transition.
                for (int t = 0; t < mTransientLaunches.size(); ++t) {
                    if (mTransientLaunches.keyAt(t).getDisplayContent() == dc) {
                        newImeTarget = dc.computeImeTarget(true /* updateImeTarget */);
                        break;
                    }
                }
                if (mRecentsDisplayId != INVALID_DISPLAY && prevImeTarget == newImeTarget) {
                    // Restore IME icon only when moving the original app task to front from
                    // recents, in case IME icon may missing if the moving task has already been
                    // the current focused task.
                    InputMethodManagerInternal.get().updateImeWindowStatus(
                            false /* disableImeIcon */);
                }
            }
            dc.removeImeSurfaceImmediately();
            dc.handleCompleteDeferredRemoval();
        }

        mState = STATE_FINISHED;
        mController.mTransitionTracer.logState(this);
        // Rotation change may be deferred while there is a display change transition, so check
        // again in case there is a new pending change.
        if (hasParticipatedDisplay && !mController.useShellTransitionsRotation()) {
            mController.mAtm.mWindowManager.updateRotation(false /* alwaysSendConfiguration */,
                    false /* forceRelayout */);
        }
        cleanUpInternal();
    }

    void abort() {
        // This calls back into itself via controller.abort, so just early return here.
        if (mState == STATE_ABORT) return;
        if (mState != STATE_COLLECTING && mState != STATE_STARTED) {
            throw new IllegalStateException("Too late to abort. state=" + mState);
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Aborting Transition: %d", mSyncId);
        mState = STATE_ABORT;
        // Syncengine abort will call through to onTransactionReady()
        mSyncEngine.abort(mSyncId);
        mController.dispatchLegacyAppTransitionCancelled();
    }

    void setRemoteTransition(RemoteTransition remoteTransition) {
        mRemoteTransition = remoteTransition;
    }

    RemoteTransition getRemoteTransition() {
        return mRemoteTransition;
    }

    void setNoAnimation(WindowContainer wc) {
        final ChangeInfo change = mChanges.get(wc);
        if (change == null) {
            throw new IllegalStateException("Can't set no-animation property of non-participant");
        }
        change.mFlags |= ChangeInfo.FLAG_CHANGE_NO_ANIMATION;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    static boolean containsChangeFor(WindowContainer wc, ArrayList<ChangeInfo> list) {
        for (int i = list.size() - 1; i >= 0; --i) {
            if (list.get(i).mContainer == wc) return true;
        }
        return false;
    }

    @Override
    public void onTransactionReady(int syncId, SurfaceControl.Transaction transaction) {
        if (syncId != mSyncId) {
            Slog.e(TAG, "Unexpected Sync ID " + syncId + ". Expected " + mSyncId);
            return;
        }
        if (mTargetDisplays.isEmpty()) {
            mTargetDisplays.add(mController.mAtm.mRootWindowContainer.getDefaultDisplay());
        }
        // While there can be multiple DC's involved. For now, we just use the first one as
        // the "primary" one for most things. Eventually, this will need to change, but, for the
        // time being, we don't have full cross-display transitions so it isn't a problem.
        final DisplayContent dc = mTargetDisplays.get(0);

        if (mState == STATE_ABORT) {
            mController.abort(this);
            dc.getPendingTransaction().merge(transaction);
            mSyncId = -1;
            mOverrideOptions = null;
            cleanUpInternal();
            return;
        }

        mState = STATE_PLAYING;
        mStartTransaction = transaction;
        mFinishTransaction = mController.mAtm.mWindowManager.mTransactionFactory.get();
        mController.moveToPlaying(this);

        if (dc.isKeyguardLocked()) {
            mFlags |= TRANSIT_FLAG_KEYGUARD_LOCKED;
        }

        // Check whether the participants were animated from back navigation.
        final boolean markBackAnimated = mController.mAtm.mBackNavigationController
                .containsBackAnimationTargets(this);
        // Resolve the animating targets from the participants
        mTargets = calculateTargets(mParticipants, mChanges);
        final TransitionInfo info = calculateTransitionInfo(mType, mFlags, mTargets, transaction);
        if (markBackAnimated) {
            mController.mAtm.mBackNavigationController.clearBackAnimations(mStartTransaction);
        }
        if (mOverrideOptions != null) {
            info.setAnimationOptions(mOverrideOptions);
            if (mOverrideOptions.getType() == ANIM_OPEN_CROSS_PROFILE_APPS) {
                for (int i = 0; i < mTargets.size(); ++i) {
                    final TransitionInfo.Change c = info.getChanges().get(i);
                    final ActivityRecord ar = mTargets.get(i).mContainer.asActivityRecord();
                    if (ar == null || c.getMode() != TRANSIT_OPEN) continue;
                    int flags = c.getFlags();
                    flags |= ar.mUserId == ar.mWmService.mCurrentUserId
                            ? TransitionInfo.FLAG_CROSS_PROFILE_OWNER_THUMBNAIL
                            : TransitionInfo.FLAG_CROSS_PROFILE_WORK_THUMBNAIL;
                    c.setFlags(flags);
                    break;
                }
            }
        }

        // TODO(b/188669821): Move to animation impl in shell.
        handleLegacyRecentsStartBehavior(dc, info);

        handleNonAppWindowsInTransition(dc, mType, mFlags);

        // The callback is only populated for custom activity-level client animations
        sendRemoteCallback(mClientAnimationStartCallback);

        // Manually show any activities that are visibleRequested. This is needed to properly
        // support simultaneous animation queueing/merging. Specifically, if transition A makes
        // an activity invisible, it's finishTransaction (which is applied *after* the animation)
        // will hide the activity surface. If transition B then makes the activity visible again,
        // the normal surfaceplacement logic won't add a show to this start transaction because
        // the activity visibility hasn't been committed yet. To deal with this, we have to manually
        // show here in the same way that we manually hide in finishTransaction.
        for (int i = mParticipants.size() - 1; i >= 0; --i) {
            final ActivityRecord ar = mParticipants.valueAt(i).asActivityRecord();
            if (ar == null || !ar.isVisibleRequested()) continue;
            transaction.show(ar.getSurfaceControl());

            // Also manually show any non-reported parents. This is necessary in a few cases
            // where a task is NOT organized but had its visibility changed within its direct
            // parent. An example of this is if an alternate home leaf-task HB is started atop the
            // normal home leaf-task HA: these are both in the Home root-task HR, so there will be a
            // transition containing HA and HB where HA surface is hidden. If a standard task SA is
            // launched on top, then HB finishes, no transition will happen since neither home is
            // visible. When SA finishes, the transition contains HR rather than HA. Since home
            // leaf-tasks are NOT organized, HA won't be in the transition and thus its surface
            // wouldn't be shown. Just show is safe here since all other properties will have
            // already been reset by the original hiding-transition's finishTransaction (we can't
            // show in the finishTransaction because by then the activity doesn't hide until
            // surface placement).
            for (WindowContainer p = ar.getParent(); p != null && !containsChangeFor(p, mTargets);
                    p = p.getParent()) {
                if (p.getSurfaceControl() != null) {
                    transaction.show(p.getSurfaceControl());
                }
            }
        }

        // Record windowtokens (activity/wallpaper) that are expected to be visible after the
        // transition animation. This will be used in finishTransition to prevent prematurely
        // committing visibility.
        for (int i = mParticipants.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mParticipants.valueAt(i);
            if (wc.asWindowToken() == null || !wc.isVisibleRequested()) continue;
            // don't include transient launches, though, since those are only temporarily visible.
            if (mTransientLaunches != null && wc.asActivityRecord() != null
                    && mTransientLaunches.containsKey(wc.asActivityRecord())) continue;
            mVisibleAtTransitionEndTokens.add(wc.asWindowToken());
        }

        // Take task snapshots before the animation so that we can capture IME before it gets
        // transferred. If transition is transient, IME won't be moved during the transition and
        // the tasks are still live, so we take the snapshot at the end of the transition instead.
        if (mTransientLaunches == null) {
            for (int i = mParticipants.size() - 1; i >= 0; --i) {
                final ActivityRecord ar = mParticipants.valueAt(i).asActivityRecord();
                if (ar == null || ar.isVisibleRequested() || ar.getTask() == null
                        || ar.getTask().isVisibleRequested()) continue;
                mController.mTaskSnapshotController.recordSnapshot(
                        ar.getTask(), false /* allowSnapshotHome */);
            }
        }

        // This is non-null only if display has changes. It handles the visible windows that don't
        // need to be participated in the transition.
        final AsyncRotationController controller = dc.getAsyncRotationController();
        if (controller != null && containsChangeFor(dc, mTargets)) {
            controller.setupStartTransaction(transaction);
        }
        buildFinishTransaction(mFinishTransaction, info.getRootLeash());
        if (mController.getTransitionPlayer() != null) {
            mController.dispatchLegacyAppTransitionStarting(info, mStatusBarTransitionDelay);
            try {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "Calling onTransitionReady: %s", info);
                mController.getTransitionPlayer().onTransitionReady(
                        mToken, info, transaction, mFinishTransaction);
                // Since we created root-leash but no longer reference it from core, release it now
                info.releaseAnimSurfaces();
                if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
                    Trace.asyncTraceBegin(TRACE_TAG_WINDOW_MANAGER, TRACE_NAME_PLAY_TRANSITION,
                            System.identityHashCode(this));
                }
            } catch (RemoteException e) {
                // If there's an exception when trying to send the mergedTransaction to the
                // client, we should finish and apply it here so the transactions aren't lost.
                cleanUpOnFailure();
            }
        } else {
            // No player registered, so just finish/apply immediately
            cleanUpOnFailure();
        }
        mOverrideOptions = null;

        reportStartReasonsToLogger();
    }

    /**
     * If the remote failed for any reason, use this to do any appropriate clean-up. Do not call
     * this directly, it's designed to by called by {@link TransitionController} only.
     */
    void cleanUpOnFailure() {
        // No need to clean-up if this isn't playing yet.
        if (mState < STATE_PLAYING) return;

        if (mStartTransaction != null) {
            mStartTransaction.apply();
        }
        if (mFinishTransaction != null) {
            mFinishTransaction.apply();
        }
        mController.finishTransition(mToken);
    }

    private void cleanUpInternal() {
        // Clean-up any native references.
        for (int i = 0; i < mChanges.size(); ++i) {
            final ChangeInfo ci = mChanges.valueAt(i);
            if (ci.mSnapshot != null) {
                ci.mSnapshot.release();
            }
        }
    }

    /** @see RecentsAnimationController#attachNavigationBarToApp */
    private void handleLegacyRecentsStartBehavior(DisplayContent dc, TransitionInfo info) {
        if ((mFlags & TRANSIT_FLAG_IS_RECENTS) == 0) {
            return;
        }
        mRecentsDisplayId = dc.mDisplayId;

        // Recents has an input-consumer to grab input from the "live tile" app. Set that up here
        final InputConsumerImpl recentsAnimationInputConsumer =
                dc.getInputMonitor().getInputConsumer(INPUT_CONSUMER_RECENTS_ANIMATION);
        if (recentsAnimationInputConsumer != null) {
            // find the top-most going-away activity and the recents activity. The top-most
            // is used as layer reference while the recents is used for registering the consumer
            // override.
            ActivityRecord recentsActivity = null;
            ActivityRecord topActivity = null;
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                if (change.getTaskInfo() == null) continue;
                final Task task = Task.fromWindowContainerToken(
                        info.getChanges().get(i).getTaskInfo().token);
                if (task == null) continue;
                final int activityType = change.getTaskInfo().topActivityType;
                final boolean isRecents = activityType == ACTIVITY_TYPE_HOME
                        || activityType == ACTIVITY_TYPE_RECENTS;
                if (isRecents && recentsActivity == null) {
                    recentsActivity = task.getTopVisibleActivity();
                } else if (!isRecents && topActivity == null) {
                    topActivity = task.getTopNonFinishingActivity();
                }
            }
            if (recentsActivity != null && topActivity != null) {
                recentsAnimationInputConsumer.mWindowHandle.touchableRegion.set(
                        topActivity.getBounds());
                dc.getInputMonitor().setActiveRecents(recentsActivity, topActivity);
            }
        }

        // The rest of this function handles nav-bar reparenting

        if (!dc.getDisplayPolicy().shouldAttachNavBarToAppDuringTransition()
                // Skip the case where the nav bar is controlled by fade rotation.
                || dc.getAsyncRotationController() != null) {
            return;
        }

        WindowContainer topWC = null;
        // Find the top-most non-home, closing app.
        for (int i = 0; i < info.getChanges().size(); ++i) {
            final TransitionInfo.Change c = info.getChanges().get(i);
            if (c.getTaskInfo() == null || c.getTaskInfo().displayId != mRecentsDisplayId
                    || c.getTaskInfo().getActivityType() != ACTIVITY_TYPE_STANDARD
                    || !(c.getMode() == TRANSIT_CLOSE || c.getMode() == TRANSIT_TO_BACK)) {
                continue;
            }
            topWC = WindowContainer.fromBinder(c.getContainer().asBinder());
            break;
        }
        if (topWC == null || topWC.inMultiWindowMode()) {
            return;
        }

        final WindowState navWindow = dc.getDisplayPolicy().getNavigationBar();
        if (navWindow == null || navWindow.mToken == null) {
            return;
        }
        mNavBarAttachedToApp = true;
        navWindow.mToken.cancelAnimation();
        final SurfaceControl.Transaction t = navWindow.mToken.getPendingTransaction();
        final SurfaceControl navSurfaceControl = navWindow.mToken.getSurfaceControl();
        t.reparent(navSurfaceControl, topWC.getSurfaceControl());
        t.show(navSurfaceControl);

        final WindowContainer imeContainer = dc.getImeContainer();
        if (imeContainer.isVisible()) {
            t.setRelativeLayer(navSurfaceControl, imeContainer.getSurfaceControl(), 1);
        } else {
            // Place the nav bar on top of anything else in the top activity.
            t.setLayer(navSurfaceControl, Integer.MAX_VALUE);
        }
        if (mController.mStatusBar != null) {
            mController.mStatusBar.setNavigationBarLumaSamplingEnabled(mRecentsDisplayId, false);
        }
    }

    /** @see RecentsAnimationController#restoreNavigationBarFromApp */
    void legacyRestoreNavigationBarFromApp() {
        if (!mNavBarAttachedToApp) return;
        mNavBarAttachedToApp = false;

        if (mRecentsDisplayId == INVALID_DISPLAY) {
            Slog.e(TAG, "Reparented navigation bar without a valid display");
            mRecentsDisplayId = DEFAULT_DISPLAY;
        }

        if (mController.mStatusBar != null) {
            mController.mStatusBar.setNavigationBarLumaSamplingEnabled(mRecentsDisplayId, true);
        }

        final DisplayContent dc =
                mController.mAtm.mRootWindowContainer.getDisplayContent(mRecentsDisplayId);
        final WindowState navWindow = dc.getDisplayPolicy().getNavigationBar();
        if (navWindow == null) return;
        navWindow.setSurfaceTranslationY(0);

        final WindowToken navToken = navWindow.mToken;
        if (navToken == null) return;
        final SurfaceControl.Transaction t = dc.getPendingTransaction();
        final WindowContainer parent = navToken.getParent();
        t.setLayer(navToken.getSurfaceControl(), navToken.getLastLayer());

        boolean animate = false;
        // Search for the home task. If it is supposed to be visible, then the navbar is not at
        // the bottom of the screen, so we need to animate it.
        for (int i = 0; i < mTargets.size(); ++i) {
            final Task task = mTargets.get(i).mContainer.asTask();
            if (task == null || !task.isActivityTypeHomeOrRecents()) continue;
            animate = task.isVisibleRequested();
            break;
        }

        if (animate) {
            final NavBarFadeAnimationController controller =
                    new NavBarFadeAnimationController(dc);
            controller.fadeWindowToken(true);
        } else {
            // Reparent the SurfaceControl of nav bar token back.
            t.reparent(navToken.getSurfaceControl(), parent.getSurfaceControl());
        }
    }

    private void handleNonAppWindowsInTransition(@NonNull DisplayContent dc,
            @TransitionType int transit, @TransitionFlags int flags) {
        if ((flags & TRANSIT_FLAG_KEYGUARD_LOCKED) != 0) {
            mController.mAtm.mWindowManager.mPolicy.applyKeyguardOcclusionChange(
                    false /* notify */);
        }
    }

    private void reportStartReasonsToLogger() {
        // Record transition start in metrics logger. We just assume everything is "DRAWN"
        // at this point since splash-screen is a presentation (shell) detail.
        ArrayMap<WindowContainer, Integer> reasons = new ArrayMap<>();
        for (int i = mParticipants.size() - 1; i >= 0; --i) {
            ActivityRecord r = mParticipants.valueAt(i).asActivityRecord();
            if (r == null || !r.isVisibleRequested()) continue;
            int transitionReason = APP_TRANSITION_WINDOWS_DRAWN;
            // At this point, r is "ready", but if it's not "ALL ready" then it is probably only
            // ready due to starting-window.
            if (r.mStartingData instanceof SplashScreenStartingData && !r.mLastAllReadyAtSync) {
                transitionReason = APP_TRANSITION_SPLASH_SCREEN;
            } else if (r.isActivityTypeHomeOrRecents() && isTransientLaunch(r)) {
                transitionReason = APP_TRANSITION_RECENTS_ANIM;
            }
            reasons.put(r, transitionReason);
        }
        mController.mAtm.mTaskSupervisor.getActivityMetricsLogger().notifyTransitionStarting(
                reasons);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("TransitionRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" id=" + mSyncId);
        sb.append(" type=" + transitTypeToString(mType));
        sb.append(" flags=" + mFlags);
        sb.append('}');
        return sb.toString();
    }

    /** Returns the parent that the remote animator can animate or control. */
    private static WindowContainer<?> getAnimatableParent(WindowContainer<?> wc) {
        WindowContainer<?> parent = wc.getParent();
        while (parent != null
                && (!parent.canCreateRemoteAnimationTarget() && !parent.isOrganized())) {
            parent = parent.getParent();
        }
        return parent;
    }

    private static boolean reportIfNotTop(WindowContainer wc) {
        // Organized tasks need to be reported anyways because Core won't show() their surfaces
        // and we can't rely on onTaskAppeared because it isn't in sync.
        // TODO(shell-transitions): switch onTaskAppeared usage over to transitions OPEN.
        return wc.isOrganized();
    }

    private static boolean isWallpaper(WindowContainer wc) {
        return wc.asWallpaperToken() != null;
    }

    private static boolean isInputMethod(WindowContainer wc) {
        return wc.getWindowType() == TYPE_INPUT_METHOD;
    }

    private static boolean occludesKeyguard(WindowContainer wc) {
        final ActivityRecord ar = wc.asActivityRecord();
        if (ar != null) {
            return ar.canShowWhenLocked();
        }
        final Task t = wc.asTask();
        if (t != null) {
            // Get the top activity which was visible (since this is going away, it will remain
            // client visible until the transition is finished).
            // skip hidden (or about to hide) apps
            final ActivityRecord top = t.getActivity(WindowToken::isClientVisible);
            return top != null && top.canShowWhenLocked();
        }
        return false;
    }

    private static boolean isTranslucent(@NonNull WindowContainer wc) {
        final TaskFragment taskFragment = wc.asTaskFragment();
        if (taskFragment != null) {
            if (taskFragment.isTranslucent(null /* starting */)) {
                return true;
            }
            final TaskFragment adjacentTaskFragment = taskFragment.getAdjacentTaskFragment();
            if (adjacentTaskFragment != null) {
                // Treat the TaskFragment as translucent if its adjacent TF is, otherwise everything
                // behind two adjacent TaskFragments are occluded.
                return adjacentTaskFragment.isTranslucent(null /* starting */);
            }
        }
        // TODO(b/172695805): hierarchical check. This is non-trivial because for containers
        //                    it is effected by child visibility but needs to work even
        //                    before visibility is committed. This means refactoring some
        //                    checks to use requested visibility.
        return !wc.fillsParent();
    }

    /**
     * Under some conditions (eg. all visible targets within a parent container are transitioning
     * the same way) the transition can be "promoted" to the parent container. This means an
     * animation can play just on the parent rather than all the individual children.
     *
     * @return {@code true} if transition in target can be promoted to its parent.
     */
    private static boolean canPromote(ChangeInfo targetChange, Targets targets,
            ArrayMap<WindowContainer, ChangeInfo> changes) {
        final WindowContainer<?> target = targetChange.mContainer;
        final WindowContainer<?> parent = target.getParent();
        final ChangeInfo parentChange = changes.get(parent);
        if (!parent.canCreateRemoteAnimationTarget()
                || parentChange == null || !parentChange.hasChanged()) {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "      SKIP: %s",
                    "parent can't be target " + parent);
            return false;
        }
        if (isWallpaper(target)) {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "      SKIP: is wallpaper");
            return false;
        }

        if (targetChange.mStartParent != null && target.getParent() != targetChange.mStartParent) {
            // When a window is reparented, the state change won't fit into any of the parents.
            // Don't promote such change so that we can animate the reparent if needed.
            return false;
        }

        final @TransitionInfo.TransitionMode int mode = targetChange.getTransitMode(target);
        for (int i = parent.getChildCount() - 1; i >= 0; --i) {
            final WindowContainer<?> sibling = parent.getChildAt(i);
            if (target == sibling) continue;
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "      check sibling %s",
                    sibling);
            final ChangeInfo siblingChange = changes.get(sibling);
            if (siblingChange == null || !targets.wasParticipated(siblingChange)) {
                if (sibling.isVisibleRequested()) {
                    // Sibling is visible but not animating, so no promote.
                    ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                            "        SKIP: sibling is visible but not part of transition");
                    return false;
                }
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "        unrelated invisible sibling %s", sibling);
                continue;
            }

            final int siblingMode = siblingChange.getTransitMode(sibling);
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                    "        sibling is a participant with mode %s",
                    TransitionInfo.modeToString(siblingMode));
            if (reduceMode(mode) != reduceMode(siblingMode)) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "          SKIP: common mode mismatch. was %s",
                        TransitionInfo.modeToString(mode));
                return false;
            }
        }
        return true;
    }

    /** "reduces" a mode into a smaller set of modes that uniquely represents visibility change. */
    @TransitionInfo.TransitionMode
    private static int reduceMode(@TransitionInfo.TransitionMode int mode) {
        switch (mode) {
            case TRANSIT_TO_BACK: return TRANSIT_CLOSE;
            case TRANSIT_TO_FRONT: return TRANSIT_OPEN;
            default: return mode;
        }
    }

    /**
     * Go through topTargets and try to promote (see {@link #canPromote}) one of them.
     *
     * @param targets all targets that will be sent to the player.
     */
    private static void tryPromote(Targets targets, ArrayMap<WindowContainer, ChangeInfo> changes) {
        WindowContainer<?> lastNonPromotableParent = null;
        // Go through from the deepest target.
        for (int i = targets.mArray.size() - 1; i >= 0; --i) {
            final ChangeInfo targetChange = targets.mArray.valueAt(i);
            final WindowContainer<?> target = targetChange.mContainer;
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "    checking %s", target);
            final WindowContainer<?> parent = target.getParent();
            if (parent == lastNonPromotableParent) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "      SKIP: its sibling was rejected");
                continue;
            }
            if (!canPromote(targetChange, targets, changes)) {
                lastNonPromotableParent = parent;
                continue;
            }
            if (reportIfNotTop(target)) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "        keep as target %s", target);
            } else {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "        remove from targets %s", target);
                targets.remove(i);
            }
            final ChangeInfo parentChange = changes.get(parent);
            if (targets.mArray.indexOfValue(parentChange) < 0) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "      CAN PROMOTE: promoting to parent %s", parent);
                // The parent has lower depth, so it will be checked in the later iteration.
                i++;
                targets.add(parentChange);
            }
            if ((targetChange.mFlags & ChangeInfo.FLAG_CHANGE_NO_ANIMATION) != 0) {
                parentChange.mFlags |= ChangeInfo.FLAG_CHANGE_NO_ANIMATION;
            } else {
                parentChange.mFlags |= ChangeInfo.FLAG_CHANGE_YES_ANIMATION;
            }
        }
    }

    /**
     * Find WindowContainers to be animated from a set of opening and closing apps. We will promote
     * animation targets to higher level in the window hierarchy if possible.
     */
    @VisibleForTesting
    @NonNull
    static ArrayList<ChangeInfo> calculateTargets(ArraySet<WindowContainer> participants,
            ArrayMap<WindowContainer, ChangeInfo> changes) {
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                "Start calculating TransitionInfo based on participants: %s", participants);

        // Add all valid participants to the target container.
        final Targets targets = new Targets();
        for (int i = participants.size() - 1; i >= 0; --i) {
            final WindowContainer<?> wc = participants.valueAt(i);
            if (!wc.isAttached()) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "  Rejecting as detached: %s", wc);
                continue;
            }
            // The level of transition target should be at least window token.
            if (wc.asWindowState() != null) continue;

            final ChangeInfo changeInfo = changes.get(wc);

            // Reject no-ops
            if (!changeInfo.hasChanged()) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "  Rejecting as no-op: %s", wc);
                continue;
            }
            targets.add(changeInfo);
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "  Initial targets: %s",
                targets.mArray);
        // Combine the targets from bottom to top if possible.
        tryPromote(targets, changes);
        // Establish the relationship between the targets and their top changes.
        populateParentChanges(targets, changes);

        final ArrayList<ChangeInfo> targetList = targets.getListSortedByZ();
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "  Final targets: %s", targetList);
        return targetList;
    }

    /** Populates parent to the change info and collects intermediate targets. */
    private static void populateParentChanges(Targets targets,
            ArrayMap<WindowContainer, ChangeInfo> changes) {
        final ArrayList<ChangeInfo> intermediates = new ArrayList<>();
        // Make a copy to iterate because the original array may be modified.
        final ArrayList<ChangeInfo> targetList = new ArrayList<>(targets.mArray.size());
        for (int i = targets.mArray.size() - 1; i >= 0; --i) {
            targetList.add(targets.mArray.valueAt(i));
        }
        for (int i = targetList.size() - 1; i >= 0; --i) {
            final ChangeInfo targetChange = targetList.get(i);
            final WindowContainer wc = targetChange.mContainer;
            // Wallpaper must belong to the top (regardless of how nested it is in DisplayAreas).
            final boolean skipIntermediateReports = isWallpaper(wc);
            intermediates.clear();
            boolean foundParentInTargets = false;
            // Collect the intermediate parents between target and top changed parent.
            for (WindowContainer<?> p = getAnimatableParent(wc); p != null;
                    p = getAnimatableParent(p)) {
                final ChangeInfo parentChange = changes.get(p);
                if (parentChange == null || !parentChange.hasChanged()) break;
                if (p.mRemoteToken == null) {
                    // Intermediate parents must be those that has window to be managed by Shell.
                    continue;
                }
                if (parentChange.mEndParent != null && !skipIntermediateReports) {
                    targetChange.mEndParent = p;
                    // The chain above the parent was processed.
                    break;
                }
                if (targetList.contains(parentChange)) {
                    if (skipIntermediateReports) {
                        targetChange.mEndParent = p;
                    } else {
                        intermediates.add(parentChange);
                    }
                    foundParentInTargets = true;
                    break;
                } else if (reportIfNotTop(p) && !skipIntermediateReports) {
                    intermediates.add(parentChange);
                }
            }
            if (!foundParentInTargets || intermediates.isEmpty()) continue;
            // Add any always-report parents along the way.
            targetChange.mEndParent = intermediates.get(0).mContainer;
            for (int j = 0; j < intermediates.size() - 1; j++) {
                final ChangeInfo intermediate = intermediates.get(j);
                intermediate.mEndParent = intermediates.get(j + 1).mContainer;
                targets.add(intermediate);
            }
        }
    }

    /**
     * Gets the leash surface for a window container.
     * @param t a transaction to create leashes on when necessary (fixed rotation at token-level).
     *          If t is null, then this will not create any leashes, just use one if it is there --
     *          this is relevant for building the finishTransaction since it needs to match the
     *          start state and not erroneously create a leash of its own.
     */
    private static SurfaceControl getLeashSurface(WindowContainer wc,
            @Nullable SurfaceControl.Transaction t) {
        final DisplayContent asDC = wc.asDisplayContent();
        if (asDC != null) {
            // DisplayContent is the "root", so we use the windowing layer instead to avoid
            // hardware-screen-level surfaces.
            return asDC.getWindowingLayer();
        }
        if (!wc.mTransitionController.useShellTransitionsRotation()) {
            final WindowToken asToken = wc.asWindowToken();
            if (asToken != null) {
                // WindowTokens can have a fixed-rotation applied to them. In the current
                // implementation this fact is hidden from the player, so we must create a leash.
                final SurfaceControl leash = t != null ? asToken.getOrCreateFixedRotationLeash(t)
                        : asToken.getFixedRotationLeash();
                if (leash != null) return leash;
            }
        }
        return wc.getSurfaceControl();
    }

    private static SurfaceControl getOrigParentSurface(WindowContainer wc) {
        if (wc.asDisplayContent() != null) {
            // DisplayContent is the "root", so we reinterpret it's wc as the window layer
            // making the parent surface the displaycontent's surface.
            return wc.getSurfaceControl();
        }
        return wc.getParent().getSurfaceControl();
    }

    /**
     * A ready group is defined by a root window-container where all transitioning windows under
     * it are expected to animate together as a group. At the moment, this treats each display as
     * a ready-group to match the existing legacy transition behavior.
     */
    private static boolean isReadyGroup(WindowContainer wc) {
        return wc instanceof DisplayContent;
    }

    /**
     * Construct a TransitionInfo object from a set of targets and changes. Also populates the
     * root surface.
     * @param sortedTargets The targets sorted by z-order from top (index 0) to bottom.
     * @param startT The start transaction - used to set-up new leashes.
     */
    @VisibleForTesting
    @NonNull
    static TransitionInfo calculateTransitionInfo(@TransitionType int type, int flags,
            ArrayList<ChangeInfo> sortedTargets,
            @Nullable SurfaceControl.Transaction startT) {
        final TransitionInfo out = new TransitionInfo(type, flags);

        WindowContainer<?> topApp = null;
        for (int i = 0; i < sortedTargets.size(); i++) {
            final WindowContainer<?> wc = sortedTargets.get(i).mContainer;
            if (!isWallpaper(wc)) {
                topApp = wc;
                break;
            }
        }
        if (topApp == null) {
            out.setRootLeash(new SurfaceControl(), 0, 0);
            return out;
        }

        WindowContainer<?> ancestor = findCommonAncestor(sortedTargets, topApp);

        // Make leash based on highest (z-order) direct child of ancestor with a participant.
        // TODO(b/261418859): Handle the case when the target contains window containers which
        // belong to a different display. As a workaround we use topApp, from which wallpaper
        // window container is removed, instead of sortedTargets here.
        WindowContainer leashReference = topApp;
        while (leashReference.getParent() != ancestor) {
            leashReference = leashReference.getParent();
        }
        final SurfaceControl rootLeash = leashReference.makeAnimationLeash().setName(
                "Transition Root: " + leashReference.getName()).build();
        startT.setLayer(rootLeash, leashReference.getLastLayer());
        out.setRootLeash(rootLeash, ancestor.getBounds().left, ancestor.getBounds().top);

        // Convert all the resolved ChangeInfos into TransactionInfo.Change objects in order.
        final int count = sortedTargets.size();
        for (int i = 0; i < count; ++i) {
            final ChangeInfo info = sortedTargets.get(i);
            final WindowContainer target = info.mContainer;
            final TransitionInfo.Change change = new TransitionInfo.Change(
                    target.mRemoteToken != null ? target.mRemoteToken.toWindowContainerToken()
                            : null, getLeashSurface(target, startT));
            // TODO(shell-transitions): Use leash for non-organized windows.
            if (info.mEndParent != null) {
                change.setParent(info.mEndParent.mRemoteToken.toWindowContainerToken());
            }
            if (info.mStartParent != null && info.mStartParent.mRemoteToken != null
                    && target.getParent() != info.mStartParent) {
                change.setLastParent(info.mStartParent.mRemoteToken.toWindowContainerToken());
            }
            change.setMode(info.getTransitMode(target));
            change.setStartAbsBounds(info.mAbsoluteBounds);
            change.setFlags(info.getChangeFlags(target));

            final Task task = target.asTask();
            final TaskFragment taskFragment = target.asTaskFragment();
            final ActivityRecord activityRecord = target.asActivityRecord();

            if (task != null) {
                final ActivityManager.RunningTaskInfo tinfo = new ActivityManager.RunningTaskInfo();
                task.fillTaskInfo(tinfo);
                change.setTaskInfo(tinfo);
                change.setRotationAnimation(getTaskRotationAnimation(task));
                final ActivityRecord topMostActivity = task.getTopMostActivity();
                change.setAllowEnterPip(topMostActivity != null
                        && topMostActivity.checkEnterPictureInPictureAppOpsState());
                final ActivityRecord topRunningActivity = task.topRunningActivity();
                if (topRunningActivity != null && task.mDisplayContent != null
                        // Display won't be rotated for multi window Task, so the fixed rotation
                        // won't be applied. This can happen when the windowing mode is changed
                        // before the previous fixed rotation is applied.
                        && !task.inMultiWindowMode()) {
                    // If Activity is in fixed rotation, its will be applied with the next rotation,
                    // when the Task is still in the previous rotation.
                    final int taskRotation = task.getWindowConfiguration().getDisplayRotation();
                    final int activityRotation = topRunningActivity.getWindowConfiguration()
                            .getDisplayRotation();
                    if (taskRotation != activityRotation) {
                        change.setEndFixedRotation(activityRotation);
                    }
                }
            } else if ((info.mFlags & ChangeInfo.FLAG_SEAMLESS_ROTATION) != 0) {
                change.setRotationAnimation(ROTATION_ANIMATION_SEAMLESS);
            }

            final WindowContainer<?> parent = target.getParent();
            final Rect bounds = target.getBounds();
            final Rect parentBounds = parent.getBounds();
            change.setEndRelOffset(bounds.left - parentBounds.left,
                    bounds.top - parentBounds.top);
            int endRotation = target.getWindowConfiguration().getRotation();
            if (activityRecord != null) {
                // TODO(b/227427984): Shell needs to aware letterbox.
                // Always use parent bounds of activity because letterbox area (e.g. fixed aspect
                // ratio or size compat mode) should be included in the animation.
                change.setEndAbsBounds(parentBounds);
                if (activityRecord.getRelativeDisplayRotation() != 0
                        && !activityRecord.mTransitionController.useShellTransitionsRotation()) {
                    // Use parent rotation because shell doesn't know the surface is rotated.
                    endRotation = parent.getWindowConfiguration().getRotation();
                }
            } else {
                change.setEndAbsBounds(bounds);
            }

            if (activityRecord != null || (taskFragment != null && taskFragment.isEmbedded())) {
                final int backgroundColor;
                final TaskFragment organizedTf = activityRecord != null
                        ? activityRecord.getOrganizedTaskFragment()
                        : taskFragment.getOrganizedTaskFragment();
                if (organizedTf != null && organizedTf.getAnimationParams()
                        .getAnimationBackgroundColor() != 0) {
                    // This window is embedded and has an animation background color set on the
                    // TaskFragment. Pass this color with this window, so the handler can use it as
                    // the animation background color if needed,
                    backgroundColor = organizedTf.getAnimationParams()
                            .getAnimationBackgroundColor();
                } else {
                    // Set background color to Task theme color for activity and embedded
                    // TaskFragment in case we want to show background during the animation.
                    final Task parentTask = activityRecord != null
                            ? activityRecord.getTask()
                            : taskFragment.getTask();
                    backgroundColor = ColorUtils.setAlphaComponent(
                            parentTask.getTaskDescription().getBackgroundColor(), 255);
                }
                change.setBackgroundColor(backgroundColor);
            }

            change.setRotation(info.mRotation, endRotation);
            if (info.mSnapshot != null) {
                change.setSnapshot(info.mSnapshot, info.mSnapshotLuma);
            }

            out.addChange(change);
        }

        final WindowManager.LayoutParams animLp =
                getLayoutParamsForAnimationsStyle(type, sortedTargets);
        if (animLp != null && animLp.type != TYPE_APPLICATION_STARTING
                && animLp.windowAnimations != 0) {
            // Don't send animation options if no windowAnimations have been set or if the we are
            // running an app starting animation, in which case we don't want the app to be able to
            // change its animation directly.
            TransitionInfo.AnimationOptions animOptions =
                    TransitionInfo.AnimationOptions.makeAnimOptionsFromLayoutParameters(animLp);
            out.setAnimationOptions(animOptions);
        }

        return out;
    }

    /**
     * Finds the top-most common ancestor of app targets.
     *
     * Makes sure that the previous parent is also a descendant to make sure the animation won't
     * be covered by other windows below the previous parent. For example, when reparenting an
     * activity from PiP Task to split screen Task.
     */
    @NonNull
    private static WindowContainer<?> findCommonAncestor(
            @NonNull ArrayList<ChangeInfo> targets,
            @NonNull WindowContainer<?> topApp) {
        WindowContainer<?> ancestor = topApp.getParent();
        // Go up ancestor parent chain until all targets are descendants. Ancestor should never be
        // null because all targets are attached.
        for (int i = targets.size() - 1; i >= 0; i--) {
            final ChangeInfo change = targets.get(i);
            final WindowContainer wc = change.mContainer;
            if (isWallpaper(wc)) {
                // Skip the non-app window.
                continue;
            }
            while (!wc.isDescendantOf(ancestor)) {
                ancestor = ancestor.getParent();
            }

            // Make sure the previous parent is also a descendant to make sure the animation won't
            // be covered by other windows below the previous parent. For example, when reparenting
            // an activity from PiP Task to split screen Task.
            final WindowContainer prevParent = change.mCommonAncestor;
            if (prevParent == null || !prevParent.isAttached()) {
                continue;
            }
            while (prevParent != ancestor && !prevParent.isDescendantOf(ancestor)) {
                ancestor = ancestor.getParent();
            }
        }
        return ancestor;
    }

    private static WindowManager.LayoutParams getLayoutParamsForAnimationsStyle(int type,
            ArrayList<ChangeInfo> sortedTargets) {
        // Find the layout params of the top-most application window that is part of the
        // transition, which is what will control the animation theme.
        final ArraySet<Integer> activityTypes = new ArraySet<>();
        final int targetCount = sortedTargets.size();
        for (int i = 0; i < targetCount; ++i) {
            final WindowContainer target = sortedTargets.get(i).mContainer;
            if (target.asActivityRecord() != null) {
                activityTypes.add(target.getActivityType());
            } else if (target.asWindowToken() == null && target.asWindowState() == null) {
                // We don't want app to customize animations that are not activity to activity.
                // Activity-level transitions can only include activities, wallpaper and subwindows.
                // Anything else is not a WindowToken nor a WindowState and is "higher" in the
                // hierarchy which means we are no longer in an activity transition.
                return null;
            }
        }
        if (activityTypes.isEmpty()) {
            // We don't want app to be able to customize transitions that are not activity to
            // activity through the layout parameter animation style.
            return null;
        }
        final ActivityRecord animLpActivity =
                findAnimLayoutParamsActivityRecord(sortedTargets, type, activityTypes);
        final WindowState mainWindow = animLpActivity != null
                ? animLpActivity.findMainWindow() : null;
        return mainWindow != null ? mainWindow.mAttrs : null;
    }

    private static ActivityRecord findAnimLayoutParamsActivityRecord(
            List<ChangeInfo> sortedTargets,
            @TransitionType int transit, ArraySet<Integer> activityTypes) {
        // Remote animations always win, but fullscreen windows override non-fullscreen windows.
        ActivityRecord result = lookForTopWindowWithFilter(sortedTargets,
                w -> w.getRemoteAnimationDefinition() != null
                    && w.getRemoteAnimationDefinition().hasTransition(transit, activityTypes));
        if (result != null) {
            return result;
        }
        result = lookForTopWindowWithFilter(sortedTargets,
                w -> w.fillsParent() && w.findMainWindow() != null);
        if (result != null) {
            return result;
        }
        return lookForTopWindowWithFilter(sortedTargets, w -> w.findMainWindow() != null);
    }

    private static ActivityRecord lookForTopWindowWithFilter(List<ChangeInfo> sortedTargets,
            Predicate<ActivityRecord> filter) {
        final int count = sortedTargets.size();
        for (int i = 0; i < count; ++i) {
            final WindowContainer target = sortedTargets.get(i).mContainer;
            final ActivityRecord activityRecord = target.asTaskFragment() != null
                    ? target.asTaskFragment().getTopNonFinishingActivity()
                    : target.asActivityRecord();
            if (activityRecord != null && filter.test(activityRecord)) {
                return activityRecord;
            }
        }
        return null;
    }

    private static int getTaskRotationAnimation(@NonNull Task task) {
        final ActivityRecord top = task.getTopVisibleActivity();
        if (top == null) return ROTATION_ANIMATION_UNSPECIFIED;
        final WindowState mainWin = top.findMainWindow(false);
        if (mainWin == null) return ROTATION_ANIMATION_UNSPECIFIED;
        int anim = mainWin.getRotationAnimationHint();
        if (anim >= 0) return anim;
        anim = mainWin.getAttrs().rotationAnimation;
        if (anim != ROTATION_ANIMATION_SEAMLESS) return anim;
        if (mainWin != task.mDisplayContent.getDisplayPolicy().getTopFullscreenOpaqueWindow()
                || !top.matchParentBounds()) {
            // At the moment, we only support seamless rotation if there is only one window showing.
            return ROTATION_ANIMATION_UNSPECIFIED;
        }
        return mainWin.getAttrs().rotationAnimation;
    }

    /** Applies the new configuration and returns {@code true} if there is a display change. */
    boolean applyDisplayChangeIfNeeded() {
        boolean changed = false;
        for (int i = mParticipants.size() - 1; i >= 0; --i) {
            final WindowContainer<?> wc = mParticipants.valueAt(i);
            final DisplayContent dc = wc.asDisplayContent();
            if (dc == null || !mChanges.get(dc).hasChanged()) continue;
            dc.sendNewConfiguration();
            changed = true;
        }
        return changed;
    }

    boolean getLegacyIsReady() {
        return isCollecting() && mSyncId >= 0;
    }

    @VisibleForTesting
    static class ChangeInfo {
        private static final int FLAG_NONE = 0;

        /**
         * When set, the associated WindowContainer has been explicitly requested to be a
         * seamless rotation. This is currently only used by DisplayContent during fixed-rotation.
         */
        private static final int FLAG_SEAMLESS_ROTATION = 1;
        private static final int FLAG_TRANSIENT_LAUNCH = 2;
        private static final int FLAG_ABOVE_TRANSIENT_LAUNCH = 4;

        /** This container explicitly requested no-animation (usually Activity level). */
        private static final int FLAG_CHANGE_NO_ANIMATION = 0x8;
        /**
         * This container has at-least one child which IS animating (not marked NO_ANIMATION).
         * Used during promotion. This trumps `FLAG_NO_ANIMATION` (if both are set).
         */
        private static final int FLAG_CHANGE_YES_ANIMATION = 0x10;

        @IntDef(prefix = { "FLAG_" }, value = {
                FLAG_NONE,
                FLAG_SEAMLESS_ROTATION,
                FLAG_TRANSIENT_LAUNCH,
                FLAG_ABOVE_TRANSIENT_LAUNCH,
                FLAG_CHANGE_NO_ANIMATION,
                FLAG_CHANGE_YES_ANIMATION
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface Flag {}

        @NonNull final WindowContainer mContainer;
        /**
         * "Parent" that is also included in the transition. When populating the parent changes, we
         * may skip the intermediate parents, so this may not be the actual parent in the hierarchy.
         */
        WindowContainer mEndParent;
        /** Actual parent window before change state. */
        WindowContainer mStartParent;
        /**
         * When the window is reparented during the transition, this is the common ancestor window
         * of the {@link #mStartParent} and the current parent. This is needed because the
         * {@link #mStartParent} may have been detached when the transition starts.
         */
        WindowContainer mCommonAncestor;

        // State tracking
        boolean mExistenceChanged = false;
        // before change state
        boolean mVisible;
        int mWindowingMode;
        final Rect mAbsoluteBounds = new Rect();
        boolean mShowWallpaper;
        int mRotation = ROTATION_UNDEFINED;
        @ActivityInfo.Config int mKnownConfigChanges;

        /** These are just extra info. They aren't used for change-detection. */
        @Flag int mFlags = FLAG_NONE;

        /** Snapshot surface and luma, if relevant. */
        SurfaceControl mSnapshot;
        float mSnapshotLuma;

        ChangeInfo(@NonNull WindowContainer origState) {
            mContainer = origState;
            mVisible = origState.isVisibleRequested();
            mWindowingMode = origState.getWindowingMode();
            mAbsoluteBounds.set(origState.getBounds());
            mShowWallpaper = origState.showWallpaper();
            mRotation = origState.getWindowConfiguration().getRotation();
            mStartParent = origState.getParent();
        }

        @VisibleForTesting
        ChangeInfo(@NonNull WindowContainer container, boolean visible, boolean existChange) {
            mContainer = container;
            mVisible = visible;
            mExistenceChanged = existChange;
            mShowWallpaper = false;
        }

        @Override
        public String toString() {
            return mContainer.toString();
        }

        boolean hasChanged() {
            // the task including transient launch must promote to root task
            if ((mFlags & ChangeInfo.FLAG_TRANSIENT_LAUNCH) != 0
                    || (mFlags & ChangeInfo.FLAG_ABOVE_TRANSIENT_LAUNCH) != 0) {
                return true;
            }
            // If it's invisible and hasn't changed visibility, always return false since even if
            // something changed, it wouldn't be a visible change.
            final boolean currVisible = mContainer.isVisibleRequested();
            if (currVisible == mVisible && !mVisible) return false;
            return currVisible != mVisible
                    || mKnownConfigChanges != 0
                    // if mWindowingMode is 0, this container wasn't attached at collect time, so
                    // assume no change in windowing-mode.
                    || (mWindowingMode != 0 && mContainer.getWindowingMode() != mWindowingMode)
                    || !mContainer.getBounds().equals(mAbsoluteBounds)
                    || mRotation != mContainer.getWindowConfiguration().getRotation();
        }

        @TransitionInfo.TransitionMode
        int getTransitMode(@NonNull WindowContainer wc) {
            if ((mFlags & ChangeInfo.FLAG_ABOVE_TRANSIENT_LAUNCH) != 0) {
                return mExistenceChanged ? TRANSIT_CLOSE : TRANSIT_TO_BACK;
            }
            final boolean nowVisible = wc.isVisibleRequested();
            if (nowVisible == mVisible) {
                return TRANSIT_CHANGE;
            }
            if (mExistenceChanged) {
                return nowVisible ? TRANSIT_OPEN : TRANSIT_CLOSE;
            } else {
                return nowVisible ? TRANSIT_TO_FRONT : TRANSIT_TO_BACK;
            }
        }

        @TransitionInfo.ChangeFlags
        int getChangeFlags(@NonNull WindowContainer wc) {
            int flags = 0;
            if (mShowWallpaper || wc.showWallpaper()) {
                flags |= FLAG_SHOW_WALLPAPER;
            }
            if (isTranslucent(wc)) {
                flags |= FLAG_TRANSLUCENT;
            }
            final Task task = wc.asTask();
            if (task != null) {
                final ActivityRecord topActivity = task.getTopNonFinishingActivity();
                if (topActivity != null) {
                    if (topActivity.mStartingData != null
                            && topActivity.mStartingData.hasImeSurface()) {
                        flags |= FLAG_WILL_IME_SHOWN;
                    }
                    if (topActivity.mAtmService.mBackNavigationController
                            .isMonitorTransitionTarget(topActivity)) {
                        flags |= TransitionInfo.FLAG_BACK_GESTURE_ANIMATED;
                    }
                } else {
                    if (task.mAtmService.mBackNavigationController
                            .isMonitorTransitionTarget(task)) {
                        flags |= TransitionInfo.FLAG_BACK_GESTURE_ANIMATED;
                    }
                }
                if (task.voiceSession != null) {
                    flags |= FLAG_IS_VOICE_INTERACTION;
                }
            }
            Task parentTask = null;
            final ActivityRecord record = wc.asActivityRecord();
            if (record != null) {
                parentTask = record.getTask();
                if (record.mVoiceInteraction) {
                    flags |= FLAG_IS_VOICE_INTERACTION;
                }
                flags |= record.mTransitionChangeFlags;
                if (record.mAtmService.mBackNavigationController
                        .isMonitorTransitionTarget(record)) {
                    flags |= TransitionInfo.FLAG_BACK_GESTURE_ANIMATED;
                }
            }
            final TaskFragment taskFragment = wc.asTaskFragment();
            if (taskFragment != null && task == null) {
                parentTask = taskFragment.getTask();
            }
            if (parentTask != null) {
                if (parentTask.forAllLeafTaskFragments(TaskFragment::isEmbedded)) {
                    // Whether this is in a Task with embedded activity.
                    flags |= FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY;
                }
                if (parentTask.forAllActivities(ActivityRecord::hasStartingWindow)) {
                    // The starting window should cover all windows inside the leaf Task.
                    flags |= FLAG_IS_BEHIND_STARTING_WINDOW;
                }
                if (isWindowFillingTask(wc, parentTask)) {
                    // Whether the container fills its parent Task bounds.
                    flags |= FLAG_FILLS_TASK;
                }
            } else {
                final DisplayContent dc = wc.asDisplayContent();
                if (dc != null) {
                    flags |= FLAG_IS_DISPLAY;
                    if (dc.hasAlertWindowSurfaces()) {
                        flags |= FLAG_DISPLAY_HAS_ALERT_WINDOWS;
                    }
                } else if (isWallpaper(wc)) {
                    flags |= FLAG_IS_WALLPAPER;
                } else if (isInputMethod(wc)) {
                    flags |= FLAG_IS_INPUT_METHOD;
                } else {
                    // In this condition, the wc can only be WindowToken or DisplayArea.
                    final int type = wc.getWindowType();
                    if (type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                            && type <= WindowManager.LayoutParams.LAST_SYSTEM_WINDOW) {
                        flags |= TransitionInfo.FLAG_IS_SYSTEM_WINDOW;
                    }
                }
            }
            if (occludesKeyguard(wc)) {
                flags |= FLAG_OCCLUDES_KEYGUARD;
            }
            if ((mFlags & FLAG_CHANGE_NO_ANIMATION) != 0
                    && (mFlags & FLAG_CHANGE_YES_ANIMATION) == 0) {
                flags |= FLAG_NO_ANIMATION;
            }
            return flags;
        }

        /** Whether the container fills its parent Task bounds before and after the transition. */
        private boolean isWindowFillingTask(@NonNull WindowContainer wc, @NonNull Task parentTask) {
            final Rect taskBounds = parentTask.getBounds();
            final int taskWidth = taskBounds.width();
            final int taskHeight = taskBounds.height();
            final Rect startBounds = mAbsoluteBounds;
            final Rect endBounds = wc.getBounds();
            // Treat it as filling the task if it is not visible.
            final boolean isInvisibleOrFillingTaskBeforeTransition = !mVisible
                    || (taskWidth == startBounds.width() && taskHeight == startBounds.height());
            final boolean isInVisibleOrFillingTaskAfterTransition = !wc.isVisibleRequested()
                    || (taskWidth == endBounds.width() && taskHeight == endBounds.height());
            return isInvisibleOrFillingTaskBeforeTransition
                    && isInVisibleOrFillingTaskAfterTransition;
        }
    }

    /**
     * This transition will be considered not-ready until a corresponding call to
     * {@link #continueTransitionReady}
     */
    void deferTransitionReady() {
        ++mReadyTracker.mDeferReadyDepth;
        // Make sure it wait until #continueTransitionReady() is called.
        mSyncEngine.setReady(mSyncId, false);
    }

    /** This undoes one call to {@link #deferTransitionReady}. */
    void continueTransitionReady() {
        --mReadyTracker.mDeferReadyDepth;
        // Apply ready in case it is waiting for the previous defer call.
        applyReady();
    }

    /**
     * The transition sync mechanism has 2 parts:
     *   1. Whether all WM operations for a particular transition are "ready" (eg. did the app
     *      launch or stop or get a new configuration?).
     *   2. Whether all the windows involved have finished drawing their final-state content.
     *
     * A transition animation can play once both parts are complete. This ready-tracker keeps track
     * of part (1). Currently, WM code assumes that "readiness" (part 1) is grouped. This means that
     * even if the WM operations in one group are ready, the whole transition itself may not be
     * ready if there are WM operations still pending in another group. This class helps keep track
     * of readiness across the multiple groups. Currently, we assume that each display is a group
     * since that is how it has been until now.
     */
    private static class ReadyTracker {
        private final ArrayMap<WindowContainer, Boolean> mReadyGroups = new ArrayMap<>();

        /**
         * Ensures that this doesn't report as allReady before it has been used. This is needed
         * in very niche cases where a transition is a no-op (nothing has been collected) but we
         * still want to be marked ready (via. setAllReady).
         */
        private boolean mUsed = false;

        /**
         * If true, this overrides all ready groups and reports ready. Used by shell-initiated
         * transitions via {@link #setAllReady()}.
         */
        private boolean mReadyOverride = false;

        /**
         * When non-zero, this transition is forced not-ready (even over setAllReady()). Use this
         * (via deferTransitionReady/continueTransitionReady) for situations where we want to do
         * bulk operations which could trigger surface-placement but the existing ready-state
         * isn't known.
         */
        private int mDeferReadyDepth = 0;

        /**
         * Adds a ready-group. Any setReady calls in this subtree will be tracked together. For
         * now these are only DisplayContents.
         */
        void addGroup(WindowContainer wc) {
            if (mReadyGroups.containsKey(wc)) {
                Slog.e(TAG, "Trying to add a ready-group twice: " + wc);
                return;
            }
            mReadyGroups.put(wc, false);
        }

        /**
         * Sets a group's ready state.
         * @param wc Any container within a group's subtree. Used to identify the ready-group.
         */
        void setReadyFrom(WindowContainer wc, boolean ready) {
            mUsed = true;
            WindowContainer current = wc;
            while (current != null) {
                if (isReadyGroup(current)) {
                    mReadyGroups.put(current, ready);
                    ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, " Setting Ready-group to"
                            + " %b. group=%s from %s", ready, current, wc);
                    break;
                }
                current = current.getParent();
            }
        }

        /** Marks this as ready regardless of individual groups. */
        void setAllReady() {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, " Setting allReady override");
            mUsed = true;
            mReadyOverride = true;
        }

        /** @return true if all tracked subtrees are ready. */
        boolean allReady() {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, " allReady query: used=%b "
                    + "override=%b defer=%d states=[%s]", mUsed, mReadyOverride, mDeferReadyDepth,
                    groupsToString());
            // If the readiness has never been touched, mUsed will be false. We never want to
            // consider a transition ready if nothing has been reported on it.
            if (!mUsed) return false;
            // If we are deferring readiness, we never report ready. This is usually temporary.
            if (mDeferReadyDepth > 0) return false;
            // Next check all the ready groups to see if they are ready. We can short-cut this if
            // ready-override is set (which is treated as "everything is marked ready").
            if (mReadyOverride) return true;
            for (int i = mReadyGroups.size() - 1; i >= 0; --i) {
                final WindowContainer wc = mReadyGroups.keyAt(i);
                if (!wc.isAttached() || !wc.isVisibleRequested()) continue;
                if (!mReadyGroups.valueAt(i)) return false;
            }
            return true;
        }

        private String groupsToString() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < mReadyGroups.size(); ++i) {
                if (i != 0) b.append(',');
                b.append(mReadyGroups.keyAt(i)).append(':')
                        .append(mReadyGroups.valueAt(i));
            }
            return b.toString();
        }
    }

    /**
     * The container to represent the depth relation for calculating transition targets. The window
     * container with larger depth is put at larger index. For the same depth, higher z-order has
     * larger index.
     */
    private static class Targets {
        /** All targets. Its keys (depth) are sorted in ascending order naturally. */
        final SparseArray<ChangeInfo> mArray = new SparseArray<>();
        /** The targets which were represented by their parent. */
        private ArrayList<ChangeInfo> mRemovedTargets;
        private int mDepthFactor;

        void add(ChangeInfo target) {
            // The number of slots per depth is larger than the total number of window container,
            // so the depth score (key) won't have collision.
            if (mDepthFactor == 0) {
                mDepthFactor = target.mContainer.mWmService.mRoot.getTreeWeight() + 1;
            }
            int score = target.mContainer.getPrefixOrderIndex();
            WindowContainer<?> wc = target.mContainer;
            while (wc != null) {
                final WindowContainer<?> parent = wc.getParent();
                if (parent != null) {
                    score += mDepthFactor;
                }
                wc = parent;
            }
            mArray.put(score, target);
        }

        void remove(int index) {
            final ChangeInfo removingTarget = mArray.valueAt(index);
            mArray.removeAt(index);
            if (mRemovedTargets == null) {
                mRemovedTargets = new ArrayList<>();
            }
            mRemovedTargets.add(removingTarget);
        }

        boolean wasParticipated(ChangeInfo wc) {
            return mArray.indexOfValue(wc) >= 0
                    || (mRemovedTargets != null && mRemovedTargets.contains(wc));
        }

        /** Returns the target list sorted by z-order in ascending order (index 0 is top). */
        ArrayList<ChangeInfo> getListSortedByZ() {
            final SparseArray<ChangeInfo> arrayByZ = new SparseArray<>(mArray.size());
            for (int i = mArray.size() - 1; i >= 0; --i) {
                final int zOrder = mArray.keyAt(i) % mDepthFactor;
                arrayByZ.put(zOrder, mArray.valueAt(i));
            }
            final ArrayList<ChangeInfo> sortedTargets = new ArrayList<>(arrayByZ.size());
            for (int i = arrayByZ.size() - 1; i >= 0; --i) {
                sortedTargets.add(arrayByZ.valueAt(i));
            }
            return sortedTargets;
        }
    }

    /**
     * Interface for freezing a container's content during sync preparation. Really just one impl
     * but broken into an interface for testing (since you can't take screenshots in unit tests).
     */
    interface IContainerFreezer {
        /**
         * Makes sure a particular window is "frozen" for the remainder of a sync.
         *
         * @return whether the freeze was successful. It fails if `wc` is already in a frozen window
         *         or is not visible/ready.
         */
        boolean freeze(@NonNull WindowContainer wc, @NonNull Rect bounds);

        /** Populates `t` with operations that clean-up any state created to set-up the freeze. */
        void cleanUp(SurfaceControl.Transaction t);
    }

    /**
     * Freezes container content by taking a screenshot. Because screenshots are heavy, usage of
     * any container "freeze" is currently explicit. WM code needs to be prudent about which
     * containers to freeze.
     */
    @VisibleForTesting
    private class ScreenshotFreezer implements IContainerFreezer {
        /** Keeps track of which windows are frozen. Not all frozen windows have snapshots. */
        private final ArraySet<WindowContainer> mFrozen = new ArraySet<>();

        /** Takes a screenshot and puts it at the top of the container's surface. */
        @Override
        public boolean freeze(@NonNull WindowContainer wc, @NonNull Rect bounds) {
            if (!wc.isVisibleRequested()) return false;

            // Check if any parents have already been "frozen". If so, `wc` is already part of that
            // snapshot, so just skip it.
            for (WindowContainer p = wc; p != null; p = p.getParent()) {
                if (mFrozen.contains(p)) return false;
            }

            if (mIsSeamlessRotation) {
                WindowState top = wc.getDisplayContent() == null ? null
                        : wc.getDisplayContent().getDisplayPolicy().getTopFullscreenOpaqueWindow();
                if (top != null && (top == wc || top.isDescendantOf(wc))) {
                    // Don't use screenshots for seamless windows: these will use BLAST even if not
                    // BLAST mode.
                    mFrozen.add(wc);
                    return true;
                }
            }

            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Screenshotting %s [%s]",
                    wc.toString(), bounds.toString());

            Rect cropBounds = new Rect(bounds);
            cropBounds.offsetTo(0, 0);
            ScreenCapture.LayerCaptureArgs captureArgs =
                    new ScreenCapture.LayerCaptureArgs.Builder(wc.getSurfaceControl())
                            .setSourceCrop(cropBounds)
                            .setCaptureSecureLayers(true)
                            .setAllowProtected(true)
                            .build();
            ScreenCapture.ScreenshotHardwareBuffer screenshotBuffer =
                    ScreenCapture.captureLayers(captureArgs);
            final HardwareBuffer buffer = screenshotBuffer == null ? null
                    : screenshotBuffer.getHardwareBuffer();
            if (buffer == null || buffer.getWidth() <= 1 || buffer.getHeight() <= 1) {
                // This can happen when display is not ready.
                Slog.w(TAG, "Failed to capture screenshot for " + wc);
                return false;
            }
            final boolean isDisplayRotation = wc.asDisplayContent() != null
                    && wc.asDisplayContent().isRotationChanging();
            // Some tests may check the name "RotationLayer" to detect display rotation.
            final String name = isDisplayRotation ? "RotationLayer" : "transition snapshot: " + wc;
            SurfaceControl snapshotSurface = wc.makeAnimationLeash()
                    .setName(name)
                    .setOpaque(true)
                    .setParent(wc.getSurfaceControl())
                    .setSecure(screenshotBuffer.containsSecureLayers())
                    .setCallsite("Transition.ScreenshotSync")
                    .setBLASTLayer()
                    .build();
            mFrozen.add(wc);
            final ChangeInfo changeInfo = Objects.requireNonNull(mChanges.get(wc));
            changeInfo.mSnapshot = snapshotSurface;
            if (isDisplayRotation) {
                // This isn't cheap, so only do it for display rotations.
                changeInfo.mSnapshotLuma = TransitionAnimation.getBorderLuma(
                        screenshotBuffer.getHardwareBuffer(), screenshotBuffer.getColorSpace());
            }
            SurfaceControl.Transaction t = wc.mWmService.mTransactionFactory.get();

            t.setBuffer(snapshotSurface, buffer);
            t.setDataSpace(snapshotSurface, screenshotBuffer.getColorSpace().getDataSpace());
            t.show(snapshotSurface);

            // Place it on top of anything else in the container.
            t.setLayer(snapshotSurface, Integer.MAX_VALUE);
            t.apply();
            t.close();

            // Detach the screenshot on the sync transaction (the screenshot is just meant to
            // freeze the window until the sync transaction is applied (with all its other
            // corresponding changes), so this is how we unfreeze it.
            wc.getSyncTransaction().reparent(snapshotSurface, null /* newParent */);
            return true;
        }

        @Override
        public void cleanUp(SurfaceControl.Transaction t) {
            for (int i = 0; i < mFrozen.size(); ++i) {
                SurfaceControl snap =
                        Objects.requireNonNull(mChanges.get(mFrozen.valueAt(i))).mSnapshot;
                // May be null if it was frozen via BLAST override.
                if (snap == null) continue;
                t.reparent(snap, null /* newParent */);
            }
        }
    }

    private static class Token extends Binder {
        final WeakReference<Transition> mTransition;

        Token(Transition transition) {
            mTransition = new WeakReference<>(transition);
        }

        @Override
        public String toString() {
            return "Token{" + Integer.toHexString(System.identityHashCode(this)) + " "
                    + mTransition.get() + "}";
        }
    }
}
