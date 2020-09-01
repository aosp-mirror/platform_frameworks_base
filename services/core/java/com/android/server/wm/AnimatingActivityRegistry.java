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

import android.util.ArrayMap;
import android.util.ArraySet;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Keeps track of all {@link ActivityRecord} that are animating and makes sure all animations are
 * finished at the same time such that we don't run into issues with z-ordering: An activity A
 * that has a shorter animation that is above another activity B with a longer animation in the same
 * task, the animation layer would put the B on top of A, but from the hierarchy, A needs to be on
 * top of B. Thus, we defer reparenting A to the original hierarchy such that it stays on top of B
 * until B finishes animating.
 */
class AnimatingActivityRegistry {

    private ArraySet<ActivityRecord> mAnimatingActivities = new ArraySet<>();
    private ArrayMap<ActivityRecord, Runnable> mFinishedTokens = new ArrayMap<>();

    private ArrayList<Runnable> mTmpRunnableList = new ArrayList<>();

    private boolean mEndingDeferredFinish;

    /**
     * Notifies that an {@link ActivityRecord} has started animating.
     */
    void notifyStarting(ActivityRecord token) {
        mAnimatingActivities.add(token);
    }

    /**
     * Notifies that an {@link ActivityRecord} has finished animating.
     */
    void notifyFinished(ActivityRecord activity) {
        mAnimatingActivities.remove(activity);
        mFinishedTokens.remove(activity);

        // If we were the last activity, make sure the end all deferred finishes.
        if (mAnimatingActivities.isEmpty()) {
            endDeferringFinished();
        }
    }

    /**
     * Called when an {@link ActivityRecord} is about to finish animating.
     *
     * @param endDeferFinishCallback Callback to run when defer finish should be ended.
     * @return {@code true} if finishing the animation should be deferred, {@code false} otherwise.
     */
    boolean notifyAboutToFinish(ActivityRecord activity, Runnable endDeferFinishCallback) {
        final boolean removed = mAnimatingActivities.remove(activity);
        if (!removed) {
            return false;
        }

        if (mAnimatingActivities.isEmpty()) {

            // If no animations are animating anymore, finish all others.
            endDeferringFinished();
            return false;
        } else {

            // Otherwise let's put it into the pending list of to be finished animations.
            mFinishedTokens.put(activity, endDeferFinishCallback);
            return true;
        }
    }

    private void endDeferringFinished() {

        // Don't start recursing. Running the finished listener invokes notifyFinished, which may
        // invoked us again.
        if (mEndingDeferredFinish) {
            return;
        }
        try {
            mEndingDeferredFinish = true;

            // Copy it into a separate temp list to avoid modifying the collection while iterating
            // as calling the callback may call back into notifyFinished.
            for (int i = mFinishedTokens.size() - 1; i >= 0; i--) {
                mTmpRunnableList.add(mFinishedTokens.valueAt(i));
            }
            mFinishedTokens.clear();
            for (int i = mTmpRunnableList.size() - 1; i >= 0; i--) {
                mTmpRunnableList.get(i).run();
            }
            mTmpRunnableList.clear();
        } finally {
            mEndingDeferredFinish = false;
        }
    }

    void dump(PrintWriter pw, String header, String prefix) {
        if (!mAnimatingActivities.isEmpty() || !mFinishedTokens.isEmpty()) {
            pw.print(prefix); pw.println(header);
            prefix = prefix + "  ";
            pw.print(prefix); pw.print("mAnimatingActivities="); pw.println(mAnimatingActivities);
            pw.print(prefix); pw.print("mFinishedTokens="); pw.println(mFinishedTokens);
        }
    }
}
