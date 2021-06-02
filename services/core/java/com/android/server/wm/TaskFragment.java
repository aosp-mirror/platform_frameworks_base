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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;

import android.annotation.IntDef;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A basic container that can be used to contain activities or other {@link TaskFragment}, which
 * also able to manage the activity lifecycle and updates the visibilities of the activities in it.
 */
class TaskFragment extends WindowContainer<WindowContainer> {
    @IntDef(prefix = {"TASK_FRAGMENT_VISIBILITY"}, value = {
            TASK_FRAGMENT_VISIBILITY_VISIBLE,
            TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT,
            TASK_FRAGMENT_VISIBILITY_INVISIBLE,
    })
    @interface TaskFragmentVisibility {}

    /**
     * TaskFragment is visible. No other TaskFragment(s) on top that fully or partially occlude it.
     */
    static final int TASK_FRAGMENT_VISIBILITY_VISIBLE = 0;

    /** TaskFragment is partially occluded by other translucent TaskFragment(s) on top of it. */
    static final int TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT = 1;

    /** TaskFragment is completely invisible. */
    static final int TASK_FRAGMENT_VISIBILITY_INVISIBLE = 2;

    final ActivityTaskManagerService mAtmService;
    final ActivityTaskSupervisor mTaskSupervisor;
    final RootWindowContainer mRootWindowContainer;

    // The TaskFragment that adjacent to this one.
    private TaskFragment mAdjacentTaskFragment;

    private final EnsureActivitiesVisibleHelper mEnsureActivitiesVisibleHelper =
            new EnsureActivitiesVisibleHelper(this);

    TaskFragment(ActivityTaskManagerService atmService) {
        super(atmService.mWindowManager);

        mAtmService = atmService;
        mTaskSupervisor = atmService.mTaskSupervisor;
        mRootWindowContainer = mAtmService.mRootWindowContainer;
    }

    void setAdjacentTaskFragment(TaskFragment taskFragment) {
        mAdjacentTaskFragment = taskFragment;
        taskFragment.mAdjacentTaskFragment = this;
    }

    TaskFragment getAdjacentTaskFragment() {
        return mAdjacentTaskFragment;
    }

    @Override
    TaskFragment asTaskFragment() {
        return this;
    }

