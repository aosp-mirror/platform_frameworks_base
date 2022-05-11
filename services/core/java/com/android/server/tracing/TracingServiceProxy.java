/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.server.tracing;

import static com.android.internal.util.FrameworkStatsLog.TRACING_SERVICE_REPORT_EVENT;
import static com.android.internal.util.FrameworkStatsLog.TRACING_SERVICE_REPORT_EVENT__EVENT__TRACING_SERVICE_REPORT_BEGIN;
import static com.android.internal.util.FrameworkStatsLog.TRACING_SERVICE_REPORT_EVENT__EVENT__TRACING_SERVICE_REPORT_BIND_PERM_INCORRECT;
import static com.android.internal.util.FrameworkStatsLog.TRACING_SERVICE_REPORT_EVENT__EVENT__TRACING_SERVICE_REPORT_SVC_COMM_ERROR;
import static com.android.internal.util.FrameworkStatsLog.TRACING_SERVICE_REPORT_EVENT__EVENT__TRACING_SERVICE_REPORT_SVC_HANDOFF;
import static com.android.internal.util.FrameworkStatsLog.TRACING_SERVICE_REPORT_EVENT__EVENT__TRACING_SERVICE_REPORT_SVC_PERM_MISSING;

import android.Manifest;
import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IMessenger;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.os.UserHandle;
import android.service.tracing.TraceReportService;
import android.tracing.ITracingServiceProxy;
import android.tracing.TraceReportParams;
import android.util.Log;
import android.util.LruCache;
import android.util.Slog;

import com.android.internal.infra.ServiceConnector;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.SystemService;

import java.io.IOException;

/**
 * TracingServiceProxy is the system_server intermediary between the Perfetto tracing daemon and the
 * other components (e.g. system tracing app Traceur, trace reporting apps).
 *
 * Access to this service is restricted via SELinux. Normal apps do not have access.
 *
 * @hide
 */
public class TracingServiceProxy extends SystemService {
    public static final String TRACING_SERVICE_PROXY_BINDER_NAME = "tracing.proxy";
    private static final String TAG = "TracingServiceProxy";
    private static final String TRACING_APP_PACKAGE_NAME = "com.android.traceur";
    private static final String TRACING_APP_ACTIVITY = "com.android.traceur.StopTraceService";

    private static final int MAX_CACHED_REPORTER_SERVICES = 8;

    // The maximum size of the trace allowed if the option |usePipeForTesting| is set.
    // Note: this size MUST be smaller than the buffer size of the pipe (i.e. what you can
    // write to the pipe without blocking) to avoid system_server blocking on this.
    // (on Linux, the minimum value is 4K i.e. 1 minimally sized page).
    private static final int MAX_FILE_SIZE_BYTES_TO_PIPE = 1024;

    // Keep this in sync with the definitions in TraceService
    private static final String INTENT_ACTION_NOTIFY_SESSION_STOPPED =
            "com.android.traceur.NOTIFY_SESSION_STOPPED";
    private static final String INTENT_ACTION_NOTIFY_SESSION_STOLEN =
            "com.android.traceur.NOTIFY_SESSION_STOLEN";

    private static final int REPORT_BEGIN =
            TRACING_SERVICE_REPORT_EVENT__EVENT__TRACING_SERVICE_REPORT_BEGIN;
    private static final int REPORT_SVC_HANDOFF =
            TRACING_SERVICE_REPORT_EVENT__EVENT__TRACING_SERVICE_REPORT_SVC_HANDOFF;
    private static final int REPORT_BIND_PERM_INCORRECT =
            TRACING_SERVICE_REPORT_EVENT__EVENT__TRACING_SERVICE_REPORT_BIND_PERM_INCORRECT;
    private static final int REPORT_SVC_PERM_MISSING =
            TRACING_SERVICE_REPORT_EVENT__EVENT__TRACING_SERVICE_REPORT_SVC_PERM_MISSING;
    private static final int REPORT_SVC_COMM_ERROR =
            TRACING_SERVICE_REPORT_EVENT__EVENT__TRACING_SERVICE_REPORT_SVC_COMM_ERROR;

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final LruCache<ComponentName, ServiceConnector<IMessenger>> mCachedReporterServices;

    private final ITracingServiceProxy.Stub mTracingServiceProxy = new ITracingServiceProxy.Stub() {
        /**
         * Notifies system tracing app that a tracing session has ended. If a session is repurposed
         * for use in a bugreport, sessionStolen can be set to indicate that tracing has ended but
         * there is no buffer available to dump.
         */
        @Override
        public void notifyTraceSessionEnded(boolean sessionStolen) {
            TracingServiceProxy.this.notifyTraceur(sessionStolen);
        }

        @Override
        public void reportTrace(@NonNull TraceReportParams params) {
            TracingServiceProxy.this.reportTrace(params);
        }
    };

    public TracingServiceProxy(Context context) {
        super(context);
        mContext = context;
        mPackageManager = context.getPackageManager();
        mCachedReporterServices = new LruCache<>(MAX_CACHED_REPORTER_SERVICES);
    }

    @Override
    public void onStart() {
        publishBinderService(TRACING_SERVICE_PROXY_BINDER_NAME, mTracingServiceProxy);
    }

