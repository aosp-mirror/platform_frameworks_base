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
import static android.Manifest.permission.READ_FRAME_BUFFER;

import static com.android.server.wm.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.ActivityTaskManagerService.LAYOUT_REASON_CONFIG_CHANGED;
import static com.android.server.wm.Task.FLAG_FORCE_HIDDEN_FOR_TASK_ORG;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.IDisplayAreaOrganizerController;
import android.window.ITaskOrganizerController;
import android.window.IWindowContainerTransactionCallback;
import android.window.IWindowOrganizerController;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                        if (!wc.isAttached()) {
                            Slog.e(TAG, "Attempt to operate on detached container: " + wc);
                            continue;
                        }
                        // Make sure we add to the syncSet before performing
                        // operations so we don't end up splitting effects between the WM
                        // pending transaction and the BLASTSync transaction.
                        if (syncId >= 0) {
                            addToSyncSet(syncId, wc);
                        }

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
                    for (int i = 0, n = hops.size(); i < n; ++i) {
                        final WindowContainerTransaction.HierarchyOp hop = hops.get(i);
                        final WindowContainer wc = WindowContainer.fromBinder(hop.getContainer());
                        if (!wc.isAttached()) {
                            Slog.e(TAG, "Attempt to operate on detached container: " + wc);
                            continue;
                        }
                        if (syncId >= 0) {
                            addToSyncSet(syncId, wc);
                        }
                        effects |= sanitizeAndApplyHierarchyOp(wc, hop);
                    }
                    // Queue-up bounds-change transactions for tasks which are now organized. Do
                    // this after hierarchy ops so we have the final organized state.
                    entries = t.getChanges().entrySet().iterator();
                    while (entries.hasNext()) {
                        final Map.Entry<IBinder, WindowContainerTransaction.Change> entry =
                                entries.next();
                        final Task task = WindowContainer.fromBinder(entry.getKey()).asTask();
                        final Rect surfaceBounds = entry.getValue().getBoundsChangeSurfaceBounds();
                        if (task == null || !task.isAttached() || surfaceBounds == null) {
                            continue;
                        }
                        if (!task.isOrganized()) {
                            final Task parent =
                                    task.getParent() != null ? task.getParent().asTask() : null;
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
                        // Already calls ensureActivityConfig
                        mService.mRootWindowContainer.ensureActivitiesVisible(
                                null, 0, PRESERVE_WINDOWS);
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

    private int applyChanges(WindowContainer container, WindowContainerTransaction.Change change) {
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

        final int windowingMode = change.getWindowingMode();
        if (windowingMode > -1) {
            container.setWindowingMode(windowingMode);
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
            mService.mStackSupervisor.updatePictureInPictureMode(tr, enterPipBounds, true);
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
                        as.reparent(dc.getDefaultTaskDisplayArea(), hop.getToTop());
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
                        as.getDisplayArea().positionStackAtTop(rootTask,
                                false /* includingParents */);
                    } else {
                        as.getDisplayArea().positionStackAtBottom(rootTask);
                    }
                }
            } else {
                throw new RuntimeException("Reparenting leaf Tasks is not supported now. " + task);
            }
        } else {
            // Ugh, of course ActivityStack has its own special reorder logic...
            if (task.isRootTask()) {
                if (hop.getToTop()) {
                    as.getDisplayArea().positionStackAtTop(as, false /* includingParents */);
                } else {
                    as.getDisplayArea().positionStackAtBottom(as);
                }
            } else {
                task.getParent().positionChildAt(
                        hop.getToTop() ? POSITION_TOP : POSITION_BOTTOM,
                        task, false /* includingParents */);
            }
        }
        return TRANSACT_EFFECTS_LIFECYCLE;
    }

    private void sanitizeWindowContainer(WindowContainer wc) {
        if (!(wc instanceof Task) && !(wc instanceof DisplayArea)) {
            throw new RuntimeException("Invalid token in task or displayArea transaction");
        }
    }

    private int applyWindowContainerChange(WindowContainer wc,
            WindowContainerTransaction.Change c) {
        sanitizeWindowContainer(wc);

        int effects = applyChanges(wc, c);

        if (wc instanceof Task) {
            effects |= applyTaskChanges(wc.asTask(), c);
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
                        PRESERVE_WINDOWS, true /* deferResume */);
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

    @VisibleForTesting
    int startSyncWithOrganizer(IWindowContainerTransactionCallback callback) {
        int id = mBLASTSyncEngine.startSyncSet(this);
        mTransactionCallbacksByPendingSyncId.put(id, callback);
        return id;
    }

    @VisibleForTesting
    void setSyncReady(int id) {
        mBLASTSyncEngine.setReady(id);
    }

    @VisibleForTesting
    void addToSyncSet(int syncId, WindowContainer wc) {
        mBLASTSyncEngine.addToSyncSet(syncId, wc);
    }

    @Override
    public void onTransactionReady(int mSyncId, Set<WindowContainer> windowContainersReady) {
        final IWindowContainerTransactionCallback callback =
                mTransactionCallbacksByPendingSyncId.get(mSyncId);

        SurfaceControl.Transaction mergedTransaction = new SurfaceControl.Transaction();
        for (WindowContainer container : windowContainersReady) {
            container.mergeBlastSyncTransaction(mergedTransaction);
        }

        try {
            callback.onTransactionReady(mSyncId, mergedTransaction);
        } catch (RemoteException e) {
            // If there's an exception when trying to send the mergedTransaction to the client, we
            // should immediately apply it here so the transactions aren't lost.
            mergedTransaction.apply();
        }

        mTransactionCallbacksByPendingSyncId.remove(mSyncId);
    }

    @Override
    public boolean takeScreenshot(WindowContainerToken token, SurfaceControl outSurfaceControl) {
        mService.mAmInternal.enforceCallingPermission(READ_FRAME_BUFFER, "takeScreenshot()");
        final WindowContainer wc = WindowContainer.fromBinder(token.asBinder());
        if (wc == null) {
            throw new RuntimeException("Invalid token in screenshot transaction");
        }

        final Rect bounds = new Rect();
        wc.getBounds(bounds);
        bounds.offsetTo(0, 0);
        SurfaceControl.ScreenshotGraphicBuffer buffer = SurfaceControl.captureLayers(
                wc.getSurfaceControl(), bounds, 1);

        if (buffer == null || buffer.getGraphicBuffer() == null) {
            return false;
        }

        SurfaceControl screenshot = mService.mWindowManager.mSurfaceControlFactory.apply(null)
                .setName(wc.getName() + " - Organizer Screenshot")
                .setBufferSize(bounds.width(), bounds.height())
                .setFormat(PixelFormat.TRANSLUCENT)
                .setParent(wc.getParentSurfaceControl())
                .setCallsite("WindowOrganizerController.takeScreenshot")
                .build();

        Surface surface = new Surface();
        surface.copyFrom(screenshot);
        surface.attachAndQueueBufferWithColorSpace(buffer.getGraphicBuffer(), null);
        surface.release();

        outSurfaceControl.copyFrom(screenshot, "WindowOrganizerController.takeScreenshot");
        return true;
    }

    private void enforceStackPermission(String func) {
        mService.mAmInternal.enforceCallingPermission(MANAGE_ACTIVITY_STACKS, func);
    }
}
