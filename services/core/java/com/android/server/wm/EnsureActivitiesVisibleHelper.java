/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.server.wm.ActivityRecord.State.INITIALIZING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.Task.TAG_VISIBILITY;

import android.annotation.Nullable;
import android.util.Slog;

import java.util.ArrayList;

/** Helper class to ensure activities are in the right visible state for a container. */
class EnsureActivitiesVisibleHelper {
    private final TaskFragment mTaskFragment;
    private ActivityRecord mTopRunningActivity;
    private ActivityRecord mStarting;
    private boolean mAboveTop;
    private boolean mContainerShouldBeVisible;
    private boolean mBehindFullyOccludedContainer;
    private boolean mNotifyClients;

    EnsureActivitiesVisibleHelper(TaskFragment container) {
        mTaskFragment = container;
    }

    /**
     * Update all attributes except {@link mTaskFragment} to use in subsequent calculations.
     *
     * @param starting The activity that is being started
     * @param notifyClients Flag indicating whether the configuration and visibility changes shoulc
     *                      be sent to the clients.
     */
    void reset(ActivityRecord starting, boolean notifyClients) {
        mStarting = starting;
        mTopRunningActivity = mTaskFragment.topRunningActivity();
        // If the top activity is not fullscreen, then we need to make sure any activities under it
        // are now visible.
        mAboveTop = mTopRunningActivity != null;
        mContainerShouldBeVisible = mTaskFragment.shouldBeVisible(mStarting);
        mBehindFullyOccludedContainer = !mContainerShouldBeVisible;
        mNotifyClients = notifyClients;
    }

    /**
     * Update and commit visibility with an option to also update the configuration of visible
     * activities.
     * @see Task#ensureActivitiesVisible(ActivityRecord)
     * @see RootWindowContainer#ensureActivitiesVisible()
     * @param starting The top most activity in the task.
     *                 The activity is either starting or resuming.
     *                 Caller should ensure starting activity is visible.
     *
     * @param notifyClients Flag indicating whether the configuration and visibility changes shoulc
     *                      be sent to the clients.
     */
    void process(@Nullable ActivityRecord starting, boolean notifyClients) {
        reset(starting, notifyClients);

        if (DEBUG_VISIBILITY) {
            Slog.v(TAG_VISIBILITY, "ensureActivitiesVisible behind " + mTopRunningActivity);
        }
        if (mTopRunningActivity != null && mTaskFragment.asTask() != null) {
            // TODO(14709632): Check if this needed to be implemented in TaskFragment.
            mTaskFragment.asTask().checkTranslucentActivityWaiting(mTopRunningActivity);
        }

        // We should not resume activities that being launched behind because these
        // activities are actually behind other fullscreen activities, but still required
        // to be visible (such as performing Recents animation).
        final boolean resumeTopActivity = mTopRunningActivity != null
                && !mTopRunningActivity.mLaunchTaskBehind
                && mTaskFragment.canBeResumed(starting)
                && (starting == null || !starting.isDescendantOf(mTaskFragment));

        ArrayList<TaskFragment> adjacentTaskFragments = null;
        for (int i = mTaskFragment.mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mTaskFragment.mChildren.get(i);
            final TaskFragment childTaskFragment = child.asTaskFragment();
            if (childTaskFragment != null
                    && childTaskFragment.getTopNonFinishingActivity() != null) {
                childTaskFragment.updateActivityVisibilities(starting, notifyClients);
                // The TaskFragment should fully occlude the activities below if the bounds
                // equals to its parent task, unless it is translucent.
                mBehindFullyOccludedContainer |=
                        (childTaskFragment.getBounds().equals(mTaskFragment.getBounds())
                                && !childTaskFragment.isTranslucent(starting));
                if (mAboveTop && mTopRunningActivity.getTaskFragment() == childTaskFragment) {
                    mAboveTop = false;
                }

                if (mBehindFullyOccludedContainer) {
                    continue;
                }

                if (adjacentTaskFragments != null && adjacentTaskFragments.contains(
                        childTaskFragment)) {
                    if (!childTaskFragment.isTranslucent(starting)
                            && !childTaskFragment.getAdjacentTaskFragment().isTranslucent(
                                    starting)) {
                        // Everything behind two adjacent TaskFragments are occluded.
                        mBehindFullyOccludedContainer = true;
                    }
                    continue;
                }

                final TaskFragment adjacentTaskFrag = childTaskFragment.getAdjacentTaskFragment();
                if (adjacentTaskFrag != null) {
                    if (adjacentTaskFragments == null) {
                        adjacentTaskFragments = new ArrayList<>();
                    }
                    adjacentTaskFragments.add(adjacentTaskFrag);
                }
            } else if (child.asActivityRecord() != null) {
                setActivityVisibilityState(child.asActivityRecord(), starting, resumeTopActivity);
            }
        }
    }