    private void notifyTraceur(boolean sessionStolen) {
        final Intent intent = new Intent();

        try {
            // Validate that Traceur is a system app.
            PackageInfo info = mPackageManager.getPackageInfo(TRACING_APP_PACKAGE_NAME,
                    PackageManager.MATCH_SYSTEM_ONLY);

            intent.setClassName(info.packageName, TRACING_APP_ACTIVITY);
            if (sessionStolen) {
                intent.setAction(INTENT_ACTION_NOTIFY_SESSION_STOLEN);
            } else {
                intent.setAction(INTENT_ACTION_NOTIFY_SESSION_STOPPED);
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                mContext.startForegroundServiceAsUser(intent, UserHandle.SYSTEM);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to notifyTraceSessionEnded", e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

        } catch (NameNotFoundException e) {
            Log.e(TAG, "Failed to locate Traceur", e);
        }
    }

    private void reportTrace(@NonNull TraceReportParams params) {
        FrameworkStatsLog.write(TRACING_SERVICE_REPORT_EVENT, REPORT_BEGIN,
                params.uuidLsb, params.uuidMsb);

        // We don't need to do any permission checks on the caller because access
        // to this service is guarded by SELinux.
        ComponentName component = new ComponentName(params.reporterPackageName,
                params.reporterClassName);
        if (!hasBindServicePermission(component)) {
            FrameworkStatsLog.write(TRACING_SERVICE_REPORT_EVENT, REPORT_BIND_PERM_INCORRECT,
                    params.uuidLsb, params.uuidMsb);
            return;
        }
        boolean hasDumpPermission = hasPermission(component, Manifest.permission.DUMP);
        boolean hasUsageStatsPermission = hasPermission(component,
                Manifest.permission.PACKAGE_USAGE_STATS);
        if (!hasDumpPermission || !hasUsageStatsPermission) {
            FrameworkStatsLog.write(TRACING_SERVICE_REPORT_EVENT, REPORT_SVC_PERM_MISSING,
                    params.uuidLsb, params.uuidMsb);
            return;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            reportTrace(getOrCreateReporterService(component), params);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void reportTrace(
            @NonNull ServiceConnector<IMessenger> reporterService,
            @NonNull TraceReportParams params) {
        reporterService.post(messenger -> {
            if (params.usePipeForTesting) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                try (AutoCloseInputStream i = new AutoCloseInputStream(params.fd)) {
                    try (AutoCloseOutputStream o = new AutoCloseOutputStream(pipe[1])) {
                        byte[] array = i.readNBytes(MAX_FILE_SIZE_BYTES_TO_PIPE);
                        if (array.length == MAX_FILE_SIZE_BYTES_TO_PIPE) {
                            throw new IllegalArgumentException(
                                    "Trace file too large when |usePipeForTesting| is set.");
                        }
                        o.write(array);
                    }
                }
                params.fd = pipe[0];
            }

            Message message = Message.obtain();
            message.what = TraceReportService.MSG_REPORT_TRACE;
            message.obj = params;
            messenger.send(message);

            FrameworkStatsLog.write(TRACING_SERVICE_REPORT_EVENT, REPORT_SVC_HANDOFF,
                    params.uuidLsb, params.uuidMsb);
        }).whenComplete((res, err) -> {
            if (err != null) {
                FrameworkStatsLog.write(TRACING_SERVICE_REPORT_EVENT, REPORT_SVC_COMM_ERROR,
                        params.uuidLsb, params.uuidMsb);
                Slog.e(TAG, "Failed to report trace", err);
            }
            try {
                params.fd.close();
            } catch (IOException ignored) {
            }
        });
    }

    private ServiceConnector<IMessenger> getOrCreateReporterService(
            @NonNull ComponentName component) {
        ServiceConnector<IMessenger> connector = mCachedReporterServices.get(component);
        if (connector == null) {
            Intent intent = new Intent();
            intent.setComponent(component);
            connector = new ServiceConnector.Impl<IMessenger>(
                    mContext, intent,
                    Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY,
                    mContext.getUser().getIdentifier(), IMessenger.Stub::asInterface) {
                private static final long DISCONNECT_TIMEOUT_MS = 15_000;
                private static final long REQUEST_TIMEOUT_MS = 10_000;

                @Override
                protected long getAutoDisconnectTimeoutMs() {
                    return DISCONNECT_TIMEOUT_MS;
                }

                @Override
                protected long getRequestTimeoutMs() {
                    return REQUEST_TIMEOUT_MS;
                }
            };
            mCachedReporterServices.put(intent.getComponent(), connector);
        }
        return connector;
    }

    private boolean hasPermission(@NonNull ComponentName componentName,
            @NonNull String permission) throws SecurityException {
        if (mPackageManager.checkPermission(permission, componentName.getPackageName())
                != PackageManager.PERMISSION_GRANTED) {
            Slog.e(TAG,
                    "Trace reporting service " + componentName.toShortString() + " does not have "
                            + permission + " permission");
            return false;
        }
        return true;
    }

    private boolean hasBindServicePermission(@NonNull ComponentName componentName) {
        ServiceInfo info;
        try {
            info = mPackageManager.getServiceInfo(componentName, 0);
        } catch (NameNotFoundException ex) {
            Slog.e(TAG,
                    "Trace reporting service " + componentName.toShortString() + " does not exist");
            return false;
        }
        if (!Manifest.permission.BIND_TRACE_REPORT_SERVICE.equals(info.permission)) {
            Slog.e(TAG,
                    "Trace reporting service " + componentName.toShortString()
                            + " does not request " + Manifest.permission.BIND_TRACE_REPORT_SERVICE
                            + " permission; instead requests " + info.permission);
            return false;
        }
        return true;
    }
}
