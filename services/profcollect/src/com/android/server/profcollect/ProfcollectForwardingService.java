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
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.IBinder.DeathRecipient;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.os.BackgroundThread;
import com.android.server.IoThread;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.art.ArtManagerLocal;
import com.android.server.profcollect.Utils;
import com.android.server.wm.ActivityMetricsLaunchObserver;
import com.android.server.wm.ActivityMetricsLaunchObserverRegistry;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * System-server-local proxy into the {@code IProfcollectd} native service.
 */
public final class ProfcollectForwardingService extends SystemService {
    public static final String LOG_TAG = "ProfcollectForwardingService";

    private static final String INTENT_UPLOAD_PROFILES =
            "com.android.server.profcollect.UPLOAD_PROFILES";
    private static final long BG_PROCESS_INTERVAL = TimeUnit.HOURS.toMillis(4); // every 4 hours.

    private int mUsageSetting;
    private boolean mUploadEnabled;

    private IProfCollectd mIProfcollect;
    private static ProfcollectForwardingService sSelfService;
    private final Handler mHandler = new ProfcollectdHandler(IoThread.getHandler().getLooper());

    private IProviderStatusCallback mProviderStatusCallback = new IProviderStatusCallback.Stub() {
        public void onProviderReady() {
            mHandler.sendEmptyMessage(ProfcollectdHandler.MESSAGE_REGISTER_SCHEDULERS);
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (INTENT_UPLOAD_PROFILES.equals(intent.getAction())) {
                Log.d(LOG_TAG, "Received broadcast to pack and upload reports");
                createAndUploadReport(sSelfService);
            }
        }
    };

    public ProfcollectForwardingService(Context context) {
        super(context);

        if (sSelfService != null) {
            throw new AssertionError("only one service instance allowed");
        }
        sSelfService = this;

        // Get "Usage & diagnostics" checkbox status. 1 is for enabled, 0 is for disabled.
        try {
            mUsageSetting = Settings.Global.getInt(context.getContentResolver(), "multi_cb");
        } catch (SettingNotFoundException e) {
            Log.e(LOG_TAG, "Usage setting not found: " + e.getMessage());
            mUsageSetting = -1;
        }

        mUploadEnabled =
            context.getResources().getBoolean(R.bool.config_profcollectReportUploaderEnabled);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(INTENT_UPLOAD_PROFILES);
        context.registerReceiver(mBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
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
                    .setPeriodic(BG_PROCESS_INTERVAL)
                    .setPriority(JobInfo.PRIORITY_MIN)
                    .build());
        }

        @Override
        public boolean onStartJob(JobParameters params) {
            createAndUploadReport(sSelfService);
            jobFinished(params, false);
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
                    registerCameraOpenObserver();
                    registerDex2oatObserver();
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

    private class AppLaunchObserver extends ActivityMetricsLaunchObserver {
        @Override
        public void onIntentStarted(Intent intent, long timestampNanos) {
            if (mIProfcollect == null) {
                return;
            }
            if (Utils.withFrequency("applaunch_trace_freq", 5)) {
                Utils.traceSystem(mIProfcollect, "applaunch");
            }
        }
    }

    private void registerDex2oatObserver() {
        ArtManagerLocal aml = LocalManagerRegistry.getManager(ArtManagerLocal.class);
        if (aml == null) {
            Log.w(LOG_TAG, "Couldn't get ArtManagerLocal");
            return;
        }
        aml.setBatchDexoptStartCallback(Runnable::run,
                (snapshot, reason, defaultPackages, builder, passedSignal) -> {
                    traceOnDex2oatStart();
                });
    }

    private void traceOnDex2oatStart() {
        if (mIProfcollect == null) {
            return;
        }
        if (Utils.withFrequency("dex2oat_trace_freq", 25)) {
            // Dex2oat could take a while before it starts. Add a short delay before start tracing.
            Utils.traceSystem(mIProfcollect, "dex2oat", /* delayMs */ 1000);
        }
    }

    private void registerOTAObserver() {
        UpdateEngine updateEngine = new UpdateEngine();
        updateEngine.bind(new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int status, float percent) {
                if (status == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT) {
                    createAndUploadReport(sSelfService);
                }
            }

            @Override
            public void onPayloadApplicationComplete(int errorCode) {
                // Ignored
            }
        });
    }

    private static void createAndUploadReport(ProfcollectForwardingService pfs) {
        BackgroundThread.get().getThreadHandler().post(() -> {
            if (pfs.mIProfcollect == null) {
                return;
            }
            String reportName;
            try {
                reportName = pfs.mIProfcollect.report(pfs.mUsageSetting) + ".zip";
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Failed to create report: " + e.getMessage());
                return;
            }
            if (!pfs.mUploadEnabled) {
                Log.i(LOG_TAG, "Upload is not enabled.");
                return;
            }
            Intent intent = new Intent()
                    .setPackage("com.android.shell")
                    .setAction("com.android.shell.action.PROFCOLLECT_UPLOAD")
                    .putExtra("filename", reportName);
            pfs.getContext().sendBroadcast(intent);
        });
    }

    private void registerCameraOpenObserver() {
        CameraManager cm = getContext().getSystemService(CameraManager.class);
        cm.registerAvailabilityCallback(new CameraManager.AvailabilityCallback() {
            @Override
            public void onCameraOpened(String cameraId, String packageId) {
                Log.d(LOG_TAG, "Received camera open event from: " + packageId);
                // Skip face auth since it triggers way too often.
                if (packageId.startsWith("client.pid")) {
                    return;
                }
                // Additional vendor specific list of apps to skip.
                String[] cameraSkipPackages =
                    getContext().getResources().getStringArray(
                        R.array.config_profcollectOnCameraOpenedSkipPackages);
                if (Arrays.asList(cameraSkipPackages).contains(packageId)) {
                    return;
                }
                if (Utils.withFrequency("camera_trace_freq", 10)) {
                    Utils.traceProcess(mIProfcollect,
                            "camera",
                            "android.hardware.camera.provider",
                            /* durationMs */ 5000);
                }
            }
        }, null);
    }
}
