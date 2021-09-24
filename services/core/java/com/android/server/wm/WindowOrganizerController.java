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

import static android.Manifest.permission.START_TASKS_FROM_RECENTS;
import static android.app.ActivityManager.isStartResultSuccessful;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_CHILDREN_TASKS_REPARENT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_CREATE_TASK_FRAGMENT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_DELETE_TASK_FRAGMENT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_LAUNCH_TASK;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_PENDING_INTENT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REORDER;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REPARENT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REPARENT_CHILDREN;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_ADJACENT_ROOTS;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_LAUNCH_ADJACENT_FLAG_ROOT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_START_ACTIVITY_IN_TASK_FRAGMENT;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_ORGANIZER;
import static com.android.server.wm.ActivityTaskManagerService.LAYOUT_REASON_CONFIG_CHANGED;
import static com.android.server.wm.ActivityTaskSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.Task.FLAG_FORCE_HIDDEN_FOR_TASK_ORG;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.AndroidRuntimeException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.view.RemoteAnimationAdapter;
import android.view.SurfaceControl;
import android.window.IDisplayAreaOrganizerController;
import android.window.ITaskFragmentOrganizer;
import android.window.ITaskFragmentOrganizerController;
import android.window.ITaskOrganizerController;
import android.window.ITransitionPlayer;
import android.window.IWindowContainerTransactionCallback;
import android.window.IWindowOrganizerController;
import android.window.TaskFragmentCreationParams;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Server side implementation for the interface for organizing windows
 * @see android.window.WindowOrganizer
 */
