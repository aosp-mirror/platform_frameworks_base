/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.startop.iorap;
// TODO: rename to com.android.server.startop.iorap

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Handler;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.BackgroundDexOptService;
import com.android.server.wm.ActivityMetricsLaunchObserver;
import com.android.server.wm.ActivityMetricsLaunchObserver.ActivityRecordProto;
import com.android.server.wm.ActivityMetricsLaunchObserver.Temperature;
import com.android.server.wm.ActivityMetricsLaunchObserverRegistry;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;

/**
 * System-server-local proxy into the {@code IIorap} native service.
 */
public class IorapForwardingService extends SystemService {

    public static final String TAG = "IorapForwardingService";
    /** $> adb shell 'setprop log.tag.IorapForwardingService VERBOSE' */
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    /** $> adb shell 'setprop ro.iorapd.enable true' */
    private static boolean IS_ENABLED = SystemProperties.getBoolean("ro.iorapd.enable", true);
    /** $> adb shell 'setprop iorapd.forwarding_service.wtf_crash true' */
    private static boolean WTF_CRASH = SystemProperties.getBoolean(
            "iorapd.forwarding_service.wtf_crash", false);

    // "Unique" job ID from the service name. Also equal to 283673059.
    public static final int JOB_ID_IORAPD = encodeEnglishAlphabetStringIntoInt("iorapd");
    // Run every 24 hours.
    public static final long JOB_INTERVAL_MS = TimeUnit.HOURS.toMillis(24);

    private IIorap mIorapRemote;
    private final Object mLock = new Object();
    /** Handle onBinderDeath by periodically trying to reconnect. */
    private final Handler mHandler =
            new BinderConnectionHandler(IoThread.getHandler().getLooper());

    private volatile IorapdJobService mJobService;  // Write-once (null -> non-null forever).
    private volatile static IorapForwardingService sSelfService;  // Write once (null -> non-null).

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public IorapForwardingService(Context context) {
        super(context);

        if (DEBUG) {
            Log.v(TAG, "IorapForwardingService (Context=" + context.toString() + ")");
        }

        if (sSelfService != null) {
            throw new AssertionError("only one service instance allowed");
        }
        sSelfService = this;
    }

    //<editor-fold desc="Providers">
    /*
     * Providers for external dependencies:
     * - These are marked as protected to allow tests to inject different values via mocks.
     */

    @VisibleForTesting
    protected ActivityMetricsLaunchObserverRegistry provideLaunchObserverRegistry() {
        ActivityTaskManagerInternal amtInternal =
                LocalServices.getService(ActivityTaskManagerInternal.class);
        ActivityMetricsLaunchObserverRegistry launchObserverRegistry =
                amtInternal.getLaunchObserverRegistry();
        return launchObserverRegistry;
    }

    @VisibleForTesting
    protected IIorap provideIorapRemote() {
        IIorap iorap;
        try {
            iorap = IIorap.Stub.asInterface(ServiceManager.getServiceOrThrow("iorapd"));
        } catch (ServiceManager.ServiceNotFoundException e) {
            handleRemoteError(e);
            return null;
        }

        try {
            iorap.asBinder().linkToDeath(provideDeathRecipient(), /*flags*/0);
        } catch (RemoteException e) {
            handleRemoteError(e);
            return null;
        }

        return iorap;
    }

    @VisibleForTesting
    protected DeathRecipient provideDeathRecipient() {
        return new DeathRecipient() {
            @Override
            public void binderDied() {
                Log.w(TAG, "iorapd has died");
                retryConnectToRemoteAndConfigure(/*attempts*/0);

                if (mJobService != null) {
                    mJobService.onIorapdDisconnected();
                }
            }
        };
    }

    @VisibleForTesting
    protected boolean isIorapEnabled() {
        // These two mendel flags should match those in iorapd native process
        // system/iorapd/src/common/property.h
        boolean isTracingEnabled =
            getMendelFlag("iorap_perfetto_enable", "iorapd.perfetto.enable", false);
        boolean isReadAheadEnabled =
            getMendelFlag("iorap_readahead_enable", "iorapd.readahead.enable", false);
        // Same as the property in iorapd.rc -- disabling this will mean the 'iorapd' binder process
        // never comes up, so all binder connections will fail indefinitely.
        return IS_ENABLED && (isTracingEnabled || isReadAheadEnabled);
    }

