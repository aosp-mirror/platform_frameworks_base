/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;

import com.android.window.flags.Flags;

/**
 * Represents a chain of WM actions where each action is "caused by" the prior action (except the
 * first one of course). A whole chain is associated with one Transition (in fact, the purpose
 * of this object is to communicate, to all callees, which transition they are part of).
 *
 * A single action is defined as "one logical thing requested of WM". This usually corresponds to
 * each ingress-point into the process. For example, when starting an activity:
 *   * the first action is to pause the current/top activity.
 *       At this point, control leaves the process while the activity pauses.
 *   * Then WM receives completePause (a new ingress). This is a new action that gets linked
 *       to the prior action. This action involves resuming the next activity, at which point,
 *       control leaves the process again.
 *   * Eventually, when everything is done, we will have formed a chain of actions.
 *
 * We don't technically need to hold onto each prior action in the chain once a new action has
 * been linked to the same transition; however, keeping the whole chain enables improved
 * debugging and the ability to detect anomalies.
 */
public class ActionChain {
    private static final String TAG = "TransitionChain";

    /**
     * Normal link type. This means the action was expected and is properly linked to the
     * current chain.
     */
    static final int TYPE_NORMAL = 0;

    /**
     * This is the "default" link. It means we haven't done anything to properly track this case
     * so it may or may not be correct. It represents the behavior as if there was no tracking.
     *
     * Any type that has "default" behavior uses the global "collecting transition" if it exists,
     * otherwise it doesn't use any transition.
     */
    static final int TYPE_DEFAULT = 1;

    /**
     * This means the action was performed via a legacy code-path. These should be removed
     * eventually. This will have the "default" behavior.
     */
    static final int TYPE_LEGACY = 2;

    /** This is for a test. */
    static final int TYPE_TEST = 3;

    /** This is finishing a transition. Collection isn't supported during this. */
    static final int TYPE_FINISH = 4;

    /**
     * Something unexpected happened so this action was started to recover from the unexpected
     * state. This means that a "real" chain-link couldn't be determined. For now, the behavior of
     * this is the same as "default".
     */
    static final int TYPE_FAILSAFE = 5;

    /**
     * Types of chain links (ie. how is this action associated with the chain it is linked to).
     * @hide
     */
    @IntDef(prefix = { "TYPE_" }, value = {
            TYPE_NORMAL,
            TYPE_DEFAULT,
            TYPE_LEGACY,
            TYPE_TEST,
            TYPE_FINISH,
            TYPE_FAILSAFE
    })
    public @interface LinkType {}

    /** Identifies the entry-point of this action. */
    @NonNull
    final String mSource;

    /** Reference to ATMS. TEMPORARY! ONLY USE THIS WHEN tracker_plumbing flag is DISABLED! */
    @Nullable
    ActivityTaskManagerService mTmpAtm;

    /** The transition that this chain's changes belong to. */
    @Nullable
    Transition mTransition;

    /** The previous action in the chain. */
    @Nullable
    ActionChain mPrevious = null;

    /** Classification of how this action is connected to the chain. */
    @LinkType int mType = TYPE_NORMAL;

    /** When this Action started. */
    long mCreateTimeMs;

    private ActionChain(String source, @LinkType int type, Transition transit) {
        mSource = source;
        mCreateTimeMs = System.currentTimeMillis();
        mType = type;
        mTransition = transit;
        if (mTransition != null) {
            mTransition.recordChain(this);
        }
    }

    private Transition getTransition() {
        if (!Flags.transitTrackerPlumbing()) {
            return mTmpAtm.getTransitionController().getCollectingTransition();
        }
        return mTransition;
    }

    boolean isFinishing() {
        return mType == TYPE_FINISH;
    }

    /**
     * Some common checks to determine (and report) whether this chain has a collecting transition.
     */
    private boolean expectCollecting() {
        final Transition transition = getTransition();
        if (transition == null) {
            Slog.e(TAG, "Can't collect into a chain with no transition");
            return false;
        }
        if (isFinishing()) {
            Slog.e(TAG, "Trying to collect into a finished transition");
            return false;
        }
        if (transition.mController.getCollectingTransition() != mTransition) {
            Slog.e(TAG, "Mismatch between current collecting ("
                    + transition.mController.getCollectingTransition() + ") and chain ("
                    + transition + ")");
            return false;
        }
        return true;
    }

    /**
     * Helper to collect a container into the associated transition. This will automatically do
     * nothing if the chain isn't associated with a collecting transition.
     */
    void collect(@NonNull WindowContainer wc) {
        if (!wc.mTransitionController.isShellTransitionsEnabled()) return;
        if (!expectCollecting()) return;
        getTransition().collect(wc);
    }

    /**
     * An interface for creating and tracking action chains.
     */
    static class Tracker {
        private final ActivityTaskManagerService mAtm;

        Tracker(ActivityTaskManagerService atm) {
            mAtm = atm;
        }

        private ActionChain makeChain(String source, @LinkType int type, Transition transit) {
            final ActionChain out = new ActionChain(source, type, transit);
            if (!Flags.transitTrackerPlumbing()) {
                out.mTmpAtm = mAtm;
            }
            return out;
        }

        private ActionChain makeChain(String source, @LinkType int type) {
            return makeChain(source, type,
                    mAtm.getTransitionController().getCollectingTransition());
        }

        /**
         * Starts tracking a normal action.
         * @see #TYPE_NORMAL
         */
        @NonNull
        ActionChain start(String source, Transition transit) {
            return makeChain(source, TYPE_NORMAL, transit);
        }

        /** @see #TYPE_DEFAULT */
        @NonNull
        ActionChain startDefault(String source) {
            return makeChain(source, TYPE_DEFAULT);
        }

        /**
         * Starts tracking an action that finishes a transition.
         * @see #TYPE_NORMAL
         */
        @NonNull
        ActionChain startFinish(String source, Transition finishTransit) {
            return makeChain(source, TYPE_FINISH, finishTransit);
        }

        /** @see #TYPE_LEGACY */
        @NonNull
        ActionChain startLegacy(String source) {
            return makeChain(source, TYPE_LEGACY, null);
        }

        /** @see #TYPE_FAILSAFE */
        @NonNull
        ActionChain startFailsafe(String source) {
            return makeChain(source, TYPE_FAILSAFE);
        }
    }

    /** Helpers for usage in tests. */
    @NonNull
    static ActionChain test() {
        return new ActionChain("test", TYPE_TEST, null /* transition */);
    }

    @NonNull
    static ActionChain testFinish(Transition toFinish) {
        return new ActionChain("test", TYPE_FINISH, toFinish);
    }
}
