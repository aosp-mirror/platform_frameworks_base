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


import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY_WITH_WALLPAPER;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER;

import android.annotation.NonNull;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.window.TransitionInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;

import java.util.ArrayList;
import java.util.Map;

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

    final @WindowManager.TransitionType int mType;
    private int mSyncId;
    private @WindowManager.TransitionFlags int mFlags;
    private final TransitionController mController;
    private final BLASTSyncEngine mSyncEngine;
    final ArrayMap<WindowContainer, ChangeInfo> mParticipants = new ArrayMap<>();
    private int mState = STATE_COLLECTING;
    private boolean mReadyCalled = false;

    Transition(@WindowManager.TransitionType int type,
            @WindowManager.TransitionFlags int flags, TransitionController controller) {
        mType = type;
        mFlags = flags;
        mController = controller;
        mSyncEngine = mController.mAtm.mWindowManager.mSyncEngine;
        mSyncId = mSyncEngine.startSyncSet(this);
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

    /** Adds wc to set of WindowContainers participating in this transition. */
    void collect(@NonNull WindowContainer wc) {
        if (mSyncId < 0) return;
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Collecting in transition %d: %s",
                mSyncId, wc);
        if (mParticipants.containsKey(wc)) return;
        mSyncEngine.addToSyncSet(mSyncId, wc);
        mParticipants.put(wc, new ChangeInfo());
    }

    /**
     * Call this when all known changes related to this transition have been applied. Until
     * all participants have finished drawing, the transition can still collect participants.
     *
     * If this is called before the transition is started, it will be deferred until start.
     */
    void setReady() {
        if (mSyncId < 0) return;
        if (mState < STATE_STARTED) {
            mReadyCalled = true;
            return;
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                "Finish collecting in transition %d", mSyncId);
        mSyncEngine.setReady(mSyncId);
    }

    /** The transition has finished animating and is ready to finalize WM state */
    void finishTransition() {
        if (mState < STATE_PLAYING) {
            throw new IllegalStateException("Can't finish a non-playing transition " + mSyncId);
        }
        for (int i = 0; i < mParticipants.size(); ++i) {
            final ActivityRecord ar = mParticipants.keyAt(i).asActivityRecord();
            if (ar == null || ar.mVisibleRequested) {
                continue;
            }
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                    "  Commit activity becoming invisible: %s", ar);
            ar.commitVisibility(false /* visible */, false /* performLayout */);
        }
    }

    @Override
    public void onTransactionReady(int syncId, SurfaceControl.Transaction transaction) {
        if (syncId != mSyncId) {
            Slog.e(TAG, "Unexpected Sync ID " + syncId + ". Expected " + mSyncId);
            return;
        }
        mState = STATE_PLAYING;
        mController.moveToPlaying(this);
        final TransitionInfo info = calculateTransitionInfo(mType, mParticipants);

        int displayId = DEFAULT_DISPLAY;
        for (WindowContainer container : mParticipants.keySet()) {
            displayId = container.mDisplayContent.getDisplayId();
        }

        handleNonAppWindowsInTransition(displayId, mType, mFlags);

        if (mController.getTransitionPlayer() != null) {
            try {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "Calling onTransitionReady: %s", info);
                mController.getTransitionPlayer().onTransitionReady(this, info, transaction);
            } catch (RemoteException e) {
                // If there's an exception when trying to send the mergedTransaction to the
                // client, we should immediately apply it here so the transactions aren't lost.
                transaction.apply();
            }
        } else {
            transaction.apply();
        }
        mSyncId = -1;
    }

    private void handleNonAppWindowsInTransition(int displayId, int transit, int flags) {
        final DisplayContent dc =
                mController.mAtm.mRootWindowContainer.getDisplayContent(displayId);
        if (dc == null) {
            return;
        }
        if (transit == TRANSIT_KEYGUARD_GOING_AWAY) {
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
        }
        if (transit == TRANSIT_KEYGUARD_GOING_AWAY
                || transit == TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER) {
            dc.startKeyguardExitOnNonAppWindows(
                    transit == TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER,
                    (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_TO_SHADE) != 0,
                    (flags & TRANSIT_FLAG_KEYGUARD_GOING_AWAY_SUBTLE_ANIMATION) != 0);
            mController.mAtm.mWindowManager.mPolicy.startKeyguardExitAnimation(transit, 0);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("TransitionRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" id=" + mSyncId);
        sb.append(" type=" + mType);
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

    private static @TransitionInfo.TransitionMode int getModeFor(WindowContainer wc) {
        if (wc.isVisibleRequested()) {
            final Task t = wc.asTask();
            if (t != null && t.getHasBeenVisible()) {
                return TransitionInfo.TRANSIT_SHOW;
            }
            return TransitionInfo.TRANSIT_OPEN;
        }
        return TransitionInfo.TRANSIT_CLOSE;
    }

    /**
     * Under some conditions (eg. all visible targets within a parent container are transitioning
     * the same way) the transition can be "promoted" to the parent container. This means an
     * animation can play just on the parent rather than all the individual children.
     *
     * @return {@code true} if transition in target can be promoted to its parent.
     */
    private static boolean canPromote(
            WindowContainer target, ArraySet<WindowContainer> topTargets) {
        final WindowContainer parent = target.getParent();
        if (parent == null || !parent.canCreateRemoteAnimationTarget()) {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "      SKIP: %s",
                    parent == null ? "no parent" : ("parent can't be target " + parent));
            return false;
        }
        @TransitionInfo.TransitionMode int mode = TransitionInfo.TRANSIT_NONE;
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
                    final int siblingMode = sibling.isVisibleRequested()
                            ? TransitionInfo.TRANSIT_OPEN : TransitionInfo.TRANSIT_CLOSE;
                    ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                            "        sibling is a top target with mode %s",
                            TransitionInfo.modeToString(siblingMode));
                    if (mode == TransitionInfo.TRANSIT_NONE) {
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
            ArrayMap<WindowContainer, ChangeInfo> targets) {
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "  --- Start combine pass ---");
        // Go through each target until we find one that can be promoted.
        targetLoop:
        for (WindowContainer targ : topTargets) {
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "    checking %s", targ);
            if (!canPromote(targ, topTargets)) {
                continue;
            }
            final WindowContainer parent = targ.getParent();
            // No obstructions found to promotion, so promote
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                    "      CAN PROMOTE: promoting to parent %s", parent);
            final ChangeInfo parentInfo = new ChangeInfo();
            targets.put(parent, parentInfo);
            // Go through all children of newly-promoted container and remove them from
            // the top-targets.
            for (int i = parent.getChildCount() - 1; i >= 0; --i) {
                final WindowContainer child = parent.getChildAt(i);
                int idx = targets.indexOfKey(child);
                if (idx >= 0) {
                    if (reportIfNotTop(child)) {
                        targets.valueAt(idx).mParent = parent;
                        parentInfo.addChild(child);
                        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                                "        keep as target %s", child);
                    } else {
                        if (targets.valueAt(idx).mChildren != null) {
                            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                                    "        merging children in from %s: %s", child,
                                    targets.valueAt(idx).mChildren);
                            parentInfo.addChildren(targets.valueAt(idx).mChildren);
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
    static TransitionInfo calculateTransitionInfo(
            int type, Map<WindowContainer, ChangeInfo> participants) {
        final TransitionInfo out = new TransitionInfo(type);

        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                "Start calculating TransitionInfo based on participants: %s",
                new ArraySet<>(participants.keySet()));

        final ArraySet<WindowContainer> topTargets = new ArraySet<>();
        // The final animation targets which cannot promote to higher level anymore.
        final ArrayMap<WindowContainer, ChangeInfo> targets = new ArrayMap<>();

        final ArrayList<WindowContainer> tmpList = new ArrayList<>();

        // Build initial set of top-level participants by removing any participants that are
        // children of other participants or are otherwise invalid.
        for (Map.Entry<WindowContainer, ChangeInfo> entry : participants.entrySet()) {
            final WindowContainer wc = entry.getKey();
            // Don't include detached windows.
            if (!wc.isAttached()) continue;

            final ChangeInfo changeInfo = entry.getValue();
            WindowContainer parent = wc.getParent();
            WindowContainer topParent = null;
            // Keep track of always-report parents in bottom-to-top order
            tmpList.clear();
            while (parent != null) {
                if (participants.containsKey(parent)) {
                    topParent = parent;
                } else if (reportIfNotTop(parent)) {
                    tmpList.add(parent);
                }
                parent = parent.getParent();
            }
            if (topParent != null) {
                // Add always-report parents along the way
                parent = topParent;
                for (int i = tmpList.size() - 1; i >= 0; --i) {
                    if (!participants.containsKey(tmpList.get(i))) {
                        final ChangeInfo info = new ChangeInfo();
                        info.mParent = parent;
                        targets.put(tmpList.get(i), info);
                    }
                    parent = tmpList.get(i);
                }
                continue;
            }
            targets.put(wc, changeInfo);
            topTargets.add(wc);
        }

        // Populate children lists
        for (int i = targets.size() - 1; i >= 0; --i) {
            if (targets.valueAt(i).mParent != null) {
                targets.get(targets.valueAt(i).mParent).addChild(targets.keyAt(i));
            }
        }

        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                "  Initial targets: %s", new ArraySet<>(targets.keySet()));
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "  Top targets: %s", topTargets);

        // Combine targets by repeatedly going through the topTargets to see if they can be
        // promoted until there aren't any promotions possible.
        while (tryPromote(topTargets, targets)) {
            // Empty on purpose
        }

        // Convert all the resolved ChangeInfos into a TransactionInfo object.
        for (int i = targets.size() - 1; i >= 0; --i) {
            final WindowContainer target = targets.keyAt(i);
            final ChangeInfo info = targets.valueAt(i);
            final TransitionInfo.Change change = new TransitionInfo.Change(
                    target.mRemoteToken.toWindowContainerToken(), target.getSurfaceControl());
            if (info.mParent != null) {
                change.setParent(info.mParent.mRemoteToken.toWindowContainerToken());
            }
            change.setMode(getModeFor(target));
            out.addChange(change);
        }

        return out;
    }

    static Transition fromBinder(IBinder binder) {
        return (Transition) binder;
    }

    @VisibleForTesting
    static class ChangeInfo {
        WindowContainer mParent;
        ArraySet<WindowContainer> mChildren;
        // TODO(shell-transitions): other tracking like before state and bounds
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
