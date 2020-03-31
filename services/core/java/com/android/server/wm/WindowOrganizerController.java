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

import static android.Manifest.permission.MANAGE_ACTIVITY_STACKS;

import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.Task.FLAG_FORCE_HIDDEN_FOR_TASK_ORG;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.IDisplayAreaOrganizerController;
import android.window.ITaskOrganizerController;
import android.window.IWindowContainerTransactionCallback;
import android.window.IWindowOrganizerController;
import android.window.WindowContainerTransaction;

import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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

    private final BLASTSyncEngine mBLASTSyncEngine = new BLASTSyncEngine();
    private final HashMap<Integer, IWindowContainerTransactionCallback>
            mTransactionCallbacksByPendingSyncId = new HashMap();

    final TaskOrganizerController mTaskOrganizerController;
    final DisplayAreaOrganizerController mDisplayAreaOrganizerController;

    WindowOrganizerController(ActivityTaskManagerService atm) {
        mService = atm;
        mGlobalLock = atm.mGlobalLock;
        mTaskOrganizerController = new TaskOrganizerController(mService);
        mDisplayAreaOrganizerController = new DisplayAreaOrganizerController(mService);
    }

    @Override
    public void applyTransaction(WindowContainerTransaction t) {
        applySyncTransaction(t, null /*callback*/);
    }

    @Override
    public int applySyncTransaction(WindowContainerTransaction t,
            IWindowContainerTransactionCallback callback) {
        enforceStackPermission("applySyncTransaction()");
        int syncId = -1;
        if (t == null) {
            throw new IllegalArgumentException(
                    "Null transaction passed to applySyncTransaction");
        }
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                int effects = 0;

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
                if (callback != null) {
                    syncId = startSyncWithOrganizer(callback);
                }
                mService.deferWindowLayout();
                try {
                    ArraySet<WindowContainer> haveConfigChanges = new ArraySet<>();
                    Iterator<Map.Entry<IBinder, WindowContainerTransaction.Change>> entries =
                            t.getChanges().entrySet().iterator();
                    while (entries.hasNext()) {
                        final Map.Entry<IBinder, WindowContainerTransaction.Change> entry =
                                entries.next();
                        final WindowContainer wc = WindowContainer.fromBinder(entry.getKey());
                        int containerEffect = applyWindowContainerChange(wc, entry.getValue());
                        effects |= containerEffect;

                        // Lifecycle changes will trigger ensureConfig for everything.
                        if ((effects & TRANSACT_EFFECTS_LIFECYCLE) == 0
                                && (containerEffect & TRANSACT_EFFECTS_CLIENT_CONFIG) != 0) {
                            haveConfigChanges.add(wc);
                        }
                        if (syncId >= 0) {
                            mBLASTSyncEngine.addToSyncSet(syncId, wc);
                        }
                    }
                    // Hierarchy changes
                    final List<WindowContainerTransaction.HierarchyOp> hops = t.getHierarchyOps();
                    for (int i = 0, n = hops.size(); i < n; ++i) {
                        final WindowContainerTransaction.HierarchyOp hop = hops.get(i);
                        final WindowContainer wc = WindowContainer.fromBinder(hop.getContainer());
                        effects |= sanitizeAndApplyHierarchyOp(wc, hop);
                    }
                    if ((effects & TRANSACT_EFFECTS_LIFECYCLE) != 0) {
                        // Already calls ensureActivityConfig
                        mService.mRootWindowContainer.ensureActivitiesVisible(
                                null, 0, PRESERVE_WINDOWS);
                    } else if ((effects & TRANSACT_EFFECTS_CLIENT_CONFIG) != 0) {
                        final PooledConsumer f = PooledLambda.obtainConsumer(
                                ActivityRecord::ensureActivityConfiguration,
                                PooledLambda.__(ActivityRecord.class), 0,
                                false /* preserveWindow */);
                        try {
                            for (int i = haveConfigChanges.size() - 1; i >= 0; --i) {
                                haveConfigChanges.valueAt(i).forAllActivities(f);
                            }
                        } finally {
                            f.recycle();
                        }
                    }
                } finally {
                    mService.continueWindowLayout();
                    if (syncId >= 0) {
                        setSyncReady(syncId);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return syncId;
    }

    private int sanitizeAndApplyChange(WindowContainer container,
            WindowContainerTransaction.Change change) {
        if (!(container instanceof Task)) {
            throw new RuntimeException("Invalid token in task transaction");
        }
        final Task task = (Task) container;
        // The "client"-facing API should prevent bad changes; however, just in case, sanitize
        // masks here.
        final int configMask = change.getConfigSetMask() & CONTROLLABLE_CONFIGS;
        final int windowMask = change.getWindowSetMask() & CONTROLLABLE_WINDOW_CONFIGS;
        int effects = 0;
        if (configMask != 0) {
            Configuration c = new Configuration(container.getRequestedOverrideConfiguration());
            c.setTo(change.getConfiguration(), configMask, windowMask);
            container.onRequestedOverrideConfigurationChanged(c);
            // TODO(b/145675353): remove the following once we could apply new bounds to the
            // pinned stack together with its children.
            resizePinnedStackIfNeeded(container, configMask, windowMask, c);
            effects |= TRANSACT_EFFECTS_CLIENT_CONFIG;
        }
        if ((change.getChangeMask() & WindowContainerTransaction.Change.CHANGE_FOCUSABLE) != 0) {
            if (container.setFocusable(change.getFocusable())) {
                effects |= TRANSACT_EFFECTS_LIFECYCLE;
            }
        }
        if ((change.getChangeMask() & WindowContainerTransaction.Change.CHANGE_HIDDEN) != 0) {
            if (task.setForceHidden(FLAG_FORCE_HIDDEN_FOR_TASK_ORG, change.getHidden())) {
                effects |= TRANSACT_EFFECTS_LIFECYCLE;
            }
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
        final ActivityStack as = (ActivityStack) task;

        if (hop.isReparent()) {
            final boolean isNonOrganizedRootableTask =
                    (task.isRootTask() && !task.mCreatedByOrganizer)
                            || task.getParent().asTask().mCreatedByOrganizer;
            if (isNonOrganizedRootableTask) {
                Task newParent = hop.getNewParent() == null ? null
                        : WindowContainer.fromBinder(hop.getNewParent()).asTask();
                if (task.getParent() != newParent) {
                    if (newParent == null) {
                        // Re-parent task to display as a root task.
                        dc.moveStackToDisplay(as, hop.getToTop());
                    } else if (newParent.inMultiWindowMode() && !task.isResizeable()
                            && task.isLeafTask()) {
                        Slog.w(TAG, "Can't support task that doesn't support multi-window mode in"
                                + " multi-window mode... newParent=" + newParent + " task=" + task);
                        return 0;
                    } else {
                        task.reparent((ActivityStack) newParent,
                                hop.getToTop() ? POSITION_TOP : POSITION_BOTTOM,
                                false /*moveParents*/, "sanitizeAndApplyHierarchyOp");
                    }
                } else {
                    final ActivityStack rootTask =
                            (ActivityStack) (newParent != null ? newParent : task.getRootTask());
                    if (hop.getToTop()) {
                        as.getDisplay().mTaskContainers.positionStackAtTop(rootTask,
                                false /* includingParents */);
                    } else {
                        as.getDisplay().mTaskContainers.positionStackAtBottom(rootTask);
                    }
                }
            } else {
                throw new RuntimeException("Reparenting leaf Tasks is not supported now.");
            }
        } else {
            // Ugh, of course ActivityStack has its own special reorder logic...
            if (task.isRootTask()) {
                if (hop.getToTop()) {
                    dc.mTaskContainers.positionStackAtTop(as, false /* includingParents */);
                } else {
                    dc.mTaskContainers.positionStackAtBottom(as);
                }
            } else {
                task.getParent().positionChildAt(
                        hop.getToTop() ? POSITION_TOP : POSITION_BOTTOM,
                        task, false /* includingParents */);
            }
        }
        return TRANSACT_EFFECTS_LIFECYCLE;
    }

    private int applyWindowContainerChange(WindowContainer wc,
            WindowContainerTransaction.Change c) {
        int effects = sanitizeAndApplyChange(wc, c);

        final Task tr = wc.asTask();

        final SurfaceControl.Transaction t = c.getBoundsChangeTransaction();
        if (t != null) {
            tr.setMainWindowSizeChangeTransaction(t);
        }

        Rect enterPipBounds = c.getEnterPipBounds();
        if (enterPipBounds != null) {
            mService.mStackSupervisor.updatePictureInPictureMode(tr,
                    enterPipBounds, true);
        }

        final int windowingMode = c.getWindowingMode();
        if (windowingMode > -1) {
            tr.setWindowingMode(windowingMode);
        }
        final int childWindowingMode = c.getActivityWindowingMode();
        if (childWindowingMode > -1) {
            tr.setActivityWindowingMode(childWindowingMode);
        }

        return effects;
    }

    private void resizePinnedStackIfNeeded(ConfigurationContainer container, int configMask,
            int windowMask, Configuration config) {
        if ((container instanceof ActivityStack)
                && ((configMask & ActivityInfo.CONFIG_WINDOW_CONFIGURATION) != 0)
                && ((windowMask & WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0)) {
            final ActivityStack stack = (ActivityStack) container;
            if (stack.inPinnedWindowingMode()) {
                stack.resize(config.windowConfiguration.getBounds(),
                        null /* configBounds */, PRESERVE_WINDOWS, true /* deferResume */);
            }
        }
    }

    @Override
    public ITaskOrganizerController getTaskOrganizerController() {
        enforceStackPermission("getTaskOrganizerController()");
        return mTaskOrganizerController;
    }

    @Override
    public IDisplayAreaOrganizerController getDisplayAreaOrganizerController() {
        enforceStackPermission("getDisplayAreaOrganizerController()");
        return mDisplayAreaOrganizerController;
    }

    int startSyncWithOrganizer(IWindowContainerTransactionCallback callback) {
        int id = mBLASTSyncEngine.startSyncSet(this);
        mTransactionCallbacksByPendingSyncId.put(id, callback);
        return id;
    }

    void setSyncReady(int id) {
        mBLASTSyncEngine.setReady(id);
    }

    @Override
    public void transactionReady(int mSyncId, SurfaceControl.Transaction mergedTransaction) {
        final IWindowContainerTransactionCallback callback =
                mTransactionCallbacksByPendingSyncId.get(mSyncId);

        try {
            callback.transactionReady(mSyncId, mergedTransaction);
        } catch (RemoteException e) {
        }

        mTransactionCallbacksByPendingSyncId.remove(mSyncId);
    }

    private void enforceStackPermission(String func) {
        mService.mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, func);
    }
}
