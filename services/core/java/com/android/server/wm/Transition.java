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
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_IS_RECENTS;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_LOCKED;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.TransitionFlags;
import static android.view.WindowManager.TransitionType;
import static android.view.WindowManager.transitTypeToString;
import static android.window.TransitionInfo.FLAG_DISPLAY_HAS_ALERT_WINDOWS;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;
import static android.window.TransitionInfo.FLAG_IS_VOICE_INTERACTION;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;
import static android.window.TransitionInfo.FLAG_OCCLUDES_KEYGUARD;
import static android.window.TransitionInfo.FLAG_SHOW_WALLPAPER;
import static android.window.TransitionInfo.FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;
import static android.window.TransitionInfo.FLAG_TRANSLUCENT;

import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_SPLASH_SCREEN;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_WINDOWS_DRAWN;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.window.RemoteTransition;
import android.window.TransitionInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Represents a logical transition.
 * @see TransitionController
 */
class Transition extends Binder implements BLASTSyncEngine.TransactionReadyListener {
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

    @IntDef(prefix = { "STATE_" }, value = {
            STATE_PENDING,
            STATE_COLLECTING,
            STATE_STARTED,
            STATE_PLAYING,
            STATE_ABORT
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TransitionState {}

    final @TransitionType int mType;
    private int mSyncId = -1;
    private @TransitionFlags int mFlags;
    private final TransitionController mController;
    private final BLASTSyncEngine mSyncEngine;
    private RemoteTransition mRemoteTransition = null;

    /** Only use for clean-up after binder death! */
    private SurfaceControl.Transaction mStartTransaction = null;
    private SurfaceControl.Transaction mFinishTransaction = null;

    /**
     * Contains change infos for both participants and all ancestors. We have to track ancestors
     * because they are all promotion candidates and thus we need their start-states
     * to be captured.
     */
    final ArrayMap<WindowContainer, ChangeInfo> mChanges = new ArrayMap<>();

    /** The collected participants in the transition. */
    final ArraySet<WindowContainer> mParticipants = new ArraySet<>();

    /** The final animation targets derived from participants after promotion. */
    private ArrayList<WindowContainer> mTargets;

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

    Transition(@TransitionType int type, @TransitionFlags int flags,
            TransitionController controller, BLASTSyncEngine syncEngine) {
        mType = type;
        mFlags = flags;
        mController = controller;
        mSyncEngine = syncEngine;
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
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Transition %d: Set %s as "
                + "transient-launch", mSyncId, activity);
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

    void setSeamlessRotation(@NonNull WindowContainer wc) {
        final ChangeInfo info = mChanges.get(wc);
        if (info == null) return;
        info.mFlags = info.mFlags | ChangeInfo.FLAG_SEAMLESS_ROTATION;
    }

    @VisibleForTesting
    int getSyncId() {
        return mSyncId;
    }

    @TransitionFlags
    int getFlags() {
        return mFlags;
    }

    /** Starts collecting phase. Once this starts, all relevant surface operations are sync. */
    void startCollecting(long timeoutMs) {
        if (mState != STATE_PENDING) {
            throw new IllegalStateException("Attempting to re-use a transition");
        }
        mState = STATE_COLLECTING;
        mSyncId = mSyncEngine.startSyncSet(this, timeoutMs, TAG);
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
    }

    /**
     * Adds wc to set of WindowContainers participating in this transition.
     */
    void collect(@NonNull WindowContainer wc) {
        if (mState < STATE_COLLECTING) {
            throw new IllegalStateException("Transition hasn't started collecting.");
        }
        if (mSyncId < 0) return;
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Collecting in transition %d: %s",
                mSyncId, wc);
        // "snapshot" all parents (as potential promotion targets). Do this before checking
        // if this is already a participant in case it has since been re-parented.
        for (WindowContainer curr = wc.getParent(); curr != null && !mChanges.containsKey(curr);
                curr = curr.getParent()) {
            mChanges.put(curr, new ChangeInfo(curr));
            if (isReadyGroup(curr)) {
                mReadyTracker.addGroup(curr);
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, " Creating Ready-group for"
                                + " Transition %d with root=%s", mSyncId, curr);
            }
        }
        if (mParticipants.contains(wc)) return;
        mSyncEngine.addToSyncSet(mSyncId, wc);
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
        if (mSyncId < 0) return;
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Existence Changed in transition %d:"
                + " %s", mSyncId, wc);
        collect(wc);
        mChanges.get(wc).mExistenceChanged = true;
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
        if (mSyncId < 0) return;
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
        if (mSyncId < 0) return;
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
        if (mSyncId < 0) return;
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
            final WindowContainer target = mTargets.get(i);
            if (target.getParent() != null) {
                final SurfaceControl targetLeash = getLeashSurface(target);
                final SurfaceControl origParent = getOrigParentSurface(target);
                // Ensure surfaceControls are re-parented back into the hierarchy.
                t.reparent(targetLeash, origParent);
                t.setLayer(targetLeash, target.getLastLayer());
                target.getRelativePosition(tmpPos);
                t.setPosition(targetLeash, tmpPos.x, tmpPos.y);
                t.setCornerRadius(targetLeash, 0);
                t.setShadowRadius(targetLeash, 0);
                t.setMatrix(targetLeash, 1, 0, 0, 1);
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
        // Need to update layers on involved displays since they were all paused while
        // the animation played. This puts the layers back into the correct order.
        for (int i = displays.size() - 1; i >= 0; --i) {
            if (displays.valueAt(i) == null) continue;
            displays.valueAt(i).assignChildLayers(t);
        }
        if (rootLeash.isValid()) {
            t.reparent(rootLeash, null);
        }
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
        mStartTransaction = mFinishTransaction = null;
        if (mState < STATE_PLAYING) {
            throw new IllegalStateException("Can't finish a non-playing transition " + mSyncId);
        }

        // Commit all going-invisible containers
        boolean activitiesWentInvisible = false;
        for (int i = 0; i < mParticipants.size(); ++i) {
            final ActivityRecord ar = mParticipants.valueAt(i).asActivityRecord();
            if (ar != null) {
                boolean visibleAtTransitionEnd = mVisibleAtTransitionEndTokens.contains(ar);
                // We need both the expected visibility AND current requested-visibility to be
                // false. If it is expected-visible but not currently visible, it means that
                // another animation is queued-up to animate this to invisibility, so we can't
                // remove the surfaces yet. If it is currently visible, but not expected-visible,
                // then doing commitVisibility here would actually be out-of-order and leave the
                // activity in a bad state.
                if (!visibleAtTransitionEnd && !ar.isVisibleRequested()) {
                    boolean commitVisibility = true;
                    if (ar.isVisible() && ar.getTask() != null) {
                        if (ar.pictureInPictureArgs != null
                                && ar.pictureInPictureArgs.isAutoEnterEnabled()) {
                            if (mTransientLaunches != null) {
                                for (int j = 0; j < mTransientLaunches.size(); ++j) {
                                    if (mTransientLaunches.keyAt(j).isVisibleRequested()) {
                                        // force enable pip-on-task-switch now that we've committed
                                        // to actually launching to the transient activity.
                                        ar.supportsEnterPipOnTaskSwitch = true;
                                        break;
                                    }
                                }
                            }
                            mController.mAtm.enterPictureInPictureMode(ar, ar.pictureInPictureArgs);
                            // Avoid commit visibility to false here, or else we will get a sudden
                            // "flash" / surface going invisible for a split second.
                            commitVisibility = false;
                        } else if (ar.getDeferHidingClient()) {
                            // Legacy PIP-enter requires pause event with user-leaving.
                            mController.mAtm.mTaskSupervisor.mUserLeaving = true;
                            ar.getTaskFragment().startPausing(false /* uiSleeping */,
                                    null /* resuming */, "finishTransition");
                            mController.mAtm.mTaskSupervisor.mUserLeaving = false;
                        }
                    }
                    if (commitVisibility) {
                        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                                "  Commit activity becoming invisible: %s", ar);
                        final Task task = ar.getTask();
                        if (task != null && !task.isVisibleRequested()
                                && mTransientLaunches != null) {
                            // If transition is transient, then snapshots are taken at end of
                            // transition.
                            mController.mTaskSnapshotController.recordTaskSnapshot(
                                    task, false /* allowSnapshotHome */);
                        }
                        ar.commitVisibility(false /* visible */, false /* performLayout */,
                                true /* fromTransition */);
                        activitiesWentInvisible = true;
                    }
                }
                if (mChanges.get(ar).mVisible != visibleAtTransitionEnd) {
                    // Legacy dispatch relies on this (for now).
                    ar.mEnteringAnimation = visibleAtTransitionEnd;
                }
            }
            final WallpaperWindowToken wt = mParticipants.valueAt(i).asWallpaperToken();
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
        if (activitiesWentInvisible) {
            // Always schedule stop processing when transition finishes because activities don't
            // stop while they are in a transition thus their stop could still be pending.
            mController.mAtm.mTaskSupervisor
                    .scheduleProcessStoppingAndFinishingActivitiesIfNeeded();
        }

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
            if (asyncRotationController != null && mTargets.contains(dc)) {
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
    }

    void abort() {
        // This calls back into itself via controller.abort, so just early return here.
        if (mState == STATE_ABORT) return;
        if (mState != STATE_COLLECTING) {
            throw new IllegalStateException("Too late to abort.");
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
            return;
        }
        // Ensure that wallpaper visibility is updated with the latest wallpaper target.
        for (int i = mParticipants.size() - 1; i >= 0; --i) {
            final WindowContainer<?> wc = mParticipants.valueAt(i);
            if (isWallpaper(wc) && wc.getDisplayContent() != null) {
                wc.getDisplayContent().mWallpaperController.adjustWallpaperWindows();
            }
        }

        mState = STATE_PLAYING;
        mController.moveToPlaying(this);

        if (dc.isKeyguardLocked()) {
            mFlags |= TRANSIT_FLAG_KEYGUARD_LOCKED;
        }

        // Resolve the animating targets from the participants
        mTargets = calculateTargets(mParticipants, mChanges);
        final TransitionInfo info = calculateTransitionInfo(mType, mFlags, mTargets, mChanges);
        if (mOverrideOptions != null) {
            info.setAnimationOptions(mOverrideOptions);
        }

        // TODO(b/188669821): Move to animation impl in shell.
        handleLegacyRecentsStartBehavior(dc, info);

        handleNonAppWindowsInTransition(dc, mType, mFlags);

        reportStartReasonsToLogger();

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
            if (ar == null || !ar.mVisibleRequested) continue;
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
            for (WindowContainer p = ar.getParent(); p != null && !mTargets.contains(p);
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
                mController.mTaskSnapshotController.recordTaskSnapshot(
                        ar.getTask(), false /* allowSnapshotHome */);
            }
        }

        // This is non-null only if display has changes. It handles the visible windows that don't
        // need to be participated in the transition.
        final AsyncRotationController controller = dc.getAsyncRotationController();
        if (controller != null && mTargets.contains(dc)) {
            controller.setupStartTransaction(transaction);
        }
        mStartTransaction = transaction;
        mFinishTransaction = mController.mAtm.mWindowManager.mTransactionFactory.get();
        buildFinishTransaction(mFinishTransaction, info.getRootLeash());
        if (mController.getTransitionPlayer() != null) {
            mController.dispatchLegacyAppTransitionStarting(info);
            try {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "Calling onTransitionReady: %s", info);
                mController.getTransitionPlayer().onTransitionReady(
                        this, info, transaction, mFinishTransaction);
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
        mSyncId = -1;
        mOverrideOptions = null;
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
        mController.finishTransition(this);
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

        // Hiding IME/IME icon when starting quick-step with resents animation.
        if (!mTargetDisplays.get(mRecentsDisplayId).isImeAttachedToApp()) {
            // Hiding IME if IME window is not attached to app.
            // Since some windowing mode is not proper to snapshot Task with IME window
            // while the app transitioning to the next task (e.g. split-screen mode)
            final InputMethodManagerInternal inputMethodManagerInternal =
                    LocalServices.getService(InputMethodManagerInternal.class);
            if (inputMethodManagerInternal != null) {
                inputMethodManagerInternal.hideCurrentInputMethod(
                        SoftInputShowHideReason.HIDE_RECENTS_ANIMATION);
            }
        } else {
            // Disable IME icon explicitly when IME attached to the app in case
            // IME icon might flickering while swiping to the next app task still
            // in animating before the next app window focused, or IME icon
            // persists on the bottom when swiping the task to recents.
            InputMethodManagerInternal.get().updateImeWindowStatus(
                    true /* disableImeIcon */);
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
            final Task task = mTargets.get(i).asTask();
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
        if ((transit == TRANSIT_KEYGUARD_GOING_AWAY
                || (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY) != 0)
                && !WindowManagerService.sEnableRemoteKeyguardGoingAwayAnimation) {
            if ((flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER) != 0
                    && (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION) == 0
                    && (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION) == 0) {
                Animation anim = mController.mAtm.mWindowManager.mPolicy
                        .createKeyguardWallpaperExit(
                                (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE) != 0);
                if (anim != null) {
                    anim.scaleCurrentDuration(
                            mController.mAtm.mWindowManager.getTransitionAnimationScaleLocked());
                    dc.mWallpaperController.startWallpaperAnimation(anim);
                }
            }
            dc.startKeyguardExitOnNonAppWindows(
                    (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER) != 0,
                    (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE) != 0,
                    (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION) != 0);
            if (!WindowManagerService.sEnableRemoteKeyguardGoingAwayAnimation) {
                // When remote animation is enabled for KEYGUARD_GOING_AWAY transition, SysUI
                // receives IRemoteAnimationRunner#onAnimationStart to start animation, so we don't
                // need to call IKeyguardService#keyguardGoingAway here.
                mController.mAtm.mWindowManager.mPolicy.startKeyguardExitAnimation(
                        SystemClock.uptimeMillis(), 0 /* duration */);
            }
        }
        if ((flags & TRANSIT_FLAG_KEYGUARD_LOCKED) != 0) {
            mController.mAtm.mWindowManager.mPolicy.applyKeyguardOcclusionChange(
                    true /* keyguardOccludingStarted */);
        }
    }

    private void reportStartReasonsToLogger() {
        // Record transition start in metrics logger. We just assume everything is "DRAWN"
        // at this point since splash-screen is a presentation (shell) detail.
        ArrayMap<WindowContainer, Integer> reasons = new ArrayMap<>();
        for (int i = mParticipants.size() - 1; i >= 0; --i) {
            ActivityRecord r = mParticipants.valueAt(i).asActivityRecord();
            if (r == null || !r.mVisibleRequested) continue;
            // At this point, r is "ready", but if it's not "ALL ready" then it is probably only
            // ready due to starting-window.
            reasons.put(r, (r.mStartingData instanceof SplashScreenStartingData
                    && !r.mLastAllReadyAtSync)
                    ? APP_TRANSITION_SPLASH_SCREEN : APP_TRANSITION_WINDOWS_DRAWN);
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

    private static boolean reportIfNotTop(WindowContainer wc) {
        // Organized tasks need to be reported anyways because Core won't show() their surfaces
        // and we can't rely on onTaskAppeared because it isn't in sync.
        // TODO(shell-transitions): switch onTaskAppeared usage over to transitions OPEN.
        return wc.isOrganized();
    }

    private static boolean isWallpaper(WindowContainer wc) {
        return wc.asWallpaperToken() != null;
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

    /**
     * Under some conditions (eg. all visible targets within a parent container are transitioning
     * the same way) the transition can be "promoted" to the parent container. This means an
     * animation can play just on the parent rather than all the individual children.
     *
     * @return {@code true} if transition in target can be promoted to its parent.
     */
    private static boolean canPromote(WindowContainer<?> target, Targets targets,
            ArrayMap<WindowContainer, ChangeInfo> changes) {
        final WindowContainer<?> parent = target.getParent();
        final ChangeInfo parentChange = changes.get(parent);
        if (!parent.canCreateRemoteAnimationTarget()
                || parentChange == null || !parentChange.hasChanged(parent)) {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "      SKIP: %s",
                    "parent can't be target " + parent);
            return false;
        }
        if (isWallpaper(target)) {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "      SKIP: is wallpaper");
            return false;
        }

        final @TransitionInfo.TransitionMode int mode = changes.get(target).getTransitMode(target);
        for (int i = parent.getChildCount() - 1; i >= 0; --i) {
            final WindowContainer<?> sibling = parent.getChildAt(i);
            if (target == sibling) continue;
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "      check sibling %s",
                    sibling);
            final ChangeInfo siblingChange = changes.get(sibling);
            if (siblingChange == null || !targets.wasParticipated(sibling)) {
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
            if (mode != siblingMode) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "          SKIP: common mode mismatch. was %s",
                        TransitionInfo.modeToString(mode));
                return false;
            }
        }
        return true;
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
            final WindowContainer<?> target = targets.mArray.valueAt(i);
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "    checking %s", target);
            final WindowContainer<?> parent = target.getParent();
            if (parent == lastNonPromotableParent) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "      SKIP: its sibling was rejected");
                continue;
            }
            if (!canPromote(target, targets, changes)) {
                lastNonPromotableParent = parent;
                continue;
            }
            if (reportIfNotTop(target)) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "        keep as target %s", target);
            } else {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "        remove from targets %s", target);
                targets.remove(i, target);
            }
            if (targets.mArray.indexOfValue(parent) < 0) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "      CAN PROMOTE: promoting to parent %s", parent);
                // The parent has lower depth, so it will be checked in the later iteration.
                i++;
                targets.add(parent);
            }
        }
    }

    /**
     * Find WindowContainers to be animated from a set of opening and closing apps. We will promote
     * animation targets to higher level in the window hierarchy if possible.
     */
    @VisibleForTesting
    @NonNull
    static ArrayList<WindowContainer> calculateTargets(ArraySet<WindowContainer> participants,
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
            if (!changeInfo.hasChanged(wc)) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "  Rejecting as no-op: %s", wc);
                continue;
            }
            targets.add(wc);
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "  Initial targets: %s",
                targets.mArray);
        // Combine the targets from bottom to top if possible.
        tryPromote(targets, changes);
        // Establish the relationship between the targets and their top changes.
        populateParentChanges(targets, changes);

        final ArrayList<WindowContainer> targetList = targets.getListSortedByZ();
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "  Final targets: %s", targetList);
        return targetList;
    }

    /** Populates parent to the change info and collects intermediate targets. */
    private static void populateParentChanges(Targets targets,
            ArrayMap<WindowContainer, ChangeInfo> changes) {
        final ArrayList<WindowContainer<?>> intermediates = new ArrayList<>();
        // Make a copy to iterate because the original array may be modified.
        final ArrayList<WindowContainer<?>> targetList = new ArrayList<>(targets.mArray.size());
        for (int i = targets.mArray.size() - 1; i >= 0; --i) {
            targetList.add(targets.mArray.valueAt(i));
        }
        for (int i = targetList.size() - 1; i >= 0; --i) {
            final WindowContainer<?> wc = targetList.get(i);
            // Wallpaper must belong to the top (regardless of how nested it is in DisplayAreas).
            final boolean skipIntermediateReports = isWallpaper(wc);
            intermediates.clear();
            boolean foundParentInTargets = false;
            // Collect the intermediate parents between target and top changed parent.
            for (WindowContainer<?> p = wc.getParent(); p != null; p = p.getParent()) {
                final ChangeInfo parentChange = changes.get(p);
                if (parentChange == null || !parentChange.hasChanged(p)) break;
                if (p.mRemoteToken == null) {
                    // Intermediate parents must be those that has window to be managed by Shell.
                    continue;
                }
                if (parentChange.mParent != null && !skipIntermediateReports) {
                    changes.get(wc).mParent = p;
                    // The chain above the parent was processed.
                    break;
                }
                if (targetList.contains(p)) {
                    if (skipIntermediateReports) {
                        changes.get(wc).mParent = p;
                    } else {
                        intermediates.add(p);
                    }
                    foundParentInTargets = true;
                    break;
                } else if (reportIfNotTop(p) && !skipIntermediateReports) {
                    intermediates.add(p);
                }
            }
            if (!foundParentInTargets || intermediates.isEmpty()) continue;
            // Add any always-report parents along the way.
            changes.get(wc).mParent = intermediates.get(0);
            for (int j = 0; j < intermediates.size() - 1; j++) {
                final WindowContainer<?> intermediate = intermediates.get(j);
                changes.get(intermediate).mParent = intermediates.get(j + 1);
                targets.add(intermediate);
            }
        }
    }

    /** Gets the leash surface for a window container */
    private static SurfaceControl getLeashSurface(WindowContainer wc) {
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
                final SurfaceControl leash = asToken.getOrCreateFixedRotationLeash();
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
     */
    @VisibleForTesting
    @NonNull
    static TransitionInfo calculateTransitionInfo(@TransitionType int type, int flags,
            ArrayList<WindowContainer> sortedTargets,
            ArrayMap<WindowContainer, ChangeInfo> changes) {
        final TransitionInfo out = new TransitionInfo(type, flags);

        WindowContainer<?> topApp = null;
        for (int i = 0; i < sortedTargets.size(); i++) {
            final WindowContainer<?> wc = sortedTargets.get(i);
            if (!isWallpaper(wc)) {
                topApp = wc;
                break;
            }
        }
        if (topApp == null) {
            out.setRootLeash(new SurfaceControl(), 0, 0);
            return out;
        }

        // Find the top-most shared ancestor of app targets.
        WindowContainer<?> ancestor = topApp.getParent();
        // Go up ancestor parent chain until all targets are descendants.
        ancestorLoop:
        while (ancestor != null) {
            for (int i = sortedTargets.size() - 1; i >= 0; --i) {
                final WindowContainer wc = sortedTargets.get(i);
                if (!isWallpaper(wc) && !wc.isDescendantOf(ancestor)) {
                    ancestor = ancestor.getParent();
                    continue ancestorLoop;
                }
            }
            break;
        }

        // make leash based on highest (z-order) direct child of ancestor with a participant.
        WindowContainer leashReference = sortedTargets.get(0);
        while (leashReference.getParent() != ancestor) {
            leashReference = leashReference.getParent();
        }
        final SurfaceControl rootLeash = leashReference.makeAnimationLeash().setName(
                "Transition Root: " + leashReference.getName()).build();
        SurfaceControl.Transaction t = ancestor.mWmService.mTransactionFactory.get();
        t.setLayer(rootLeash, leashReference.getLastLayer());
        t.apply();
        t.close();
        out.setRootLeash(rootLeash, ancestor.getBounds().left, ancestor.getBounds().top);

        // Convert all the resolved ChangeInfos into TransactionInfo.Change objects in order.
        final int count = sortedTargets.size();
        for (int i = 0; i < count; ++i) {
            final WindowContainer target = sortedTargets.get(i);
            final ChangeInfo info = changes.get(target);
            final TransitionInfo.Change change = new TransitionInfo.Change(
                    target.mRemoteToken != null ? target.mRemoteToken.toWindowContainerToken()
                            : null, getLeashSurface(target));
            // TODO(shell-transitions): Use leash for non-organized windows.
            if (info.mParent != null) {
                change.setParent(info.mParent.mRemoteToken.toWindowContainerToken());
            }
            change.setMode(info.getTransitMode(target));
            change.setStartAbsBounds(info.mAbsoluteBounds);
            change.setEndAbsBounds(target.getBounds());
            change.setEndRelOffset(target.getBounds().left - target.getParent().getBounds().left,
                    target.getBounds().top - target.getParent().getBounds().top);
            change.setFlags(info.getChangeFlags(target));
            change.setRotation(info.mRotation, target.getWindowConfiguration().getRotation());
            final Task task = target.asTask();
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
            final ActivityRecord activityRecord = target.asActivityRecord();
            if (activityRecord != null) {
                final Task arTask = activityRecord.getTask();
                final int backgroundColor = ColorUtils.setAlphaComponent(
                        arTask.getTaskDescription().getBackgroundColor(), 255);
                change.setBackgroundColor(backgroundColor);
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

    private static WindowManager.LayoutParams getLayoutParamsForAnimationsStyle(int type,
            ArrayList<WindowContainer> sortedTargets) {
        // Find the layout params of the top-most application window that is part of the
        // transition, which is what will control the animation theme.
        final ArraySet<Integer> activityTypes = new ArraySet<>();
        for (WindowContainer target : sortedTargets) {
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
            List<WindowContainer> sortedTargets,
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

    private static ActivityRecord lookForTopWindowWithFilter(List<WindowContainer> sortedTargets,
            Predicate<ActivityRecord> filter) {
        for (WindowContainer target : sortedTargets) {
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

    boolean getLegacyIsReady() {
        return (mState == STATE_STARTED || mState == STATE_COLLECTING) && mSyncId >= 0;
    }

    static Transition fromBinder(IBinder binder) {
        return (Transition) binder;
    }

    @VisibleForTesting
    static class ChangeInfo {
        private static final int FLAG_NONE = 0;

        /**
         * When set, the associated WindowContainer has been explicitly requested to be a
         * seamless rotation. This is currently only used by DisplayContent during fixed-rotation.
         */
        private static final int FLAG_SEAMLESS_ROTATION = 1;

        @IntDef(prefix = { "FLAG_" }, value = {
                FLAG_NONE,
                FLAG_SEAMLESS_ROTATION
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface Flag {}

        // Usually "post" change state.
        WindowContainer mParent;

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

        ChangeInfo(@NonNull WindowContainer origState) {
            mVisible = origState.isVisibleRequested();
            mWindowingMode = origState.getWindowingMode();
            mAbsoluteBounds.set(origState.getBounds());
            mShowWallpaper = origState.showWallpaper();
            mRotation = origState.getWindowConfiguration().getRotation();
        }

        @VisibleForTesting
        ChangeInfo(boolean visible, boolean existChange) {
            mVisible = visible;
            mExistenceChanged = existChange;
            mShowWallpaper = false;
        }

        boolean hasChanged(@NonNull WindowContainer newState) {
            // If it's invisible and hasn't changed visibility, always return false since even if
            // something changed, it wouldn't be a visible change.
            final boolean currVisible = newState.isVisibleRequested();
            if (currVisible == mVisible && !mVisible) return false;
            return currVisible != mVisible
                    || mKnownConfigChanges != 0
                    // if mWindowingMode is 0, this container wasn't attached at collect time, so
                    // assume no change in windowing-mode.
                    || (mWindowingMode != 0 && newState.getWindowingMode() != mWindowingMode)
                    || !newState.getBounds().equals(mAbsoluteBounds)
                    || mRotation != newState.getWindowConfiguration().getRotation();
        }

        @TransitionInfo.TransitionMode
        int getTransitMode(@NonNull WindowContainer wc) {
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
            if (!wc.fillsParent()) {
                // TODO(b/172695805): hierarchical check. This is non-trivial because for containers
                //                    it is effected by child visibility but needs to work even
                //                    before visibility is committed. This means refactoring some
                //                    checks to use requested visibility.
                flags |= FLAG_TRANSLUCENT;
            }
            final Task task = wc.asTask();
            if (task != null && task.voiceSession != null) {
                flags |= FLAG_IS_VOICE_INTERACTION;
            }
            if (task != null && task.isTranslucent(null)) {
                flags |= FLAG_TRANSLUCENT;
            }
            final ActivityRecord record = wc.asActivityRecord();
            if (record != null) {
                if (record.mUseTransferredAnimation) {
                    flags |= FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;
                }
                if (record.mVoiceInteraction) {
                    flags |= FLAG_IS_VOICE_INTERACTION;
                }
            }
            final DisplayContent dc = wc.asDisplayContent();
            if (dc != null) {
                flags |= FLAG_IS_DISPLAY;
                if (dc.hasAlertWindowSurfaces()) {
                    flags |= FLAG_DISPLAY_HAS_ALERT_WINDOWS;
                }
            }
            if (isWallpaper(wc)) {
                flags |= FLAG_IS_WALLPAPER;
            }
            if (occludesKeyguard(wc)) {
                flags |= FLAG_OCCLUDES_KEYGUARD;
            }
            return flags;
        }
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
                    + "override=%b states=[%s]", mUsed, mReadyOverride, groupsToString());
            if (!mUsed) return false;
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
        final SparseArray<WindowContainer<?>> mArray = new SparseArray<>();
        /** The targets which were represented by their parent. */
        private ArrayList<WindowContainer<?>> mRemovedTargets;
        private int mDepthFactor;

        void add(WindowContainer<?> target) {
            // The number of slots per depth is larger than the total number of window container,
            // so the depth score (key) won't have collision.
            if (mDepthFactor == 0) {
                mDepthFactor = target.mWmService.mRoot.getTreeWeight() + 1;
            }
            int score = target.getPrefixOrderIndex();
            WindowContainer<?> wc = target;
            while (wc != null) {
                final WindowContainer<?> parent = wc.getParent();
                if (parent != null) {
                    score += mDepthFactor;
                }
                wc = parent;
            }
            mArray.put(score, target);
        }

        void remove(int index, WindowContainer<?> removingTarget) {
            mArray.removeAt(index);
            if (mRemovedTargets == null) {
                mRemovedTargets = new ArrayList<>();
            }
            mRemovedTargets.add(removingTarget);
        }

        boolean wasParticipated(WindowContainer<?> wc) {
            return mArray.indexOfValue(wc) >= 0
                    || (mRemovedTargets != null && mRemovedTargets.contains(wc));
        }

        /** Returns the target list sorted by z-order in ascending order (index 0 is top). */
        ArrayList<WindowContainer> getListSortedByZ() {
            final SparseArray<WindowContainer<?>> arrayByZ = new SparseArray<>(mArray.size());
            for (int i = mArray.size() - 1; i >= 0; --i) {
                final int zOrder = mArray.keyAt(i) % mDepthFactor;
                arrayByZ.put(zOrder, mArray.valueAt(i));
            }
            final ArrayList<WindowContainer> sortedTargets = new ArrayList<>(arrayByZ.size());
            for (int i = arrayByZ.size() - 1; i >= 0; --i) {
                sortedTargets.add(arrayByZ.valueAt(i));
            }
            return sortedTargets;
        }
    }
}
