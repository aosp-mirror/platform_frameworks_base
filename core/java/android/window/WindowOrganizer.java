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

package android.window;

import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.os.RemoteException;
import android.util.Singleton;

import java.util.List;

/**
 * Class for organizing specific types of windows like Tasks and DisplayAreas
 *
 * @hide
 */
public class WindowOrganizer {

    /**
     * Apply multiple WindowContainer operations at once.
     * @param t The transaction to apply.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public static void applyTransaction(WindowContainerTransaction t) throws RemoteException {
        getWindowOrganizerController().applyTransaction(t);
    }

    /**
     * Apply multiple WindowContainer operations at once.
     * @param t The transaction to apply.
     * @param callback This transaction will use the synchronization scheme described in
     *        BLASTSyncEngine.java. The SurfaceControl transaction containing the effects of this
     *        WindowContainer transaction will be passed to this callback when ready.
     * @return An ID for the sync operation which will later be passed to transactionReady callback.
     *         This lets the caller differentiate overlapping sync operations.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    public static int applySyncTransaction(WindowContainerTransaction t,
            IWindowContainerTransactionCallback callback) throws RemoteException {
        return getWindowOrganizerController().applySyncTransaction(t, callback);
    }

    /** @hide */
    @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
    private static IWindowOrganizerController getWindowOrganizerController() {
        return IWindowOrganizerControllerSingleton.get();
    }

    private static final Singleton<IWindowOrganizerController> IWindowOrganizerControllerSingleton =
            new Singleton<IWindowOrganizerController>() {
                @Override
                protected IWindowOrganizerController create() {
                    try {
                        return ActivityTaskManager.getService().getWindowOrganizerController();
                    } catch (RemoteException e) {
                        return null;
                    }
                }
            };

    public static class TaskOrganizer {

        /**
         * Register a TaskOrganizer to manage tasks as they enter the given windowing mode.
         * If there was already a TaskOrganizer for this windowing mode it will be evicted
         * and receive taskVanished callbacks in the process.
         */
        @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
        public static void registerOrganizer(ITaskOrganizer organizer, int windowingMode)
                throws RemoteException {
            getController().registerTaskOrganizer(organizer, windowingMode);
        }

        /** Unregisters a previously registered task organizer. */
        @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
        public static void unregisterOrganizer(ITaskOrganizer organizer) throws RemoteException {
            getController().unregisterTaskOrganizer(organizer);
        }

        /** Creates a persistent root task in WM for a particular windowing-mode. */
        @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
        public static ActivityManager.RunningTaskInfo createRootTask(
                int displayId, int windowingMode) throws RemoteException {
            return getController().createRootTask(displayId, windowingMode);
        }

        /** Deletes a persistent root task in WM */
        @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
        public static boolean deleteRootTask(IWindowContainer task) throws RemoteException {
            return getController().deleteRootTask(task);
        }

        /** Gets direct child tasks (ordered from top-to-bottom) */
        @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
        public static List<ActivityManager.RunningTaskInfo> getChildTasks(IWindowContainer parent,
                int[] activityTypes) throws RemoteException {
            return getController().getChildTasks(parent, activityTypes);
        }

        /** Gets all root tasks on a display (ordered from top-to-bottom) */
        @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
        public static List<ActivityManager.RunningTaskInfo> getRootTasks(
                int displayId, int[] activityTypes) throws RemoteException {
            return getController().getRootTasks(displayId, activityTypes);
        }

        /** Get the root task which contains the current ime target */
        @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
        public static IWindowContainer getImeTarget(int display) throws RemoteException {
            return getController().getImeTarget(display);
        }

        /**
         * Set's the root task to launch new tasks into on a display. {@code null} means no launch
         * root and thus new tasks just end up directly on the display.
         */
        @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
        public static void setLaunchRoot(int displayId, IWindowContainer root)
                throws RemoteException {
            getController().setLaunchRoot(displayId, root);
        }

        /**
         * Requests that the given task organizer is notified when back is pressed on the root
         * activity of one of its controlled tasks.
         */
        @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
        public static void setInterceptBackPressedOnTaskRoot(ITaskOrganizer organizer,
                boolean interceptBackPressed) throws RemoteException {
            getController().setInterceptBackPressedOnTaskRoot(organizer, interceptBackPressed);
        }

        private static ITaskOrganizerController getController() {
            return ITaskOrganizerControllerSingleton.get();
        }

        private static final Singleton<ITaskOrganizerController> ITaskOrganizerControllerSingleton =
                new Singleton<ITaskOrganizerController>() {
                    @Override
                    protected ITaskOrganizerController create() {
                        try {
                            return getWindowOrganizerController().getTaskOrganizerController();
                        } catch (RemoteException e) {
                            return null;
                        }
                    }
                };
    }

    /** Class for organizing display areas. */
    public static class DisplayAreaOrganizer {

        public static final int FEATURE_UNDEFINED = -1;
        public static final int FEATURE_SYSTEM_FIRST = 0;
        // The Root display area on a display
        public static final int FEATURE_ROOT = FEATURE_SYSTEM_FIRST;
        // Display area hosting the task container.
        public static final int FEATURE_TASK_CONTAINER = FEATURE_SYSTEM_FIRST + 1;
        // Display area hosting non-activity window tokens.
        public static final int FEATURE_WINDOW_TOKENS = FEATURE_SYSTEM_FIRST + 2;

        public static final int FEATURE_SYSTEM_LAST = 10_000;

        // Vendor specific display area definition can start with this value.
        public static final int FEATURE_VENDOR_FIRST = FEATURE_SYSTEM_LAST + 1;

        /** @hide */
        @RequiresPermission(android.Manifest.permission.MANAGE_ACTIVITY_STACKS)
        public static void registerOrganizer(
                IDisplayAreaOrganizer organizer, int displayAreaFeature) throws RemoteException {
            getController().registerOrganizer(organizer, displayAreaFeature);
        }

        /** @hide */
        private static IDisplayAreaOrganizerController getController() {
            return IDisplayAreaOrganizerControllerSingleton.get();
        }

        private static final Singleton<IDisplayAreaOrganizerController>
                IDisplayAreaOrganizerControllerSingleton =
                new Singleton<IDisplayAreaOrganizerController>() {
                    @Override
                    protected IDisplayAreaOrganizerController create() {
                        try {
                            return getWindowOrganizerController()
                                    .getDisplayAreaOrganizerController();
                        } catch (RemoteException e) {
                            return null;
                        }
                    }
                };

    }
}
