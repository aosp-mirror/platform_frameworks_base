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


import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
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
import static android.view.WindowManager.transitTypeToString;
import static android.window.TransitionInfo.FLAG_IS_VOICE_INTERACTION;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;
import static android.window.TransitionInfo.FLAG_SHOW_WALLPAPER;
import static android.window.TransitionInfo.FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;
import static android.window.TransitionInfo.FLAG_TRANSLUCENT;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.ActivityManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.window.IRemoteTransition;
import android.window.TransitionInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;

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

    final @WindowManager.TransitionType int mType;
    private int mSyncId;
    private @WindowManager.TransitionFlags int mFlags;
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

    private @TransitionState int mState = STATE_COLLECTING;
    private boolean mReadyCalled = false;

    Transition(@WindowManager.TransitionType int type, @WindowManager.TransitionFlags int flags,
            TransitionController controller, BLASTSyncEngine syncEngine) {
        mType = type;
        mFlags = flags;
        mController = controller;
        mSyncEngine = syncEngine;
        mSyncId = mSyncEngine.startSyncSet(this);
    }

    @VisibleForTesting
    int getSyncId() {
        return mSyncId;
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
        if (mReadyCalled) {
            setReady();
        }
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

    /**
     * Call this when all known changes related to this transition have been applied. Until
     * all participants have finished drawing, the transition can still collect participants.
     *
     * If this is called before the transition is started, it will be deferred until start.
     */
    void setReady(boolean ready) {
        if (mSyncId < 0) return;
        if (mState < STATE_STARTED) {
            mReadyCalled = ready;
            return;
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                "Set transition ready=%b %d", ready, mSyncId);
        mSyncEngine.setReady(mSyncId, ready);
    }

    /** @see #setReady . This calls with parameter true. */
    void setReady() {
        setReady(true);
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
        for (int i = 0; i < mParticipants.size(); ++i) {
            final ActivityRecord ar = mParticipants.valueAt(i).asActivityRecord();
            if (ar != null && !ar.isVisibleRequested()) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "  Commit activity becoming invisible: %s", ar);
                ar.commitVisibility(false /* visible */, false /* performLayout */);
            }
            final WallpaperWindowToken wt = mParticipants.valueAt(i).asWallpaperToken();
            if (wt != null && !wt.isVisibleRequested()) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "  Commit wallpaper becoming invisible: %s", ar);
                wt.commitVisibility(false /* visible */);
            }
        }
    }

    void abort() {
        // This calls back into itself via controller.abort, so just early return here.
        if (mState == STATE_ABORT) return;
        if (mState != STATE_COLLECTING) {
            throw new IllegalStateException("Too late to abort.");
        }
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

        handleNonAppWindowsInTransition(displayId, mType, mFlags);

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
        }

        mStartTransaction = transaction;
        mFinishTransaction = mController.mAtm.mWindowManager.mTransactionFactory.get();
        buildFinishTransaction(mFinishTransaction, info.getRootLeash());
        if (mController.getTransitionPlayer() != null) {
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

    private void handleNonAppWindowsInTransition(int displayId,
            @WindowManager.TransitionType int transit, int flags) {
        final DisplayContent dc =
                mController.mAtm.mRootWindowContainer.getDisplayContent(displayId);
        if (dc == null) {
            return;
        }
        if (transit == TRANSIT_KEYGUARD_GOING_AWAY
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
            if (!wc.isAttached()) continue;

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
            for (WindowContainer p = wc.getParent(); p != null; p = p.getParent()) {
                if (!p.isAttached() || !changes.get(p).hasChanged(p)) {
                    // Again, we're skipping no-ops
                    break;
                }
                if (participants.contains(p)) {
                    topParent = p;
                    break;
                } else if (reportIfNotTop(p)) {
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
     * Construct a TransitionInfo object from a set of targets and changes. Also populates the
     * root surface.
     */
    @VisibleForTesting
    @NonNull
    static TransitionInfo calculateTransitionInfo(int type, int flags,
            ArraySet<WindowContainer> targets, ArrayMap<WindowContainer, ChangeInfo> changes) {
        final TransitionInfo out = new TransitionInfo(type, flags);

        final ArraySet<WindowContainer> appTargets = new ArraySet<>();
        final ArraySet<WindowContainer> wallpapers = new ArraySet<>();
        for (int i = targets.size() - 1; i >= 0; --i) {
            (isWallpaper(targets.valueAt(i)) ? wallpapers : appTargets).add(targets.valueAt(i));
        }

        // Find the top-most shared ancestor of app targets
        WindowContainer ancestor = null;
        for (int i = appTargets.size() - 1; i >= 0; --i) {
            final WindowContainer wc = appTargets.valueAt(i);
            ancestor = wc;
            break;
        }
        if (ancestor == null) {
            out.setRootLeash(new SurfaceControl(), 0, 0);
            return out;
        }
        ancestor = ancestor.getParent();

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
            }
            out.addChange(change);
        }

        return out;
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
            if (isWallpaper(wc)) {
                flags |= FLAG_IS_WALLPAPER;
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
}
