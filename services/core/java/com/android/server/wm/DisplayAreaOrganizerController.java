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

import static android.window.DisplayAreaOrganizer.FEATURE_RUNTIME_TASK_CONTAINER_FIRST;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_ORGANIZER;
import static com.android.server.wm.DisplayArea.Type.ANY;

import android.annotation.Nullable;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.DisplayAreaAppearedInfo;
import android.window.IDisplayAreaOrganizer;
import android.window.IDisplayAreaOrganizerController;
import android.window.WindowContainerToken;

import com.android.internal.protolog.common.ProtoLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class DisplayAreaOrganizerController extends IDisplayAreaOrganizerController.Stub {
    private static final String TAG = "DisplayAreaOrganizerController";

    /**
     * Next available feature id for a runtime task display area.
     * @see #createTaskDisplayArea(IDisplayAreaOrganizer organizer, int, int, String)
     */
    private int mNextTaskDisplayAreaFeatureId = FEATURE_RUNTIME_TASK_CONTAINER_FIRST;

    final ActivityTaskManagerService mService;
    private final WindowManagerGlobalLock mGlobalLock;
    private final HashMap<Integer, DisplayAreaOrganizerState> mOrganizersByFeatureIds =
            new HashMap();

    private class DeathRecipient implements IBinder.DeathRecipient {
        int mFeature;
        IDisplayAreaOrganizer mOrganizer;

        DeathRecipient(IDisplayAreaOrganizer organizer, int feature) {
            mOrganizer = organizer;
            mFeature = feature;
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                IDisplayAreaOrganizer featureOrganizer = getOrganizerByFeature(mFeature);
                if (featureOrganizer != null) {
                    IBinder organizerBinder = featureOrganizer.asBinder();
                    if (!organizerBinder.equals(mOrganizer.asBinder()) &&
                               organizerBinder.isBinderAlive()) {
                        Slog.d(TAG, "Dead organizer replaced for feature=" + mFeature);
                        return;
                    }
                    mOrganizersByFeatureIds.remove(mFeature).destroy();
                }
            }
        }
    }

    private class DisplayAreaOrganizerState {
        private final IDisplayAreaOrganizer mOrganizer;
        private final DeathRecipient mDeathRecipient;

        DisplayAreaOrganizerState(IDisplayAreaOrganizer organizer, int feature) {
            mOrganizer = organizer;
            mDeathRecipient = new DeathRecipient(organizer, feature);
            try {
                organizer.asBinder().linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
                // Oh well...
            }
        }

        void destroy() {
            IBinder organizerBinder = mOrganizer.asBinder();
            mService.mRootWindowContainer.forAllDisplayAreas((da) -> {
                if (da.mOrganizer != null && da.mOrganizer.asBinder().equals(organizerBinder)) {
                    if (da.isTaskDisplayArea() && da.asTaskDisplayArea().mCreatedByOrganizer) {
                        // Delete the organizer created TDA when unregister.
                        deleteTaskDisplayArea(da.asTaskDisplayArea());
                    } else {
                        da.setOrganizer(null);
                    }
                }
            });
            organizerBinder.unlinkToDeath(mDeathRecipient, 0);
        }
    }

    DisplayAreaOrganizerController(ActivityTaskManagerService atm) {
        mService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    private void enforceTaskPermission(String func) {
        mService.enforceTaskPermission(func);
    }

    @Nullable
    IDisplayAreaOrganizer getOrganizerByFeature(int featureId) {
        final DisplayAreaOrganizerState state = mOrganizersByFeatureIds.get(featureId);
        return state != null ? state.mOrganizer : null;
    }

    @Override
    public ParceledListSlice<DisplayAreaAppearedInfo> registerOrganizer(
            IDisplayAreaOrganizer organizer, int feature) {
        enforceTaskPermission("registerOrganizer()");
        final long uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Register display organizer=%s uid=%d",
                        organizer.asBinder(), uid);
                if (mOrganizersByFeatureIds.get(feature) != null) {
                    if (mOrganizersByFeatureIds.get(feature).mOrganizer.asBinder()
                            .isBinderAlive()) {
                        throw new IllegalStateException(
                                "Replacing existing organizer currently unsupported");
                    }

                    mOrganizersByFeatureIds.remove(feature).destroy();
                    Slog.d(TAG, "Replacing dead organizer for feature=" + feature);
                }

                final DisplayAreaOrganizerState state = new DisplayAreaOrganizerState(organizer,
                        feature);
                final List<DisplayAreaAppearedInfo> displayAreaInfos = new ArrayList<>();
                mService.mRootWindowContainer.forAllDisplays(dc -> {
                    if (!dc.isTrusted()) {
                        ProtoLog.w(WM_DEBUG_WINDOW_ORGANIZER,
                                "Don't organize or trigger events for untrusted displayId=%d",
                                dc.getDisplayId());
                        return;
                    }
                    dc.forAllDisplayAreas((da) -> {
                        if (da.mFeatureId != feature) return;
                        displayAreaInfos.add(organizeDisplayArea(organizer, da,
                                "DisplayAreaOrganizerController.registerOrganizer"));
                    });
                });

                mOrganizersByFeatureIds.put(feature, state);
                return new ParceledListSlice<>(displayAreaInfos);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void unregisterOrganizer(IDisplayAreaOrganizer organizer) {
        enforceTaskPermission("unregisterTaskOrganizer()");
        final long uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Unregister display organizer=%s uid=%d",
                        organizer.asBinder(), uid);
                mOrganizersByFeatureIds.entrySet().removeIf((entry) -> {
                    final boolean matches = entry.getValue().mOrganizer.asBinder()
                            .equals(organizer.asBinder());
                    if (matches) {
                        entry.getValue().destroy();
                    }
                    return matches;
                });
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public DisplayAreaAppearedInfo createTaskDisplayArea(IDisplayAreaOrganizer organizer,
            int displayId, int parentFeatureId, String name) {
        enforceTaskPermission("createTaskDisplayArea()");
        final long uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Create TaskDisplayArea uid=%d", uid);

                final DisplayContent display =
                        mService.mRootWindowContainer.getDisplayContent(displayId);
                if (display == null) {
                    throw new IllegalArgumentException("createTaskDisplayArea unknown displayId="
                            + displayId);
                }
                if (!display.isTrusted()) {
                    throw new IllegalArgumentException("createTaskDisplayArea untrusted displayId="
                            + displayId);
                }

                // The parentFeatureId can be either a RootDisplayArea or a TaskDisplayArea.
                // Check if there is a RootDisplayArea with the given parentFeatureId.
                final RootDisplayArea parentRoot = display.getItemFromDisplayAreas(da ->
                        da.asRootDisplayArea() != null && da.mFeatureId == parentFeatureId
                                ? da.asRootDisplayArea()
                                : null);
                final TaskDisplayArea parentTda;
                if (parentRoot == null) {
                    // There is no RootDisplayArea matching the parentFeatureId.
                    // Check if there is a TaskDisplayArea with the given parentFeatureId.
                    parentTda = display.getItemFromTaskDisplayAreas(taskDisplayArea ->
                            taskDisplayArea.mFeatureId == parentFeatureId
                                    ? taskDisplayArea
                                    : null);
                } else {
                    parentTda = null;
                }
                if (parentRoot == null && parentTda == null) {
                    throw new IllegalArgumentException(
                            "Can't find a parent DisplayArea with featureId=" + parentFeatureId);
                }

                final int taskDisplayAreaFeatureId = mNextTaskDisplayAreaFeatureId++;
                final DisplayAreaOrganizerState state = new DisplayAreaOrganizerState(organizer,
                        taskDisplayAreaFeatureId);

                final TaskDisplayArea tda = parentRoot != null
                        ? createTaskDisplayArea(parentRoot, name, taskDisplayAreaFeatureId)
                        : createTaskDisplayArea(parentTda, name, taskDisplayAreaFeatureId);
                final DisplayAreaAppearedInfo tdaInfo = organizeDisplayArea(organizer, tda,
                        "DisplayAreaOrganizerController.createTaskDisplayArea");
                mOrganizersByFeatureIds.put(taskDisplayAreaFeatureId, state);
                return tdaInfo;
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void deleteTaskDisplayArea(WindowContainerToken token) {
        enforceTaskPermission("deleteTaskDisplayArea()");
        final long uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "Delete TaskDisplayArea uid=%d", uid);

                final WindowContainer wc = WindowContainer.fromBinder(token.asBinder());
                if (wc == null || wc.asTaskDisplayArea() == null) {
                    throw new IllegalArgumentException("Can't resolve TaskDisplayArea from token");
                }
                final TaskDisplayArea taskDisplayArea = wc.asTaskDisplayArea();
                if (!taskDisplayArea.mCreatedByOrganizer) {
                    throw new IllegalArgumentException(
                            "Attempt to delete TaskDisplayArea not created by organizer "
                                    + "TaskDisplayArea=" + taskDisplayArea);
                }

                mOrganizersByFeatureIds.remove(taskDisplayArea.mFeatureId).destroy();
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void onDisplayAreaAppeared(IDisplayAreaOrganizer organizer, DisplayArea da) {
        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "DisplayArea appeared name=%s", da.getName());
        try {
            SurfaceControl outSurfaceControl = new SurfaceControl(da.getSurfaceControl(),
                    "DisplayAreaOrganizerController.onDisplayAreaAppeared");
            organizer.onDisplayAreaAppeared(da.getDisplayAreaInfo(), outSurfaceControl);
        } catch (RemoteException e) {
            // Oh well...
        }
    }

    void onDisplayAreaVanished(IDisplayAreaOrganizer organizer, DisplayArea da) {
        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "DisplayArea vanished name=%s", da.getName());
        if (!organizer.asBinder().isBinderAlive()) {
            Slog.d(TAG, "Organizer died before sending onDisplayAreaVanished");
            return;
        }
        try {
            organizer.onDisplayAreaVanished(da.getDisplayAreaInfo());
        } catch (RemoteException e) {
            // Oh well...
        }
    }

    void onDisplayAreaInfoChanged(IDisplayAreaOrganizer organizer, DisplayArea da) {
        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "DisplayArea info changed name=%s", da.getName());
        try {
            organizer.onDisplayAreaInfoChanged(da.getDisplayAreaInfo());
        } catch (RemoteException e) {
            // Oh well...
        }
    }

    private DisplayAreaAppearedInfo organizeDisplayArea(IDisplayAreaOrganizer organizer,
            DisplayArea displayArea, String callsite) {
        displayArea.setOrganizer(organizer, true /* skipDisplayAreaAppeared */);
        return new DisplayAreaAppearedInfo(displayArea.getDisplayAreaInfo(),
                new SurfaceControl(displayArea.getSurfaceControl(), callsite));
    }

    /**
     * Creates a {@link TaskDisplayArea} as the topmost TDA below the given {@link RootDisplayArea}.
     */
    private TaskDisplayArea createTaskDisplayArea(RootDisplayArea root, String name,
            int taskDisplayAreaFeatureId) {
        final TaskDisplayArea taskDisplayArea = new TaskDisplayArea(root.mDisplayContent,
                root.mWmService, name, taskDisplayAreaFeatureId, true /* createdByOrganizer */);

        // Find the top most DA that can contain Task (either a TDA or a DisplayAreaGroup).
        final DisplayArea topTaskContainer = root.getItemFromDisplayAreas(da -> {
            if (da.mType != ANY) {
                return null;
            }

            final RootDisplayArea rootDA = da.getRootDisplayArea();
            if (rootDA == root || rootDA == da) {
                // Either it is the top TDA below the root or it is a DisplayAreaGroup.
                return da;
            }
            return null;
        });
        if (topTaskContainer == null) {
            throw new IllegalStateException("Root must either contain TDA or DAG root=" + root);
        }

        // Insert the TaskDisplayArea as the top Task container.
        final WindowContainer parent = topTaskContainer.getParent();
        final int index = parent.mChildren.indexOf(topTaskContainer) + 1;
        parent.addChild(taskDisplayArea, index);

        return taskDisplayArea;
    }

    /**
     * Creates a {@link TaskDisplayArea} as the topmost child of the given {@link TaskDisplayArea}.
     */
    private TaskDisplayArea createTaskDisplayArea(TaskDisplayArea parentTda, String name,
            int taskDisplayAreaFeatureId) {
        final TaskDisplayArea taskDisplayArea = new TaskDisplayArea(parentTda.mDisplayContent,
                parentTda.mWmService, name, taskDisplayAreaFeatureId,
                true /* createdByOrganizer */);

        // Insert the TaskDisplayArea on the top.
        parentTda.addChild(taskDisplayArea, WindowContainer.POSITION_TOP);

        return taskDisplayArea;
    }

    private void deleteTaskDisplayArea(TaskDisplayArea taskDisplayArea) {
        taskDisplayArea.setOrganizer(null);
        mService.mRootWindowContainer.mTaskSupervisor.beginDeferResume();

        // TaskDisplayArea#remove() move the stacks to the default TaskDisplayArea.
        Task lastReparentedRootTask;
        try {
            lastReparentedRootTask = taskDisplayArea.remove();
        } finally {
            mService.mRootWindowContainer.mTaskSupervisor.endDeferResume();
        }

        taskDisplayArea.removeImmediately();

        // Only update focus/visibility for the last one because there may be many root tasks are
        // reparented and the intermediate states are unnecessary.
        if (lastReparentedRootTask != null) {
            lastReparentedRootTask.resumeNextFocusAfterReparent();
        }
    }
}
