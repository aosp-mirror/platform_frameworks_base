/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.server.pm;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Process;
import android.os.ServiceManager;
import android.util.ByteStringUtils;
import android.util.EventLog;
import android.util.Log;

import com.android.server.pm.dex.DexLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scheduled jobs related to logging of app dynamic code loading. The idle logging job runs daily
 * while idle and charging  and calls {@link DexLogger} to write dynamic code information to the
 * event log. The audit watching job scans the event log periodically while idle to find AVC audit
 * messages indicating use of dynamic native code and adds the information to {@link DexLogger}.
 * {@hide}
 */
public class DynamicCodeLoggingService extends JobService {
    private static final String TAG = DynamicCodeLoggingService.class.getName();

    private static final boolean DEBUG = false;

    private static final int IDLE_LOGGING_JOB_ID = 2030028;
    private static final int AUDIT_WATCHING_JOB_ID = 203142925;

    private static final long IDLE_LOGGING_PERIOD_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final long AUDIT_WATCHING_PERIOD_MILLIS = TimeUnit.HOURS.toMillis(2);

    private static final int AUDIT_AVC = 1400;  // Defined in linux/audit.h
    private static final String AVC_PREFIX = "type=" + AUDIT_AVC + " ";

    private static final Pattern EXECUTE_NATIVE_AUDIT_PATTERN =
            Pattern.compile(".*\\bavc: granted \\{ execute(?:_no_trans|) \\} .*"
                    + "\\bpath=(?:\"([^\" ]*)\"|([0-9A-F]+)) .*"
                    + "\\bscontext=u:r:untrusted_app_2(?:5|7):.*"
                    + "\\btcontext=u:object_r:app_data_file:.*"
                    + "\\btclass=file\\b.*");

    private volatile boolean mIdleLoggingStopRequested = false;
    private volatile boolean mAuditWatchingStopRequested = false;

    /**
     * Schedule our jobs with the {@link JobScheduler}.
     */
    public static void schedule(Context context) {
        ComponentName serviceName = new ComponentName(
                "android", DynamicCodeLoggingService.class.getName());

        JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(IDLE_LOGGING_JOB_ID, serviceName)
                .setRequiresDeviceIdle(true)
                .setRequiresCharging(true)
                .setPeriodic(IDLE_LOGGING_PERIOD_MILLIS)
                .build());
        js.schedule(new JobInfo.Builder(AUDIT_WATCHING_JOB_ID, serviceName)
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .setPeriodic(AUDIT_WATCHING_PERIOD_MILLIS)
                .build());