class WindowOrganizerController extends IWindowOrganizerController.Stub
    implements BLASTSyncEngine.TransactionReadyListener {

    private static final String TAG = "WindowOrganizerController";

    /** Flag indicating that an applied transaction may have effected lifecycle */
    private static final int TRANSACT_EFFECTS_CLIENT_CONFIG = 1;
    private static final int TRANSACT_EFFECTS_LIFECYCLE = 1 << 1;

    /**
     * Masks specifying which configurations task-organizers can control. Incoming transactions
     * will be filtered to only include these.
     */
    static final int CONTROLLABLE_CONFIGS = ActivityInfo.CONFIG_WINDOW_CONFIGURATION
            | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE | ActivityInfo.CONFIG_SCREEN_SIZE;
    static final int CONTROLLABLE_WINDOW_CONFIGS = WindowConfiguration.WINDOW_CONFIG_BOUNDS
            | WindowConfiguration.WINDOW_CONFIG_APP_BOUNDS;

    private final ActivityTaskManagerService mService;
    private final WindowManagerGlobalLock mGlobalLock;

    private final HashMap<Integer, IWindowContainerTransactionCallback>
            mTransactionCallbacksByPendingSyncId = new HashMap();

    final TaskOrganizerController mTaskOrganizerController;
    final DisplayAreaOrganizerController mDisplayAreaOrganizerController;
    final TaskFragmentOrganizerController mTaskFragmentOrganizerController;

    final TransitionController mTransitionController;
    /**
     * A Map which manages the relationship between
     * {@link TaskFragmentCreationParams#getFragmentToken()} and {@link TaskFragment}
     */
    @VisibleForTesting
    final ArrayMap<IBinder, TaskFragment> mLaunchTaskFragments = new ArrayMap<>();

    WindowOrganizerController(ActivityTaskManagerService atm) {
        mService = atm;
        mGlobalLock = atm.mGlobalLock;
        mTaskOrganizerController = new TaskOrganizerController(mService);
        mDisplayAreaOrganizerController = new DisplayAreaOrganizerController(mService);
        mTaskFragmentOrganizerController = new TaskFragmentOrganizerController(atm);
        mTransitionController = new TransitionController(atm);
    }

    TransitionController getTransitionController() {
        return mTransitionController;
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            throw ActivityTaskManagerService.logAndRethrowRuntimeExceptionOnTransact(TAG, e);
        }
    }

    @Override
    public void applyTransaction(WindowContainerTransaction t) {
        if (t == null) {
            throw new IllegalArgumentException("Null transaction passed to applyTransaction");
        }
        enforceTaskPermission("applyTransaction()", t);
        final CallerInfo caller = new CallerInfo();
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                applyTransaction(t, -1 /*syncId*/, null /*transition*/, caller);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int applySyncTransaction(WindowContainerTransaction t,
            IWindowContainerTransactionCallback callback) {
        if (t == null) {
            throw new IllegalArgumentException("Null transaction passed to applySyncTransaction");
        }
        enforceTaskPermission("applySyncTransaction()", t);
        final CallerInfo caller = new CallerInfo();
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                /**
                 * If callback is non-null we are looking to synchronize this transaction by
                 * collecting all the results in to a SurfaceFlinger transaction and then delivering
                 * that to the given transaction ready callback. See {@link BLASTSyncEngine} for the
                 * details of the operation. But at a high level we create a sync operation with a
                 * given ID and an associated callback. Then we notify each WindowContainer in this
                 * WindowContainer transaction that it is participating in a sync operation with
                 * that ID. Once everything is notified we tell the BLASTSyncEngine "setSyncReady"
                 * which means that we have added everything to the set. At any point after this,
                 * all the WindowContainers will eventually finish applying their changes and notify
                 * the BLASTSyncEngine which will deliver the Transaction to the callback.
                 */
                int syncId = -1;
                if (callback != null) {
                    syncId = startSyncWithOrganizer(callback);
                }
                applyTransaction(t, syncId, null /*transition*/, caller);
                if (syncId >= 0) {
                    setSyncReady(syncId);
                }
                return syncId;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public IBinder startTransition(int type, @Nullable IBinder transitionToken,
            @Nullable WindowContainerTransaction t) {
        enforceTaskPermission("startTransition()");
        final CallerInfo caller = new CallerInfo();
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                Transition transition = Transition.fromBinder(transitionToken);
                // In cases where transition is already provided, the "readiness lifecycle" of the
                // transition is determined outside of this transaction. However, if this is a
                // direct call from shell, the entire transition lifecycle is contained in the
                // provided transaction and thus we can setReady immediately after apply.
                boolean needsSetReady = transition == null && t != null;
                if (transition == null) {
                    if (type < 0) {
                        throw new IllegalArgumentException("Can't create transition with no type");
                    }
                    if (mTransitionController.getTransitionPlayer() == null) {
                        Slog.w(TAG, "Using shell transitions API for legacy transitions.");
                        if (t == null) {
                            throw new IllegalArgumentException("Can't use legacy transitions in"
                                    + " compatibility mode with no WCT.");
                        }
                        applyTransaction(t, -1 /* syncId */, null, caller);
                        return null;
                    }
                    transition = mTransitionController.createTransition(type);
                }
                transition.start();
                if (t == null) {
                    t = new WindowContainerTransaction();
                }
                applyTransaction(t, -1 /*syncId*/, transition, caller);
                if (needsSetReady) {
                    transition.setAllReady();
                }
                return transition;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int startLegacyTransition(int type, @NonNull RemoteAnimationAdapter adapter,
            @NonNull IWindowContainerTransactionCallback callback,
            @NonNull WindowContainerTransaction t) {
        enforceTaskPermission("startLegacyTransition()");
        final CallerInfo caller = new CallerInfo();
        final long ident = Binder.clearCallingIdentity();
        int syncId;
        try {
            synchronized (mGlobalLock) {
                if (type < 0) {
                    throw new IllegalArgumentException("Can't create transition with no type");
                }
                if (mTransitionController.getTransitionPlayer() != null) {
                    throw new IllegalArgumentException("Can't use legacy transitions in"
                            + " when shell transitions are enabled.");
                }
                final DisplayContent dc =
                        mService.mRootWindowContainer.getDisplayContent(DEFAULT_DISPLAY);
                if (dc.mAppTransition.isTransitionSet()) {
                    // a transition already exists, so the callback probably won't be called.
                    return -1;
                }
                adapter.setCallingPidUid(caller.mPid, caller.mUid);
                dc.prepareAppTransition(type);
                dc.mAppTransition.overridePendingAppTransitionRemote(adapter, true /* sync */);
                syncId = startSyncWithOrganizer(callback);
                applyTransaction(t, syncId, null /* transition */, caller);
                setSyncReady(syncId);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return syncId;
    }

    @Override
    public int finishTransition(@NonNull IBinder transitionToken,
            @Nullable WindowContainerTransaction t,
            @Nullable IWindowContainerTransactionCallback callback) {
        enforceTaskPermission("finishTransition()");
        final CallerInfo caller = new CallerInfo();
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                int syncId = -1;
                if (t != null && callback != null) {
                    syncId = startSyncWithOrganizer(callback);
                }
                // apply the incoming transaction before finish in case it alters the visibility
                // of the participants.
                if (t != null) {
                    applyTransaction(t, syncId, null /*transition*/, caller);
                }
                getTransitionController().finishTransition(transitionToken);
                if (syncId >= 0) {
                    setSyncReady(syncId);
                }
                return syncId;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * @param syncId If non-null, this will be a sync-transaction.
     * @param transition A transition to collect changes into.
     * @param caller Info about the calling process.
     */
    private void applyTransaction(@NonNull WindowContainerTransaction t, int syncId,
            @Nullable Transition transition, @Nullable CallerInfo caller) {
        int effects = 0;
        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Apply window transaction, syncId=%d", syncId);
        mService.deferWindowLayout();
        mService.mTaskSupervisor.setDeferRootVisibilityUpdate(true /* deferUpdate */);
        try {
            if (transition != null) {
                // First check if we have a display rotation transition and if so, update it.
                final DisplayContent dc = DisplayRotation.getDisplayFromTransition(transition);
                if (dc != null && transition.mChanges.get(dc).mRotation != dc.getRotation()) {
                    // Go through all tasks and collect them before the rotation
                    // TODO(shell-transitions): move collect() to onConfigurationChange once
                    //       wallpaper handling is synchronized.
                    dc.forAllTasks(task -> {
                        if (task.isVisible()) transition.collect(task);
                    });
                    dc.getInsetsStateController().addProvidersToTransition();
                    dc.sendNewConfiguration();
                    effects |= TRANSACT_EFFECTS_LIFECYCLE;
                }
            }
            ArraySet<WindowContainer> haveConfigChanges = new ArraySet<>();
            Iterator<Map.Entry<IBinder, WindowContainerTransaction.Change>> entries =
                    t.getChanges().entrySet().iterator();
            while (entries.hasNext()) {
                final Map.Entry<IBinder, WindowContainerTransaction.Change> entry = entries.next();
                final WindowContainer wc = WindowContainer.fromBinder(entry.getKey());
                if (wc == null || !wc.isAttached()) {
                    Slog.e(TAG, "Attempt to operate on detached container: " + wc);
                    continue;
                }
                // Make sure we add to the syncSet before performing
                // operations so we don't end up splitting effects between the WM
                // pending transaction and the BLASTSync transaction.
                if (syncId >= 0) {
                    addToSyncSet(syncId, wc);
                }
                if (transition != null) transition.collect(wc);

                int containerEffect = applyWindowContainerChange(wc, entry.getValue());
                effects |= containerEffect;

                // Lifecycle changes will trigger ensureConfig for everything.
                if ((effects & TRANSACT_EFFECTS_LIFECYCLE) == 0
                        && (containerEffect & TRANSACT_EFFECTS_CLIENT_CONFIG) != 0) {
                    haveConfigChanges.add(wc);
                }
            }
            // Hierarchy changes
            final List<WindowContainerTransaction.HierarchyOp> hops = t.getHierarchyOps();
            final int hopSize = hops.size();
            if (hopSize > 0) {
                final boolean isInLockTaskMode = mService.isInLockTaskMode();
                for (int i = 0; i < hopSize; ++i) {
                    effects |= applyHierarchyOp(hops.get(i), effects, syncId, transition,
                            isInLockTaskMode, caller, t.getErrorCallbackToken(),
                            t.getTaskFragmentOrganizer());
                }
            }
            // Queue-up bounds-change transactions for tasks which are now organized. Do
            // this after hierarchy ops so we have the final organized state.
            entries = t.getChanges().entrySet().iterator();
            while (entries.hasNext()) {
                final Map.Entry<IBinder, WindowContainerTransaction.Change> entry = entries.next();
                final WindowContainer wc = WindowContainer.fromBinder(entry.getKey());
                if (wc == null || !wc.isAttached()) {
                    Slog.e(TAG, "Attempt to operate on detached container: " + wc);
                    continue;
                }
                final Task task = wc.asTask();
                final Rect surfaceBounds = entry.getValue().getBoundsChangeSurfaceBounds();
                if (task == null || !task.isAttached() || surfaceBounds == null) {
                    continue;
                }
                if (!task.isOrganized()) {
                    final Task parent = task.getParent() != null ? task.getParent().asTask() : null;
                    // Also allow direct children of created-by-organizer tasks to be
                    // controlled. In the future, these will become organized anyways.
                    if (parent == null || !parent.mCreatedByOrganizer) {
                        throw new IllegalArgumentException(
                                "Can't manipulate non-organized task surface " + task);
                    }
                }
                final SurfaceControl.Transaction sft = new SurfaceControl.Transaction();
                final SurfaceControl sc = task.getSurfaceControl();
                sft.setPosition(sc, surfaceBounds.left, surfaceBounds.top);
                if (surfaceBounds.isEmpty()) {
                    sft.setWindowCrop(sc, null);
                } else {
                    sft.setWindowCrop(sc, surfaceBounds.width(), surfaceBounds.height());
                }
                task.setMainWindowSizeChangeTransaction(sft);
            }
            if ((effects & TRANSACT_EFFECTS_LIFECYCLE) != 0) {
                mService.mTaskSupervisor.setDeferRootVisibilityUpdate(false /* deferUpdate */);
                // Already calls ensureActivityConfig
                mService.mRootWindowContainer.ensureActivitiesVisible(null, 0, PRESERVE_WINDOWS);
                mService.mRootWindowContainer.resumeFocusedTasksTopActivities();
            } else if ((effects & TRANSACT_EFFECTS_CLIENT_CONFIG) != 0) {
                final PooledConsumer f = PooledLambda.obtainConsumer(
                        ActivityRecord::ensureActivityConfiguration,
                        PooledLambda.__(ActivityRecord.class), 0,
                        true /* preserveWindow */);
                try {
                    for (int i = haveConfigChanges.size() - 1; i >= 0; --i) {
                        haveConfigChanges.valueAt(i).forAllActivities(f);
                    }
                } finally {
                    f.recycle();
                }
            }

            if ((effects & TRANSACT_EFFECTS_CLIENT_CONFIG) == 0) {
                mService.addWindowLayoutReasons(LAYOUT_REASON_CONFIG_CHANGED);
            }
        } finally {
            mService.mTaskSupervisor.setDeferRootVisibilityUpdate(false /* deferUpdate */);
            mService.continueWindowLayout();
        }
    }

    private int applyChanges(WindowContainer container, WindowContainerTransaction.Change change) {
        // The "client"-facing API should prevent bad changes; however, just in case, sanitize
        // masks here.
        final int configMask = change.getConfigSetMask() & CONTROLLABLE_CONFIGS;
        final int windowMask = change.getWindowSetMask() & CONTROLLABLE_WINDOW_CONFIGS;
        int effects = 0;
        final int windowingMode = change.getWindowingMode();
        if (configMask != 0) {
            if (windowingMode > -1 && windowingMode != container.getWindowingMode()) {
                // Special handling for when we are setting a windowingMode in the same transaction.
                // Setting the windowingMode is going to call onConfigurationChanged so we don't
                // need it called right now. Additionally, some logic requires everything in the
                // configuration to change at the same time (ie. surface-freezer requires bounds
                // and mode to change at the same time).
                final Configuration c = container.getRequestedOverrideConfiguration();
                c.setTo(change.getConfiguration(), configMask, windowMask);
            } else {
                final Configuration c =
                        new Configuration(container.getRequestedOverrideConfiguration());
                c.setTo(change.getConfiguration(), configMask, windowMask);
                container.onRequestedOverrideConfigurationChanged(c);
            }
            effects |= TRANSACT_EFFECTS_CLIENT_CONFIG;
        }
        if ((change.getChangeMask() & WindowContainerTransaction.Change.CHANGE_FOCUSABLE) != 0) {
            if (container.setFocusable(change.getFocusable())) {
                effects |= TRANSACT_EFFECTS_LIFECYCLE;
            }
        }

        if (windowingMode > -1) {
            if (mService.isInLockTaskMode()
                    && WindowConfiguration.inMultiWindowMode(windowingMode)) {
                throw new UnsupportedOperationException("Not supported to set multi-window"
                        + " windowing mode during locked task mode.");
            }

            final int prevMode = container.getWindowingMode();
            container.setWindowingMode(windowingMode);
            if (prevMode != container.getWindowingMode()) {
                // The activity in the container may become focusable or non-focusable due to
                // windowing modes changes (such as entering or leaving pinned windowing mode),
                // so also apply the lifecycle effects to this transaction.
                effects |= TRANSACT_EFFECTS_LIFECYCLE;
            }
        }
        return effects;
    }

    private int applyTaskChanges(Task tr, WindowContainerTransaction.Change c) {
        int effects = 0;
        final SurfaceControl.Transaction t = c.getBoundsChangeTransaction();

        if ((c.getChangeMask() & WindowContainerTransaction.Change.CHANGE_HIDDEN) != 0) {
            if (tr.setForceHidden(FLAG_FORCE_HIDDEN_FOR_TASK_ORG, c.getHidden())) {
                effects = TRANSACT_EFFECTS_LIFECYCLE;
            }
        }

        final int childWindowingMode = c.getActivityWindowingMode();
        if (childWindowingMode > -1) {
            tr.setActivityWindowingMode(childWindowingMode);
        }

        if (t != null) {
            tr.setMainWindowSizeChangeTransaction(t);
        }

        Rect enterPipBounds = c.getEnterPipBounds();
        if (enterPipBounds != null) {
            tr.mDisplayContent.mPinnedTaskController.setEnterPipBounds(enterPipBounds);
        }

        return effects;
    }

    private int applyDisplayAreaChanges(DisplayArea displayArea,
            WindowContainerTransaction.Change c) {
        final int[] effects = new int[1];

        if ((c.getChangeMask()
                & WindowContainerTransaction.Change.CHANGE_IGNORE_ORIENTATION_REQUEST) != 0) {
            if (displayArea.setIgnoreOrientationRequest(c.getIgnoreOrientationRequest())) {
                effects[0] |= TRANSACT_EFFECTS_LIFECYCLE;
            }
        }

        displayArea.forAllTasks(task -> {
            Task tr = (Task) task;
            if ((c.getChangeMask() & WindowContainerTransaction.Change.CHANGE_HIDDEN) != 0) {
                if (tr.setForceHidden(FLAG_FORCE_HIDDEN_FOR_TASK_ORG, c.getHidden())) {
                    effects[0] |= TRANSACT_EFFECTS_LIFECYCLE;
                }
            }
        });

        return effects[0];
    }

    private int applyHierarchyOp(WindowContainerTransaction.HierarchyOp hop, int effects,
            int syncId, @Nullable Transition transition, boolean isInLockTaskMode,
            @Nullable CallerInfo caller, @Nullable IBinder errorCallbackToken,
            @Nullable ITaskFragmentOrganizer organizer) {
        final int type = hop.getType();
        switch (type) {
            case HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT: {
                final WindowContainer wc = WindowContainer.fromBinder(hop.getContainer());
                final Task task = wc != null ? wc.asTask() : null;
                if (task != null) {
                    task.getDisplayArea().setLaunchRootTask(task,
                            hop.getWindowingModes(), hop.getActivityTypes());
                } else {
                    throw new IllegalArgumentException("Cannot set non-task as launch root: " + wc);
                }
                break;
            }
            case HIERARCHY_OP_TYPE_SET_LAUNCH_ADJACENT_FLAG_ROOT: {
                final WindowContainer wc = WindowContainer.fromBinder(hop.getContainer());
                final Task task = wc != null ? wc.asTask() : null;
                if (task == null) {
                    throw new IllegalArgumentException("Cannot set non-task as launch root: " + wc);
                } else if (!task.mCreatedByOrganizer) {
                    throw new UnsupportedOperationException(
                            "Cannot set non-organized task as adjacent flag root: " + wc);
                } else if (task.getAdjacentTaskFragment() == null) {
                    throw new UnsupportedOperationException(
                            "Cannot set non-adjacent task as adjacent flag root: " + wc);
                }

                final boolean clearRoot = hop.getToTop();
                task.getDisplayArea().setLaunchAdjacentFlagRootTask(clearRoot ? null : task);
                break;
            }
            case HIERARCHY_OP_TYPE_SET_ADJACENT_ROOTS:
                effects |= setAdjacentRootsHierarchyOp(hop);
                break;
        }
        // The following operations may change task order so they are skipped while in lock task
        // mode. The above operations are still allowed because they don't move tasks. And it may
        // be necessary such as clearing launch root after entering lock task mode.
        if (isInLockTaskMode) {
            Slog.w(TAG, "Skip applying hierarchy operation " + hop + " while in lock task mode");
            return effects;
        }

        final WindowContainer wc;
        final IBinder fragmentToken;
        switch (type) {
            case HIERARCHY_OP_TYPE_CHILDREN_TASKS_REPARENT:
                effects |= reparentChildrenTasksHierarchyOp(hop, transition, syncId);
                break;
            case HIERARCHY_OP_TYPE_REORDER:
            case HIERARCHY_OP_TYPE_REPARENT:
                wc = WindowContainer.fromBinder(hop.getContainer());
                if (wc == null || !wc.isAttached()) {
                    Slog.e(TAG, "Attempt to operate on detached container: " + wc);
                    break;
                }
                if (syncId >= 0) {
                    addToSyncSet(syncId, wc);
                }
                if (transition != null) {
                    transition.collect(wc);
                    if (hop.isReparent()) {
                        if (wc.getParent() != null) {
                            // Collect the current parent. It's visibility may change as
                            // a result of this reparenting.
                            transition.collect(wc.getParent());
                        }
                        if (hop.getNewParent() != null) {
                            final WindowContainer parentWc =
                                    WindowContainer.fromBinder(hop.getNewParent());
                            if (parentWc == null) {
                                Slog.e(TAG, "Can't resolve parent window from token");
                                break;
                            }
                            transition.collect(parentWc);
                        }
                    }
                }
                effects |= sanitizeAndApplyHierarchyOp(wc, hop);
                break;
            case HIERARCHY_OP_TYPE_LAUNCH_TASK:
                mService.mAmInternal.enforceCallingPermission(START_TASKS_FROM_RECENTS,
                        "launchTask HierarchyOp");
                final Bundle launchOpts = hop.getLaunchOptions();
                final int taskId = launchOpts.getInt(
                        WindowContainerTransaction.HierarchyOp.LAUNCH_KEY_TASK_ID);
                launchOpts.remove(WindowContainerTransaction.HierarchyOp.LAUNCH_KEY_TASK_ID);
                final SafeActivityOptions safeOptions = caller != null
                        ? SafeActivityOptions.fromBundle(launchOpts, caller.mPid, caller.mUid)
                        : SafeActivityOptions.fromBundle(launchOpts);
                mService.mTaskSupervisor.startActivityFromRecents(caller.mPid, caller.mUid,
                        taskId, safeOptions);
                break;
            case HIERARCHY_OP_TYPE_PENDING_INTENT:
                String resolvedType = hop.getActivityIntent() != null
                        ? hop.getActivityIntent().resolveTypeIfNeeded(
                                mService.mContext.getContentResolver())
                        : null;

                Bundle options = null;
                if (hop.getPendingIntent().isActivity()) {
                    // Set the context display id as preferred for this activity launches, so that
                    // it can land on caller's display. Or just brought the task to front at the
                    // display where it was on since it has higher preference.
                    ActivityOptions activityOptions = hop.getLaunchOptions() != null
                            ? new ActivityOptions(hop.getLaunchOptions())
                            : ActivityOptions.makeBasic();
                    activityOptions.setCallerDisplayId(DEFAULT_DISPLAY);
                    options = activityOptions.toBundle();
                }

                mService.mAmInternal.sendIntentSender(hop.getPendingIntent().getTarget(),
                        hop.getPendingIntent().getWhitelistToken(), 0 /* code */,
                        hop.getActivityIntent(), resolvedType, null /* finishReceiver */,
                        null /* requiredPermission */, options);
                break;
            case HIERARCHY_OP_TYPE_CREATE_TASK_FRAGMENT:
                final TaskFragmentCreationParams taskFragmentCreationOptions =
                        hop.getTaskFragmentCreationOptions();
                createTaskFragment(taskFragmentCreationOptions, errorCallbackToken);
                break;
            case HIERARCHY_OP_TYPE_DELETE_TASK_FRAGMENT:
                wc = WindowContainer.fromBinder(hop.getContainer());
                if (wc == null || !wc.isAttached()) {
                    Slog.e(TAG, "Attempt to operate on unknown or detached container: " + wc);
                    break;
                }
                final TaskFragment taskFragment = wc.asTaskFragment();
                if (taskFragment == null || taskFragment.asTask() != null) {
                    throw new IllegalArgumentException(
                            "Can only delete organized TaskFragment, but not Task.");
                }
                effects |= deleteTaskFragment(taskFragment, errorCallbackToken);
                break;
            case HIERARCHY_OP_TYPE_START_ACTIVITY_IN_TASK_FRAGMENT:
                fragmentToken = hop.getContainer();
                if (!mLaunchTaskFragments.containsKey(fragmentToken)) {
                    final Throwable exception = new IllegalArgumentException(
                            "Not allowed to operate with invalid fragment token");
                    sendTaskFragmentOperationFailure(organizer, errorCallbackToken, exception);
                    break;
                }
                final Intent activityIntent = hop.getActivityIntent();
                final Bundle activityOptions = hop.getLaunchOptions();
                final TaskFragment tf = mLaunchTaskFragments.get(fragmentToken);
                final int result = mService.getActivityStartController()
                        .startActivityInTaskFragment(tf, activityIntent, activityOptions,
                                hop.getCallingActivity());
                if (!isStartResultSuccessful(result)) {
                    sendTaskFragmentOperationFailure(tf.getTaskFragmentOrganizer(),
                            errorCallbackToken,
                            convertStartFailureToThrowable(result, activityIntent));
                }
                break;
            case HIERARCHY_OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT:
                fragmentToken = hop.getNewParent();
                final ActivityRecord activity = ActivityRecord.forTokenLocked(hop.getContainer());
                if (!mLaunchTaskFragments.containsKey(fragmentToken) || activity == null) {
                    final Throwable exception = new IllegalArgumentException(
                            "Not allowed to operate with invalid fragment token or activity.");
                    sendTaskFragmentOperationFailure(organizer, errorCallbackToken, exception);
                    break;
                }
                activity.reparent(mLaunchTaskFragments.get(fragmentToken), POSITION_TOP);
                effects |= TRANSACT_EFFECTS_LIFECYCLE;
                break;
            case HIERARCHY_OP_TYPE_REPARENT_CHILDREN:
                final WindowContainer oldParent = WindowContainer.fromBinder(hop.getContainer());
                final WindowContainer newParent = hop.getNewParent() != null
                        ? WindowContainer.fromBinder(hop.getNewParent())
                        : null;
                if (oldParent == null || !oldParent.isAttached()) {
                    Slog.e(TAG, "Attempt to operate on unknown or detached container: "
                            + oldParent);
                    break;
                }
                reparentTaskFragment(oldParent, newParent, errorCallbackToken);
                effects |= TRANSACT_EFFECTS_LIFECYCLE;
                break;
            case HIERARCHY_OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS:
                fragmentToken = hop.getContainer();
                final IBinder adjacentFragmentToken = hop.getAdjacentRoot();
                final TaskFragment tf1 = mLaunchTaskFragments.get(fragmentToken);
                final TaskFragment tf2 = adjacentFragmentToken != null
                        ? mLaunchTaskFragments.get(adjacentFragmentToken)
                        : null;
                if (tf1 == null || (adjacentFragmentToken != null && tf2 == null)) {
                    final Throwable exception = new IllegalArgumentException(
                            "Not allowed to set adjacent on invalid fragment tokens");
                    sendTaskFragmentOperationFailure(organizer, errorCallbackToken, exception);
                    break;
                }
                tf1.setAdjacentTaskFragment(tf2);
                effects |= TRANSACT_EFFECTS_LIFECYCLE;

                final Bundle bundle = hop.getLaunchOptions();
                final WindowContainerTransaction.TaskFragmentAdjacentParams adjacentParams =
                        bundle != null ? new WindowContainerTransaction.TaskFragmentAdjacentParams(
                                bundle) : null;
                if (adjacentParams == null) {
                    break;
                }

                tf1.setDelayLastActivityRemoval(
                        adjacentParams.shouldDelayPrimaryLastActivityRemoval());
                if (tf2 != null) {
                    tf2.setDelayLastActivityRemoval(
                            adjacentParams.shouldDelaySecondaryLastActivityRemoval());
                }
                break;
        }
        return effects;
    }

    private int sanitizeAndApplyHierarchyOp(WindowContainer container,
            WindowContainerTransaction.HierarchyOp hop) {
        final Task task = container.asTask();
        if (task == null) {
            throw new IllegalArgumentException("Invalid container in hierarchy op");
        }
        final DisplayContent dc = task.getDisplayContent();
        if (dc == null) {
            Slog.w(TAG, "Container is no longer attached: " + task);
            return 0;
        }
        final Task as = task;

        if (hop.isReparent()) {
            final boolean isNonOrganizedRootableTask =
                    task.isRootTask() || task.getParent().asTask().mCreatedByOrganizer;
            if (isNonOrganizedRootableTask) {
                WindowContainer newParent = hop.getNewParent() == null
                        ? dc.getDefaultTaskDisplayArea()
                        : WindowContainer.fromBinder(hop.getNewParent());
                if (newParent == null) {
                    Slog.e(TAG, "Can't resolve parent window from token");
                    return 0;
                }
                if (task.getParent() != newParent) {
                    if (newParent.asTaskDisplayArea() != null) {
                        // For now, reparenting to displayarea is different from other reparents...
                        as.reparent(newParent.asTaskDisplayArea(), hop.getToTop());
                    } else if (newParent.asTask() != null) {
                        if (newParent.inMultiWindowMode() && task.isLeafTask()) {
                            if (newParent.inPinnedWindowingMode()) {
                                Slog.w(TAG, "Can't support moving a task to another PIP window..."
                                        + " newParent=" + newParent + " task=" + task);
                                return 0;
                            }
                            if (!task.supportsMultiWindowInDisplayArea(
                                    newParent.asTask().getDisplayArea())) {
                                Slog.w(TAG, "Can't support task that doesn't support multi-window"
                                        + " mode in multi-window mode... newParent=" + newParent
                                        + " task=" + task);
                                return 0;
                            }
                        }
                        task.reparent((Task) newParent,
                                hop.getToTop() ? POSITION_TOP : POSITION_BOTTOM,
                                false /*moveParents*/, "sanitizeAndApplyHierarchyOp");
                    } else {
                        throw new RuntimeException("Can only reparent task to another task or"
                                + " taskDisplayArea, but not " + newParent);
                    }
                } else {
                    final Task rootTask = (Task) (
                            (newParent != null && !(newParent instanceof TaskDisplayArea))
                                    ? newParent : task.getRootTask());
                    as.getDisplayArea().positionChildAt(
                            hop.getToTop() ? POSITION_TOP : POSITION_BOTTOM, rootTask,
                            false /* includingParents */);
                }
            } else {
                throw new RuntimeException("Reparenting leaf Tasks is not supported now. " + task);
            }
        } else {
            task.getParent().positionChildAt(
                    hop.getToTop() ? POSITION_TOP : POSITION_BOTTOM,
                    task, false /* includingParents */);
        }
        return TRANSACT_EFFECTS_LIFECYCLE;
    }

    private int reparentChildrenTasksHierarchyOp(WindowContainerTransaction.HierarchyOp hop,
            @Nullable Transition transition, int syncId) {
        WindowContainer currentParent = hop.getContainer() != null
                ? WindowContainer.fromBinder(hop.getContainer()) : null;
        WindowContainer newParent = hop.getNewParent() != null
                ? WindowContainer.fromBinder(hop.getNewParent()) : null;
        if (currentParent == null && newParent == null) {
            throw new IllegalArgumentException("reparentChildrenTasksHierarchyOp: " + hop);
        } else if (currentParent == null) {
            currentParent = newParent.asTask().getDisplayContent().getDefaultTaskDisplayArea();
        } else if (newParent == null) {
            newParent = currentParent.asTask().getDisplayContent().getDefaultTaskDisplayArea();
        }

        if (currentParent == newParent) {
            Slog.e(TAG, "reparentChildrenTasksHierarchyOp parent not changing: " + hop);
            return 0;
        }
        if (!currentParent.isAttached()) {
            Slog.e(TAG, "reparentChildrenTasksHierarchyOp currentParent detached="
                    + currentParent + " hop=" + hop);
            return 0;
        }
        if (!newParent.isAttached()) {
            Slog.e(TAG, "reparentChildrenTasksHierarchyOp newParent detached="
                    + newParent + " hop=" + hop);
            return 0;
        }
        if (newParent.inPinnedWindowingMode()) {
            Slog.e(TAG, "reparentChildrenTasksHierarchyOp newParent in PIP="
                    + newParent + " hop=" + hop);
            return 0;
        }

        final boolean newParentInMultiWindow = newParent.inMultiWindowMode();
        final TaskDisplayArea newParentTda = newParent.asTask() != null
                ? newParent.asTask().getDisplayArea()
                : newParent.asTaskDisplayArea();
        final WindowContainer finalCurrentParent = currentParent;
        Slog.i(TAG, "reparentChildrenTasksHierarchyOp"
                + " currentParent=" + currentParent + " newParent=" + newParent + " hop=" + hop);

        // We want to collect the tasks first before re-parenting to avoid array shifting on us.
        final ArrayList<Task> tasksToReparent = new ArrayList<>();

        currentParent.forAllTasks((Consumer<Task>) (task) -> {
            Slog.i(TAG, " Processing task=" + task);
            if (task.mCreatedByOrganizer
                    || task.getParent() != finalCurrentParent) {
                // We only care about non-organized task that are direct children of the thing we
                // are reparenting from.
                return;
            }
            if (newParentInMultiWindow && !task.supportsMultiWindowInDisplayArea(newParentTda)) {
                Slog.e(TAG, "reparentChildrenTasksHierarchyOp non-resizeable task to multi window,"
                        + " task=" + task);
                return;
            }
            if (!ArrayUtils.contains(hop.getActivityTypes(), task.getActivityType())) return;
            if (!ArrayUtils.contains(hop.getWindowingModes(), task.getWindowingMode())) return;

            tasksToReparent.add(task);
        }, !hop.getToTop());

        final int count = tasksToReparent.size();
        for (int i = 0; i < count; ++i) {
            final Task task = tasksToReparent.get(i);
            if (syncId >= 0) {
                addToSyncSet(syncId, task);
            }
            if (transition != null) transition.collect(task);

            if (newParent instanceof TaskDisplayArea) {
                // For now, reparenting to display area is different from other reparents...
                task.reparent((TaskDisplayArea) newParent, hop.getToTop());
            } else {
                task.reparent((Task) newParent,
                        hop.getToTop() ? POSITION_TOP : POSITION_BOTTOM,
                        false /*moveParents*/, "processChildrenTaskReparentHierarchyOp");
            }
        }

        if (transition != null) transition.collect(newParent);

        return TRANSACT_EFFECTS_LIFECYCLE;
    }

    private int setAdjacentRootsHierarchyOp(WindowContainerTransaction.HierarchyOp hop) {
        final TaskFragment root1 = WindowContainer.fromBinder(hop.getContainer()).asTaskFragment();
        final TaskFragment root2 =
                WindowContainer.fromBinder(hop.getAdjacentRoot()).asTaskFragment();
        if (!root1.mCreatedByOrganizer || !root2.mCreatedByOrganizer) {
            throw new IllegalArgumentException("setAdjacentRootsHierarchyOp: Not created by"
                    + " organizer root1=" + root1 + " root2=" + root2);
        }
        root1.setAdjacentTaskFragment(root2);
        return TRANSACT_EFFECTS_LIFECYCLE;
    }

    private void sanitizeWindowContainer(WindowContainer wc) {
        if (!(wc instanceof TaskFragment) && !(wc instanceof DisplayArea)) {
            throw new RuntimeException("Invalid token in task fragment or displayArea transaction");
        }
    }

    private int applyWindowContainerChange(WindowContainer wc,
            WindowContainerTransaction.Change c) {
        sanitizeWindowContainer(wc);

        int effects = applyChanges(wc, c);

        if (wc instanceof DisplayArea) {
            effects |= applyDisplayAreaChanges(wc.asDisplayArea(), c);
        } else if (wc instanceof Task) {
            effects |= applyTaskChanges(wc.asTask(), c);
        }

        return effects;
    }

    @Override
    public ITaskOrganizerController getTaskOrganizerController() {
        enforceTaskPermission("getTaskOrganizerController()");
        return mTaskOrganizerController;
    }

    @Override
    public IDisplayAreaOrganizerController getDisplayAreaOrganizerController() {
        enforceTaskPermission("getDisplayAreaOrganizerController()");
        return mDisplayAreaOrganizerController;
    }

    @Override
    public ITaskFragmentOrganizerController getTaskFragmentOrganizerController() {
        return mTaskFragmentOrganizerController;
    }

    @VisibleForTesting
    int startSyncWithOrganizer(IWindowContainerTransactionCallback callback) {
        int id = mService.mWindowManager.mSyncEngine.startSyncSet(this);
        mTransactionCallbacksByPendingSyncId.put(id, callback);
        return id;
    }

    @VisibleForTesting
    void setSyncReady(int id) {
        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Set sync ready, syncId=%d", id);
        mService.mWindowManager.mSyncEngine.setReady(id);
    }

    @VisibleForTesting
    void addToSyncSet(int syncId, WindowContainer wc) {
        mService.mWindowManager.mSyncEngine.addToSyncSet(syncId, wc);
    }

    @Override
    public void onTransactionReady(int syncId, SurfaceControl.Transaction t) {
        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Transaction ready, syncId=%d", syncId);
        final IWindowContainerTransactionCallback callback =
                mTransactionCallbacksByPendingSyncId.get(syncId);

        try {
            callback.onTransactionReady(syncId, t);
        } catch (RemoteException e) {
            // If there's an exception when trying to send the mergedTransaction to the client, we
            // should immediately apply it here so the transactions aren't lost.
            t.apply();
        }

        mTransactionCallbacksByPendingSyncId.remove(syncId);
    }

    @Override
    public void registerTransitionPlayer(ITransitionPlayer player) {
        enforceTaskPermission("registerTransitionPlayer()");
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                mTransitionController.registerTransitionPlayer(player);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** Whether the configuration changes are important to report back to an organizer. */
    static boolean configurationsAreEqualForOrganizer(
            Configuration newConfig, @Nullable Configuration oldConfig) {
        if (oldConfig == null) {
            return false;
        }
        int cfgChanges = newConfig.diff(oldConfig);
        final int winCfgChanges = (cfgChanges & ActivityInfo.CONFIG_WINDOW_CONFIGURATION) != 0
                ? (int) newConfig.windowConfiguration.diff(oldConfig.windowConfiguration,
                true /* compareUndefined */) : 0;
        if ((winCfgChanges & CONTROLLABLE_WINDOW_CONFIGS) == 0) {
            cfgChanges &= ~ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
        }
        return (cfgChanges & CONTROLLABLE_CONFIGS) == 0;
    }

    private void enforceTaskPermission(String func) {
        mService.enforceTaskPermission(func);
    }

    private void enforceTaskPermission(String func, WindowContainerTransaction t) {
        if (t == null || t.getTaskFragmentOrganizer() == null) {
            enforceTaskPermission(func);
            return;
        }

        // Apps may not have the permission to manage Tasks, but we are allowing apps to manage
        // TaskFragments belonging to their own Task.
        enforceOperationsAllowedForTaskFragmentOrganizer(func, t);
    }

    /**
     * Makes sure that the transaction only contains operations that are allowed for the
     * {@link WindowContainerTransaction#getTaskFragmentOrganizer()}.
     */
    private void enforceOperationsAllowedForTaskFragmentOrganizer(
            String func, WindowContainerTransaction t) {
        final ITaskFragmentOrganizer organizer = t.getTaskFragmentOrganizer();

        // Configuration changes
        final Iterator<Map.Entry<IBinder, WindowContainerTransaction.Change>> entries =
                t.getChanges().entrySet().iterator();
        while (entries.hasNext()) {
            final Map.Entry<IBinder, WindowContainerTransaction.Change> entry = entries.next();
            // Only allow to apply changes to TaskFragment that is created by this organizer.
            enforceTaskFragmentOrganized(func, WindowContainer.fromBinder(entry.getKey()),
                    organizer);
        }

        // Hierarchy changes
        final List<WindowContainerTransaction.HierarchyOp> hops = t.getHierarchyOps();
        for (int i = hops.size() - 1; i >= 0; i--) {
            final WindowContainerTransaction.HierarchyOp hop = hops.get(i);
            final int type = hop.getType();
            // Check for each type of the operations that are allowed for TaskFragmentOrganizer.
            switch (type) {
                case HIERARCHY_OP_TYPE_REORDER:
                case HIERARCHY_OP_TYPE_DELETE_TASK_FRAGMENT:
                    enforceTaskFragmentOrganized(func,
                            WindowContainer.fromBinder(hop.getContainer()), organizer);
                    break;
                case HIERARCHY_OP_TYPE_SET_ADJACENT_ROOTS:
                    enforceTaskFragmentOrganized(func,
                            WindowContainer.fromBinder(hop.getContainer()), organizer);
                    enforceTaskFragmentOrganized(func,
                            WindowContainer.fromBinder(hop.getAdjacentRoot()),
                            organizer);
                    break;
                case HIERARCHY_OP_TYPE_CREATE_TASK_FRAGMENT:
                    // We are allowing organizer to create TaskFragment. We will check the
                    // ownerToken in #createTaskFragment, and trigger error callback if that is not
                    // valid.
                case HIERARCHY_OP_TYPE_START_ACTIVITY_IN_TASK_FRAGMENT:
                case HIERARCHY_OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT:
                case HIERARCHY_OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS:
                    // We are allowing organizer to start/reparent activity to a TaskFragment it
                    // created, or set two TaskFragments adjacent to each other. Nothing to check
                    // here because the TaskFragment may not be created yet, but will be created in
                    // the same transaction.
                    break;
                case HIERARCHY_OP_TYPE_REPARENT_CHILDREN:
                    enforceTaskFragmentOrganized(func,
                            WindowContainer.fromBinder(hop.getContainer()), organizer);
                    if (hop.getNewParent() != null) {
                        enforceTaskFragmentOrganized(func,
                                WindowContainer.fromBinder(hop.getNewParent()),
                                organizer);
                    }
                    break;
                default:
                    // Other types of hierarchy changes are not allowed.
                    String msg = "Permission Denial: " + func + " from pid="
                            + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                            + " trying to apply a hierarchy change that is not allowed for"
                            + " TaskFragmentOrganizer=" + organizer;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
            }
        }
    }

    private void enforceTaskFragmentOrganized(String func, @Nullable WindowContainer wc,
            ITaskFragmentOrganizer organizer) {
        if (wc == null) {
            Slog.e(TAG, "Attempt to operate on window that no longer exists");
            return;
        }

        final TaskFragment tf = wc.asTaskFragment();
        if (tf == null || !tf.hasTaskFragmentOrganizer(organizer)) {
            String msg = "Permission Denial: " + func + " from pid=" + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid() + " trying to modify window container not"
                    + " belonging to the TaskFragmentOrganizer=" + organizer;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
    }

    void createTaskFragment(@NonNull TaskFragmentCreationParams creationParams,
            @Nullable IBinder errorCallbackToken) {
        final ActivityRecord ownerActivity =
                ActivityRecord.forTokenLocked(creationParams.getOwnerToken());
        final ITaskFragmentOrganizer organizer = ITaskFragmentOrganizer.Stub.asInterface(
                creationParams.getOrganizer().asBinder());

        if (ownerActivity == null || ownerActivity.getTask() == null) {
            final Throwable exception =
                    new IllegalArgumentException("Not allowed to operate with invalid ownerToken");
            sendTaskFragmentOperationFailure(organizer, errorCallbackToken, exception);
            return;
        }
        // The ownerActivity has to belong to the same app as the root Activity of the target Task.
        final ActivityRecord rootActivity = ownerActivity.getTask().getRootActivity();
        if (rootActivity.getUid() != ownerActivity.getUid()) {
            final Throwable exception =
                    new IllegalArgumentException("Not allowed to operate with the ownerToken while "
                            + "the root activity of the target task belong to the different app");
            sendTaskFragmentOperationFailure(organizer, errorCallbackToken, exception);
            return;
        }
        final TaskFragment taskFragment = new TaskFragment(mService,
                creationParams.getFragmentToken(), true /* createdByOrganizer */);
        // Set task fragment organizer immediately, since it might have to be notified about further
        // actions.
        taskFragment.setTaskFragmentOrganizer(
                creationParams.getOrganizer(), ownerActivity.getPid());
        ownerActivity.getTask().addChild(taskFragment, POSITION_TOP);
        taskFragment.setWindowingMode(creationParams.getWindowingMode());
        taskFragment.setBounds(creationParams.getInitialBounds());
        mLaunchTaskFragments.put(creationParams.getFragmentToken(), taskFragment);
    }

    void reparentTaskFragment(@NonNull WindowContainer oldParent,
            @Nullable WindowContainer newParent,  @Nullable IBinder errorCallbackToken) {
        WindowContainer parent = newParent;
        if (parent == null && oldParent.asTaskFragment() != null) {
            parent = oldParent.asTaskFragment().getTask();
        }
        if (parent == null) {
            final Throwable exception =
                    new IllegalArgumentException("Not allowed to operate with invalid container");
            sendTaskFragmentOperationFailure(oldParent.asTaskFragment().getTaskFragmentOrganizer(),
                    errorCallbackToken, exception);
            return;
        }
        while (oldParent.hasChild()) {
            oldParent.getChildAt(0).reparent(parent, POSITION_TOP);
        }
    }

    private int deleteTaskFragment(@NonNull TaskFragment taskFragment,
            @Nullable IBinder errorCallbackToken) {
        final int index = mLaunchTaskFragments.indexOfValue(taskFragment);
        if (index < 0) {
            final Throwable exception =
                    new IllegalArgumentException("Not allowed to operate with invalid "
                            + "taskFragment");
            sendTaskFragmentOperationFailure(taskFragment.getTaskFragmentOrganizer(),
                    errorCallbackToken, exception);
            return 0;
        }
        mLaunchTaskFragments.removeAt(index);
        taskFragment.remove(true /* withTransition */, "deleteTaskFragment");
        return TRANSACT_EFFECTS_LIFECYCLE;
    }

    @Nullable
    TaskFragment getTaskFragment(IBinder tfToken) {
        return mLaunchTaskFragments.get(tfToken);
    }

    static class CallerInfo {
        final int mPid;
        final int mUid;

        CallerInfo() {
            mPid = Binder.getCallingPid();
            mUid = Binder.getCallingUid();
        }
    }

    void sendTaskFragmentOperationFailure(@NonNull ITaskFragmentOrganizer organizer,
            @Nullable IBinder errorCallbackToken, @NonNull Throwable exception) {
        if (organizer == null) {
            throw new IllegalArgumentException("Not allowed to operate with invalid organizer");
        }
        mService.mTaskFragmentOrganizerController
                .onTaskFragmentError(organizer, errorCallbackToken, exception);
    }

    private Throwable convertStartFailureToThrowable(int result, Intent intent) {
        switch (result) {
            case ActivityManager.START_INTENT_NOT_RESOLVED:
            case ActivityManager.START_CLASS_NOT_FOUND:
                return new ActivityNotFoundException("No Activity found to handle " + intent);
            case ActivityManager.START_PERMISSION_DENIED:
                return new SecurityException("Permission denied and not allowed to start activity "
                        + intent);
            case ActivityManager.START_CANCELED:
                return new AndroidRuntimeException("Activity could not be started for " + intent
                        + " with error code : " + result);
            default:
                return new AndroidRuntimeException("Start activity failed with error code : "
                        + result + " when starting " + intent);
        }
    }
}
