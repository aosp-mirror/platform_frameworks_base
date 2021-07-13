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

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WINDOW_ORGANIZER;
import static com.android.server.wm.WindowOrganizerController.configurationsAreEqualForOrganizer;

import android.content.res.Configuration;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.ITaskFragmentOrganizer;
import android.window.ITaskFragmentOrganizerController;
import android.window.TaskFragmentAppearedInfo;
import android.window.TaskFragmentInfo;

import com.android.internal.protolog.common.ProtoLog;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stores and manages the client {@link android.window.TaskFragmentOrganizer}.
 */
public class TaskFragmentOrganizerController extends ITaskFragmentOrganizerController.Stub {
    private static final String TAG = "TaskFragmentOrganizerController";

    private final ActivityTaskManagerService mAtmService;
    private final WindowManagerGlobalLock mGlobalLock;
    private final Map<TaskFragment, TaskFragmentInfo> mLastSentTaskFragmentInfos =
            new WeakHashMap<>();
    private final Map<TaskFragment, Configuration> mLastSentTaskFragmentParentConfigs =
            new WeakHashMap<>();
    /**
     * A Map which manages the relationship between
     * {@link ITaskFragmentOrganizer} and {@link TaskFragmentOrganizerState}
     */
    private final ArrayMap<IBinder, TaskFragmentController> mTaskFragmentOrganizerControllers =
            new ArrayMap<>();

    TaskFragmentOrganizerController(ActivityTaskManagerService atm) {
        mAtmService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    /**
     * A class to manage {@link ITaskFragmentOrganizer} and its organized
     * {@link TaskFragment TaskFragments}.
     */
    private class TaskFragmentController implements IBinder.DeathRecipient {
        private final ArrayList<TaskFragment> mOrganizedTaskFragments = new ArrayList<>();
        private final ITaskFragmentOrganizer mOrganizer;

        TaskFragmentController(ITaskFragmentOrganizer organizer) {
            mOrganizer = organizer;
            try {
                mOrganizer.asBinder().linkToDeath(this, 0 /*flags*/);
            } catch (RemoteException e) {
                Slog.e(TAG, "TaskFragmentOrganizer failed to register death recipient");
            }
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                removeOrganizer(mOrganizer);
            }
        }

        void addTaskFragment(TaskFragment taskFragment) {
            if (!mOrganizedTaskFragments.contains(taskFragment)) {
                mOrganizedTaskFragments.add(taskFragment);
            }
        }

        void removeTaskFragment(TaskFragment taskFragment) {
            mOrganizedTaskFragments.remove(taskFragment);
        }

        void dispose() {
            mOrganizedTaskFragments.forEach(TaskFragment::removeImmediately);
            mOrganizedTaskFragments.clear();
            mOrganizer.asBinder().unlinkToDeath(this, 0 /*flags*/);
        }
    }

