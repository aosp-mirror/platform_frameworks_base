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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.pm.ActivityInfo.FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING;
import static android.content.pm.ActivityInfo.FLAG_RESUME_WHILE_PAUSING;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.os.Process.INVALID_UID;
import static android.os.Process.SYSTEM_UID;
import static android.os.UserHandle.USER_NULL;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_BACK_PREVIEW;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STATES;
import static com.android.server.wm.ActivityRecord.State.PAUSED;
import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityRecord.State.STOPPING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_SWITCH;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TRANSITION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityTaskSupervisor.printThisActivity;
import static com.android.server.wm.IdentifierProto.HASH_CODE;
import static com.android.server.wm.IdentifierProto.TITLE;
import static com.android.server.wm.IdentifierProto.USER_ID;
import static com.android.server.wm.TaskFragmentProto.ACTIVITY_TYPE;
import static com.android.server.wm.TaskFragmentProto.DISPLAY_ID;
import static com.android.server.wm.TaskFragmentProto.MIN_HEIGHT;
import static com.android.server.wm.TaskFragmentProto.MIN_WIDTH;
import static com.android.server.wm.TaskFragmentProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowContainerChildProto.TASK_FRAGMENT;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.ResultInfo;
import android.app.WindowConfiguration;
import android.app.servertransaction.ActivityResultItem;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.NewIntentItem;
import android.app.servertransaction.PauseActivityItem;
import android.app.servertransaction.ResumeActivityItem;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.ITaskFragmentOrganizer;
import android.window.ScreenCapture;
import android.window.TaskFragmentAnimationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentOrganizerToken;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.am.HostingRecord;
import com.android.server.pm.pkg.AndroidPackage;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

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

    private static final String TAG = TAG_WITH_CLASS_NAME ? "TaskFragment" : TAG_ATM;
    private static final String TAG_SWITCH = TAG + POSTFIX_SWITCH;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_TRANSITION = TAG + POSTFIX_TRANSITION;

    /** Set to false to disable the preview that is shown while a new activity is being started. */
    static final boolean SHOW_APP_STARTING_PREVIEW = true;

    /**
     * An embedding check result of {@link #isAllowedToEmbedActivity(ActivityRecord)} or
     * {@link ActivityStarter#canEmbedActivity(TaskFragment, ActivityRecord, Task)}:
     * indicate that an Activity can be embedded successfully.
     */
    static final int EMBEDDING_ALLOWED = 0;
    /**
     * An embedding check result of {@link #isAllowedToEmbedActivity(ActivityRecord)} or
     * {@link ActivityStarter#canEmbedActivity(TaskFragment, ActivityRecord, Task)}:
     * indicate that an Activity can't be embedded because either the Activity does not allow
     * untrusted embedding, and the embedding host app is not trusted.
     */
    static final int EMBEDDING_DISALLOWED_UNTRUSTED_HOST = 1;
    /**
     * An embedding check result of {@link #isAllowedToEmbedActivity(ActivityRecord)} or
     * {@link ActivityStarter#canEmbedActivity(TaskFragment, ActivityRecord, Task)}:
     * indicate that an Activity can't be embedded because this taskFragment's bounds are
     * {@link #smallerThanMinDimension(ActivityRecord)}.
     */
    static final int EMBEDDING_DISALLOWED_MIN_DIMENSION_VIOLATION = 2;
    /**
     * An embedding check result of
     * {@link ActivityStarter#canEmbedActivity(TaskFragment, ActivityRecord, Task)}:
     * indicate that an Activity can't be embedded because the Activity is started on a new task.
     */
    static final int EMBEDDING_DISALLOWED_NEW_TASK = 3;

    /**
     * Embedding check results of {@link #isAllowedToEmbedActivity(ActivityRecord)} or
     * {@link ActivityStarter#canEmbedActivity(TaskFragment, ActivityRecord, Task)}.
     */
    @IntDef(prefix = {"EMBEDDING_"}, value = {
            EMBEDDING_ALLOWED,
            EMBEDDING_DISALLOWED_UNTRUSTED_HOST,
            EMBEDDING_DISALLOWED_MIN_DIMENSION_VIOLATION,
            EMBEDDING_DISALLOWED_NEW_TASK,
    })
    @interface EmbeddingCheckResult {}

    /**
     * Indicate that the minimal width/height should use the default value.
     *
     * @see #mMinWidth
     * @see #mMinHeight
     */
    static final int INVALID_MIN_SIZE = -1;

    final ActivityTaskManagerService mAtmService;
    final ActivityTaskSupervisor mTaskSupervisor;
    final RootWindowContainer mRootWindowContainer;
    private final TaskFragmentOrganizerController mTaskFragmentOrganizerController;

    // TODO(b/233177466): Move mMinWidth and mMinHeight to Task and remove usages in TaskFragment
    /**
     * Minimal width of this task fragment when it's resizeable. {@link #INVALID_MIN_SIZE} means it
     * should use the default minimal width.
     */
    int mMinWidth;

    /**
     * Minimal height of this task fragment when it's resizeable. {@link #INVALID_MIN_SIZE} means it
     * should use the default minimal height.
     */
    int mMinHeight;

    Dimmer mDimmer = Dimmer.DIMMER_REFACTOR
            ? new SmoothDimmer(this) : new LegacyDimmer(this);

    /** Apply the dim layer on the embedded TaskFragment. */
    static final int EMBEDDED_DIM_AREA_TASK_FRAGMENT = 0;

    /** Apply the dim layer on the parent Task for an embedded TaskFragment. */
    static final int EMBEDDED_DIM_AREA_PARENT_TASK = 1;

    /**
     * The type of dim layer area for an embedded TaskFragment.
     */
    @IntDef(prefix = {"EMBEDDED_DIM_AREA_"}, value = {
            EMBEDDED_DIM_AREA_TASK_FRAGMENT,
            EMBEDDED_DIM_AREA_PARENT_TASK,
    })
    @interface EmbeddedDimArea {}

    @EmbeddedDimArea
    private int mEmbeddedDimArea = EMBEDDED_DIM_AREA_TASK_FRAGMENT;

    /** This task fragment will be removed when the cleanup of its children are done. */
    private boolean mIsRemovalRequested;

    /** The TaskFragment that is adjacent to this one. */
    @Nullable
    private TaskFragment mAdjacentTaskFragment;

    /**
     * Unlike the {@link mAdjacentTaskFragment}, the companion TaskFragment is not always visually
     * adjacent to this one, but this TaskFragment will be removed by the organizer if the
     * companion TaskFragment is removed.
     */
    @Nullable
    private TaskFragment mCompanionTaskFragment;

    /**
     * Prevents duplicate calls to onTaskFragmentAppeared.
     */
    boolean mTaskFragmentAppearedSent;

    /**
     * Prevents unnecessary callbacks after onTaskFragmentVanished.
     */
    boolean mTaskFragmentVanishedSent;

    /**
     * The last running activity of the TaskFragment was finished due to clear task while launching
     * an activity in the Task.
     */
    boolean mClearedTaskForReuse;

    /**
     * The last running activity of the TaskFragment was reparented to a different Task because it
     * is entering PiP.
     */
    boolean mClearedTaskFragmentForPip;

    /**
     * The last running activity of the TaskFragment was removed and added to the top-most of the
     * Task because it was launched with FLAG_ACTIVITY_REORDER_TO_FRONT.
     */
    boolean mClearedForReorderActivityToFront;

    /**
     * When we are in the process of pausing an activity, before starting the
     * next one, this variable holds the activity that is currently being paused.
     *
     * Only set at leaf task fragments.
     */
    @Nullable
    private ActivityRecord mPausingActivity = null;

    /**
     * This is the last activity that we put into the paused state.  This is
     * used to determine if we need to do an activity transition while sleeping,
     * when we normally hold the top activity paused.
     */
    ActivityRecord mLastPausedActivity = null;

    /**
     * Current activity that is resumed, or null if there is none.
     * Only set at leaf task fragments.
     */
    @Nullable
    private ActivityRecord mResumedActivity = null;

    /**
     * This TaskFragment was created by an organizer which has the following implementations.
     * <ul>
     *     <li>The TaskFragment won't be removed when it is empty. Removal has to be an explicit
     *     request from the organizer.</li>
     *     <li>If this fragment is a Task object then unlike other non-root tasks, it's direct
     *     children are visible to the organizer for ordering purposes.</li>
     *     <li>A TaskFragment can be created by {@link android.window.TaskFragmentOrganizer}, and
     *     a Task can be created by {@link android.window.TaskOrganizer}.</li>
     * </ul>
     */
    @VisibleForTesting
    boolean mCreatedByOrganizer;

    /** Whether this TaskFragment is embedded in a task. */
    private final boolean mIsEmbedded;

    /** Organizer that organizing this TaskFragment. */
    @Nullable
    private ITaskFragmentOrganizer mTaskFragmentOrganizer;
    private int mTaskFragmentOrganizerUid = INVALID_UID;
    private @Nullable String mTaskFragmentOrganizerProcessName;

    /** Client assigned unique token for this TaskFragment if this is created by an organizer. */
    @Nullable
    private final IBinder mFragmentToken;

    /** The animation override params for animation running on this TaskFragment. */
    @NonNull
    private TaskFragmentAnimationParams mAnimationParams = TaskFragmentAnimationParams.DEFAULT;

    /**
     * The organizer requested bounds of the embedded TaskFragment relative to the parent Task.
     * {@code null} if it is not {@link #mIsEmbedded}
     */
    @Nullable
    private final Rect mRelativeEmbeddedBounds;

    /**
     * Whether to delay the call to {@link #updateOrganizedTaskFragmentSurface()} when there is a
     * configuration change.
     */
    private boolean mDelayOrganizedTaskFragmentSurfaceUpdate;

    /**
     * Whether to delay the last activity of TaskFragment being immediately removed while finishing.
     * This should only be set on a embedded TaskFragment, where the organizer can have the
     * opportunity to perform animations and finishing the adjacent TaskFragment.
     */
    private boolean mDelayLastActivityRemoval;

    /**
     * Whether the activity navigation should be isolated. That is, Activities cannot be launched
     * on an isolated TaskFragment, unless the activity is launched from an Activity in the same
     * isolated TaskFragment, or explicitly requested to be launched to.
     * <p>
     * Note that only an embedded TaskFragment can be isolated.
     */
    private boolean mIsolatedNav;

    /** When set, will force the task to report as invisible. */
    static final int FLAG_FORCE_HIDDEN_FOR_PINNED_TASK = 1;
    static final int FLAG_FORCE_HIDDEN_FOR_TASK_ORG = 1 << 1;
    static final int FLAG_FORCE_HIDDEN_FOR_TASK_FRAGMENT_ORG = 1 << 2;

    @IntDef(prefix = {"FLAG_FORCE_HIDDEN_"}, value = {
            FLAG_FORCE_HIDDEN_FOR_PINNED_TASK,
            FLAG_FORCE_HIDDEN_FOR_TASK_ORG,
            FLAG_FORCE_HIDDEN_FOR_TASK_FRAGMENT_ORG,
    }, flag = true)
    @interface FlagForceHidden {}
    protected int mForceHiddenFlags = 0;

    private boolean mForceTranslucent = false;

    final Point mLastSurfaceSize = new Point();

    private final Rect mTmpBounds = new Rect();
    private final Rect mTmpAbsBounds = new Rect();
    private final Rect mTmpFullBounds = new Rect();
    /** For calculating screenWidthDp and screenWidthDp, i.e. the area without the system bars. */
    private final Rect mTmpStableBounds = new Rect();
    /** For calculating app bounds, i.e. the area without the nav bar and display cutout. */
    private final Rect mTmpNonDecorBounds = new Rect();

    //TODO(b/207481538) Remove once the infrastructure to support per-activity screenshot is
    // implemented
    HashMap<String, ScreenCapture.ScreenshotHardwareBuffer> mBackScreenshots = new HashMap<>();

    private final EnsureActivitiesVisibleHelper mEnsureActivitiesVisibleHelper =
            new EnsureActivitiesVisibleHelper(this);

    /** Creates an embedded task fragment. */
    TaskFragment(ActivityTaskManagerService atmService, IBinder fragmentToken,
            boolean createdByOrganizer) {
        this(atmService, fragmentToken, createdByOrganizer, true /* isEmbedded */);
    }

    TaskFragment(ActivityTaskManagerService atmService, IBinder fragmentToken,
            boolean createdByOrganizer, boolean isEmbedded) {
        super(atmService.mWindowManager);

        mAtmService = atmService;
        mTaskSupervisor = mAtmService.mTaskSupervisor;
        mRootWindowContainer = mAtmService.mRootWindowContainer;
        mCreatedByOrganizer = createdByOrganizer;
        mIsEmbedded = isEmbedded;
        mRelativeEmbeddedBounds = isEmbedded ? new Rect() : null;
        mTaskFragmentOrganizerController =
                mAtmService.mWindowOrganizerController.mTaskFragmentOrganizerController;
        mFragmentToken = fragmentToken;
        mRemoteToken = new RemoteToken(this);
    }

    @NonNull
    static TaskFragment fromTaskFragmentToken(@Nullable IBinder token,
            @NonNull ActivityTaskManagerService service) {
        if (token == null) return null;
        return service.mWindowOrganizerController.getTaskFragment(token);
    }

    void setAdjacentTaskFragment(@Nullable TaskFragment taskFragment) {
        if (mAdjacentTaskFragment == taskFragment) {
            return;
        }
        resetAdjacentTaskFragment();
        if (taskFragment != null) {
            mAdjacentTaskFragment = taskFragment;
            taskFragment.setAdjacentTaskFragment(this);
        }
    }

    void setCompanionTaskFragment(@Nullable TaskFragment companionTaskFragment) {
        mCompanionTaskFragment = companionTaskFragment;
    }

    TaskFragment getCompanionTaskFragment() {
        return mCompanionTaskFragment;
    }

    void resetAdjacentTaskFragment() {
        // Reset the adjacent TaskFragment if its adjacent TaskFragment is also this TaskFragment.
        if (mAdjacentTaskFragment != null && mAdjacentTaskFragment.mAdjacentTaskFragment == this) {
            mAdjacentTaskFragment.mAdjacentTaskFragment = null;
            mAdjacentTaskFragment.mDelayLastActivityRemoval = false;
        }
        mAdjacentTaskFragment = null;
        mDelayLastActivityRemoval = false;
    }

    void setTaskFragmentOrganizer(@NonNull TaskFragmentOrganizerToken organizer, int uid,
            @NonNull String processName) {
        mTaskFragmentOrganizer = ITaskFragmentOrganizer.Stub.asInterface(organizer.asBinder());
        mTaskFragmentOrganizerUid = uid;
        mTaskFragmentOrganizerProcessName = processName;
    }

    void onTaskFragmentOrganizerRemoved() {
        mTaskFragmentOrganizer = null;
    }

    /** Whether this TaskFragment is organized by the given {@code organizer}. */
    boolean hasTaskFragmentOrganizer(ITaskFragmentOrganizer organizer) {
        return organizer != null && mTaskFragmentOrganizer != null
                && organizer.asBinder().equals(mTaskFragmentOrganizer.asBinder());
    }

    /**
     * Returns the process of organizer if this TaskFragment is organized and the activity lives in
     * a different process than the organizer.
     */
    @Nullable
    private WindowProcessController getOrganizerProcessIfDifferent(@Nullable ActivityRecord r) {
        if ((r == null || mTaskFragmentOrganizerProcessName == null)
                || (mTaskFragmentOrganizerProcessName.equals(r.processName)
                && mTaskFragmentOrganizerUid == r.getUid())) {
            // No organizer or the process is the same.
            return null;
        }
        return mAtmService.getProcessController(mTaskFragmentOrganizerProcessName,
                mTaskFragmentOrganizerUid);
    }

    void setAnimationParams(@NonNull TaskFragmentAnimationParams animationParams) {
        mAnimationParams = animationParams;
    }

    @NonNull
    TaskFragmentAnimationParams getAnimationParams() {
        return mAnimationParams;
    }

    /** @see #mIsolatedNav */
    void setIsolatedNav(boolean isolatedNav) {
        if (!isEmbedded()) {
            return;
        }
        mIsolatedNav = isolatedNav;
    }

    /** @see #mIsolatedNav */
    boolean isIsolatedNav() {
        return isEmbedded() && mIsolatedNav;
    }

    TaskFragment getAdjacentTaskFragment() {
        return mAdjacentTaskFragment;
    }

    /** Returns the currently topmost resumed activity. */
    @Nullable
    ActivityRecord getTopResumedActivity() {
        final ActivityRecord taskFragResumedActivity = getResumedActivity();
        for (int i = getChildCount() - 1; i >= 0; --i) {
            WindowContainer<?> child = getChildAt(i);
            ActivityRecord topResumedActivity = null;
            if (taskFragResumedActivity != null && child == taskFragResumedActivity) {
                topResumedActivity = child.asActivityRecord();
            } else if (child.asTaskFragment() != null) {
                topResumedActivity = child.asTaskFragment().getTopResumedActivity();
            }
            if (topResumedActivity != null) {
                return topResumedActivity;
            }
        }
        return null;
    }

    /**
     * Returns the currently resumed activity in this TaskFragment's
     * {@link #mChildren direct children}
     */
    ActivityRecord getResumedActivity() {
        return mResumedActivity;
    }

    void setResumedActivity(ActivityRecord r, String reason) {
        warnForNonLeafTaskFragment("setResumedActivity");
        if (mResumedActivity == r) {
            return;
        }

        if (ActivityTaskManagerDebugConfig.DEBUG_ROOT_TASK) {
            Slog.d(TAG, "setResumedActivity taskFrag:" + this + " + from: "
                    + mResumedActivity + " to:" + r + " reason:" + reason);
        }

        if (r != null && mResumedActivity == null) {
            // Task is becoming active.
            getTask().touchActiveTime();
        }

        final ActivityRecord prevR = mResumedActivity;
        mResumedActivity = r;
        mTaskSupervisor.updateTopResumedActivityIfNeeded(reason);
        if (r == null && prevR.mDisplayContent != null
                && prevR.mDisplayContent.getFocusedRootTask() == null) {
            // Only need to notify DWPC when no activity will resume.
            prevR.mDisplayContent.onRunningActivityChanged();
        } else if (r != null) {
            r.mDisplayContent.onRunningActivityChanged();
        }
    }

    @VisibleForTesting
    void setPausingActivity(ActivityRecord pausing) {
        mPausingActivity = pausing;
    }

    /** Returns the currently topmost pausing activity. */
    @Nullable
    ActivityRecord getTopPausingActivity() {
        final ActivityRecord taskFragPausingActivity = getPausingActivity();
        for (int i = getChildCount() - 1; i >= 0; --i) {
            WindowContainer<?> child = getChildAt(i);
            ActivityRecord topPausingActivity = null;
            if (taskFragPausingActivity != null && child == taskFragPausingActivity) {
                topPausingActivity = child.asActivityRecord();
            } else if (child.asTaskFragment() != null) {
                topPausingActivity = child.asTaskFragment().getTopPausingActivity();
            }
            if (topPausingActivity != null) {
                return topPausingActivity;
            }
        }
        return null;
    }

    ActivityRecord getPausingActivity() {
        return mPausingActivity;
    }

    int getDisplayId() {
        final DisplayContent dc = getDisplayContent();
        return dc != null ? dc.mDisplayId : INVALID_DISPLAY;
    }

    @Nullable
    Task getTask() {
        if (asTask() != null) {
            return asTask();
        }

        TaskFragment parent = getParent() != null ? getParent().asTaskFragment() : null;
        return parent != null ? parent.getTask() : null;
    }

    @Override
    @Nullable
    TaskDisplayArea getDisplayArea() {
        return (TaskDisplayArea) super.getDisplayArea();
    }

    @Override
    public boolean isAttached() {
        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        return taskDisplayArea != null && !taskDisplayArea.isRemoved();
    }

    /**
     * Returns the root {@link TaskFragment}, which is usually also a {@link Task}.
     */
    @NonNull
    TaskFragment getRootTaskFragment() {
        final WindowContainer parent = getParent();
        if (parent == null) return this;

        final TaskFragment parentTaskFragment = parent.asTaskFragment();
        return parentTaskFragment == null ? this : parentTaskFragment.getRootTaskFragment();
    }

    @Nullable
    Task getRootTask() {
        return getRootTaskFragment().asTask();
    }

    @Override
    TaskFragment asTaskFragment() {
        return this;
    }

    @Override
    boolean isEmbedded() {
        return mIsEmbedded;
    }

    @EmbeddingCheckResult
    int isAllowedToEmbedActivity(@NonNull ActivityRecord a) {
        return isAllowedToEmbedActivity(a, mTaskFragmentOrganizerUid);
    }

    /**
     * Checks if the organized task fragment is allowed to have the specified activity, which is
     * allowed if an activity allows embedding in untrusted mode, if the trusted mode can be
     * enabled, or if the organized task fragment bounds are not
     * {@link #smallerThanMinDimension(ActivityRecord)}.
     *
     * @param uid   uid of the TaskFragment organizer.
     * @see #isAllowedToEmbedActivityInTrustedMode(ActivityRecord)
     */
    @EmbeddingCheckResult
    int isAllowedToEmbedActivity(@NonNull ActivityRecord a, int uid) {
        if (!isAllowedToEmbedActivityInUntrustedMode(a)
                && !isAllowedToEmbedActivityInTrustedMode(a, uid)) {
            return EMBEDDING_DISALLOWED_UNTRUSTED_HOST;
        }

        if (smallerThanMinDimension(a)) {
            return EMBEDDING_DISALLOWED_MIN_DIMENSION_VIOLATION;
        }

        return EMBEDDING_ALLOWED;
    }

    boolean smallerThanMinDimension(@NonNull ActivityRecord activity) {
        final Rect taskFragBounds = getBounds();
        final Task task = getTask();
        // Don't need to check if the bounds match parent Task bounds because the fallback mechanism
        // is to reparent the Activity to parent if minimum dimensions are not satisfied.
        if (task == null || taskFragBounds.equals(task.getBounds())) {
            return false;
        }
        final Point minDimensions = activity.getMinDimensions();
        if (minDimensions == null) {
            return false;
        }
        final int minWidth = minDimensions.x;
        final int minHeight = minDimensions.y;
        return taskFragBounds.width() < minWidth
                || taskFragBounds.height() < minHeight;
    }

    /**
     * Checks if the organized task fragment is allowed to embed activity in untrusted mode.
     */
    boolean isAllowedToEmbedActivityInUntrustedMode(@NonNull ActivityRecord a) {
        final WindowContainer parent = getParent();
        if (parent == null || !parent.getBounds().contains(getBounds())) {
            // Without full trust between the host and the embedded activity, we don't allow
            // TaskFragment to have bounds outside of the parent bounds.
            return false;
        }
        return (a.info.flags & FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING)
                == FLAG_ALLOW_UNTRUSTED_ACTIVITY_EMBEDDING;
    }

    boolean isAllowedToEmbedActivityInTrustedMode(@NonNull ActivityRecord a) {
        return isAllowedToEmbedActivityInTrustedMode(a, mTaskFragmentOrganizerUid);
    }

    /**
     * Checks if the organized task fragment is allowed to embed activity in fully trusted mode,
     * which means that all transactions are allowed. This is supported in the following cases:
     * <li>the activity belongs to the same app as the organizer host;</li>
     * <li>the activity has declared the organizer host as trusted explicitly via known
     * certificate.</li>
     * @param uid   uid of the TaskFragment organizer.
     */
    boolean isAllowedToEmbedActivityInTrustedMode(@NonNull ActivityRecord a, int uid) {
        if (isFullyTrustedEmbedding(a, uid)) {
            return true;
        }

        Set<String> knownActivityEmbeddingCerts = a.info.getKnownActivityEmbeddingCerts();
        if (knownActivityEmbeddingCerts.isEmpty()) {
            // An application must either declare that it allows untrusted embedding, or specify
            // a set of app certificates that are allowed to embed it in trusted mode.
            return false;
        }

        AndroidPackage hostPackage = mAtmService.getPackageManagerInternalLocked()
                .getPackage(uid);

        return hostPackage != null && hostPackage.getSigningDetails().hasAncestorOrSelfWithDigest(
                knownActivityEmbeddingCerts);
    }

    /**
     * It is fully trusted for embedding in the system app or embedding in the same app. This is
     * different from {@link #isAllowedToBeEmbeddedInTrustedMode()} since there may be a small
     * chance for a previous trusted app to start doing something bad.
     */
    private static boolean isFullyTrustedEmbedding(@NonNull ActivityRecord a, int uid) {
        // The system is trusted to embed other apps securely and for all users.
        return UserHandle.getAppId(uid) == SYSTEM_UID
                // Activities from the same UID can be embedded freely by the host.
                || a.isUid(uid);
    }

    /**
     * Checks if all activities in the task fragment are embedded as fully trusted.
     * @see #isFullyTrustedEmbedding(ActivityRecord, int)
     * @param uid   uid of the TaskFragment organizer.
     */
    boolean isFullyTrustedEmbedding(int uid) {
        // Traverse all activities to see if any of them are not fully trusted embedding.
        return !forAllActivities(r -> !isFullyTrustedEmbedding(r, uid));
    }

    /**
     * Checks if all activities in the task fragment are allowed to be embedded in trusted mode.
     * @see #isAllowedToEmbedActivityInTrustedMode(ActivityRecord)
     */
    boolean isAllowedToBeEmbeddedInTrustedMode() {
        // Traverse all activities to see if any of them are not in the trusted mode.
        return !forAllActivities(r -> !isAllowedToEmbedActivityInTrustedMode(r));
    }

    /**
     * Returns the TaskFragment that is being organized, which could be this or the ascendant
     * TaskFragment.
     */
    @Nullable
    TaskFragment getOrganizedTaskFragment() {
        if (mTaskFragmentOrganizer != null) {
            return this;
        }

        TaskFragment parentTaskFragment = getParent() != null ? getParent().asTaskFragment() : null;
        return parentTaskFragment != null ? parentTaskFragment.getOrganizedTaskFragment() : null;
    }

    /**
     * Simply check and give warning logs if this is not operated on leaf {@link TaskFragment}.
     */
    private void warnForNonLeafTaskFragment(String func) {
        if (!isLeafTaskFragment()) {
            Slog.w(TAG, func + " on non-leaf task fragment " + this);
        }
    }

    boolean hasDirectChildActivities() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).asActivityRecord() != null) {
                return true;
            }
        }
        return false;
    }

    void cleanUpActivityReferences(@NonNull ActivityRecord r) {
        if (mPausingActivity != null && mPausingActivity == r) {
            mPausingActivity = null;
        }

        if (mResumedActivity != null && mResumedActivity == r) {
            setResumedActivity(null, "cleanUpActivityReferences");
        }
        r.removeTimeouts();
    }

    /**
     * Returns whether this TaskFragment is currently forced to be hidden for any reason.
     */
    protected boolean isForceHidden() {
        return mForceHiddenFlags != 0;
    }

    /**
     * Sets/unsets the forced-hidden state flag for this task depending on {@param set}.
     * @return Whether the force hidden state changed
     */
    boolean setForceHidden(@FlagForceHidden int flags, boolean set) {
        int newFlags = mForceHiddenFlags;
        if (set) {
            newFlags |= flags;
        } else {
            newFlags &= ~flags;
        }
        if (mForceHiddenFlags == newFlags) {
            return false;
        }
        mForceHiddenFlags = newFlags;
        return true;
    }

    boolean isForceTranslucent() {
        return mForceTranslucent;
    }

    void setForceTranslucent(boolean set) {
        mForceTranslucent = set;
    }

    boolean isLeafTaskFragment() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).asTaskFragment() != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * This should be called when an child activity changes state. This should only
     * be called from
     * {@link ActivityRecord#setState(ActivityRecord.State, String)} .
     * @param record The {@link ActivityRecord} whose state has changed.
     * @param state The new state.
     * @param reason The reason for the change.
     */
    void onActivityStateChanged(ActivityRecord record, ActivityRecord.State state,
            String reason) {
        warnForNonLeafTaskFragment("onActivityStateChanged");
        if (record == mResumedActivity && state != RESUMED) {
            setResumedActivity(null, reason + " - onActivityStateChanged");
        }

        if (state == RESUMED) {
            if (ActivityTaskManagerDebugConfig.DEBUG_ROOT_TASK) {
                Slog.v(TAG, "set resumed activity to:" + record + " reason:" + reason);
            }
            setResumedActivity(record, reason + " - onActivityStateChanged");
            mTaskSupervisor.mRecentTasks.add(record.getTask());
        }

        // Update the process state for the organizer process if the activity is in a different
        // process in case the organizer process may not have activity state change in its process.
        final WindowProcessController hostProcess = getOrganizerProcessIfDifferent(record);
        if (hostProcess != null) {
            mTaskSupervisor.onProcessActivityStateChanged(hostProcess, false /* forceBatch */);
            hostProcess.updateProcessInfo(false /* updateServiceConnectionActivities */,
                    true /* activityChange */, true /* updateOomAdj */,
                    false /* addPendingTopUid */);
        }
    }

    /**
     * Resets local parameters because an app's activity died.
     * @param app The app of the activity that died.
     * @return {@code true} if the process of the pausing activity is died.
     */
    boolean handleAppDied(WindowProcessController app) {
        warnForNonLeafTaskFragment("handleAppDied");
        boolean isPausingDied = false;
        if (mPausingActivity != null && mPausingActivity.app == app) {
            ProtoLog.v(WM_DEBUG_STATES, "App died while pausing: %s",
                    mPausingActivity);
            mPausingActivity = null;
            isPausingDied = true;
        }
        if (mLastPausedActivity != null && mLastPausedActivity.app == app) {
            mLastPausedActivity = null;
        }
        return isPausingDied;
    }

    void awakeFromSleeping() {
        if (mPausingActivity != null) {
            Slog.d(TAG, "awakeFromSleeping: previously pausing activity didn't pause");
            mPausingActivity.activityPaused(true);
        }
    }

    /**
     * Tries to put the activities in the task fragment to sleep.
     *
     * If the task fragment is not in a state where its activities can be put to sleep, this
     * function will start any necessary actions to move the task fragment into such a state.
     * It is expected that this function get called again when those actions complete.
     *
     * @param shuttingDown {@code true} when the called because the device is shutting down.
     * @return true if the root task finished going to sleep, false if the root task only started
     * the process of going to sleep (checkReadyForSleep will be called when that process finishes).
     */
    boolean sleepIfPossible(boolean shuttingDown) {
        boolean shouldSleep = true;
        if (mResumedActivity != null) {
            // Still have something resumed; can't sleep until it is paused.
            ProtoLog.v(WM_DEBUG_STATES, "Sleep needs to pause %s", mResumedActivity);
            startPausing(false /* userLeaving */, true /* uiSleeping */, null /* resuming */,
                    "sleep");
            shouldSleep = false;
        } else if (mPausingActivity != null) {
            // Still waiting for something to pause; can't sleep yet.
            ProtoLog.v(WM_DEBUG_STATES, "Sleep still waiting to pause %s", mPausingActivity);
            shouldSleep = false;
        }

        if (!shuttingDown) {
            if (containsStoppingActivity()) {
                // Still need to tell some activities to stop; can't sleep yet.
                ProtoLog.v(WM_DEBUG_STATES, "Sleep still need to stop %d activities",
                        mTaskSupervisor.mStoppingActivities.size());

                mTaskSupervisor.scheduleIdle();
                shouldSleep = false;
            }
        }

        if (shouldSleep) {
            updateActivityVisibilities(null /* starting */, 0 /* configChanges */,
                    !PRESERVE_WINDOWS, true /* notifyClients */);
        }

        return shouldSleep;
    }

    private boolean containsStoppingActivity() {
        for (int i = mTaskSupervisor.mStoppingActivities.size() - 1; i >= 0; --i) {
            ActivityRecord r = mTaskSupervisor.mStoppingActivities.get(i);
            if (r.getTaskFragment() == this) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the TaskFragment is translucent and can have other contents visible behind
     * it if needed. A TaskFragment is considered translucent if it don't contain a visible or
     * starting (about to be visible) activity that is fullscreen (opaque).
     * @param starting The currently starting activity or null if there is none.
     */
    boolean isTranslucent(@Nullable ActivityRecord starting) {
        if (!isAttached() || isForceHidden() || isForceTranslucent()) {
            return true;
        }
        // A TaskFragment isn't translucent if it has at least one visible activity that occludes
        // this TaskFragment.
        return mTaskSupervisor.mOpaqueActivityHelper.getVisibleOpaqueActivity(this,
                starting, true /* ignoringKeyguard */) == null;
    }

    /**
     * Whether the TaskFragment should be treated as translucent for the current transition.
     * This is different from {@link #isTranslucent(ActivityRecord)} as this function also checks
     * finishing activities when the TaskFragment itself is becoming invisible.
     */
    boolean isTranslucentForTransition() {
        if (!isAttached() || isForceHidden() || isForceTranslucent()) {
            return true;
        }
        // Including finishing Activity if the TaskFragment is becoming invisible in the transition.
        return mTaskSupervisor.mOpaqueActivityHelper.getOpaqueActivity(this,
                true /* ignoringKeyguard */) == null;
    }

    /**
     * Like {@link  #isTranslucent(ActivityRecord)} but evaluating the actual visibility of the
     * windows rather than their visibility ignoring keyguard.
     */
    boolean isTranslucentAndVisible() {
        if (!isAttached() || isForceHidden() || isForceTranslucent()) {
            return true;
        }
        return mTaskSupervisor.mOpaqueActivityHelper.getVisibleOpaqueActivity(this, null,
                false /* ignoringKeyguard */) == null;
    }

    ActivityRecord getTopNonFinishingActivity() {
        return getTopNonFinishingActivity(true /* includeOverlays */);
    }

    /**
     * Returns the top-most non-finishing activity, even if the activity is NOT ok to show to
     * the current user.
     * @param includeOverlays whether the task overlay activity should be included.
     * @see #topRunningActivity(boolean)
     */
    ActivityRecord getTopNonFinishingActivity(boolean includeOverlays) {
        // Split into 2 to avoid object creation due to variable capture.
        if (includeOverlays) {
            return getActivity((r) -> !r.finishing);
        }
        return getActivity((r) -> !r.finishing && !r.isTaskOverlay());
    }

    ActivityRecord topRunningActivity() {
        return topRunningActivity(false /* focusableOnly */);
    }

    /**
     * Returns the top-most running activity, which the activity is non-finishing and ok to show
     * to the current user.
     *
     * @see ActivityRecord#canBeTopRunning()
     */
    ActivityRecord topRunningActivity(boolean focusableOnly) {
        // Split into 2 to avoid object creation due to variable capture.
        if (focusableOnly) {
            return getActivity((r) -> r.canBeTopRunning() && r.isFocusable());
        }
        return getActivity(ActivityRecord::canBeTopRunning);
    }

    /**
     * Reports non-finishing activity count including this TaskFragment's child embedded
     * TaskFragments' children activities.
     */
    int getNonFinishingActivityCount() {
        final int[] runningActivityCount = new int[1];
        forAllActivities(a -> {
            if (!a.finishing) {
                runningActivityCount[0]++;
            }
        });
        return runningActivityCount[0];
    }

    /**
     * Returns {@code true} if there's any non-finishing direct children activity, which is not
     * embedded in TaskFragments
     */
    boolean hasNonFinishingDirectActivity() {
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final ActivityRecord activity = getChildAt(i).asActivityRecord();
            if (activity != null && !activity.finishing) {
                return true;
            }
        }
        return false;
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
    int getVisibility(ActivityRecord starting) {
        if (!isAttached() || isForceHidden()) {
            return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
        }

        if (isTopActivityLaunchedBehind()) {
            return TASK_FRAGMENT_VISIBILITY_VISIBLE;
        }
        final WindowContainer<?> parent = getParent();
        final Task thisTask = asTask();
        if (thisTask != null && parent.asTask() == null
                && mTransitionController.isTransientVisible(thisTask)) {
            // Keep transient-hide root tasks visible. Non-root tasks still follow standard rule.
            return TASK_FRAGMENT_VISIBILITY_VISIBLE;
        }

        boolean gotTranslucentFullscreen = false;
        boolean gotTranslucentAdjacent = false;
        boolean shouldBeVisible = true;

        // This TaskFragment is only considered visible if all its parent TaskFragments are
        // considered visible, so check the visibility of all ancestor TaskFragment first.
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
        for (int i = parent.getChildCount() - 1; i >= 0; --i) {
            final WindowContainer other = parent.getChildAt(i);
            if (other == null) continue;

            final boolean hasRunningActivities = hasRunningActivity(other);
            if (other == this) {
                if (!adjacentTaskFragments.isEmpty() && !gotTranslucentAdjacent) {
                    // The z-order of this TaskFragment is in middle of two adjacent TaskFragments
                    // and it cannot be visible if the TaskFragment on top is not translucent and
                    // is occluding this one.
                    mTmpRect.set(getBounds());
                    for (int j = adjacentTaskFragments.size() - 1; j >= 0; --j) {
                        final TaskFragment taskFragment = adjacentTaskFragments.get(j);
                        final TaskFragment adjacentTaskFragment =
                                taskFragment.mAdjacentTaskFragment;
                        if (adjacentTaskFragment == this) {
                            continue;
                        }
                        if (mTmpRect.intersect(taskFragment.getBounds())
                                || mTmpRect.intersect(adjacentTaskFragment.getBounds())) {
                            return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
                        }
                    }
                }
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
            if (otherWindowingMode == WINDOWING_MODE_FULLSCREEN
                    || (otherWindowingMode != WINDOWING_MODE_PINNED && other.matchParentBounds())) {
                if (isTranslucent(other, starting)) {
                    // Can be visible behind a translucent TaskFragment.
                    gotTranslucentFullscreen = true;
                    continue;
                }
                return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
            }

            final TaskFragment otherTaskFrag = other.asTaskFragment();
            if (otherTaskFrag != null && otherTaskFrag.mAdjacentTaskFragment != null) {
                if (adjacentTaskFragments.contains(otherTaskFrag.mAdjacentTaskFragment)) {
                    if (otherTaskFrag.isTranslucent(starting)
                            || otherTaskFrag.mAdjacentTaskFragment.isTranslucent(starting)) {
                        // Can be visible behind a translucent adjacent TaskFragments.
                        gotTranslucentFullscreen = true;
                        gotTranslucentAdjacent = true;
                        continue;
                    }
                    // Can not be visible behind adjacent TaskFragments.
                    return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
                } else {
                    adjacentTaskFragments.add(otherTaskFrag);
                }
            }

        }

        if (!shouldBeVisible) {
            return TASK_FRAGMENT_VISIBILITY_INVISIBLE;
        }

        // Lastly - check if there is a translucent fullscreen TaskFragment on top.
        return gotTranslucentFullscreen
                ? TASK_FRAGMENT_VISIBILITY_VISIBLE_BEHIND_TRANSLUCENT
                : TASK_FRAGMENT_VISIBILITY_VISIBLE;
    }

    private static boolean hasRunningActivity(WindowContainer wc) {
        if (wc.asTaskFragment() != null) {
            return wc.asTaskFragment().topRunningActivity() != null;
        }
        return wc.asActivityRecord() != null && !wc.asActivityRecord().finishing;
    }

    private static boolean isTranslucent(WindowContainer wc, ActivityRecord starting) {
        if (wc.asTaskFragment() != null) {
            return wc.asTaskFragment().isTranslucent(starting);
        } else if (wc.asActivityRecord() != null) {
            return !wc.asActivityRecord().occludesParent();
        }
        return false;
    }


    private boolean isTopActivityLaunchedBehind() {
        final ActivityRecord top = topRunningActivity();
        return top != null && top.mLaunchTaskBehind;
    }

    final void updateActivityVisibilities(@Nullable ActivityRecord starting, int configChanges,
            boolean preserveWindows, boolean notifyClients) {
        mTaskSupervisor.beginActivityVisibilityUpdate();
        try {
            mEnsureActivitiesVisibleHelper.process(
                    starting, configChanges, preserveWindows, notifyClients);
        } finally {
            mTaskSupervisor.endActivityVisibilityUpdate();
        }
    }

    final boolean resumeTopActivity(ActivityRecord prev, ActivityOptions options,
            boolean skipPause) {
        ActivityRecord next = topRunningActivity(true /* focusableOnly */);
        if (next == null || !next.canResumeByCompat()) {
            return false;
        }

        next.delayedResume = false;

        if (!skipPause && !mRootWindowContainer.allPausedActivitiesComplete()) {
            // If we aren't skipping pause, then we have to wait for currently pausing activities.
            ProtoLog.v(WM_DEBUG_STATES, "resumeTopActivity: Skip resume: some activity pausing.");
            return false;
        }

        final TaskDisplayArea taskDisplayArea = getDisplayArea();
        // If the top activity is the resumed one, nothing to do.
        if (mResumedActivity == next && next.isState(RESUMED)
                && taskDisplayArea.allResumedActivitiesComplete()) {
            // Ensure the visibility gets updated before execute app transition.
            taskDisplayArea.ensureActivitiesVisible(null /* starting */, 0 /* configChanges */,
                    false /* preserveWindows */, true /* notifyClients */);
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);

            // In a multi-resumed environment, like in a freeform device, the top
            // activity can be resumed, but it might not be the focused app.
            // Set focused app when top activity is resumed
            if (taskDisplayArea.inMultiWindowMode() && taskDisplayArea.mDisplayContent != null
                    && taskDisplayArea.mDisplayContent.mFocusedApp != next) {
                taskDisplayArea.mDisplayContent.setFocusedApp(next);
            }
            ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Top activity "
                    + "resumed %s", next);
            return false;
        }

        // If we are sleeping, and there is no resumed activity, and the top activity is paused,
        // well that is the state we want.
        if (mLastPausedActivity == next && shouldSleepOrShutDownActivities()) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Going to sleep and"
                    + " all paused");
            return false;
        }

        // Make sure that the user who owns this activity is started.  If not,
        // we will just leave it as is because someone should be bringing
        // another user's activities to the top of the stack.
        if (!mAtmService.mAmInternal.hasStartedUserState(next.mUserId)) {
            Slog.w(TAG, "Skipping resume of top activity " + next
                    + ": user " + next.mUserId + " is stopped");
            return false;
        }

        // The activity may be waiting for stop, but that is no longer
        // appropriate for it.
        mTaskSupervisor.mStoppingActivities.remove(next);

        if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Resuming " + next);

        mTaskSupervisor.setLaunchSource(next.info.applicationInfo.uid);

        ActivityRecord lastResumed = null;
        final Task lastFocusedRootTask = taskDisplayArea.getLastFocusedRootTask();
        if (lastFocusedRootTask != null && lastFocusedRootTask != getRootTaskFragment().asTask()) {
            // So, why aren't we using prev here??? See the param comment on the method. prev
            // doesn't represent the last resumed activity. However, the last focus stack does if
            // it isn't null.
            lastResumed = lastFocusedRootTask.getTopResumedActivity();
        }

        boolean pausing = !skipPause && taskDisplayArea.pauseBackTasks(next);
        if (mResumedActivity != null) {
            ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Pausing %s", mResumedActivity);
            pausing |= startPausing(mTaskSupervisor.mUserLeaving, false /* uiSleeping */,
                    next, "resumeTopActivity");
        }
        if (pausing) {
            ProtoLog.v(WM_DEBUG_STATES, "resumeTopActivity: Skip resume: need to"
                    + " start pausing");
            // At this point we want to put the upcoming activity's process
            // at the top of the LRU list, since we know we will be needing it
            // very soon and it would be a waste to let it get killed if it
            // happens to be sitting towards the end.
            if (next.attachedToProcess()) {
                next.app.updateProcessInfo(false /* updateServiceConnectionActivities */,
                        true /* activityChange */, false /* updateOomAdj */,
                        false /* addPendingTopUid */);
            } else if (!next.isProcessRunning()) {
                // Since the start-process is asynchronous, if we already know the process of next
                // activity isn't running, we can start the process earlier to save the time to wait
                // for the current activity to be paused.
                final boolean isTop = this == taskDisplayArea.getFocusedRootTask();
                mAtmService.startProcessAsync(next, false /* knownToBeDead */, isTop,
                        isTop ? HostingRecord.HOSTING_TYPE_NEXT_TOP_ACTIVITY
                                : HostingRecord.HOSTING_TYPE_NEXT_ACTIVITY);
            }
            if (lastResumed != null) {
                lastResumed.setWillCloseOrEnterPip(true);
            }
            return true;
        } else if (mResumedActivity == next && next.isState(RESUMED)
                && taskDisplayArea.allResumedActivitiesComplete()) {
            // It is possible for the activity to be resumed when we paused back stacks above if the
            // next activity doesn't have to wait for pause to complete.
            // So, nothing else to-do except:
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            executeAppTransition(options);
            ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Top activity resumed "
                    + "(dontWaitForPause) %s", next);
            return true;
        }

        // If the most recent activity was noHistory but was only stopped rather
        // than stopped+finished because the device went to sleep, we need to make
        // sure to finish it as we're making a new activity topmost.
        if (shouldSleepActivities()) {
            mTaskSupervisor.finishNoHistoryActivitiesIfNeeded(next);
        }

        if (prev != null && prev != next && next.nowVisible) {
            // The next activity is already visible, so hide the previous
            // activity's windows right now so we can show the new one ASAP.
            // We only do this if the previous is finishing, which should mean
            // it is on top of the one being resumed so hiding it quickly
            // is good.  Otherwise, we want to do the normal route of allowing
            // the resumed activity to be shown so we can decide if the
            // previous should actually be hidden depending on whether the
            // new one is found to be full-screen or not.
            if (prev.finishing) {
                prev.setVisibility(false);
                if (DEBUG_SWITCH) {
                    Slog.v(TAG_SWITCH, "Not waiting for visible to hide: " + prev
                            + ", nowVisible=" + next.nowVisible);
                }
            } else {
                if (DEBUG_SWITCH) {
                    Slog.v(TAG_SWITCH, "Previous already visible but still waiting to hide: " + prev
                            + ", nowVisible=" + next.nowVisible);
                }
            }
        }

        try {
            mTaskSupervisor.getActivityMetricsLogger()
                    .notifyBeforePackageUnstopped(next.packageName);
            mAtmService.getPackageManagerInternalLocked().notifyComponentUsed(
                    next.packageName, next.mUserId,
                    next.packageName, next.toString()); /* TODO: Verify if correct userid */
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Failed trying to unstop package "
                    + next.packageName + ": " + e);
        }

        // We are starting up the next activity, so tell the window manager
        // that the previous one will be hidden soon.  This way it can know
        // to ignore it when computing the desired screen orientation.
        boolean anim = true;
        final DisplayContent dc = taskDisplayArea.mDisplayContent;
        if (prev != null) {
            if (prev.finishing) {
                if (DEBUG_TRANSITION) {
                    Slog.v(TAG_TRANSITION, "Prepare close transition: prev=" + prev);
                }
                if (mTaskSupervisor.mNoAnimActivities.contains(prev)) {
                    anim = false;
                    dc.prepareAppTransition(TRANSIT_NONE);
                } else {
                    dc.prepareAppTransition(TRANSIT_CLOSE);
                }
                prev.setVisibility(false);
            } else {
                if (DEBUG_TRANSITION) {
                    Slog.v(TAG_TRANSITION, "Prepare open transition: prev=" + prev);
                }
                if (mTaskSupervisor.mNoAnimActivities.contains(next)) {
                    anim = false;
                    dc.prepareAppTransition(TRANSIT_NONE);
                } else {
                    dc.prepareAppTransition(TRANSIT_OPEN,
                            next.mLaunchTaskBehind ? TRANSIT_FLAG_OPEN_BEHIND : 0);
                }
            }
        } else {
            if (DEBUG_TRANSITION) Slog.v(TAG_TRANSITION, "Prepare open transition: no previous");
            if (mTaskSupervisor.mNoAnimActivities.contains(next)) {
                anim = false;
                dc.prepareAppTransition(TRANSIT_NONE);
            } else {
                dc.prepareAppTransition(TRANSIT_OPEN);
            }
        }

        if (anim) {
            next.applyOptionsAnimation();
        } else {
            next.abortAndClearOptionsAnimation();
        }

        mTaskSupervisor.mNoAnimActivities.clear();

        if (next.attachedToProcess()) {
            if (DEBUG_SWITCH) {
                Slog.v(TAG_SWITCH, "Resume running: " + next + " stopped=" + next.mAppStopped
                        + " visibleRequested=" + next.isVisibleRequested());
            }

            // If the previous activity is translucent, force a visibility update of
            // the next activity, so that it's added to WM's opening app list, and
            // transition animation can be set up properly.
            // For example, pressing Home button with a translucent activity in focus.
            // Launcher is already visible in this case. If we don't add it to opening
            // apps, maybeUpdateTransitToWallpaper() will fail to identify this as a
            // TRANSIT_WALLPAPER_OPEN animation, and run some funny animation.
            final boolean lastActivityTranslucent = inMultiWindowMode()
                    || mLastPausedActivity != null && !mLastPausedActivity.occludesParent();

            // This activity is now becoming visible.
            if (!next.isVisibleRequested() || next.mAppStopped || lastActivityTranslucent) {
                next.app.addToPendingTop();
                next.setVisibility(true);
            }

            // schedule launch ticks to collect information about slow apps.
            next.startLaunchTickingLocked();

            ActivityRecord lastResumedActivity =
                    lastFocusedRootTask == null ? null
                            : lastFocusedRootTask.getTopResumedActivity();
            final ActivityRecord.State lastState = next.getState();

            mAtmService.updateCpuStats();

            ProtoLog.v(WM_DEBUG_STATES, "Moving to RESUMED: %s (in existing)", next);

            next.setState(RESUMED, "resumeTopActivity");

            // Have the window manager re-evaluate the orientation of
            // the screen based on the new activity order.
            boolean notUpdated = true;

            // Activity should also be visible if set mLaunchTaskBehind to true (see
            // ActivityRecord#shouldBeVisibleIgnoringKeyguard()).
            if (shouldBeVisible(next)) {
                // We have special rotation behavior when here is some active activity that
                // requests specific orientation or Keyguard is locked. Make sure all activity
                // visibilities are set correctly as well as the transition is updated if needed
                // to get the correct rotation behavior. Otherwise the following call to update
                // the orientation may cause incorrect configurations delivered to client as a
                // result of invisible window resize.
                // TODO: Remove this once visibilities are set correctly immediately when
                // starting an activity.
                notUpdated = !mRootWindowContainer.ensureVisibilityAndConfig(next, getDisplayId(),
                        true /* markFrozenIfConfigChanged */, false /* deferResume */);
            }

            if (notUpdated) {
                // The configuration update wasn't able to keep the existing
                // instance of the activity, and instead started a new one.
                // We should be all done, but let's just make sure our activity
                // is still at the top and schedule another run if something
                // weird happened.
                ActivityRecord nextNext = topRunningActivity();
                ProtoLog.i(WM_DEBUG_STATES, "Activity config changed during resume: "
                        + "%s, new next: %s", next, nextNext);
                if (nextNext != next) {
                    // Do over!
                    mTaskSupervisor.scheduleResumeTopActivities();
                }
                if (!next.isVisibleRequested() || next.mAppStopped) {
                    next.setVisibility(true);
                }
                next.completeResumeLocked();
                return true;
            }

            try {
                final ClientTransaction transaction = ClientTransaction.obtain(
                        next.app.getThread());
                // Deliver all pending results.
                ArrayList<ResultInfo> a = next.results;
                if (a != null) {
                    final int size = a.size();
                    if (!next.finishing && size > 0) {
                        if (DEBUG_RESULTS) {
                            Slog.v(TAG_RESULTS, "Delivering results to " + next + ": " + a);
                        }
                        transaction.addCallback(ActivityResultItem.obtain(next.token, a));
                    }
                }

                if (next.newIntents != null) {
                    transaction.addCallback(
                            NewIntentItem.obtain(next.token, next.newIntents, true /* resume */));
                }

                // Well the app will no longer be stopped.
                // Clear app token stopped state in window manager if needed.
                next.notifyAppResumed();

                EventLogTags.writeWmResumeActivity(next.mUserId, System.identityHashCode(next),
                        next.getTask().mTaskId, next.shortComponentName);

                mAtmService.getAppWarningsLocked().onResumeActivity(next);
                next.app.setPendingUiCleanAndForceProcessStateUpTo(mAtmService.mTopProcessState);
                next.abortAndClearOptionsAnimation();
                transaction.setLifecycleStateRequest(
                        ResumeActivityItem.obtain(next.token, next.app.getReportedProcState(),
                                dc.isNextTransitionForward(), next.shouldSendCompatFakeFocus()));
                mAtmService.getLifecycleManager().scheduleTransaction(transaction);

                ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Resumed %s", next);
            } catch (Exception e) {
                // Whoops, need to restart this activity!
                ProtoLog.v(WM_DEBUG_STATES, "Resume failed; resetting state to %s: "
                        + "%s", lastState, next);
                next.setState(lastState, "resumeTopActivityInnerLocked");

                // lastResumedActivity being non-null implies there is a lastStack present.
                if (lastResumedActivity != null) {
                    lastResumedActivity.setState(RESUMED, "resumeTopActivityInnerLocked");
                }

                Slog.i(TAG, "Restarting because process died: " + next);
                if (!next.hasBeenLaunched) {
                    next.hasBeenLaunched = true;
                } else if (SHOW_APP_STARTING_PREVIEW && lastFocusedRootTask != null
                        && lastFocusedRootTask.isTopRootTaskInDisplayArea()) {
                    next.showStartingWindow(false /* taskSwitch */);
                }
                mTaskSupervisor.startSpecificActivity(next, true, false);
                return true;
            }

            // From this point on, if something goes wrong there is no way
            // to recover the activity.
            try {
                next.completeResumeLocked();
            } catch (Exception e) {
                // If any exception gets thrown, toss away this
                // activity and try the next one.
                Slog.w(TAG, "Exception thrown during resume of " + next, e);
                next.finishIfPossible("resume-exception", true /* oomAdj */);
                return true;
            }
        } else {
            // Whoops, need to restart this activity!
            if (!next.hasBeenLaunched) {
                next.hasBeenLaunched = true;
            } else {
                if (SHOW_APP_STARTING_PREVIEW) {
                    next.showStartingWindow(false /* taskSwich */);
                }
                if (DEBUG_SWITCH) Slog.v(TAG_SWITCH, "Restarting: " + next);
            }
            ProtoLog.d(WM_DEBUG_STATES, "resumeTopActivity: Restarting %s", next);
            mTaskSupervisor.startSpecificActivity(next, true, true);
        }

        return true;
    }

    boolean shouldSleepOrShutDownActivities() {
        return shouldSleepActivities() || mAtmService.mShuttingDown;
    }

    /**
     * Returns true if the TaskFragment should be visible.
     *
     * @param starting The currently starting activity or null if there is none.
     */
    boolean shouldBeVisible(ActivityRecord starting) {
        return getVisibility(starting) != TASK_FRAGMENT_VISIBILITY_INVISIBLE;
    }

    /**
     * Returns {@code true} is the activity in this TaskFragment can be resumed.
     *
     * @param starting The currently starting activity or {@code null} if there is none.
     */
    boolean canBeResumed(@Nullable ActivityRecord starting) {
        // No need to resume activity in TaskFragment that is not visible.
        return isTopActivityFocusable()
                && getVisibility(starting) == TASK_FRAGMENT_VISIBILITY_VISIBLE;
    }

    boolean isFocusableAndVisible() {
        return isTopActivityFocusable() && shouldBeVisible(null /* starting */);
    }

    final boolean startPausing(boolean uiSleeping, ActivityRecord resuming, String reason) {
        return startPausing(mTaskSupervisor.mUserLeaving, uiSleeping, resuming, reason);
    }

    /**
     * Start pausing the currently resumed activity.  It is an error to call this if there
     * is already an activity being paused or there is no resumed activity.
     *
     * @param userLeaving True if this should result in an onUserLeaving to the current activity.
     * @param uiSleeping True if this is happening with the user interface going to sleep (the
     * screen turning off).
     * @param resuming The activity we are currently trying to resume or null if this is not being
     *                 called as part of resuming the top activity, so we shouldn't try to instigate
     *                 a resume here if not null.
     * @param reason The reason of pausing the activity.
     * @return Returns true if an activity now is in the PAUSING state, and we are waiting for
     * it to tell us when it is done.
     */
    boolean startPausing(boolean userLeaving, boolean uiSleeping, ActivityRecord resuming,
            String reason) {
        if (!hasDirectChildActivities()) {
            return false;
        }

        ProtoLog.d(WM_DEBUG_STATES, "startPausing: taskFrag =%s " + "mResumedActivity=%s", this,
                mResumedActivity);

        if (mPausingActivity != null) {
            Slog.wtf(TAG, "Going to pause when pause is already pending for " + mPausingActivity
                    + " state=" + mPausingActivity.getState());
            if (!shouldSleepActivities()) {
                // Avoid recursion among check for sleep and complete pause during sleeping.
                // Because activity will be paused immediately after resume, just let pause
                // be completed by the order of activity paused from clients.
                completePause(false, resuming);
            }
        }
        ActivityRecord prev = mResumedActivity;

        if (prev == null) {
            if (resuming == null) {
                Slog.wtf(TAG, "Trying to pause when nothing is resumed");
                mRootWindowContainer.resumeFocusedTasksTopActivities();
            }
            return false;
        }

        if (prev == resuming) {
            Slog.wtf(TAG, "Trying to pause activity that is in process of being resumed");
            return false;
        }

        ProtoLog.v(WM_DEBUG_STATES, "Moving to PAUSING: %s", prev);
        mPausingActivity = prev;
        mLastPausedActivity = prev;
        if (!prev.finishing && prev.isNoHistory()
                && !mTaskSupervisor.mNoHistoryActivities.contains(prev)) {
            mTaskSupervisor.mNoHistoryActivities.add(prev);
        }
        prev.setState(PAUSING, "startPausingLocked");
        prev.getTask().touchActiveTime();

        mAtmService.updateCpuStats();

        boolean pauseImmediately = false;
        boolean shouldAutoPip = false;
        if (resuming != null) {
            // We do not want to trigger auto-PiP upon launch of a translucent activity.
            final boolean resumingOccludesParent = resuming.occludesParent();
            // Resuming the new resume activity only if the previous activity can't go into Pip
            // since we want to give Pip activities a chance to enter Pip before resuming the
            // next activity.
            final boolean lastResumedCanPip = prev.checkEnterPictureInPictureState(
                    "shouldAutoPipWhilePausing", userLeaving);

            if (ActivityTaskManagerService.isPip2ExperimentEnabled()) {
                // If a new task is being launched, then mark the existing top activity as
                // supporting picture-in-picture while pausing only if the starting activity
                // would not be considered an overlay on top of the current activity
                // (eg. not fullscreen, or the assistant)
                Task.enableEnterPipOnTaskSwitch(prev, resuming.getTask(),
                        resuming, resuming.getOptions());
            }
            if (prev.supportsEnterPipOnTaskSwitch && userLeaving
                    && resumingOccludesParent && lastResumedCanPip
                    && prev.pictureInPictureArgs.isAutoEnterEnabled()) {
                shouldAutoPip = true;
            } else if (!lastResumedCanPip) {
                // If the flag RESUME_WHILE_PAUSING is set, then continue to schedule the previous
                // activity to be paused.
                pauseImmediately = (resuming.info.flags & FLAG_RESUME_WHILE_PAUSING) != 0;
            } else {
                // The previous activity may still enter PIP even though it did not allow auto-PIP.
            }
        }

        if (prev.attachedToProcess()) {
            if (shouldAutoPip && ActivityTaskManagerService.isPip2ExperimentEnabled()) {
                prev.mPauseSchedulePendingForPip = true;
                boolean willAutoPip = mAtmService.prepareAutoEnterPictureAndPictureMode(prev);
                ProtoLog.d(WM_DEBUG_STATES, "Auto-PIP allowed, requesting PIP mode "
                        + "via requestStartTransition(): %s, willAutoPip: %b", prev, willAutoPip);
            } else if (shouldAutoPip) {
                prev.mPauseSchedulePendingForPip = true;
                boolean didAutoPip = mAtmService.enterPictureInPictureMode(
                        prev, prev.pictureInPictureArgs, false /* fromClient */);
                ProtoLog.d(WM_DEBUG_STATES, "Auto-PIP allowed, entering PIP mode "
                        + "directly: %s, didAutoPip: %b", prev, didAutoPip);
            } else {
                schedulePauseActivity(prev, userLeaving, pauseImmediately,
                        false /* autoEnteringPip */, reason);
            }
        } else {
            mPausingActivity = null;
            mLastPausedActivity = null;
            mTaskSupervisor.mNoHistoryActivities.remove(prev);
        }

        // If we are not going to sleep, we want to ensure the device is
        // awake until the next activity is started.
        if (!uiSleeping && !mAtmService.isSleepingOrShuttingDownLocked()) {
            mTaskSupervisor.acquireLaunchWakelock();
        }

        // If already entered PIP mode, no need to keep pausing.
        if (mPausingActivity != null) {
            // Have the window manager pause its key dispatching until the new
            // activity has started.  If we're pausing the activity just because
            // the screen is being turned off and the UI is sleeping, don't interrupt
            // key dispatch; the same activity will pick it up again on wakeup.
            if (!uiSleeping) {
                prev.pauseKeyDispatchingLocked();
            } else {
                ProtoLog.v(WM_DEBUG_STATES, "Key dispatch not paused for screen off");
            }

            if (pauseImmediately) {
                // If the caller said they don't want to wait for the pause, then complete
                // the pause now.
                completePause(false, resuming);
                return false;

            } else {
                prev.schedulePauseTimeout();
                // All activities will be stopped when sleeping, don't need to wait for pause.
                if (!uiSleeping) {
                    // Unset readiness since we now need to wait until this pause is complete.
                    mTransitionController.setReady(this, false /* ready */);
                }
                return true;
            }

        } else {
            // This activity either failed to schedule the pause or it entered PIP mode,
            // so just treat it as being paused now.
            ProtoLog.v(WM_DEBUG_STATES, "Activity not running or entered PiP, resuming next.");
            if (resuming == null) {
                mRootWindowContainer.resumeFocusedTasksTopActivities();
            }
            return false;
        }
    }

    void schedulePauseActivity(ActivityRecord prev, boolean userLeaving,
            boolean pauseImmediately, boolean autoEnteringPip, String reason) {
        ProtoLog.v(WM_DEBUG_STATES, "Enqueueing pending pause: %s", prev);
        try {
            prev.mPauseSchedulePendingForPip = false;
            EventLogTags.writeWmPauseActivity(prev.mUserId, System.identityHashCode(prev),
                    prev.shortComponentName, "userLeaving=" + userLeaving, reason);

            mAtmService.getLifecycleManager().scheduleTransaction(prev.app.getThread(),
                    PauseActivityItem.obtain(prev.token, prev.finishing, userLeaving,
                            prev.configChangeFlags, pauseImmediately, autoEnteringPip));
        } catch (Exception e) {
            // Ignore exception, if process died other code will cleanup.
            Slog.w(TAG, "Exception thrown during pause", e);
            mPausingActivity = null;
            mLastPausedActivity = null;
            mTaskSupervisor.mNoHistoryActivities.remove(prev);
        }
    }

    @VisibleForTesting
    void completePause(boolean resumeNext, ActivityRecord resuming) {
        // Complete the pausing process of a pausing activity, so it doesn't make sense to
        // operate on non-leaf tasks.
        // warnForNonLeafTask("completePauseLocked");

        ActivityRecord prev = mPausingActivity;
        ProtoLog.v(WM_DEBUG_STATES, "Complete pause: %s", prev);

        if (prev != null) {
            prev.setWillCloseOrEnterPip(false);
            final boolean wasStopping = prev.isState(STOPPING);
            prev.setState(PAUSED, "completePausedLocked");
            if (prev.finishing) {
                // We will update the activity visibility later, no need to do in
                // completeFinishing(). Updating visibility here might also making the next
                // activities to be resumed, and could result in wrong app transition due to
                // lack of previous activity information.
                ProtoLog.v(WM_DEBUG_STATES, "Executing finish of activity: %s", prev);
                prev = prev.completeFinishing(false /* updateVisibility */,
                        "completePausedLocked");
            } else if (prev.attachedToProcess()) {
                ProtoLog.v(WM_DEBUG_STATES, "Enqueue pending stop if needed: %s "
                                + "wasStopping=%b visibleRequested=%b",  prev,  wasStopping,
                        prev.isVisibleRequested());
                if (prev.deferRelaunchUntilPaused) {
                    // Complete the deferred relaunch that was waiting for pause to complete.
                    ProtoLog.v(WM_DEBUG_STATES, "Re-launching after pause: %s", prev);
                    prev.relaunchActivityLocked(prev.preserveWindowOnDeferredRelaunch);
                } else if (wasStopping) {
                    // We are also stopping, the stop request must have gone soon after the pause.
                    // We can't clobber it, because the stop confirmation will not be handled.
                    // We don't need to schedule another stop, we only need to let it happen.
                    prev.setState(STOPPING, "completePausedLocked");
                } else if (!prev.isVisibleRequested() || shouldSleepOrShutDownActivities()) {
                    // Clear out any deferred client hide we might currently have.
                    prev.setDeferHidingClient(false);
                    // If we were visible then resumeTopActivities will release resources before
                    // stopping.
                    prev.addToStopping(true /* scheduleIdle */, false /* idleDelayed */,
                            "completePauseLocked");
                }
            } else {
                ProtoLog.v(WM_DEBUG_STATES, "App died during pause, not stopping: %s", prev);
                prev = null;
            }
            // It is possible the activity was freezing the screen before it was paused.
            // In that case go ahead and remove the freeze this activity has on the screen
            // since it is no longer visible.
            if (prev != null) {
                prev.stopFreezingScreenLocked(true /*force*/);
            }
            mPausingActivity = null;
        }

        if (resumeNext) {
            final Task topRootTask = mRootWindowContainer.getTopDisplayFocusedRootTask();
            if (topRootTask != null && !topRootTask.shouldSleepOrShutDownActivities()) {
                mRootWindowContainer.resumeFocusedTasksTopActivities(topRootTask, prev,
                        null /* targetOptions */);
            } else {
                // checkReadyForSleep();
                final ActivityRecord top =
                        topRootTask != null ? topRootTask.topRunningActivity() : null;
                if (top == null || (prev != null && top != prev)) {
                    // If there are no more activities available to run, do resume anyway to start
                    // something. Also if the top activity on the root task is not the just paused
                    // activity, we need to go ahead and resume it to ensure we complete an
                    // in-flight app switch.
                    mRootWindowContainer.resumeFocusedTasksTopActivities();
                }
            }
        }

        if (prev != null) {
            prev.resumeKeyDispatchingLocked();
        }

        mRootWindowContainer.ensureActivitiesVisible(resuming, 0, !PRESERVE_WINDOWS);

        // Notify when the task stack has changed, but only if visibilities changed (not just
        // focus). Also if there is an active root pinned task - we always want to notify it about
        // task stack changes, because its positioning may depend on it.
        if (mTaskSupervisor.mAppVisibilitiesChangedSinceLastPause
                || (getDisplayArea() != null && getDisplayArea().hasPinnedTask())) {
            mAtmService.getTaskChangeNotificationController().notifyTaskStackChanged();
            mTaskSupervisor.mAppVisibilitiesChangedSinceLastPause = false;
        }
    }

    @ActivityInfo.ScreenOrientation
    @Override
    int getOrientation(@ActivityInfo.ScreenOrientation int candidate) {
        if (shouldReportOrientationUnspecified()) {
            return SCREEN_ORIENTATION_UNSPECIFIED;
        }
        if (canSpecifyOrientation()) {
            return super.getOrientation(candidate);
        }
        return SCREEN_ORIENTATION_UNSET;
    }

    /**
     * Whether or not to allow this container to specify an app requested orientation.
     *
     * This is different from {@link #providesOrientation()} that
     * 1. The container may still provide an orientation even if it can't specify the app requested
     *    one, such as {@link #shouldReportOrientationUnspecified()}
     * 2. Even if the container can specify an app requested orientation, it may not be used by the
     *    parent container if it is {@link ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED}.
     */
    boolean canSpecifyOrientation() {
        final int windowingMode = getWindowingMode();
        final int activityType = getActivityType();
        return windowingMode == WINDOWING_MODE_FULLSCREEN
                || activityType == ACTIVITY_TYPE_HOME
                || activityType == ACTIVITY_TYPE_RECENTS
                || activityType == ACTIVITY_TYPE_ASSISTANT;
    }

    /**
     * Whether or not the parent container should use the orientation provided by this container
     * even if it is {@link ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED}.
     */
    @Override
    boolean providesOrientation() {
        return super.providesOrientation() || shouldReportOrientationUnspecified();
    }

    private boolean shouldReportOrientationUnspecified() {
        // Apps and their containers are not allowed to specify orientation from adjacent
        // TaskFragment.
        return getAdjacentTaskFragment() != null && isVisibleRequested();
    }

    @Override
    void forAllTaskFragments(Consumer<TaskFragment> callback, boolean traverseTopToBottom) {
        super.forAllTaskFragments(callback, traverseTopToBottom);
        callback.accept(this);
    }

    @Override
    void forAllLeafTaskFragments(Consumer<TaskFragment> callback, boolean traverseTopToBottom) {
        final int count = mChildren.size();
        boolean isLeafTaskFrag = true;
        if (traverseTopToBottom) {
            for (int i = count - 1; i >= 0; --i) {
                final TaskFragment child = mChildren.get(i).asTaskFragment();
                if (child != null) {
                    isLeafTaskFrag = false;
                    child.forAllLeafTaskFragments(callback, traverseTopToBottom);
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                final TaskFragment child = mChildren.get(i).asTaskFragment();
                if (child != null) {
                    isLeafTaskFrag = false;
                    child.forAllLeafTaskFragments(callback, traverseTopToBottom);
                }
            }
        }
        if (isLeafTaskFrag) callback.accept(this);
    }

    @Override
    boolean forAllLeafTaskFragments(Predicate<TaskFragment> callback) {
        boolean isLeafTaskFrag = true;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final TaskFragment child = mChildren.get(i).asTaskFragment();
            if (child != null) {
                isLeafTaskFrag = false;
                if (child.forAllLeafTaskFragments(callback)) {
                    return true;
                }
            }
        }
        if (isLeafTaskFrag) {
            return callback.test(this);
        }
        return false;
    }

    void addChild(ActivityRecord r) {
        addChild(r, POSITION_TOP);
    }

    @Override
    void addChild(WindowContainer child, int index) {
        ActivityRecord r = topRunningActivity();
        mClearedTaskForReuse = false;
        mClearedTaskFragmentForPip = false;
        mClearedForReorderActivityToFront = false;

        final ActivityRecord addingActivity = child.asActivityRecord();
        final boolean isAddingActivity = addingActivity != null;
        final Task task = isAddingActivity ? getTask() : null;

        // If this task had any activity before we added this one.
        boolean taskHadActivity = task != null && task.getTopMostActivity() != null;
        // getActivityType() looks at the top child, so we need to read the type before adding
        // a new child in case the new child is on top and UNDEFINED.
        final int activityType = task != null ? task.getActivityType() : ACTIVITY_TYPE_UNDEFINED;

        super.addChild(child, index);

        if (isAddingActivity && task != null) {
            // TODO(b/207481538): temporary per-activity screenshoting
            if (r != null && BackNavigationController.isScreenshotEnabled()) {
                ProtoLog.v(WM_DEBUG_BACK_PREVIEW, "Screenshotting Activity %s",
                        r.mActivityComponent.flattenToString());
                Rect outBounds = r.getBounds();
                ScreenCapture.ScreenshotHardwareBuffer backBuffer = ScreenCapture.captureLayers(
                        r.mSurfaceControl,
                        new Rect(0, 0, outBounds.width(), outBounds.height()),
                        1f);
                mBackScreenshots.put(r.mActivityComponent.flattenToString(), backBuffer);
            }
            addingActivity.inHistory = true;
            task.onDescendantActivityAdded(taskHadActivity, activityType, addingActivity);
        }

        final WindowProcessController hostProcess = getOrganizerProcessIfDifferent(addingActivity);
        if (hostProcess != null) {
            hostProcess.addEmbeddedActivity(addingActivity);
        }
    }

    @Override
    void onChildPositionChanged(WindowContainer child) {
        super.onChildPositionChanged(child);

        sendTaskFragmentInfoChanged();
    }

    void executeAppTransition(ActivityOptions options) {
        // No app transition applied to the task fragment.
    }

    @Override
    RemoteAnimationTarget createRemoteAnimationTarget(
            RemoteAnimationController.RemoteAnimationRecord record) {
        final ActivityRecord activity = record.getMode() == RemoteAnimationTarget.MODE_OPENING
                // There may be a launching (e.g. trampoline or embedded) activity without a window
                // on top of the existing task which is moving to front. Exclude finishing activity
                // so the window of next activity can be chosen to create the animation target.
                ? getActivity(r -> !r.finishing && r.hasChild())
                : getTopMostActivity();
        return activity != null ? activity.createRemoteAnimationTarget(record) : null;
    }

    @Override
    boolean canCreateRemoteAnimationTarget() {
        return true;
    }

    boolean shouldSleepActivities() {
        final Task task = getRootTask();
        return task != null && task.shouldSleepActivities();
    }

    @Override
    void resolveOverrideConfiguration(Configuration newParentConfig) {
        mTmpBounds.set(getResolvedOverrideConfiguration().windowConfiguration.getBounds());
        super.resolveOverrideConfiguration(newParentConfig);
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();

        if (mRelativeEmbeddedBounds != null && !mRelativeEmbeddedBounds.isEmpty()) {
            // For embedded TaskFragment, make sure the bounds is set based on the relative bounds.
            resolvedConfig.windowConfiguration.setBounds(translateRelativeBoundsToAbsoluteBounds(
                    mRelativeEmbeddedBounds, newParentConfig.windowConfiguration.getBounds()));
        }
        int windowingMode = resolvedConfig.windowConfiguration.getWindowingMode();
        final int parentWindowingMode = newParentConfig.windowConfiguration.getWindowingMode();

        // Resolve override windowing mode to fullscreen for home task (even on freeform
        // display), or split-screen if in split-screen mode.
        if (getActivityType() == ACTIVITY_TYPE_HOME && windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = WINDOWING_MODE_FULLSCREEN;
            resolvedConfig.windowConfiguration.setWindowingMode(windowingMode);
        }

        // Do not allow tasks not support multi window to be in a multi-window mode, unless it is in
        // pinned windowing mode.
        if (!supportsMultiWindow()) {
            final int candidateWindowingMode =
                    windowingMode != WINDOWING_MODE_UNDEFINED ? windowingMode : parentWindowingMode;
            if (WindowConfiguration.inMultiWindowMode(candidateWindowingMode)
                    && candidateWindowingMode != WINDOWING_MODE_PINNED) {
                resolvedConfig.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
            }
        }

        final Task thisTask = asTask();
        if (thisTask != null) {
            thisTask.resolveLeafTaskOnlyOverrideConfigs(newParentConfig,
                    mTmpBounds /* previousBounds */);
        }
        computeConfigResourceOverrides(resolvedConfig, newParentConfig);
    }

    boolean supportsMultiWindow() {
        return supportsMultiWindowInDisplayArea(getDisplayArea());
    }

    /**
     * @return whether this task supports multi-window if it is in the given
     *         {@link TaskDisplayArea}.
     */
    boolean supportsMultiWindowInDisplayArea(@Nullable TaskDisplayArea tda) {
        if (!mAtmService.mSupportsMultiWindow) {
            return false;
        }
        if (tda == null) {
            return false;
        }
        final Task task = getTask();
        if (task == null) {
            return false;
        }
        if (!task.isResizeable() && !tda.supportsNonResizableMultiWindow()) {
            // Not support non-resizable in multi window.
            return false;
        }

        final ActivityRecord rootActivity = task.getRootActivity();
        return tda.supportsActivityMinWidthHeightMultiWindow(mMinWidth, mMinHeight,
                rootActivity != null ? rootActivity.info : null);
    }

    private int getTaskId() {
        return getTask() != null ? getTask().mTaskId : INVALID_TASK_ID;
    }

    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig) {
        computeConfigResourceOverrides(inOutConfig, parentConfig, null /* overrideDisplayInfo */,
                null /* compatInsets */);
    }

    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig, @Nullable DisplayInfo overrideDisplayInfo) {
        if (overrideDisplayInfo != null) {
            // Make sure the screen related configs can be computed by the provided display info.
            inOutConfig.screenLayout = Configuration.SCREENLAYOUT_UNDEFINED;
            invalidateAppBoundsConfig(inOutConfig);
        }
        computeConfigResourceOverrides(inOutConfig, parentConfig, overrideDisplayInfo,
                null /* compatInsets */);
    }

    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig,
            @Nullable ActivityRecord.CompatDisplayInsets compatInsets) {
        if (compatInsets != null) {
            // Make sure the app bounds can be computed by the compat insets.
            invalidateAppBoundsConfig(inOutConfig);
        }
        computeConfigResourceOverrides(inOutConfig, parentConfig, null /* overrideDisplayInfo */,
                compatInsets);
    }

    /**
     * Forces the app bounds related configuration can be computed by
     * {@link #computeConfigResourceOverrides(Configuration, Configuration, DisplayInfo,
     * ActivityRecord.CompatDisplayInsets)}.
     */
    private static void invalidateAppBoundsConfig(@NonNull Configuration inOutConfig) {
        final Rect appBounds = inOutConfig.windowConfiguration.getAppBounds();
        if (appBounds != null) {
            appBounds.setEmpty();
        }
        inOutConfig.screenWidthDp = Configuration.SCREEN_WIDTH_DP_UNDEFINED;
        inOutConfig.screenHeightDp = Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
    }

    /**
     * Calculates configuration values used by the client to get resources. This should be run
     * using app-facing bounds (bounds unmodified by animations or transient interactions).
     *
     * This assumes bounds are non-empty/null. For the null-bounds case, the caller is likely
     * configuring an "inherit-bounds" window which means that all configuration settings would
     * just be inherited from the parent configuration.
     **/
    void computeConfigResourceOverrides(@NonNull Configuration inOutConfig,
            @NonNull Configuration parentConfig, @Nullable DisplayInfo overrideDisplayInfo,
            @Nullable ActivityRecord.CompatDisplayInsets compatInsets) {
        int windowingMode = inOutConfig.windowConfiguration.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = parentConfig.windowConfiguration.getWindowingMode();
        }

        float density = inOutConfig.densityDpi;
        if (density == Configuration.DENSITY_DPI_UNDEFINED) {
            density = parentConfig.densityDpi;
        }
        density *= DisplayMetrics.DENSITY_DEFAULT_SCALE;

        // The bounds may have been overridden at this level. If the parent cannot cover these
        // bounds, the configuration is still computed according to the override bounds.
        final boolean insideParentBounds;

        final Rect parentBounds = parentConfig.windowConfiguration.getBounds();
        final Rect resolvedBounds = inOutConfig.windowConfiguration.getBounds();
        if (resolvedBounds.isEmpty()) {
            mTmpFullBounds.set(parentBounds);
            insideParentBounds = true;
        } else {
            mTmpFullBounds.set(resolvedBounds);
            insideParentBounds = parentBounds.contains(resolvedBounds);
        }

        // Non-null compatibility insets means the activity prefers to keep its original size, so
        // out bounds doesn't need to be restricted by the parent or current display
        final boolean customContainerPolicy = compatInsets != null;

        Rect outAppBounds = inOutConfig.windowConfiguration.getAppBounds();
        if (outAppBounds == null || outAppBounds.isEmpty()) {
            // App-bounds hasn't been overridden, so calculate a value for it.
            inOutConfig.windowConfiguration.setAppBounds(mTmpFullBounds);
            outAppBounds = inOutConfig.windowConfiguration.getAppBounds();

            if (!customContainerPolicy && windowingMode != WINDOWING_MODE_FREEFORM) {
                final Rect containingAppBounds;
                if (insideParentBounds) {
                    containingAppBounds = parentConfig.windowConfiguration.getAppBounds();
                } else {
                    // Restrict appBounds to display non-decor rather than parent because the
                    // override bounds are beyond the parent. Otherwise, it won't match the
                    // overridden bounds.
                    final TaskDisplayArea displayArea = getDisplayArea();
                    containingAppBounds = displayArea != null
                            ? displayArea.getWindowConfiguration().getAppBounds() : null;
                }
                if (containingAppBounds != null && !containingAppBounds.isEmpty()) {
                    outAppBounds.intersect(containingAppBounds);
                }
            }
        }

        if (inOutConfig.screenWidthDp == Configuration.SCREEN_WIDTH_DP_UNDEFINED
                || inOutConfig.screenHeightDp == Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
            if (!customContainerPolicy && WindowConfiguration.isFloating(windowingMode)) {
                mTmpNonDecorBounds.set(mTmpFullBounds);
                mTmpStableBounds.set(mTmpFullBounds);
            } else if (!customContainerPolicy
                    && (overrideDisplayInfo != null || getDisplayContent() != null)) {
                final DisplayInfo di = overrideDisplayInfo != null
                        ? overrideDisplayInfo
                        : getDisplayContent().getDisplayInfo();

                // For calculating screenWidthDp, screenWidthDp, we use the stable inset screen
                // area, i.e. the screen area without the system bars.
                // The non decor inset are areas that could never be removed in Honeycomb. See
                // {@link WindowManagerPolicy#getNonDecorInsetsLw}.
                calculateInsetFrames(mTmpNonDecorBounds, mTmpStableBounds, mTmpFullBounds, di);
            } else {
                // Apply the given non-decor and stable insets to calculate the corresponding bounds
                // for screen size of configuration.
                int rotation = inOutConfig.windowConfiguration.getRotation();
                if (rotation == ROTATION_UNDEFINED) {
                    rotation = parentConfig.windowConfiguration.getRotation();
                }
                if (rotation != ROTATION_UNDEFINED && customContainerPolicy) {
                    mTmpNonDecorBounds.set(mTmpFullBounds);
                    mTmpStableBounds.set(mTmpFullBounds);
                    compatInsets.getBoundsByRotation(mTmpBounds, rotation);
                    intersectWithInsetsIfFits(mTmpNonDecorBounds, mTmpBounds,
                            compatInsets.mNonDecorInsets[rotation]);
                    intersectWithInsetsIfFits(mTmpStableBounds, mTmpBounds,
                            compatInsets.mStableInsets[rotation]);
                    outAppBounds.set(mTmpNonDecorBounds);
                } else {
                    // Set to app bounds because it excludes decor insets.
                    mTmpNonDecorBounds.set(outAppBounds);
                    mTmpStableBounds.set(outAppBounds);
                }
            }

            if (inOutConfig.screenWidthDp == Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
                final int overrideScreenWidthDp = (int) (mTmpStableBounds.width() / density + 0.5f);
                inOutConfig.screenWidthDp = (insideParentBounds && !customContainerPolicy)
                        ? Math.min(overrideScreenWidthDp, parentConfig.screenWidthDp)
                        : overrideScreenWidthDp;
            }
            if (inOutConfig.screenHeightDp == Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
                final int overrideScreenHeightDp =
                        (int) (mTmpStableBounds.height() / density + 0.5f);
                inOutConfig.screenHeightDp = (insideParentBounds && !customContainerPolicy)
                        ? Math.min(overrideScreenHeightDp, parentConfig.screenHeightDp)
                        : overrideScreenHeightDp;
            }

            // TODO(b/238331848): Consider simplifying logic that computes smallestScreenWidthDp.
            if (inOutConfig.smallestScreenWidthDp
                    == Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED) {
                // When entering to or exiting from Pip, the PipTaskOrganizer will set the
                // windowing mode of the activity in the task to WINDOWING_MODE_FULLSCREEN and
                // temporarily set the bounds of the task to fullscreen size for transitioning.
                // It will get the wrong value if the calculation is based on this temporary
                // fullscreen bounds.
                // We should just inherit the value from parent for this temporary state.
                final boolean inPipTransition = windowingMode == WINDOWING_MODE_PINNED
                        && !mTmpFullBounds.isEmpty() && mTmpFullBounds.equals(parentBounds);
                if (WindowConfiguration.isFloating(windowingMode) && !inPipTransition) {
                    // For floating tasks, calculate the smallest width from the bounds of the
                    // task, because they should not be affected by insets.
                    inOutConfig.smallestScreenWidthDp = (int) (0.5f
                            + Math.min(mTmpFullBounds.width(), mTmpFullBounds.height()) / density);
                } else if (windowingMode == WINDOWING_MODE_MULTI_WINDOW && mIsEmbedded
                        && insideParentBounds && !resolvedBounds.equals(parentBounds)) {
                    // For embedded TFs, the smallest width should be updated. Otherwise, inherit
                    // from the parent task would result in applications loaded wrong resource.
                    inOutConfig.smallestScreenWidthDp =
                            Math.min(inOutConfig.screenWidthDp, inOutConfig.screenHeightDp);
                }
                // otherwise, it will just inherit
            }
        }

        if (inOutConfig.orientation == ORIENTATION_UNDEFINED) {
            inOutConfig.orientation = (inOutConfig.screenWidthDp <= inOutConfig.screenHeightDp)
                    ? ORIENTATION_PORTRAIT : ORIENTATION_LANDSCAPE;
        }
        if (inOutConfig.screenLayout == Configuration.SCREENLAYOUT_UNDEFINED) {
            // For calculating screen layout, we need to use the non-decor inset screen area for the
            // calculation for compatibility reasons, i.e. screen area without system bars that
            // could never go away in Honeycomb.
            int compatScreenWidthDp = (int) (mTmpNonDecorBounds.width() / density + 0.5f);
            int compatScreenHeightDp = (int) (mTmpNonDecorBounds.height() / density + 0.5f);
            // Use overrides if provided. If both overrides are provided, mTmpNonDecorBounds is
            // undefined so it can't be used.
            if (inOutConfig.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED) {
                compatScreenWidthDp = inOutConfig.screenWidthDp;
            }
            if (inOutConfig.screenHeightDp != Configuration.SCREEN_HEIGHT_DP_UNDEFINED) {
                compatScreenHeightDp = inOutConfig.screenHeightDp;
            }
            // Reducing the screen layout starting from its parent config.
            inOutConfig.screenLayout = computeScreenLayout(parentConfig.screenLayout,
                    compatScreenWidthDp, compatScreenHeightDp);
        }
    }

    /**
     * Gets bounds with non-decor and stable insets applied respectively.
     *
     * If bounds overhangs the display, those edges will not get insets. See
     * {@link #intersectWithInsetsIfFits}
     *
     * @param outNonDecorBounds where to place bounds with non-decor insets applied.
     * @param outStableBounds where to place bounds with stable insets applied.
     * @param bounds the bounds to inset.
     */
    void calculateInsetFrames(Rect outNonDecorBounds, Rect outStableBounds, Rect bounds,
            DisplayInfo displayInfo) {
        outNonDecorBounds.set(bounds);
        outStableBounds.set(bounds);
        if (mDisplayContent == null) {
            return;
        }
        mTmpBounds.set(0, 0, displayInfo.logicalWidth, displayInfo.logicalHeight);

        final DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        final DisplayPolicy.DecorInsets.Info info = policy.getDecorInsetsInfo(
                displayInfo.rotation, displayInfo.logicalWidth, displayInfo.logicalHeight);
        intersectWithInsetsIfFits(outNonDecorBounds, mTmpBounds, info.mNonDecorInsets);
        intersectWithInsetsIfFits(outStableBounds, mTmpBounds, info.mConfigInsets);
    }

    /**
     * Intersects inOutBounds with intersectBounds-intersectInsets. If inOutBounds is larger than
     * intersectBounds on a side, then the respective side will not be intersected.
     *
     * The assumption is that if inOutBounds is initially larger than intersectBounds, then the
     * inset on that side is no-longer applicable. This scenario happens when a task's minimal
     * bounds are larger than the provided parent/display bounds.
     *
     * @param inOutBounds the bounds to intersect.
     * @param intersectBounds the bounds to intersect with.
     * @param intersectInsets insets to apply to intersectBounds before intersecting.
     */
    static void intersectWithInsetsIfFits(
            Rect inOutBounds, Rect intersectBounds, Rect intersectInsets) {
        if (inOutBounds.right <= intersectBounds.right) {
            inOutBounds.right =
                    Math.min(intersectBounds.right - intersectInsets.right, inOutBounds.right);
        }
        if (inOutBounds.bottom <= intersectBounds.bottom) {
            inOutBounds.bottom =
                    Math.min(intersectBounds.bottom - intersectInsets.bottom, inOutBounds.bottom);
        }
        if (inOutBounds.left >= intersectBounds.left) {
            inOutBounds.left =
                    Math.max(intersectBounds.left + intersectInsets.left, inOutBounds.left);
        }
        if (inOutBounds.top >= intersectBounds.top) {
            inOutBounds.top =
                    Math.max(intersectBounds.top + intersectInsets.top, inOutBounds.top);
        }
    }

    @Override
    public int getActivityType() {
        final int applicationType = super.getActivityType();
        if (applicationType != ACTIVITY_TYPE_UNDEFINED || !hasChild()) {
            return applicationType;
        }
        final ActivityRecord activity = getTopMostActivity();
        return activity != null ? activity.getActivityType() : getTopChild().getActivityType();
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        super.onConfigurationChanged(newParentConfig);
        updateOrganizedTaskFragmentSurface();
        sendTaskFragmentInfoChanged();
    }

    void deferOrganizedTaskFragmentSurfaceUpdate() {
        mDelayOrganizedTaskFragmentSurfaceUpdate = true;
    }

    void continueOrganizedTaskFragmentSurfaceUpdate() {
        mDelayOrganizedTaskFragmentSurfaceUpdate = false;
        updateOrganizedTaskFragmentSurface();
    }

    /**
     * TaskFragmentOrganizer doesn't have access to the surface for security reasons, so we need to
     * update its surface on the server side if it is not collected for Shell or in pending
     * animation.
     */
    void updateOrganizedTaskFragmentSurface() {
        if (mDelayOrganizedTaskFragmentSurfaceUpdate || mTaskFragmentOrganizer == null) {
            return;
        }
        if (mTransitionController.isShellTransitionsEnabled()
                && !mTransitionController.isCollecting(this)) {
            // TaskFragmentOrganizer doesn't have access to the surface for security reasons, so
            // update the surface here if it is not collected by Shell transition.
            updateOrganizedTaskFragmentSurfaceUnchecked();
        } else if (!mTransitionController.isShellTransitionsEnabled() && !isAnimating()) {
            // Update the surface here instead of in the organizer so that we can make sure
            // it can be synced with the surface freezer for legacy app transition.
            updateOrganizedTaskFragmentSurfaceUnchecked();
        }
    }

    private void updateOrganizedTaskFragmentSurfaceUnchecked() {
        final SurfaceControl.Transaction t = getSyncTransaction();
        updateSurfacePosition(t);
        updateOrganizedTaskFragmentSurfaceSize(t, false /* forceUpdate */);
    }

    /** Updates the surface size so that the sub windows cannot be shown out of bounds. */
    private void updateOrganizedTaskFragmentSurfaceSize(SurfaceControl.Transaction t,
            boolean forceUpdate) {
        if (mTaskFragmentOrganizer == null) {
            // We only want to update for organized TaskFragment. Task will handle itself.
            return;
        }
        if (mSurfaceControl == null || mSurfaceAnimator.hasLeash() || mSurfaceFreezer.hasLeash()) {
            return;
        }

        // If this TaskFragment is closing while resizing, crop to the starting bounds instead.
        final Rect bounds = isClosingWhenResizing()
                ? mDisplayContent.mClosingChangingContainers.get(this)
                : getBounds();
        final int width = bounds.width();
        final int height = bounds.height();
        if (!forceUpdate && width == mLastSurfaceSize.x && height == mLastSurfaceSize.y) {
            return;
        }
        t.setWindowCrop(mSurfaceControl, width, height);
        mLastSurfaceSize.set(width, height);
    }

    @Override
    public void onAnimationLeashCreated(SurfaceControl.Transaction t, SurfaceControl leash) {
        super.onAnimationLeashCreated(t, leash);
        // Reset surface bounds for animation. It will be taken care by the animation leash, and
        // reset again onAnimationLeashLost.
        if (mTaskFragmentOrganizer != null
                && (mLastSurfaceSize.x != 0 || mLastSurfaceSize.y != 0)) {
            t.setWindowCrop(mSurfaceControl, 0, 0);
            final SurfaceControl.Transaction syncTransaction = getSyncTransaction();
            if (t != syncTransaction) {
                // Avoid restoring to old window crop if the sync transaction is applied later.
                syncTransaction.setWindowCrop(mSurfaceControl, 0, 0);
            }
            mLastSurfaceSize.set(0, 0);
        }
    }

    @Override
    public void onAnimationLeashLost(SurfaceControl.Transaction t) {
        super.onAnimationLeashLost(t);
        // Update the surface bounds after animation.
        if (mTaskFragmentOrganizer != null) {
            updateOrganizedTaskFragmentSurfaceSize(t, true /* forceUpdate */);
        }
    }

    /**
     * Gets the relative bounds of this embedded TaskFragment. This should only be called on
     * embedded TaskFragment.
     */
    @NonNull
    Rect getRelativeEmbeddedBounds() {
        if (mRelativeEmbeddedBounds == null) {
            throw new IllegalStateException("The TaskFragment is not embedded");
        }
        return mRelativeEmbeddedBounds;
    }

    /**
     * Translates the given relative bounds to screen space based on the given parent bounds.
     * When the relative bounds is outside of the parent bounds, it will be adjusted to fit the Task
     * bounds.
     */
    Rect translateRelativeBoundsToAbsoluteBounds(@NonNull Rect relativeBounds,
            @NonNull Rect parentBounds) {
        if (relativeBounds.isEmpty()) {
            mTmpAbsBounds.setEmpty();
            return mTmpAbsBounds;
        }
        // Translate the relative bounds to absolute bounds.
        mTmpAbsBounds.set(relativeBounds);
        mTmpAbsBounds.offset(parentBounds.left, parentBounds.top);

        if (!isAllowedToBeEmbeddedInTrustedMode() && !parentBounds.contains(mTmpAbsBounds)) {
            // For untrusted embedding, we want to make sure the embedded bounds will never go
            // outside of the Task bounds.
            // We expect the organizer to update the bounds after receiving the Task bounds changed,
            // so skip trusted embedding to avoid unnecessary configuration change before organizer
            // requests a new bounds.
            // When the requested TaskFragment bounds is outside of Task bounds, try use the
            // intersection.
            // This can happen when the Task resized before the TaskFragmentOrganizer request.
            if (!mTmpAbsBounds.intersect(parentBounds)) {
                // Use empty bounds to fill Task if there is no intersection.
                mTmpAbsBounds.setEmpty();
            }
        }
        return mTmpAbsBounds;
    }

    void recomputeConfiguration() {
        onRequestedOverrideConfigurationChanged(getRequestedOverrideConfiguration());
    }

    /**
     * Sets the relative bounds in parent coordinate for this embedded TaskFragment.
     * This will not override the requested bounds, and the actual bounds will be calculated in
     * {@link #resolveOverrideConfiguration}, so that it makes sure to record and use the relative
     * bounds that is set by the organizer until the organizer requests a new relative bounds.
     */
    void setRelativeEmbeddedBounds(@NonNull Rect relativeEmbeddedBounds) {
        if (mRelativeEmbeddedBounds == null) {
            throw new IllegalStateException("The TaskFragment is not embedded");
        }
        if (mRelativeEmbeddedBounds.equals(relativeEmbeddedBounds)) {
            return;
        }
        mRelativeEmbeddedBounds.set(relativeEmbeddedBounds);
    }

    /**
     * Updates the record of relative bounds of this embedded TaskFragment, and checks whether we
     * should prepare a transition for the bounds change.
     */
    boolean shouldStartChangeTransition(@NonNull Rect absStartBounds,
            @NonNull Rect relStartBounds) {
        if (mTaskFragmentOrganizer == null || !canStartChangeTransition()) {
            return false;
        }

        if (mTransitionController.isShellTransitionsEnabled()) {
            // For Shell transition, the change will be collected anyway, so only take snapshot when
            // the bounds are resized.
            final Rect endBounds = getConfiguration().windowConfiguration.getBounds();
            return endBounds.width() != absStartBounds.width()
                    || endBounds.height() != absStartBounds.height();
        } else {
            // For legacy transition, we need to trigger a change transition as long as the bounds
            // is changed, even if it is not resized.
            return !relStartBounds.equals(mRelativeEmbeddedBounds);
        }
    }

    @Override
    boolean canStartChangeTransition() {
        final Task task = getTask();
        // Skip change transition when the Task is drag resizing.
        return task != null && !task.isDragResizing() && super.canStartChangeTransition();
    }

    /**
     * Returns {@code true} if the starting bounds of the closing organized TaskFragment is
     * recorded. Otherwise, return {@code false}.
     */
    boolean setClosingChangingStartBoundsIfNeeded() {
        if (isOrganizedTaskFragment() && mDisplayContent != null
                && mDisplayContent.mChangingContainers.remove(this)) {
            mDisplayContent.mClosingChangingContainers.put(
                    this, new Rect(mSurfaceFreezer.mFreezeBounds));
            return true;
        }
        return false;
    }

    @Override
    boolean isSyncFinished(BLASTSyncEngine.SyncGroup group) {
        return super.isSyncFinished(group) && isReadyToTransit();
    }

    @Override
    void setSurfaceControl(SurfaceControl sc) {
        super.setSurfaceControl(sc);
        if (mTaskFragmentOrganizer != null) {
            updateOrganizedTaskFragmentSurfaceUnchecked();
            // If the TaskFragmentOrganizer was set before we created the SurfaceControl, we need to
            // emit the callbacks now.
            sendTaskFragmentAppeared();
        }
    }

    void sendTaskFragmentInfoChanged() {
        if (mTaskFragmentOrganizer != null) {
            mTaskFragmentOrganizerController
                    .onTaskFragmentInfoChanged(mTaskFragmentOrganizer, this);
        }
    }

    void sendTaskFragmentParentInfoChanged() {
        final Task parentTask = getParent().asTask();
        if (mTaskFragmentOrganizer != null && parentTask != null) {
            mTaskFragmentOrganizerController
                    .onTaskFragmentParentInfoChanged(mTaskFragmentOrganizer, parentTask);
        }
    }

    private void sendTaskFragmentAppeared() {
        if (mTaskFragmentOrganizer != null) {
            mTaskFragmentOrganizerController.onTaskFragmentAppeared(mTaskFragmentOrganizer, this);
        }
    }

    private void sendTaskFragmentVanished() {
        if (mTaskFragmentOrganizer != null) {
            mTaskFragmentOrganizerController.onTaskFragmentVanished(mTaskFragmentOrganizer, this);
        }
    }

    /**
     * Returns a {@link TaskFragmentInfo} with information from this TaskFragment. Should not be
     * called from {@link Task}.
     */
    TaskFragmentInfo getTaskFragmentInfo() {
        List<IBinder> childActivities = new ArrayList<>();
        List<IBinder> inRequestedTaskFragmentActivities = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) {
            final WindowContainer<?> wc = getChildAt(i);
            final ActivityRecord ar = wc.asActivityRecord();
            if (mTaskFragmentOrganizerUid != INVALID_UID && ar != null
                    && ar.info.processName.equals(mTaskFragmentOrganizerProcessName)
                    && ar.getUid() == mTaskFragmentOrganizerUid && !ar.finishing) {
                // Only includes Activities that belong to the organizer process for security.
                childActivities.add(ar.token);
                if (ar.mRequestedLaunchingTaskFragmentToken == mFragmentToken) {
                    inRequestedTaskFragmentActivities.add(ar.token);
                }
            }
        }
        final Point positionInParent = new Point();
        getRelativePosition(positionInParent);
        return new TaskFragmentInfo(
                mFragmentToken,
                mRemoteToken.toWindowContainerToken(),
                getConfiguration(),
                getNonFinishingActivityCount(),
                shouldBeVisible(null /* starting */),
                childActivities,
                inRequestedTaskFragmentActivities,
                positionInParent,
                mClearedTaskForReuse,
                mClearedTaskFragmentForPip,
                mClearedForReorderActivityToFront,
                calculateMinDimension());
    }

    /**
     * Calculates the minimum dimensions that this TaskFragment can be resized.
     * @see TaskFragmentInfo#getMinimumWidth()
     * @see TaskFragmentInfo#getMinimumHeight()
     */
    Point calculateMinDimension() {
        final int[] maxMinWidth = new int[1];
        final int[] maxMinHeight = new int[1];

        forAllActivities(a -> {
            if (a.finishing) {
                return;
            }
            final Point minDimensions = a.getMinDimensions();
            if (minDimensions == null) {
                return;
            }
            maxMinWidth[0] = Math.max(maxMinWidth[0], minDimensions.x);
            maxMinHeight[0] = Math.max(maxMinHeight[0], minDimensions.y);
        });
        return new Point(maxMinWidth[0], maxMinHeight[0]);
    }

    @Nullable
    IBinder getFragmentToken() {
        return mFragmentToken;
    }

    @Nullable
    ITaskFragmentOrganizer getTaskFragmentOrganizer() {
        return mTaskFragmentOrganizer;
    }

    @Override
    boolean isOrganized() {
        return mTaskFragmentOrganizer != null;
    }

    /** Whether this is an organized {@link TaskFragment} and not a {@link Task}. */
    final boolean isOrganizedTaskFragment() {
        return mTaskFragmentOrganizer != null;
    }

    /**
     * Whether this is an embedded {@link TaskFragment} that does not fill the parent {@link Task}.
     */
    boolean isEmbeddedWithBoundsOverride() {
        if (!mIsEmbedded) {
            return false;
        }
        final Task task = getTask();
        if (task == null) {
            return false;
        }
        final Rect taskBounds = task.getBounds();
        final Rect taskFragBounds = getBounds();
        return !taskBounds.equals(taskFragBounds) && taskBounds.contains(taskFragBounds);
    }

    /** Whether the Task should be visible. */
    boolean isTaskVisibleRequested() {
        final Task task = getTask();
        return task != null && task.isVisibleRequested();
    }

    boolean isReadyToTransit() {
        // We only wait when this is organized to give the organizer a chance to update.
        if (!isOrganizedTaskFragment()) {
            return true;
        }
        // We don't want to start the transition if the organized TaskFragment is empty, unless
        // it is requested to be removed.
        if (getTopNonFinishingActivity() != null || mIsRemovalRequested) {
            return true;
        }
        // Organizer shouldn't change embedded TaskFragment in PiP.
        if (isEmbeddedTaskFragmentInPip()) {
            return true;
        }
        // The TaskFragment becomes empty because the last running activity enters PiP when the Task
        // is minimized.
        if (mClearedTaskFragmentForPip && !isTaskVisibleRequested()) {
            return true;
        }
        return false;
    }

    @Override
    boolean canCustomizeAppTransition() {
        // This is only called when the app transition is going to be played by system server. In
        // this case, we should allow custom app transition for fullscreen embedded TaskFragment
        // just like Activity.
        return isEmbedded() && matchParentBounds();
    }

    /** Clear {@link #mLastPausedActivity} for all {@link TaskFragment} children */
    void clearLastPausedActivity() {
        forAllTaskFragments(taskFragment -> taskFragment.mLastPausedActivity = null);
    }

    /**
     * Sets {@link #mMinWidth} and {@link #mMinWidth} to this TaskFragment.
     * It is usually set from the parent {@link Task} when adding the TaskFragment to the window
     * hierarchy.
     */
    void setMinDimensions(int minWidth, int minHeight) {
        if (asTask() != null) {
            throw new UnsupportedOperationException("This method must not be used to Task. The "
                    + " minimum dimension of Task should be passed from Task constructor.");
        }
        mMinWidth = minWidth;
        mMinHeight = minHeight;
    }

    /**
     * Whether this is an embedded TaskFragment in PIP Task. We don't allow any client config
     * override for such TaskFragment to prevent flight with PipTaskOrganizer.
     */
    boolean isEmbeddedTaskFragmentInPip() {
        return isOrganizedTaskFragment() && getTask() != null && getTask().inPinnedWindowingMode();
    }

    boolean shouldRemoveSelfOnLastChildRemoval() {
        return !mCreatedByOrganizer || mIsRemovalRequested;
    }

    @Nullable
    HardwareBuffer getSnapshotForActivityRecord(@Nullable ActivityRecord r) {
        if (!BackNavigationController.isScreenshotEnabled()) {
            return null;
        }
        if (r != null && r.mActivityComponent != null) {
            ScreenCapture.ScreenshotHardwareBuffer backBuffer =
                    mBackScreenshots.get(r.mActivityComponent.flattenToString());
            return backBuffer != null ? backBuffer.getHardwareBuffer() : null;
        }
        return null;
    }

    @Override
    void removeChild(WindowContainer child) {
        removeChild(child, true /* removeSelfIfPossible */);
    }

    void removeChild(WindowContainer child, boolean removeSelfIfPossible) {
        super.removeChild(child);
        final ActivityRecord r = child.asActivityRecord();
        if (BackNavigationController.isScreenshotEnabled()) {
            //TODO(b/207481538) Remove once the infrastructure to support per-activity screenshot is
            // implemented
            if (r != null) {
                mBackScreenshots.remove(r.mActivityComponent.flattenToString());
            }
        }
        final WindowProcessController hostProcess = getOrganizerProcessIfDifferent(r);
        if (hostProcess != null) {
            hostProcess.removeEmbeddedActivity(r);
        }
        if (removeSelfIfPossible && shouldRemoveSelfOnLastChildRemoval() && !hasChild()) {
            removeImmediately("removeLastChild " + child);
        }
    }

    /**
     * Requests to remove this task fragment. If it doesn't have children, it is removed
     * immediately. Otherwise it will be removed until all activities are destroyed.
     *
     * @param withTransition Whether to use transition animation when removing activities. Set to
     *                       {@code false} if this is invisible to user, e.g. display removal.
     */
    void remove(boolean withTransition, String reason) {
        if (!hasChild()) {
            removeImmediately(reason);
            return;
        }
        mIsRemovalRequested = true;
        // The task order may be changed by finishIfPossible() for adjusting focus if there are
        // nested tasks, so add all activities into a list to avoid missed removals.
        final ArrayList<ActivityRecord> removingActivities = new ArrayList<>();
        forAllActivities((Consumer<ActivityRecord>) removingActivities::add);
        for (int i = removingActivities.size() - 1; i >= 0; --i) {
            final ActivityRecord r = removingActivities.get(i);
            if (withTransition && r.isVisible()) {
                r.finishIfPossible(reason, false /* oomAdj */);
            } else {
                r.destroyIfPossible(reason);
            }
        }
    }

    void setDelayLastActivityRemoval(boolean delay) {
        if (!mIsEmbedded) {
            Slog.w(TAG, "Set delaying last activity removal on a non-embedded TF.");
        }
        mDelayLastActivityRemoval = delay;
    }

    boolean isDelayLastActivityRemoval() {
        return mDelayLastActivityRemoval;
    }

    boolean shouldDeferRemoval() {
        if (!hasChild()) {
            return false;
        }
        return isExitAnimationRunningSelfOrChild();
    }

    @Override
    boolean handleCompleteDeferredRemoval() {
        if (shouldDeferRemoval()) {
            return true;
        }
        return super.handleCompleteDeferredRemoval();
    }

    /** The overridden method must call {@link #removeImmediately()} instead of super. */
    void removeImmediately(String reason) {
        Slog.d(TAG, "Remove task fragment: " + reason);
        removeImmediately();
    }

    @Override
    void removeImmediately() {
        mIsRemovalRequested = false;
        resetAdjacentTaskFragment();
        cleanUpEmbeddedTaskFragment();
        final boolean shouldExecuteAppTransition =
                mClearedTaskFragmentForPip && isTaskVisibleRequested();
        super.removeImmediately();
        sendTaskFragmentVanished();
        if (shouldExecuteAppTransition && mDisplayContent != null) {
            // When the Task is still visible, and the TaskFragment is removed because the last
            // running activity is reparenting to PiP, it is possible that no activity is getting
            // paused or resumed (having an embedded activity in split), thus we need to relayout
            // and execute it explicitly.
            mAtmService.addWindowLayoutReasons(
                    ActivityTaskManagerService.LAYOUT_REASON_VISIBILITY_CHANGED);
            mDisplayContent.executeAppTransition();
        }
    }

    /** Called on remove to cleanup. */
    private void cleanUpEmbeddedTaskFragment() {
        if (!mIsEmbedded) {
            return;
        }
        mAtmService.mWindowOrganizerController.cleanUpEmbeddedTaskFragment(this);
        final Task task = getTask();
        if (task == null) {
            return;
        }
        task.forAllLeafTaskFragments(taskFragment -> {
            if (taskFragment.getCompanionTaskFragment() == this) {
                taskFragment.setCompanionTaskFragment(null /* companionTaskFragment */);
            }
        }, false /* traverseTopToBottom */);
    }

    @Override
    Dimmer getDimmer() {
        // If this is in an embedded TaskFragment and we want the dim applies on the TaskFragment.
        if (mIsEmbedded && mEmbeddedDimArea == EMBEDDED_DIM_AREA_TASK_FRAGMENT) {
            return mDimmer;
        }

        return super.getDimmer();
    }

    /** Bounds to be used for dimming, as well as touch related tests. */
    void getDimBounds(@NonNull Rect out) {
        if (mIsEmbedded && mEmbeddedDimArea == EMBEDDED_DIM_AREA_PARENT_TASK) {
            out.set(getTask().getBounds());
        } else {
            out.set(getBounds());
        }
    }

    void setEmbeddedDimArea(@EmbeddedDimArea int embeddedDimArea) {
        mEmbeddedDimArea = embeddedDimArea;
    }

    @Override
    void prepareSurfaces() {
        if (asTask() != null) {
            super.prepareSurfaces();
            return;
        }

        mDimmer.resetDimStates();
        super.prepareSurfaces();

        final Rect dimBounds = mDimmer.getDimBounds();
        if (dimBounds != null) {
            // Bounds need to be relative, as the dim layer is a child.
            dimBounds.offsetTo(0 /* newLeft */, 0 /* newTop */);
            if (mDimmer.updateDims(getSyncTransaction())) {
                scheduleAnimation();
            }
        }
    }

    @Override
    boolean fillsParent() {
        // From the perspective of policy, we still want to report that this task fills parent
        // in fullscreen windowing mode even it doesn't match parent bounds because there will be
        // letterbox around its real content.
        return getWindowingMode() == WINDOWING_MODE_FULLSCREEN || matchParentBounds();
    }

    @Override
    protected boolean onChildVisibleRequestedChanged(@Nullable WindowContainer child) {
        if (!super.onChildVisibleRequestedChanged(child)) return false;
        // Send the info changed to update the TaskFragment visibility.
        sendTaskFragmentInfoChanged();
        return true;
    }

    @Nullable
    @Override
    TaskFragment getTaskFragment(Predicate<TaskFragment> callback) {
        final TaskFragment taskFragment = super.getTaskFragment(callback);
        if (taskFragment != null) {
            return taskFragment;
        }
        return callback.test(this) ? this : null;
    }

    /**
     * Moves the passed child to front
     * @return whether it was actually moved (vs already being top).
     */
    boolean moveChildToFront(WindowContainer newTop) {
        int origDist = getDistanceFromTop(newTop);
        positionChildAt(POSITION_TOP, newTop, false /* includeParents */);
        return getDistanceFromTop(newTop) != origDist;
    }

    String toFullString() {
        final StringBuilder sb = new StringBuilder(128);
        sb.append(this);
        sb.setLength(sb.length() - 1); // Remove tail '}'.
        if (mTaskFragmentOrganizerUid != INVALID_UID) {
            sb.append(" organizerUid=");
            sb.append(mTaskFragmentOrganizerUid);
        }
        if (mTaskFragmentOrganizerProcessName != null) {
            sb.append(" organizerProc=");
            sb.append(mTaskFragmentOrganizerProcessName);
        }
        if (mAdjacentTaskFragment != null) {
            sb.append(" adjacent=");
            sb.append(mAdjacentTaskFragment);
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public String toString() {
        return "TaskFragment{" + Integer.toHexString(System.identityHashCode(this))
                + " mode=" + WindowConfiguration.windowingModeToString(getWindowingMode()) + "}";
    }

    boolean dump(String prefix, FileDescriptor fd, PrintWriter pw, boolean dumpAll,
            boolean dumpClient, String dumpPackage, final boolean needSep, Runnable header) {
        boolean printed = false;
        Runnable headerPrinter = () -> {
            if (needSep) {
                pw.println();
            }
            if (header != null) {
                header.run();
            }

            dumpInner(prefix, pw, dumpAll, dumpPackage);
        };

        if (dumpPackage == null) {
            // If we are not filtering by package, we want to print absolutely everything,
            // so always print the header even if there are no tasks/activities inside.
            headerPrinter.run();
            headerPrinter = null;
            printed = true;
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            WindowContainer child = mChildren.get(i);
            if (child.asTaskFragment() != null) {
                printed |= child.asTaskFragment().dump(prefix + "  ", fd, pw, dumpAll,
                        dumpClient, dumpPackage, needSep, headerPrinter);
            } else if (child.asActivityRecord() != null) {
                ActivityRecord.dumpActivity(fd, pw, i, child.asActivityRecord(), prefix + "  ",
                        "Hist ", true, !dumpAll, dumpClient, dumpPackage, false, headerPrinter,
                        getTask());
            }
        }

        return printed;
    }

    void dumpInner(String prefix, PrintWriter pw, boolean dumpAll, String dumpPackage) {
        pw.print(prefix); pw.print("* "); pw.println(toFullString());
        final Rect bounds = getRequestedOverrideBounds();
        if (!bounds.isEmpty()) {
            pw.println(prefix + "  mBounds=" + bounds);
        }
        if (mIsRemovalRequested) {
            pw.println(prefix + "  mIsRemovalRequested=true");
        }
        if (dumpAll) {
            printThisActivity(pw, mLastPausedActivity, dumpPackage, false,
                    prefix + "  mLastPausedActivity: ", null);
        }
    }

    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        pw.println(prefix + "bounds=" + getBounds().toShortString()
                + (mIsolatedNav ? ", isolatedNav" : ""));
        final String doublePrefix = prefix + "  ";
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowContainer<?> child = mChildren.get(i);
            final TaskFragment tf = child.asTaskFragment();
            pw.println(prefix + "* " + (tf != null ? tf.toFullString() : child));
            // Only dump non-activity because full activity info is already printed by
            // RootWindowContainer#dumpActivities.
            if (tf != null) {
                child.dump(pw, doublePrefix, dumpAll);
            }
        }
    }

    @Override
    void writeIdentifierToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        final ActivityRecord topActivity = topRunningActivity();
        proto.write(USER_ID, topActivity != null ? topActivity.mUserId : USER_NULL);
        proto.write(TITLE, topActivity != null ? topActivity.intent.getComponent()
                .flattenToShortString() : "TaskFragment");
        proto.end(token);
    }

    @Override
    long getProtoFieldId() {
        return TASK_FRAGMENT;
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTraceLogLevel int logLevel) {
        if (logLevel == WindowTraceLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);

        super.dumpDebug(proto, WINDOW_CONTAINER, logLevel);

        proto.write(DISPLAY_ID, getDisplayId());
        proto.write(ACTIVITY_TYPE, getActivityType());
        proto.write(MIN_WIDTH, mMinWidth);
        proto.write(MIN_HEIGHT, mMinHeight);

        proto.end(token);
    }
}
