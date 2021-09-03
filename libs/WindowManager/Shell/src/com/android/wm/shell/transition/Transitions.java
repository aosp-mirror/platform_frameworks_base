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
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;
import static android.window.TransitionInfo.FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ITransitionPlayer;
import android.window.RemoteTransition;
import android.window.TransitionFilter;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransactionCallback;
import android.window.WindowOrganizer;

import androidx.annotation.BinderThread;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;
import java.util.Arrays;

/** Plays transition animations */
public class Transitions implements RemoteCallable<Transitions> {
    static final String TAG = "ShellTransitions";

    /** Set to {@code true} to enable shell transitions. */
    public static final boolean ENABLE_SHELL_TRANSITIONS =
            SystemProperties.getBoolean("persist.debug.shell_transit", false);

    /** Transition type for dismissing split-screen via dragging the divider off the screen. */
    public static final int TRANSIT_SPLIT_DISMISS_SNAP = TRANSIT_FIRST_CUSTOM + 1;

    /** Transition type for launching 2 tasks simultaneously. */
    public static final int TRANSIT_SPLIT_SCREEN_PAIR_OPEN = TRANSIT_FIRST_CUSTOM + 2;

    /** Transition type for exiting PIP via the Shell, via pressing the expand button. */
    public static final int TRANSIT_EXIT_PIP = TRANSIT_FIRST_CUSTOM + 3;

    /** Transition type for removing PIP via the Shell, either via Dismiss bubble or Close. */
    public static final int TRANSIT_REMOVE_PIP = TRANSIT_FIRST_CUSTOM + 4;

    /** Transition type for entering split by opening an app into side-stage. */
    public static final int TRANSIT_SPLIT_SCREEN_OPEN_TO_SIDE = TRANSIT_FIRST_CUSTOM + 5;

    private final WindowOrganizer mOrganizer;
    private final Context mContext;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;
    private final TransitionPlayerImpl mPlayerImpl;
    private final RemoteTransitionHandler mRemoteTransitionHandler;
    private final ShellTransitionImpl mImpl = new ShellTransitionImpl();

    /** List of possible handlers. Ordered by specificity (eg. tapped back to front). */
    private final ArrayList<TransitionHandler> mHandlers = new ArrayList<>();

    private float mTransitionAnimationScaleSetting = 1.0f;

    private static final class ActiveTransition {
        IBinder mToken;
        TransitionHandler mHandler;
        boolean mMerged;
        boolean mAborted;
        TransitionInfo mInfo;
        SurfaceControl.Transaction mStartT;
        SurfaceControl.Transaction mFinishT;
    }

    /** Keeps track of currently playing transitions in the order of receipt. */
    private final ArrayList<ActiveTransition> mActiveTransitions = new ArrayList<>();