    @Override
    public void registerOrganizer(ITaskFragmentOrganizer organizer) {
        final int pid = Binder.getCallingPid();
        final long uid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Register task fragment organizer=%s uid=%d pid=%d",
                    organizer.asBinder(), uid, pid);
            if (mTaskFragmentOrganizerControllers.containsKey(organizer.asBinder())) {
                throw new IllegalStateException(
                        "Replacing existing organizer currently unsupported");
            }
            mTaskFragmentOrganizerControllers.put(organizer.asBinder(),
                    new TaskFragmentController(organizer));
        }
    }

    @Override
    public void unregisterOrganizer(ITaskFragmentOrganizer organizer) {
        validateAndGetController(organizer);
        final int pid = Binder.getCallingPid();
        final long uid = Binder.getCallingUid();
        synchronized (mGlobalLock) {
            ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                    "Unregister task fragment organizer=%s uid=%d pid=%d",
                    organizer.asBinder(), uid, pid);
            removeOrganizer(organizer);
        }
    }

    void onTaskFragmentAppeared(ITaskFragmentOrganizer organizer, TaskFragment tf) {
        final TaskFragmentController controller = validateAndGetController(organizer);

        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment appeared name=%s", tf.getName());
        final TaskFragmentInfo info = tf.getTaskFragmentInfo();
        final SurfaceControl outSurfaceControl = new SurfaceControl(tf.getSurfaceControl(),
                "TaskFragmentOrganizerController.onTaskFragmentInfoAppeared");
        controller.addTaskFragment(tf);
        try {
            organizer.onTaskFragmentAppeared(
                    new TaskFragmentAppearedInfo(info, outSurfaceControl));
            mLastSentTaskFragmentInfos.put(tf, info);
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception sending onTaskFragmentAppeared callback", e);
        }
    }

    void onTaskFragmentInfoChanged(ITaskFragmentOrganizer organizer, TaskFragment tf) {
        validateAndGetController(organizer);

        // Check if the info is different from the last reported info.
        final TaskFragmentInfo info = tf.getTaskFragmentInfo();
        final TaskFragmentInfo lastInfo = mLastSentTaskFragmentInfos.get(tf);
        if (info.equalsForTaskFragmentOrganizer(lastInfo) && configurationsAreEqualForOrganizer(
                info.getConfiguration(), lastInfo.getConfiguration())) {
            return;
        }

        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment info changed name=%s", tf.getName());
        try {
            organizer.onTaskFragmentInfoChanged(tf.getTaskFragmentInfo());
            mLastSentTaskFragmentInfos.put(tf, info);
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception sending onTaskFragmentInfoChanged callback", e);
        }
    }

    void onTaskFragmentVanished(ITaskFragmentOrganizer organizer, TaskFragment tf) {
        final TaskFragmentController controller = validateAndGetController(organizer);

        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment vanished name=%s", tf.getName());
        try {
            organizer.onTaskFragmentVanished(tf.getTaskFragmentInfo());
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception sending onTaskFragmentVanished callback", e);
        }
        mLastSentTaskFragmentInfos.remove(tf);
        mLastSentTaskFragmentParentConfigs.remove(tf);
        controller.removeTaskFragment(tf);
    }

    void onTaskFragmentParentInfoChanged(ITaskFragmentOrganizer organizer, TaskFragment tf) {
        validateAndGetController(organizer);

        // Check if the parent info is different from the last reported parent info.
        if (tf.getParent() == null || tf.getParent().asTask() == null) {
            mLastSentTaskFragmentParentConfigs.remove(tf);
            return;
        }
        final Task parent = tf.getParent().asTask();
        final Configuration parentConfig = parent.getConfiguration();
        final Configuration lastParentConfig = mLastSentTaskFragmentParentConfigs.get(tf);
        if (configurationsAreEqualForOrganizer(parentConfig, lastParentConfig)) {
            return;
        }

        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                "TaskFragment parent info changed name=%s parentTaskId=%d",
                tf.getName(), parent.mTaskId);
        try {
            organizer.onTaskFragmentParentInfoChanged(tf.getFragmentToken(), parentConfig);
            mLastSentTaskFragmentParentConfigs.put(tf, new Configuration(parentConfig));
        } catch (RemoteException e) {
            Slog.e(TAG, "Exception sending onTaskFragmentParentInfoChanged callback", e);
        }
    }

    private void removeOrganizer(ITaskFragmentOrganizer organizer) {
        final TaskFragmentController controller = validateAndGetController(organizer);
        // remove all of the children of the organized TaskFragment
        controller.dispose();
        mTaskFragmentOrganizerControllers.remove(organizer.asBinder());
    }

    /**
     * Makes sure that the organizer has been correctly registered to prevent any Sidecar
     * implementation from organizing {@link TaskFragment} without registering first. In such case,
     * we wouldn't register {@link DeathRecipient} for the organizer, and might not remove the
     * {@link TaskFragment} after the organizer process died.
     */
    private TaskFragmentController validateAndGetController(ITaskFragmentOrganizer organizer) {
        final TaskFragmentController controller =
                mTaskFragmentOrganizerControllers.get(organizer.asBinder());
        if (controller == null) {
            throw new IllegalArgumentException(
                    "TaskFragmentOrganizer has not been registered. Organizer=" + organizer);
        }
        return controller;
    }
}
