/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.cpu;

import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;

import android.content.Context;
import android.os.Binder;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;
import com.android.server.utils.PriorityDump;
import com.android.server.utils.Slogf;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Service to monitor CPU availability and usage. */
public final class CpuMonitorService extends SystemService {
    static final String TAG = CpuMonitorService.class.getSimpleName();
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    // TODO(b/242722241): Make this a resource overlay property.
    //  Maintain 3 monitoring intervals:
    //  * One to poll very frequently when mCpuAvailabilityCallbackInfoByCallbacks are available and
    //    CPU availability is above a threshold (such as at least 10% of CPU is available).
    //  * One to poll less frequently when mCpuAvailabilityCallbackInfoByCallbacks are available
    //    and CPU availability is below a threshold (such as less than 10% of CPU is available).
    //  * One to poll very less frequently when no callbacks are available and the build is either
    //    user-debug or eng. This will be useful for debugging in development environment.
    static final int DEFAULT_CPU_MONITORING_INTERVAL_MILLISECONDS = 5_000;

    private final Context mContext;
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArrayMap<CpuMonitorInternal.CpuAvailabilityCallback, CpuAvailabilityCallbackInfo>
            mCpuAvailabilityCallbackInfoByCallbacks = new ArrayMap<>();
    @GuardedBy("mLock")
    private long mMonitoringIntervalMilliseconds = DEFAULT_CPU_MONITORING_INTERVAL_MILLISECONDS;

    private final CpuMonitorInternal mLocalService = new CpuMonitorInternal() {
        @Override
        public void addCpuAvailabilityCallback(Executor executor,
                CpuAvailabilityMonitoringConfig config, CpuAvailabilityCallback callback) {
            Objects.requireNonNull(callback, "Callback must be non-null");
            Objects.requireNonNull(config, "Config must be non-null");
            synchronized (mLock) {
                if (mCpuAvailabilityCallbackInfoByCallbacks.containsKey(callback)) {
                    Slogf.i(TAG, "Overwriting the existing CpuAvailabilityCallback %s",
                            mCpuAvailabilityCallbackInfoByCallbacks.get(callback));
                    // TODO(b/242722241): Overwrite any internal cache (will be added in future CLs)
                    //  that maps callbacks based on the CPU availability thresholds.
                }
                CpuAvailabilityCallbackInfo info = new CpuAvailabilityCallbackInfo(config,
                        executor);
                mCpuAvailabilityCallbackInfoByCallbacks.put(callback, info);
                if (DEBUG) {
                    Slogf.d(TAG, "Added a CPU availability callback: %s", info);
                }
            }
            // TODO(b/242722241):
            //  * On the executor or on the handler thread, call the callback with the latest CPU
            //    availability info and monitoring interval.
            //  * Monitor the CPU stats more frequently when the first callback is added.
        }

        @Override
        public void removeCpuAvailabilityCallback(CpuAvailabilityCallback callback) {
            synchronized (mLock) {
                if (!mCpuAvailabilityCallbackInfoByCallbacks.containsKey(callback)) {
                    Slogf.i(TAG, "CpuAvailabilityCallback was not previously added."
                            + " Ignoring the remove request");
                    return;
                }
                CpuAvailabilityCallbackInfo info =
                        mCpuAvailabilityCallbackInfoByCallbacks.remove(callback);
                if (DEBUG) {
                    Slogf.d(TAG, "Removed a CPU availability callback: %s", info);
                }
            }
            // TODO(b/242722241): Increase CPU monitoring interval when all callbacks are removed.
        }
    };

    public CpuMonitorService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishLocalService(CpuMonitorInternal.class, mLocalService);
        publishBinderService("cpu_monitor", new CpuMonitorBinder(), /* allowIsolated= */ false,
                DUMP_FLAG_PRIORITY_CRITICAL);
    }

    private void doDump(IndentingPrintWriter writer) {
        writer.printf("*%s*\n", getClass().getSimpleName());
        writer.increaseIndent();
        synchronized (mLock) {
            writer.printf("CPU monitoring interval: %d ms\n", mMonitoringIntervalMilliseconds);
            if (!mCpuAvailabilityCallbackInfoByCallbacks.isEmpty()) {
                writer.println("CPU availability change callbacks:");
                writer.increaseIndent();
                for (int i = 0; i < mCpuAvailabilityCallbackInfoByCallbacks.size(); i++) {
                    writer.printf("%s: %s\n", mCpuAvailabilityCallbackInfoByCallbacks.keyAt(i),
                            mCpuAvailabilityCallbackInfoByCallbacks.valueAt(i));
                }
                writer.decreaseIndent();
            }
        }
        // TODO(b/242722241): Print the recent past CPU stats.
        writer.decreaseIndent();
    }

    private static final class CpuAvailabilityCallbackInfo {
        public final CpuAvailabilityMonitoringConfig config;
        public final Executor executor;

        CpuAvailabilityCallbackInfo(CpuAvailabilityMonitoringConfig config,
                Executor executor) {
            this.config = config;
            this.executor = executor;
        }

        @Override
        public String toString() {
            return "CpuAvailabilityCallbackInfo{" + "config=" + config + ", mExecutor=" + executor
                    + '}';
        }
    }

    private final class CpuMonitorBinder extends Binder {
        private final PriorityDump.PriorityDumper mPriorityDumper =
                new PriorityDump.PriorityDumper() {
                    @Override
                    public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args,
                            boolean asProto) {
                        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)
                                || asProto) {
                            return;
                        }
                        try (IndentingPrintWriter ipw = new IndentingPrintWriter(pw)) {
                            doDump(ipw);
                        }
                    }
                };

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            PriorityDump.dump(mPriorityDumper, fd, pw, args);
        }
    }
}
