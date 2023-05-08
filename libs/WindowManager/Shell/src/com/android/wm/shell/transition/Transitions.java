/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.transition;

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FIRST_CUSTOM;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_SLEEP;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.fixScale;
import static android.window.TransitionInfo.FLAG_IS_OCCLUDED;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;
import static android.window.TransitionInfo.FLAG_NO_ANIMATION;
import static android.window.TransitionInfo.FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.sysui.ShellSharedConstants.KEY_EXTRA_SHELL_SHELL_TRANSITIONS;
import static com.android.wm.shell.util.TransitionUtil.isClosingType;
import static com.android.wm.shell.util.TransitionUtil.isOpeningType;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.IApplicationThread;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ITransitionPlayer;
import android.window.RemoteTransition;
import android.window.TransitionFilter;
import android.window.TransitionInfo;
import android.window.TransitionMetrics;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransactionCallback;
import android.window.WindowOrganizer;

import androidx.annotation.BinderThread;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ExternalInterfaceBinder;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.util.TransitionUtil;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Plays transition animations. Within this player, each transition has a lifecycle.
 * 1. When a transition is directly started or requested, it is added to "pending" state.
 * 2. Once WMCore applies the transition and notifies, the transition moves to "ready" state.
 * 3. When a transition starts animating, it is moved to the "active" state.
 *
 * Basically: --start--> PENDING --onTransitionReady--> READY --play--> ACTIVE --finish--> |
 *                                                            --merge--> MERGED --^
 *
 * The READY and beyond lifecycle is managed per "track". Within a track, all the animations are
 * serialized as described; however, multiple tracks can play simultaneously. This implies that,
 * within a track, only one transition can be animating ("active") at a time.
 *
 * While a transition is animating in a track, transitions dispatched to the track will be queued
 * in the "ready" state for their turn. At the same time, whenever a transition makes it to the
 * head of the "ready" queue, it will attempt to merge to with the "active" transition. If the
 * merge succeeds, it will be moved to the "active" transition's "merged" list and then the next
 * "ready" transition can attempt to merge. Once the "active" transition animation is finished,
 * the next "ready" transition can play.
 *
 * Track assignments are expected to be provided by WMCore and this generally tries to maintain
 * the same assignments. If, however, WMCore decides that a transition conflicts with >1 active
 * track, it will be marked as SYNC. This means that all currently active tracks must be flushed
 * before the SYNC transition can play.
 */
