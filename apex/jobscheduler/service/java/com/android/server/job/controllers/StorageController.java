/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.job.controllers;

import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateControllerProto;
import com.android.server.storage.DeviceStorageMonitorService;

import java.util.function.Predicate;

/**
 * Simple controller that tracks the status of the device's storage.
 */
public final class StorageController extends StateController {
    private static final String TAG = "JobScheduler.Storage";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private final ArraySet<JobStatus> mTrackedTasks = new ArraySet<JobStatus>();
    private final StorageTracker mStorageTracker;

    @VisibleForTesting
    public StorageTracker getTracker() {
        return mStorageTracker;
    }

    public StorageController(JobSchedulerService service) {
        super(service);
        mStorageTracker = new StorageTracker();
        mStorageTracker.startTracking();
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus taskStatus, JobStatus lastJob) {
        if (taskStatus.hasStorageNotLowConstraint()) {
            final long nowElapsed = sElapsedRealtimeClock.millis();
            mTrackedTasks.add(taskStatus);
            taskStatus.setTrackingController(JobStatus.TRACKING_STORAGE);
            taskStatus.setStorageNotLowConstraintSatisfied(
                    nowElapsed, mStorageTracker.isStorageNotLow());
        }
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus taskStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if (taskStatus.clearTrackingController(JobStatus.TRACKING_STORAGE)) {
            mTrackedTasks.remove(taskStatus);
        }
    }

    private void maybeReportNewStorageState() {
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final boolean storageNotLow = mStorageTracker.isStorageNotLow();
        boolean reportChange = false;
        synchronized (mLock) {
            for (int i = mTrackedTasks.size() - 1; i >= 0; i--) {
                final JobStatus ts = mTrackedTasks.valueAt(i);
                reportChange |= ts.setStorageNotLowConstraintSatisfied(nowElapsed, storageNotLow);
            }
        }
        if (storageNotLow) {
            // Tell the scheduler that any ready jobs should be flushed.
            mStateChangedListener.onRunJobNow(null);
        } else if (reportChange) {
            // Let the scheduler know that state has changed. This may or may not result in an
            // execution.
            mStateChangedListener.onControllerStateChanged(mTrackedTasks);
        }
    }

    public final class StorageTracker extends BroadcastReceiver {
        /**
         * Track whether storage is low.
         */
        private boolean mStorageLow;
        /** Sequence number of last broadcast. */
        private int mLastStorageSeq = -1;

        public StorageTracker() {
        }

        public void startTracking() {
            IntentFilter filter = new IntentFilter();

            // Storage status.  Just need to register, since STORAGE_LOW is a sticky
            // broadcast we will receive that if it is currently active.
            filter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
            filter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
            mContext.registerReceiver(this, filter);
        }

        public boolean isStorageNotLow() {
            return !mStorageLow;
        }

        public int getSeq() {
            return mLastStorageSeq;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            onReceiveInternal(intent);
        }

        @VisibleForTesting
        public void onReceiveInternal(Intent intent) {
            final String action = intent.getAction();
            mLastStorageSeq = intent.getIntExtra(DeviceStorageMonitorService.EXTRA_SEQUENCE,
                    mLastStorageSeq);
            if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
                if (DEBUG) {
                    Slog.d(TAG, "Available storage too low to do work. @ "
                            + sElapsedRealtimeClock.millis());
                }
                mStorageLow = true;
                maybeReportNewStorageState();
            } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                if (DEBUG) {
                    Slog.d(TAG, "Available storage high enough to do work. @ "
                            + sElapsedRealtimeClock.millis());
                }
                mStorageLow = false;
                maybeReportNewStorageState();
            }
        }
    }

    @Override
    public void dumpControllerStateLocked(IndentingPrintWriter pw,
            Predicate<JobStatus> predicate) {
        pw.println("Not low: " + mStorageTracker.isStorageNotLow());
        pw.println("Sequence: " + mStorageTracker.getSeq());
        pw.println();

        for (int i = 0; i < mTrackedTasks.size(); i++) {
            final JobStatus js = mTrackedTasks.valueAt(i);
            if (!predicate.test(js)) {
                continue;
            }
            pw.print("#");
            js.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, js.getSourceUid());
            pw.println();
        }
    }

    @Override
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate) {
        final long token = proto.start(fieldId);
        final long mToken = proto.start(StateControllerProto.STORAGE);

        proto.write(StateControllerProto.StorageController.IS_STORAGE_NOT_LOW,
                mStorageTracker.isStorageNotLow());
        proto.write(StateControllerProto.StorageController.LAST_BROADCAST_SEQUENCE_NUMBER,
                mStorageTracker.getSeq());

        for (int i = 0; i < mTrackedTasks.size(); i++) {
            final JobStatus js = mTrackedTasks.valueAt(i);
            if (!predicate.test(js)) {
                continue;
            }
            final long jsToken = proto.start(StateControllerProto.StorageController.TRACKED_JOBS);
            js.writeToShortProto(proto, StateControllerProto.StorageController.TrackedJob.INFO);
            proto.write(StateControllerProto.StorageController.TrackedJob.SOURCE_UID,
                    js.getSourceUid());
            proto.end(jsToken);
        }

        proto.end(mToken);
        proto.end(token);
    }
}