        if (DEBUG) {
            Log.d(TAG, "Jobs scheduled");
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        int jobId = params.getJobId();
        if (DEBUG) {
            Log.d(TAG, "onStartJob " + jobId);
        }
        switch (jobId) {
            case IDLE_LOGGING_JOB_ID:
                mIdleLoggingStopRequested = false;
                new IdleLoggingThread(params).start();
                return true;  // Job is running on another thread
            case AUDIT_WATCHING_JOB_ID:
                mAuditWatchingStopRequested = false;
                new AuditWatchingThread(params).start();
                return true;  // Job is running on another thread
            default:
                // Shouldn't happen, but indicate nothing is running.
                return false;
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        int jobId = params.getJobId();
        if (DEBUG) {
            Log.d(TAG, "onStopJob " + jobId);
        }
        switch (jobId) {
            case IDLE_LOGGING_JOB_ID:
                mIdleLoggingStopRequested = true;
                return true;  // Requests job be re-scheduled.
            case AUDIT_WATCHING_JOB_ID:
                mAuditWatchingStopRequested = true;
                return true;  // Requests job be re-scheduled.
            default:
                return false;
        }
    }

    private static DexLogger getDexLogger() {
        PackageManagerService pm = (PackageManagerService) ServiceManager.getService("package");
        return pm.getDexManager().getDexLogger();
    }

    private class IdleLoggingThread extends Thread {
        private final JobParameters mParams;

        IdleLoggingThread(JobParameters params) {
            super("DynamicCodeLoggingService_IdleLoggingJob");
            mParams = params;
        }

        @Override
        public void run() {
            if (DEBUG) {
                Log.d(TAG, "Starting IdleLoggingJob run");
            }

            DexLogger dexLogger = getDexLogger();
            for (String packageName : dexLogger.getAllPackagesWithDynamicCodeLoading()) {
                if (mIdleLoggingStopRequested) {
                    Log.w(TAG, "Stopping IdleLoggingJob run at scheduler request");
                    return;
                }

                dexLogger.logDynamicCodeLoading(packageName);
            }

            jobFinished(mParams, /* reschedule */ false);
            if (DEBUG) {
                Log.d(TAG, "Finished IdleLoggingJob run");
            }
        }
    }

    private class AuditWatchingThread extends Thread {
        private final JobParameters mParams;

        AuditWatchingThread(JobParameters params) {
            super("DynamicCodeLoggingService_AuditWatchingJob");
            mParams = params;
        }

        @Override
        public void run() {
            if (DEBUG) {
                Log.d(TAG, "Starting AuditWatchingJob run");
            }

            if (processAuditEvents()) {
                jobFinished(mParams, /* reschedule */ false);
                if (DEBUG) {
                    Log.d(TAG, "Finished AuditWatchingJob run");
                }
            }
        }

        private boolean processAuditEvents() {
            // Scan the event log for SELinux (avc) audit messages indicating when an
            // (untrusted) app has executed native code from an app data
            // file. Matches are recorded in DexLogger.
            //
            // These messages come from the kernel audit system via logd. (Note that
            // some devices may not generate these messages at all, or the format may
            // be different, in which case nothing will be recorded.)
            //
            // The messages use the auditd tag and the uid of the app that executed
            // the code.
            //
            // A typical message might look like this:
            // type=1400 audit(0.0:521): avc: granted { execute } for comm="executable"
            //  path="/data/data/com.dummy.app/executable" dev="sda13" ino=1655302
            //  scontext=u:r:untrusted_app_27:s0:c66,c257,c512,c768
            //  tcontext=u:object_r:app_data_file:s0:c66,c257,c512,c768 tclass=file
            //
            // The information we want is the uid and the path. (Note this may be
            // either a quoted string, as shown above, or a sequence of hex-encoded
            // bytes.)
            //
            // On each run we process all the matching events in the log. This may
            // mean re-processing events we have already seen, and in any case there
            // may be duplicate events for the same app+file. These are de-duplicated
            // by DexLogger.
            //
            // Note that any app can write a message to the event log, including one
            // that looks exactly like an AVC audit message, so the information may
            // be spoofed by an app; in such a case the uid we see will be the app
            // that generated the spoof message.

            try {
                int[] tags = { EventLog.getTagCode("auditd") };
                if (tags[0] == -1) {
                    // auditd is not a registered tag on this system, so there can't be any messages
                    // of interest.
                    return true;
                }

                DexLogger dexLogger = getDexLogger();

                List<EventLog.Event> events = new ArrayList<>();
                EventLog.readEvents(tags, events);

                for (int i = 0; i < events.size(); ++i) {
                    if (mAuditWatchingStopRequested) {
                        Log.w(TAG, "Stopping AuditWatchingJob run at scheduler request");
                        return false;
                    }

                    EventLog.Event event = events.get(i);

                    // Discard clearly unrelated messages as quickly as we can.
                    int uid = event.getUid();
                    if (!Process.isApplicationUid(uid)) {
                        continue;
                    }
                    Object data = event.getData();
                    if (!(data instanceof String)) {
                        continue;
                    }
                    String message = (String) data;
                    if (!message.startsWith(AVC_PREFIX)) {
                        continue;
                    }

                    // And then use a regular expression to verify it's one of the messages we're
                    // interested in and to extract the path of the file being loaded.
                    Matcher matcher = EXECUTE_NATIVE_AUDIT_PATTERN.matcher(message);
                    if (!matcher.matches()) {
                        continue;
                    }
                    String path = matcher.group(1);
                    if (path == null) {
                        // If the path contains spaces or various weird characters the kernel
                        // hex-encodes the bytes; we need to undo that.
                        path = unhex(matcher.group(2));
                    }
                    dexLogger.recordNative(uid, path);
                }

                return true;
            } catch (Exception e) {
                Log.e(TAG, "AuditWatchingJob failed", e);
                return true;
            }
        }
    }

    private static String unhex(String hexEncodedPath) {
        byte[] bytes = ByteStringUtils.fromHexToByteArray(hexEncodedPath);
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return new String(bytes);
    }
}
