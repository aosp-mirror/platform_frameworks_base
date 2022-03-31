/**
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

package com.android.server.profcollect;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder.DeathRecipient;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.os.BackgroundThread;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityMetricsLaunchObserver;
import com.android.server.wm.ActivityMetricsLaunchObserverRegistry;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * System-server-local proxy into the {@code IProfcollectd} native service.
 */
public final class ProfcollectForwardingService extends SystemService {
    public static final String LOG_TAG = "ProfcollectForwardingService";

    private static final boolean DEBUG = Log.isLoggable(LOG_TAG, Log.DEBUG);

    private static final long BG_PROCESS_PERIOD = TimeUnit.HOURS.toMillis(4); // every 4 hours.

    private IProfCollectd mIProfcollect;
    private static ProfcollectForwardingService sSelfService;
    private final Handler mHandler = new ProfcollectdHandler(IoThread.getHandler().getLooper());

    private IProviderStatusCallback mProviderStatusCallback = new IProviderStatusCallback.Stub() {
        public void onProviderReady() {
            mHandler.sendEmptyMessage(ProfcollectdHandler.MESSAGE_REGISTER_SCHEDULERS);
        }
    };

    public ProfcollectForwardingService(Context context) {
        super(context);

        if (sSelfService != null) {
            throw new AssertionError("only one service instance allowed");
        }
        sSelfService = this;
    }