    private boolean getMendelFlag(String mendelFlag, String sysProperty, boolean defaultValue) {
        // TODO(yawanng) use DeviceConfig to get mendel property.
        // DeviceConfig doesn't work and the reason is not clear.
        // Provider service is already up before IORapForwardService.
        String mendelProperty = "persist.device_config."
            + DeviceConfig.NAMESPACE_RUNTIME_NATIVE_BOOT
            + "."
            + mendelFlag;
        return SystemProperties.getBoolean(mendelProperty,
            SystemProperties.getBoolean(sysProperty, defaultValue));
    }

    //</editor-fold>

    @Override
    public void onStart() {
        if (DEBUG) {
            Log.v(TAG, "onStart");
        }

        retryConnectToRemoteAndConfigure(/*attempts*/0);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            if (DEBUG) {
                Log.v(TAG, "onBootPhase(PHASE_BOOT_COMPLETED)");
            }

            if (isIorapEnabled()) {
                // Set up a recurring background job. This has to be done in a later phase since it
                // has a dependency the job scheduler.
                //
                // Doing this too early can result in a ServiceNotFoundException for 'jobservice'
                // or a null reference for #getSystemService(JobScheduler.class)
                mJobService = new IorapdJobService(getContext());
            }
        }
    }

    private class BinderConnectionHandler extends Handler {
        public BinderConnectionHandler(android.os.Looper looper) {
            super(looper);
        }

        public static final int MESSAGE_BINDER_CONNECT = 0;

        private int mAttempts = 0;

        @Override
        public void handleMessage(android.os.Message message) {
           switch (message.what) {
               case MESSAGE_BINDER_CONNECT:
                   if (!retryConnectToRemoteAndConfigure(mAttempts)) {
                       mAttempts++;
                   } else {
                       mAttempts = 0;
                   }
                   break;
               default:
                   throw new AssertionError("Unknown message: " + message.toString());
           }
        }
    }

    /**
     * Handle iorapd shutdowns and crashes, by attempting to reconnect
     * until the service is reached again.
     *
     * <p>The first connection attempt is synchronous,
     * subsequent attempts are done by posting delayed tasks to the IoThread.</p>
     *
     * @return true if connection succeeded now, or false if it failed now [and needs to requeue].
     */
    private boolean retryConnectToRemoteAndConfigure(int attempts) {
        final int sleepTime = 1000;  // ms

        if (DEBUG) {
            Log.v(TAG, "retryConnectToRemoteAndConfigure - attempt #" + attempts);
        }

        if (connectToRemoteAndConfigure()) {
            return true;
        }

        // Either 'iorapd' is stuck in a crash loop (ouch!!) or we manually
        // called 'adb shell stop iorapd' , which means this would loop until it comes back
        // up.
        //
        // TODO: it would be good to get nodified of 'adb shell stop iorapd' to avoid
        // printing this warning.
        if (DEBUG) {
            Log.v(TAG, "Failed to connect to iorapd, is it down? Delay for " + sleepTime);
        }

        // Use a handler instead of Thread#sleep to avoid backing up the binder thread
        // when this is called from the death recipient callback.
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(BinderConnectionHandler.MESSAGE_BINDER_CONNECT),
                sleepTime);

        return false;

        // Log.e(TAG, "Can't connect to iorapd - giving up after " + attempts + " attempts");
    }

    private boolean connectToRemoteAndConfigure() {
        synchronized (mLock) {
            // Synchronize against any concurrent calls to this via the DeathRecipient.
            return connectToRemoteAndConfigureLocked();
        }
    }

    private boolean connectToRemoteAndConfigureLocked() {
        if (!isIorapEnabled()) {
            if (DEBUG) {
                Log.v(TAG, "connectToRemoteAndConfigure - iorapd is disabled, skip rest of work");
            }
            // When we see that iorapd is disabled (when system server comes up),
            // it stays disabled permanently until the next system server reset.

            // TODO: consider listening to property changes as a callback, then we can
            // be more dynamic about handling enable/disable.
            return true;
        }

        // Connect to the native binder service.
        mIorapRemote = provideIorapRemote();
        if (mIorapRemote == null) {
            if (DEBUG) {
                Log.e(TAG, "connectToRemoteAndConfigure - null iorap remote. check for Log.wtf?");
            }
            return false;
        }
        invokeRemote(mIorapRemote,
            (IIorap remote) -> remote.setTaskListener(new RemoteTaskListener()) );
        registerInProcessListenersLocked();

        Log.i(TAG, "Connected to iorapd native service.");

        return true;
    }

    private final AppLaunchObserver mAppLaunchObserver = new AppLaunchObserver();
    private final EventSequenceValidator mEventSequenceValidator = new EventSequenceValidator();
    private final DexOptPackagesUpdated mDexOptPackagesUpdated = new DexOptPackagesUpdated();
    private boolean mRegisteredListeners = false;

    private void registerInProcessListenersLocked() {
        if (mRegisteredListeners) {
            // Listeners are registered only once (idempotent operation).
            //
            // Today listeners are tolerant of the remote side going away
            // by handling remote errors.
            //
            // We could try to 'unregister' the listener when we get a binder disconnect,
            // but we'd still have to handle the case of encountering synchronous errors so
            // it really wouldn't be a win (other than having less log spew).
            return;
        }

        // Listen to App Launch Sequence events from ActivityTaskManager,
        // and forward them to the native binder service.
        ActivityMetricsLaunchObserverRegistry launchObserverRegistry =
                provideLaunchObserverRegistry();
        launchObserverRegistry.registerLaunchObserver(mAppLaunchObserver);
        launchObserverRegistry.registerLaunchObserver(mEventSequenceValidator);

        BackgroundDexOptService.addPackagesUpdatedListener(mDexOptPackagesUpdated);


        mRegisteredListeners = true;
    }

    private class DexOptPackagesUpdated implements BackgroundDexOptService.PackagesUpdatedListener {
        @Override
        public void onPackagesUpdated(ArraySet<String> updatedPackages) {
            String[] updated = updatedPackages.toArray(new String[0]);
            for (String packageName : updated) {
                Log.d(TAG, "onPackagesUpdated: " + packageName);
                invokeRemote(mIorapRemote,
                    (IIorap remote) ->
                        remote.onDexOptEvent(RequestId.nextValueForSequence(),
                                DexOptEvent.createPackageUpdate(packageName))
                );
            }
        }
    }

    private class AppLaunchObserver implements ActivityMetricsLaunchObserver {
        // We add a synthetic sequence ID here to make it easier to differentiate new
        // launch sequences on the native side.
        private @AppLaunchEvent.SequenceId long mSequenceId = -1;

        // All callbacks occur on the same background thread. Don't synchronize explicitly.

        @Override
        public void onIntentStarted(@NonNull Intent intent, long timestampNs) {
            // #onIntentStarted [is the only transition that] initiates a new launch sequence.
            ++mSequenceId;

            if (DEBUG) {
                Log.v(TAG, String.format("AppLaunchObserver#onIntentStarted(%d, %s, %d)",
                        mSequenceId, intent, timestampNs));
            }

            invokeRemote(mIorapRemote,
                (IIorap remote) ->
                    remote.onAppLaunchEvent(RequestId.nextValueForSequence(),
                        new AppLaunchEvent.IntentStarted(mSequenceId, intent, timestampNs))
            );
        }

        @Override
        public void onIntentFailed() {
            if (DEBUG) {
                Log.v(TAG, String.format("AppLaunchObserver#onIntentFailed(%d)", mSequenceId));
            }

            invokeRemote(mIorapRemote,
                (IIorap remote) ->
                    remote.onAppLaunchEvent(RequestId.nextValueForSequence(),
                        new AppLaunchEvent.IntentFailed(mSequenceId))
            );
        }

        @Override
        public void onActivityLaunched(@NonNull @ActivityRecordProto byte[] activity,
                @Temperature int temperature) {
            if (DEBUG) {
                Log.v(TAG, String.format("AppLaunchObserver#onActivityLaunched(%d, %s, %d)",
                        mSequenceId, activity, temperature));
            }

            invokeRemote(mIorapRemote,
                (IIorap remote) ->
                    remote.onAppLaunchEvent(RequestId.nextValueForSequence(),
                            new AppLaunchEvent.ActivityLaunched(mSequenceId, activity, temperature))
            );
        }

        @Override
        public void onActivityLaunchCancelled(@Nullable @ActivityRecordProto byte[] activity) {
            if (DEBUG) {
                Log.v(TAG, String.format("AppLaunchObserver#onActivityLaunchCancelled(%d, %s)",
                        mSequenceId, activity));
            }

            invokeRemote(mIorapRemote,
                (IIorap remote) ->
                    remote.onAppLaunchEvent(RequestId.nextValueForSequence(),
                            new AppLaunchEvent.ActivityLaunchCancelled(mSequenceId,
                                    activity)));
        }

        @Override
        public void onActivityLaunchFinished(@NonNull @ActivityRecordProto byte[] activity,
            long timestampNs) {
            if (DEBUG) {
                Log.v(TAG, String.format("AppLaunchObserver#onActivityLaunchFinished(%d, %s, %d)",
                        mSequenceId, activity, timestampNs));
            }

            invokeRemote(mIorapRemote,
                (IIorap remote) ->
                    remote.onAppLaunchEvent(RequestId.nextValueForSequence(),
                        new AppLaunchEvent.ActivityLaunchFinished(mSequenceId,
                            activity,
                            timestampNs))
            );
        }

        @Override
        public void onReportFullyDrawn(@NonNull @ActivityRecordProto byte[] activity,
            long timestampNs) {
            if (DEBUG) {
                Log.v(TAG, String.format("AppLaunchObserver#onReportFullyDrawn(%d, %s, %d)",
                        mSequenceId, activity, timestampNs));
            }

            invokeRemote(mIorapRemote,
                (IIorap remote) ->
                    remote.onAppLaunchEvent(RequestId.nextValueForSequence(),
                        new AppLaunchEvent.ReportFullyDrawn(mSequenceId, activity, timestampNs))
            );
        }
    }

    /**
     * Debugging:
     *
     * $> adb shell dumpsys jobscheduler
     *
     * Search for 'IorapdJobServiceProxy'.
     *
     *   JOB #1000/283673059: 6e54ed android/com.google.android.startop.iorap.IorapForwardingService$IorapdJobServiceProxy
     *   ^    ^                      ^
     *   (uid, job id)               ComponentName(package/class)
     *
     * Forcing the job to be run, ignoring constraints:
     *
     * $> adb shell cmd jobscheduler run -f android 283673059
     *                                      ^        ^
     *                                      package  job_id
     *
     * ------------------------------------------------------------
     *
     * This class is instantiated newly by the JobService every time
     * it wants to run a new job.
     *
     * We need to forward invocations to the current running instance of
     * IorapForwardingService#IorapdJobService.
     *
     * Visibility: Must be accessible from android.app.AppComponentFactory
     */
    public static class IorapdJobServiceProxy extends JobService {

        public IorapdJobServiceProxy() {
            getActualIorapdJobService().bindProxy(this);
        }


        @NonNull
        private IorapdJobService getActualIorapdJobService() {
            // Can't ever be null, because the guarantee is that the
            // IorapForwardingService is always running.
            // We are in the same process as Job Service.
            return sSelfService.mJobService;
        }

        // Called by system to start the job.
        @Override
        public boolean onStartJob(JobParameters params) {
            return getActualIorapdJobService().onStartJob(params);
        }

        // Called by system to prematurely stop the job.
        @Override
        public boolean onStopJob(JobParameters params) {
            return getActualIorapdJobService().onStopJob(params);
        }
    }

    private class IorapdJobService extends JobService {
        private final ComponentName IORAPD_COMPONENT_NAME;

        private final Object mLock = new Object();
        // Jobs currently running remotely on iorapd.
        // They were started by the JobScheduler and need to be finished.
        private final HashMap<RequestId, JobParameters> mRunningJobs = new HashMap<>();

        private final JobInfo IORAPD_JOB_INFO;

        private volatile IorapdJobServiceProxy mProxy;

        public void bindProxy(IorapdJobServiceProxy proxy) {
            mProxy = proxy;
        }

        // Create a new job service which immediately schedules a 24-hour idle maintenance mode
        // background job to execute.
        public IorapdJobService(Context context) {
            if (DEBUG) {
                Log.v(TAG, "IorapdJobService (Context=" + context.toString() + ")");
            }

            // Schedule the proxy class to be instantiated by the JobScheduler
            // when it is time to invoke background jobs for IorapForwardingService.


            // This also needs a BIND_JOB_SERVICE permission in
            // frameworks/base/core/res/AndroidManifest.xml
            IORAPD_COMPONENT_NAME = new ComponentName(context, IorapdJobServiceProxy.class);

            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID_IORAPD, IORAPD_COMPONENT_NAME);
            builder.setPeriodic(JOB_INTERVAL_MS);
            builder.setPrefetch(true);

            builder.setRequiresCharging(true);
            builder.setRequiresDeviceIdle(true);

            builder.setRequiresStorageNotLow(true);

            IORAPD_JOB_INFO = builder.build();

            JobScheduler js = context.getSystemService(JobScheduler.class);
            js.schedule(IORAPD_JOB_INFO);
            Log.d(TAG,
                    "BgJob Scheduled (jobId=" + JOB_ID_IORAPD
                            + ", interval: " + JOB_INTERVAL_MS + "ms)");
        }

        // Called by system to start the job.
        @Override
        public boolean onStartJob(JobParameters params) {
            // Tell iorapd to start a background job.
            Log.d(TAG, "Starting background job: " + params.toString());

            // We wait until that job's sequence ID returns to us with 'Completed',
            RequestId request;
            synchronized (mLock) {
                // TODO: would be cleaner if we got the request from the 'invokeRemote' function.
                // Better yet, consider a Pair<RequestId, Future<TaskResult>> or similar.
                request = RequestId.nextValueForSequence();
                mRunningJobs.put(request, params);
            }

            if (!invokeRemote(mIorapRemote, (IIorap remote) ->
                    remote.onJobScheduledEvent(request,
                            JobScheduledEvent.createIdleMaintenance(
                                    JobScheduledEvent.TYPE_START_JOB,
                                    params))
            )) {
                synchronized (mLock) {
                    mRunningJobs.remove(request); // Avoid memory leaks.
                }

                // Something went wrong on the remote side. Treat the job as being
                // 'already finished' (i.e. immediately release wake lock).
                return false;
            }

            // True -> keep the wakelock acquired until #jobFinished is called.
            return true;
        }

        // Called by system to prematurely stop the job.
        @Override
        public boolean onStopJob(JobParameters params) {
            // As this is unexpected behavior, print a warning.
            Log.w(TAG, "onStopJob(params=" + params.toString() + ")");

            // No longer track this job (avoids a memory leak).
            boolean wasTracking = false;
            synchronized (mLock) {
                for (HashMap.Entry<RequestId, JobParameters> entry : mRunningJobs.entrySet()) {
                   if (entry.getValue().getJobId() == params.getJobId()) {
                       mRunningJobs.remove(entry.getKey());
                       wasTracking = true;
                   }
                }
            }

            // Notify iorapd to stop (abort) the job.
            if (wasTracking) {
                invokeRemote(mIorapRemote, (IIorap remote) ->
                        remote.onJobScheduledEvent(RequestId.nextValueForSequence(),
                                JobScheduledEvent.createIdleMaintenance(
                                        JobScheduledEvent.TYPE_STOP_JOB,
                                        params))
                );
            } else {
                // Even weirder. This could only be considered "correct" if iorapd reported success
                // concurrently to the JobService requesting an onStopJob.
                Log.e(TAG, "Untracked onStopJob request");  // see above Log.w for the params.
            }


            // Yes, retry the job at a later time no matter what.
            return true;
        }

        // Listen to *all* task completes for all requests.
        // The majority of these might be unrelated to background jobs.
        public void onIorapdTaskCompleted(RequestId requestId) {
            JobParameters jobParameters;
            synchronized (mLock) {
                jobParameters = mRunningJobs.remove(requestId);
            }

            // Typical case: This was a task callback unrelated to our jobs.
            if (jobParameters == null) {
                return;
            }

            if (DEBUG) {
                Log.v(TAG,
                        String.format("IorapdJobService#onIorapdTaskCompleted(%s), found params=%s",
                                requestId, jobParameters));
            }

            Log.d(TAG, "Finished background job: " + jobParameters.toString());

            // Job is successful and periodic. Do not 'reschedule' according to the back-off
            // criteria.
            //
            // This releases the wakelock that was acquired in #onStartJob.

            IorapdJobServiceProxy proxy = mProxy;
            if (proxy != null) {
                proxy.jobFinished(jobParameters, /*reschedule*/false);
            }
            // Cannot call 'jobFinished' on 'this' because it was not constructed
            // from the JobService, so it would get an NPE when calling mEngine.
        }

        public void onIorapdDisconnected() {
            synchronized (mLock) {
                mRunningJobs.clear();
            }

            if (DEBUG) {
                Log.v(TAG, String.format("IorapdJobService#onIorapdDisconnected"));
            }

            // TODO: should we try to resubmit all incomplete jobs after it's reconnected?
        }
    }

    private class RemoteTaskListener extends ITaskListener.Stub {
        @Override
        public void onProgress(RequestId requestId, TaskResult result) throws RemoteException {
            if (DEBUG) {
                Log.v(TAG,
                        String.format("RemoteTaskListener#onProgress(%s, %s)", requestId, result));
            }

            // TODO: implement rest.
        }

        @Override
        public void onComplete(RequestId requestId, TaskResult result) throws RemoteException {
            if (DEBUG) {
                Log.v(TAG,
                        String.format("RemoteTaskListener#onComplete(%s, %s)", requestId, result));
            }

            if (mJobService != null) {
                mJobService.onIorapdTaskCompleted(requestId);
            }

            // TODO: implement rest.
        }
    }

    /** Allow passing lambdas to #invokeRemote */
    private interface RemoteRunnable {
        // TODO: run(RequestId) ?
        void run(IIorap iorap) throws RemoteException;
    }

    // Always pass in the iorap directly here to avoid data race.
    private static boolean invokeRemote(IIorap iorap, RemoteRunnable r) {
       if (iorap == null) {
         Log.w(TAG, "IIorap went to null in this thread, drop invokeRemote.");
         return false;
       }
       try {
           r.run(iorap);
           return true;
       } catch (RemoteException e) {
           // This could be a logic error (remote side returning error), which we need to fix.
           //
           // This could also be a DeadObjectException in which case its probably just iorapd
           // being manually restarted.
           //
           // Don't make any assumption, since DeadObjectException could also mean iorapd crashed
           // unexpectedly.
           //
           // DeadObjectExceptions are recovered from using DeathRecipient and #linkToDeath.
           handleRemoteError(e);
           return false;
       }
    }

    private static void handleRemoteError(Throwable t) {
        if (WTF_CRASH) {
            // In development modes, we just want to crash.
            throw new AssertionError("unexpected remote error", t);
        } else {
            // Log to wtf which gets sent to dropbox, and in system_server this does not crash.
            Log.wtf(TAG, t);
        }
    }

    // Encode A-Z bitstring into bits. Every character is bits.
    // Characters outside of the range [a,z] are considered out of range.
    //
    // The least significant bits hold the last character.
    // First 2 bits are left as 0.
    private static int encodeEnglishAlphabetStringIntoInt(String name) {
        int value = 0;

        final int CHARS_PER_INT = 6;
        final int BITS_PER_CHAR = 5;
        // Note: 2 top bits are unused, this also means our values are non-negative.
        final char CHAR_LOWER = 'a';
        final char CHAR_UPPER = 'z';

        if (name.length() > CHARS_PER_INT) {
            throw new IllegalArgumentException(
                    "String too long. Cannot encode more than 6 chars: " + name);
        }

        for (int i = 0; i < name.length(); ++i) {
           char c = name.charAt(i);

           if (c < CHAR_LOWER || c > CHAR_UPPER) {
               throw new IllegalArgumentException("String has out-of-range [a-z] chars: " + name);
           }

           // Avoid sign extension during promotion.
           int cur_value = (c & 0xFFFF) - (CHAR_LOWER & 0xFFFF);
           if (cur_value >= (1 << BITS_PER_CHAR)) {
               throw new AssertionError("wtf? i=" + i + ", name=" + name);
           }

           value = value << BITS_PER_CHAR;
           value = value | cur_value;
        }

        return value;
    }
}
