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
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.INPUT_CONSUMER_RECENTS_ANIMATION;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_UNSPECIFIED;
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
import static android.view.WindowManager.TRANSIT_NONE;
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
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.window.IRemoteTransition;
import android.window.TransitionInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.function.pooled.PooledLambda;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * Represents a logical transition.
 * @see TransitionController
 */
class Transition extends Binder implements BLASTSyncEngine.TransactionReadyListener {
    private static final String TAG = "Transition";

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
            STATE_COLLECTING,
            STATE_STARTED,
            STATE_PLAYING,
            STATE_ABORT
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface TransitionState {}

    final @TransitionType int mType;
    private int mSyncId;
    private @TransitionFlags int mFlags;
    private final TransitionController mController;
    private final BLASTSyncEngine mSyncEngine;
    private IRemoteTransition mRemoteTransition = null;

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
    private ArraySet<WindowContainer> mTargets = null;

    /**
     * Set of participating windowtokens (activity/wallpaper) which are visible at the end of
     * the transition animation.
     */
    private final ArraySet<WindowToken> mVisibleAtTransitionEndTokens = new ArraySet<>();

    /** Custom activity-level animation options and callbacks. */
    private TransitionInfo.AnimationOptions mOverrideOptions;
    private IRemoteCallback mClientAnimationStartCallback = null;
    private IRemoteCallback mClientAnimationFinishCallback = null;

