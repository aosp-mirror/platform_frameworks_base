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
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.INPUT_CONSUMER_RECENTS_ANIMATION;
import static android.view.WindowManager.KEYGUARD_VISIBILITY_TRANSIT_FLAGS;
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
import static android.window.TaskFragmentAnimationParams.DEFAULT_ANIMATION_BACKGROUND_COLOR;
import static android.window.TransitionInfo.AnimationOptions;
import static android.window.TransitionInfo.FLAGS_IS_OCCLUDED_NO_ANIMATION;
import static android.window.TransitionInfo.FLAG_CONFIG_AT_END;
import static android.window.TransitionInfo.FLAG_DISPLAY_HAS_ALERT_WINDOWS;
import static android.window.TransitionInfo.FLAG_FILLS_TASK;
import static android.window.TransitionInfo.FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY;
import static android.window.TransitionInfo.FLAG_IS_BEHIND_STARTING_WINDOW;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;
import static android.window.TransitionInfo.FLAG_IS_INPUT_METHOD;
import static android.window.TransitionInfo.FLAG_IS_OCCLUDED;
import static android.window.TransitionInfo.FLAG_IS_VOICE_INTERACTION;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;
import static android.window.TransitionInfo.FLAG_MOVED_TO_TOP;
import static android.window.TransitionInfo.FLAG_NO_ANIMATION;
import static android.window.TransitionInfo.FLAG_SHOW_WALLPAPER;
import static android.window.TransitionInfo.FLAG_TASK_LAUNCHING_BEHIND;
import static android.window.TransitionInfo.FLAG_TRANSLUCENT;
import static android.window.TransitionInfo.FLAG_WILL_IME_SHOWN;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_PENDING_INTENT;

