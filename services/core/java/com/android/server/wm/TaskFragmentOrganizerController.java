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
import android.util.ArraySet;
import android.view.SurfaceControl;
import android.window.ITaskFragmentOrganizer;
import android.window.ITaskFragmentOrganizerController;
import android.window.TaskFragmentAppearedInfo;
import android.window.TaskFragmentInfo;

import com.android.internal.protolog.common.ProtoLog;

import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Stores and manages the client {@link android.window.TaskFragmentOrganizer}.
 */
public class TaskFragmentOrganizerController extends ITaskFragmentOrganizerController.Stub {
    private static final String TAG = "TaskFragmentOrganizerController";

    private final ActivityTaskManagerService mAtmService;
    private final WindowManagerGlobalLock mGlobalLock;
    private final Set<ITaskFragmentOrganizer> mOrganizers = new ArraySet<>();
    private final Map<ITaskFragmentOrganizer, DeathRecipient> mDeathRecipients = new ArrayMap<>();
    private final Map<TaskFragment, TaskFragmentInfo> mLastSentTaskFragmentInfos =
            new WeakHashMap<>();
    private final Map<TaskFragment, Configuration> mLastSentTaskFragmentParentConfigs =
            new WeakHashMap<>();

    private class DeathRecipient implements IBinder.DeathRecipient {
        final ITaskFragmentOrganizer mOrganizer;

        DeathRecipient(ITaskFragmentOrganizer organizer) {
            mOrganizer = organizer;
        }

        @Override
        public void binderDied() {
            removeOrganizer(mOrganizer);
        }
    }

    TaskFragmentOrganizerController(ActivityTaskManagerService atm) {
        mAtmService = atm;
        mGlobalLock = atm.mGlobalLock;
    }

    @Override
    public void registerOrganizer(ITaskFragmentOrganizer organizer) {
        final int pid = Binder.getCallingPid();
        final long uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                        "Register task fragment organizer=%s uid=%d pid=%d",
                        organizer.asBinder(), uid, pid);
                if (mOrganizers.contains(organizer)) {
                    throw new IllegalStateException(
                            "Replacing existing organizer currently unsupported");
                }

                final DeathRecipient dr = new DeathRecipient(organizer);
                try {
                    organizer.asBinder().linkToDeath(dr, 0);
                } catch (RemoteException e) {
                    // Oh well...
                }

                mOrganizers.add(organizer);
                mDeathRecipients.put(organizer, dr);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void unregisterOrganizer(ITaskFragmentOrganizer organizer) {
        final int pid = Binder.getCallingPid();
        final long uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mGlobalLock) {
                ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER,
                        "Unregister task fragment organizer=%s uid=%d pid=%d",
                        organizer.asBinder(), uid, pid);
                if (!mOrganizers.contains(organizer)) {
                    throw new IllegalStateException(
                            "The task fragment organizer hasn't been registered.");
                }

                final DeathRecipient dr = mDeathRecipients.get(organizer);
                organizer.asBinder().unlinkToDeath(dr, 0);

                removeOrganizer(organizer);
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    void onTaskFragmentAppeared(ITaskFragmentOrganizer organizer, TaskFragment tf) {
        validateOrganizer(organizer);

        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment appeared name=%s", tf.getName());
        final TaskFragmentInfo info = tf.getTaskFragmentInfo();
        final SurfaceControl outSurfaceControl = new SurfaceControl(tf.getSurfaceControl(),
                "TaskFragmentOrganizerController.onTaskFragmentInfoAppeared");
        try {
            organizer.onTaskFragmentAppeared(
                    new TaskFragmentAppearedInfo(info, outSurfaceControl));
            mLastSentTaskFragmentInfos.put(tf, info);
        } catch (RemoteException e) {
            // Oh well...
        }
    }

    void onTaskFragmentInfoChanged(ITaskFragmentOrganizer organizer, TaskFragment tf) {
        validateOrganizer(organizer);

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
            // Oh well...
        }
    }

    void onTaskFragmentVanished(ITaskFragmentOrganizer organizer, TaskFragment tf) {
        validateOrganizer(organizer);

        ProtoLog.v(WM_DEBUG_WINDOW_ORGANIZER, "TaskFragment vanished name=%s", tf.getName());
        try {
            organizer.onTaskFragmentVanished(tf.getTaskFragmentInfo());
        } catch (RemoteException e) {
            // Oh well...
        }
        mLastSentTaskFragmentInfos.remove(tf);
        mLastSentTaskFragmentParentConfigs.remove(tf);
    }

    void onTaskFragmentParentInfoChanged(ITaskFragmentOrganizer organizer, TaskFragment tf) {
        validateOrganizer(organizer);

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
            mLastSentTaskFragmentParentConfigs.put(tf, parentConfig);
        } catch (RemoteException e) {
            // Oh well...
        }
    }

    private void removeOrganizer(ITaskFragmentOrganizer organizer) {
        synchronized (mGlobalLock) {
            mOrganizers.remove(organizer);
            mDeathRecipients.remove(organizer);
        }
        // TODO(b/190432728) move child activities of organized TaskFragment to leaf Task
    }

    /**
     * Makes sure that the organizer has been correctly registered to prevent any Sidecar
     * implementation from organizing {@link TaskFragment} without registering first. In such case,
     * we wouldn't register {@link DeathRecipient} for the organizer, and might not remove the
     * {@link TaskFragment} after the organizer process died.
     */
    private void validateOrganizer(ITaskFragmentOrganizer organizer) {
        if (!mOrganizers.contains(organizer)) {
            throw new IllegalArgumentException(
                    "TaskFragmentOrganizer has not been registered. Organizer=" + organizer);
        }
    }
}