    boolean hasDirectChildActivities() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).asActivityRecord() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether this TaskFragment is currently forced to be hidden for any reason.
     */
    protected boolean isForceHidden() {
        return false;
    }

    /**
     * Returns true if the TaskFragment is translucent and can have other contents visible behind
     * it if needed. A TaskFragment is considered translucent if it don't contain a visible or
     * starting (about to be visible) activity that is fullscreen (opaque).
     * @param starting The currently starting activity or null if there is none.
     */
    @VisibleForTesting
    boolean isTranslucent(ActivityRecord starting) {
        if (!isAttached() || isForceHidden()) {
            return true;
        }
        final PooledPredicate p = PooledLambda.obtainPredicate(TaskFragment::isOpaqueActivity,
                PooledLambda.__(ActivityRecord.class), starting);
        final ActivityRecord opaque = getActivity(p);
        p.recycle();
        return opaque == null;
    }

    private static boolean isOpaqueActivity(ActivityRecord r, ActivityRecord starting) {
        if (r.finishing) {
            // We don't factor in finishing activities when determining translucency since
            // they will be gone soon.
            return false;
        }

        if (!r.visibleIgnoringKeyguard && r != starting) {
            // Also ignore invisible activities that are not the currently starting
            // activity (about to be visible).
            return false;
        }

        if (r.occludesParent()) {
            // Root task isn't translucent if it has at least one fullscreen activity
            // that is visible.
            return true;
        }
        return false;
    }

    ActivityRecord topRunningActivity() {
        return topRunningActivity(false /* focusableOnly */);
    }

    ActivityRecord topRunningActivity(boolean focusableOnly) {
        // Split into 2 to avoid object creation due to variable capture.
        if (focusableOnly) {
            return getActivity((r) -> r.canBeTopRunning() && r.isFocusable());
        } else {
            return getActivity(ActivityRecord::canBeTopRunning);
        }
    }

    boolean isTopActivityFocusable() {
        final ActivityRecord r = topRunningActivity();
        return r != null ? r.isFocusable()
                : (isFocusable() && getWindowConfiguration().canReceiveKeys());
    }

    /**
     * Returns the visibility state of this TaskFragment.
     *
     * @param starting The currently starting activity or null if there is none.
     */
    @TaskFragmentVisibility
    // TODO(b/189384393): Review the usage of this method and see if that needs to be done via
    //  TaskFragment vs. Task.
    int getVisibility(ActivityRecord starting) {
        if (!isAttached() || isForceHidden()) {
            return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
        }

        if (isTopActivityLaunchedBehind()) {
            return TASK_FRAGMENT_VISIBILITY_VISIBLE;
        }

        boolean gotRootSplitScreenFragment = false;
        boolean gotOpaqueSplitScreenPrimary = false;
        boolean gotOpaqueSplitScreenSecondary = false;
        boolean gotTranslucentFullscreen = false;
        boolean gotTranslucentSplitScreenPrimary = false;
        boolean gotTranslucentSplitScreenSecondary = false;
        boolean shouldBeVisible = true;

        // This TaskFragment is only considered visible if all its parent TaskFragments are
        // considered visible, so check the visibility of all ancestor TaskFragment first.
        final WindowContainer parent = getParent();
        if (parent.asTaskFragment() != null) {
            final int parentVisibility = parent.asTaskFragment().getVisibility(starting);
            if (parentVisibility == TASK_FRAGMENT_VISIBILITY_INVISIBLE) {
                // Can't be visible if parent isn't visible
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            } else if (parentVisibility == TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT) {
                // Parent is behind a translucent container so the highest visibility this container
                // can get is that.
                gotTranslucentFullscreen = true;
            }
        }

        final List<TaskFragment> adjacentTaskFragments = new ArrayList<>();
        final int windowingMode = getWindowingMode();
        final boolean isAssistantType = isActivityTypeAssistant();
        for (int i = parent.getChildCount() - 1; i >= 0; --i) {
            final WindowContainer wc = parent.getChildAt(i);
            final TaskFragment other = wc.asTaskFragment();
            if (other == null) continue;

            final boolean hasRunningActivities = other.topRunningActivity() != null;
            if (other == this) {
                // Should be visible if there is no other fragment occluding it, unless it doesn't
                // have any running activities, not starting one and not home stack.
                shouldBeVisible = hasRunningActivities
                        || (starting != null && starting.isDescendantOf(this))
                        || isActivityTypeHome();
                break;
            }

            if (!hasRunningActivities) {
                continue;
            }

            final int otherWindowingMode = other.getWindowingMode();
            if (otherWindowingMode == WINDOWING_MODE_FULLSCREEN) {
                if (other.isTranslucent(starting)) {
                    // Can be visible behind a translucent fullscreen TaskFragment.
                    gotTranslucentFullscreen = true;
                    continue;
                }
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            } else if (otherWindowingMode == WINDOWING_MODE_MULTI_WINDOW
                    && other.matchParentBounds()) {
                if (other.isTranslucent(starting)) {
                    // Can be visible behind a translucent TaskFragment.
                    gotTranslucentFullscreen = true;
                    continue;
                }
                // Multi-window TaskFragment that matches parent bounds would occlude other children
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            } else if (otherWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                    && !gotOpaqueSplitScreenPrimary) {
                gotRootSplitScreenFragment = true;
                gotTranslucentSplitScreenPrimary = other.isTranslucent(starting);
                gotOpaqueSplitScreenPrimary = !gotTranslucentSplitScreenPrimary;
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                        && gotOpaqueSplitScreenPrimary) {
                    // Can't be visible behind another opaque TaskFragment in split-screen-primary.
                    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
                }
            } else if (otherWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    && !gotOpaqueSplitScreenSecondary) {
                gotRootSplitScreenFragment = true;
                gotTranslucentSplitScreenSecondary = other.isTranslucent(starting);
                gotOpaqueSplitScreenSecondary = !gotTranslucentSplitScreenSecondary;
                if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                        && gotOpaqueSplitScreenSecondary) {
                    // Can't be visible behind another opaque TaskFragment in split-screen-secondary
                    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
                }
            }
            if (gotOpaqueSplitScreenPrimary && gotOpaqueSplitScreenSecondary) {
                // Can not be visible if we are in split-screen windowing mode and both halves of
                // the screen are opaque.
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            }
            if (isAssistantType && gotRootSplitScreenFragment) {
                // Assistant TaskFragment can't be visible behind split-screen. In addition to
                // this not making sense, it also works around an issue here we boost the z-order
                // of the assistant window surfaces in window manager whenever it is visible.
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            }

            if (other.mAdjacentTaskFragment != null) {
                if (adjacentTaskFragments.contains(other.mAdjacentTaskFragment)) {
                    if (other.isTranslucent(starting)
                            || other.mAdjacentTaskFragment.isTranslucent(starting)) {
                        // Can be visible behind a translucent adjacent TaskFragments.
                        gotTranslucentFullscreen = true;
                        continue;
                    }
                    // Can not be visible behind adjacent TaskFragments.
                    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
                } else {
                    adjacentTaskFragments.add(other);
                }
            }

        }

        if (!shouldBeVisible) {
            return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
        }

        // Handle cases when there can be a translucent split-screen TaskFragment on top.
        switch (windowingMode) {
            case WINDOWING_MODE_FULLSCREEN:
                if (gotTranslucentSplitScreenPrimary || gotTranslucentSplitScreenSecondary) {
                    // At least one of the split-screen TaskFragment that covers this one is
                    // translucent.
                    // When in split mode, home will be reparented to the secondary split while
                    // leaving TaskFragments not supporting split below. Due to
                    // TaskDisplayArea#assignRootTaskOrdering always adjusts home surface layer to
                    // the bottom, this makes sure TaskFragments not in split roots won't occlude
                    // home task unexpectedly.
                    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
                }
                break;
            case WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:
                if (gotTranslucentSplitScreenPrimary) {
                    // Covered by translucent primary split-screen on top.
                    return TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
            case WINDOWING_MODE_SPLIT_SCREEN_SECONDARY:
                if (gotTranslucentSplitScreenSecondary) {
                    // Covered by translucent secondary split-screen on top.
                    return TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT;
                }
                break;
        }

        // Lastly - check if there is a translucent fullscreen TaskFragment on top.
        return gotTranslucentFullscreen
                ? TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT
                : TASK_FRAGMENT_VISIBILITY_VISIBLE;
    }

    private boolean isTopActivityLaunchedBehind() {
        final ActivityRecord top = topRunningActivity();
        if (top != null && top.mLaunchTaskBehind) {
            return true;
        }
        return false;
    }

    void ensureFragmentActivitiesVisible(@Nullable ActivityRecord starting, int configChanges,
            boolean preserveWindows, boolean notifyClients) {
        mEnsureActivitiesVisibleHelper.process(
                starting, configChanges, preserveWindows, notifyClients);
    }

    /**
     * Returns true if the TaskFragment should be visible.
     *
     * @param starting The currently starting activity or null if there is none.
     */
    boolean shouldBeVisible(ActivityRecord starting) {
        return getVisibility(starting) != TASK_FRAGMENT_VISIBILITY_INVISIBLE;
    }

    void forAllTaskFragments(Consumer<TaskFragment> callback, boolean traverseTopToBottom) {
        final int count = mChildren.size();
        if (traverseTopToBottom) {
            for (int i = count - 1; i >= 0; --i) {
                final TaskFragment child = mChildren.get(i).asTaskFragment();
                if (child != null) {
                    child.forAllTaskFragments(callback, traverseTopToBottom);
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                final TaskFragment child = mChildren.get(i).asTaskFragment();
                if (child != null) {
                    child.forAllTaskFragments(callback, traverseTopToBottom);
                }
            }
        }
        callback.accept(this);
    }
}
