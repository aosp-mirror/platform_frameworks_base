/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.memory;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.os.IMmd;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.time.Duration;

/**
 * Schedules zram maintenance (e.g. zram writeback, zram recompression).
 *
 * <p>ZramMaintenance notifies mmd the good timing to execute zram maintenance based on:
 *
 * <ul>
 * <li>Enough interval has passed.
 * <li>The system is idle.
 * <li>The battery is not low.
 * </ul>
 */
public class ZramMaintenance extends JobService {
    private static final String TAG = ZramMaintenance.class.getName();
    // Job id must be unique across all clients of the same uid. ZramMaintenance uses the bug number
    // as the job id.
    @VisibleForTesting
    public static final int JOB_ID = 375432472;
    private static final ComponentName sZramMaintenance =
            new ComponentName("android", ZramMaintenance.class.getName());
    @VisibleForTesting
    public static final String KEY_CHECK_STATUS = "check_status";

    private static final String SYSTEM_PROPERTY_PREFIX = "mm.";
    private static final String FIRST_DELAY_SECONDS_PROP =
            "zram.maintenance.first_delay_seconds";
    // The default is 1 hour.
    private static final long DEFAULT_FIRST_DELAY_SECONDS = 3600;
    private static final String PERIODIC_DELAY_SECONDS_PROP =
            "zram.maintenance.periodic_delay_seconds";
    // The default is 1 hour.
    private static final long DEFAULT_PERIODIC_DELAY_SECONDS = 3600;
    private static final String REQUIRE_DEVICE_IDLE_PROP =
            "zram.maintenance.require_device_idle";
    private static final boolean DEFAULT_REQUIRE_DEVICE_IDLE =
            true;
    private static final String REQUIRE_BATTERY_NOT_LOW_PROP =
            "zram.maintenance.require_battery_not_low";
    private static final boolean DEFAULT_REQUIRE_BATTERY_NOT_LOW =
            true;

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread("ZramMaintenance") {
            @Override
            public void run() {
                try {
                    IBinder binder = ServiceManager.getService("mmd");
                    IMmd mmd = IMmd.Stub.asInterface(binder);
                    startJob(ZramMaintenance.this, params, mmd);
                } finally {
                    jobFinished(params, false);
                }
            }
        }.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    /**
     * This is public to test ZramMaintenance logic.
     *
     * <p>
     * We need to pass mmd as parameter because we can't mock "IMmd.Stub.asInterface".
     *
     * <p>
     * Since IMmd.isZramMaintenanceSupported() is blocking call, this method should be executed on
     * a worker thread.
     */
    @VisibleForTesting
    public static void startJob(Context context, JobParameters params, IMmd mmd) {
        boolean checkStatus = params.getExtras().getBoolean(KEY_CHECK_STATUS);
        if (mmd != null) {
            try {
                if (checkStatus && !mmd.isZramMaintenanceSupported()) {
                    Slog.i(TAG, "zram maintenance is not supported");
                    return;
                }
                // Status check is required before the first doZramMaintenanceAsync() call once.
                checkStatus = false;

                mmd.doZramMaintenanceAsync();
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to binder call to mmd", e);
            }
        } else {
            Slog.w(TAG, "binder not found");
        }
        Duration delay = Duration.ofSeconds(getLongProperty(PERIODIC_DELAY_SECONDS_PROP,
                DEFAULT_PERIODIC_DELAY_SECONDS));
        scheduleZramMaintenance(context, delay, checkStatus);
    }

    /**
     * Starts periodical zram maintenance.
     */
    public static void startZramMaintenance(Context context) {
        Duration delay = Duration.ofSeconds(
                getLongProperty(FIRST_DELAY_SECONDS_PROP, DEFAULT_FIRST_DELAY_SECONDS));
        scheduleZramMaintenance(context, delay, true);
    }

    private static void scheduleZramMaintenance(Context context, Duration delay,
            boolean checkStatus) {
        JobScheduler js = context.getSystemService(JobScheduler.class);

        if (js != null) {
            final PersistableBundle bundle = new PersistableBundle();
            bundle.putBoolean(KEY_CHECK_STATUS, checkStatus);
            js.schedule(new JobInfo.Builder(JOB_ID, sZramMaintenance)
                    .setMinimumLatency(delay.toMillis())
                    .setRequiresDeviceIdle(
                            getBooleanProperty(REQUIRE_DEVICE_IDLE_PROP,
                                    DEFAULT_REQUIRE_DEVICE_IDLE))
                    .setRequiresBatteryNotLow(
                            getBooleanProperty(REQUIRE_BATTERY_NOT_LOW_PROP,
                                    DEFAULT_REQUIRE_BATTERY_NOT_LOW))
                    .setExtras(bundle)
                    .build());
        }
    }

    private static long getLongProperty(String name, long defaultValue) {
        return DeviceConfig.getLong(DeviceConfig.NAMESPACE_MM, name,
                SystemProperties.getLong(SYSTEM_PROPERTY_PREFIX + name, defaultValue));
    }

    private static boolean getBooleanProperty(String name, boolean defaultValue) {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_MM, name,
                SystemProperties.getBoolean(SYSTEM_PROPERTY_PREFIX + name, defaultValue));
    }
}