public class Transitions implements RemoteCallable<Transitions>,
        ShellCommandHandler.ShellCommandActionHandler {
    static final String TAG = "ShellTransitions";

    /** Set to {@code true} to enable shell transitions. */
    public static final boolean ENABLE_SHELL_TRANSITIONS =
            SystemProperties.getBoolean("persist.wm.debug.shell_transit", true);
    public static final boolean SHELL_TRANSITIONS_ROTATION = ENABLE_SHELL_TRANSITIONS
            && SystemProperties.getBoolean("persist.wm.debug.shell_transit_rotate", false);

    /** Transition type for exiting PIP via the Shell, via pressing the expand button. */
    public static final int TRANSIT_EXIT_PIP = TRANSIT_FIRST_CUSTOM + 1;

    public static final int TRANSIT_EXIT_PIP_TO_SPLIT =  TRANSIT_FIRST_CUSTOM + 2;

    /** Transition type for removing PIP via the Shell, either via Dismiss bubble or Close. */
    public static final int TRANSIT_REMOVE_PIP = TRANSIT_FIRST_CUSTOM + 3;

    /** Transition type for launching 2 tasks simultaneously. */
    public static final int TRANSIT_SPLIT_SCREEN_PAIR_OPEN = TRANSIT_FIRST_CUSTOM + 4;

    /** Transition type for entering split by opening an app into side-stage. */
    public static final int TRANSIT_SPLIT_SCREEN_OPEN_TO_SIDE = TRANSIT_FIRST_CUSTOM + 5;

    /** Transition type for dismissing split-screen via dragging the divider off the screen. */
    public static final int TRANSIT_SPLIT_DISMISS_SNAP = TRANSIT_FIRST_CUSTOM + 6;

    /** Transition type for dismissing split-screen. */
    public static final int TRANSIT_SPLIT_DISMISS = TRANSIT_FIRST_CUSTOM + 7;

    /** Transition type for freeform to maximize transition. */
    public static final int TRANSIT_MAXIMIZE = WindowManager.TRANSIT_FIRST_CUSTOM + 8;

    /** Transition type for maximize to freeform transition. */
    public static final int TRANSIT_RESTORE_FROM_MAXIMIZE = WindowManager.TRANSIT_FIRST_CUSTOM + 9;

    /** Transition type to freeform in desktop mode. */
    public static final int TRANSIT_ENTER_FREEFORM = WindowManager.TRANSIT_FIRST_CUSTOM + 10;

    /** Transition type to freeform in desktop mode. */
    public static final int TRANSIT_ENTER_DESKTOP_MODE = WindowManager.TRANSIT_FIRST_CUSTOM + 11;

    /** Transition type to fullscreen from desktop mode. */
    public static final int TRANSIT_EXIT_DESKTOP_MODE = WindowManager.TRANSIT_FIRST_CUSTOM + 12;

    /** Transition type to animate back to fullscreen when drag to freeform is cancelled. */
    public static final int TRANSIT_CANCEL_ENTERING_DESKTOP_MODE =
            WindowManager.TRANSIT_FIRST_CUSTOM + 13;

    private final WindowOrganizer mOrganizer;
    private final Context mContext;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;
    private final TransitionPlayerImpl mPlayerImpl;
    private final DefaultTransitionHandler mDefaultTransitionHandler;
    private final RemoteTransitionHandler mRemoteTransitionHandler;
    private final DisplayController mDisplayController;
    private final ShellController mShellController;
    private final ShellTransitionImpl mImpl = new ShellTransitionImpl();
    private final SleepHandler mSleepHandler = new SleepHandler();
    private final Tracer mTracer = new Tracer();
    private boolean mIsRegistered = false;

    /** List of possible handlers. Ordered by specificity (eg. tapped back to front). */
    private final ArrayList<TransitionHandler> mHandlers = new ArrayList<>();

    @Nullable
    private final ShellCommandHandler mShellCommandHandler;

    private final ArrayList<TransitionObserver> mObservers = new ArrayList<>();

    /** List of {@link Runnable} instances to run when the last active transition has finished.  */
    private final ArrayList<Runnable> mRunWhenIdleQueue = new ArrayList<>();

    private float mTransitionAnimationScaleSetting = 1.0f;

    /**
     * How much time we allow for an animation to finish itself on sync. If it takes longer, we
     * will force-finish it (on this end) which may leave it in a bad state but won't hang the
     * device. This needs to be pretty small because it is an allowance for each queued animation,
     * however it can't be too small since there is some potential IPC involved.
     */
    private static final int SYNC_ALLOWANCE_MS = 120;

    /** For testing only. Disables the force-finish timeout on sync. */
    private boolean mDisableForceSync = false;

    private static final class ActiveTransition {
        IBinder mToken;
        TransitionHandler mHandler;
        boolean mAborted;
        TransitionInfo mInfo;
        SurfaceControl.Transaction mStartT;
        SurfaceControl.Transaction mFinishT;

        /** Ordered list of transitions which have been merged into this one. */
        private ArrayList<ActiveTransition> mMerged;

        boolean isSync() {
            return (mInfo.getFlags() & TransitionInfo.FLAG_SYNC) != 0;
        }

        int getTrack() {
            return mInfo != null ? mInfo.getTrack() : -1;
        }

        @Override
        public String toString() {
            if (mInfo != null && mInfo.getDebugId() >= 0) {
                return "(#" + mInfo.getDebugId() + ")" + mToken + "@" + getTrack();
            }
            return mToken.toString() + "@" + getTrack();
        }
    }

    private static class Track {
        /** Keeps track of transitions which are ready to play but still waiting for their turn. */
        final ArrayList<ActiveTransition> mReadyTransitions = new ArrayList<>();

        /** The currently playing transition in this track. */
        ActiveTransition mActiveTransition = null;

        boolean isIdle() {
            return mActiveTransition == null && mReadyTransitions.isEmpty();
        }
    }

    /** Keeps track of transitions which have been started, but aren't ready yet. */
    private final ArrayList<ActiveTransition> mPendingTransitions = new ArrayList<>();

    /**
     * Transitions which are ready to play, but haven't been sent to a track yet because a sync
     * is ongoing.
     */
    private final ArrayList<ActiveTransition> mReadyDuringSync = new ArrayList<>();

    private final ArrayList<Track> mTracks = new ArrayList<>();

    public Transitions(@NonNull Context context,
            @NonNull ShellInit shellInit,
            @NonNull ShellController shellController,
            @NonNull WindowOrganizer organizer,
            @NonNull TransactionPool pool,
            @NonNull DisplayController displayController,
            @NonNull ShellExecutor mainExecutor,
            @NonNull Handler mainHandler,
            @NonNull ShellExecutor animExecutor) {
        this(context, shellInit, shellController, organizer, pool, displayController, mainExecutor,
                mainHandler, animExecutor, null);
    }

    public Transitions(@NonNull Context context,
            @NonNull ShellInit shellInit,
            @NonNull ShellController shellController,
            @NonNull WindowOrganizer organizer,
            @NonNull TransactionPool pool,
            @NonNull DisplayController displayController,
            @NonNull ShellExecutor mainExecutor,
            @NonNull Handler mainHandler,
            @NonNull ShellExecutor animExecutor,
            @Nullable ShellCommandHandler shellCommandHandler) {
        mOrganizer = organizer;
        mContext = context;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
        mDisplayController = displayController;
        mPlayerImpl = new TransitionPlayerImpl();
        mDefaultTransitionHandler = new DefaultTransitionHandler(context, shellInit,
                displayController, pool, mainExecutor, mainHandler, animExecutor);
        mRemoteTransitionHandler = new RemoteTransitionHandler(mMainExecutor);
        mShellController = shellController;
        // The very last handler (0 in the list) should be the default one.
        mHandlers.add(mDefaultTransitionHandler);
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "addHandler: Default");
        // Next lowest priority is remote transitions.
        mHandlers.add(mRemoteTransitionHandler);
        mShellCommandHandler = shellCommandHandler;
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "addHandler: Remote");
        shellInit.addInitCallback(this::onInit, this);
    }

    private void onInit() {
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            mOrganizer.shareTransactionQueue();
        }
        mShellController.addExternalInterface(KEY_EXTRA_SHELL_SHELL_TRANSITIONS,
                this::createExternalInterface, this);

        ContentResolver resolver = mContext.getContentResolver();
        mTransitionAnimationScaleSetting = getTransitionAnimationScaleSetting();
        dispatchAnimScaleSetting(mTransitionAnimationScaleSetting);

        resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false,
                new SettingsObserver());

        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            mIsRegistered = true;
            // Register this transition handler with Core
            try {
                mOrganizer.registerTransitionPlayer(mPlayerImpl);
            } catch (RuntimeException e) {
                mIsRegistered = false;
                throw e;
            }
            // Pre-load the instance.
            TransitionMetrics.getInstance();
        }

        if (mShellCommandHandler != null) {
            mShellCommandHandler.addCommandCallback("transitions", this, this);
        }
    }

    public boolean isRegistered() {
        return mIsRegistered;
    }

    private float getTransitionAnimationScaleSetting() {
        return fixScale(Settings.Global.getFloat(mContext.getContentResolver(),
                Settings.Global.TRANSITION_ANIMATION_SCALE, mContext.getResources().getFloat(
                                R.dimen.config_appTransitionAnimationDurationScaleDefault)));
    }

    public ShellTransitions asRemoteTransitions() {
        return mImpl;
    }

    private ExternalInterfaceBinder createExternalInterface() {
        return new IShellTransitionsImpl(this);
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    private void dispatchAnimScaleSetting(float scale) {
        for (int i = mHandlers.size() - 1; i >= 0; --i) {
            mHandlers.get(i).setAnimScaleSetting(scale);
        }
    }

    /**
     * Adds a handler candidate.
     * @see TransitionHandler
     */
    public void addHandler(@NonNull TransitionHandler handler) {
        if (mHandlers.isEmpty()) {
            throw new RuntimeException("Unexpected handler added prior to initialization, please "
                    + "use ShellInit callbacks to ensure proper ordering");
        }
        mHandlers.add(handler);
        // Set initial scale settings.
        handler.setAnimScaleSetting(mTransitionAnimationScaleSetting);
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "addHandler: %s",
                handler.getClass().getSimpleName());
    }

    public ShellExecutor getMainExecutor() {
        return mMainExecutor;
    }

    public ShellExecutor getAnimExecutor() {
        return mAnimExecutor;
    }

    /** Only use this in tests. This is used to avoid running animations during tests. */
    @VisibleForTesting
    void replaceDefaultHandlerForTest(TransitionHandler handler) {
        mHandlers.set(0, handler);
    }

    /** Register a remote transition to be used when `filter` matches an incoming transition */
    public void registerRemote(@NonNull TransitionFilter filter,
            @NonNull RemoteTransition remoteTransition) {
        mRemoteTransitionHandler.addFiltered(filter, remoteTransition);
    }

    /** Unregisters a remote transition and all associated filters */
    public void unregisterRemote(@NonNull RemoteTransition remoteTransition) {
        mRemoteTransitionHandler.removeFiltered(remoteTransition);
    }

    RemoteTransitionHandler getRemoteTransitionHandler() {
        return mRemoteTransitionHandler;
    }

    /** Registers an observer on the lifecycle of transitions. */
    public void registerObserver(@NonNull TransitionObserver observer) {
        mObservers.add(observer);
    }

    /** Unregisters the observer. */
    public void unregisterObserver(@NonNull TransitionObserver observer) {
        mObservers.remove(observer);
    }

    /** Boosts the process priority of remote animation player. */
    public static void setRunningRemoteTransitionDelegate(IApplicationThread appThread) {
        if (appThread == null) return;
        try {
            ActivityTaskManager.getService().setRunningRemoteTransitionDelegate(appThread);
        } catch (SecurityException e) {
            Log.e(TAG, "Unable to boost animation process. This should only happen"
                    + " during unit tests");
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Runs the given {@code runnable} when the last active transition has finished, or immediately
     * if there are currently no active transitions.
     *
     * <p>This method should be called on the Shell main-thread, where the given {@code runnable}
     * will be executed when the last active transition is finished.
     */
    public void runOnIdle(Runnable runnable) {
        if (isIdle()) {
            runnable.run();
        } else {
            mRunWhenIdleQueue.add(runnable);
        }
    }

    void setDisableForceSyncForTest(boolean disable) {
        mDisableForceSync = disable;
    }

    /**
     * Sets up visibility/alpha/transforms to resemble the starting state of an animation.
     */
    private static void setupStartState(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull SurfaceControl.Transaction finishT) {
        boolean isOpening = isOpeningType(info.getType());
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (change.hasFlags(TransitionInfo.FLAGS_IS_NON_APP_WINDOW)) {
                // Currently system windows are controlled by WindowState, so don't change their
                // surfaces. Otherwise their surfaces could be hidden or cropped unexpectedly.
                // This includes Wallpaper (always z-ordered at bottom) and IME (associated with
                // app), because there may not be a transition associated with their visibility
                // changes, and currently they don't need transition animation.
                continue;
            }
            final SurfaceControl leash = change.getLeash();
            final int mode = info.getChanges().get(i).getMode();

            if (mode == TRANSIT_TO_FRONT
                    && ((change.getStartAbsBounds().height() != change.getEndAbsBounds().height()
                    || change.getStartAbsBounds().width() != change.getEndAbsBounds().width()))) {
                // When the window is moved to front with a different size, make sure the crop is
                // updated to prevent it from using the old crop.
                t.setWindowCrop(leash, change.getEndAbsBounds().width(),
                        change.getEndAbsBounds().height());
            }

            // Don't move anything that isn't independent within its parents
            if (!TransitionInfo.isIndependent(change, info)) {
                if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT || mode == TRANSIT_CHANGE) {
                    t.show(leash);
                    t.setMatrix(leash, 1, 0, 0, 1);
                    t.setAlpha(leash, 1.f);
                    t.setPosition(leash, change.getEndRelOffset().x, change.getEndRelOffset().y);
                }
                continue;
            }

            if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT) {
                t.show(leash);
                t.setMatrix(leash, 1, 0, 0, 1);
                if (isOpening
                        // If this is a transferred starting window, we want it immediately visible.
                        && (change.getFlags() & FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT) == 0) {
                    t.setAlpha(leash, 0.f);
                }
                finishT.show(leash);
            } else if (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK) {
                finishT.hide(leash);
            }
        }
    }

    /**
     * Reparents all participants into a shared parent and orders them based on: the global transit
     * type, their transit mode, and their destination z-order.
     */
    private static void setupAnimHierarchy(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull SurfaceControl.Transaction finishT) {
        final int type = info.getType();
        final boolean isOpening = isOpeningType(type);
        final boolean isClosing = isClosingType(type);
        for (int i = 0; i < info.getRootCount(); ++i) {
            t.show(info.getRoot(i).getLeash());
        }
        final int numChanges = info.getChanges().size();
        // Put animating stuff above this line and put static stuff below it.
        final int zSplitLine = numChanges + 1;
        // changes should be ordered top-to-bottom in z
        for (int i = numChanges - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final SurfaceControl leash = change.getLeash();
            final int mode = change.getMode();

            // Don't reparent anything that isn't independent within its parents
            if (!TransitionInfo.isIndependent(change, info)) {
                continue;
            }

            boolean hasParent = change.getParent() != null;

            final int rootIdx = TransitionUtil.rootIndexFor(change, info);
            if (!hasParent) {
                t.reparent(leash, info.getRoot(rootIdx).getLeash());
                t.setPosition(leash,
                        change.getStartAbsBounds().left - info.getRoot(rootIdx).getOffset().x,
                        change.getStartAbsBounds().top - info.getRoot(rootIdx).getOffset().y);
            }
            final int layer;
            // Put all the OPEN/SHOW on top
            if ((change.getFlags() & FLAG_IS_WALLPAPER) != 0) {
                // Wallpaper is always at the bottom, opening wallpaper on top of closing one.
                if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT) {
                    layer = -zSplitLine + numChanges - i;
                } else {
                    layer = -zSplitLine - i;
                }
            } else if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT) {
                if (isOpening) {
                    // put on top
                    layer = zSplitLine + numChanges - i;
                } else {
                    // put on bottom
                    layer = zSplitLine - i;
                }
            } else if (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK) {
                if (isOpening) {
                    // put on bottom and leave visible
                    layer = zSplitLine - i;
                } else {
                    // put on top
                    layer = zSplitLine + numChanges - i;
                }
            } else { // CHANGE or other
                if (isClosing) {
                    // Put below CLOSE mode.
                    layer = zSplitLine - i;
                } else {
                    // Put above CLOSE mode.
                    layer = zSplitLine + numChanges - i;
                }
            }
            t.setLayer(leash, layer);
        }
    }

    private static int findByToken(ArrayList<ActiveTransition> list, IBinder token) {
        for (int i = list.size() - 1; i >= 0; --i) {
            if (list.get(i).mToken == token) return i;
        }
        return -1;
    }

    /**
     * Look through a transition and see if all non-closing changes are no-animation. If so, no
     * animation should play.
     */
    static boolean isAllNoAnimation(TransitionInfo info) {
        if (isClosingType(info.getType())) {
            // no-animation is only relevant for launching (open) activities.
            return false;
        }
        boolean hasNoAnimation = false;
        final int changeSize = info.getChanges().size();
        for (int i = changeSize - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (isClosingType(change.getMode())) {
                // ignore closing apps since they are a side-effect of the transition and don't
                // animate.
                continue;
            }
            if (change.hasFlags(FLAG_NO_ANIMATION)) {
                hasNoAnimation = true;
            } else {
                // at-least one relevant participant *is* animated, so we need to animate.
                return false;
            }
        }
        return hasNoAnimation;
    }

    /**
     * Check if all changes in this transition are only ordering changes. If so, we won't animate.
     */
    static boolean isAllOrderOnly(TransitionInfo info) {
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            if (!TransitionUtil.isOrderOnly(info.getChanges().get(i))) return false;
        }
        return true;
    }

    private Track getOrCreateTrack(int trackId) {
        while (trackId >= mTracks.size()) {
            mTracks.add(new Track());
        }
        return mTracks.get(trackId);
    }

    @VisibleForTesting
    void onTransitionReady(@NonNull IBinder transitionToken, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull SurfaceControl.Transaction finishT) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "onTransitionReady %s: %s",
                transitionToken, info);
        final int activeIdx = findByToken(mPendingTransitions, transitionToken);
        if (activeIdx < 0) {
            throw new IllegalStateException("Got transitionReady for non-pending transition "
                    + transitionToken + ". expecting one of "
                    + Arrays.toString(mPendingTransitions.stream().map(
                            activeTransition -> activeTransition.mToken).toArray()));
        }
        // Move from pending to ready
        final ActiveTransition active = mPendingTransitions.remove(activeIdx);
        active.mInfo = info;
        active.mStartT = t;
        active.mFinishT = finishT;
        if (activeIdx > 0) {
            Log.i(TAG, "Transition might be ready out-of-order " + activeIdx + " for " + active
                    + ". This is ok if it's on a different track.");
        }
        if (!mReadyDuringSync.isEmpty()) {
            mReadyDuringSync.add(active);
        } else {
            dispatchReady(active);
        }
    }

    /**
     * Returns true if dispatching succeeded, otherwise false. Dispatching can fail if it is
     * blocked by a sync or sleep.
     */
    boolean dispatchReady(ActiveTransition active) {
        final TransitionInfo info = active.mInfo;

        if (info.getType() == TRANSIT_SLEEP || active.isSync()) {
            // Adding to *front*! If we are here, it means that it was pulled off the front
            // so we are just putting it back; or, it is the first one so it doesn't matter.
            mReadyDuringSync.add(0, active);
            boolean hadPreceding = false;
            // Now flush all the tracks.
            for (int i = 0; i < mTracks.size(); ++i) {
                final Track tr = mTracks.get(i);
                if (tr.isIdle()) continue;
                hadPreceding = true;
                // Sleep starts a process of forcing all prior transitions to finish immediately
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                        "Start finish-for-sync track %d", i);
                finishForSync(i, null /* forceFinish */);
            }
            if (hadPreceding) {
                return false;
            }
            // Actually able to process the sleep now, so re-remove it from the queue and continue
            // the normal flow.
            mReadyDuringSync.remove(active);
        }

        final Track track = getOrCreateTrack(info.getTrack());
        track.mReadyTransitions.add(active);

        for (int i = 0; i < mObservers.size(); ++i) {
            mObservers.get(i).onTransitionReady(
                    active.mToken, info, active.mStartT, active.mFinishT);
        }

        if (info.getRootCount() == 0 && !TransitionUtil.alwaysReportToKeyguard(info)) {
            // No root-leashes implies that the transition is empty/no-op, so just do
            // housekeeping and return.
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "No transition roots in %s so"
                    + " abort", active);
            onAbort(active);
            return true;
        }

        final int changeSize = info.getChanges().size();
        boolean taskChange = false;
        boolean transferStartingWindow = false;
        boolean allOccluded = changeSize > 0;
        for (int i = changeSize - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            taskChange |= change.getTaskInfo() != null;
            transferStartingWindow |= change.hasFlags(FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT);
            if (!change.hasFlags(FLAG_IS_OCCLUDED)) {
                allOccluded = false;
            }
        }
        // There does not need animation when:
        // A. Transfer starting window. Apply transfer starting window directly if there is no other
        // task change. Since this is an activity->activity situation, we can detect it by selecting
        // transitions with only 2 changes where neither are tasks and one is a starting-window
        // recipient.
        if (!taskChange && transferStartingWindow && changeSize == 2
                // B. It's visibility change if the TRANSIT_TO_BACK/TO_FRONT happened when all
                // changes are underneath another change.
                || ((info.getType() == TRANSIT_TO_BACK || info.getType() == TRANSIT_TO_FRONT)
                && allOccluded)) {
            // Treat this as an abort since we are bypassing any merge logic and effectively
            // finishing immediately.
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                    "Non-visible anim so abort: %s", active);
            onAbort(active);
            return true;
        }

        setupStartState(active.mInfo, active.mStartT, active.mFinishT);

        if (track.mReadyTransitions.size() > 1) {
            // There are already transitions waiting in the queue, so just return.
            return true;
        }
        processReadyQueue(track);
        return true;
    }

    private boolean areTracksIdle() {
        for (int i = 0; i < mTracks.size(); ++i) {
            if (!mTracks.get(i).isIdle()) return false;
        }
        return true;
    }

    private boolean isAnimating() {
        return !mReadyDuringSync.isEmpty() || !areTracksIdle();
    }

    private boolean isIdle() {
        return mPendingTransitions.isEmpty() && !isAnimating();
    }

    void processReadyQueue(Track track) {
        if (track.mReadyTransitions.isEmpty()) {
            if (track.mActiveTransition == null) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Track %d became idle",
                        mTracks.indexOf(track));
                if (areTracksIdle()) {
                    if (!mReadyDuringSync.isEmpty()) {
                        // Dispatch everything unless we hit another sync
                        while (!mReadyDuringSync.isEmpty()) {
                            ActiveTransition next = mReadyDuringSync.remove(0);
                            boolean success = dispatchReady(next);
                            // Hit a sync or sleep, so stop dispatching.
                            if (!success) break;
                        }
                    } else if (mPendingTransitions.isEmpty()) {
                        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "All active transition "
                                + "animations finished");
                        // Run all runnables from the run-when-idle queue.
                        for (int i = 0; i < mRunWhenIdleQueue.size(); i++) {
                            mRunWhenIdleQueue.get(i).run();
                        }
                        mRunWhenIdleQueue.clear();
                    }
                }
            }
            return;
        }
        final ActiveTransition ready = track.mReadyTransitions.get(0);
        if (track.mActiveTransition == null) {
            // The normal case, just play it.
            track.mReadyTransitions.remove(0);
            track.mActiveTransition = ready;
            if (ready.mAborted) {
                // finish now since there's nothing to animate. Calls back into processReadyQueue
                onFinish(ready, null, null);
                return;
            }
            playTransition(ready);
            // Attempt to merge any more queued-up transitions.
            processReadyQueue(track);
            return;
        }
        // An existing animation is playing, so see if we can merge.
        final ActiveTransition playing = track.mActiveTransition;
        if (ready.mAborted) {
            // record as merged since it is no-op. Calls back into processReadyQueue
            onMerged(playing, ready);
            return;
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition %s ready while"
                + " %s is still animating. Notify the animating transition"
                + " in case they can be merged", ready, playing);
        mTracer.logMergeRequested(ready.mInfo.getDebugId(), playing.mInfo.getDebugId());
        playing.mHandler.mergeAnimation(ready.mToken, ready.mInfo, ready.mStartT,
                playing.mToken, (wct, cb) -> onMerged(playing, ready));
    }

    private void onMerged(@NonNull ActiveTransition playing, @NonNull ActiveTransition merged) {
        if (playing.getTrack() != merged.getTrack()) {
            throw new IllegalStateException("Can't merge across tracks: " + merged + " into "
                    + playing);
        }
        final Track track = mTracks.get(playing.getTrack());
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition was merged: %s into %s",
                merged, playing);
        int readyIdx = 0;
        if (track.mReadyTransitions.isEmpty() || track.mReadyTransitions.get(0) != merged) {
            Log.e(TAG, "Merged transition out-of-order? " + merged);
            readyIdx = track.mReadyTransitions.indexOf(merged);
            if (readyIdx < 0) {
                Log.e(TAG, "Merged a transition that is no-longer queued? " + merged);
                return;
            }
        }
        track.mReadyTransitions.remove(readyIdx);
        if (playing.mMerged == null) {
            playing.mMerged = new ArrayList<>();
        }
        playing.mMerged.add(merged);
        // if it was aborted, then onConsumed has already been reported.
        if (merged.mHandler != null && !merged.mAborted) {
            merged.mHandler.onTransitionConsumed(merged.mToken, false /* abort */, merged.mFinishT);
        }
        for (int i = 0; i < mObservers.size(); ++i) {
            mObservers.get(i).onTransitionMerged(merged.mToken, playing.mToken);
        }
        mTracer.logMerged(merged.mInfo.getDebugId(), playing.mInfo.getDebugId());
        // See if we should merge another transition.
        processReadyQueue(track);
    }

    private void playTransition(@NonNull ActiveTransition active) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Playing animation for %s", active);
        for (int i = 0; i < mObservers.size(); ++i) {
            mObservers.get(i).onTransitionStarting(active.mToken);
        }

        setupAnimHierarchy(active.mInfo, active.mStartT, active.mFinishT);

        // If a handler already chose to run this animation, try delegating to it first.
        if (active.mHandler != null) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " try firstHandler %s",
                    active.mHandler);
            boolean consumed = active.mHandler.startAnimation(active.mToken, active.mInfo,
                    active.mStartT, active.mFinishT, (wct, cb) -> onFinish(active, wct, cb));
            if (consumed) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " animated by firstHandler");
                mTracer.logDispatched(active.mInfo.getDebugId(), active.mHandler);
                return;
            }
        }
        // Otherwise give every other handler a chance
        active.mHandler = dispatchTransition(active.mToken, active.mInfo, active.mStartT,
                active.mFinishT, (wct, cb) -> onFinish(active, wct, cb), active.mHandler);
    }

    /**
     * Gives every handler (in order) a chance to animate until one consumes the transition.
     * @return the handler which consumed the transition.
     */
    TransitionHandler dispatchTransition(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startT, @NonNull SurfaceControl.Transaction finishT,
            @NonNull TransitionFinishCallback finishCB, @Nullable TransitionHandler skip) {
        for (int i = mHandlers.size() - 1; i >= 0; --i) {
            if (mHandlers.get(i) == skip) continue;
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " try handler %s",
                    mHandlers.get(i));
            boolean consumed = mHandlers.get(i).startAnimation(transition, info, startT, finishT,
                    finishCB);
            if (consumed) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " animated by %s",
                        mHandlers.get(i));
                mTracer.logDispatched(info.getDebugId(), mHandlers.get(i));
                return mHandlers.get(i);
            }
        }
        throw new IllegalStateException(
                "This shouldn't happen, maybe the default handler is broken.");
    }

    /**
     * Gives every handler (in order) a chance to handle request until one consumes the transition.
     * @return the WindowContainerTransaction given by the handler which consumed the transition.
     */
    public Pair<TransitionHandler, WindowContainerTransaction> dispatchRequest(
            @NonNull IBinder transition, @NonNull TransitionRequestInfo request,
            @Nullable TransitionHandler skip) {
        for (int i = mHandlers.size() - 1; i >= 0; --i) {
            if (mHandlers.get(i) == skip) continue;
            WindowContainerTransaction wct = mHandlers.get(i).handleRequest(transition, request);
            if (wct != null) {
                return new Pair<>(mHandlers.get(i), wct);
            }
        }
        return null;
    }

    /** Aborts a transition. This will still queue it up to maintain order. */
    private void onAbort(ActiveTransition transition) {
        final Track track = mTracks.get(transition.getTrack());
        // apply immediately since they may be "parallel" operations: We currently we use abort for
        // thing which are independent to other transitions (like starting-window transfer).
        transition.mStartT.apply();
        transition.mFinishT.apply();
        transition.mAborted = true;

        mTracer.logAborted(transition.mInfo.getDebugId());

        if (transition.mHandler != null) {
            // Notifies to clean-up the aborted transition.
            transition.mHandler.onTransitionConsumed(
                    transition.mToken, true /* aborted */, null /* finishTransaction */);
        }

        releaseSurfaces(transition.mInfo);

        // This still went into the queue (to maintain the correct finish ordering).
        if (track.mReadyTransitions.size() > 1) {
            // There are already transitions waiting in the queue, so just return.
            return;
        }
        processReadyQueue(track);
    }

    /**
     * Releases an info's animation-surfaces. These don't need to persist and we need to release
     * them asap so that SF can free memory sooner.
     */
    private void releaseSurfaces(@Nullable TransitionInfo info) {
        if (info == null) return;
        info.releaseAnimSurfaces();
    }

    private void onFinish(ActiveTransition active,
            @Nullable WindowContainerTransaction wct,
            @Nullable WindowContainerTransactionCallback wctCB) {
        final Track track = mTracks.get(active.getTrack());
        if (track.mActiveTransition != active) {
            Log.e(TAG, "Trying to finish a non-running transition. Either remote crashed or "
                    + " a handler didn't properly deal with a merge. " + active,
                    new RuntimeException());
            return;
        }
        track.mActiveTransition = null;

        for (int i = 0; i < mObservers.size(); ++i) {
            mObservers.get(i).onTransitionFinished(active.mToken, active.mAborted);
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition animation finished "
                + "(aborted=%b), notifying core %s", active.mAborted, active);
        if (active.mStartT != null) {
            // Applied by now, so clear immediately to remove any references. Do not set to null
            // yet, though, since nullness is used later to disambiguate malformed transitions.
            active.mStartT.clear();
        }
        // Merge all associated transactions together
        SurfaceControl.Transaction fullFinish = active.mFinishT;
        if (active.mMerged != null) {
            for (int iM = 0; iM < active.mMerged.size(); ++iM) {
                final ActiveTransition toMerge = active.mMerged.get(iM);
                // Include start. It will be a no-op if it was already applied. Otherwise, we need
                // it to maintain consistent state.
                if (toMerge.mStartT != null) {
                    if (fullFinish == null) {
                        fullFinish = toMerge.mStartT;
                    } else {
                        fullFinish.merge(toMerge.mStartT);
                    }
                }
                if (toMerge.mFinishT != null) {
                    if (fullFinish == null) {
                        fullFinish = toMerge.mFinishT;
                    } else {
                        fullFinish.merge(toMerge.mFinishT);
                    }
                }
            }
        }
        if (fullFinish != null) {
            fullFinish.apply();
        }
        // Now perform all the finish callbacks (starting with the playing one and then all the
        // transitions merged into it).
        releaseSurfaces(active.mInfo);
        mOrganizer.finishTransition(active.mToken, wct, wctCB);
        if (active.mMerged != null) {
            for (int iM = 0; iM < active.mMerged.size(); ++iM) {
                ActiveTransition merged = active.mMerged.get(iM);
                mOrganizer.finishTransition(merged.mToken, null /* wct */, null /* wctCB */);
                releaseSurfaces(merged.mInfo);
            }
            active.mMerged.clear();
        }

        // Now that this is done, check the ready queue for more work.
        processReadyQueue(track);
    }

    private boolean isTransitionKnown(IBinder token) {
        for (int i = 0; i < mPendingTransitions.size(); ++i) {
            if (mPendingTransitions.get(i).mToken == token) return true;
        }
        for (int t = 0; t < mTracks.size(); ++t) {
            final Track tr = mTracks.get(t);
            for (int i = 0; i < tr.mReadyTransitions.size(); ++i) {
                if (tr.mReadyTransitions.get(i).mToken == token) return true;
            }
            final ActiveTransition active = tr.mActiveTransition;
            if (active == null) continue;
            if (active.mToken == token) return true;
            if (active.mMerged == null) continue;
            for (int m = 0; m < active.mMerged.size(); ++m) {
                if (active.mMerged.get(m).mToken == token) return true;
            }
        }
        return false;
    }

    void requestStartTransition(@NonNull IBinder transitionToken,
            @Nullable TransitionRequestInfo request) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition requested: %s %s",
                transitionToken, request);
        if (isTransitionKnown(transitionToken)) {
            throw new RuntimeException("Transition already started " + transitionToken);
        }
        final ActiveTransition active = new ActiveTransition();
        WindowContainerTransaction wct = null;

        // If we have sleep, we use a special handler and we try to finish everything ASAP.
        if (request.getType() == TRANSIT_SLEEP) {
            mSleepHandler.handleRequest(transitionToken, request);
            active.mHandler = mSleepHandler;
        } else {
            for (int i = mHandlers.size() - 1; i >= 0; --i) {
                wct = mHandlers.get(i).handleRequest(transitionToken, request);
                if (wct != null) {
                    active.mHandler = mHandlers.get(i);
                    break;
                }
            }
            if (request.getDisplayChange() != null) {
                TransitionRequestInfo.DisplayChange change = request.getDisplayChange();
                if (change.getEndRotation() != change.getStartRotation()) {
                    // Is a rotation, so dispatch to all displayChange listeners
                    if (wct == null) {
                        wct = new WindowContainerTransaction();
                    }
                    mDisplayController.getChangeController().dispatchOnDisplayChange(wct,
                            change.getDisplayId(), change.getStartRotation(),
                            change.getEndRotation(), null /* newDisplayAreaInfo */);
                }
            }
        }
        mOrganizer.startTransition(transitionToken, wct != null && wct.isEmpty() ? null : wct);
        active.mToken = transitionToken;
        // Currently, WMCore only does one transition at a time. If it makes a requestStart, it
        // is already collecting that transition on core-side, so it will be the next one to
        // become ready. There may already be pending transitions added as part of direct
        // `startNewTransition` but if we have a request now, it means WM created the request
        // transition before it acknowledged any of the pending `startNew` transitions. So, insert
        // it at the front.
        mPendingTransitions.add(0, active);
    }

    /** Start a new transition directly. */
    public IBinder startTransition(@WindowManager.TransitionType int type,
            @NonNull WindowContainerTransaction wct, @Nullable TransitionHandler handler) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Directly starting a new transition "
                + "type=%d wct=%s handler=%s", type, wct, handler);
        final ActiveTransition active = new ActiveTransition();
        active.mHandler = handler;
        active.mToken = mOrganizer.startNewTransition(type, wct);
        mPendingTransitions.add(active);
        return active.mToken;
    }

    /**
     * Finish running animations (almost) immediately when a SLEEP transition comes in. We use this
     * as both a way to reduce unnecessary work (animations not visible while screen off) and as a
     * failsafe to unblock "stuck" animations (in particular remote animations).
     *
     * This works by "merging" the sleep transition into the currently-playing transition (even if
     * its out-of-order) -- turning SLEEP into a signal. If the playing transition doesn't finish
     * within `SYNC_ALLOWANCE_MS` from this merge attempt, this will then finish it directly (and
     * send an abort/consumed message).
     *
     * This is then repeated until there are no more pending sleep transitions.
     *
     * @param forceFinish When non-null, this is the transition that we last sent the SLEEP merge
     *                    signal to -- so it will be force-finished if it's still running.
     */
    private void finishForSync(int trackIdx, @Nullable ActiveTransition forceFinish) {
        final Track track = mTracks.get(trackIdx);
        if (forceFinish != null) {
            final Track trk = mTracks.get(forceFinish.getTrack());
            if (trk != track) {
                Log.e(TAG, "finishForSleep: mismatched Tracks between forceFinish and logic "
                        + forceFinish.getTrack() + " vs " + trackIdx);
            }
            if (trk.mActiveTransition == forceFinish) {
                Log.e(TAG, "Forcing transition to finish due to sync timeout: " + forceFinish);
                forceFinish.mAborted = true;
                // Last notify of it being consumed. Note: mHandler should never be null,
                // but check just to be safe.
                if (forceFinish.mHandler != null) {
                    forceFinish.mHandler.onTransitionConsumed(
                            forceFinish.mToken, true /* aborted */, null /* finishTransaction */);
                }
                onFinish(forceFinish, null, null);
            }
        }
        if (track.isIdle() || mReadyDuringSync.isEmpty()) {
            // Done finishing things.
            return;
        }
        final SurfaceControl.Transaction dummyT = new SurfaceControl.Transaction();
        final TransitionInfo dummyInfo = new TransitionInfo(TRANSIT_SLEEP, 0 /* flags */);
        while (track.mActiveTransition != null && !mReadyDuringSync.isEmpty()) {
            final ActiveTransition playing = track.mActiveTransition;
            final ActiveTransition nextSync = mReadyDuringSync.get(0);
            if (!nextSync.isSync()) {
                Log.e(TAG, "Somehow blocked on a non-sync transition? " + nextSync);
            }
            // Attempt to merge a SLEEP info to signal that the playing transition needs to
            // fast-forward.
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " Attempt to merge sync %s"
                    + " into %s via a SLEEP proxy", nextSync, playing);
            playing.mHandler.mergeAnimation(nextSync.mToken, dummyInfo, dummyT,
                    playing.mToken, (wct, cb) -> {});
            // it's possible to complete immediately. If that happens, just repeat the signal
            // loop until we either finish everything or start playing an animation that isn't
            // finishing immediately.
            if (track.mActiveTransition == playing) {
                if (!mDisableForceSync) {
                    // Give it a short amount of time to process it before forcing.
                    mMainExecutor.executeDelayed(() -> finishForSync(trackIdx, playing),
                            SYNC_ALLOWANCE_MS);
                }
                break;
            }
        }
    }

    /**
     * Interface for a callback that must be called after a TransitionHandler finishes playing an
     * animation.
     */
    public interface TransitionFinishCallback {
        /**
         * This must be called on the main thread when a transition finishes playing an animation.
         * The transition must not touch the surfaces after this has been called.
         *
         * @param wct A WindowContainerTransaction to run along with the transition clean-up.
         * @param wctCB A sync callback that will be run when the transition clean-up is done and
         *              wct has been applied.
         */
        void onTransitionFinished(@Nullable WindowContainerTransaction wct,
                @Nullable WindowContainerTransactionCallback wctCB);
    }

    /**
     * Interface for something which can handle a subset of transitions.
     */
    public interface TransitionHandler {
        /**
         * Starts a transition animation. This is always called if handleRequest returned non-null
         * for a particular transition. Otherwise, it is only called if no other handler before
         * it handled the transition.
         * @param startTransaction the transaction given to the handler to be applied before the
         *                         transition animation. Note the handler is expected to call on
         *                         {@link SurfaceControl.Transaction#apply()} for startTransaction.
         * @param finishTransaction the transaction given to the handler to be applied after the
         *                       transition animation. Unlike startTransaction, the handler is NOT
         *                       expected to apply this transaction. The Transition system will
         *                       apply it when finishCallback is called.
         * @param finishCallback Call this when finished. This MUST be called on main thread.
         * @return true if transition was handled, false if not (falls-back to default).
         */
        boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction,
                @NonNull TransitionFinishCallback finishCallback);

        /**
         * Attempts to merge a different transition's animation into an animation that this handler
         * is currently playing. If a merge is not possible/supported, this should be a no-op.
         *
         * This gets called if another transition becomes ready while this handler is still playing
         * an animation. This is called regardless of whether this handler claims to support that
         * particular transition or not.
         *
         * When this happens, there are 2 options:
         *  1. Do nothing. This effectively rejects the merge request. This is the "safest" option.
         *  2. Merge the incoming transition into this one. The implementation is up to this
         *     handler. To indicate that this handler has "consumed" the merge transition, it
         *     must call the finishCallback immediately, or at-least before the original
         *     transition's finishCallback is called.
         *
         * @param transition This is the transition that wants to be merged.
         * @param info Information about what is changing in the transition.
         * @param t Contains surface changes that resulted from the transition.
         * @param mergeTarget This is the transition that we are attempting to merge with (ie. the
         *                    one this handler is currently already animating).
         * @param finishCallback Call this if merged. This MUST be called on main thread.
         */
        default void mergeAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction t, @NonNull IBinder mergeTarget,
                @NonNull TransitionFinishCallback finishCallback) { }

        /**
         * Potentially handles a startTransition request.
         *
         * @param transition The transition whose start is being requested.
         * @param request Information about what is requested.
         * @return WCT to apply with transition-start or null. If a WCT is returned here, this
         *         handler will be the first in line to animate.
         */
        @Nullable
        WindowContainerTransaction handleRequest(@NonNull IBinder transition,
                @NonNull TransitionRequestInfo request);

        /**
         * Called when a transition which was already "claimed" by this handler has been merged
         * into another animation or has been aborted. Gives this handler a chance to clean-up any
         * expectations.
         *
         * @param transition The transition been consumed.
         * @param aborted Whether the transition is aborted or not.
         * @param finishTransaction The transaction to be applied after the transition animated.
         */
        default void onTransitionConsumed(@NonNull IBinder transition, boolean aborted,
                @Nullable SurfaceControl.Transaction finishTransaction) { }

        /**
         * Sets transition animation scale settings value to handler.
         *
         * @param scale The setting value of transition animation scale.
         */
        default void setAnimScaleSetting(float scale) {}
    }

    /**
     * Interface for something that needs to know the lifecycle of some transitions, but never
     * handles any transition by itself.
     */
    public interface TransitionObserver {
        /**
         * Called when the transition is ready to play. It may later be merged into other
         * transitions. Note this doesn't mean this transition will be played anytime soon.
         *
         * @param transition the unique token of this transition
         * @param startTransaction the transaction given to the handler to be applied before the
         *                         transition animation. This will be applied when the transition
         *                         handler that handles this transition starts the transition.
         * @param finishTransaction the transaction given to the handler to be applied after the
         *                          transition animation. The Transition system will apply it when
         *                          finishCallback is called by the transition handler.
         */
        void onTransitionReady(@NonNull IBinder transition, @NonNull TransitionInfo info,
                @NonNull SurfaceControl.Transaction startTransaction,
                @NonNull SurfaceControl.Transaction finishTransaction);

        /**
         * Called when the transition is starting to play. It isn't called for merged transitions.
         *
         * @param transition the unique token of this transition
         */
        void onTransitionStarting(@NonNull IBinder transition);

        /**
         * Called when a transition is merged into another transition. There won't be any following
         * lifecycle calls for the merged transition.
         *
         * @param merged the unique token of the transition that's merged to another one
         * @param playing the unique token of the transition that accepts the merge
         */
        void onTransitionMerged(@NonNull IBinder merged, @NonNull IBinder playing);

        /**
         * Called when the transition is finished. This isn't called for merged transitions.
         *
         * @param transition the unique token of this transition
         * @param aborted {@code true} if this transition is aborted; {@code false} otherwise.
         */
        void onTransitionFinished(@NonNull IBinder transition, boolean aborted);
    }

    @BinderThread
    private class TransitionPlayerImpl extends ITransitionPlayer.Stub {
        @Override
        public void onTransitionReady(IBinder iBinder, TransitionInfo transitionInfo,
                SurfaceControl.Transaction t, SurfaceControl.Transaction finishT)
                throws RemoteException {
            mMainExecutor.execute(() -> Transitions.this.onTransitionReady(
                    iBinder, transitionInfo, t, finishT));
        }

        @Override
        public void requestStartTransition(IBinder iBinder,
                TransitionRequestInfo request) throws RemoteException {
            mMainExecutor.execute(() -> Transitions.this.requestStartTransition(iBinder, request));
        }
    }

    /**
     * The interface for calls from outside the Shell, within the host process.
     */
    @ExternalThread
    private class ShellTransitionImpl implements ShellTransitions {
        @Override
        public void registerRemote(@NonNull TransitionFilter filter,
                @NonNull RemoteTransition remoteTransition) {
            mMainExecutor.execute(() -> {
                mRemoteTransitionHandler.addFiltered(filter, remoteTransition);
            });
        }

        @Override
        public void unregisterRemote(@NonNull RemoteTransition remoteTransition) {
            mMainExecutor.execute(() -> {
                mRemoteTransitionHandler.removeFiltered(remoteTransition);
            });
        }
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class IShellTransitionsImpl extends IShellTransitions.Stub
            implements ExternalInterfaceBinder {
        private Transitions mTransitions;

        IShellTransitionsImpl(Transitions transitions) {
            mTransitions = transitions;
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        @Override
        public void invalidate() {
            mTransitions = null;
        }

        @Override
        public void registerRemote(@NonNull TransitionFilter filter,
                @NonNull RemoteTransition remoteTransition) {
            executeRemoteCallWithTaskPermission(mTransitions, "registerRemote",
                    (transitions) -> {
                        transitions.mRemoteTransitionHandler.addFiltered(filter, remoteTransition);
                    });
        }

        @Override
        public void unregisterRemote(@NonNull RemoteTransition remoteTransition) {
            executeRemoteCallWithTaskPermission(mTransitions, "unregisterRemote",
                    (transitions) -> {
                        transitions.mRemoteTransitionHandler.removeFiltered(remoteTransition);
                    });
        }

        @Override
        public IBinder getShellApplyToken() {
            return SurfaceControl.Transaction.getDefaultApplyToken();
        }
    }

    private class SettingsObserver extends ContentObserver {

        SettingsObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            mTransitionAnimationScaleSetting = getTransitionAnimationScaleSetting();

            mMainExecutor.execute(() -> dispatchAnimScaleSetting(mTransitionAnimationScaleSetting));
        }
    }


    @Override
    public boolean onShellCommand(String[] args, PrintWriter pw) {
        switch (args[0]) {
            case "tracing": {
                mTracer.onShellCommand(Arrays.copyOfRange(args, 1, args.length), pw);
                return true;
            }
            default: {
                pw.println("Invalid command: " + args[0]);
                printShellCommandHelp(pw, "");
                return false;
            }
        }
    }

    @Override
    public void printShellCommandHelp(PrintWriter pw, String prefix) {
        pw.println(prefix + "tracing");
        mTracer.printShellCommandHelp(pw, prefix + "  ");
    }
}