    private @TransitionState int mState = STATE_COLLECTING;
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
        mSyncId = mSyncEngine.startSyncSet(this);
    }

    void addFlag(int flag) {
        mFlags |= flag;
    }

    @VisibleForTesting
    int getSyncId() {
        return mSyncId;
    }

    @TransitionFlags
    int getFlags() {
        return mFlags;
    }

    /**
     * Formally starts the transition. Participants can be collected before this is started,
     * but this won't consider itself ready until started -- even if all the participants have
     * drawn.
     */
    void start() {
        if (mState >= STATE_STARTED) {
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
        if (info.mShowWallpaper) {
            // Collect the wallpaper so it is part of the sync set.
            final WindowContainer wallpaper =
                    wc.getDisplayContent().mWallpaperController.getTopVisibleWallpaper();
            if (wallpaper != null) {
                collect(wallpaper);
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
            final WindowContainer target = mTargets.valueAt(i);
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
                    if (ar.getDeferHidingClient() && ar.getTask() != null) {
                        if (ar.pictureInPictureArgs != null
                                && ar.pictureInPictureArgs.isAutoEnterEnabled()) {
                            mController.mAtm.enterPictureInPictureMode(ar, ar.pictureInPictureArgs);
                            // Avoid commit visibility to false here, or else we will get a sudden
                            // "flash" / surface going invisible for a split second.
                            commitVisibility = false;
                        } else {
                            mController.mAtm.mTaskSupervisor.mUserLeaving = true;
                            ar.getTaskFragment().startPausing(false /* uiSleeping */,
                                    null /* resuming */, "finishTransition");
                            mController.mAtm.mTaskSupervisor.mUserLeaving = false;
                        }
                    }
                    if (commitVisibility) {
                        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                                "  Commit activity becoming invisible: %s", ar);
                        ar.commitVisibility(false /* visible */, false /* performLayout */);
                        activitiesWentInvisible = true;
                    }
                }
                if (mChanges.get(ar).mVisible != visibleAtTransitionEnd) {
                    // Legacy dispatch relies on this (for now).
                    ar.mEnteringAnimation = visibleAtTransitionEnd;
                }
                mController.dispatchLegacyAppTransitionFinished(ar);
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
    }

    void abort() {
        // This calls back into itself via controller.abort, so just early return here.
        if (mState == STATE_ABORT) return;
        if (mState != STATE_COLLECTING) {
            throw new IllegalStateException("Too late to abort.");
        }
        mController.dispatchLegacyAppTransitionCancelled();
        mState = STATE_ABORT;
        // Syncengine abort will call through to onTransactionReady()
        mSyncEngine.abort(mSyncId);
    }

    void setRemoteTransition(IRemoteTransition remoteTransition) {
        mRemoteTransition = remoteTransition;
    }

    IRemoteTransition getRemoteTransition() {
        return mRemoteTransition;
    }

    @Override
    public void onTransactionReady(int syncId, SurfaceControl.Transaction transaction) {
        if (syncId != mSyncId) {
            Slog.e(TAG, "Unexpected Sync ID " + syncId + ". Expected " + mSyncId);
            return;
        }
        int displayId = DEFAULT_DISPLAY;
        for (WindowContainer container : mParticipants) {
            if (container.mDisplayContent == null) continue;
            displayId = container.mDisplayContent.getDisplayId();
        }

        if (mState == STATE_ABORT) {
            mController.abort(this);
            mController.mAtm.mRootWindowContainer.getDisplayContent(displayId)
                    .getPendingTransaction().merge(transaction);
            mSyncId = -1;
            mOverrideOptions = null;
            return;
        }

        mState = STATE_PLAYING;
        mController.moveToPlaying(this);

        if (mController.mAtm.mTaskSupervisor.getKeyguardController().isKeyguardLocked()) {
            mFlags |= TRANSIT_FLAG_KEYGUARD_LOCKED;
        }

        // Resolve the animating targets from the participants
        mTargets = calculateTargets(mParticipants, mChanges);
        final TransitionInfo info = calculateTransitionInfo(mType, mFlags, mTargets, mChanges);
        info.setAnimationOptions(mOverrideOptions);

        // TODO(b/188669821): Move to animation impl in shell.
        handleLegacyRecentsStartBehavior(displayId, info);

        handleNonAppWindowsInTransition(displayId, mType, mFlags);

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
        finishTransition();
    }

    /** @see RecentsAnimationController#attachNavigationBarToApp */
    private void handleLegacyRecentsStartBehavior(int displayId, TransitionInfo info) {
        if ((mFlags & TRANSIT_FLAG_IS_RECENTS) == 0) {
            return;
        }
        final DisplayContent dc =
                mController.mAtm.mRootWindowContainer.getDisplayContent(displayId);
        if (dc == null) return;
        mRecentsDisplayId = displayId;

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
                || dc.getFadeRotationAnimationController() != null) {
            return;
        }

        WindowContainer topWC = null;
        // Find the top-most non-home, closing app.
        for (int i = 0; i < info.getChanges().size(); ++i) {
            final TransitionInfo.Change c = info.getChanges().get(i);
            if (c.getTaskInfo() == null || c.getTaskInfo().displayId != displayId
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
            mController.mStatusBar.setNavigationBarLumaSamplingEnabled(displayId, false);
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
            final Task task = mTargets.valueAt(i).asTask();
            if (task == null || !task.isHomeOrRecentsRootTask()) continue;
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

    private void handleNonAppWindowsInTransition(int displayId,
            @TransitionType int transit, @TransitionFlags int flags) {
        final DisplayContent dc =
                mController.mAtm.mRootWindowContainer.getDisplayContent(displayId);
        if (dc == null) {
            return;
        }
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
            mController.mAtm.mWindowManager.mPolicy.startKeyguardExitAnimation(
                    SystemClock.uptimeMillis(), 0 /* duration */);
        }
        if ((flags & TRANSIT_FLAG_KEYGUARD_LOCKED) != 0) {
            mController.mAtm.mWindowManager.mPolicy.applyKeyguardOcclusionChange();
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
        // Also report wallpaper so it can be handled properly during display change/rotation.
        // TODO(shell-transitions): switch onTaskAppeared usage over to transitions OPEN.
        return wc.isOrganized() || isWallpaper(wc);
    }

    /** @return the depth of child within ancestor, 0 if child == ancestor, or -1 if not a child. */
    private static int getChildDepth(WindowContainer child, WindowContainer ancestor) {
        WindowContainer parent = child;
        int depth = 0;
        while (parent != null) {
            if (parent == ancestor) {
                return depth;
            }
            parent = parent.getParent();
            ++depth;
        }
        return -1;
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
    private static boolean canPromote(WindowContainer target, ArraySet<WindowContainer> topTargets,
            ArrayMap<WindowContainer, ChangeInfo> changes) {
        final WindowContainer parent = target.getParent();
        final ChangeInfo parentChanges = parent != null ? changes.get(parent) : null;
        if (parent == null || !parent.canCreateRemoteAnimationTarget()
                || parentChanges == null || !parentChanges.hasChanged(parent)) {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "      SKIP: %s",
                    parent == null ? "no parent" : ("parent can't be target " + parent));
            return false;
        }
        if (isWallpaper(target)) {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "      SKIP: is wallpaper");
            return false;
        }
        @TransitionInfo.TransitionMode int mode = TRANSIT_NONE;
        // Go through all siblings of this target to see if any of them would prevent
        // the target from promoting.
        siblingLoop:
        for (int i = parent.getChildCount() - 1; i >= 0; --i) {
            final WindowContainer sibling = parent.getChildAt(i);
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "      check sibling %s",
                    sibling);
            // Check if any topTargets are the sibling or within it
            for (int j = topTargets.size() - 1; j >= 0; --j) {
                final int depth = getChildDepth(topTargets.valueAt(j), sibling);
                if (depth < 0) continue;
                if (depth == 0) {
                    final int siblingMode = changes.get(sibling).getTransitMode(sibling);
                    ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                            "        sibling is a top target with mode %s",
                            TransitionInfo.modeToString(siblingMode));
                    if (mode == TRANSIT_NONE) {
                        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                                "          no common mode yet, so set it");
                        mode = siblingMode;
                    } else if (mode != siblingMode) {
                        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                                "          SKIP: common mode mismatch. was %s",
                                TransitionInfo.modeToString(mode));
                        return false;
                    }
                    continue siblingLoop;
                } else {
                    // Sibling subtree may not be promotable.
                    ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                            "        SKIP: sibling contains top target %s",
                            topTargets.valueAt(j));
                    return false;
                }
            }
            // No other animations are playing in this sibling
            if (sibling.isVisibleRequested()) {
                // Sibling is visible but not animating, so no promote.
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "        SKIP: sibling is visible but not part of transition");
                return false;
            }
        }
        return true;
    }

    /**
     * Go through topTargets and try to promote (see {@link #canPromote}) one of them.
     *
     * @param topTargets set of just the top-most targets in the hierarchy of participants.
     * @param targets all targets that will be sent to the player.
     * @return {@code true} if something was promoted.
     */
    private static boolean tryPromote(ArraySet<WindowContainer> topTargets,
            ArraySet<WindowContainer> targets, ArrayMap<WindowContainer, ChangeInfo> changes) {
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "  --- Start combine pass ---");
        // Go through each target until we find one that can be promoted.
        for (WindowContainer targ : topTargets) {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "    checking %s", targ);
            if (!canPromote(targ, topTargets, changes)) {
                continue;
            }
            // No obstructions found to promotion, so promote
            final WindowContainer parent = targ.getParent();
            final ChangeInfo parentInfo = changes.get(parent);
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                    "      CAN PROMOTE: promoting to parent %s", parent);
            targets.add(parent);

            // Go through all children of newly-promoted container and remove them from the
            // top-targets.
            for (int i = parent.getChildCount() - 1; i >= 0; --i) {
                final WindowContainer child = parent.getChildAt(i);
                int idx = targets.indexOf(child);
                if (idx >= 0) {
                    final ChangeInfo childInfo = changes.get(child);
                    if (reportIfNotTop(child)) {
                        childInfo.mParent = parent;
                        parentInfo.addChild(child);
                        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                                "        keep as target %s", child);
                    } else {
                        if (childInfo.mChildren != null) {
                            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                                    "        merging children in from %s: %s", child,
                                    childInfo.mChildren);
                            parentInfo.addChildren(childInfo.mChildren);
                        }
                        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                                "        remove from targets %s", child);
                        targets.removeAt(idx);
                    }
                }
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "        remove from topTargets %s", child);
                topTargets.remove(child);
            }
            topTargets.add(parent);
            return true;
        }
        return false;
    }

    /**
     * Find WindowContainers to be animated from a set of opening and closing apps. We will promote
     * animation targets to higher level in the window hierarchy if possible.
     */
    @VisibleForTesting
    @NonNull
    static ArraySet<WindowContainer> calculateTargets(ArraySet<WindowContainer> participants,
            ArrayMap<WindowContainer, ChangeInfo> changes) {
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                "Start calculating TransitionInfo based on participants: %s", participants);

        final ArraySet<WindowContainer> topTargets = new ArraySet<>();
        // The final animation targets which cannot promote to higher level anymore.
        final ArraySet<WindowContainer> targets = new ArraySet<>();

        final ArrayList<WindowContainer> tmpList = new ArrayList<>();

        // Build initial set of top-level participants by removing any participants that are no-ops
        // or children of other participants or are otherwise invalid; however, keep around a list
        // of participants that should always be reported even if they aren't top.
        for (WindowContainer wc : participants) {
            // Don't include detached windows.
            if (!wc.isAttached()) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "  Rejecting as detached: %s", wc);
                continue;
            }

            final ChangeInfo changeInfo = changes.get(wc);

            // Reject no-ops
            if (!changeInfo.hasChanged(wc)) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "  Rejecting as no-op: %s", wc);
                continue;
            }

            // Search through ancestors to find the top-most participant (if one exists)
            WindowContainer topParent = null;
            tmpList.clear();
            if (reportIfNotTop(wc)) {
                tmpList.add(wc);
            }
            // Wallpaper must be the top (regardless of how nested it is in DisplayAreas).
            boolean skipIntermediateReports = isWallpaper(wc);
            for (WindowContainer p = wc.getParent(); p != null; p = p.getParent()) {
                if (!p.isAttached() || !changes.get(p).hasChanged(p)) {
                    // Again, we're skipping no-ops
                    break;
                }
                if (participants.contains(p)) {
                    topParent = p;
                    break;
                } else if (isWallpaper(p)) {
                    skipIntermediateReports = true;
                } else if (reportIfNotTop(p) && !skipIntermediateReports) {
                    tmpList.add(p);
                }
            }
            if (topParent != null) {
                // There was an ancestor participant, so don't add wc to targets unless always-
                // report. Similarly, add any always-report parents along the way.
                for (int i = 0; i < tmpList.size(); ++i) {
                    targets.add(tmpList.get(i));
                    final ChangeInfo info = changes.get(tmpList.get(i));
                    info.mParent = i < tmpList.size() - 1 ? tmpList.get(i + 1) : topParent;
                }
                continue;
            }
            // No ancestors in participant-list, so wc is a top target.
            targets.add(wc);
            topTargets.add(wc);
        }

        // Populate children lists
        for (int i = targets.size() - 1; i >= 0; --i) {
            if (changes.get(targets.valueAt(i)).mParent != null) {
                changes.get(changes.get(targets.valueAt(i)).mParent).addChild(targets.valueAt(i));
            }
        }

        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "  Initial targets: %s", targets);
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "  Top targets: %s", topTargets);

        // Combine targets by repeatedly going through the topTargets to see if they can be
        // promoted until there aren't any promotions possible.
        while (tryPromote(topTargets, targets, changes)) {
            // Empty on purpose
        }
        return targets;
    }

    /** Add any of `members` within `root` to `out` in top-to-bottom z-order. */
    private static void addMembersInOrder(WindowContainer root, ArraySet<WindowContainer> members,
            ArrayList<WindowContainer> out) {
        for (int i = root.getChildCount() - 1; i >= 0; --i) {
            final WindowContainer child = root.getChildAt(i);
            addMembersInOrder(child, members, out);
            if (members.contains(child)) {
                out.add(child);
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
     */
    @VisibleForTesting
    @NonNull
    static TransitionInfo calculateTransitionInfo(@TransitionType int type, int flags,
            ArraySet<WindowContainer> targets, ArrayMap<WindowContainer, ChangeInfo> changes) {
        final TransitionInfo out = new TransitionInfo(type, flags);

        final ArraySet<WindowContainer> appTargets = new ArraySet<>();
        final ArraySet<WindowContainer> wallpapers = new ArraySet<>();
        for (int i = targets.size() - 1; i >= 0; --i) {
            (isWallpaper(targets.valueAt(i)) ? wallpapers : appTargets).add(targets.valueAt(i));
        }

        // Find the top-most shared ancestor of app targets
        if (appTargets.isEmpty()) {
            out.setRootLeash(new SurfaceControl(), 0, 0);
            return out;
        }
        WindowContainer ancestor = appTargets.valueAt(appTargets.size() - 1).getParent();

        // Go up ancestor parent chain until all targets are descendants.
        ancestorLoop:
        while (ancestor != null) {
            for (int i = appTargets.size() - 1; i >= 0; --i) {
                final WindowContainer wc = appTargets.valueAt(i);
                if (!wc.isDescendantOf(ancestor)) {
                    ancestor = ancestor.getParent();
                    continue ancestorLoop;
                }
            }
            break;
        }

        // Sort targets top-to-bottom in Z. Check ALL targets here in case the display area itself
        // is animating: then we want to include wallpapers at the right position.
        ArrayList<WindowContainer> sortedTargets = new ArrayList<>();
        addMembersInOrder(ancestor, targets, sortedTargets);

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

        // add the wallpapers at the bottom
        for (int i = wallpapers.size() - 1; i >= 0; --i) {
            final WindowContainer wc = wallpapers.valueAt(i);
            // If the displayarea itself is animating, then the wallpaper was already added.
            if (wc.isDescendantOf(ancestor)) break;
            sortedTargets.add(wc);
        }

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
            }
            out.addChange(change);
        }

        return out;
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
        return mState == STATE_STARTED && mSyncId >= 0 && mSyncEngine.isReady(mSyncId);
    }

    static Transition fromBinder(IBinder binder) {
        return (Transition) binder;
    }

    @VisibleForTesting
    static class ChangeInfo {
        // Usually "post" change state.
        WindowContainer mParent;
        ArraySet<WindowContainer> mChildren;

        // State tracking
        boolean mExistenceChanged = false;
        // before change state
        boolean mVisible;
        int mWindowingMode;
        final Rect mAbsoluteBounds = new Rect();
        boolean mShowWallpaper;
        int mRotation = ROTATION_UNDEFINED;

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

        void addChild(@NonNull WindowContainer wc) {
            if (mChildren == null) {
                mChildren = new ArraySet<>();
            }
            mChildren.add(wc);
        }
        void addChildren(@NonNull ArraySet<WindowContainer> wcs) {
            if (mChildren == null) {
                mChildren = new ArraySet<>();
            }
            mChildren.addAll(wcs);
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
}