    /**
     * Check whether profcollect is enabled through device config.
     */
    public static boolean enabled() {
        return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PROFCOLLECT_NATIVE_BOOT, "enabled",
            false) || SystemProperties.getBoolean("persist.profcollectd.enabled_override", false);
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Log.d(LOG_TAG, "Profcollect forwarding service start");
        }
        connectNativeService();
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            if (mIProfcollect == null) {
                return;
            }
            BackgroundThread.get().getThreadHandler().post(() -> {
                if (serviceHasSupportedTraceProvider()) {
                    registerProviderStatusCallback();
                }
            });
        }
    }

    private void registerProviderStatusCallback() {
        if (mIProfcollect == null) {
            return;
        }
        try {
            mIProfcollect.registerProviderStatusCallback(mProviderStatusCallback);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Failed to register provider status callback: " + e.getMessage());
        }
    }

    private boolean serviceHasSupportedTraceProvider() {
        if (mIProfcollect == null) {
            return false;
        }
        try {
            return !mIProfcollect.get_supported_provider().isEmpty();
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Failed to get supported provider: " + e.getMessage());
            return false;
        }
    }

    private boolean tryConnectNativeService() {
        if (connectNativeService()) {
            return true;
        }
        // Cannot connect to the native service at this time, retry after a short delay.
        mHandler.sendEmptyMessageDelayed(ProfcollectdHandler.MESSAGE_BINDER_CONNECT, 5000);
        return false;
    }

    private boolean connectNativeService() {
        try {
            IProfCollectd profcollectd =
                    IProfCollectd.Stub.asInterface(
                            ServiceManager.getServiceOrThrow("profcollectd"));
            profcollectd.asBinder().linkToDeath(new ProfcollectdDeathRecipient(), /*flags*/0);
            mIProfcollect = profcollectd;
            return true;
        } catch (ServiceManager.ServiceNotFoundException | RemoteException e) {
            Log.w(LOG_TAG, "Failed to connect profcollectd binder service.");
            return false;
        }
    }

    private class ProfcollectdHandler extends Handler {
        public ProfcollectdHandler(Looper looper) {
            super(looper);
        }

        public static final int MESSAGE_BINDER_CONNECT = 0;
        public static final int MESSAGE_REGISTER_SCHEDULERS = 1;

        @Override
        public void handleMessage(android.os.Message message) {
            switch (message.what) {
                case MESSAGE_BINDER_CONNECT:
                    connectNativeService();
                    break;
                case MESSAGE_REGISTER_SCHEDULERS:
                    registerObservers();
                    ProfcollectBGJobService.schedule(getContext());
                    break;
                default:
                    throw new AssertionError("Unknown message: " + message);
            }
        }
    }

    private class ProfcollectdDeathRecipient implements DeathRecipient {
        @Override
        public void binderDied() {
            Log.w(LOG_TAG, "profcollectd has died");

            mIProfcollect = null;
            tryConnectNativeService();
        }
    }

    /**
     * Background trace process service.
     */
    public static class ProfcollectBGJobService extends JobService {
        // Unique ID in system service
        private static final int JOB_IDLE_PROCESS = 260817;
        private static final ComponentName JOB_SERVICE_NAME = new ComponentName(
                "android",
                ProfcollectBGJobService.class.getName());

        /**
         * Attach the service to the system job scheduler.
         */
        public static void schedule(Context context) {
            JobScheduler js = context.getSystemService(JobScheduler.class);

            js.schedule(new JobInfo.Builder(JOB_IDLE_PROCESS, JOB_SERVICE_NAME)
                    .setRequiresDeviceIdle(true)
                    .setRequiresCharging(true)
                    .setPeriodic(BG_PROCESS_PERIOD)
                    .build());
        }

        @Override
        public boolean onStartJob(JobParameters params) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Starting background process job");
            }

            BackgroundThread.get().getThreadHandler().post(
                    () -> {
                        try {
                            sSelfService.mIProfcollect.process();
                        } catch (RemoteException e) {
                            Log.e(LOG_TAG, "Failed to process profiles in background: "
                                    + e.getMessage());
                        }
                    });
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            // TODO: Handle this?
            return false;
        }
    }

    // Event observers
    private void registerObservers() {
        BackgroundThread.get().getThreadHandler().post(
                () -> {
                    registerAppLaunchObserver();
                    registerOTAObserver();
                });
    }

    private final AppLaunchObserver mAppLaunchObserver = new AppLaunchObserver();
    private void registerAppLaunchObserver() {
        ActivityTaskManagerInternal atmInternal =
                LocalServices.getService(ActivityTaskManagerInternal.class);
        ActivityMetricsLaunchObserverRegistry launchObserverRegistry =
                atmInternal.getLaunchObserverRegistry();
        launchObserverRegistry.registerLaunchObserver(mAppLaunchObserver);
    }

    private void traceOnAppStart(String packageName) {
        if (mIProfcollect == null) {
            return;
        }

        // Sample for a fraction of app launches.
        int traceFrequency = DeviceConfig.getInt(DeviceConfig.NAMESPACE_PROFCOLLECT_NATIVE_BOOT,
                "applaunch_trace_freq", 2);
        int randomNum = ThreadLocalRandom.current().nextInt(100);
        if (randomNum < traceFrequency) {
            if (DEBUG) {
                Log.d(LOG_TAG, "Tracing on app launch event: " + packageName);
            }
            BackgroundThread.get().getThreadHandler().post(() -> {
                try {
                    mIProfcollect.trace_once("applaunch");
                } catch (RemoteException e) {
                    Log.e(LOG_TAG, "Failed to initiate trace: " + e.getMessage());
                }
            });
        }
    }

    private class AppLaunchObserver implements ActivityMetricsLaunchObserver {
        @Override
        public void onIntentStarted(Intent intent, long timestampNanos) {
            traceOnAppStart(intent.getPackage());
        }

        @Override
        public void onIntentFailed() {
            // Ignored
        }

        @Override
        public void onActivityLaunched(byte[] activity, int temperature) {
            // Ignored
        }

        @Override
        public void onActivityLaunchCancelled(byte[] abortingActivity) {
            // Ignored
        }

        @Override
        public void onActivityLaunchFinished(byte[] finalActivity, long timestampNanos) {
            // Ignored
        }

        @Override
        public void onReportFullyDrawn(byte[] activity, long timestampNanos) {
            // Ignored
        }
    }

    private void registerOTAObserver() {
        UpdateEngine updateEngine = new UpdateEngine();
        updateEngine.bind(new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int status, float percent) {
                if (DEBUG) {
                    Log.d(LOG_TAG, "Received OTA status update, status: " + status + ", percent: "
                            + percent);
                }

                if (status == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT) {
                    packProfileReport();
                }
            }

            @Override
            public void onPayloadApplicationComplete(int errorCode) {
                // Ignored
            }
        });
    }

    private void packProfileReport() {
        if (mIProfcollect == null) {
            return;
        }

        Context context = getContext();
        BackgroundThread.get().getThreadHandler().post(() -> {
            try {
                // Prepare profile report
                String reportName = mIProfcollect.report() + ".zip";

                if (!context.getResources().getBoolean(
                        R.bool.config_profcollectReportUploaderEnabled)) {
                    Log.i(LOG_TAG, "Upload is not enabled.");
                    return;
                }

                // Upload the report
                Intent intent = new Intent()
                        .setPackage("com.android.shell")
                        .setAction("com.android.shell.action.PROFCOLLECT_UPLOAD")
                        .putExtra("filename", reportName);
                context.sendBroadcast(intent);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Failed to upload report: " + e.getMessage());
            }
        });
    }
}