    private void setActivityVisibilityState(ActivityRecord r, ActivityRecord starting,
            final boolean resumeTopActivity) {
        final boolean isTop = r == mTopRunningActivity;
        if (mAboveTop && !isTop) {
            // Ensure activities above the top-running activity to be invisible because the
            // activity should be finishing or cannot show to current user.
            r.makeInvisible();
            return;
        }
        mAboveTop = false;

        r.updateVisibilityIgnoringKeyguard(mBehindFullyOccludedContainer);
        final boolean reallyVisible = r.shouldBeVisibleUnchecked();

        // Check whether activity should be visible without Keyguard influence
        if (r.visibleIgnoringKeyguard) {
            if (r.occludesParent()) {
                // At this point, nothing else needs to be shown in this task.
                if (DEBUG_VISIBILITY) {
                    Slog.v(TAG_VISIBILITY, "Fullscreen: at " + r
                            + " containerVisible=" + mContainerShouldBeVisible
                            + " behindFullyOccluded=" + mBehindFullyOccludedContainer);
                }
                mBehindFullyOccludedContainer = true;
            } else {
                mBehindFullyOccludedContainer = false;
            }
        } else if (r.isState(INITIALIZING)) {
            r.cancelInitializing();
        }

        if (reallyVisible) {
            if (r.finishing) {
                return;
            }
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Make visible? " + r
                        + " finishing=" + r.finishing + " state=" + r.getState());
            }
            // First: if this is not the current activity being started, make
            // sure it matches the current configuration.
            if (r != mStarting && mNotifyClients) {
                r.ensureActivityConfiguration(true /* ignoreVisibility */);
            }

            if (!r.attachedToProcess()) {
                makeVisibleAndRestartIfNeeded(mStarting, resumeTopActivity && isTop, r);
            } else if (r.isVisibleRequested()) {
                // If this activity is already visible, then there is nothing to do here.
                if (DEBUG_VISIBILITY) {
                    Slog.v(TAG_VISIBILITY, "Skipping: already visible at " + r);
                }

                if (r.mClientVisibilityDeferred && mNotifyClients) {
                    r.makeActiveIfNeeded(r.mClientVisibilityDeferred ? null : starting);
                    r.mClientVisibilityDeferred = false;
                }

                r.handleAlreadyVisible();
                if (mNotifyClients) {
                    r.makeActiveIfNeeded(mStarting);
                }
            } else {
                r.makeVisibleIfNeeded(mStarting, mNotifyClients);
            }
        } else {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Make invisible? " + r
                        + " finishing=" + r.finishing + " state=" + r.getState()
                        + " containerShouldBeVisible=" + mContainerShouldBeVisible
                        + " behindFullyOccludedContainer=" + mBehindFullyOccludedContainer
                        + " mLaunchTaskBehind=" + r.mLaunchTaskBehind);
            }
            r.makeInvisible();
        }

        if (!mBehindFullyOccludedContainer && mTaskFragment.isActivityTypeHome()
                && r.isRootOfTask()) {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Home task: at " + mTaskFragment
                        + " containerShouldBeVisible=" + mContainerShouldBeVisible
                        + " behindOccludedParentContainer=" + mBehindFullyOccludedContainer);
            }
            // No other task in the root home task should be visible behind the home activity.
            // Home activities is usually a translucent activity with the wallpaper behind
            // them. However, when they don't have the wallpaper behind them, we want to
            // show activities in the next application root task behind them vs. another
            // task in the root home task like recents.
            mBehindFullyOccludedContainer = true;
        }
    }

    private void makeVisibleAndRestartIfNeeded(ActivityRecord starting,
            boolean andResume, ActivityRecord r) {
        // This activity needs to be visible, but isn't even running...
        // get it started and resume if no other root task in this root task is resumed.
        if (DEBUG_VISIBILITY) {
            Slog.v(TAG_VISIBILITY, "Start and freeze screen for " + r);
        }
        if (!r.isVisibleRequested() || r.mLaunchTaskBehind) {
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG_VISIBILITY, "Starting and making visible: " + r);
            }
            r.setVisibility(true);
        }
        if (r != starting) {
            mTaskFragment.mTaskSupervisor.startSpecificActivity(r, andResume,
                    true /* checkConfig */);
        }
    }
}