import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_RECENTS_ANIM;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_SPLASH_SCREEN;
import static com.android.server.wm.ActivityTaskManagerInternal.APP_TRANSITION_WINDOWS_DRAWN;
import static com.android.server.wm.WindowState.BLAST_TIMEOUT_DURATION;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.content.pm.ActivityInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ScreenCapture;
import android.window.TaskFragmentAnimationParams;
import android.window.TransitionInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.policy.TransitionAnimation;
import com.android.internal.protolog.ProtoLogGroup;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.window.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Represents a logical transition. This keeps track of all the changes associated with a logical
 * WM state -> state transition.
 * @see TransitionController
 *
 * In addition to tracking individual container changes, this also tracks ordering-changes (just
 * on-top for now). However, since order is a "global" property, the mechanics of order-change
 * detection/reporting is non-trivial when transitions are collecting in parallel. See
 * {@link #collectOrderChanges} for more details.
 */
class Transition implements BLASTSyncEngine.TransactionReadyListener {
    private static final String TAG = "Transition";
    private static final String TRACE_NAME_PLAY_TRANSITION = "playing";

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

    private @Nullable ActivityRecord mPipActivity;

    /** Only use for clean-up after binder death! */
    private SurfaceControl.Transaction mStartTransaction = null;
    private SurfaceControl.Transaction mFinishTransaction = null;

    /** Used for failsafe clean-up to prevent leaks due to misbehaving player impls. */
    private SurfaceControl.Transaction mCleanupTransaction = null;

    /**
     * Contains change infos for both participants and all remote-animatable ancestors. The
     * ancestors can be the promotion candidates so their start-states need to be captured.
     * @see #getAnimatableParent
     */
    final ArrayMap<WindowContainer, ChangeInfo> mChanges = new ArrayMap<>();

    /** The collected participants in the transition. */
    final ArraySet<WindowContainer> mParticipants = new ArraySet<>();

    /** The final animation targets derived from participants after promotion. */
    ArrayList<ChangeInfo> mTargets;

    /** The displays that this transition is running on. */
    private final ArrayList<DisplayContent> mTargetDisplays = new ArrayList<>();

    /**
     * The (non alwaysOnTop) tasks which were on-top of their display before the transition. If
     * tasks are nested, all the tasks that are parents of the on-top task are also included.
     */
    private final ArrayList<Task> mOnTopTasksStart = new ArrayList<>();

    /**
     * The (non alwaysOnTop) tasks which were on-top of their display when this transition became
     * ready (via setReady, not animation-ready).
     */
    private final ArrayList<Task> mOnTopTasksAtReady = new ArrayList<>();

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

    /**
     * The tasks that may be occluded by the transient activity. Assume the task stack is
     * [Home, A(opaque), B(opaque), C(translucent)] (bottom to top), then A is the restore-below
     * task, and [B, C] are the transient-hide tasks.
     */
    private ArrayList<Task> mTransientHideTasks;

    @VisibleForTesting
    ArrayList<Runnable> mTransactionCompletedListeners = null;

    private ArrayList<Runnable> mTransitionEndedListeners = null;

    /** Custom activity-level animation options and callbacks. */
    private AnimationOptions mOverrideOptions;

    private IRemoteCallback mClientAnimationStartCallback = null;
    private IRemoteCallback mClientAnimationFinishCallback = null;

    private @TransitionState int mState = STATE_PENDING;
    private final ReadyTrackerOld mReadyTrackerOld = new ReadyTrackerOld();
    final ReadyTracker mReadyTracker = new ReadyTracker(this);

    private int mRecentsDisplayId = INVALID_DISPLAY;

    /** The delay for light bar appearance animation. */
    long mStatusBarTransitionDelay;

    /** @see #setCanPipOnFinish */
    private boolean mCanPipOnFinish = true;

    private boolean mIsSeamlessRotation = false;
    private IContainerFreezer mContainerFreezer = null;

    /**
     * {@code true} if some other operation may have caused the originally-recorded state (in
     * mChanges) to be dirty. This is usually due to finishTransition being called mid-collect;
     * and, the reason that finish can alter the "start" state of other transitions is because
     * setVisible(false) is deferred until then.
     * Instead of adding this conditional, we could re-check always; but, this situation isn't
     * common so it'd be wasted work.
     */
    boolean mPriorVisibilityMightBeDirty = false;

    final TransitionController.Logger mLogger = new TransitionController.Logger();

    /** Whether this transition was forced to play early (eg for a SLEEP signal). */
    private boolean mForcePlaying = false;

    /**
     * {@code false} if this transition runs purely in WMCore (meaning Shell is completely unaware
     * of it). Currently, this happens before the display is ready since nothing can be seen yet.
     */
    boolean mIsPlayerEnabled = true;

    /** This transition doesn't run in parallel. */
    static final int PARALLEL_TYPE_NONE = 0;

    /** Any 2 transitions of this type can run in parallel with each other. Used for testing. */
    static final int PARALLEL_TYPE_MUTUAL = 1;

    /** This is a recents transition. */
    static final int PARALLEL_TYPE_RECENTS = 2;


    @IntDef(prefix = { "PARALLEL_TYPE_" }, value = {
            PARALLEL_TYPE_NONE,
            PARALLEL_TYPE_MUTUAL,
            PARALLEL_TYPE_RECENTS
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ParallelType {}

    /**
     * What category of parallel-collect support this transition has. The value of this is used
     * by {@link TransitionController} to determine which transitions can collect in parallel. If
     * a transition can collect in parallel, it means that it will start collecting as soon as the
     * prior collecting transition is {@link #isPopulated}. This is a shortcut for supporting
     * a couple specific situations before we have full-fledged support for parallel transitions.
     */
    @ParallelType int mParallelCollectType = PARALLEL_TYPE_NONE;

    /**
     * A "Track" is a set of animations which must cooperate with each other to play smoothly. If
     * animations can play independently of each other, then they can be in different tracks. If
     * a transition must cooperate with transitions in >1 other track, then it must be marked
     * FLAG_SYNC and it will end-up flushing all animations before it starts.
     */
    int mAnimationTrack = 0;

    /**
     * List of activities whose configurations are sent to the client at the end of the transition
     * instead of immediately when the configuration changes.
     */
    ArrayList<ActivityRecord> mConfigAtEndActivities = null;

    @VisibleForTesting
    Transition(@TransitionType int type, @TransitionFlags int flags,
            TransitionController controller, BLASTSyncEngine syncEngine) {
        mType = type;
        mFlags = flags;
        mController = controller;
        mSyncEngine = syncEngine;
        mToken = new Token(this);

        mLogger.mCreateWallTimeMs = System.currentTimeMillis();
        mLogger.mCreateTimeNs = SystemClock.elapsedRealtimeNanos();
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

    void calcParallelCollectType(WindowContainerTransaction wct) {
        for (int i = 0; i < wct.getHierarchyOps().size(); ++i) {
            final WindowContainerTransaction.HierarchyOp hop = wct.getHierarchyOps().get(i);
            if (hop.getType() != HIERARCHY_OP_TYPE_PENDING_INTENT) continue;
            final Bundle b = hop.getLaunchOptions();
            if (b == null || b.isEmpty()) continue;
            final boolean transientLaunch = b.getBoolean(ActivityOptions.KEY_TRANSIENT_LAUNCH);
            if (transientLaunch) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "Starting a Recents transition which can be parallel.");
                mParallelCollectType = PARALLEL_TYPE_RECENTS;
            }
        }
    }

    /** Records an activity as transient-launch. This activity must be already collected. */
    void setTransientLaunch(@NonNull ActivityRecord activity, @Nullable Task restoreBelow) {
        if (mTransientLaunches == null) {
            mTransientLaunches = new ArrayMap<>();
            mTransientHideTasks = new ArrayList<>();
        }
        mTransientLaunches.put(activity, restoreBelow);
        setTransientLaunchToChanges(activity);

        final Task transientRootTask = activity.getRootTask();
        final WindowContainer<?> parent = restoreBelow != null ? restoreBelow.getParent()
                : (transientRootTask != null ? transientRootTask.getParent() : null);
        if (parent != null) {
            // Collect all visible tasks which can be occluded by the transient activity to
            // make sure they are in the participants so their visibilities can be updated when
            // finishing transition.
            parent.forAllTasks(t -> {
                // Skip transient-launch task
                if (t == transientRootTask) return false;
                if (t.isVisibleRequested() && !t.isAlwaysOnTop()) {
                    if (t.isRootTask()) {
                        mTransientHideTasks.add(t);
                    }
                    if (t.isLeafTask()) {
                        collect(t);
                    }
                }
                return restoreBelow != null
                        // Stop at the restoreBelow task
                        ? t == restoreBelow
                        // Or stop at the last visible task if no restore-below (new task)
                        : (t.isRootTask() && t.fillsParent());
            });
            // Add FLAG_ABOVE_TRANSIENT_LAUNCH to the tree of transient-hide tasks,
            // so ChangeInfo#hasChanged() can return true to report the transition info.
            for (int i = mChanges.size() - 1; i >= 0; --i) {
                updateTransientFlags(mChanges.valueAt(i));
            }
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Transition %d: Set %s as "
                + "transient-launch", mSyncId, activity);
    }

    /** @return whether `wc` is a descendent of a transient-hide window. */
    boolean isInTransientHide(@NonNull WindowContainer wc) {
        if (mTransientHideTasks == null) return false;
        for (int i = mTransientHideTasks.size() - 1; i >= 0; --i) {
            final Task task = mTransientHideTasks.get(i);
            if (wc == task || wc.isDescendantOf(task)) {
                return true;
            }
        }
        return false;
    }

    /** Returns {@code true} if the task should keep visible if this is a transient transition. */
    boolean isTransientVisible(@NonNull Task task) {
        if (mTransientLaunches == null) return false;
        int occludedCount = 0;
        final int numTransient = mTransientLaunches.size();
        for (int i = numTransient - 1; i >= 0; --i) {
            final Task transientRoot = mTransientLaunches.keyAt(i).getRootTask();
            if (transientRoot == null) continue;
            final WindowContainer<?> rootParent = transientRoot.getParent();
            if (rootParent == null || rootParent.getTopChild() == transientRoot) continue;
            final ActivityRecord topOpaque = mController.mAtm.mTaskSupervisor.mOpaqueActivityHelper
                    .getOpaqueActivity(rootParent, true /* ignoringKeyguard */);
            if (transientRoot.compareTo(topOpaque.getRootTask()) < 0) {
                occludedCount++;
            }
        }
        if (occludedCount == numTransient) {
            for (int i = mTransientLaunches.size() - 1; i >= 0; --i) {
                if (mTransientLaunches.keyAt(i).isDescendantOf(task)) {
                    // Keep transient activity visible until transition finished, so it won't pause
                    // with transient-hide tasks that may delay resuming the next top.
                    return true;
                }
            }
            // Let transient-hide activities pause before transition is finished.
            return false;
        }
        return isInTransientHide(task);
    }

    boolean canApplyDim(@NonNull Task task) {
        if (mTransientLaunches == null) return true;
        final Dimmer dimmer = task.getDimmer();
        if (dimmer == null) {
            return false;
        }
        if (dimmer.getHost().asTask() != null) {
            // Always allow to dim if the host only affects its task.
            return true;
        }
        // The dimmer host of a translucent task can be a display, then it is not in transient-hide.
        for (int i = mTransientLaunches.size() - 1; i >= 0; --i) {
            // The transient task is usually the task of recents/home activity.
            final Task transientTask = mTransientLaunches.keyAt(i).getTask();
            if (transientTask != null && transientTask.canAffectSystemUiFlags()) {
                // It usually means that the recents animation has moved the transient-hide task
                // an noticeable distance, then the display level dimmer should not show.
                return false;
            }
        }
        return true;
    }

    boolean hasTransientLaunch() {
        return mTransientLaunches != null && !mTransientLaunches.isEmpty();
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

    void setConfigAtEnd(@NonNull WindowContainer<?> wc) {
        wc.forAllActivities(ar -> {
            if (!ar.isVisible() || !ar.isVisibleRequested()) return;
            if (mConfigAtEndActivities == null) {
                mConfigAtEndActivities = new ArrayList<>();
            }
            if (mConfigAtEndActivities.contains(ar)) {
                return;
            }
            mConfigAtEndActivities.add(ar);
            ar.pauseConfigurationDispatch();
            snapshotStartState(ar);
            mChanges.get(ar).mFlags |= ChangeInfo.FLAG_CHANGE_CONFIG_AT_END;
        });
        snapshotStartState(wc);
        mChanges.get(wc).mFlags |= ChangeInfo.FLAG_CHANGE_CONFIG_AT_END;
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
     * Set the pip-able activity participating in this transition.
     * @param pipActivity activity about to enter pip
     */
    void setPipActivity(@Nullable ActivityRecord pipActivity) {
        mPipActivity = pipActivity;
    }

    /**
     * @return pip-able activity participating in this transition.
     */
    @Nullable ActivityRecord getPipActivity() {
        return mPipActivity;
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

    boolean isPending() {
        return mState == STATE_PENDING;
    }

    boolean isCollecting() {
        return mState == STATE_COLLECTING || mState == STATE_STARTED;
    }

    boolean isAborted() {
        return mState == STATE_ABORT;
    }

    boolean isStarted() {
        return mState == STATE_STARTED;
    }

    boolean isPlaying() {
        return mState == STATE_PLAYING;
    }

    boolean isFinished() {
        return mState == STATE_FINISHED;
    }

    /** Starts collecting phase. Once this starts, all relevant surface operations are sync. */
    void startCollecting(long timeoutMs) {
        if (mState != STATE_PENDING) {
            throw new IllegalStateException("Attempting to re-use a transition");
        }
        mState = STATE_COLLECTING;
        mSyncId = mSyncEngine.startSyncSet(this, timeoutMs,
                TAG + "-" + transitTypeToString(mType),
                mParallelCollectType != PARALLEL_TYPE_NONE);
        mSyncEngine.setSyncMethod(mSyncId, TransitionController.SYNC_METHOD);

        mLogger.mSyncId = mSyncId;
        mLogger.mCollectTimeNs = SystemClock.elapsedRealtimeNanos();
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
            Slog.w(TAG, "Transition already started id=" + mSyncId + " state=" + mState);
            // The transition may be aborted (STATE_ABORT) or timed out (STATE_PLAYING by
            // SyncGroup#finishNow), so do not revert the state to STATE_STARTED.
            return;
        }
        mState = STATE_STARTED;
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Starting Transition %d",
                mSyncId);
        applyReady();

        mLogger.mStartTimeNs = SystemClock.elapsedRealtimeNanos();

        mController.updateAnimatingState();
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
        // Snapshot before checking if this is a participant in case it has been re-parented.
        snapshotStartState(getAnimatableParent(wc));
        if (mParticipants.contains(wc)) return;
        // Transient-hide may be hidden later, so no need to request redraw.
        if (!isInTransientHide(wc)) {
            mSyncEngine.addToSyncSet(mSyncId, wc);
        }
        if (wc.asWindowToken() != null && wc.asWindowToken().mRoundedCornerOverlay) {
            // Only need to sync the transaction (SyncSet) without ChangeInfo because cutout and
            // rounded corner overlay never need animations. Especially their surfaces may be put
            // in root (null, see WindowToken#makeSurface()) that cannot reparent.
            return;
        }
        ChangeInfo info = mChanges.get(wc);
        if (info == null) {
            info = new ChangeInfo(wc);
            updateTransientFlags(info);
            mChanges.put(wc, info);
        }
        mParticipants.add(wc);
        recordDisplay(wc.getDisplayContent());
        if (info.mShowWallpaper) {
            // Collect the wallpaper token (for isWallpaper(wc)) so it is part of the sync set.
            wc.mDisplayContent.mWallpaperController.collectTopWallpapers(this);
        }
    }

    /** "snapshot" `wc` and all its parents (as potential promotion targets). */
    private void snapshotStartState(@NonNull WindowContainer<?> wc) {
        for (WindowContainer<?> curr = wc;
                curr != null && !mChanges.containsKey(curr);
                curr = getAnimatableParent(curr)) {
            final ChangeInfo info = new ChangeInfo(curr);
            updateTransientFlags(info);
            mChanges.put(curr, info);
            if (isReadyGroup(curr)) {
                mReadyTrackerOld.addGroup(curr);
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, " Creating Ready-group for"
                        + " Transition %d with root=%s", mSyncId, curr);
            }
        }
    }

    private void updateTransientFlags(@NonNull ChangeInfo info) {
        final WindowContainer<?> wc = info.mContainer;
        // Only look at tasks, taskfragments, or activities
        if (wc.asTaskFragment() == null && wc.asActivityRecord() == null) return;
        if (!isInTransientHide(wc)) return;
        info.mFlags |= ChangeInfo.FLAG_ABOVE_TRANSIENT_LAUNCH;
    }

    private void recordDisplay(DisplayContent dc) {
        if (dc == null || mTargetDisplays.contains(dc)) return;
        mTargetDisplays.add(dc);
        addOnTopTasks(dc, mOnTopTasksStart);
        // Handle the case {transition.start(); applyTransaction(wct);} that the animating state
        // is set before collecting participants.
        if (mController.isAnimating()) {
            dc.enableHighPerfTransition(true);
        }
    }

    /**
     * Records information about the initial task order. This does NOT collect anything. Call this
     * before any ordering changes *could* occur, but it is not known yet if it will occur.
     */
    void recordTaskOrder(WindowContainer from) {
        recordDisplay(from.getDisplayContent());
    }

    /** Adds the top non-alwaysOnTop tasks within `task` to `out`. */
    private static void addOnTopTasks(Task task, ArrayList<Task> out) {
        for (int i = task.getChildCount() - 1; i >= 0; --i) {
            final Task child = task.getChildAt(i).asTask();
            if (child == null) return;
            if (child.getWindowConfiguration().isAlwaysOnTop()) continue;
            out.add(child);
            addOnTopTasks(child, out);
            break;
        }
    }

    /** Get the top non-alwaysOnTop leaf task on the display `dc`. */
    private static void addOnTopTasks(DisplayContent dc, ArrayList<Task> out) {
        final Task topNotAlwaysOnTop = dc.getRootTask(
                t -> !t.getWindowConfiguration().isAlwaysOnTop());
        if (topNotAlwaysOnTop == null) return;
        out.add(topNotAlwaysOnTop);
        addOnTopTasks(topNotAlwaysOnTop, out);
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
        if (wc.mDisplayContent == null || !isInTransition(wc)) return;
        if (!wc.mDisplayContent.getDisplayPolicy().isScreenOnFully()
                || wc.mDisplayContent.getDisplayInfo().state == Display.STATE_OFF) {
            mFlags |= WindowManager.TRANSIT_FLAG_INVISIBLE;
            return;
        }
        // Activity doesn't need to capture snapshot if the starting window has associated to task.
        if (wc.asActivityRecord() != null) {
            final ActivityRecord activityRecord = wc.asActivityRecord();
            if (activityRecord.mStartingData != null
                    && activityRecord.mStartingData.mAssociatedTask != null) {
                return;
            }
        }

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
     * Collects a window container which will be removed or invisible.
     */
    void collectClose(@NonNull WindowContainer<?> wc) {
        if (wc.isVisibleRequested()) {
            collectExistenceChange(wc);
        } else {
            // Removing a non-visible window doesn't require a transition, but if there is one
            // collecting, this should be a member just in case.
            collect(wc);
        }
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
    void setOverrideAnimation(@Nullable AnimationOptions options,
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
        mReadyTrackerOld.setReadyFrom(wc, ready);
        applyReady();
    }

    private void applyReady() {
        if (mState < STATE_STARTED) return;
        final boolean ready;
        if (mController.useFullReadyTracking()) {
            ready = mReadyTracker.isReady();
        } else {
            ready = mReadyTrackerOld.allReady();
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                "Set transition ready=%b %d", ready, mSyncId);
        boolean changed = mSyncEngine.setReady(mSyncId, ready);
        if (changed && ready) {
            mLogger.mReadyTimeNs = SystemClock.elapsedRealtimeNanos();
            mOnTopTasksAtReady.clear();
            for (int i = 0; i < mTargetDisplays.size(); ++i) {
                addOnTopTasks(mTargetDisplays.get(i), mOnTopTasksAtReady);
            }
            mController.onTransitionPopulated(this);
        }
    }

    /**
     * Sets all possible ready groups to ready.
     * @see ReadyTrackerOld#setAllReady
     */
    void setAllReady() {
        if (!isCollecting() || mSyncId < 0) return;
        mReadyTrackerOld.setAllReady();
        applyReady();
    }

    @VisibleForTesting
    boolean allReady() {
        return mReadyTrackerOld.allReady();
    }

    /** This transition has all of its expected participants. */
    boolean isPopulated() {
        return mState >= STATE_STARTED && mReadyTrackerOld.allReady();
    }

    /**
     * Populates `t` with instructions to reset surface transform of `change` so it matches
     * the WM hierarchy. This "undoes" lingering state left by the animation.
     */
    private void resetSurfaceTransform(SurfaceControl.Transaction t, WindowContainer target,
            SurfaceControl targetLeash) {
        final Point tmpPos = new Point();
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
        t.setMatrix(targetLeash, 1, 0, 0, 1);
        // The bounds sent to the transition is always a real bounds. This means we lose
        // information about "null" bounds (inheriting from parent). Core will fix-up
        // non-organized window surface bounds; however, since Core can't touch organized
        // surfaces, add the "inherit from parent" restoration here.
        if (target.isOrganized() && target.matchParentBounds()) {
            t.setWindowCrop(targetLeash, -1, -1);
        }
    }

    /**
     * Build a transaction that "resets" all the re-parenting and layer changes. This is
     * intended to be applied at the end of the transition but before the finish callback. This
     * needs to be passed/applied in shell because until finish is called, shell owns the surfaces.
     * Additionally, this gives shell the ability to better deal with merged transitions.
     */
    private void buildFinishTransaction(SurfaceControl.Transaction t, TransitionInfo info) {
        // usually only size 1
        final ArraySet<DisplayContent> displays = new ArraySet<>();
        for (int i = mTargets.size() - 1; i >= 0; --i) {
            final WindowContainer<?> target = mTargets.get(i).mContainer;
            if (target.getParent() == null) continue;
            final SurfaceControl targetLeash = getLeashSurface(target, null /* t */);
            final SurfaceControl origParent = getOrigParentSurface(target);
            // Ensure surfaceControls are re-parented back into the hierarchy.
            t.reparent(targetLeash, origParent);
            t.setLayer(targetLeash, target.getLastLayer());
            t.setCornerRadius(targetLeash, 0);
            t.setShadowRadius(targetLeash, 0);
            t.setAlpha(targetLeash, 1);
            displays.add(target.getDisplayContent());
            // For config-at-end, the end-transform will be reset after the config is actually
            // applied in the client (since the transform depends on config). The other properties
            // remain here because shell might want to persistently override them.
            if ((mTargets.get(i).mFlags & ChangeInfo.FLAG_CHANGE_CONFIG_AT_END) == 0) {
                resetSurfaceTransform(t, target, targetLeash);
            }
        }
        // Remove screenshot layers if necessary
        if (mContainerFreezer != null) {
            mContainerFreezer.cleanUp(t);
        }
        // Need to update layers on involved displays since they were all paused while
        // the animation played. This puts the layers back into the correct order.
        for (int i = displays.size() - 1; i >= 0; --i) {
            if (displays.valueAt(i) == null) continue;
            assignLayers(displays.valueAt(i), t);
        }

        for (int i = 0; i < info.getRootCount(); ++i) {
            t.reparent(info.getRoot(i).getLeash(), null);
        }
    }

    /** Assigns the layers for the start or end state of transition. */
    static void assignLayers(WindowContainer<?> wc, SurfaceControl.Transaction t) {
        wc.mTransitionController.mBuildingFinishLayers = true;
        try {
            wc.assignChildLayers(t);
        } finally {
            wc.mTransitionController.mBuildingFinishLayers = false;
        }
    }

    /**
     * Build a transaction that cleans-up transition-only surfaces (transition root and snapshots).
     * This will ALWAYS be applied on transition finish just in-case
     */
    private static void buildCleanupTransaction(SurfaceControl.Transaction t, TransitionInfo info) {
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change c = info.getChanges().get(i);
            if (c.getSnapshot() != null) {
                t.reparent(c.getSnapshot(), null);
            }
            // The fixed transform hint was set in DisplayContent#applyRotation(). Make sure to
            // clear the hint in case the start transaction is not applied.
            if (c.hasFlags(FLAG_IS_DISPLAY) && c.getStartRotation() != c.getEndRotation()
                    && c.getContainer() != null) {
                t.unsetFixedTransformHint(WindowContainer.fromBinder(c.getContainer().asBinder())
                        .asDisplayContent().mSurfaceControl);
            }
        }
        for (int i = info.getRootCount() - 1; i >= 0; --i) {
            final SurfaceControl leash = info.getRoot(i).getLeash();
            if (leash == null) continue;
            t.reparent(leash, null);
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
        if (!mCanPipOnFinish || !ar.isVisible() || ar.getTask() == null || !ar.isState(RESUMED)) {
            return false;
        }

        final ActivityRecord resuming = getVisibleTransientLaunch(ar.getTaskDisplayArea());
        if (ar.pictureInPictureArgs != null && ar.pictureInPictureArgs.isAutoEnterEnabled()) {
            if (!ar.getTask().isVisibleRequested() || didCommitTransientLaunch()) {
                // force enable pip-on-task-switch now that we've committed to actually launching
                // to the transient activity.
                ar.supportsEnterPipOnTaskSwitch = true;
            }
            // Make sure this activity can enter pip under the current circumstances.
            // `enterPictureInPicture` internally checks, but with beforeStopping=false which
            // is specifically for non-auto-enter.
            if (!ar.checkEnterPictureInPictureState("enterPictureInPictureMode",
                    true /* beforeStopping */)) {
                return false;
            }
            final int prevMode = ar.getTask().getWindowingMode();
            final boolean inPip = mController.mAtm.enterPictureInPictureMode(ar,
                    ar.pictureInPictureArgs, false /* fromClient */, true /* isAutoEnter */);
            final int currentMode = ar.getTask().getWindowingMode();
            if (prevMode == WINDOWING_MODE_FULLSCREEN && currentMode == WINDOWING_MODE_PINNED
                    && mTransientLaunches != null
                    && ar.mDisplayContent.hasTopFixedRotationLaunchingApp()) {
                // There will be a display configuration change after finishing this transition.
                // Skip dispatching the change for PiP task to avoid its activity drawing for the
                // intermediate state which will cause flickering. The final PiP bounds in new
                // rotation will be applied by PipTransition.
                ar.mDisplayContent.mPinnedTaskController.setEnterPipTransaction(null);
            }
            return inPip;
        }

        // Legacy pip-entry (not via isAutoEnterEnabled).
        if ((!ar.getTask().isVisibleRequested() || didCommitTransientLaunch())
                && ar.supportsPictureInPicture()) {
            // force enable pip-on-task-switch now that we've committed to actually launching to the
            // transient activity, and then recalculate whether we can attempt pip.
            ar.supportsEnterPipOnTaskSwitch = true;
        }

        try {
            // If not going auto-pip, the activity should be paused with user-leaving.
            mController.mAtm.mTaskSupervisor.mUserLeaving = true;
            ar.getTaskFragment().startPausing(false /* uiSleeping */, resuming, "finishTransition");
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
        if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER) && mIsPlayerEnabled) {
            asyncTraceEnd(System.identityHashCode(this));
        }
        mLogger.mFinishTimeNs = SystemClock.elapsedRealtimeNanos();
        mController.mLoggerHandler.post(mLogger::logOnFinish);
        mController.mTransitionTracer.logFinishedTransition(this);
        // Close the transactions now. They were originally copied to Shell in case we needed to
        // apply them due to a remote failure. Since we don't need to apply them anymore, free them
        // immediately.
        if (mStartTransaction != null) mStartTransaction.close();
        if (mFinishTransaction != null) mFinishTransaction.close();
        mStartTransaction = mFinishTransaction = null;
        if (mCleanupTransaction != null) {
            mCleanupTransaction.apply();
            mCleanupTransaction = null;
        }
        if (mState < STATE_PLAYING) {
            throw new IllegalStateException("Can't finish a non-playing transition " + mSyncId);
        }
        mController.mFinishingTransition = this;

        if (mTransientHideTasks != null && !mTransientHideTasks.isEmpty()) {
            // The transient hide tasks could be occluded now, e.g. returning to home. So trigger
            // the update to make the activities in the tasks invisible-requested, then the next
            // step can continue to commit the visibility.
            mController.mAtm.mRootWindowContainer.ensureActivitiesVisible();
            // Record all the now-hiding activities so that they are committed. Just use
            // mParticipants because we can avoid a new list this way.
            for (int i = 0; i < mTransientHideTasks.size(); ++i) {
                final Task rootTask = mTransientHideTasks.get(i);
                rootTask.forAllActivities(r -> {
                    // Only check leaf-tasks that were collected
                    if (!mParticipants.contains(r.getTask())) return;
                    if (rootTask.isVisibleRequested()) {
                        // This transient-hide didn't hide, so don't commit anything (otherwise we
                        // could prematurely commit invisible on unrelated activities). To be safe,
                        // though, notify the controller to prevent degenerate cases.
                        if (!r.isVisibleRequested()) {
                            mController.mValidateCommitVis.add(r);
                        } else {
                            // Make sure onAppTransitionFinished can be notified.
                            mParticipants.add(r);
                        }
                        return;
                    }
                    // This did hide: commit immediately so that other transitions know about it.
                    mParticipants.add(r);
                });
            }
        }

        boolean hasParticipatedDisplay = false;
        boolean hasVisibleTransientLaunch = false;
        boolean enterAutoPip = false;
        boolean committedSomeInvisible = false;
        // Commit all going-invisible containers
        for (int i = 0; i < mParticipants.size(); ++i) {
            final WindowContainer<?> participant = mParticipants.valueAt(i);
            final ActivityRecord ar = participant.asActivityRecord();
            if (ar != null) {
                final Task task = ar.getTask();
                if (task == null) continue;
                boolean visibleAtTransitionEnd = mVisibleAtTransitionEndTokens.contains(ar);
                // visibleAtTransitionEnd is used to guard against pre-maturely committing
                // invisible on a window which is actually hidden by a later transition and not this
                // one. However, for a transient launch, we can't use this mechanism because the
                // visibility is determined at finish. Instead, use a different heuristic: don't
                // commit invisible if the window is already in a later transition. That later
                // transition will then handle the commit.
                if (isTransientLaunch(ar) && !ar.isVisibleRequested()
                        && mController.inCollectingTransition(ar)) {
                    visibleAtTransitionEnd = true;
                }
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
                        final SnapshotController snapController = mController.mSnapshotController;
                        if (mTransientLaunches != null && !task.isVisibleRequested()
                                && !task.isActivityTypeHome()) {
                            final long startTimeNs = mLogger.mSendTimeNs;
                            final long lastSnapshotTimeNs = snapController.mTaskSnapshotController
                                    .getSnapshotCaptureTime(task.mTaskId);
                            // If transition is transient, then snapshots are taken at end of
                            // transition only if a snapshot was not already captured by request
                            // during the transition
                            if (lastSnapshotTimeNs < startTimeNs) {
                                snapController.mTaskSnapshotController.recordSnapshot(task);
                            } else {
                                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                                        "  Skipping post-transition snapshot for task %d",
                                        task.mTaskId);
                            }
                        }
                        ar.commitVisibility(false /* visible */, false /* performLayout */,
                                true /* fromTransition */);
                        committedSomeInvisible = true;
                    } else {
                        enterAutoPip = true;
                    }
                }
                final ChangeInfo changeInfo = mChanges.get(ar);
                // Due to transient-hide, there may be some activities here which weren't in the
                // transition.
                if (changeInfo != null && changeInfo.mVisible != visibleAtTransitionEnd) {
                    // Legacy dispatch relies on this (for now).
                    ar.mEnteringAnimation = visibleAtTransitionEnd;
                } else if (mTransientLaunches != null && mTransientLaunches.containsKey(ar)
                        && ar.isVisible()) {
                    // Transient launch was committed, so report enteringAnimation
                    ar.mEnteringAnimation = true;
                    hasVisibleTransientLaunch = true;

                    // Since transient launches don't automatically take focus, make sure we
                    // synchronize focus since we committed to the launch.
                    if (!task.isFocused() && ar.isTopRunningActivity()) {
                        mController.mAtm.setLastResumedActivityUncheckLocked(ar,
                                "transitionFinished");
                    }
                }
                continue;
            }
            if (participant.asDisplayContent() != null) {
                hasParticipatedDisplay = true;
                continue;
            }
            final Task tr = participant.asTask();
            if (tr != null && tr.isVisibleRequested() && tr.inPinnedWindowingMode()) {
                final ActivityRecord top = tr.getTopNonFinishingActivity();
                if (top != null && !top.inPinnedWindowingMode()) {
                    mController.mStateValidators.add(() -> {
                        if (!tr.isAttached() || !tr.isVisibleRequested()
                                || !tr.inPinnedWindowingMode()) return;
                        final ActivityRecord currTop = tr.getTopNonFinishingActivity();
                        if (currTop.inPinnedWindowingMode()) return;
                        Slog.e(TAG, "Enter-PIP was started but not completed, this is a Shell/SysUI"
                                + " bug. This state breaks gesture-nav, so attempting clean-up.");
                        // We don't know the destination bounds, so we can't actually finish the
                        // operation. So, to prevent the half-pipped task from covering everything,
                        // abort the action (which moves the task to back).
                        tr.abortPipEnter(currTop);
                    });
                }
            }
        }
        // Commit wallpaper visibility after activity, because usually the wallpaper target token is
        // an activity, and wallpaper's visibility depends on activity's visibility.
        for (int i = mParticipants.size() - 1; i >= 0; --i) {
            final WallpaperWindowToken wt = mParticipants.valueAt(i).asWallpaperToken();
            if (wt == null) continue;
            final WindowState target = wt.mDisplayContent.mWallpaperController.getWallpaperTarget();
            final boolean isTargetInvisible = target == null || !target.mToken.isVisible();
            final boolean isWallpaperVisibleAtEnd =
                    wt.isVisibleRequested() || mVisibleAtTransitionEndTokens.contains(wt);
            if (isTargetInvisible || !isWallpaperVisibleAtEnd) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "  Commit wallpaper becoming invisible: %s", wt);
                wt.commitVisibility(false /* visible */);
            }
            if (isTargetInvisible) {
                // Our original target went invisible, so we should look for a new target.
                wt.mDisplayContent.pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            }
        }
        if (committedSomeInvisible) {
            mController.onCommittedInvisibles();
        }

        if (hasVisibleTransientLaunch) {
            // Notify the change about the transient-below task if entering auto-pip.
            if (enterAutoPip) {
                mController.mAtm.getTaskChangeNotificationController().notifyTaskStackChanged();
            }
            // Prevent spurious background app switches.
            mController.mAtm.stopAppSwitches();
            // The end of transient launch may not reorder task, so make sure to compute the latest
            // task rank according to the current visibility.
            mController.mAtm.mRootWindowContainer.rankTaskLayers();
        }

        commitConfigAtEndActivities();

        // dispatch legacy callback in a different loop. This is because multiple legacy handlers
        // (fixed-rotation/displaycontent) make global changes, so we want to ensure that we've
        // processed all the participants first (in particular, we want to trigger pip-enter first)
        for (int i = 0; i < mParticipants.size(); ++i) {
            final ActivityRecord ar = mParticipants.valueAt(i).asActivityRecord();
            if (ar == null) continue;

            // If the activity was just inserted to an invisible task, it will keep INITIALIZING
            // state. Then no need to notify the callback to avoid clearing some states
            // unexpectedly, e.g. launch-task-behind.
            if (ar.isVisibleRequested() || !ar.isState(ActivityRecord.State.INITIALIZING)) {
                mController.dispatchLegacyAppTransitionFinished(ar);
            }

            // Reset the ActivityRecord#mCurrentLaunchCanTurnScreenOn state if it is not the top
            // running activity. Doing so in case the state is not yet consumed during rapid
            // activity launch.
            if (ar.currentLaunchCanTurnScreenOn() && ar.getDisplayContent() != null
                    && ar.getDisplayContent().topRunningActivity() != ar) {
                ar.setCurrentLaunchCanTurnScreenOn(false);
            }
        }

        // Update the input-sink (touch-blocking) state now that the animation is finished.
        SurfaceControl.Transaction inputSinkTransaction = null;
        for (int i = 0; i < mParticipants.size(); ++i) {
            final ActivityRecord ar = mParticipants.valueAt(i).asActivityRecord();
            if (ar == null || !ar.isVisible() || ar.getParent() == null) continue;
            if (inputSinkTransaction == null) {
                inputSinkTransaction = ar.mWmService.mTransactionFactory.get();
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
            dc.getInputMonitor().updateInputWindowsLw(false /* force */);
        }
        if (mTransientLaunches != null) {
            for (int i = mTransientLaunches.size() - 1; i >= 0; --i) {
                // Reset the ability of controlling SystemUi which might be changed by
                // setTransientLaunch or setRecentsAppBehindSystemBars.
                final Task task = mTransientLaunches.keyAt(i).getTask();
                if (task != null) {
                    task.setCanAffectSystemUiFlags(true);
                }
            }
        }

        for (int i = 0; i < mTargetDisplays.size(); ++i) {
            final DisplayContent dc = mTargetDisplays.get(i);
            final AsyncRotationController asyncRotationController = dc.getAsyncRotationController();
            if (asyncRotationController != null && containsChangeFor(dc, mTargets)) {
                asyncRotationController.onTransitionFinished();
            }
            dc.onTransitionFinished();
            if (hasParticipatedDisplay && dc.mDisplayRotationCompatPolicy != null) {
                final ChangeInfo changeInfo = mChanges.get(dc);
                if (changeInfo != null
                        && changeInfo.mRotation != dc.getWindowConfiguration().getRotation()) {
                    dc.mDisplayRotationCompatPolicy.onScreenRotationAnimationFinished();
                }
            }
            if (mTransientLaunches != null) {
                TaskDisplayArea transientTDA = null;
                for (int t = 0; t < mTransientLaunches.size(); ++t) {
                    if (mTransientLaunches.keyAt(t).getDisplayContent() == dc) {
                        if (hasVisibleTransientLaunch) {
                            updateImeForVisibleTransientLaunch(dc);
                        }
                        transientTDA = mTransientLaunches.keyAt(i).getTaskDisplayArea();
                        break;
                    }
                }
                if (!hasVisibleTransientLaunch && mRecentsDisplayId == dc.mDisplayId) {
                    // Restore IME icon only when moving the original app task to front from
                    // recents, in case IME icon may missing if the moving task has already been
                    // the current focused task.
                    InputMethodManagerInternal.get().updateImeWindowStatus(
                            false /* disableImeIcon */, dc.getDisplayId());
                }
                // An uncommitted transient launch can leave incomplete lifecycles if visibilities
                // didn't change (eg. re-ordering with translucent tasks will leave launcher
                // in RESUMED state), so force an update here.
                if (!hasVisibleTransientLaunch && transientTDA != null) {
                    transientTDA.pauseBackTasks(null /* resuming */);
                }
            }
            dc.removeImeSurfaceImmediately();
            dc.handleCompleteDeferredRemoval();
        }
        validateKeyguardOcclusion();

        mState = STATE_FINISHED;
        // Rotation change may be deferred while there is a display change transition, so check
        // again in case there is a new pending change.
        if (hasParticipatedDisplay && !mController.useShellTransitionsRotation()) {
            mController.mAtm.mWindowManager.updateRotation(false /* alwaysSendConfiguration */,
                    false /* forceRelayout */);
        }
        cleanUpInternal();

        // Handle back animation if it's already started.
        mController.mAtm.mBackNavigationController.onTransitionFinish(mTargets, this);
        mController.mFinishingTransition = null;
        mController.mSnapshotController.onTransitionFinish(mType, mTargets);
        // Resume snapshot persist thread after snapshot controller analysis this transition.
        mController.updateAnimatingState();

        invokeTransitionEndedListeners();
    }

    private void invokeTransitionEndedListeners() {
        if (mTransitionEndedListeners == null) {
            return;
        }
        for (int i = 0; i < mTransitionEndedListeners.size(); i++) {
            mTransitionEndedListeners.get(i).run();
        }
        mTransitionEndedListeners = null;
    }

    private void commitConfigAtEndActivities() {
        if (mConfigAtEndActivities == null || mConfigAtEndActivities.isEmpty()) {
            return;
        }
        final SurfaceControl.Transaction t =
                mController.mAtm.mWindowManager.mTransactionFactory.get();
        for (int i = 0; i < mTargets.size(); ++i) {
            final WindowContainer target = mTargets.get(i).mContainer;
            if (target.getParent() == null || (mTargets.get(i).mFlags
                    & ChangeInfo.FLAG_CHANGE_CONFIG_AT_END) == 0) {
                continue;
            }
            final SurfaceControl targetLeash = getLeashSurface(target, null /* t */);
            // Reset surface state here (since it was skipped in buildFinishTransaction). Since
            // we are resuming config to the "current" state, we have to calculate the matching
            // surface state now (rather than snapshotting it at animation start).
            resetSurfaceTransform(t, target, targetLeash);
        }

        // Now we resume the configuration dispatch, wait until the now resumed configs have been
        // drawn, and then apply everything together.
        final BLASTSyncEngine.SyncGroup sg = mSyncEngine.prepareSyncSet(
                new BLASTSyncEngine.TransactionReadyListener() {
                    @Override
                    public void onTransactionReady(int mSyncId,
                            SurfaceControl.Transaction transaction) {
                        t.merge(transaction);
                        t.apply();
                    }

                    @Override
                    public void onTransactionCommitTimeout() {
                        t.apply();
                    }
                }, "ConfigAtTransitEnd");
        final int syncId = sg.mSyncId;
        mSyncEngine.startSyncSet(sg, BLAST_TIMEOUT_DURATION, true /* parallel */);
        mSyncEngine.setSyncMethod(syncId, BLASTSyncEngine.METHOD_BLAST);
        for (int i = 0; i < mConfigAtEndActivities.size(); ++i) {
            final ActivityRecord ar = mConfigAtEndActivities.get(i);
            mSyncEngine.addToSyncSet(syncId, ar);
            ar.resumeConfigurationDispatch();
        }
        mSyncEngine.setReady(syncId);
    }

    @Nullable
    private ActivityRecord getVisibleTransientLaunch(TaskDisplayArea taskDisplayArea) {
        if (mTransientLaunches == null) return null;
        for (int i = mTransientLaunches.size() - 1; i >= 0; --i) {
            final ActivityRecord candidateActivity = mTransientLaunches.keyAt(i);
            if (candidateActivity.getTaskDisplayArea() != taskDisplayArea) {
                continue;
            }
            if (!candidateActivity.isVisibleRequested()) {
                continue;
            }
            return candidateActivity;
        }
        return null;
    }

    /**
     * Transient-launch activities cannot be IME target (see {@link WindowState#canBeImeTarget}),
     * so re-compute in case the IME target is changed after transition.
     */
    private void updateImeForVisibleTransientLaunch(@NonNull DisplayContent dc) {
        final WindowState imeTarget = dc.computeImeTarget(true /* updateImeTarget */);
        final WindowState imeWindow = dc.mInputMethodWindow;
        if (imeWindow == null || imeTarget == null
                || !mController.hasCollectingRotationChange(dc, dc.getRotation())) {
            return;
        }
        // Drop the insets leash if it is still controlled by previous (invisible) app. This avoids
        // showing IME with old rotation on an app with new rotation if IME parent is updated
        // but insets leash hasn't been refreshed, i.e. DisplayContent#updateImeParent is called
        // but InsetsStateController#notifyControlTargetChanged still waits for IME to redraw.
        final InsetsSourceProvider sourceProvider = imeWindow.getControllableInsetProvider();
        if (sourceProvider == null || sourceProvider.mControl == null
                || !sourceProvider.isClientVisible()
                || imeTarget == sourceProvider.getControlTarget()) {
            return;
        }
        final SurfaceControl imeInsetsLeash = sourceProvider.mControl.getLeash();
        final InsetsControlTarget controlTarget = sourceProvider.getControlTarget();
        if (imeInsetsLeash != null && controlTarget != null && controlTarget.getWindow() != null
                && !controlTarget.getWindow().mToken.isVisible()) {
            dc.getSyncTransaction().reparent(imeInsetsLeash, null);
        }
    }

    void abort() {
        // This calls back into itself via controller.abort, so just early return here.
        if (mState == STATE_ABORT) return;
        if (mState == STATE_PENDING) {
            // hasn't started collecting, so can jump directly to aborted state.
            mState = STATE_ABORT;
            return;
        }
        if (mState != STATE_COLLECTING && mState != STATE_STARTED) {
            throw new IllegalStateException("Too late to abort. state=" + mState);
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Aborting Transition: %d", mSyncId);
        mState = STATE_ABORT;
        mLogger.mAbortTimeNs = SystemClock.elapsedRealtimeNanos();
        mController.mTransitionTracer.logAbortedTransition(this);
        // Syncengine abort will call through to onTransactionReady()
        mSyncEngine.abort(mSyncId);
        mController.dispatchLegacyAppTransitionCancelled();
        invokeTransitionEndedListeners();
    }

    /** Immediately moves this to playing even if it isn't started yet. */
    void playNow() {
        if (!(mState == STATE_COLLECTING || mState == STATE_STARTED)) {
            return;
        }
        ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Force Playing Transition: %d",
                mSyncId);
        mForcePlaying = true;
        // backwards since conditions are removed.
        for (int i = mReadyTracker.mConditions.size() - 1; i >= 0; --i) {
            mReadyTracker.mConditions.get(i).meetAlternate("play-now");
        }
        final ReadyCondition forcePlay = new ReadyCondition("force-play-now");
        mReadyTracker.add(forcePlay);
        forcePlay.meet();
        setAllReady();
        if (mState == STATE_COLLECTING) {
            start();
        }
        // Don't wait for actual surface-placement. We don't want anything else collected in this
        // transition.
        mSyncEngine.onSurfacePlacement();
    }

    boolean isForcePlaying() {
        return mForcePlaying;
    }

    /** Adjusts the priority of the process which will run the transition animation. */
    void setRemoteAnimationApp(IApplicationThread app) {
        final WindowProcessController wpc = mController.mAtm.getProcessController(app);
        if (wpc != null) {
            // This is an early prediction. If the process doesn't ack the animation in 200 ms,
            // the priority will be restored.
            mController.mRemotePlayer.update(wpc, true /* running */, true /* predict */);
        }
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

        if (mController.useFullReadyTracking()) {
            for (int i = 0; i < mReadyTracker.mMet.size(); ++i) {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "#%d: Met condition: %s",
                        mSyncId, mReadyTracker.mMet.get(i));
            }
        }

        // Commit the visibility of visible activities before calculateTransitionInfo(), so the
        // TaskInfo can be visible. Also it needs to be done before moveToPlaying(), otherwise
        // ActivityRecord#canShowWindows() may reject to show its window. The visibility also
        // needs to be updated for STATE_ABORT.
        commitVisibleActivities(transaction);
        commitVisibleWallpapers();

        if (mTransactionCompletedListeners != null) {
            for (int i = 0; i < mTransactionCompletedListeners.size(); i++) {
                final Runnable listener = mTransactionCompletedListeners.get(i);
                transaction.addTransactionCompletedListener(Runnable::run,
                        (stats) -> listener.run());
            }
        }

        // Fall-back to the default display if there isn't one participating.
        final DisplayContent primaryDisplay = !mTargetDisplays.isEmpty() ? mTargetDisplays.get(0)
                : mController.mAtm.mRootWindowContainer.getDefaultDisplay();

        if (mState == STATE_ABORT) {
            mController.onAbort(this);
            if (mConfigAtEndActivities != null) {
                for (int i = 0; i < mConfigAtEndActivities.size(); ++i) {
                    mConfigAtEndActivities.get(i).resumeConfigurationDispatch();
                }
                mConfigAtEndActivities = null;
            }
            primaryDisplay.getPendingTransaction().merge(transaction);
            mSyncId = -1;
            mOverrideOptions = null;
            cleanUpInternal();
            return;
        }

        if (mState != STATE_STARTED) {
            Slog.e(TAG, "Playing a Transition which hasn't started! #" + mSyncId + " This will "
                    + "likely cause an exception in Shell");
        }

        mState = STATE_PLAYING;
        mStartTransaction = transaction;
        mFinishTransaction = mController.mAtm.mWindowManager.mTransactionFactory.get();

        // Flags must be assigned before calculateTransitionInfo. Otherwise it won't take effect.
        if (primaryDisplay.isKeyguardLocked()) {
            mFlags |= TRANSIT_FLAG_KEYGUARD_LOCKED;
        }

        // This is the only (or last) transition that is collecting, so we need to report any
        // leftover order changes.
        collectOrderChanges(mController.mWaitingTransitions.isEmpty());

        if (mPriorVisibilityMightBeDirty) {
            updatePriorVisibility();
        }
        // Resolve the animating targets from the participants.
        mTargets = calculateTargets(mParticipants, mChanges);

        // Check whether the participants were animated from back navigation.
        mController.mAtm.mBackNavigationController.onTransactionReady(this, mTargets,
                transaction);
        final TransitionInfo info = calculateTransitionInfo(mType, mFlags, mTargets, transaction);
        info.setDebugId(mSyncId);
        mController.assignTrack(this, info);

        mController.moveToPlaying(this);

        // Repopulate the displays based on the resolved targets.
        mTargetDisplays.clear();
        for (int i = 0; i < info.getRootCount(); ++i) {
            final DisplayContent dc = mController.mAtm.mRootWindowContainer.getDisplayContent(
                    info.getRoot(i).getDisplayId());
            mTargetDisplays.add(dc);
        }

        for (int i = 0; i < mTargets.size(); ++i) {
            final DisplayArea da = mTargets.get(i).mContainer.asDisplayArea();
            if (da == null) continue;
            if (da.isVisibleRequested()) {
                mController.mValidateDisplayVis.remove(da);
            } else {
                // In case something accidentally hides a displayarea and nothing shows it again.
                mController.mValidateDisplayVis.add(da);
            }
        }
        overrideAnimationOptionsToInfoIfNecessary(info);

        // TODO(b/188669821): Move to animation impl in shell.
        for (int i = 0; i < mTargetDisplays.size(); ++i) {
            handleLegacyRecentsStartBehavior(mTargetDisplays.get(i), info);
            if (mRecentsDisplayId != INVALID_DISPLAY) break;
        }

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
        // committing visibility. Skip transient launches since those are only temporarily visible.
        if (mTransientLaunches == null) {
            for (int i = mParticipants.size() - 1; i >= 0; --i) {
                final WindowContainer wc = mParticipants.valueAt(i);
                if (wc.asWindowToken() == null || !wc.isVisibleRequested()) continue;
                mVisibleAtTransitionEndTokens.add(wc.asWindowToken());
            }
        }

        // This is non-null only if display has changes. It handles the visible windows that don't
        // need to be participated in the transition.
        for (int i = 0; i < mTargetDisplays.size(); ++i) {
            final DisplayContent dc = mTargetDisplays.get(i);
            final AsyncRotationController controller = dc.getAsyncRotationController();
            if (controller != null && containsChangeFor(dc, mTargets)) {
                controller.setupStartTransaction(transaction);
            }
        }
        buildFinishTransaction(mFinishTransaction, info);
        mCleanupTransaction = mController.mAtm.mWindowManager.mTransactionFactory.get();
        buildCleanupTransaction(mCleanupTransaction, info);
        if (mController.getTransitionPlayer() != null && mIsPlayerEnabled) {
            mController.dispatchLegacyAppTransitionStarting(info, mStatusBarTransitionDelay);
            try {
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS,
                        "Calling onTransitionReady: %s", info);
                mLogger.mSendTimeNs = SystemClock.elapsedRealtimeNanos();
                mLogger.mInfo = info;
                mController.getTransitionPlayer().onTransitionReady(
                        mToken, info, transaction, mFinishTransaction);
                if (Trace.isTagEnabled(TRACE_TAG_WINDOW_MANAGER)) {
                    asyncTraceBegin(TRACE_NAME_PLAY_TRANSITION, System.identityHashCode(this));
                }
            } catch (RemoteException e) {
                // If there's an exception when trying to send the mergedTransaction to the
                // client, we should finish and apply it here so the transactions aren't lost.
                postCleanupOnFailure();
            }
            for (int i = 0; i < mTargetDisplays.size(); ++i) {
                final DisplayContent dc = mTargetDisplays.get(i);
                final AccessibilityController accessibilityController =
                        dc.mWmService.mAccessibilityController;
                if (accessibilityController.hasCallbacks()) {
                    accessibilityController.onWMTransition(dc.getDisplayId(), mType, mFlags);
                }
            }
        } else {
            // No player registered or it's not enabled, so just finish/apply immediately
            if (!mIsPlayerEnabled) {
                mLogger.mSendTimeNs = SystemClock.uptimeNanos();
                ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, "Apply and finish immediately"
                        + " because player is disabled for transition #%d .", mSyncId);
            }
            postCleanupOnFailure();
        }
        mOverrideOptions = null;

        reportStartReasonsToLogger();

        // Take snapshots for closing tasks/activities before the animation finished but after
        // dispatching onTransitionReady, so IME (if there is) can be captured together and the
        // time spent on snapshot won't delay the start of animation. Note that if this transition
        // is transient (mTransientLaunches != null), the snapshot will be captured at the end of
        // the transition, because IME won't move be moved during the transition and the tasks are
        // still live.
        if (mTransientLaunches == null) {
            mController.mSnapshotController.onTransactionReady(mType, mTargets);
        }

        // Since we created root-leash but no longer reference it from core, release it now
        info.releaseAnimSurfaces();

        if (mLogger.mInfo != null) {
            mLogger.logOnSendAsync(mController.mLoggerHandler);
            mController.mTransitionTracer.logSentTransition(this, mTargets);
        }
    }

    private void overrideAnimationOptionsToInfoIfNecessary(@NonNull TransitionInfo info) {
        if (mOverrideOptions == null) {
            return;
        }

        if (!Flags.moveAnimationOptionsToChange()) {
            info.setAnimationOptions(mOverrideOptions);
        } else {
            final List<TransitionInfo.Change> changes = info.getChanges();
            for (int i = changes.size() - 1; i >= 0; --i) {
                final WindowContainer<?> container = mTargets.get(i).mContainer;
                if (container.asActivityRecord() != null
                        || (container.asTask() != null
                                && mOverrideOptions.getOverrideTaskTransition())) {
                    changes.get(i).setAnimationOptions(mOverrideOptions);
                    // TODO(b/295805497): Extract mBackgroundColor from AnimationOptions.
                    changes.get(i).setBackgroundColor(mOverrideOptions.getBackgroundColor());
                }
            }
        }
        updateActivityTargetForCrossProfileAnimation(info);
    }

    /**
     * Updates activity open target if {@link #mOverrideOptions} is
     * {@link ANIM_OPEN_CROSS_PROFILE_APPS}.
     */
    private void updateActivityTargetForCrossProfileAnimation(@NonNull TransitionInfo info) {
        if (mOverrideOptions.getType() != ANIM_OPEN_CROSS_PROFILE_APPS) {
            return;
        }
        for (int i = 0; i < mTargets.size(); ++i) {
            final ActivityRecord activity = mTargets.get(i).mContainer
                    .asActivityRecord();
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (activity == null || change.getMode() != TRANSIT_OPEN) {
                continue;
            }

            int flags = change.getFlags();
            flags |= activity.mUserId == activity.mWmService.mCurrentUserId
                    ? TransitionInfo.FLAG_CROSS_PROFILE_OWNER_THUMBNAIL
                    : TransitionInfo.FLAG_CROSS_PROFILE_WORK_THUMBNAIL;
            change.setFlags(flags);
            break;
        }
    }

    @Override
    public void onTransactionCommitTimeout() {
        if (mCleanupTransaction == null) return;
        for (int i = mTargetDisplays.size() - 1; i >= 0; --i) {
            final DisplayContent dc = mTargetDisplays.get(i);
            final AsyncRotationController asyncRotationController = dc.getAsyncRotationController();
            if (asyncRotationController != null && containsChangeFor(dc, mTargets)) {
                asyncRotationController.onTransactionCommitTimeout(mCleanupTransaction);
            }
        }
    }

    /**
     * Adds a listener that will be executed after the start transaction of this transition
     * is presented on the screen, the listener will be executed on a binder thread
     */
    void addTransactionCompletedListener(Runnable listener) {
        if (mTransactionCompletedListeners == null) {
            mTransactionCompletedListeners = new ArrayList<>();
        }
        mTransactionCompletedListeners.add(listener);
    }

    /**
     * Adds a listener that will be executed after the transition is finished or aborted.
     */
    void addTransitionEndedListener(Runnable listener) {
        if (mState != STATE_COLLECTING && mState != STATE_STARTED) {
            throw new IllegalStateException(
                    "Can't register listeners if the transition isn't collecting. state=" + mState);
        }
        if (mTransitionEndedListeners == null) {
            mTransitionEndedListeners = new ArrayList<>();
        }
        mTransitionEndedListeners.add(listener);
    }

    /**
     * Checks if the transition contains order changes.
     *
     * This is a shallow check that doesn't account for collection in parallel, unlike
     * {@code collectOrderChanges}
     */
    boolean hasOrderChanges() {
        ArrayList<Task> onTopTasks = new ArrayList<>();
        // Iterate over target displays to get up to date on top tasks.
        // Cannot use `mOnTopTasksAtReady` as it's not populated before the `applyReady` is called.
        for (DisplayContent dc : mTargetDisplays) {
            addOnTopTasks(dc, onTopTasks);
        }
        for (Task task : onTopTasks) {
            if (!mOnTopTasksStart.contains(task)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collect tasks which moved-to-top as part of this transition. This also updates the
     * controller's latest-reported when relevant.
     *
     * This is a non-trivial operation because transition can collect in parallel; however, it can
     * be made tenable by acknowledging that the "setup" part of collection (phase 1) is still
     * globally serial; so, we can build some reasonable rules around it.
     *
     * First, we record the "start" on-top state (to compare against). Then, when this becomes
     * ready (via allReady, NOT onTransactionReady), we also record the "onReady" on-top state
     * -- the idea here is that upon "allReady", all the actual WM changes should be done and we
     * are now just waiting for window content to become ready (finish drawing).
     *
     * Then, in this function (during onTransactionReady), we compare the two orders and include
     * any changes to the order in the reported transition-info. Unfortunately, because of parallel
     * collection, the order can change in unexpected ways by now. To resolve this, we ALSO keep a
     * global "latest reported order" in TransitionController and use that to make decisions.
     */
    @VisibleForTesting
    void collectOrderChanges(boolean reportCurrent) {
        if (mOnTopTasksStart.isEmpty()) return;
        boolean includesOrderChange = false;
        for (int i = 0; i < mOnTopTasksAtReady.size(); ++i) {
            final Task task = mOnTopTasksAtReady.get(i);
            if (mOnTopTasksStart.contains(task)) continue;
            includesOrderChange = true;
            break;
        }
        if (!includesOrderChange && !reportCurrent) {
            // This transition doesn't include an order change, so if it isn't required to report
            // the current focus (eg. it's the last of a cluster of transitions), then don't
            // report.
            return;
        }
        // The transition included an order change, but it may not be up-to-date, so grab the
        // latest state and compare with the last reported state (or our start state if no
        // reported state exists).
        ArrayList<Task> onTopTasksEnd = new ArrayList<>();
        for (int d = 0; d < mTargetDisplays.size(); ++d) {
            addOnTopTasks(mTargetDisplays.get(d), onTopTasksEnd);
            final int displayId = mTargetDisplays.get(d).mDisplayId;
            ArrayList<Task> reportedOnTop = mController.mLatestOnTopTasksReported.get(displayId);
            for (int i = onTopTasksEnd.size() - 1; i >= 0; --i) {
                final Task task = onTopTasksEnd.get(i);
                if (task.getDisplayId() != displayId) continue;
                // If it didn't change since last report, don't report
                if (reportedOnTop == null) {
                    if (mOnTopTasksStart.contains(task)) continue;
                } else if (reportedOnTop.contains(task)) {
                    continue;
                }
                // Need to report it.
                mParticipants.add(task);
                int changeIdx = mChanges.indexOfKey(task);
                if (changeIdx < 0) {
                    mChanges.put(task, new ChangeInfo(task));
                    changeIdx = mChanges.indexOfKey(task);
                }
                mChanges.valueAt(changeIdx).mFlags |= ChangeInfo.FLAG_CHANGE_MOVED_TO_TOP;
            }
            // Swap in the latest on-top tasks.
            mController.mLatestOnTopTasksReported.put(displayId, onTopTasksEnd);
            onTopTasksEnd = reportedOnTop != null ? reportedOnTop : new ArrayList<>();
            onTopTasksEnd.clear();
        }
    }

    private void postCleanupOnFailure() {
        mController.mAtm.mH.post(() -> {
            synchronized (mController.mAtm.mGlobalLock) {
                cleanUpOnFailure();
            }
        });
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

    private void cleanUpInternal() {
        // Clean-up any native references.
        for (int i = 0; i < mChanges.size(); ++i) {
            final ChangeInfo ci = mChanges.valueAt(i);
            if (ci.mSnapshot != null) {
                ci.mSnapshot.release();
            }
        }
        if (mCleanupTransaction != null) {
            mCleanupTransaction.apply();
            mCleanupTransaction = null;
        }
    }

    /** The transition is ready to play. Make the start transaction show the surfaces. */
    private void commitVisibleActivities(SurfaceControl.Transaction transaction) {
        for (int i = mParticipants.size() - 1; i >= 0; --i) {
            final ActivityRecord ar = mParticipants.valueAt(i).asActivityRecord();
            if (ar == null || ar.getTask() == null) {
                continue;
            }
            if (ar.isVisibleRequested()) {
                ar.commitVisibility(true /* visible */, false /* performLayout */,
                        true /* fromTransition */);
                ar.commitFinishDrawing(transaction);
            }
            ar.getTask().setDeferTaskAppear(false);
        }
    }

    /**
     * Reset waitingToshow for all wallpapers, and commit the visibility of the visible ones
     */
    private void commitVisibleWallpapers() {
        boolean showWallpaper = shouldWallpaperBeVisible();
        for (int i = mParticipants.size() - 1; i >= 0; --i) {
            final WallpaperWindowToken wallpaper = mParticipants.valueAt(i).asWallpaperToken();
            if (wallpaper != null) {
                if (!wallpaper.isVisible() && wallpaper.isVisibleRequested()) {
                    wallpaper.commitVisibility(showWallpaper);
                }
            }
        }
    }

    private boolean shouldWallpaperBeVisible() {
        for (int i = mParticipants.size() - 1; i >= 0; --i) {
            WindowContainer participant = mParticipants.valueAt(i);
            if (participant.showWallpaper()) return true;
        }
        return false;
    }

    // TODO(b/188595497): Remove after migrating to shell.
    /** @see RecentsAnimationController#attachNavigationBarToApp */
    private void handleLegacyRecentsStartBehavior(DisplayContent dc, TransitionInfo info) {
        if ((mFlags & TRANSIT_FLAG_IS_RECENTS) == 0) {
            return;
        }

        // Recents has an input-consumer to grab input from the "live tile" app. Set that up here
        final InputConsumerImpl recentsAnimationInputConsumer =
                dc.getInputMonitor().getInputConsumer(INPUT_CONSUMER_RECENTS_ANIMATION);
        ActivityRecord recentsActivity = null;
        if (recentsAnimationInputConsumer != null) {
            // Find the top-most going-away task and the recents activity. The top-most
            // is used as layer reference while the recents is used for registering the consumer
            // override.
            Task topNonRecentsTask = null;
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final ActivityManager.RunningTaskInfo taskInfo =
                        info.getChanges().get(i).getTaskInfo();
                if (taskInfo == null) continue;
                final Task task = Task.fromWindowContainerToken(taskInfo.token);
                if (task == null) continue;
                final int activityType = taskInfo.topActivityType;
                final boolean isRecents = activityType == ACTIVITY_TYPE_HOME
                        || activityType == ACTIVITY_TYPE_RECENTS;
                if (isRecents && recentsActivity == null) {
                    recentsActivity = task.getTopVisibleActivity();
                } else if (!isRecents && topNonRecentsTask == null) {
                    topNonRecentsTask = task;
                }
            }
            if (recentsActivity != null && topNonRecentsTask != null) {
                recentsAnimationInputConsumer.mWindowHandle.touchableRegion.set(
                        topNonRecentsTask.getBounds());
                dc.getInputMonitor().setActiveRecents(recentsActivity, topNonRecentsTask);
            }
        }

        if (recentsActivity == null) {
            // No recents activity on `dc`, its probably on a different display.
            return;
        }
        mRecentsDisplayId = dc.mDisplayId;

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
        mController.mNavigationBarAttachedToApp = true;
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
        final StatusBarManagerInternal bar = dc.getDisplayPolicy().getStatusBarManagerInternal();
        if (bar != null) {
            bar.setNavigationBarLumaSamplingEnabled(mRecentsDisplayId, false);
        }
    }

    /** @see RecentsAnimationController#restoreNavigationBarFromApp */
    void legacyRestoreNavigationBarFromApp() {
        if (!mController.mNavigationBarAttachedToApp) {
            return;
        }
        mController.mNavigationBarAttachedToApp = false;

        int recentsDisplayId = mRecentsDisplayId;
        if (recentsDisplayId == INVALID_DISPLAY) {
            Slog.i(TAG, "Restore parent surface of navigation bar by another transition");
            recentsDisplayId = DEFAULT_DISPLAY;
        }

        final DisplayContent dc =
                mController.mAtm.mRootWindowContainer.getDisplayContent(recentsDisplayId);
        final StatusBarManagerInternal bar = dc.getDisplayPolicy().getStatusBarManagerInternal();
        if (bar != null) {
            bar.setNavigationBarLumaSamplingEnabled(recentsDisplayId, true);
        }
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

        // To apply transactions.
        dc.mWmService.scheduleAnimationLocked();
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
        sb.append(" flags=0x" + Integer.toHexString(mFlags));
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
        if (taskFragment == null) {
            return !wc.fillsParent();
        }

        // Check containers differently as they are affected by child visibility.

        if (taskFragment.isTranslucentForTransition()) {
            // TaskFragment doesn't contain occluded ActivityRecord.
            return true;
        }
        final TaskFragment adjacentTaskFragment = taskFragment.getAdjacentTaskFragment();
        if (adjacentTaskFragment != null) {
            // When the TaskFragment has an adjacent TaskFragment, sibling behind them should be
            // hidden unless any of them are translucent.
            return adjacentTaskFragment.isTranslucentForTransition();
        } else {
            // Non-filling without adjacent is considered as translucent.
            return !wc.fillsParent();
        }
    }

    private void updatePriorVisibility() {
        for (int i = 0; i < mChanges.size(); ++i) {
            final ChangeInfo chg = mChanges.valueAt(i);
            // For task/activity, recalculate the current "real" visibility.
            if (chg.mContainer.asActivityRecord() == null && chg.mContainer.asTask() == null) {
                continue;
            }
            // This ONLY works in the visible -> invisible case (and is only needed for this case)
            // because commitVisible(false) is deferred until finish.
            if (!chg.mVisible) continue;
            chg.mVisible = chg.mContainer.isVisible();
        }
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
            if ((targetChange.mFlags & ChangeInfo.FLAG_CHANGE_CONFIG_AT_END) != 0) {
                parentChange.mFlags |= ChangeInfo.FLAG_CHANGE_CONFIG_AT_END;
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
            // Reject no-ops, unless wallpaper
            if (!changeInfo.hasChanged() && wc.asWallpaperToken() == null) {
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
                if (parentChange == null) {
                    break;
                }
                if (!parentChange.hasChanged()) {
                    // In case the target is collected after the parent has been changed, it could
                    // be too late to snapshot the parent change. Skip to see if there is any
                    // parent window further up to be considered as change parent.
                    continue;
                }
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
                    // for config-at-end, we want to promote the flag based on the end-state even
                    // if the activity was reparented because it operates after the animation. So,
                    // check that here since the promote code skips reparents.
                    if ((targetChange.mFlags & ChangeInfo.FLAG_CHANGE_CONFIG_AT_END) != 0
                            && targetChange.mContainer.asActivityRecord() != null
                            && targetChange.mContainer.getParent() == p) {
                        parentChange.mFlags |= ChangeInfo.FLAG_CHANGE_CONFIG_AT_END;
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

    private static int getDisplayId(@NonNull WindowContainer wc) {
        return wc.getDisplayContent() != null
                ? wc.getDisplayContent().getDisplayId() : INVALID_DISPLAY;
    }

    @VisibleForTesting
    static void calculateTransitionRoots(@NonNull TransitionInfo outInfo,
            ArrayList<ChangeInfo> sortedTargets,
            @NonNull SurfaceControl.Transaction startT) {
        // There needs to be a root on each display.
        for (int i = 0; i < sortedTargets.size(); ++i) {
            final WindowContainer<?> wc = sortedTargets.get(i).mContainer;
            // Don't include wallpapers since they are in a different DA.
            if (isWallpaper(wc)) continue;
            final DisplayContent dc = wc.getDisplayContent();
            if (dc == null) continue;
            final int endDisplayId = dc.getDisplayId();

            // Check if Root was already created for this display with a higher-Z window
            if (outInfo.findRootIndex(endDisplayId) >= 0) continue;

            WindowContainer<?> ancestor = findCommonAncestor(sortedTargets, wc);

            // Make leash based on highest (z-order) direct child of ancestor with a participant.
            // Check whether the ancestor is belonged to last parent, shouldn't happen.
            final boolean hasReparent = !wc.isDescendantOf(ancestor);
            WindowContainer leashReference = wc;
            if (hasReparent) {
                Slog.e(TAG, "Did not find common ancestor! Ancestor= " + ancestor
                        + " target= " + wc);
            } else {
                while (leashReference.getParent() != ancestor) {
                    leashReference = leashReference.getParent();
                }
            }
            final SurfaceControl rootLeash = leashReference.makeAnimationLeash().setName(
                    "Transition Root: " + leashReference.getName())
                    .setCallsite("Transition.calculateTransitionRoots").build();
            rootLeash.setUnreleasedWarningCallSite("Transition.calculateTransitionRoots");
            // Update layers to start transaction because we prevent assignment during collect, so
            // the layer of transition root can be correct.
            assignLayers(dc, startT);
            startT.setLayer(rootLeash, leashReference.getLastLayer());
            outInfo.addRootLeash(endDisplayId, rootLeash,
                    ancestor.getBounds().left, ancestor.getBounds().top);
        }
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
            @NonNull SurfaceControl.Transaction startT) {
        final TransitionInfo out = new TransitionInfo(type, flags);
        calculateTransitionRoots(out, sortedTargets, startT);
        if (out.getRootCount() == 0) {
            return out;
        }

        final AnimationOptions animOptionsForActivityTransition =
                calculateAnimationOptionsForActivityTransition(type, sortedTargets);
        if (!Flags.moveAnimationOptionsToChange() && animOptionsForActivityTransition != null) {
            out.setAnimationOptions(animOptionsForActivityTransition);
        }

        final ArraySet<WindowContainer> occludedAtEndContainers = new ArraySet<>();
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
            info.mReadyMode = change.getMode();
            change.setStartAbsBounds(info.mAbsoluteBounds);
            change.setFlags(info.getChangeFlags(target));
            info.mReadyFlags = change.getFlags();
            change.setDisplayId(info.mDisplayId, getDisplayId(target));

            // Add FLAGS_IS_OCCLUDED to preventing from visible-translucent change which belows
            // the non-translucent change playing unexpected open animation.
            if (change.getMode() == TRANSIT_TO_FRONT || change.getMode() == TRANSIT_OPEN) {
                for (int occIndex = occludedAtEndContainers.size() - 1; occIndex >= 0; --occIndex) {
                    if (target.isDescendantOf(occludedAtEndContainers.valueAt(occIndex))) {
                        change.setFlags(change.getFlags() | FLAG_IS_OCCLUDED);
                        break;
                    }
                }
            }
            if (!change.hasFlags(FLAG_TRANSLUCENT)  && (change.getMode() == TRANSIT_OPEN
                    || change.getMode() == TRANSIT_TO_FRONT
                    || change.getMode() == TRANSIT_CHANGE)) {
                occludedAtEndContainers.add(target.getParent());
            }

            final Task task = target.asTask();
            final TaskFragment taskFragment = target.asTaskFragment();
            final boolean isEmbeddedTaskFragment = taskFragment != null
                    && taskFragment.isEmbedded();
            final ActivityRecord activityRecord = target.asActivityRecord();

            if (task != null) {
                final ActivityManager.RunningTaskInfo tinfo = new ActivityManager.RunningTaskInfo();
                task.fillTaskInfo(tinfo);
                change.setTaskInfo(tinfo);
                change.setRotationAnimation(getTaskRotationAnimation(task));
                final ActivityRecord topRunningActivity = task.topRunningActivity();
                if (topRunningActivity != null) {
                    if (topRunningActivity.info.supportsPictureInPicture()) {
                        change.setAllowEnterPip(
                                topRunningActivity.checkEnterPictureInPictureAppOpsState());
                    }
                    setEndFixedRotationIfNeeded(change, task, topRunningActivity);
                }
            } else if ((info.mFlags & ChangeInfo.FLAG_SEAMLESS_ROTATION) != 0) {
                change.setRotationAnimation(ROTATION_ANIMATION_SEAMLESS);
            }

            final WindowContainer<?> parent = target.getParent();
            final Rect bounds = target.getBounds();
            final Rect parentBounds = parent.getBounds();
            change.setEndRelOffset(bounds.left - parentBounds.left,
                    bounds.top - parentBounds.top);
            if (Flags.activityEmbeddingOverlayPresentationFlag()) {
                change.setEndParentSize(parentBounds.width(), parentBounds.height());
            }
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

            if (activityRecord != null || isEmbeddedTaskFragment) {
                final int backgroundColor;
                final TaskFragment organizedTf = activityRecord != null
                        ? activityRecord.getOrganizedTaskFragment()
                        : taskFragment.getOrganizedTaskFragment();
                if (organizedTf != null && organizedTf.getAnimationParams()
                        .getAnimationBackgroundColor() != DEFAULT_ANIMATION_BACKGROUND_COLOR) {
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
                    backgroundColor = parentTask.getTaskDescription().getBackgroundColor();
                }
                // Set to opaque for animation background to prevent it from exposing the blank
                // background or content below.
                change.setBackgroundColor(ColorUtils.setAlphaComponent(backgroundColor, 255));
            }

            AnimationOptions animOptions = null;
            if (Flags.moveAnimationOptionsToChange()) {
                if (activityRecord != null && animOptionsForActivityTransition != null) {
                    animOptions = animOptionsForActivityTransition;
                } else if (Flags.activityEmbeddingOverlayPresentationFlag()
                        && isEmbeddedTaskFragment) {
                    final TaskFragmentAnimationParams params = taskFragment.getAnimationParams();
                    if (params.hasOverrideAnimation()) {
                        // Only set AnimationOptions if there's any animation override.
                        // We use separated field for backgroundColor, and
                        // AnimationOptions#backgroundColor will be removed in long term.
                        animOptions = AnimationOptions.makeCustomAnimOptions(
                                taskFragment.getTask().getBasePackageName(),
                                params.getOpenAnimationResId(), params.getChangeAnimationResId(),
                                params.getCloseAnimationResId(), 0 /* backgroundColor */,
                                false /* overrideTaskTransition */);
                    }
                }
                if (animOptions != null) {
                    change.setAnimationOptions(animOptions);
                }
            }

            if (activityRecord != null) {
                change.setActivityComponent(activityRecord.mActivityComponent);
            }

            change.setRotation(info.mRotation, endRotation);
            if (info.mSnapshot != null) {
                change.setSnapshot(info.mSnapshot, info.mSnapshotLuma);
            }

            out.addChange(change);
        }
        return out;
    }

    /**
     * Calculates {@link AnimationOptions} for activity-to-activity transition.
     * It returns a valid {@link AnimationOptions} if:
     * <ul>
     *   <li>the top animation target is an Activity</li>
     *   <li>there's a {@link android.view.Window#setWindowAnimations(int)} and there's only
     *     {@link WindowState}, {@link WindowToken} and {@link ActivityRecord} target</li>
     * </ul>
     * Otherwise, it returns {@code null}.
     */
    @Nullable
    private static AnimationOptions calculateAnimationOptionsForActivityTransition(
            @TransitionType int type, @NonNull ArrayList<ChangeInfo> sortedTargets) {
        TransitionInfo.AnimationOptions animOptions = null;

        // Check if the top-most app is an activity (ie. activity->activity). If so, make sure
        // to honor its custom transition options.
        WindowContainer<?> topApp = null;
        for (int i = 0; i < sortedTargets.size(); i++) {
            if (isWallpaper(sortedTargets.get(i).mContainer)) continue;
            topApp = sortedTargets.get(i).mContainer;
            break;
        }
        if (topApp.asActivityRecord() != null) {
            final ActivityRecord topActivity = topApp.asActivityRecord();
            animOptions = addCustomActivityTransition(topActivity, true/* open */,
                    null /* animOptions */);
            animOptions = addCustomActivityTransition(topActivity, false/* open */,
                    animOptions);
        }
        final WindowManager.LayoutParams animLp =
                getLayoutParamsForAnimationsStyle(type, sortedTargets);
        if (animLp != null && animLp.type != TYPE_APPLICATION_STARTING
                && animLp.windowAnimations != 0) {
            // Don't send animation options if no windowAnimations have been set or if the we
            // are running an app starting animation, in which case we don't want the app to be
            // able to change its animation directly.
            if (animOptions != null) {
                animOptions.addOptionsFromLayoutParameters(animLp);
            } else {
                animOptions = TransitionInfo.AnimationOptions
                        .makeAnimOptionsFromLayoutParameters(animLp);
            }
        }
        return animOptions;
    }

    /**
     * Returns {@link TransitionInfo.AnimationOptions} with custom Activity transition appended if
     * {@code topActivity} specifies {@link ActivityRecord#getCustomAnimation(boolean)}, or
     * {@code animOptions}, otherwise.
     * <p>
     * If the passed {@code animOptions} is {@code null}, this method will creates an
     * {@link TransitionInfo.AnimationOptions} with custom animation appended
     *
     * @param open {@code true} to add a custom open animation, and {@false} to add a close one
     */
    @Nullable
    private static TransitionInfo.AnimationOptions addCustomActivityTransition(
            @NonNull ActivityRecord activity, boolean open,
            @Nullable TransitionInfo.AnimationOptions animOptions) {
        final ActivityRecord.CustomAppTransition customAnim =
                activity.getCustomAnimation(open);
        if (customAnim != null) {
            if (animOptions == null) {
                animOptions = TransitionInfo.AnimationOptions
                        .makeCommonAnimOptions(activity.packageName);
            }
            animOptions.addCustomActivityTransition(open, customAnim.mEnterAnim,
                    customAnim.mExitAnim, customAnim.mBackgroundColor);
        }
        return animOptions;
    }

    private static void setEndFixedRotationIfNeeded(@NonNull TransitionInfo.Change change,
            @NonNull Task task, @NonNull ActivityRecord taskTopRunning) {
        if (!taskTopRunning.isVisibleRequested()) {
            // Fixed rotation only applies to opening or changing activity.
            return;
        }
        if (task.inMultiWindowMode() && taskTopRunning.inMultiWindowMode()) {
            // Display won't be rotated for multi window Task, so the fixed rotation won't be
            // applied. This can happen when the windowing mode is changed before the previous
            // fixed rotation is applied. Check both task and activity because the activity keeps
            // fullscreen mode when the task is entering PiP.
            return;
        }
        final int taskRotation = task.getWindowConfiguration().getDisplayRotation();
        final int activityRotation = taskTopRunning.getWindowConfiguration()
                .getDisplayRotation();
        // If the Activity uses fixed rotation, its rotation will be applied to display after
        // the current transition is done, while the Task is still in the previous rotation.
        if (taskRotation != activityRotation) {
            change.setEndFixedRotation(activityRotation);
            return;
        }

        // For example, the task is entering PiP so it no longer decides orientation. If the next
        // orientation source (it could be an activity which was behind the PiP or launching to top)
        // will change display rotation, then set the fixed rotation hint as well so the animation
        // can consider the rotated position.
        if (!task.inPinnedWindowingMode() || taskTopRunning.mDisplayContent.inTransition()) {
            return;
        }
        final WindowContainer<?> orientationSource =
                taskTopRunning.mDisplayContent.getLastOrientationSource();
        if (orientationSource == null) {
            return;
        }
        final int nextRotation = orientationSource.getWindowConfiguration().getDisplayRotation();
        if (taskRotation != nextRotation) {
            change.setEndFixedRotation(nextRotation);
        }
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
        final int displayId = getDisplayId(topApp);
        WindowContainer<?> ancestor = topApp.getParent();
        // Go up ancestor parent chain until all targets are descendants. Ancestor should never be
        // null because all targets are attached.
        for (int i = targets.size() - 1; i >= 0; i--) {
            final ChangeInfo change = targets.get(i);
            final WindowContainer wc = change.mContainer;
            if (isWallpaper(wc) || getDisplayId(wc) != displayId) {
                // Skip the non-app window or windows on a different display
                continue;
            }
            // Re-initiate the last parent as the initial ancestor instead of the top target.
            // When move a leaf task from organized task to display area, try to keep the transition
            // root be the original organized task for close transition animation.
            // Otherwise, shell will use wrong root layer to play animation.
            // Note: Since the target is sorted, so only need to do this at the lowest target.
            if (change.mStartParent != null && wc.getParent() != null
                    && change.mStartParent.isAttached() && wc.getParent() != change.mStartParent
                    && i == targets.size() - 1) {
                final int transitionMode = change.getTransitMode(wc);
                if (transitionMode == TRANSIT_CLOSE || transitionMode == TRANSIT_TO_BACK) {
                    ancestor = change.mStartParent;
                    continue;
                }
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

    private void validateKeyguardOcclusion() {
        if ((mFlags & KEYGUARD_VISIBILITY_TRANSIT_FLAGS) != 0) {
            mController.mStateValidators.add(
                mController.mAtm.mWindowManager.mPolicy::applyKeyguardOcclusionChange);
        }
    }

    /** Returns {@code true} if the display should use high performance hint for this transition. */
    boolean shouldUsePerfHint(@NonNull DisplayContent dc) {
        if (mOverrideOptions != null
                && mOverrideOptions.getType() == ActivityOptions.ANIM_SCENE_TRANSITION
                && mType == TRANSIT_TO_BACK && mParticipants.size() == 1) {
            // This should be from convertFromTranslucent that makes the occluded activity invisible
            // without animation. So do not use perf hint (especially early-wakeup) that may disturb
            // SurfaceFlinger scheduling around the last frame.
            return false;
        }
        return mTargetDisplays.contains(dc);
    }

    /**
     * Returns {@code true} if the transition and the corresponding transaction should be applied
     * on display thread. Currently, this only checks for display rotation change because the order
     * of dispatching the new display info will be after requesting the windows to sync drawing.
     * That avoids potential flickering of screen overlays (e.g. cutout, rounded corner). Also,
     * because the display thread has a higher priority, it is faster to perform the configuration
     * changes and window hierarchy traversal.
     */
    boolean shouldApplyOnDisplayThread() {
        for (int i = mParticipants.size() - 1; i >= 0; --i) {
            final DisplayContent dc = mParticipants.valueAt(i).asDisplayContent();
            if (dc == null) continue;
            final ChangeInfo changeInfo = mChanges.get(dc);
            if (changeInfo != null && changeInfo.mRotation != dc.getRotation()) {
                return Looper.myLooper() != mController.mAtm.mWindowManager.mH.getLooper();
            }
        }
        return false;
    }

    /**
     * Applies the new configuration for the changed displays. Returns the activities that should
     * check whether to deliver the new configuration to clients.
     */
    @Nullable
    void applyDisplayChangeIfNeeded(@NonNull ArraySet<WindowContainer<?>> activitiesMayChange) {
        for (int i = mParticipants.size() - 1; i >= 0; --i) {
            final WindowContainer<?> wc = mParticipants.valueAt(i);
            final DisplayContent dc = wc.asDisplayContent();
            if (dc == null || !mChanges.get(dc).hasChanged()) continue;
            final boolean changed = dc.sendNewConfiguration();
            // Set to ready if no other change controls the ready state. But if there is, such as
            // if an activity is pausing, it will call setReady(ar, false) and wait for the next
            // resumed activity. Then do not set to ready because the transition only contains
            // partial participants. Otherwise the transition may only handle HIDE and miss OPEN.
            if (!mReadyTrackerOld.mUsed) {
                setReady(dc, true);
            }
            if (!changed) continue;
            // If the update is deferred, sendNewConfiguration won't deliver new configuration to
            // clients, then it is the caller's responsibility to deliver the changes.
            if (mController.mAtm.mTaskSupervisor.isRootVisibilityUpdateDeferred()) {
                dc.forAllActivities(r -> {
                    if (r.isVisibleRequested()) {
                        activitiesMayChange.add(r);
                    }
                });
            }
        }
    }

    boolean getLegacyIsReady() {
        return isCollecting() && mSyncId >= 0;
    }

    static void asyncTraceBegin(@NonNull String name, int cookie) {
        Trace.asyncTraceForTrackBegin(Trace.TRACE_TAG_WINDOW_MANAGER, TAG, name, cookie);
    }

    static void asyncTraceEnd(int cookie) {
        Trace.asyncTraceForTrackEnd(Trace.TRACE_TAG_WINDOW_MANAGER, TAG, cookie);
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

        /** Whether this change's container moved to the top. */
        private static final int FLAG_CHANGE_MOVED_TO_TOP = 0x20;

        /** Whether this change contains config-at-end members. */
        private static final int FLAG_CHANGE_CONFIG_AT_END = 0x40;

        @IntDef(prefix = { "FLAG_" }, value = {
                FLAG_NONE,
                FLAG_SEAMLESS_ROTATION,
                FLAG_TRANSIENT_LAUNCH,
                FLAG_ABOVE_TRANSIENT_LAUNCH,
                FLAG_CHANGE_NO_ANIMATION,
                FLAG_CHANGE_YES_ANIMATION,
                FLAG_CHANGE_MOVED_TO_TOP,
                FLAG_CHANGE_CONFIG_AT_END
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
        int mDisplayId = -1;
        @ActivityInfo.Config int mKnownConfigChanges;

        /** Extra information about this change. */
        @Flag int mFlags = FLAG_NONE;

        /** Snapshot surface and luma, if relevant. */
        SurfaceControl mSnapshot;
        float mSnapshotLuma;

        /** The mode which is set when the transition is ready. */
        @TransitionInfo.TransitionMode
        int mReadyMode;

        /** The flags which is set when the transition is ready. */
        @TransitionInfo.ChangeFlags
        int mReadyFlags;

        ChangeInfo(@NonNull WindowContainer origState) {
            mContainer = origState;
            mVisible = origState.isVisibleRequested();
            mWindowingMode = origState.getWindowingMode();
            mAbsoluteBounds.set(origState.getBounds());
            mShowWallpaper = origState.showWallpaper();
            mRotation = origState.getWindowConfiguration().getRotation();
            mStartParent = origState.getParent();
            mDisplayId = getDisplayId(origState);
        }

        @VisibleForTesting
        ChangeInfo(@NonNull WindowContainer container, boolean visible, boolean existChange) {
            this(container);
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
                    || mRotation != mContainer.getWindowConfiguration().getRotation()
                    || mDisplayId != getDisplayId(mContainer)
                    || (mFlags & ChangeInfo.FLAG_CHANGE_MOVED_TO_TOP) != 0;
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
            if (wc.mWmService.mAtmService.mBackNavigationController.isMonitorTransitionTarget(wc)) {
                flags |= TransitionInfo.FLAG_BACK_GESTURE_ANIMATED;
            }
            final Task task = wc.asTask();
            if (task != null) {
                final ActivityRecord topActivity = task.getTopNonFinishingActivity();
                if (topActivity != null) {
                    if (topActivity.mStartingData != null
                            && topActivity.mStartingData.hasImeSurface()) {
                        flags |= FLAG_WILL_IME_SHOWN;
                    }
                    if (topActivity.mLaunchTaskBehind) {
                        Slog.e(TAG, "Unexpected launch-task-behind operation in shell transition");
                        flags |= FLAG_TASK_LAUNCHING_BEHIND;
                    }
                    if ((topActivity.mTransitionChangeFlags & FLAGS_IS_OCCLUDED_NO_ANIMATION)
                            == FLAGS_IS_OCCLUDED_NO_ANIMATION) {
                        flags |= FLAGS_IS_OCCLUDED_NO_ANIMATION;
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
                if (record.isConfigurationDispatchPaused()) {
                    flags |= FLAG_CONFIG_AT_END;
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
                final ActivityRecord starting = parentTask.topActivityContainsStartingWindow();
                if (starting != null) {
                    if (starting == record || (starting.mStartingData != null
                            && starting.mStartingData.mAssociatedTask != null)) {
                        flags |= FLAG_IS_BEHIND_STARTING_WINDOW;
                    } else if (record != null && parentTask.mChildren.indexOf(record)
                            < parentTask.mChildren.indexOf(starting)) {
                        flags |= FLAG_IS_BEHIND_STARTING_WINDOW;
                    }
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
            if ((mFlags & FLAG_CHANGE_NO_ANIMATION) != 0
                    && (mFlags & FLAG_CHANGE_YES_ANIMATION) == 0) {
                flags |= FLAG_NO_ANIMATION;
            }
            if ((mFlags & FLAG_CHANGE_MOVED_TO_TOP) != 0) {
                flags |= FLAG_MOVED_TO_TOP;
            }
            if ((mFlags & FLAG_CHANGE_CONFIG_AT_END) != 0) {
                flags |= FLAG_CONFIG_AT_END;
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
        ++mReadyTrackerOld.mDeferReadyDepth;
        // Make sure it wait until #continueTransitionReady() is called.
        mSyncEngine.setReady(mSyncId, false);
    }

    /** This undoes one call to {@link #deferTransitionReady}. */
    void continueTransitionReady() {
        --mReadyTrackerOld.mDeferReadyDepth;
        // Apply ready in case it is waiting for the previous defer call.
        applyReady();
    }

    @Override
    public void onReadyTimeout() {
        if (!mController.useFullReadyTracking()) {
            Slog.e(TAG, "#" + mSyncId + " readiness timeout, used=" + mReadyTrackerOld.mUsed
                    + " deferReadyDepth=" + mReadyTrackerOld.mDeferReadyDepth
                    + " group=" + mReadyTrackerOld.mReadyGroups);
            return;
        }
        Slog.e(TAG, "#" + mSyncId + " met conditions: " + mReadyTracker.mMet);
        Slog.e(TAG, "#" + mSyncId + " unmet conditions: " + mReadyTracker.mConditions);
    }

    /**
     * Represents a condition that must be met before an associated transition can be considered
     * ready.
     *
     * Expected usage is that a ReadyCondition is created and then attached to a transition's
     * ReadyTracker via {@link ReadyTracker#add}. After that, it is expected to monitor the state
     * of the system and when the condition it represents is met, it will call
     * {@link ReadyTracker#meet}.
     *
     * This base class is a simple explicit, named condition. A caller will create/attach the
     * condition and then explicitly call {@link #meet} on it (which internally calls
     * {@link ReadyTracker#meet}.
     *
     * Example:
     * <pre>
     *     ReadyCondition myCondition = new ReadyCondition("my condition");
     *     transitionController.waitFor(myCondition);
     *     ... Some operations ...
     *     myCondition.meet();
     * </pre>
     */
    static class ReadyCondition {
        final String mName;

        /** Just used for debugging */
        final Object mDebugTarget;
        ReadyTracker mTracker;
        boolean mMet = false;

        /** If set (non-null), then this is met by another reason besides state (eg. timeout). */
        String mAlternate = null;

        ReadyCondition(@NonNull String name) {
            mName = name;
            mDebugTarget = null;
        }

        ReadyCondition(@NonNull String name, @Nullable Object debugTarget) {
            mName = name;
            mDebugTarget = debugTarget;
        }

        protected String getDebugRep() {
            if (mDebugTarget != null) {
                return mName + ":" + mDebugTarget;
            }
            return mName;
        }

        @Override
        public String toString() {
            return "{" + getDebugRep() + (mAlternate != null ? " (" + mAlternate + ")" : "") + "}";
        }

        /**
         * Instructs this condition to start tracking system state to detect when this is met.
         * Don't call this directly; it is called when this object is attached to a transition's
         * ready-tracker.
         */
        void startTracking() {
        }

        /**
         * Immediately consider this condition met by an alternative reason (one which doesn't
         * match the normal intent of this condition -- eg. a timeout).
         */
        void meetAlternate(@NonNull String reason) {
            if (mMet) return;
            mAlternate = reason;
            meet();
        }

        /** Immediately consider this condition met. */
        void meet() {
            if (mMet) return;
            if (mTracker == null) {
                throw new IllegalStateException("Can't meet a condition before it is waited on");
            }
            mTracker.meet(this);
        }
    }

    static class ReadyTracker {
        /**
         * Used as a place-holder in situations where the transition system isn't active (such as
         * early-boot, mid shell crash/recovery, or when using legacy).
         */
        static final ReadyTracker NULL_TRACKER = new ReadyTracker(null);

        private final Transition mTransition;

        /** List of conditions that are still being waited on. */
        final ArrayList<ReadyCondition> mConditions = new ArrayList<>();

        /** List of already-met conditions. Fully-qualified for debugging. */
        final ArrayList<ReadyCondition> mMet = new ArrayList<>();

        ReadyTracker(Transition transition) {
            mTransition = transition;
        }

        void add(@NonNull ReadyCondition condition) {
            if (mTransition == null || !mTransition.mController.useFullReadyTracking()) {
                condition.mTracker = NULL_TRACKER;
                return;
            }
            mConditions.add(condition);
            condition.mTracker = this;
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, " Add condition %s for #%d",
                    condition, mTransition.mSyncId);
            condition.startTracking();
        }

        void meet(@NonNull ReadyCondition condition) {
            if (mTransition == null || !mTransition.mController.useFullReadyTracking()) return;
            if (mTransition.mState >= STATE_PLAYING) {
                Slog.w(TAG, "#%d: Condition met too late, already in state=" + mTransition.mState
                        + ": " + condition);
                return;
            }
            if (!mConditions.remove(condition)) {
                if (mMet.contains(condition)) {
                    throw new IllegalStateException("Can't meet the same condition more than once: "
                            + condition + " #" + mTransition.mSyncId);
                } else {
                    throw new IllegalArgumentException("Can't meet a condition that isn't being "
                            + "waited on: " + condition + " in #" + mTransition.mSyncId);
                }
            }
            condition.mMet = true;
            ProtoLog.v(ProtoLogGroup.WM_DEBUG_WINDOW_TRANSITIONS, " Met condition %s for #%d (%d"
                    + " left)", condition, mTransition.mSyncId, mConditions.size());
            mMet.add(condition);
            mTransition.applyReady();
        }

        boolean isReady() {
            return mConditions.isEmpty() && !mMet.isEmpty();
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
    private static class ReadyTrackerOld {
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
            final boolean isDisplayRotation = wc.asDisplayContent() != null
                    && wc.asDisplayContent().isRotationChanging();
            ScreenCapture.LayerCaptureArgs captureArgs =
                    new ScreenCapture.LayerCaptureArgs.Builder(wc.getSurfaceControl())
                            .setSourceCrop(cropBounds)
                            .setCaptureSecureLayers(true)
                            .setAllowProtected(true)
                            .setHintForSeamlessTransition(isDisplayRotation)
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
            // Some tests may check the name "RotationLayer" to detect display rotation.
            final String name = isDisplayRotation ? "RotationLayer" : "transition snapshot: " + wc;
            SurfaceControl snapshotSurface = wc.makeAnimationLeash()
                    .setName(name)
                    .setOpaque(wc.fillsParent())
                    .setParent(wc.getSurfaceControl())
                    .setSecure(screenshotBuffer.containsSecureLayers())
                    .setCallsite("Transition.ScreenshotSync")
                    .setBLASTLayer()
                    .build();
            mFrozen.add(wc);
            final ChangeInfo changeInfo = Objects.requireNonNull(mChanges.get(wc));
            changeInfo.mSnapshot = snapshotSurface;
            if (changeInfo.mRotation != wc.mDisplayContent.getRotation()) {
                // This isn't cheap, so only do it for rotation change.
                changeInfo.mSnapshotLuma = TransitionAnimation.getBorderLuma(
                        buffer, screenshotBuffer.getColorSpace(), wc.mSurfaceControl);
            }
            SurfaceControl.Transaction t = wc.mWmService.mTransactionFactory.get();
            TransitionAnimation.configureScreenshotLayer(t, snapshotSurface, screenshotBuffer);
            t.show(snapshotSurface);

            // Place it on top of anything else in the container.
            t.setLayer(snapshotSurface, Integer.MAX_VALUE);
            t.apply();
            t.close();
            buffer.close();

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