    public Transitions(@NonNull WindowOrganizer organizer, @NonNull TransactionPool pool,
            @NonNull DisplayController displayController, @NonNull Context context,
            @NonNull ShellExecutor mainExecutor, @NonNull ShellExecutor animExecutor) {
        mOrganizer = organizer;
        mContext = context;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
        mPlayerImpl = new TransitionPlayerImpl();
        // The very last handler (0 in the list) should be the default one.
        mHandlers.add(new DefaultTransitionHandler(displayController, pool, context, mainExecutor,
                animExecutor));
        // Next lowest priority is remote transitions.
        mRemoteTransitionHandler = new RemoteTransitionHandler(mainExecutor);
        mHandlers.add(mRemoteTransitionHandler);

        ContentResolver resolver = context.getContentResolver();
        mTransitionAnimationScaleSetting = Settings.Global.getFloat(resolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                context.getResources().getFloat(
                        R.dimen.config_appTransitionAnimationDurationScaleDefault));
        dispatchAnimScaleSetting(mTransitionAnimationScaleSetting);

        resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE), false,
                new SettingsObserver());
    }

    private Transitions() {
        mOrganizer = null;
        mContext = null;
        mMainExecutor = null;
        mAnimExecutor = null;
        mPlayerImpl = null;
        mRemoteTransitionHandler = null;
    }

    public ShellTransitions asRemoteTransitions() {
        return mImpl;
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

    /** Create an empty/non-registering transitions object for system-ui tests. */
    @VisibleForTesting
    public static ShellTransitions createEmptyForTesting() {
        return new ShellTransitions() {
            @Override
            public void registerRemote(@androidx.annotation.NonNull TransitionFilter filter,
                    @androidx.annotation.NonNull RemoteTransition remoteTransition) {
                // Do nothing
            }

            @Override
            public void unregisterRemote(
                    @androidx.annotation.NonNull RemoteTransition remoteTransition) {
                // Do nothing
            }
        };
    }

    /** Register this transition handler with Core */
    public void register(ShellTaskOrganizer taskOrganizer) {
        if (mPlayerImpl == null) return;
        taskOrganizer.registerTransitionPlayer(mPlayerImpl);
    }

    /**
     * Adds a handler candidate.
     * @see TransitionHandler
     */
    public void addHandler(@NonNull TransitionHandler handler) {
        mHandlers.add(handler);
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

    /** @return true if the transition was triggered by opening something vs closing something */
    public static boolean isOpeningType(@WindowManager.TransitionType int type) {
        return type == TRANSIT_OPEN
                || type == TRANSIT_TO_FRONT
                || type == TRANSIT_KEYGUARD_GOING_AWAY;
    }

    /** @return true if the transition was triggered by closing something vs opening something */
    public static boolean isClosingType(@WindowManager.TransitionType int type) {
        return type == TRANSIT_CLOSE || type == TRANSIT_TO_BACK;
    }

    /**
     * Sets up visibility/alpha/transforms to resemble the starting state of an animation.
     */
    private static void setupStartState(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull SurfaceControl.Transaction finishT) {
        boolean isOpening = isOpeningType(info.getType());
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final SurfaceControl leash = change.getLeash();
            final int mode = info.getChanges().get(i).getMode();

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
                    // fix alpha in finish transaction in case the animator itself no-ops.
                    finishT.setAlpha(leash, 1.f);
                }
            } else if (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK) {
                // Wallpaper is a bit of an anomaly: it's visibility is tied to other WindowStates.
                // As a result, we actually can't hide it's WindowToken because there may not be a
                // transition associated with it becoming visible again. Fortunately, since it is
                // always z-ordered to the back, we don't have to worry about it flickering to the
                // front during reparenting, so the hide here isn't necessary for it.
                if ((change.getFlags() & FLAG_IS_WALLPAPER) == 0) {
                    finishT.hide(leash);
                }
            }
        }
    }

    /**
     * Reparents all participants into a shared parent and orders them based on: the global transit
     * type, their transit mode, and their destination z-order.
     */
    private static void setupAnimHierarchy(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull SurfaceControl.Transaction finishT) {
        boolean isOpening = isOpeningType(info.getType());
        if (info.getRootLeash().isValid()) {
            t.show(info.getRootLeash());
        }
        // Put animating stuff above this line and put static stuff below it.
        int zSplitLine = info.getChanges().size();
        // changes should be ordered top-to-bottom in z
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final SurfaceControl leash = change.getLeash();
            final int mode = info.getChanges().get(i).getMode();

            // Don't reparent anything that isn't independent within its parents
            if (!TransitionInfo.isIndependent(change, info)) {
                continue;
            }

            boolean hasParent = change.getParent() != null;

            if (!hasParent) {
                t.reparent(leash, info.getRootLeash());
                t.setPosition(leash, change.getStartAbsBounds().left - info.getRootOffset().x,
                        change.getStartAbsBounds().top - info.getRootOffset().y);
            }
            // Put all the OPEN/SHOW on top
            if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT) {
                if (isOpening) {
                    // put on top
                    t.setLayer(leash, zSplitLine + info.getChanges().size() - i);
                } else {
                    // put on bottom
                    t.setLayer(leash, zSplitLine - i);
                }
            } else if (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK) {
                if (isOpening) {
                    // put on bottom and leave visible
                    t.setLayer(leash, zSplitLine - i);
                } else {
                    // put on top
                    t.setLayer(leash, zSplitLine + info.getChanges().size() - i);
                }
            } else { // CHANGE or other
                t.setLayer(leash, zSplitLine + info.getChanges().size() - i);
            }
        }
    }

    private int findActiveTransition(IBinder token) {
        for (int i = mActiveTransitions.size() - 1; i >= 0; --i) {
            if (mActiveTransitions.get(i).mToken == token) return i;
        }
        return -1;
    }

    @VisibleForTesting
    void onTransitionReady(@NonNull IBinder transitionToken, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull SurfaceControl.Transaction finishT) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "onTransitionReady %s: %s",
                transitionToken, info);
        final int activeIdx = findActiveTransition(transitionToken);
        if (activeIdx < 0) {
            throw new IllegalStateException("Got transitionReady for non-active transition "
                    + transitionToken + ". expecting one of "
                    + Arrays.toString(mActiveTransitions.stream().map(
                            activeTransition -> activeTransition.mToken).toArray()));
        }
        if (!info.getRootLeash().isValid()) {
            // Invalid root-leash implies that the transition is empty/no-op, so just do
            // housekeeping and return.
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Invalid root leash (%s): %s",
                    transitionToken, info);
            t.apply();
            onAbort(transitionToken);
            return;
        }

        final ActiveTransition active = mActiveTransitions.get(activeIdx);
        active.mInfo = info;
        active.mStartT = t;
        active.mFinishT = finishT;
        setupStartState(active.mInfo, active.mStartT, active.mFinishT);

        if (activeIdx > 0) {
            // This is now playing at the same time as an existing animation, so try merging it.
            attemptMergeTransition(mActiveTransitions.get(0), active);
            return;
        }
        // The normal case, just play it.
        playTransition(active);
    }

    /**
     * Attempt to merge by delegating the transition start to the handler of the currently
     * playing transition.
     */
    void attemptMergeTransition(@NonNull ActiveTransition playing,
            @NonNull ActiveTransition merging) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition %s ready while"
                + " another transition %s is still animating. Notify the animating transition"
                + " in case they can be merged", merging.mToken, playing.mToken);
        playing.mHandler.mergeAnimation(merging.mToken, merging.mInfo, merging.mStartT,
                playing.mToken, (wct, cb) -> onFinish(merging.mToken, wct, cb));
    }

    boolean startAnimation(@NonNull ActiveTransition active, TransitionHandler handler) {
        return handler.startAnimation(active.mToken, active.mInfo, active.mStartT, active.mFinishT,
                (wct, cb) -> onFinish(active.mToken, wct, cb));
    }

    void playTransition(@NonNull ActiveTransition active) {
        setupAnimHierarchy(active.mInfo, active.mStartT, active.mFinishT);

        // If a handler already chose to run this animation, try delegating to it first.
        if (active.mHandler != null) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " try firstHandler %s",
                    active.mHandler);
            if (startAnimation(active, active.mHandler)) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " animated by firstHandler");
                return;
            }
        }
        // Otherwise give every other handler a chance (in order)
        for (int i = mHandlers.size() - 1; i >= 0; --i) {
            if (mHandlers.get(i) == active.mHandler) continue;
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " try handler %s",
                    mHandlers.get(i));
            if (startAnimation(active, mHandlers.get(i))) {
                ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, " animated by %s",
                        mHandlers.get(i));
                active.mHandler = mHandlers.get(i);
                return;
            }
        }
        throw new IllegalStateException(
                "This shouldn't happen, maybe the default handler is broken.");
    }

    /** Special version of finish just for dealing with no-op/invalid transitions. */
    private void onAbort(IBinder transition) {
        onFinish(transition, null /* wct */, null /* wctCB */, true /* abort */);
    }

    private void onFinish(IBinder transition,
            @Nullable WindowContainerTransaction wct,
            @Nullable WindowContainerTransactionCallback wctCB) {
        onFinish(transition, wct, wctCB, false /* abort */);
    }

    private void onFinish(IBinder transition,
            @Nullable WindowContainerTransaction wct,
            @Nullable WindowContainerTransactionCallback wctCB,
            boolean abort) {
        int activeIdx = findActiveTransition(transition);
        if (activeIdx < 0) {
            Log.e(TAG, "Trying to finish a non-running transition. Either remote crashed or "
                    + " a handler didn't properly deal with a merge.", new RuntimeException());
            return;
        } else if (activeIdx > 0) {
            // This transition was merged.
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition was merged (abort=%b:"
                    + " %s", abort, transition);
            final ActiveTransition active = mActiveTransitions.get(activeIdx);
            active.mMerged = true;
            active.mAborted = abort;
            if (active.mHandler != null) {
                active.mHandler.onTransitionMerged(active.mToken);
            }
            return;
        }
        mActiveTransitions.get(activeIdx).mAborted = abort;
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                "Transition animation finished (abort=%b), notifying core %s", abort, transition);
        // Merge all relevant transactions together
        SurfaceControl.Transaction fullFinish = mActiveTransitions.get(activeIdx).mFinishT;
        for (int iA = activeIdx + 1; iA < mActiveTransitions.size(); ++iA) {
            final ActiveTransition toMerge = mActiveTransitions.get(iA);
            if (!toMerge.mMerged) break;
            // aborted transitions have no start/finish transactions
            if (mActiveTransitions.get(iA).mStartT == null) break;
            if (fullFinish == null) {
                fullFinish = new SurfaceControl.Transaction();
            }
            // Include start. It will be a no-op if it was already applied. Otherwise, we need it
            // to maintain consistent state.
            fullFinish.merge(mActiveTransitions.get(iA).mStartT);
            fullFinish.merge(mActiveTransitions.get(iA).mFinishT);
        }
        if (fullFinish != null) {
            fullFinish.apply();
        }
        // Now perform all the finishes.
        mActiveTransitions.remove(activeIdx);
        mOrganizer.finishTransition(transition, wct, wctCB);
        while (activeIdx < mActiveTransitions.size()) {
            if (!mActiveTransitions.get(activeIdx).mMerged) break;
            ActiveTransition merged = mActiveTransitions.remove(activeIdx);
            mOrganizer.finishTransition(merged.mToken, null /* wct */, null /* wctCB */);
        }
        // sift through aborted transitions
        while (mActiveTransitions.size() > activeIdx
                && mActiveTransitions.get(activeIdx).mAborted) {
            ActiveTransition aborted = mActiveTransitions.remove(activeIdx);
            mOrganizer.finishTransition(aborted.mToken, null /* wct */, null /* wctCB */);
        }
        if (mActiveTransitions.size() <= activeIdx) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "All active transition animations "
                    + "finished");
            return;
        }
        // Start animating the next active transition
        final ActiveTransition next = mActiveTransitions.get(activeIdx);
        if (next.mInfo == null) {
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Pending transition after one"
                    + " finished, but it isn't ready yet.");
            return;
        }
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Pending transitions after one"
                + " finished, so start the next one.");
        playTransition(next);
        // Now try to merge the rest of the transitions (re-acquire activeIdx since next may have
        // finished immediately)
        activeIdx = findActiveTransition(next.mToken);
        if (activeIdx < 0) {
            // This means 'next' finished immediately and thus re-entered this function. Since
            // that is the case, just return here since all relevant logic has already run in the
            // re-entered call.
            return;
        }

        // This logic is also convoluted because 'next' may finish immediately in response to any of
        // the merge requests (eg. if it decided to "cancel" itself).
        int mergeIdx = activeIdx + 1;
        while (mergeIdx < mActiveTransitions.size()) {
            ActiveTransition mergeCandidate = mActiveTransitions.get(mergeIdx);
            if (mergeCandidate.mAborted) {
                // transition was aborted, so we can skip for now (still leave it in the list
                // so that it gets cleaned-up in the right order).
                ++mergeIdx;
                continue;
            }
            if (mergeCandidate.mMerged) {
                throw new IllegalStateException("Can't merge a transition after not-merging"
                        + " a preceding one.");
            }
            attemptMergeTransition(next, mergeCandidate);
            mergeIdx = findActiveTransition(mergeCandidate.mToken);
            if (mergeIdx < 0) {
                // This means 'next' finished immediately and thus re-entered this function. Since
                // that is the case, just return here since all relevant logic has already run in
                // the re-entered call.
                return;
            }
            ++mergeIdx;
        }
    }

    void requestStartTransition(@NonNull IBinder transitionToken,
            @Nullable TransitionRequestInfo request) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition requested: %s %s",
                transitionToken, request);
        if (findActiveTransition(transitionToken) >= 0) {
            throw new RuntimeException("Transition already started " + transitionToken);
        }
        final ActiveTransition active = new ActiveTransition();
        WindowContainerTransaction wct = null;
        for (int i = mHandlers.size() - 1; i >= 0; --i) {
            wct = mHandlers.get(i).handleRequest(transitionToken, request);
            if (wct != null) {
                active.mHandler = mHandlers.get(i);
                break;
            }
        }
        active.mToken = mOrganizer.startTransition(
                request.getType(), transitionToken, wct);
        mActiveTransitions.add(active);
    }

    /** Start a new transition directly. */
    public IBinder startTransition(@WindowManager.TransitionType int type,
            @NonNull WindowContainerTransaction wct, @Nullable TransitionHandler handler) {
        final ActiveTransition active = new ActiveTransition();
        active.mHandler = handler;
        active.mToken = mOrganizer.startTransition(type, null /* token */, wct);
        mActiveTransitions.add(active);
        return active.mToken;
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
         * into another animation. Gives this handler a chance to clean-up any expectations.
         */
        default void onTransitionMerged(@NonNull IBinder transition) { }

        /**
         * Sets transition animation scale settings value to handler.
         *
         * @param scale The setting value of transition animation scale.
         */
        default void setAnimScaleSetting(float scale) {}
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
        private IShellTransitionsImpl mIShellTransitions;

        @Override
        public IShellTransitions createExternalInterface() {
            if (mIShellTransitions != null) {
                mIShellTransitions.invalidate();
            }
            mIShellTransitions = new IShellTransitionsImpl(Transitions.this);
            return mIShellTransitions;
        }

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
    private static class IShellTransitionsImpl extends IShellTransitions.Stub {
        private Transitions mTransitions;

        IShellTransitionsImpl(Transitions transitions) {
            mTransitions = transitions;
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        void invalidate() {
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
    }

    private class SettingsObserver extends ContentObserver {

        SettingsObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            mTransitionAnimationScaleSetting = Settings.Global.getFloat(
                    mContext.getContentResolver(), Settings.Global.TRANSITION_ANIMATION_SCALE,
                    mTransitionAnimationScaleSetting);

            mMainExecutor.execute(() -> dispatchAnimScaleSetting(mTransitionAnimationScaleSetting));
        }
    }
}
